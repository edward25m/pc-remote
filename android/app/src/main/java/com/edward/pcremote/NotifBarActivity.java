package com.edward.pcremote;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Choose and order the buttons on the notification bar (up to 6). */
public class NotifBarActivity extends BaseActivity {
    private LinearLayout box;

    @Override protected void onCreate(Bundle b) {
        needsConnection = false;
        super.onCreate(b);
        box = Ui.screen(this, "Notification bar", true);
        Ui.barButton(this, "Reset", v -> { Prefs.setNotifBar(this, new java.util.ArrayList<>(Prefs.DEFAULT_BAR)); changed(); });
        render();
    }

    private void changed() {
        RemoteService.refresh(this);
        render();
    }

    private void render() {
        box.removeAllViews();
        List<String> bar = Prefs.notifBar(this);
        TextView intro = Ui.text(this, "These buttons show in your notifications while connected. \"Clear all\" won't remove them. Up to 6 buttons.", 14, Ui.MUTED);
        box.addView(intro);

        box.addView(Ui.section(this, "On the bar (" + bar.size() + "/6)"));
        for (int i = 0; i < bar.size(); i++) {
            Keys.Action a = Keys.find(this, bar.get(i));
            if (a == null) continue;
            final int idx = i;
            LinearLayout row = row(a);
            row.addView(small("▲", () -> { if (idx > 0) { bar.add(idx - 1, bar.remove(idx)); Prefs.setNotifBar(this, bar); changed(); } }));
            row.addView(small("▼", () -> { if (idx < bar.size() - 1) { bar.add(idx + 1, bar.remove(idx)); Prefs.setNotifBar(this, bar); changed(); } }));
            row.addView(small("✕", () -> { bar.remove(idx); Prefs.setNotifBar(this, bar); changed(); }));
            box.addView(row, rowLp());
        }
        if (bar.isEmpty()) box.addView(Ui.text(this, "No buttons: the notification will just show the PC's name.", 14, Ui.MUTED));

        box.addView(Ui.section(this, "Add a button"));
        for (Keys.Action a : Keys.all(this)) {
            if (bar.contains(a.id)) continue;
            LinearLayout row = row(a);
            row.addView(small("＋", () -> {
                if (bar.size() >= 6) { Ui.toast(this, "The bar holds 6 buttons. Remove one first."); return; }
                bar.add(a.id);
                Prefs.setNotifBar(this, bar);
                changed();
            }));
            box.addView(row, rowLp());
        }
    }

    private LinearLayout row(Keys.Action a) {
        LinearLayout r = Ui.hbox(this);
        r.setBackground(Ui.round(this, Ui.CARD, 14));
        int p = Ui.dp(this, 10);
        r.setPadding(Ui.dp(this, 14), p, p, p);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        int pad = Ui.dp(this, 7);
        if (a.image != null) iv.setImageBitmap(a.image);
        else { iv.setImageResource(a.icon); iv.setColorFilter(Ui.TEXT); iv.setPadding(pad / 2, pad / 2, pad / 2, pad / 2); }
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        il.setMarginEnd(Ui.dp(this, 12));
        r.addView(iv, il);
        r.addView(Ui.text(this, a.label, 15, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }

    private TextView small(String s, Runnable r) {
        TextView t = Ui.text(this, s, 18, Ui.ACCENT_LIGHT);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        t.setOnClickListener(v -> { Ui.haptic(v); r.run(); });
        return t;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = Ui.lp(-1, -2);
        lp.bottomMargin = Ui.dp(this, 8);
        return lp;
    }
}
