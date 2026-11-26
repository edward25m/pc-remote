package com.edward.pcremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/** App buttons (EA app, FC 27, PotPlayer…) get their real icons from the PC they run on. */
public final class AppIcons {
    private AppIcons() {}

    /** Adds the starter app buttons once. */
    public static void addPresets(Context c) {
        if (Prefs.sp(c).getBoolean("presets_v1", false)) return;
        try {
            JSONArray a = Prefs.customButtons(c);
            a.put(app("ea", "EA app", "EA app|EA"));
            a.put(app("fc27", "FC 27", "EA SPORTS FC 27|FC 27"));
            a.put(app("potplayer", "PotPlayer", "PotPlayer 64 bit|PotPlayer"));
            Prefs.setCustomButtons(c, a);
        } catch (Exception ignored) {}
        Prefs.put(c, "presets_v1", true);
    }

    private static JSONObject app(String uid, String label, String value) throws Exception {
        JSONObject o = new JSONObject();
        o.put("uid", uid);
        o.put("label", label);
        o.put("type", "app");
        o.put("value", value);
        return o;
    }

    /** Asks the PC for icons of app buttons that don't have one yet (e.g. PotPlayer once it's installed). */
    public static void sync(Context c) {
        JSONArray a = Prefs.customButtons(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject b = a.optJSONObject(i);
            if (b != null && "app".equals(b.optString("type")) && b.optString("img").isEmpty())
                request(b);
        }
    }

    public static void request(JSONObject b) {
        Conn.get().send(Conn.msg("app_icon", "q", b.optString("value"), "uid", b.optString("uid")));
    }

    /** Called on the connection thread with the PC's answer. */
    static void onIcon(Context c, JSONObject m) {
        if (!m.optBoolean("found") || m.optString("png").isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                JSONArray a = Prefs.customButtons(c);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject b = a.optJSONObject(i);
                    if (b != null && b.optString("uid").equals(m.optString("uid"))) {
                        b.put("img", m.optString("png"));
                        Prefs.setCustomButtons(c, a);
                        RemoteService.refresh(c);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        });
    }
}
