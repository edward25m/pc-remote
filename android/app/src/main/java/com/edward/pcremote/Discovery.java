package com.edward.pcremote;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds PCs running PC Remote on the same Wi-Fi or on this phone's hotspot. */
public final class Discovery {
    public static final int PORT = 47800;

    public interface Callback { void onFound(List<JSONObject> pcs); }

    private Discovery() {}

    public static void find(int timeoutMs, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Map<String, JSONObject> found = new LinkedHashMap<>();
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                s.setSoTimeout(250);
                byte[] q = "PCREMOTE?".getBytes(StandardCharsets.UTF_8);
                Set<InetAddress> targets = broadcastAddresses();
                long end = System.currentTimeMillis() + timeoutMs;
                long nextSend = 0;
                byte[] buf = new byte[2048];
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() >= nextSend) {
                        for (InetAddress a : targets) {
                            try { s.send(new DatagramPacket(q, q.length, a, PORT)); } catch (Exception ignored) {}
                        }
                        nextSend = System.currentTimeMillis() + 500;
                    }
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if (!"pc".equals(o.optString("t"))) continue;
                        o.put("ip", p.getAddress().getHostAddress());
                        found.put(o.optString("id"), o);
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            List<JSONObject> l = new ArrayList<>(found.values());
            main.post(() -> cb.onFound(l));
        }, "discovery").start();
    }

    private static Set<InetAddress> broadcastAddresses() {
        Set<InetAddress> out = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                // skip mobile data interfaces: nothing to find there and we never want to use data
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-rmnet") || n.startsWith("dummy")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignored) {}
        try { out.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
        return out;
    }

    /** Paired Bluetooth computers. Needs the Bluetooth permission on Android 12+. */
    @SuppressLint("MissingPermission")
    public static List<BluetoothDevice> pairedComputers(Context c) {
        List<BluetoothDevice> l = new ArrayList<>();
        if (!hasBtPermission(c)) return l;
        try {
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) return l;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                BluetoothClass bc = d.getBluetoothClass();
                if (bc == null || bc.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER) l.add(d);
            }
        } catch (Exception ignored) {}
        return l;
    }

    public static boolean hasBtPermission(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean btOn(Context c) {
        try {
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
        } catch (Exception e) { return false; }
    }

    /**
     * Connects to the most recently used PC that can be reached: first over Wi-Fi/hotspot,
     * then over Bluetooth. If no remembered PC answers but exactly one new PC does, it uses that one.
     * Calls back with the PCs that were seen (so the caller can show a picker).
     */
    public static void autoConnect(Context c, Callback seen) {
        Conn conn = Conn.get();
        find(1600, found -> {
            if (conn.state != Conn.IDLE) { seen.onFound(found); return; }
            List<JSONObject> known = Prefs.pcs(c);
            for (JSONObject k : known) {
                for (JSONObject f : found) {
                    if (f.optString("id").equals(k.optString("id"))) {
                        try { f.put("last", k.optLong("last")); if (k.has("bt") && f.optString("bt").isEmpty()) f.put("bt", k.optString("bt")); } catch (Exception ignored) {}
                        conn.connect(f, "wifi");
                        seen.onFound(found);
                        return;
                    }
                }
            }
            if (known.isEmpty() && found.size() == 1) {
                conn.connect(found.get(0), "wifi");
                seen.onFound(found);
                return;
            }
            if (!known.isEmpty() && found.isEmpty()) {
                JSONObject last = known.get(0);
                if (!last.optString("bt").isEmpty() && btOn(c) && hasBtPermission(c)) {
                    conn.connect(last, "bluetooth");
                }
            }
            seen.onFound(found);
        });
    }
}
