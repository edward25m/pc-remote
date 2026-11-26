package com.edward.pcremote;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Browse every folder on the PC; download, send, open, play, rename, move, copy and delete. */
public class FilesActivity extends BaseActivity {
    private static final int PICK = 5;
    private static final String[] MEDIA = {".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".3gp", ".wmv", ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".wma"};

    private String path = "", parent = "";
    private final List<JSONObject> items = new ArrayList<>();
    private TextView pathView, pasteBar, empty;
    private Adapter adapter;
    private List<String> clipPaths = null;
    private boolean clipMove;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout c = Ui.screen(this, "PC files", false);
        c.setPadding(0, 0, 0, 0);
        Ui.barButton(this, "⬆ Send here", v -> pick());
        Ui.barButton(this, "⋮", v -> more());

        pathView = Ui.text(this, "", 13, Ui.MUTED);
        pathView.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        pathView.setSingleLine(true);
        pathView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        c.addView(pathView, Ui.lp(-1, -2));

        empty = Ui.text(this, "Loading…", 15, Ui.MUTED);
        empty.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), 0);
        c.addView(empty, Ui.lp(-1, -2));

        ListView lv = new ListView(this);
        lv.setDivider(null);
        adapter = new Adapter();
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> tap(items.get(pos)));
        lv.setOnItemLongClickListener((p, v, pos, id) -> { menu(items.get(pos)); return true; });
        c.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));

        pasteBar = Ui.button(this, "", Ui.ACCENT, v -> paste());
        pasteBar.setVisibility(View.GONE);
        c.addView(pasteBar, Ui.margins(this, -1, -2, 10));

        list("");
    }

    @Override protected void onReconnected() { list(path); }

    private void list(String p) {
        empty.setText("Loading…");
        empty.setVisibility(View.VISIBLE);
        conn.send(Conn.msg("ls", "path", p));
    }

    @Override public void onMessage(String t, JSONObject m) {
        super.onMessage(t, m);
        switch (t) {
            case "ls_res": {
                path = m.optString("path");
                parent = m.optString("parent");
                items.clear();
                JSONArray a = m.optJSONArray("items");
                for (int i = 0; a != null && i < a.length(); i++) items.add(a.optJSONObject(i));
                if (!path.isEmpty()) Collections.sort(items, (x, y) -> {
                    if (x.optBoolean("d") != y.optBoolean("d")) return x.optBoolean("d") ? -1 : 1;
                    return x.optString("n").compareToIgnoreCase(y.optString("n"));
                });
                pathView.setText(path.isEmpty() ? "This PC" : path);
                empty.setText("This folder is empty.");
                empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
                break;
            }
            case "op_done":
                if (!m.optBoolean("ok")) Ui.toast(this, "Couldn't do that: " + m.optString("err"));
                list(path);
                break;
            case "put_done":
                if (!path.isEmpty()) list(path);
                break;
            case "stream_url":
                startActivity(new Intent(this, PlayerActivity.class)
                        .putExtra("url", m.optString("url")).putExtra("name", m.optString("name")));
                break;
        }
    }

    @Override public void onBackPressed() {
        if (!path.isEmpty()) list(parent); else super.onBackPressed();
    }

    // ------------------------------------------------------------ actions

    private void tap(JSONObject it) {
        if (it.optBoolean("d")) { list(it.optString("p")); return; }
        String name = it.optString("n");
        if (isMedia(name)) {
            String def = Prefs.mediaDefault(this);
            if (def.equals("pc")) { open(it); return; }
            if (def.equals("phone")) { playOnPhone(it); return; }
            CheckBox remember = new CheckBox(this);
            remember.setText("Always do this (you can change it in Settings)");
            remember.setTextColor(Ui.MUTED);
            LinearLayout w = Ui.vbox(this);
            w.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
            w.addView(remember);
            new AlertDialog.Builder(this).setTitle(name).setView(w)
                    .setPositiveButton("Play on laptop", (d, x) -> { if (remember.isChecked()) Prefs.put(this, "media_default", "pc"); open(it); })
                    .setNegativeButton("Play on phone", (d, x) -> { if (remember.isChecked()) Prefs.put(this, "media_default", "phone"); playOnPhone(it); })
                    .show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(name).setMessage(Ui.size(it.optLong("s")))
                .setPositiveButton("Open on PC", (d, x) -> open(it))
                .setNegativeButton("Download to phone", (d, x) -> download(it))
                .show();
    }

    private void menu(JSONObject it) {
        if (path.isEmpty()) return; // drives & shortcuts
        boolean dir = it.optBoolean("d");
        List<String> opts = new ArrayList<>();
        if (!dir) opts.add("Download to phone");
        if (!dir && isMedia(it.optString("n"))) opts.add("Play on phone");
        opts.add("Open on PC");
        opts.add("Rename");
        opts.add("Move…");
        opts.add("Copy…");
        opts.add("Delete");
        new AlertDialog.Builder(this).setTitle(it.optString("n"))
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    switch (opts.get(w)) {
                        case "Download to phone": download(it); break;
                        case "Play on phone": playOnPhone(it); break;
                        case "Open on PC": open(it); break;
                        case "Rename": rename(it); break;
                        case "Move…": setClip(it, true); break;
                        case "Copy…": setClip(it, false); break;
                        case "Delete": delete(it); break;
                    }
                }).show();
    }

    private void open(JSONObject it) {
        conn.send(Conn.msg("open", "path", it.optString("p")));
        Ui.toast(this, "Opening on the PC…");
    }

    private void playOnPhone(JSONObject it) {
        if (!conn.isWifi()) { Ui.toast(this, "Playing on the phone needs Wi-Fi or hotspot (not Bluetooth)."); return; }
        conn.send(Conn.msg("stream", "path", it.optString("p")));
    }

    private void download(JSONObject it) {
        if (!Transfers.hasAllFilesAccess() && !Prefs.sp(this).getBoolean("asked_all_files", false)) {
            Prefs.put(this, "asked_all_files", true);
            new AlertDialog.Builder(this).setTitle("Save to a \"PC Remote\" folder?")
                    .setMessage("To save files in a PC Remote folder you can see in My Files, allow \"All files access\" on the next screen.\n\nIf you skip this, files go to Download/PC Remote instead.")
                    .setPositiveButton("Allow", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton("Skip", (d, w) -> Transfers.download(it.optString("p"), it.optString("n")))
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != 0) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 3);
            return;
        }
        Transfers.download(it.optString("p"), it.optString("n"));
    }

    private void rename(JSONObject it) {
        EditText e = new EditText(this);
        e.setText(it.optString("n"));
        e.setTextColor(Ui.TEXT);
        e.setSelectAllOnFocus(true);
        LinearLayout w = Ui.vbox(this);
        w.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        w.addView(e);
        new AlertDialog.Builder(this).setTitle("Rename").setView(w)
                .setPositiveButton("Rename", (d, x) -> conn.send(Conn.msg("ren", "path", it.optString("p"), "name", e.getText().toString().trim())))
                .setNegativeButton("Cancel", null).show();
    }

    private void delete(JSONObject it) {
        new AlertDialog.Builder(this).setTitle("Delete \"" + it.optString("n") + "\"?")
                .setMessage("It goes to the PC's Recycle Bin.")
                .setPositiveButton("Delete", (d, x) -> conn.send(Conn.msg("del", "paths", new JSONArray().put(it.optString("p")))))
                .setNegativeButton("Cancel", null).show();
    }

    private void setClip(JSONObject it, boolean move) {
        clipPaths = new ArrayList<>();
        clipPaths.add(it.optString("p"));
        clipMove = move;
        pasteBar.setText((move ? "Move" : "Copy") + " \"" + it.optString("n") + "\" here  ·  tap  (long-press to cancel)");
        pasteBar.setVisibility(View.VISIBLE);
        pasteBar.setOnLongClickListener(v -> { clipPaths = null; pasteBar.setVisibility(View.GONE); return true; });
        Ui.toast(this, "Open the folder you want, then tap the blue bar.");
    }

    private void paste() {
        if (clipPaths == null) return;
        if (path.isEmpty()) { Ui.toast(this, "Open a folder first"); return; }
        JSONArray a = new JSONArray();
        for (String p : clipPaths) a.put(p);
        conn.send(Conn.msg(clipMove ? "mv" : "cp", "paths", a, "dest", path));
        clipPaths = null;
        pasteBar.setVisibility(View.GONE);
    }

    private void more() {
        String[] opts = {"New folder", "Refresh", "Go to This PC"};
        new AlertDialog.Builder(this).setItems(opts, (d, w) -> {
            if (w == 0) newFolder();
            else if (w == 1) list(path);
            else list("");
        }).show();
    }

    private void newFolder() {
        if (path.isEmpty()) { Ui.toast(this, "Open a folder first"); return; }
        EditText e = new EditText(this);
        e.setHint("Folder name");
        e.setTextColor(Ui.TEXT);
        LinearLayout w = Ui.vbox(this);
        w.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        w.addView(e);
        new AlertDialog.Builder(this).setTitle("New folder").setView(w)
                .setPositiveButton("Create", (d, x) -> conn.send(Conn.msg("mkdir", "path", path + (path.endsWith("\\") ? "" : "\\") + e.getText().toString().trim())))
                .setNegativeButton("Cancel", null).show();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK);
        Ui.toast(this, path.isEmpty() ? "Files will go to the PC's Desktop" : "Files will go to this folder");
    }

    @Override protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc != PICK || res != RESULT_OK || data == null) return;
        String dir = path;
        if (data.getClipData() != null)
            for (int i = 0; i < data.getClipData().getItemCount(); i++) Transfers.upload(data.getClipData().getItemAt(i).getUri(), dir);
        else if (data.getData() != null) Transfers.upload(data.getData(), dir);
    }

    private static boolean isMedia(String name) {
        String n = name.toLowerCase(Locale.US);
        for (String e : MEDIA) if (n.endsWith(e)) return true;
        return false;
    }

    private static String icon(JSONObject it) {
        if ("drive".equals(it.optString("k"))) return "💽";
        if ("fav".equals(it.optString("k"))) return "⭐";
        if (it.optBoolean("d")) return "📁";
        String n = it.optString("n").toLowerCase(Locale.US);
        if (isMedia(n)) return n.matches(".*\\.(mp3|m4a|aac|wav|ogg|flac|wma)$") ? "🎵" : "🎬";
        if (n.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp|heic)$")) return "🖼";
        if (n.matches(".*\\.(pdf)$")) return "📕";
        if (n.matches(".*\\.(docx?|txt|rtf|odt)$")) return "📝";
        if (n.matches(".*\\.(xlsx?|csv)$")) return "📊";
        if (n.matches(".*\\.(pptx?)$")) return "📽";
        if (n.matches(".*\\.(zip|rar|7z)$")) return "🗜";
        if (n.matches(".*\\.(exe|msi|apk)$")) return "⚙";
        return "📄";
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            LinearLayout row;
            TextView ic, name, sub;
            if (v == null) {
                row = Ui.hbox(FilesActivity.this);
                int p = Ui.dp(FilesActivity.this, 12);
                row.setPadding(Ui.dp(FilesActivity.this, 16), p, Ui.dp(FilesActivity.this, 16), p);
                ic = Ui.text(FilesActivity.this, "", 24, Ui.TEXT);
                ic.setPadding(0, 0, Ui.dp(FilesActivity.this, 14), 0);
                LinearLayout col = Ui.vbox(FilesActivity.this);
                name = Ui.text(FilesActivity.this, "", 16, Ui.TEXT);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                sub = Ui.text(FilesActivity.this, "", 12, Ui.MUTED);
                col.addView(name);
                col.addView(sub);
                row.addView(ic);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                row.setTag(new TextView[]{ic, name, sub});
            } else row = (LinearLayout) v;
            TextView[] t = (TextView[]) row.getTag();
            JSONObject it = items.get(i);
            t[0].setText(icon(it));
            t[1].setText(it.optString("n"));
            t[1].setTypeface(it.optBoolean("d") ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            String s;
            if ("drive".equals(it.optString("k"))) s = Ui.size(it.optLong("free")) + " free of " + Ui.size(it.optLong("s"));
            else if (it.optBoolean("d")) s = it.has("m") ? DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(it.optLong("m"))) : "";
            else s = Ui.size(it.optLong("s")) + "  ·  " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(it.optLong("m")));
            t[2].setText(s);
            t[2].setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
