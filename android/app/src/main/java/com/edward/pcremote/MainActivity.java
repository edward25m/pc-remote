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
    private TextView pcName, status, actionBtn, updateBanner;
    private final List<View> tiles = new ArrayList<>();
    private static boolean manualDisconnect = false;
    private static boolean searching = false;
    private static long lastUpdateCheck = 0;
    private static boolean pickerShown = false;

    @Override protected void onCreate(Bundle b) {
        needsConnection = false;
        super.onCreate(b);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);
        sv.addView(root);

        // ---- header card ----
        LinearLayout card = Ui.vbox(this);
        card.setBackground(Ui.round(this, Ui.CARD, 22));
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

        LinearLayout btns = Ui.hbox(this);
        btns.setPadding(0, Ui.dp(this, 14), 0, 0);
        TextView sw = Ui.button(this, "⇄  Switch PC", Ui.CARD2, v -> startActivity(new Intent(this, PickerActivity.class)));
        actionBtn = Ui.button(this, "Disconnect", Ui.ACCENT, v -> onAction());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1);
        l1.setMarginEnd(Ui.dp(this, 10));
        btns.addView(sw, l1);
        btns.addView(actionBtn, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(btns);
        root.addView(card, Ui.lp(-1, -2));

        updateBanner = Ui.button(this, "", 0xFF1D4ED8, null);
        updateBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams ul = Ui.lp(-1, -2);
        ul.topMargin = Ui.dp(this, 12);
        root.addView(updateBanner, ul);

        // ---- tiles ----
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(0, Ui.dp(this, 8), 0, 0);
        addTile(grid, "🖱", "Mouse", TouchpadActivity.class, false);
        addTile(grid, "⌨", "Keyboard", TouchpadActivity.class, true);
        addTile(grid, "🎵", "Media", MediaActivity.class, false);
        addTile(grid, "🖥", "Screen", ScreenActivity.class, false);
        addTile(grid, "📁", "Files", FilesActivity.class, false);
        addTile(grid, "⏻", "Power", PowerActivity.class, false);
        addTile(grid, "⭐", "My buttons", CustomActivity.class, false);
        addTile(grid, "⚙", "Settings", SettingsActivity.class, false);
        root.addView(grid, Ui.lp(-1, -2));

        TextView ver = Ui.text(this, "PC Remote " + Updater.currentVersion(this), 12, Ui.MUTED);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, Ui.dp(this, 14), 0, 0);
        root.addView(ver, Ui.lp(-1, -2));

        setContentView(sv);
        askPermissions();
    }

    private void addTile(GridLayout grid, String icon, String label, Class<?> cls, boolean keyboard) {
        boolean always = cls == SettingsActivity.class || cls == CustomActivity.class;
        LinearLayout t = Ui.tile(this, icon, label, v -> {
            if (!always && !conn.isConnected()) { Ui.toast(this, "Connect to a PC first"); return; }
            startActivity(new Intent(this, cls).putExtra("keyboard", keyboard));
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = Ui.dp(this, 112);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        grid.addView(t, lp);
        if (!always) tiles.add(t);
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
        if (conn.state == Conn.IDLE && !manualDisconnect) search();
    }

    @Override protected void onResume() {
        super.onResume();
        render();
        if (conn.state == Conn.IDLE && !manualDisconnect) search();
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

    private void search() {
        if (searching) return;
        searching = true;
        render();
        Discovery.autoConnect(this, found -> {
            searching = false;
            if (conn.state == Conn.IDLE && Prefs.pcs(this).isEmpty() && !isFinishing() && !pickerShown) {
                pickerShown = true;
                // first time: let the user pick (or see help on how to set up the PC)
                startActivity(new Intent(this, PickerActivity.class).putExtra("first", true));
            }
            render();
        });
    }

    private void onAction() {
        if (conn.state == Conn.IDLE) {
            manualDisconnect = false;
            search();
        } else {
            manualDisconnect = true;
            conn.disconnect();
        }
        render();
    }

    private void render() {
        JSONObject last = conn.state != Conn.IDLE ? conn.pc : (Prefs.pcs(this).isEmpty() ? null : Prefs.pcs(this).get(0));
        pcName.setText(last == null ? "No PC yet" : last.optString("name", "PC"));
        String s;
        switch (conn.state) {
            case Conn.CONNECTED: s = "● Connected over " + (conn.isWifi() ? "Wi-Fi" : "Bluetooth"); break;
            case Conn.CONNECTING: s = "Connecting" + (conn.isWifi() ? "…" : " over Bluetooth…"); break;
            case Conn.WAITING: s = "Click \"Allow\" on the PC to finish connecting"; break;
            default:
                if (searching) s = "Looking for your PC…";
                else if (!conn.error.isEmpty()) s = conn.error;
                else if (manualDisconnect) s = "Disconnected";
                else s = "Not connected. Is PC Remote running on the PC?";
        }
        status.setText(s);
        status.setTextColor(conn.state == Conn.CONNECTED ? Ui.ON : Ui.MUTED);
        actionBtn.setText(conn.state == Conn.IDLE ? (searching ? "Searching…" : "Connect") : "Disconnect");
        for (View t : tiles) t.setAlpha(conn.isConnected() ? 1f : 0.45f);
    }

    @Override public void onState(int s) {
        if (s == Conn.CONNECTED) manualDisconnect = false;
        render();
    }

    @Override public void onMessage(String t, JSONObject m) { }
}
