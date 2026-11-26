package com.edward.pcremote;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.GridLayout;
import android.widget.LinearLayout;

/** Shut down, restart, sleep, lock… */
public class PowerActivity extends BaseActivity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout c = Ui.screen(this, "Power", true);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(2);
        add(g, "🔒", "Lock", "lock", false);
        add(g, "🌙", "Sleep", "sleep", true);
        add(g, "⏻", "Shut down", "shutdown", true);
        add(g, "🔄", "Restart", "restart", true);
        add(g, "🚪", "Sign out", "signout", true);
        add(g, "💤", "Hibernate", "hibernate", true);
        add(g, "🖥", "Screen off", "screen_off", false);
        add(g, "✋", "Cancel shutdown", "cancel", false);
        c.addView(g, Ui.lp(-1, -2));
    }

    private void add(GridLayout g, String icon, String label, String action, boolean confirm) {
        LinearLayout t = Ui.tile(this, icon, label, v -> {
            if (!confirm) { conn.power(action); Ui.toast(this, label); return; }
            new AlertDialog.Builder(this)
                    .setTitle(label + "?")
                    .setMessage(conn.pcName() + " will " + label.toLowerCase() + " now. Unsaved work may be lost.")
                    .setPositiveButton(label, (d, w) -> conn.power(action))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = Ui.dp(this, 110);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        g.addView(t, lp);
    }
}
