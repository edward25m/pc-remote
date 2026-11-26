package com.edward.pcremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Sends files phone -> PC and receives files PC -> phone (only over the local link, never mobile data). */
public final class Transfers {
    public static final String FOLDER = "PC Remote";
    private static final String CHANNEL = "transfers";
    private static final AtomicInteger NEXT_ID = new AtomicInteger((int) (System.currentTimeMillis() % 100000) * 10);
    private static final Map<Integer, Download> downloads = new ConcurrentHashMap<>();
    private static final Map<Integer, CountDownLatch> waits = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> cancelledUploads = new ConcurrentHashMap<>();
    private static final ExecutorService uploader = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;

    private Transfers() {}

    public static void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW);
            app.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private static final class Download {
        String name, label; long size, got; OutputStream os; long lastNote; Uri uri; File file;
    }

    // ------------------------------------------------------------ where received files go

    public static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : true;
    }

    public static File rootFolder() {
        return new File(Environment.getExternalStorageDirectory(), FOLDER);
    }

    private static void openOutput(Download d) throws Exception {
        if (Build.VERSION.SDK_INT < 29 || hasAllFilesAccess()) {
            File dir = rootFolder();
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Can't create the PC Remote folder");
            File f = unique(dir, d.name);
            d.file = f;
            d.os = new FileOutputStream(f);
            d.label = FOLDER + "/" + f.getName();
        } else {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, d.name);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            ContentResolver cr = app.getContentResolver();
            d.uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (d.uri == null) throw new Exception("Can't save the file");
            d.os = cr.openOutputStream(d.uri);
            d.label = "Download/" + FOLDER + "/" + d.name;
        }
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    // ------------------------------------------------------------ PC -> phone

    public static void download(String pcPath, String name) {
        int id = NEXT_ID.incrementAndGet();
        Download d = new Download();
        d.name = name;
        downloads.put(id, d);
        Conn.get().send(Conn.msg("get", "path", pcPath, "id", id));
        toast("Downloading " + name + "…");
    }

    /** Called on the connection's reader thread. */
    static void onJson(String t, JSONObject m) {
        int id = m.optInt("id");
        switch (t) {
            case "file_start": {
                Download d = downloads.get(id);
                if (d == null) return;
                d.size = m.optLong("size");
                try { openOutput(d); }
                catch (Exception e) {
                    downloads.remove(id);
                    Conn.get().send(Conn.msg("cancel", "id", id));
                    toast("Couldn't save " + d.name + ": " + e.getMessage());
                }
                break;
            }
            case "file_end": {
                Download d = downloads.remove(id);
                if (d == null || d.os == null) return;
                try { d.os.close(); } catch (Exception ignored) {}
                if (d.uri != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    app.getContentResolver().update(d.uri, v, null, null);
                }
                note(id, "Saved " + d.name, "In " + d.label, -1);
                toast("Saved to " + d.label);
                break;
            }
            case "file_error":
            case "file_cancelled": {
                Download d = downloads.remove(id);
                if (d != null) {
                    cleanup(d);
                    cancelNote(id);
                    if (t.equals("file_error")) toast("Download failed: " + m.optString("err"));
                }
                break;
            }
            case "put_ready":
            case "put_done": {
                CountDownLatch l = waits.get(id * 2 + (t.equals("put_done") ? 1 : 0));
                if (l != null) l.countDown();
                break;
            }
        }
    }

    /** Called on the reader thread with [4-byte id][bytes]. */
    static void onData(byte[] p) {
        if (p.length < 4) return;
        int id = ((p[0] & 0xFF) << 24) | ((p[1] & 0xFF) << 16) | ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        Download d = downloads.get(id);
        if (d == null || d.os == null) return;
        try {
            d.os.write(p, 4, p.length - 4);
            d.got += p.length - 4;
            long now = System.currentTimeMillis();
            if (now - d.lastNote > 700) {
                d.lastNote = now;
                note(id, "Receiving " + d.name, Ui.size(d.got) + " of " + Ui.size(d.size), d.size > 0 ? (int) (d.got * 100 / d.size) : 0);
            }
        } catch (Exception e) {
            downloads.remove(id);
            cleanup(d);
            Conn.get().send(Conn.msg("cancel", "id", id));
            toast("Couldn't save " + d.name);
        }
    }

    private static void cleanup(Download d) {
        try { if (d.os != null) d.os.close(); } catch (Exception ignored) {}
        try { if (d.file != null) d.file.delete(); } catch (Exception ignored) {}
        try { if (d.uri != null) app.getContentResolver().delete(d.uri, null, null); } catch (Exception ignored) {}
    }

    static void onDisconnected() {
        for (Map.Entry<Integer, Download> e : downloads.entrySet()) {
            cleanup(e.getValue());
            cancelNote(e.getKey());
        }
        downloads.clear();
        for (CountDownLatch l : waits.values()) l.countDown();
    }

    // ------------------------------------------------------------ phone -> PC

    /** Sends a file to the PC. dir = folder on the PC, or "" for the Desktop. */
    public static void upload(Uri uri, String dir) {
        // Open the file right away: access granted by other apps (share menu) can end once our screen closes.
        String name = "file";
        long size = -1;
        try (Cursor c = app.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {}
        InputStream in;
        try { in = app.getContentResolver().openInputStream(uri); }
        catch (Exception e) { toast("Can't read " + name); return; }
        final String fname = name;
        final long fsize = size;
        uploader.execute(() -> doUpload(in, fname, fsize, dir));
    }

    private static void doUpload(InputStream source, String name, long size, String dir) {
        Conn conn = Conn.get();
        if (!conn.isConnected()) { toast("Not connected to a PC."); try { source.close(); } catch (Exception ignored) {} return; }

        int id = NEXT_ID.incrementAndGet();
        CountDownLatch ready = new CountDownLatch(1), done = new CountDownLatch(1);
        waits.put(id * 2, ready);
        waits.put(id * 2 + 1, done);
        try (InputStream in = source) {
            if (in == null) throw new Exception("can't read the file");
            conn.send(Conn.msg("put_start", "id", id, "name", name, "size", size, "dir", dir == null ? "" : dir));
            if (!ready.await(15, TimeUnit.SECONDS) || !conn.isConnected()) throw new Exception("the PC didn't answer");

            byte[] buf = new byte[conn.isWifi() ? 128 * 1024 : 16 * 1024];
            byte[] frame = new byte[buf.length + 4];
            frame[0] = (byte) (id >> 24); frame[1] = (byte) (id >> 16); frame[2] = (byte) (id >> 8); frame[3] = (byte) id;
            long sent = 0, lastNote = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelledUploads.remove(id) != null) { conn.send(Conn.msg("cancel", "id", id)); cancelNote(id); return; }
                byte[] f = n == buf.length ? frame : new byte[n + 4];
                System.arraycopy(frame, 0, f, 0, 4);
                System.arraycopy(buf, 0, f, 4, n);
                if (!conn.writeFrame(Conn.DATA, f)) throw new Exception("connection lost");
                sent += n;
                long now = System.currentTimeMillis();
                if (now - lastNote > 700) {
                    lastNote = now;
                    note(id, "Sending " + name, Ui.size(sent) + (size > 0 ? " of " + Ui.size(size) : ""), size > 0 ? (int) (sent * 100 / size) : 0);
                }
            }
            conn.send(Conn.msg("put_end", "id", id));
            done.await(15, TimeUnit.SECONDS);
            note(id, "Sent " + name, "Saved on " + conn.pcName(), -1);
            toast("Sent " + name + " to " + conn.pcName());
        } catch (Exception e) {
            cancelNote(id);
            toast("Couldn't send " + name + ": " + e.getMessage());
        } finally {
            waits.remove(id * 2);
            waits.remove(id * 2 + 1);
        }
    }

    // ------------------------------------------------------------ notifications

    private static void note(int id, String title, String text, int progress) {
        if (app == null) return;
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
            b.setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text).setOnlyAlertOnce(true);
            if (progress >= 0) b.setProgress(100, progress, false).setOngoing(true);
            else {
                b.setAutoCancel(true);
                Intent open = new Intent(app, FilesActivity.class);
                b.setContentIntent(PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_IMMUTABLE));
            }
            app.getSystemService(NotificationManager.class).notify(1000 + (id % 100000), b.build());
        } catch (Exception ignored) {}
    }

    private static void cancelNote(int id) {
        try { app.getSystemService(NotificationManager.class).cancel(1000 + (id % 100000)); } catch (Exception ignored) {}
    }

    private static void toast(String s) {
        main.post(() -> { if (app != null) Ui.toast(app, s); });
    }
}
