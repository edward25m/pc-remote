package com.edward.pcremote;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/** Plays a video or song that is stored on the PC, streamed over Wi-Fi/hotspot (no mobile data). */
public class PlayerActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        VideoView vv = new VideoView(this);
        root.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        TextView title = Ui.text(this, getIntent().getStringExtra("name"), 14, Ui.MUTED);
        title.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0);
        root.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);

        Net.bindProcess(this, true);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);
        vv.setVideoURI(Uri.parse(getIntent().getStringExtra("url")));
        vv.setOnPreparedListener(mp -> { vv.start(); mc.show(3000); });
        vv.setOnErrorListener((mp, what, extra) -> {
            Ui.toast(this, "This phone can't play that file type. Try \"Play on laptop\" instead.");
            finish();
            return true;
        });
        vv.setOnCompletionListener(mp -> mc.show(0));
    }

    @Override protected void onDestroy() {
        Net.bindProcess(this, false);
        super.onDestroy();
    }
}
