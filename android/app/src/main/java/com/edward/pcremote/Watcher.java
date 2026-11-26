package com.edward.pcremote;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Keeps you connected:
 *  - while the app is open, it listens for the PC announcing itself (it does so when it starts or joins a network)
 *    and connects within a second, and it also searches every few seconds;
 *  - if a connection drops, it keeps trying to reconnect for 2 minutes, even with the app in the background.
 */
public final class Watcher {
    public static final int ANNOUNCE_PORT = 47803;
    private static final long RECONNECT_FOR_MS = 120_000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static int visible = 0;
    private static long reconnectUntil = 0;
    public static volatile boolean searching = false;
    private static boolean tickScheduled = false;
    private static Thread listenThread;
    private static DatagramSocket listenSock;
    private static WifiManager.MulticastLock mlock;

    private Watcher() {}

    public static void init(Context c) { if (app == null) app = c.getApplicationContext(); }

    public static void screenShown(Context c) {
        init(c);
        visible++;
        update();
    }

    public static void screenHidden() {
        visible = Math.max(0, visible - 1);
        main.postDelayed(Watcher::update, 2000); // switching between screens isn't "leaving the app"
    }

    public static boolean reconnecting() { return System.currentTimeMillis() < reconnectUntil; }

    private static boolean active() {
        return app != null && !Conn.get().autoPaused && (visible > 0 || reconnecting());
    }

    /** Called by Conn on the main thread whenever a connection ends. */
    static void onClosed(boolean lost) {
        if (lost) reconnectUntil = System.currentTimeMillis() + RECONNECT_FOR_MS;
        update();
        if (lost) RemoteService.refresh(app); // notification shows "Reconnecting…"
        else stopServiceIfIdle();
    }

    /** User asked to connect now (or changed connection type). */
    public static void connectNow(Context c) {
        init(c);
        Conn.get().autoPaused = false;
        update();
        tick();
    }

    private static void update() {
        if (active()) {
            startListening();
            if (!tickScheduled) { tickScheduled = true; main.post(Watcher::tick); }
        } else {
            stopListening();
            if (!reconnecting()) stopServiceIfIdle();
        }
    }

    private static void stopServiceIfIdle() {
        if (app != null && Conn.get().state == Conn.IDLE && !reconnecting())
            try { app.stopService(new Intent(app, RemoteService.class)); } catch (Exception ignored) {}
    }

    private static void tick() {
        tickScheduled = false;
        if (!active()) { update(); return; }
        Conn conn = Conn.get();
        if (conn.state == Conn.IDLE && !searching) {
            searching = true;
            conn.refreshUi();
            Discovery.autoConnect(app, found -> {
                searching = false;
                conn.refreshUi();
            });
        }
        if (Conn.get().state == Conn.CONNECTED) reconnectUntil = 0;
        tickScheduled = true;
        main.postDelayed(Watcher::tick, conn.state == Conn.IDLE ? 4000 : 2000);
    }

    // ------------------------------------------------------------ PC announcements

    private static synchronized void startListening() {
        if (listenThread != null) return;
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            mlock = wm.createMulticastLock("PCRemote");
            mlock.setReferenceCounted(false);
            mlock.acquire(); // some phones drop broadcast packets without this
        } catch (Exception ignored) {}
        listenThread = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(ANNOUNCE_PORT));
                listenSock = s;
                byte[] buf = new byte[2048];
                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    try {
                        JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if (!"pc".equals(o.optString("t"))) continue;
                        o.put("ip", p.getAddress().getHostAddress());
                        main.post(() -> onAnnounce(o));
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }, "pc-announce");
        listenThread.start();
    }

    private static synchronized void stopListening() {
        if (listenThread == null) return;
        listenThread.interrupt();
        try { if (listenSock != null) listenSock.close(); } catch (Exception ignored) {}
        listenSock = null;
        listenThread = null;
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Exception ignored) {}
    }

    private static void onAnnounce(JSONObject o) {
        if (!active()) return;
        Conn conn = Conn.get();
        String mode = Prefs.connMode(app);
        if ("bt".equals(mode)) return;
        boolean known = Prefs.pc(app, o.optString("id")) != null;
        if (!known && !Prefs.pcs(app).isEmpty()) return; // only jump to PCs you've used before
        try {
            JSONObject k = Prefs.pc(app, o.optString("id"));
            if (k != null && k.has("bt") && o.optString("bt").isEmpty()) o.put("bt", k.optString("bt"));
        } catch (Exception ignored) {}
        String how = Discovery.how(mode, Discovery.classify(app, o));
        if ("wifi".equals(mode) && "hotspot".equals(how) || "hotspot".equals(mode) && "wifi".equals(how)) return;

        if (conn.state == Conn.IDLE) {
            conn.connect(o, how);
        } else if (conn.state == Conn.CONNECTING && conn.pc.optString("id").equals(o.optString("id"))
                && !o.optString("ip").equals(conn.pc.optString("ip")) && conn.isWifi()) {
            conn.connect(o, how); // it was trying an old address: use the one the PC just announced
        }
    }
}
