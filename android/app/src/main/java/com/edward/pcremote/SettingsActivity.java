package com.edward.pcremote;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

public class SettingsActivity extends BaseActivity {
    private LinearLayout box;
    private TextView pcUpdateStatus;

    @Override protected void onCreate(Bundle b) {
        needsConnection = false;
        super.onCreate(b);
        box = Ui.screen(this, "Settings", true);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        box.removeAllViews();

        box.addView(Ui.section(this, "Look"));
        box.addView(toggle("Night mode (AMOLED black)", "Pure black screens, easy on the eyes and battery at night",
                Ui.night, on -> { Prefs.put(this, "night", on); recreate(); }), lp());

        box.addView(Ui.section(this, "Start-up"));
        box.addView(toggle("Open Mouse & keyboard straight away", "When you open the app, go to the touchpad as soon as it's connected",
                Prefs.sp(this).getBoolean("auto_mouse", true), on -> Prefs.put(this, "auto_mouse", on)), lp());

        box.addView(Ui.section(this, "Quick keys row"));
        box.addView(link("Choose quick keys", "The always-visible row on the Mouse and Screen pages: Alt+Tab, Alt+F4, Win, copy/paste… or make your own",
                () -> startActivity(new Intent(this, QuickKeysActivity.class))), lp());

        box.addView(Ui.section(this, "Notification bar"));
        box.addView(link("Choose buttons", "Media keys, Alt+F4, your own buttons…", () -> startActivity(new Intent(this, NotifBarActivity.class))), lp());

        box.addView(Ui.section(this, "Phone volume buttons"));
        box.addView(choice("Control the PC's volume", new String[]{"Off", "Only in this app", "Always (even in other apps)"},
                new String[]{"off", "app", "always"}, Prefs.volMode(this), v -> { Prefs.put(this, "vol_mode", v); RemoteService.refresh(this); }), lp());

        box.addView(Ui.section(this, "Clipboard"));
        box.addView(toggle("Shared clipboard", "Copy on one, paste on the other (text). Phone → PC happens when you open this app.",
                Prefs.clipSync(this), on -> { Prefs.put(this, "clip_sync", on); conn.send(Conn.msg("clipsync", "on", on)); }), lp());

        box.addView(Ui.section(this, "Mouse"));
        box.addView(slider("Touchpad speed", "mouse_speed", Prefs.mouseSpeed(this)), lp());
        box.addView(slider("Scroll speed", "scroll_speed", Prefs.scrollSpeed(this)), lp());
        box.addView(slider("Air mouse speed", "air_speed", Prefs.airSpeed(this)), lp());
        box.addView(toggle("Reverse scrolling", "Two-finger scroll moves the other way", Prefs.invertScroll(this),
                on -> Prefs.put(this, "invert_scroll", on)), lp());

        box.addView(Ui.section(this, "Screen view"));
        box.addView(choice("Picture quality", new String[]{"Low (fastest)", "Medium", "High", "Best"},
                new String[]{"0", "1", "2", "3"}, String.valueOf(Prefs.screenQuality(this)), v -> Prefs.put(this, "screen_q", Integer.parseInt(v))), lp());

        box.addView(Ui.section(this, "Videos & music on the PC"));
        box.addView(choice("When I tap a media file", new String[]{"Ask me", "Play on laptop", "Play on phone"},
                new String[]{"ask", "pc", "phone"}, Prefs.mediaDefault(this), v -> Prefs.put(this, "media_default", v)), lp());

        box.addView(Ui.section(this, "Files"));
        box.addView(link("Received files folder", Transfers.hasAllFilesAccess()
                        ? "Saved in \"PC Remote\" (open My Files to see it)"
                        : "Saved in Download/PC Remote. Tap to allow a \"PC Remote\" folder in My Files instead.",
                () -> {
                    if (Transfers.hasAllFilesAccess()) return;
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                }), lp());

        box.addView(Ui.section(this, "Staying connected"));
        box.addView(link("Battery: don't put PC Remote to sleep", "Samsung may close the connection in the background. Find PC Remote and choose \"Unrestricted\" / \"Don't optimise\".",
                () -> {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                }), lp());

        box.addView(Ui.section(this, "Updates"));
        box.addView(link("Update this app", "You have version " + Updater.currentVersion(this) + ". Tap to check (shows the size first).",
                () -> Updater.check(this, false)), lp());
        String pcSub = conn.isConnected() ? conn.pcName() + " has version " + conn.pcVersion + ". Tap to update it." : "Connect to a PC to update its program.";
        TextView pcRow = link("Update the PC program", pcSub, () -> {
            if (!conn.isConnected()) { Ui.toast(this, "Connect to a PC first"); return; }
            new AlertDialog.Builder(this).setTitle("Update the PC program?")
                    .setMessage("The PC downloads the update using its own internet, not your phone's data. It restarts PC Remote, so you'll reconnect in a few seconds.")
                    .setPositiveButton("Update PC", (d, w) -> conn.send(Conn.msg("upd_pc")))
                    .setNegativeButton("Cancel", null).show();
        });
        box.addView(pcRow, lp());
        pcUpdateStatus = Ui.text(this, "", 13, Ui.ACCENT_LIGHT);
        box.addView(pcUpdateStatus);

        box.addView(Ui.section(this, "Other PCs"));
        box.addView(link("Get PC Remote for Windows", "Open this link on the PC to download PCRemote.exe",
                () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/edward25m/pc-remote/releases/latest")))), lp());
    }

    @Override public void onMessage(String t, JSONObject m) {
        super.onMessage(t, m);
        if (t.equals("upd_status") && pcUpdateStatus != null) pcUpdateStatus.setText(m.optString("msg"));
    }

    // ------------------------------------------------------------ widgets

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams lp = Ui.lp(-1, -2);
        lp.bottomMargin = Ui.dp(this, 8);
        return lp;
    }

    private LinearLayout card() {
        LinearLayout c = Ui.vbox(this);
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, p);
        Ui.setBg(c, Ui.CARD, 16);
        return c;
    }

