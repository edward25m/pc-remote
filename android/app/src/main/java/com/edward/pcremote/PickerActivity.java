package com.edward.pcremote;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Choose which PC to control (Wi-Fi, hotspot or Bluetooth). */
public class PickerActivity extends BaseActivity {
    private LinearLayout box;
    private Map<String, JSONObject> found = new HashMap<>();
    private boolean scanning;

    @Override protected void onCreate(Bundle b) {
        needsConnection = false;
        super.onCreate(b);
        box = Ui.screen(this, "Choose a PC", true);
        Ui.barButton(this, "Refresh", v -> scan());
        render();
        scan();
    }

    private void scan() {
        if (scanning) return;
        scanning = true;
        render();
        Discovery.find(this, 2000, "auto", list -> {
            scanning = false;
            found.clear();
            for (JSONObject o : list) found.put(o.optString("id"), o);
            render();
        });
    }

    @SuppressLint("MissingPermission")
    private void render() {
        box.removeAllViews();
        List<JSONObject> known = Prefs.pcs(this);

        if (!known.isEmpty()) {
            box.addView(Ui.section(this, "Your PCs"));
            for (JSONObject k : known) {
                JSONObject live = found.get(k.optString("id"));
                boolean current = conn.state != Conn.IDLE && conn.pc.optString("id").equals(k.optString("id"));
                String sub = current ? "Connected now" : live != null ? "On this network · tap to connect"
                        : !k.optString("bt").isEmpty() ? "Not seen on Wi-Fi · tap to try Bluetooth" : "Not seen on this network";
                TextView row = row("💻  " + k.optString("name"), sub, current);
                row.setOnClickListener(v -> {
                    if (live != null) go(live, Discovery.how(Prefs.connMode(this), live));
                    else if (!k.optString("bt").isEmpty()) goBt(k);
                    else Ui.toast(this, "Can't see that PC. Make sure it's on and on the same Wi-Fi/hotspot.");
                });
                row.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this).setTitle(k.optString("name"))
                            .setItems(new String[]{"Connect over Bluetooth", "Forget this PC"}, (d, w) -> {
                                if (w == 0) goBt(k);
                                else { Prefs.forgetPc(this, k.optString("id")); render(); }
                            }).show();
                    return true;
                });
                box.addView(row, rowLp());
            }
        }

        box.addView(Ui.section(this, scanning ? "Searching Wi-Fi and hotspot…" : "Found on Wi-Fi / hotspot"));
        int newOnes = 0;
        for (JSONObject f : found.values()) {
            if (Prefs.pc(this, f.optString("id")) != null) continue;
            newOnes++;
            TextView row = row("🆕  " + f.optString("name"), f.optString("ip") + " · tap to connect", false);
            row.setOnClickListener(v -> go(f, Discovery.how(Prefs.connMode(this), f)));
            box.addView(row, rowLp());
        }
        if (newOnes == 0 && !scanning) box.addView(note(known.isEmpty()
                ? "No PC found yet. On the PC, run PCRemote.exe once (it then starts with Windows). Then make sure this phone and the PC are on the same Wi-Fi, or connect the PC to this phone's hotspot."
                : "No new PCs found."));

        box.addView(Ui.section(this, "Bluetooth (paired PCs)"));
        if (!Discovery.hasBtPermission(this)) {
            TextView r = row("Allow Bluetooth", "Needed to find paired PCs", false);
            r.setOnClickListener(v -> { if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 2); });
            box.addView(r, rowLp());
        } else if (!Discovery.btOn(this)) {
            box.addView(note("Bluetooth is off."));
        } else {
            List<BluetoothDevice> paired = Discovery.pairedComputers(this);
            if (paired.isEmpty()) box.addView(note("No paired computers. On the PC open Settings > Bluetooth & devices > Add device, pair this phone, then come back."));
            for (BluetoothDevice d : paired) {
                String name = d.getName() == null ? d.getAddress() : d.getName();
                TextView row = row("ᛒ  " + name, "Tap to connect over Bluetooth (slower; no screen view)", false);
                row.setOnClickListener(v -> {
                    try {
                        JSONObject pc = new JSONObject();
                        JSONObject knownPc = null;
                        for (JSONObject k : known) if (d.getAddress().equalsIgnoreCase(k.optString("bt"))) knownPc = k;
                        if (knownPc != null) pc = new JSONObject(knownPc.toString());
                        else { pc.put("name", name); }
                        pc.put("bt", d.getAddress());
                        go(pc, "bluetooth");
                    } catch (Exception ignored) {}
                });
                box.addView(row, rowLp());
            }
        }

        box.addView(Ui.section(this, "Other"));
        TextView ip = row("Enter the PC's IP address", "If the PC isn't found automatically", false);
        ip.setOnClickListener(v -> askIp());
        box.addView(ip, rowLp());
        TextView dl = row("Get PC Remote for Windows", "Download PCRemote.exe for another PC (open this link on the PC)", false);
        dl.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/edward25m/pc-remote/releases/latest"))));
        box.addView(dl, rowLp());
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        render();
    }

    private void askIp() {
        EditText e = new EditText(this);
        e.setHint("e.g. 192.168.1.20");
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setTextColor(Ui.TEXT);
        LinearLayout wrap = Ui.vbox(this);
        int p = Ui.dp(this, 20);
        wrap.setPadding(p, p / 2, p, 0);
        wrap.addView(e);
        new AlertDialog.Builder(this).setTitle("PC's IP address").setView(wrap)
                .setMessage("On the PC: Settings > Network & internet > Wi-Fi > your network > IPv4 address.")
                .setPositiveButton("Connect", (d, w) -> {
                    try {
                        JSONObject pc = new JSONObject();
                        pc.put("ip", e.getText().toString().trim());
                        pc.put("port", Conn.TCP_PORT);
                        pc.put("name", e.getText().toString().trim());
                        go(pc, Net.wifi(this) != null ? "wifi" : "hotspot");
                    } catch (Exception ignored) {}
                }).setNegativeButton("Cancel", null).show();
    }

    private void goBt(JSONObject pc) {
        if (!Discovery.hasBtPermission(this)) { Ui.toast(this, "Allow Bluetooth first"); return; }
        if (!Discovery.btOn(this)) { Ui.toast(this, "Turn on Bluetooth first"); return; }
        go(pc, "bluetooth");
    }

    private void go(JSONObject pc, String how) {
        conn.connect(pc, how);
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    private TextView row(String title, String sub, boolean highlight) {
        TextView t = Ui.text(this, title + "\n", 17, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title + "\n" + sub);
        sb.setSpan(new android.text.style.ForegroundColorSpan(highlight ? Ui.ON : Ui.MUTED), title.length() + 1, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.8f), title.length() + 1, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(Typeface.NORMAL), title.length() + 1, sb.length(), 0);
        t.setText(sb);
        int p = Ui.dp(this, 14);
        t.setPadding(p, p, p, p);
        Ui.setBg(t, highlight ? Ui.SELECTED : Ui.CARD, 16);
        t.setClickable(true);
        return t;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = Ui.lp(-1, -2);
        lp.bottomMargin = Ui.dp(this, 8);
        return lp;
    }

    private TextView note(String s) {
        TextView t = Ui.text(this, s, 14, Ui.MUTED);
        t.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 8));
        return t;
    }
}
