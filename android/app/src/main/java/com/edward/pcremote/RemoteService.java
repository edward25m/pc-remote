package com.edward.pcremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

import java.util.List;

/**
 * Runs only while connected. Keeps the connection alive in the background and shows the
 * notification bar with your quick buttons. "Clear all" doesn't remove it.
 */
public class RemoteService extends Service {
    private static final String CHANNEL = "remote";
    private static final int NOTE_ID = 7;
    private static RemoteService running;
    private MediaSession session;
    private final Conn.Listener listener = new Conn.Listener() {
        @Override public void onState(int s) { if (s == Conn.IDLE && !Watcher.reconnecting()) stopSelf(); else refresh(); }
    };

    public static void refresh(Context c) {
        RemoteService s = running;
        if (s != null) s.refresh();
    }

    @Override public void onCreate() {
        super.onCreate();
        running = this;
        Conn.get().init(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Remote control bar", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Conn.get().addListener(listener);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTE_ID, n);
        updateVolumeSession();
        if (Conn.get().state == Conn.IDLE && !Watcher.reconnecting()) stopSelf();
        return START_NOT_STICKY;
    }

    private void refresh() {
        try {
            getSystemService(NotificationManager.class).notify(NOTE_ID, build());
            updateVolumeSession();
        } catch (Exception ignored) {}
    }

    private Notification build() {
        Conn conn = Conn.get();
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notif_bar);
        int[] ids = {R.id.b0, R.id.b1, R.id.b2, R.id.b3, R.id.b4, R.id.b5};
        List<String> bar = Prefs.notifBar(this);
        for (int i = 0; i < ids.length; i++) {
            if (i < bar.size()) {
                Keys.Action a = Keys.find(this, bar.get(i));
                if (a == null) { rv.setViewVisibility(ids[i], android.view.View.GONE); continue; }
                rv.setViewVisibility(ids[i], android.view.View.VISIBLE);
                if (a.image != null) {
                    rv.setImageViewBitmap(ids[i], a.image);
                    rv.setInt(ids[i], "setColorFilter", 0);
                } else {
                    rv.setImageViewResource(ids[i], a.icon);
                    rv.setInt(ids[i], "setColorFilter", iconColor()); // plain icons that match the notification text
                }
                Intent it = new Intent(this, ActionReceiver.class).setAction("act:" + a.id);
                rv.setOnClickPendingIntent(ids[i], PendingIntent.getBroadcast(this, 100 + i, it,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else rv.setViewVisibility(ids[i], android.view.View.GONE);
        }

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String how = conn.isWifi() ? "Wi-Fi" : "Bluetooth";
        String title = conn.state == Conn.CONNECTED ? conn.pcName() + " · " + how
                : Watcher.reconnecting() ? "Reconnecting to " + conn.pcName() + "…" : "Connecting…";

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (!bar.isEmpty()) {
            b.setCustomContentView(rv);
            b.setCustomBigContentView(rv);
            b.setStyle(new Notification.DecoratedCustomViewStyle());
        } else {
            b.setContentText("Tap to open PC Remote");
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        Notification n = b.build();
        n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        return n;
    }

    private int iconColor() {
        int night = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES ? 0xFFFFFFFF : 0xFF1F2937;
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        refresh();
    }

    /** When "volume buttons always control the PC" is on, a remote-volume media session takes the volume keys. */
    private void updateVolumeSession() {
        boolean want = "always".equals(Prefs.volMode(this)) && Conn.get().isConnected();
        if (want && session == null) {
            session = new MediaSession(this, "PCRemote");
            session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                @Override public void onAdjustVolume(int direction) {
                    if (direction > 0) Conn.get().keys(Keys.VOLUP);
                    else if (direction < 0) Conn.get().keys(Keys.VOLDOWN);
                }
            });
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build());
            session.setActive(true);
        } else if (!want && session != null) {
            session.release();
            session = null;
        }
    }

    @Override public void onDestroy() {
        running = null;
        Conn.get().removeListener(listener);
        if (session != null) { session.release(); session = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
