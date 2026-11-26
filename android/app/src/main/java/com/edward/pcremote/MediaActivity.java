package com.edward.pcremote;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Play/pause, tracks, volume and a few handy player keys. */
public class MediaActivity extends BaseActivity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout c = Ui.screen(this, "Media", true);

        c.addView(row(big("⏮", Keys.PREV), big("⏯", Keys.PLAY), big("⏭", Keys.NEXT)), rowLp(110));
        c.addView(row(big("🔉", Keys.VOLDOWN), big("🔇", Keys.MUTE), big("🔊", Keys.VOLUP)), rowLp(96));
        c.addView(row(big("⏹", Keys.STOP)), rowLp(80));

        c.addView(Ui.section(this, "Player keys (YouTube, VLC, Netflix…)"));
        c.addView(row(small("⏪ 5s", Keys.LEFT), small("Space", Keys.SPACE), small("5s ⏩", Keys.RIGHT)), rowLp(64));
        c.addView(row(small("Fullscreen (F)", 'F'), small("F11", Keys.F1 + 10), small("Esc", Keys.ESC)), rowLp(64));
        c.addView(row(small("Vol ↑ (↑)", Keys.UP), small("Vol ↓ (↓)", Keys.DOWN), small("Mute (M)", 'M')), rowLp(64));

        TextView tip = Ui.text(this, "Tip: your phone's volume buttons also change the PC volume. You can turn that off in Settings.", 13, Ui.MUTED);
        tip.setPadding(Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4), 0);
        c.addView(tip);
    }

    private TextView big(String label, int vk) {
        TextView t = Ui.button(this, label, Ui.CARD, v -> conn.keys(vk));
        t.setTextSize(34);
        return t;
    }

    private TextView small(String label, int vk) {
        TextView t = Ui.button(this, label, Ui.CARD2, v -> conn.keys(vk));
        t.setTextSize(15);
        return t;
    }

    private LinearLayout row(TextView... views) {
        LinearLayout r = Ui.hbox(this);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i < views.length - 1) lp.setMarginEnd(Ui.dp(this, 10));
            r.addView(views[i], lp);
        }
        return r;
    }

    private LinearLayout.LayoutParams rowLp(int hDp) {
        LinearLayout.LayoutParams lp = Ui.lp(-1, Ui.dp(this, hDp));
        lp.bottomMargin = Ui.dp(this, 10);
        return lp;
    }
}
