package com.edward.pcremote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Makes sure PC traffic goes over Wi-Fi, never mobile data. (When a Wi-Fi network has no
 * internet, Android otherwise sends app traffic over mobile data, where the PC can't be reached.)
 */
public final class Net {
    private Net() {}

    public static Network wifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Exception ignored) {}
        return null; // e.g. this phone is the hotspot: the hotspot link is local and never uses data
    }

    /** Name of the Wi-Fi interface the phone uses as a normal Wi-Fi client (e.g. wlan0), or null. */
    public static String wifiInterface(Context c) {
        try {
            Network n = wifi(c);
            if (n == null) return null;
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm.getLinkProperties(n).getInterfaceName();
        } catch (Exception e) { return null; }
    }

    /** True if the phone seems to be sharing a hotspot (a local network interface other than Wi-Fi client / mobile data). */
    public static boolean hotspotOn(Context c) {
        return !Discovery.broadcasts(c, "hotspot").isEmpty();
    }

    public static void bind(Context c, java.net.Socket s) {
        Network n = wifi(c);
        if (n != null) try { n.bindSocket(s); } catch (Exception ignored) {}
    }

    public static void bind(Context c, java.net.DatagramSocket s) {
        Network n = wifi(c);
        if (n != null) try { n.bindSocket(s); } catch (Exception ignored) {}
    }

    /** For the video player: routes the whole app over Wi-Fi while it plays. */
    public static void bindProcess(Context c, boolean on) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.bindProcessToNetwork(on ? wifi(c) : null);
        } catch (Exception ignored) {}
    }
}
