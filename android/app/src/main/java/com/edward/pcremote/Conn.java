package com.edward.pcremote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** The single connection to a PC (over Wi-Fi/hotspot or Bluetooth). */
public final class Conn {
    public static final int IDLE = 0, CONNECTING = 1, WAITING = 2, CONNECTED = 3;
    public static final byte JSON = 1, SCREEN = 2, DATA = 3, INPUT = 4;
    public static final int TCP_PORT = 47801;
    public static final UUID BT_UUID = UUID.fromString("6f3c2a10-9b1e-4c7d-a2f4-5e8b9c0d1a23");

    public interface Listener {
        default void onState(int state) {}
        default void onMessage(String type, JSONObject m) {}
    }

    public interface FrameSink { void onFrame(byte[] jpeg); }

    private static Conn instance;
    public static synchronized Conn get() {
        if (instance == null) instance = new Conn();
        return instance;
    }

    private Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler writer;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();

    public volatile int state = IDLE;
    public volatile String error = "";
    public volatile JSONObject pc = new JSONObject();
    public volatile String transport = "";
    public volatile int screenW = 1920, screenH = 1080;
    public volatile String pcVersion = "";
    /** What the connection is doing right now, shown on the home screen while connecting. */
    public volatile String stage = "";
    public volatile FrameSink frameSink;
    public static volatile String lastClip = null;

    private Closeable socket;
    private OutputStream out;
    private int generation = 0;
    private volatile long lastRx;
    private boolean userDisconnected;

    private Conn() {
        HandlerThread t = new HandlerThread("conn-writer");
        t.start();
        writer = new Handler(t.getLooper());
    }

    public void init(Context c) { if (app == null) app = c.getApplicationContext(); }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public boolean isConnected() { return state == CONNECTED; }
    /** True for Wi-Fi and hotspot (a network link); false for Bluetooth. */
    public boolean isWifi() { return !"bluetooth".equals(transport); }
    public String transportLabel() {
        return "bluetooth".equals(transport) ? "Bluetooth" : "hotspot".equals(transport) ? "hotspot" : "Wi-Fi";
    }
    public String pcName() { return pc.optString("name", "PC"); }

    private void setState(int s) {
        state = s;
        main.post(() -> { for (Listener l : listeners) l.onState(s); });
    }

    // ---------------------------------------------------------------- connecting

    public void connect(JSONObject target, String how) {
        disconnectQuietly();
        final int gen;
        synchronized (this) { gen = ++generation; }
        pc = target;
        transport = how;
        error = "";
        userDisconnected = false;
        stage = "bluetooth".equals(how) ? "Connecting over Bluetooth…"
                : "Reaching " + target.optString("name", "the PC") + " at " + target.optString("ip") + "…";
        setState(CONNECTING);
        new Thread(() -> open(gen, target, how), "conn-open").start();
    }

