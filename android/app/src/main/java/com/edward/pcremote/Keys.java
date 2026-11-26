package com.edward.pcremote;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Windows key codes, the shortcut parser, and the actions that can go on the notification bar. */
public final class Keys {
    public static final int BACK = 0x08, TAB = 0x09, ENTER = 0x0D, SHIFT = 0x10, CTRL = 0x11, ALT = 0x12,
            ESC = 0x1B, SPACE = 0x20, PGUP = 0x21, PGDN = 0x22, END = 0x23, HOME = 0x24, LEFT = 0x25, UP = 0x26,
            RIGHT = 0x27, DOWN = 0x28, PRTSC = 0x2C, INS = 0x2D, DEL = 0x2E, WIN = 0x5B, APPS = 0x5D,
            F1 = 0x70, MUTE = 0xAD, VOLDOWN = 0xAE, VOLUP = 0xAF, NEXT = 0xB0, PREV = 0xB1, STOP = 0xB2, PLAY = 0xB3;

    private static final Map<String, Integer> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put("ctrl", CTRL); NAMES.put("control", CTRL); NAMES.put("shift", SHIFT); NAMES.put("alt", ALT);
        NAMES.put("win", WIN); NAMES.put("windows", WIN); NAMES.put("esc", ESC); NAMES.put("escape", ESC);
        NAMES.put("tab", TAB); NAMES.put("enter", ENTER); NAMES.put("return", ENTER); NAMES.put("space", SPACE);
        NAMES.put("backspace", BACK); NAMES.put("del", DEL); NAMES.put("delete", DEL); NAMES.put("ins", INS);
        NAMES.put("insert", INS); NAMES.put("home", HOME); NAMES.put("end", END); NAMES.put("pgup", PGUP);
        NAMES.put("pageup", PGUP); NAMES.put("pgdn", PGDN); NAMES.put("pagedown", PGDN); NAMES.put("up", UP);
        NAMES.put("down", DOWN); NAMES.put("left", LEFT); NAMES.put("right", RIGHT); NAMES.put("prtsc", PRTSC);
        NAMES.put("printscreen", PRTSC); NAMES.put("menu", APPS); NAMES.put("mute", MUTE);
        NAMES.put("volup", VOLUP); NAMES.put("voldown", VOLDOWN); NAMES.put("next", NEXT); NAMES.put("prev", PREV);
        NAMES.put("play", PLAY); NAMES.put("stop", STOP);
        NAMES.put(",", 0xBC); NAMES.put(".", 0xBE); NAMES.put("-", 0xBD); NAMES.put("=", 0xBB); NAMES.put(";", 0xBA);
        NAMES.put("/", 0xBF); NAMES.put("`", 0xC0); NAMES.put("[", 0xDB); NAMES.put("\\", 0xDC); NAMES.put("]", 0xDD);
        NAMES.put("'", 0xDE);
    }

    private Keys() {}

    /** "ctrl+shift+esc" -> key codes. Returns null if something isn't understood. */
    public static List<Integer> parse(String combo) {
        List<Integer> out = new ArrayList<>();
        if (combo == null) return null;
        for (String raw : combo.split("\\+")) {
            String k = raw.trim().toLowerCase(Locale.US);
            if (k.isEmpty()) { if (combo.contains("++")) k = "="; else continue; }
            Integer vk = NAMES.get(k);
            if (vk == null && k.length() == 1) vk = forChar(k.charAt(0));
            if (vk == null && k.matches("f([1-9]|1[0-9]|2[0-4])")) vk = F1 + Integer.parseInt(k.substring(1)) - 1;
            if (vk == null) return null;
            out.add(vk);
        }
        return out.isEmpty() ? null : out;
    }

    /** Key code for a letter or digit, or null. */
    public static Integer forChar(char c) {
        c = Character.toUpperCase(c);
        if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return (int) c;
        Integer v = NAMES.get(String.valueOf(c));
        return v;
    }

    // ---------------- actions (notification bar + custom buttons) ----------------

    public static final class Action {
        public final String id, symbol, label;
        public int icon = R.drawable.ic_star;
        public android.graphics.Bitmap image; // real app icon for app buttons
        Action(String id, String symbol, String label) {
            this.id = id; this.symbol = symbol; this.label = label;
            int r = iconFor(id);
            if (r != 0) icon = r;
        }
    }

    static int iconFor(String id) {
        switch (id) {
            case "prev": return R.drawable.ic_prev;
            case "play": return R.drawable.ic_play;
            case "next": return R.drawable.ic_next;
            case "voldown": return R.drawable.ic_voldown;
            case "volup": return R.drawable.ic_volup;
            case "mute": return R.drawable.ic_mute;
            case "altf4": return R.drawable.ic_altf4;
            case "stop": return R.drawable.ic_stop;
            case "space": return R.drawable.ic_space;
            case "lock": return R.drawable.ic_lock;
            case "screen_off": return R.drawable.ic_screen_off;
            default: return 0;
        }
    }

    /** Decodes a button's saved app icon, if it has one. */
    public static android.graphics.Bitmap image(JSONObject b) {
        String img = b.optString("img");
        if (img.isEmpty()) return null;
        try {
            byte[] d = android.util.Base64.decode(img, android.util.Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length);
        } catch (Exception e) { return null; }
    }

    public static List<Action> builtIn() {
        List<Action> l = new ArrayList<>();
        l.add(new Action("prev", "⏮", "Previous"));
        l.add(new Action("play", "⏯", "Play / pause"));
        l.add(new Action("next", "⏭", "Next"));
        l.add(new Action("voldown", "🔉", "Volume down"));
        l.add(new Action("volup", "🔊", "Volume up"));
        l.add(new Action("mute", "🔇", "Mute"));
        l.add(new Action("altf4", "✕", "Close window (Alt+F4)"));
        l.add(new Action("stop", "⏹", "Stop"));
        l.add(new Action("space", "␣", "Space"));
        l.add(new Action("lock", "🔒", "Lock PC"));
        l.add(new Action("screen_off", "🌙", "Turn off PC screen"));
        return l;
    }

    /** All actions including the user's own buttons (ids "custom:<n>"). */
    public static List<Action> all(Context c) {
        List<Action> l = builtIn();
        JSONArray custom = Prefs.customButtons(c);
        for (int i = 0; i < custom.length(); i++) {
            JSONObject b = custom.optJSONObject(i);
            if (b == null) continue;
            String label = b.optString("label", "Button");
            String sym = b.optString("icon", "");
            if (sym.isEmpty()) sym = label.length() <= 3 ? label : label.substring(0, 2);
            Action a = new Action("custom:" + b.optString("uid"), sym, label + " (my button)");
            a.image = image(b);
            l.add(a);
        }
        return l;
    }

    public static Action find(Context c, String id) {
        for (Action a : all(c)) if (a.id.equals(id)) return a;
        return null;
    }

    public static void run(Context c, String id) {
        Conn conn = Conn.get();
        switch (id) {
            case "prev": conn.keys(PREV); break;
            case "play": conn.keys(PLAY); break;
            case "next": conn.keys(NEXT); break;
            case "voldown": conn.keys(VOLDOWN); break;
            case "volup": conn.keys(VOLUP); break;
            case "mute": conn.keys(MUTE); break;
            case "altf4": conn.keys(ALT, F1 + 3); break;
            case "stop": conn.keys(STOP); break;
            case "space": conn.keys(SPACE); break;
            case "lock": conn.power("lock"); break;
            case "screen_off": conn.power("screen_off"); break;
            default:
                if (id.startsWith("custom:")) runCustom(c, id.substring(7));
        }
    }

    public static void runCustom(Context c, String uid) {
        JSONArray custom = Prefs.customButtons(c);
        for (int i = 0; i < custom.length(); i++) {
            JSONObject b = custom.optJSONObject(i);
            if (b != null && uid.equals(b.optString("uid"))) { runButton(b); return; }
        }
    }

    public static void runButton(JSONObject b) {
        Conn conn = Conn.get();
        String type = b.optString("type", "keys");
        String value = b.optString("value", "");
        switch (type) {
            case "keys":
                // several shortcuts can be chained with commas: "win+r, ctrl+a"
                for (String part : value.split(",")) {
                    List<Integer> vks = parse(part);
                    if (vks != null) conn.keys(toArray(vks));
                }
                break;
            case "text": conn.text(value); break;
            case "run": conn.run(value); break;
            case "app": conn.send(Conn.msg("run_app", "q", value, "label", b.optString("label"))); break;
        }
    }

    public static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }
}
