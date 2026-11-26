package com.edward.pcremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Handles taps on the notification bar buttons. */
public class ActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null || !a.startsWith("act:")) return;
        Conn conn = Conn.get();
        conn.init(c);
        if (!conn.isConnected()) { Ui.toast(c, "Not connected to a PC"); return; }
        Keys.run(c, a.substring(4));
        try {
            Vibrator v = c.getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }
}
