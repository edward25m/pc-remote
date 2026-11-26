package com.edward.pcremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** The in-app Update button: checks GitHub for a newer version, shows its size, downloads and installs it. */
public final class Updater {
    public static final String API = "https://api.github.com/repos/edward25m/pc-remote/releases/latest";

    public static final class Release { public int build; public String version, url; public long size; }

    public interface Result { void done(Release r, String error); }

    private Updater() {}

    public static int currentBuild(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) { return 0; }
    }

    public static String currentVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    public static void latest(Result cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
                h.setRequestProperty("Accept", "application/vnd.github+json");
                h.setRequestProperty("User-Agent", "PCRemote-Android");
                h.setConnectTimeout(10000);
                h.setReadTimeout(15000);
                InputStream in = h.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                Release r = new Release();
                r.version = j.optString("tag_name").replaceFirst("^v", "");
                String[] parts = r.version.split("\\.");
                r.build = Integer.parseInt(parts[parts.length - 1]);
                JSONArray assets = j.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").equalsIgnoreCase("PCRemote.apk")) {
                        r.url = a.optString("browser_download_url");
                        r.size = a.optLong("size");
                    }
                }
                main.post(() -> cb.done(r, null));
            } catch (Exception e) {
                main.post(() -> cb.done(null, "Couldn't check for updates. Are you online?"));
            }
        }, "update-check").start();
    }

    /** Checks and, if there's a newer version, offers it (showing the download size). */
    public static void check(Activity a, boolean quietIfNone) {
        latest((r, err) -> {
            if (a.isFinishing()) return;
            if (err != null) { if (!quietIfNone) Ui.toast(a, err); return; }
            if (r.url == null || r.build <= currentBuild(a)) {
                if (!quietIfNone) Ui.toast(a, "You have the latest version (" + currentVersion(a) + ").");
                return;
            }
            offer(a, r);
        });
    }

    public static void offer(Activity a, Release r) {
        new AlertDialog.Builder(a)
                .setTitle("Update available")
                .setMessage("Version " + r.version + " is ready.\nYou have " + currentVersion(a) + ".\n\nDownload size: " + Ui.size(r.size)
                        + "\n(This uses your Wi-Fi or mobile data.)")
                .setPositiveButton("Download & install", (d, w) -> download(a, r))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void download(Activity a, Release r) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("One-time permission")
                    .setMessage("Android needs you to allow PC Remote to install updates. On the next screen turn on \"Allow from this source\", then come back and tap Update again.")
                    .setPositiveButton("Open settings", (d, w) -> a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.getPackageName()))))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        LinearLayout box = Ui.vbox(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, p, p, p);
        TextView label = Ui.text(a, "Downloading… 0 of " + Ui.size(r.size), 15, Ui.TEXT);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(label);
        box.addView(bar, Ui.lp(-1, -2));
        AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Updating PC Remote").setView(box).setCancelable(false).show();
        Handler main = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(r.url).openConnection();
                h.setInstanceFollowRedirects(true);
                h.setRequestProperty("User-Agent", "PCRemote-Android");
                int code = h.getResponseCode();
                if (code >= 300 && code < 400) { // follow GitHub's redirect to its download server
                    h = (HttpURLConnection) new URL(h.getHeaderField("Location")).openConnection();
                }
                long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : r.size;

                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(a.getPackageName());
                if (total > 0) sp.setSize(total);
                int sid = pi.createSession(sp);
                try (PackageInstaller.Session s = pi.openSession(sid);
                     InputStream in = h.getInputStream();
                     OutputStream out = s.openWrite("PCRemote.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0, last = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 200) {
                            last = now;
                            final long g = got;
                            main.post(() -> {
                                label.setText("Downloading… " + Ui.size(g) + " of " + Ui.size(total));
                                if (total > 0) bar.setProgress((int) (g * 100 / total));
                            });
                        }
                    }
                    s.fsync(out);
                    out.close();
                    Intent done = new Intent(a, InstallReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    s.commit(PendingIntent.getBroadcast(a, 0, done, flags).getIntentSender());
                }
                main.post(() -> { dlg.dismiss(); Ui.toast(a, "Tap Update on the next screen to finish."); });
            } catch (Exception e) {
                main.post(() -> { dlg.dismiss(); Ui.toast(a, "Update failed: " + e.getMessage()); });
            }
        }, "update-download").start();
    }
}
