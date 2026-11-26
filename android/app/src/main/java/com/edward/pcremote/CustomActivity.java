package com.edward.pcremote;

import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/** Your own buttons: a shortcut, opening a program/website, or typing a piece of text. */
public class CustomActivity extends BaseActivity {
    private LinearLayout box;

    @Override protected void onCreate(Bundle b) {
        needsConnection = false;
        super.onCreate(b);
        box = Ui.screen(this, "My buttons", true);
        Ui.barButton(this, "+ Add", v -> edit(null, -1));
        render();
    }

    private void render() {
        box.removeAllViews();
        JSONArray a = Prefs.customButtons(this);
        if (a.length() == 0) {
            TextView t = Ui.text(this, "No buttons yet.\n\nTap \"+ Add\" to make one. Examples:\n• Any program → App on the PC (uses its real icon)\n• Task Manager → shortcut ctrl+shift+esc\n• YouTube → open https://youtube.com\n• Spotify → open spotify:\n• My email → type text\n\nLong-press a button to change or delete it. You can also put your buttons on the notification bar (Settings → Notification bar).", 15, Ui.MUTED);
            box.addView(t);
            return;
        }
        GridLayout g = new GridLayout(this);
        g.setColumnCount(2);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            final int idx = i;
            String icon = o.optString("icon");
            LinearLayout t = Ui.tile(this, Keys.image(o), icon.isEmpty() ? ("app".equals(o.optString("type")) ? "🎮" : "⭐") : icon, o.optString("label"), v -> {
                if (!conn.isConnected()) { Ui.toast(this, "Connect to a PC first"); return; }
                Keys.runButton(o);
            });
            t.setOnLongClickListener(v -> { edit(o, idx); return true; });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = Ui.dp(this, 104);
            int m = Ui.dp(this, 6);
            lp.setMargins(m, m, m, m);
            g.addView(t, lp);
        }
        box.addView(g, Ui.lp(-1, -2));
    }

    private EditText[] pickTarget;

    @Override public void onMessage(String t, JSONObject m) {
        super.onMessage(t, m);
        if (t.equals("app_icon")) render();
        if (t.equals("apps") && pickTarget != null) showAppList(m.optJSONArray("items"));
    }

    private void showAppList(JSONArray items) {
        if (items == null || items.length() == 0) { Ui.toast(this, "No apps found on the PC"); return; }
        java.util.List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < items.length(); i++) all.add(items.optString(i));
        LinearLayout w = Ui.vbox(this);
        w.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        EditText search = new EditText(this);
        search.setHint("Search");
        search.setTextColor(Ui.TEXT);
        w.addView(search);
        android.widget.ListView lv = new android.widget.ListView(this);
        java.util.List<String> shown = new java.util.ArrayList<>(all);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, shown) {
            @Override public android.view.View getView(int pos, android.view.View cv, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(Ui.TEXT);
                return tv;
            }
        };
        lv.setAdapter(ad);
        w.addView(lv, Ui.lp(-1, Ui.dp(this, 360)));
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Apps on " + conn.pcName()).setView(w).setNegativeButton("Cancel", null).show();
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                shown.clear();
                String q = s.toString().toLowerCase();
                for (String n : all) if (n.toLowerCase().contains(q)) shown.add(n);
                ad.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });
        lv.setOnItemClickListener((p, v, pos, id) -> {
            String name = shown.get(pos);
            if (pickTarget != null) {
                if (pickTarget[0].getText().toString().trim().isEmpty()) pickTarget[0].setText(name);
                pickTarget[1].setText(name);
            }
            dlg.dismiss();
        });
    }

    private EditText field(LinearLayout w, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        w.addView(e, Ui.lp(-1, -2));
        return e;
    }

    private void edit(JSONObject existing, int index) {
        LinearLayout w = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        w.setPadding(p, Ui.dp(this, 8), p, 0);
        EditText label = field(w, "Name (e.g. Task Manager)", existing == null ? "" : existing.optString("label"));
        EditText icon = field(w, "Icon: an emoji (optional)", existing == null ? "" : existing.optString("icon"));

        RadioGroup rg = new RadioGroup(this);
        String[][] types = {{"keys", "Keyboard shortcut"}, {"run", "Open a file, folder or website"}, {"text", "Type some text"}, {"app", "App on the PC (real icon)"}};
        for (int i = 0; i < types.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(types[i][1]);
            r.setTextColor(Ui.TEXT);
            r.setId(100 + i);
            rg.addView(r);
        }
        w.addView(rg);
        EditText value = field(w, "", existing == null ? "" : existing.optString("value"));
        TextView help = Ui.text(this, "", 12, Ui.MUTED);
        w.addView(help);
        TextView pickBtn = Ui.button(this, "Pick an app from the PC…", Ui.CARD2, v -> {
            if (!conn.isConnected()) { Ui.toast(this, "Connect to a PC first"); return; }
            pickTarget = new EditText[]{label, value};
            conn.send(Conn.msg("list_apps"));
            Ui.toast(this, "Getting the PC's apps…");
        });
        LinearLayout.LayoutParams pl = Ui.lp(-1, -2);
        pl.topMargin = Ui.dp(this, 8);
        w.addView(pickBtn, pl);

        rg.setOnCheckedChangeListener((group, id) -> {
            pickBtn.setVisibility(id == 103 ? android.view.View.VISIBLE : android.view.View.GONE);
            switch (id) {
                case 100:
                    value.setHint("e.g. ctrl+shift+esc");
                    help.setText("Join keys with +. Several shortcuts in a row: separate with commas (win+r, ctrl+a). Names: ctrl alt shift win esc tab enter space del home end pgup pgdn up down left right f1–f12 prtsc, letters, numbers.");
                    break;
                case 103:
                    value.setHint("App name, e.g. PotPlayer");
                    help.setText("The name as it appears in the PC's Start menu. Tap below to pick it from a list. The real app icon is added automatically.");
                    break;
                case 101:
                    value.setHint("e.g. notepad, chrome, https://youtube.com");
                    help.setText("Anything you could type in the Windows Run box (Win+R): a program name, a full path to a file or folder, or a web address.");
                    break;
                default:
                    value.setHint("Text to type");
                    help.setText("Types this text wherever the cursor is on the PC.");
            }
        });
        String type = existing == null ? "keys" : existing.optString("type", "keys");
        rg.check(type.equals("run") ? 101 : type.equals("text") ? 102 : type.equals("app") ? 103 : 100);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(w);
        AlertDialog.Builder bld = new AlertDialog.Builder(this).setTitle(existing == null ? "New button" : "Edit button").setView(sv)
                .setPositiveButton("Save", (d, x) -> {
                    try {
                        int ck = rg.getCheckedRadioButtonId();
                        String t = ck == 101 ? "run" : ck == 102 ? "text" : ck == 103 ? "app" : "keys";
                        String v = value.getText().toString().trim();
                        if (t.equals("keys") && v.split(",").length > 0) {
                            for (String part : v.split(",")) if (Keys.parse(part) == null) { Ui.toast(this, "I don't understand \"" + part.trim() + "\""); return; }
                        }
                        JSONObject o = new JSONObject();
                        o.put("uid", existing == null ? UUID.randomUUID().toString().substring(0, 8) : existing.optString("uid"));
                        o.put("label", label.getText().toString().trim().isEmpty() ? "Button" : label.getText().toString().trim());
                        o.put("icon", icon.getText().toString().trim());
                        o.put("type", t);
                        o.put("value", v);
                        if (t.equals("app") && existing != null && existing.optString("value").equals(v)) o.put("img", existing.optString("img"));
                        JSONArray a = Prefs.customButtons(this);
                        if (index >= 0) a.put(index, o); else a.put(o);
                        Prefs.setCustomButtons(this, a);
                        RemoteService.refresh(this);
                        render();
                        if (t.equals("app") && o.optString("img").isEmpty() && conn.isConnected()) AppIcons.request(o);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null);
        if (existing != null) bld.setNeutralButton("Delete", (d, x) -> {
            JSONArray a = Prefs.customButtons(this);
            a.remove(index);
            Prefs.setCustomButtons(this, a);
            java.util.List<String> bar = Prefs.notifBar(this);
            bar.remove("custom:" + existing.optString("uid"));
            Prefs.setNotifBar(this, bar);
            RemoteService.refresh(this);
            render();
        });
        bld.show();
    }
}
