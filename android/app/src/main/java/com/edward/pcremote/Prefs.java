package com.edward.pcremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Everything the app remembers. */
public final class Prefs {
    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("pcremote", Context.MODE_PRIVATE);
    }

    public static String deviceId(Context c) {
        SharedPreferences p = sp(c);
        String id = p.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    public static String deviceName(Context c) {
        String n = null;
        try { n = Settings.Global.getString(c.getContentResolver(), "device_name"); } catch (Exception ignored) {}
        if (n == null || n.isEmpty()) n = Build.MANUFACTURER + " " + Build.MODEL;
        return n;
    }

    // ---- settings ----
    public static String volMode(Context c) { return sp(c).getString("vol_mode", "app"); } // off | app | always
    public static boolean clipSync(Context c) { return sp(c).getBoolean("clip_sync", true); }
    public static int mouseSpeed(Context c) { return sp(c).getInt("mouse_speed", 50); }
    public static int airSpeed(Context c) { return sp(c).getInt("air_speed", 50); }
    public static int scrollSpeed(Context c) { return sp(c).getInt("scroll_speed", 50); }
    public static int screenQuality(Context c) { return sp(c).getInt("screen_q", 1); } // 0 low .. 3 best
    public static String mediaDefault(Context c) { return sp(c).getString("media_default", "ask"); } // ask | pc | phone
    public static boolean invertScroll(Context c) { return sp(c).getBoolean("invert_scroll", false); }

    public static void put(Context c, String k, Object v) {
        SharedPreferences.Editor e = sp(c).edit();
        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else e.putString(k, String.valueOf(v));
        e.apply();
    }

    // ---- notification bar ----
    public static final List<String> DEFAULT_BAR = Arrays.asList("prev", "play", "next", "voldown", "volup", "altf4");

    public static List<String> notifBar(Context c) {
        String s = sp(c).getString("notif_bar", null);
        if (s == null) return new ArrayList<>(DEFAULT_BAR);
        List<String> l = new ArrayList<>();
        if (!s.isEmpty()) Collections.addAll(l, s.split(","));
        return l;
    }

    public static void setNotifBar(Context c, List<String> ids) {
        sp(c).edit().putString("notif_bar", String.join(",", ids)).apply();
    }

    // ---- custom buttons ----
    public static JSONArray customButtons(Context c) {
        try { return new JSONArray(sp(c).getString("custom_buttons", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public static void setCustomButtons(Context c, JSONArray a) {
        sp(c).edit().putString("custom_buttons", a.toString()).apply();
    }

    // ---- remembered PCs ----
    public static List<JSONObject> pcs(Context c) {
        List<JSONObject> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("pcs", "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getJSONObject(i));
        } catch (Exception ignored) {}
        Collections.sort(l, (x, y) -> Long.compare(y.optLong("last"), x.optLong("last")));
        return l;
    }

    public static JSONObject pc(Context c, String id) {
        for (JSONObject p : pcs(c)) if (p.optString("id").equals(id)) return p;
        return null;
    }

    public static void savePc(Context c, JSONObject pc) {
        List<JSONObject> l = pcs(c);
        JSONArray out = new JSONArray();
        boolean found = false;
        for (JSONObject p : l) {
            if (p.optString("id").equals(pc.optString("id"))) {
                // keep fields we don't have now (e.g. Bluetooth address learned earlier)
                try {
                    java.util.Iterator<String> it = p.keys();
                    while (it.hasNext()) { String k = it.next(); if (!pc.has(k) || pc.optString(k).isEmpty()) pc.put(k, p.get(k)); }
                } catch (Exception ignored) {}
                out.put(pc);
                found = true;
            } else out.put(p);
        }
        if (!found) out.put(pc);
        sp(c).edit().putString("pcs", out.toString()).apply();
    }

    public static void forgetPc(Context c, String id) {
        JSONArray out = new JSONArray();
        for (JSONObject p : pcs(c)) if (!p.optString("id").equals(id)) out.put(p);
        sp(c).edit().putString("pcs", out.toString()).apply();
    }
}
