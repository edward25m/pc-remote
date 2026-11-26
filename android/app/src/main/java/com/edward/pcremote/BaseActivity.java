package com.edward.pcremote;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;

import org.json.JSONObject;

/** Shared behaviour: volume keys, clipboard sync, and leaving the screen if the PC disconnects. */
public abstract class BaseActivity extends Activity implements Conn.Listener {
    protected Conn conn;
    /** Screens that need a connection close themselves when it drops. */
    protected boolean needsConnection = true;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        conn = Conn.get();
        conn.init(this);
        Transfers.init(this);
    }

    @Override protected void onResume() {
        super.onResume();
        conn.addListener(this);
        if (needsConnection && conn.state == Conn.IDLE) finish();
    }

    @Override protected void onPause() {
        conn.removeListener(this);
        super.onPause();
    }

    @Override public void onState(int s) {
        if (needsConnection && s == Conn.IDLE) {
            Ui.toast(this, conn.error.isEmpty() ? "Disconnected from the PC" : conn.error);
            finish();
        }
    }

    @Override public void onMessage(String t, JSONObject m) {
        if (t.equals("error")) Ui.toast(this, m.optString("msg"));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN)
                && !"off".equals(Prefs.volMode(this)) && conn.isConnected()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN)
                conn.keys(k == KeyEvent.KEYCODE_VOLUME_UP ? Keys.VOLUP : Keys.VOLDOWN);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Android only lets apps read the clipboard while they're on screen, so sync it then.
        if (hasFocus && conn.isConnected() && Prefs.clipSync(this)) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0) {
                    CharSequence s = cd.getItemAt(0).coerceToText(this);
                    if (s != null && s.length() > 0 && s.length() < 200000) conn.sendClipboard(s.toString());
                }
            } catch (Exception ignored) {}
        }
    }
}
