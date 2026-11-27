package com.edward.pcremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The always-visible row of handy keys (Alt+Tab, Alt+F4, Win, Esc…). You choose which ones appear
 * in Settings → Quick keys row (or the ✎ at the end of the row).
 *
 * Alt+Tab and Ctrl+Tab are "switchers": the first tap holds Alt and shows the window switcher, each extra
 * tap moves to the next window (long-press goes back), and Alt is let go automatically a moment after
 * your last tap, or straight away when you touch the touchpad.
 */
public final class QuickKeys {
    public static final class Item {
        public final String id, label, group, combo;
        public final int switchMod; // 0 = normal shortcut; otherwise the key held while switching
        Item(String id, String label, String group, String combo, int switchMod) {
            this.id = id; this.label = label; this.group = group; this.combo = combo; this.switchMod = switchMod;
        }
    }

    public static final List<String> DEFAULT = Arrays.asList("alttab", "altf4", "win", "esc", "tab", "enter", "copy", "paste", "undo");

    private QuickKeys() {}

    public static List<Item> builtIn() {
        List<Item> l = new ArrayList<>();
        String g = "Switch windows";
        l.add(new Item("alttab", "Alt+Tab", g, "", Keys.ALT));
        l.add(new Item("ctrltab", "Ctrl+Tab", g, "", Keys.CTRL));
        l.add(new Item("wintab", "Task view", g, "win+tab", 0));
        l.add(new Item("altf4", "Alt+F4", g, "alt+f4", 0));
        l.add(new Item("windesk", "Desktop", g, "win+d", 0));
        g = "Single keys";
        l.add(new Item("win", "Win", g, "win", 0));
        l.add(new Item("alt", "Alt", g, "alt", 0));
        l.add(new Item("ctrl", "Ctrl", g, "ctrl", 0));
        l.add(new Item("shift", "Shift", g, "shift", 0));
        l.add(new Item("esc", "Esc", g, "esc", 0));
        l.add(new Item("tab", "Tab", g, "tab", 0));
        l.add(new Item("enter", "Enter", g, "enter", 0));
        l.add(new Item("space", "Space", g, "space", 0));
        l.add(new Item("bksp", "Bksp", g, "backspace", 0));
        l.add(new Item("del", "Del", g, "del", 0));
        l.add(new Item("left", "←", g, "left", 0));
        l.add(new Item("up", "↑", g, "up", 0));
        l.add(new Item("down", "↓", g, "down", 0));
        l.add(new Item("right", "→", g, "right", 0));
        l.add(new Item("f5", "F5", g, "f5", 0));
        l.add(new Item("f11", "F11", g, "f11", 0));
        l.add(new Item("prtsc", "PrtSc", g, "prtsc", 0));
        g = "Editing";
        l.add(new Item("copy", "Copy", g, "ctrl+c", 0));
        l.add(new Item("paste", "Paste", g, "ctrl+v", 0));
        l.add(new Item("cut", "Cut", g, "ctrl+x", 0));
        l.add(new Item("undo", "Undo", g, "ctrl+z", 0));
        l.add(new Item("redo", "Redo", g, "ctrl+y", 0));
        l.add(new Item("selall", "Select all", g, "ctrl+a", 0));
        l.add(new Item("save", "Save", g, "ctrl+s", 0));
        l.add(new Item("find", "Find", g, "ctrl+f", 0));
        g = "Browser";
        l.add(new Item("newtab", "New tab", g, "ctrl+t", 0));
        l.add(new Item("closetab", "Close tab", g, "ctrl+w", 0));
        l.add(new Item("reopentab", "Reopen tab", g, "ctrl+shift+t", 0));
        l.add(new Item("back", "Back", g, "alt+left", 0));
        g = "Windows";
        l.add(new Item("taskmgr", "Task Manager", g, "ctrl+shift+esc", 0));
        l.add(new Item("explorer", "File Explorer", g, "win+e", 0));
        l.add(new Item("snip", "Screenshot", g, "win+shift+s", 0));
        l.add(new Item("cliphist", "Clipboard history", g, "win+v", 0));
        l.add(new Item("emoji", "Emoji", g, "win+.", 0));
        l.add(new Item("settings", "Settings", g, "win+i", 0));
        l.add(new Item("altenter", "Alt+Enter (full screen)", g, "alt+enter", 0));
        g = "Media";
        l.add(new Item("play", "Play/Pause", g, "play", 0));
        l.add(new Item("volup", "Vol +", g, "volup", 0));
        l.add(new Item("voldown", "Vol −", g, "voldown", 0));
        l.add(new Item("mute", "Mute", g, "mute", 0));
        return l;
    }