    @SuppressLint("MissingPermission")
    private void open(int gen, JSONObject target, String how) {
        try {
            InputStream in;
            Closeable sock;
            if ("bluetooth".equals(how)) {
                BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
                if (ad == null) throw new IOException("This phone has no Bluetooth.");
                if (!ad.isEnabled()) throw new IOException("Bluetooth is off. Turn it on and try again.");
                BluetoothDevice dev = ad.getRemoteDevice(target.optString("bt").toUpperCase());
                try { ad.cancelDiscovery(); } catch (Exception ignored) {}
                BluetoothSocket bs = dev.createRfcommSocketToServiceRecord(BT_UUID);
                sock = bs;
                synchronized (this) { if (gen != generation) { bs.close(); return; } socket = bs; }
                bs.connect();
                in = bs.getInputStream();
                out = new BufferedOutputStream(bs.getOutputStream(), 16 * 1024);
            } else {
                Socket s = new Socket();
                sock = s;
                synchronized (this) { if (gen != generation) { s.close(); return; } socket = s; }
                if ("wifi".equals(how)) Net.bind(app, s); // hotspot links are local to the phone: no binding needed
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(1024 * 1024);
                s.connect(new InetSocketAddress(target.optString("ip"), target.optInt("port", TCP_PORT)), 4000);
                in = s.getInputStream();
                out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            }
            if (gen != generation) { sock.close(); return; }
            lastRx = System.currentTimeMillis();

            JSONObject hello = new JSONObject();
            hello.put("t", "hello");
            hello.put("id", Prefs.deviceId(app));
            hello.put("name", Prefs.deviceName(app));
            hello.put("clip", Prefs.clipSync(app));
            writeFrame(JSON, hello.toString().getBytes(StandardCharsets.UTF_8));
            stage = "Reached " + target.optString("name", "the PC") + ", waiting for it to answer…";
            setState(CONNECTING);
            // If the PC program doesn't answer within 8 s, stop waiting and say so.
            writer.postDelayed(() -> {
                if (gen == generation && state == CONNECTING) {
                    error = "Reached " + target.optString("name", "the PC") + " but PC Remote on it didn't answer. On the PC, right-click the "
                            + "PC Remote icon near the clock → Exit, then open PC Remote again from the Start menu.";
                    closeSocket(gen);
                }
            }, 8000);

            readLoop(gen, new DataInputStream(new BufferedInputStream(in, 256 * 1024)));
        } catch (Exception e) {
            if (gen != generation) return;
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (state == CONNECTING) {
                String name = target.optString("name", "the PC");
                if ("bluetooth".equals(how))
                    error = "Couldn't connect over Bluetooth. Make sure the PC is on, paired with this phone and PC Remote is running on it.";
                else if (e instanceof java.net.ConnectException)
                    error = "Reached " + name + " but PC Remote isn't running on it. Open PC Remote on the PC.";
                else if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.NoRouteToHostException)
                    error = "Can't reach " + name + " (" + target.optString("ip") + "). Check it's on the same " + ("hotspot".equals(how) ? "hotspot" : "Wi-Fi")
                            + ". If it is, on the PC: tray icon → Fix connection problems.";
                else
                    error = "Couldn't reach " + name + ". Is it on and on the same Wi-Fi/hotspot?";
            } else if (!userDisconnected) {
                error = "Connection lost.";
            }
            android.util.Log.w("PCRemote", "connection: " + msg);
            closeSocket(gen);
        }
    }

    private void readLoop(int gen, DataInputStream in) throws Exception {
        byte[] hdr = new byte[5];
        while (gen == generation) {
            in.readFully(hdr);
            int len = ((hdr[1] & 0xFF) << 24) | ((hdr[2] & 0xFF) << 16) | ((hdr[3] & 0xFF) << 8) | (hdr[4] & 0xFF);
            if (len < 0 || len > 32 * 1024 * 1024) throw new IOException("bad data");
            byte[] p = new byte[len];
            in.readFully(p);
            lastRx = System.currentTimeMillis();
            switch (hdr[0]) {
                case JSON: onJson(new JSONObject(new String(p, StandardCharsets.UTF_8))); break;
                case SCREEN: { FrameSink f = frameSink; if (f != null) f.onFrame(p); break; }
                case DATA: Transfers.onData(p); break;
            }
        }
    }

    private void onJson(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "pending": setState(WAITING); break;
            case "denied":
                error = "The PC didn't allow this phone.";
                userDisconnected = true;
                closeSocket(generation);
                return;
            case "welcome":
                try {
                    JSONObject p = new JSONObject();
                    p.put("id", m.optString("id"));
                    p.put("name", m.optString("name"));
                    if (isWifi()) { p.put("ip", pc.optString("ip")); p.put("port", pc.optInt("port", TCP_PORT)); }
                    String bt = m.optString("bt");
                    if (!bt.isEmpty()) p.put("bt", bt); else if (pc.has("bt")) p.put("bt", pc.optString("bt"));
                    p.put("last", System.currentTimeMillis());
                    p.put("transport", transport);
                    Prefs.savePc(app, p);
                    pc = Prefs.pc(app, p.optString("id"));
                    if (pc == null) pc = p;
                } catch (Exception ignored) {}
                screenW = m.optInt("sw", 1920);
                screenH = m.optInt("sh", 1080);
                pcVersion = m.optString("ver");
                setState(CONNECTED);
                lockWifi(true);
                main.post(() -> {
                    try {
                        Intent i = new Intent(app, RemoteService.class);
                        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
                    } catch (Exception e) { android.util.Log.w("PCRemote", "service: " + e); }
                });
                schedulePing(generation);
                AppIcons.sync(app);
                break;
            case "app_icon":
                AppIcons.onIcon(app, m);
                break;
            case "clip": {
                String s = m.optString("s");
                lastClip = s;
                main.post(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("PC", s));
                    } catch (Exception ignored) {}
                });
                break;
            }
            case "pong": return;
        }
        Transfers.onJson(t, m);
        main.post(() -> { for (Listener l : listeners) l.onMessage(t, m); });
    }

    private void schedulePing(int gen) {
        writer.postDelayed(() -> {
            if (gen != generation || state != CONNECTED) return;
            if (System.currentTimeMillis() - lastRx > 16000) {
                error = "Connection lost.";
                closeSocket(gen);
                return;
            }
            send(msg("ping"));
            schedulePing(gen);
        }, 5000);
    }

    public void disconnect() {
        userDisconnected = true;
        error = "";
        closeSocket(generation);
    }

    private void disconnectQuietly() {
        synchronized (this) { generation++; }
        Closeable s = socket;
        socket = null;
        try { if (s != null) s.close(); } catch (Exception ignored) {}
        out = null;
    }

    private void closeSocket(int gen) {
        synchronized (this) {
            if (gen != generation) return;
            generation++;
        }
        Closeable s = socket;
        socket = null;
        out = null;
        try { if (s != null) s.close(); } catch (Exception ignored) {}
        Transfers.onDisconnected();
        lockWifi(false);
        setState(IDLE);
        main.post(() -> { try { app.stopService(new Intent(app, RemoteService.class)); } catch (Exception ignored) {} });
    }

    // ---------------------------------------------------------------- sending

    /** Writes one frame from the calling thread (blocks while the network is busy). */
    public boolean writeFrame(byte type, byte[] data) {
        OutputStream o = out;
        if (o == null) return false;
        synchronized (writeLock) {
            try {
                int len = data.length;
                o.write(new byte[]{type, (byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len});
                o.write(data);
                o.flush();
                return true;
            } catch (IOException e) {
                int g = generation;
                main.post(() -> { if (state != IDLE) { error = "Connection lost."; closeSocket(g); } });
                return false;
            }
        }
    }

    public void send(JSONObject m) {
        if (out == null) return;
        byte[] b = m.toString().getBytes(StandardCharsets.UTF_8);
        writer.post(() -> writeFrame(JSON, b));
    }

    public static JSONObject msg(String type, Object... kv) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", type);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {}
        return o;
    }

    // ---------------------------------------------------------------- low latency

    private android.net.wifi.WifiManager.WifiLock wifiLock;

    /** Stops the phone's Wi-Fi power saving while connected, which otherwise adds lag. */
    private void lockWifi(boolean on) {
        try {
            if (on && isWifi()) {
                if (wifiLock == null) {
                    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                    int mode = Build.VERSION.SDK_INT >= 29 ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "PCRemote");
                    wifiLock.setReferenceCounted(false);
                }
                wifiLock.acquire();
            } else if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    // Mouse input uses tiny binary messages, and movements that pile up while the link is busy
    // are merged into one, so the pointer never falls behind your finger (important on Bluetooth).
    private final Object inLock = new Object();
    private int pendDx, pendDy, pendSy, pendSx;
    private double absX = -1, absY = -1;
    private boolean movePosted, scrollPosted, absPosted;

    private void writeInput(byte[] p) { writeFrame(INPUT, p); }

    private static byte[] op(int op, int a, int b) {
        a = Math.max(-32768, Math.min(32767, a));
        b = Math.max(-32768, Math.min(32767, b));
        return new byte[]{(byte) op, (byte) (a >> 8), (byte) a, (byte) (b >> 8), (byte) b};
    }

    public void move(int dx, int dy) {
        if ((dx == 0 && dy == 0) || out == null) return;
        synchronized (inLock) {
            pendDx += dx; pendDy += dy;
            if (movePosted) return;
            movePosted = true;
        }
        writer.post(() -> {
            int x, y;
            synchronized (inLock) { x = pendDx; y = pendDy; pendDx = pendDy = 0; movePosted = false; }
            if (x != 0 || y != 0) writeInput(op(1, x, y));
        });
    }

    public void moveTo(double x, double y) {
        if (out == null) return;
        synchronized (inLock) {
            absX = x; absY = y;
            if (absPosted) return;
            absPosted = true;
        }
        writer.post(() -> {
            double ax, ay;
            synchronized (inLock) { ax = absX; ay = absY; absPosted = false; }
            int ix = (int) Math.round(Math.max(0, Math.min(1, ax)) * 65535), iy = (int) Math.round(Math.max(0, Math.min(1, ay)) * 65535);
            writeInput(new byte[]{2, (byte) (ix >> 8), (byte) ix, (byte) (iy >> 8), (byte) iy});
        });
    }

    public void click(String b, String a) {
        if (out == null) return;
        int bb = "r".equals(b) ? 1 : "m".equals(b) ? 2 : 0;
        int aa = "down".equals(a) ? 1 : "up".equals(a) ? 2 : "dbl".equals(a) ? 3 : 0;
        byte[] p = {3, (byte) bb, (byte) aa};
        writer.post(() -> writeInput(p));
    }

    public void scroll(int dy, int dx) {
        if ((dx == 0 && dy == 0) || out == null) return;
        synchronized (inLock) {
            pendSy += dy; pendSx += dx;
            if (scrollPosted) return;
            scrollPosted = true;
        }
        writer.post(() -> {
            int y, x;
            synchronized (inLock) { y = pendSy; x = pendSx; pendSy = pendSx = 0; scrollPosted = false; }
            if (x != 0 || y != 0) writeInput(op(4, y, x));
        });
    }

    public void keys(int... vks) {
        JSONArray a = new JSONArray();
        for (int v : vks) a.put(v);
        send(msg("keys", "vks", a));
    }
    public void keyDown(int vk) { send(msg("kd", "vk", vk)); }
    public void keyUp(int vk) { send(msg("ku", "vk", vk)); }
    public void text(String s) { if (s != null && !s.isEmpty()) send(msg("txt", "s", s)); }
    public void power(String a) { send(msg("power", "a", a)); }
    public void run(String cmd) { send(msg("run", "cmd", cmd)); }

    public void sendClipboard(String s) {
        if (s == null || s.equals(lastClip)) return;
        lastClip = s;
        send(msg("clip", "s", s));
    }
}
