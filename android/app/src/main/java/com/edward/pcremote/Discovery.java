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

    public static void find(Context ctx, int timeoutMs, Callback cb) { find(ctx, timeoutMs, Prefs.connMode(ctx), cb); }

    /** mode: auto | wifi | hotspot */
    public static void find(Context ctx, int timeoutMs, String mode, Callback cb) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Map<String, JSONObject> found = new LinkedHashMap<>();
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                java.net.DatagramSocket wifiSock = null;
                if (!"hotspot".equals(mode) && Net.wifi(app) != null) { wifiSock = new java.net.DatagramSocket(); wifiSock.setBroadcast(true); Net.bind(app, wifiSock); wifiSock.setSoTimeout(1); }
                s.setSoTimeout(250);
                byte[] q = "PCREMOTE?".getBytes(StandardCharsets.UTF_8);
                Set<InetAddress> targets = broadcasts(app, mode);
                long end = System.currentTimeMillis() + timeoutMs;
                long nextSend = 0;
                byte[] buf = new byte[2048];
                while (System.currentTimeMillis() < end) {
                    if (System.currentTimeMillis() >= nextSend) {
                        for (InetAddress a : targets) {
                            try { s.send(new DatagramPacket(q, q.length, a, PORT)); } catch (Exception ignored) {}
                            if (wifiSock != null) try { wifiSock.send(new DatagramPacket(q, q.length, a, PORT)); } catch (Exception ignored) {}
                        }
                        nextSend = System.currentTimeMillis() + 500;
                    }
                    for (java.net.DatagramSocket rs : wifiSock == null ? new java.net.DatagramSocket[]{s} : new java.net.DatagramSocket[]{s, wifiSock})
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        rs.receive(p);
                        JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if (!"pc".equals(o.optString("t"))) continue;
                        o.put("ip", p.getAddress().getHostAddress());
                        o.put("via", rs == wifiSock ? "wifi" : (Net.wifi(app) != null && !"hotspot".equals(mode) && onWifiSubnet(app, p.getAddress()) ? "wifi" : "hotspot"));
                        found.put(o.optString("id"), o);
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception ignored) {}
                }
                if (wifiSock != null) wifiSock.close();
            } catch (Exception ignored) {}
            List<JSONObject> l = new ArrayList<>(found.values());
            main.post(() -> cb.onFound(l));
        }, "discovery").start();
    }

    /** Broadcast addresses to search: the Wi-Fi network, the phone's hotspot, or both (auto). */
    static Set<InetAddress> broadcasts(Context c, String mode) {
        Set<InetAddress> out = new LinkedHashSet<>();
        String wifiIf = Net.wifiInterface(c);
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                // skip mobile data: nothing to find there and we never want to use data
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-") || n.startsWith("dummy")
                        || n.startsWith("tun") || n.startsWith("ip6") || n.startsWith("p2p")) continue;
                boolean isWifiClient = n.equals(wifiIf);
                if ("wifi".equals(mode) && !isWifiClient) continue;
                if ("hotspot".equals(mode) && isWifiClient) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignored) {}
        if (!"hotspot".equals(mode) && wifiIf != null) {
            try { out.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean onWifiSubnet(Context c, InetAddress a) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(Net.wifiInterface(c));
            if (ni == null) return false;
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                byte[] x = ia.getAddress().getAddress(), y = a.getAddress();
                if (x.length != y.length) continue;
                int bits = ia.getNetworkPrefixLength();
                boolean same = true;
                for (int i = 0; i < x.length && bits > 0; i++, bits -= 8) {
                    int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                    if ((x[i] & mask) != (y[i] & mask)) { same = false; break; }
                }
                if (same) return true;
            }
        } catch (Exception ignored) {}
        return false;
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
     * Connects to the most recently used PC using the chosen connection type (Settings / home screen):
     * auto = Wi-Fi or hotspot first, then Bluetooth. Calls back with the PCs that were seen.
     * Sets Conn.error with a helpful message when the chosen type isn't available.
     */
    public static void autoConnect(Context c, Callback seen) {
        Conn conn = Conn.get();
        String mode = Prefs.connMode(c);
        List<JSONObject> known = Prefs.pcs(c);

        if ("bt".equals(mode)) {
            if (!hasBtPermission(c)) { conn.error = "Allow Bluetooth for PC Remote first (tap Switch PC)."; seen.onFound(new ArrayList<>()); return; }
            if (!btOn(c)) { conn.error = "Bluetooth is off. Turn it on to connect."; seen.onFound(new ArrayList<>()); return; }
            for (JSONObject k : known) if (!k.optString("bt").isEmpty()) { conn.connect(k, "bluetooth"); seen.onFound(new ArrayList<>()); return; }
            conn.error = "No Bluetooth PC yet. Tap Switch PC and choose your PC under Bluetooth.";
            seen.onFound(new ArrayList<>());
            return;
        }
        if ("wifi".equals(mode) && Net.wifi(c) == null) {
            conn.error = "This phone isn't on Wi-Fi. Connect to Wi-Fi, or choose Hotspot / Bluetooth.";
            seen.onFound(new ArrayList<>());
            return;
        }
        if ("hotspot".equals(mode) && broadcasts(c, "hotspot").isEmpty()) {
            conn.error = "Your hotspot looks off. Turn it on and connect the PC to it.";
            seen.onFound(new ArrayList<>());
            return;
        }

        find(c, 1600, mode, found -> {
            if (conn.state != Conn.IDLE) { seen.onFound(found); return; }
            for (JSONObject k : known) {
                for (JSONObject f : found) {
                    if (f.optString("id").equals(k.optString("id"))) {
                        try { f.put("last", k.optLong("last")); if (k.has("bt") && f.optString("bt").isEmpty()) f.put("bt", k.optString("bt")); } catch (Exception ignored) {}
                        conn.connect(f, how(mode, f));
                        seen.onFound(found);
                        return;
                    }
                }
            }
            if (known.isEmpty() && found.size() == 1) {
                conn.connect(found.get(0), how(mode, found.get(0)));
                seen.onFound(found);
                return;
            }
            if ("auto".equals(mode) && !known.isEmpty() && found.isEmpty()) {
                JSONObject last = known.get(0);
                if (!last.optString("bt").isEmpty() && btOn(c) && hasBtPermission(c)) conn.connect(last, "bluetooth");
            }
            if (found.isEmpty() && !"auto".equals(mode))
                conn.error = "hotspot".equals(mode) ? "No PC found on your hotspot. Is the PC connected to it and PC Remote running?"
                        : "No PC found on this Wi-Fi. Is the PC on the same Wi-Fi and PC Remote running?";
            seen.onFound(found);
        });
    }

    /** Which link a found PC is on. */
    public static String how(String mode, JSONObject found) {
        if ("hotspot".equals(mode)) return "hotspot";
        if ("wifi".equals(mode)) return "wifi";
        return "hotspot".equals(found.optString("via")) ? "hotspot" : "wifi";
    }
}