    private TextView link(String title, String sub, Runnable r) {
        TextView t = Ui.text(this, "", 16, Ui.TEXT);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title + "\n" + sub);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length(), 0);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED), title.length() + 1, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.82f), title.length() + 1, sb.length(), 0);
        t.setText(sb);
        int p = Ui.dp(this, 14);
        t.setPadding(p, p, p, p);
        Ui.setBg(t, Ui.CARD, 16);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    interface OnPick { void pick(String v); }
    interface OnToggle { void set(boolean on); }

    private LinearLayout choice(String title, String[] labels, String[] values, String current, OnPick cb) {
        LinearLayout c = card();
        TextView t = Ui.text(this, title, 16, Ui.TEXT);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.addView(t);
        LinearLayout row = Ui.hbox(this);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView[] views = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final String v = values[i];
            TextView o = Ui.text(this, labels[i], 13, Ui.TEXT);
            o.setGravity(android.view.Gravity.CENTER);
            o.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
            Ui.setBg(o, v.equals(current) ? Ui.ACCENT : Ui.CARD2, 10);
            views[i] = o;
            o.setOnClickListener(x -> {
                for (int j = 0; j < views.length; j++) Ui.setBg(views[j], views[j] == o ? Ui.ACCENT : Ui.CARD2, 10);
                cb.pick(v);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i < labels.length - 1) lp.setMarginEnd(Ui.dp(this, 6));
            row.addView(o, lp);
        }
        c.addView(row, Ui.lp(-1, -2));
        return c;
    }

    private LinearLayout toggle(String title, String sub, boolean on, OnToggle cb) {
        LinearLayout c = card();
        LinearLayout row = Ui.hbox(this);
        LinearLayout texts = Ui.vbox(this);
        TextView t = Ui.text(this, title, 16, Ui.TEXT);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        texts.addView(t);
        texts.addView(Ui.text(this, sub, 13, Ui.MUTED));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Switch s = new Switch(this);
        s.setChecked(on);
        s.setOnCheckedChangeListener((v, checked) -> cb.set(checked));
        row.addView(s);
        c.addView(row);
        return c;
    }

    private LinearLayout slider(String title, String key, int value) {
        LinearLayout c = card();
        TextView t = Ui.text(this, title, 16, Ui.TEXT);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(value);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { if (u) Prefs.put(SettingsActivity.this, key, p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        c.addView(sb, Ui.lp(-1, -2));
        return c;
    }
}
