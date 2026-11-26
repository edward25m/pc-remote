package com.edward.pcremote;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/** "Send to PC" in Android's share menu. Files land on the PC's Desktop. */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Conn conn = Conn.get();
        conn.init(this);
        Transfers.init(this);

        List<Uri> uris = new ArrayList<>();
        Intent i = getIntent();
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            else {
                String text = i.getStringExtra(Intent.EXTRA_TEXT);
                if (text != null) {
                    if (conn.isConnected()) { conn.sendClipboard(text); Ui.toast(this, "Text copied to the PC's clipboard"); }
                    else Ui.toast(this, "Open PC Remote and connect first");
                    finish();
                    return;
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (uris.isEmpty()) { finish(); return; }

        if (conn.isConnected()) { send(uris); return; }

        // not connected yet: try the usual PC, then send
        Ui.toast(this, "Connecting to your PC…");
        Discovery.autoConnect(this, found -> {});
        Handler h = new Handler(Looper.getMainLooper());
        long until = System.currentTimeMillis() + 15000;
        h.post(new Runnable() {
            @Override public void run() {
                if (conn.isConnected()) { send(uris); return; }
                if (System.currentTimeMillis() > until || (conn.state == Conn.IDLE && System.currentTimeMillis() > until - 12000)) {
                    Ui.toast(ShareActivity.this, "Couldn't reach your PC. Open PC Remote and connect, then share again.");
                    finish();
                    return;
                }
                h.postDelayed(this, 300);
            }
        });
    }

    private void send(List<Uri> uris) {
        for (Uri u : uris) {
            try { grantUriPermission(getPackageName(), u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            Transfers.upload(u, "");
        }
        Ui.toast(this, "Sending " + uris.size() + (uris.size() == 1 ? " file" : " files") + " to " + Conn.get().pcName() + "'s Desktop");
        finish();
    }
}
