package com.edward.pcremote;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pick, order and add the keys shown in the quick keys row. */
public class QuickKeysActivity extends BaseActivity {
    private LinearLayout box;

    @Override protected void onCreate(Bundle b) {
        needsConnection = false;
        super.onCreate(b);
        box = Ui.screen(this, "Quick keys row", true);
        Ui.barButton(this, "Reset", v -> { QuickKeys.setSelected(this, new ArrayList<>(QuickKeys.DEFAULT)); render(); });
        render();
    }

    private void render() {
        box.removeAllViews();
        List<String> sel = QuickKeys.selected(this);
        box.addView(Ui.text(this, "This row is always shown on the Mouse and Screen pages. Tap ＋ to add a key, ▲▼ to reorder, ✕ to remove.\n\n"
                + "Alt+Tab: tap to open the window switcher, tap again for the next window (hold for the previous one). "
                + "It picks the window a moment after your last tap, or straight away when you touch the touchpad.", 14, Ui.MUTED));

        box.addView(Ui.section(this, "In your row (" + sel.size() + ")"));
        for (int i = 0; i < sel.size(); i++) {
            QuickKeys.Item it = QuickKeys.find(this, sel.get(i));
            if (it == null) continue;
            final int idx = i;
            LinearLayout row = row(it.label, it.combo);
            row.addView(small("▲", () -> { if (idx > 0) { sel.add(idx - 1, sel.remove(idx)); save(sel); } }));
            row.addView(small("▼", () -> { if (idx < sel.size() - 1) { sel.add(idx + 1, sel.remove(idx)); save(sel); } }));
            row.addView(small("✕", () -> { sel.remove(idx); save(sel); }));
            box.addView(row, rowLp());
        }

        TextView make = Ui.button(this, "＋  Make your own shortcut", Ui.ACCENT, v -> makeOwn());
        LinearLayout.LayoutParams ml = Ui.lp(-1, -2);
        ml.topMargin = Ui.dp(this, 10);
        box.addView(make, ml);

        String group = "";
        for (QuickKeys.Item it : QuickKeys.all(this)) {
            if (sel.contains(it.id)) continue;
            if (!it.group.equals(group)) { group = it.group; box.addView(Ui.section(this, group)); }
            LinearLayout row = row(it.label, it.switchMod != 0 ? "hold-and-tap switcher" : it.combo);
            row.addView(small("＋", () -> { sel.add(it.id); save(sel); }));
            if (it.id.startsWith("own:")) row.addView(small("🗑", () -> deleteOwn(it.id.substring(4))));
            box.addView(row, rowLp());
        }
    }

    private void save(List<String> sel) {
        QuickKeys.setSelected(this, sel);
        render();
    }

    private void makeOwn() {
        LinearLayout w = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        w.setPadding(p, Ui.dp(this, 8), p, 0);
        EditText name = new EditText(this);
        name.setHint("Name on the button (e.g. Mute mic)");
        name.setTextColor(Ui.TEXT);
        EditText combo = new EditText(this);
        combo.setHint("Shortcut (e.g. ctrl+shift+m)");
        combo.setTextColor(Ui.TEXT);
        w.addView(name);
        w.addView(combo);
        w.addView(Ui.text(this, "Join keys with +. Names: ctrl alt shift win esc tab enter space del home end pgup pgdn up down left right f1–f12 prtsc, letters and numbers.", 12, Ui.MUTED));
        new AlertDialog.Builder(this).setTitle("Your shortcut").setView(w)
                .setPositiveButton("Add", (d, x) -> {
                    String c = combo.getText().toString().trim();
                    if (Keys.parse(c) == null) { Ui.toast(this, "I don't understand \"" + c + "\""); return; }
                    try {
                        String uid = UUID.randomUUID().toString().substring(0, 8);
                        JSONObject o = new JSONObject();
                        o.put("uid", uid);
                        String n = name.getText().toString().trim();
                        o.put("label", n.isEmpty() ? c : n);
                        o.put("combo", c);
                        JSONArray a = QuickKeys.own(this);
                        a.put(o);
                        QuickKeys.setOwn(this, a);
                        List<String> sel = QuickKeys.selected(this);
                        sel.add("own:" + uid);
                        save(sel);
                    } catch (Exception ignored) {}
                }).setNegativeButton("Cancel", null).show();
    }

    private void deleteOwn(String uid) {
        JSONArray a = QuickKeys.own(this), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!uid.equals(a.optJSONObject(i).optString("uid"))) out.put(a.optJSONObject(i));
        QuickKeys.setOwn(this, out);
        render();
    }

    private LinearLayout row(String label, String sub) {
        LinearLayout r = Ui.hbox(this);
        r.setBackground(Ui.round(this, Ui.CARD, 14));
        int p = Ui.dp(this, 10);
        r.setPadding(Ui.dp(this, 14), p, p, p);
        LinearLayout col = Ui.vbox(this);
        col.addView(Ui.text(this, label, 15, Ui.TEXT));
        if (sub != null && !sub.isEmpty() && !sub.equalsIgnoreCase(label)) col.addView(Ui.text(this, sub, 12, Ui.MUTED));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
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
