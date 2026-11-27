package com.edward.pcremote;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {
    private static final String[] MODES = {"auto", "wifi", "hotspot", "bt"};
    private static final String[] MODE_LABELS = {"Auto", "Wi-Fi", "Hotspot", "Bluetooth"};

    private TextView pcName, status, actionBtn, updateBanner;
    private final List<View> tiles = new ArrayList<>();
    private final TextView[] modeViews = new TextView[MODES.length];
    private static long lastUpdateCheck = 0;
    private static boolean pickerShown = false;
    private boolean night;
    /** Opening the app goes straight to Mouse & keyboard once connected (Settings can turn this off). */
    private boolean autoOpen;

    @Override protected void onCreate(Bundle b) {
        needsConnection = false;
        super.onCreate(b);
        night = Ui.night;
        AppIcons.addPresets(this);
        autoOpen = b == null && Prefs.sp(this).getBoolean("auto_mouse", true);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);
        sv.addView(root);

        // ---- title row ----
        LinearLayout top = Ui.hbox(this);
        TextView title = Ui.text(this, "PC Remote", 24, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView gear = Ui.text(this, "⚙", 26, Ui.TEXT);
        gear.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(gear);
        LinearLayout.LayoutParams tl = Ui.lp(-1, -2);
        tl.bottomMargin = Ui.dp(this, 12);
        root.addView(top, tl);

        // ---- header card ----
        LinearLayout card = Ui.vbox(this);
        card.setBackground(Ui.round(this, Ui.CARD, 22));
        if (!Ui.night) card.setElevation(Ui.dp(this, 1.5f));
        card.setPadding(p, p, p, p);
        LinearLayout row = Ui.hbox(this);
        TextView icon = Ui.text(this, "💻", 30, Ui.TEXT);
        icon.setPadding(0, 0, Ui.dp(this, 12), 0);
        row.addView(icon);
        LinearLayout names = Ui.vbox(this);
        pcName = Ui.text(this, "PC Remote", 21, Ui.TEXT);
        pcName.setTypeface(Typeface.DEFAULT_BOLD);
        status = Ui.text(this, "", 14, Ui.MUTED);
        names.addView(pcName);
        names.addView(status);
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);

        // connection type
        TextView ct = Ui.text(this, "Connect using", 12, Ui.MUTED);
        ct.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        card.addView(ct);
        LinearLayout modes = Ui.hbox(this);
        for (int i = 0; i < MODES.length; i++) {
            final String m = MODES[i];
            TextView o = Ui.text(this, MODE_LABELS[i], 13, Ui.TEXT);
            o.setGravity(Gravity.CENTER);
            o.setPadding(Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9));
            o.setOnClickListener(v -> { Ui.haptic(v); setMode(m); });
            modeViews[i] = o;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < MODES.length - 1) lp.setMarginEnd(Ui.dp(this, 6));
            modes.addView(o, lp);
        }
        card.addView(modes, Ui.lp(-1, -2));

        LinearLayout btns = Ui.hbox(this);
        btns.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView sw = Ui.button(this, "↔  Switch PC", Ui.CARD2, v -> startActivity(new Intent(this, PickerActivity.class)));
        actionBtn = Ui.button(this, "Disconnect", Ui.ACCENT, v -> onAction());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1);
        l1.setMarginEnd(Ui.dp(this, 10));
        btns.addView(sw, l1);
        btns.addView(actionBtn, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(btns);
        root.addView(card, Ui.lp(-1, -2));

        updateBanner = Ui.button(this, "", Ui.ACCENT, null);
        updateBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams ul = Ui.lp(-1, -2);
        ul.topMargin = Ui.dp(this, 12);
        root.addView(updateBanner, ul);

        // ---- tiles ----
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(0, Ui.dp(this, 8), 0, 0);
        addTile(grid, "🖱", "Mouse & keyboard", TouchpadActivity.class);
        addTile(grid, "🖥", "Screen", ScreenActivity.class);
        addTile(grid, "🎵", "Media", MediaActivity.class);
        addTile(grid, "📁", "Files", FilesActivity.class);
        addTile(grid, "⭐", "My buttons & apps", CustomActivity.class);
        addTile(grid, "⚡", "Power", PowerActivity.class);
        root.addView(grid, Ui.lp(-1, -2));

        TextView ver = Ui.text(this, "PC Remote " + Updater.currentVersion(this), 12, Ui.MUTED);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, Ui.dp(this, 14), 0, 0);
        root.addView(ver, Ui.lp(-1, -2));

        setContentView(sv);
        askPermissions();
    }

    private void addTile(GridLayout grid, String icon, String label, Class<?> cls) {
        boolean always = cls == CustomActivity.class;
        LinearLayout t = Ui.tile(this, icon, label, v -> {
            if (!always && !conn.isConnected()) { Ui.toast(this, "Connect to a PC first"); return; }
            startActivity(new Intent(this, cls));
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = Ui.dp(this, 112);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        grid.addView(t, lp);
        if (!always) tiles.add(t);
    }

    private void setMode(String m) {
        if (m.equals(Prefs.connMode(this)) && conn.state != Conn.IDLE) return;
        Prefs.put(this, "conn_mode", m);
        if (m.equals("bt") && Build.VERSION.SDK_INT >= 31 && !Discovery.hasBtPermission(this)) {
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 1);
        }
        if (conn.state != Conn.IDLE) conn.disconnect();
        conn.error = "";
        Watcher.connectNow(this);
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        if (conn.state == Conn.IDLE && !conn.autoPaused) Watcher.connectNow(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (night != Prefs.sp(this).getBoolean("night", false)) { recreate(); return; }
        render();
        maybeAutoOpen();
        // Searching and reconnecting run automatically while the app is open (see Watcher).
        if (Prefs.pcs(this).isEmpty() && !pickerShown) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && conn.state == Conn.IDLE && Prefs.pcs(this).isEmpty() && !pickerShown) {
                    pickerShown = true;
                    startActivity(new Intent(this, PickerActivity.class).putExtra("first", true));
                }
            }, 4000);
        }
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheck > 6 * 3600 * 1000L) {
            lastUpdateCheck = now;
            Updater.latest((r, err) -> {
                if (r == null || r.url == null || r.build <= Updater.currentBuild(this)) return;
                updateBanner.setText("⬇  Update available: " + r.version + " (" + Ui.size(r.size) + ")");
                updateBanner.setOnClickListener(v -> Updater.offer(this, r));
                updateBanner.setVisibility(View.VISIBLE);
            });
        }
    }

    private void onAction() {
        if (conn.state == Conn.IDLE) Watcher.connectNow(this);
        else conn.disconnect();
        render();
    }

    private void render() {
        String mode = Prefs.connMode(this);
        for (int i = 0; i < MODES.length; i++) {
            boolean sel = MODES[i].equals(mode);
            Ui.setBg(modeViews[i], sel ? Ui.ACCENT : Ui.CARD2, 10);
            modeViews[i].setTextColor(sel ? Ui.WHITE : Ui.TEXT);
        }
        JSONObject last = conn.state != Conn.IDLE ? conn.pc : (Prefs.pcs(this).isEmpty() ? null : Prefs.pcs(this).get(0));
        pcName.setText(last == null ? "No PC yet" : last.optString("name", "PC"));
        String s;
        switch (conn.state) {
            case Conn.CONNECTED: s = "● Connected over " + conn.transportLabel(); break;
            case Conn.CONNECTING: s = conn.stage.isEmpty() ? "Connecting over " + conn.transportLabel() + "…" : conn.stage; break;
            case Conn.WAITING: s = "Click \"Allow\" on the PC to finish connecting"; break;
            default:
                if (Watcher.reconnecting()) s = "Connection dropped. Reconnecting…";
                else if (Watcher.searching && conn.error.isEmpty()) s = "Looking for your PC…";
                else if (!conn.error.isEmpty()) s = conn.error + (conn.autoPaused ? "" : "  (still looking…)");
                else if (conn.autoPaused) s = "Disconnected";
                else s = "Not connected. Is PC Remote running on the PC?";
        }
        status.setText(s);
        status.setTextColor(conn.state == Conn.CONNECTED ? Ui.ON : Ui.MUTED);
        actionBtn.setText(conn.state == Conn.IDLE ? (conn.autoPaused ? "Connect" : "Searching…") : "Disconnect");
        for (View t : tiles) t.setAlpha(conn.isConnected() ? 1f : 0.45f);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (Intent.ACTION_MAIN.equals(i.getAction()) || i.getAction() == null)
            autoOpen = Prefs.sp(this).getBoolean("auto_mouse", true);
    }

    private void maybeAutoOpen() {
        if (autoOpen && conn.isConnected() && !isFinishing()) {
            autoOpen = false;
            startActivity(new Intent(this, TouchpadActivity.class));
        }
    }

    @Override public void onState(int s) { render(); if (s == Conn.CONNECTED) maybeAutoOpen(); }

    @Override public void onMessage(String t, JSONObject m) { }
}