    /** Built-ins + shortcuts you made + your "My buttons". */
    public static List<Item> all(Context c) {
        List<Item> l = builtIn();
        JSONArray own = own(c);
        for (int i = 0; i < own.length(); i++) {
            JSONObject o = own.optJSONObject(i);
            if (o != null) l.add(new Item("own:" + o.optString("uid"), o.optString("label"), "Your shortcuts", o.optString("combo"), 0));
        }
        JSONArray btns = Prefs.customButtons(c);
        for (int i = 0; i < btns.length(); i++) {
            JSONObject o = btns.optJSONObject(i);
            if (o != null) l.add(new Item("custom:" + o.optString("uid"), o.optString("label"), "My buttons & apps", "", 0));
        }
        return l;
    }

    public static Item find(Context c, String id) {
        for (Item i : all(c)) if (i.id.equals(id)) return i;
        return null;
    }

    public static List<String> selected(Context c) {
        String s = Prefs.sp(c).getString("quick_keys", null);
        if (s == null) return new ArrayList<>(DEFAULT);
        List<String> l = new ArrayList<>();
        if (!s.isEmpty()) Collections.addAll(l, s.split(","));
        return l;
    }

    public static void setSelected(Context c, List<String> ids) {
        Prefs.sp(c).edit().putString("quick_keys", String.join(",", ids)).apply();
    }

    public static JSONArray own(Context c) {
        try { return new JSONArray(Prefs.sp(c).getString("quick_own", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public static void setOwn(Context c, JSONArray a) {
        Prefs.sp(c).edit().putString("quick_own", a.toString()).apply();
    }

    // ------------------------------------------------------------ the row

    private static final Handler h = new Handler(Looper.getMainLooper());
    private static int heldMod = 0;
    private static final Runnable release = QuickKeys::releaseNow;

    /** Lets go of a held Alt/Ctrl from a switcher (picks the highlighted window). */
    public static void releaseNow() {
        h.removeCallbacks(release);
        if (heldMod != 0) { Conn.get().keyUp(heldMod); heldMod = 0; }
    }

    private static void switchTap(int mod, boolean backwards) {
        Conn conn = Conn.get();
        if (heldMod != mod) { releaseNow(); conn.keyDown(mod); heldMod = mod; }
        if (backwards) conn.keys(Keys.SHIFT, Keys.TAB); else conn.keys(Keys.TAB);
        h.removeCallbacks(release);
        h.postDelayed(release, 1100); // stop switching a moment after your last tap
    }

    public static void run(Context c, Item it) {
        if (it.switchMod != 0) { switchTap(it.switchMod, false); return; }
        releaseNow();
        if (it.id.startsWith("custom:")) { Keys.runCustom(c, it.id.substring(7)); return; }
        List<Integer> vks = Keys.parse(it.combo);
        if (vks != null) Conn.get().keys(Keys.toArray(vks));
    }

    /** Builds the scrollable row of chips. */
    public static HorizontalScrollView row(Activity a) {
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = Ui.hbox(a);
        r.setPadding(0, Ui.dp(a, 8), 0, 0);
        hs.addView(r);
        fill(a, r);
        return hs;
    }

    public static void fill(Activity a, LinearLayout r) {
        r.removeAllViews();
        for (String id : selected(a)) {
            Item it = find(a, id);
            if (it == null) continue;
            TextView t = chip(a, r, it.label);
            t.setOnClickListener(v -> { Ui.haptic(v); run(a, it); });
            if (it.switchMod != 0) t.setOnLongClickListener(v -> { Ui.haptic(v); switchTap(it.switchMod, true); return true; });
        }
        TextView edit = chip(a, r, "✎ Edit");
        edit.setTextColor(Ui.ACCENT_LIGHT);
        edit.setOnClickListener(v -> a.startActivity(new Intent(a, QuickKeysActivity.class)));
    }

    private static TextView chip(Activity a, LinearLayout r, String label) {
        TextView t = Ui.text(a, label, 14, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(a, 52));
        t.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        Ui.setBg(t, Ui.CARD2, 12);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.setMarginEnd(Ui.dp(a, 8));
        r.addView(t, lp);
        return t;
    }
}
