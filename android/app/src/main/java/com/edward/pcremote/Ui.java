package com.edward.pcremote;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Small helpers so every screen shares the same dark-blue look. */
public final class Ui {
    public static final int ACCENT = 0xFF2563EB, WHITE = 0xFFFFFFFF;
    public static int BG, BAR, CARD, CARD2, ACCENT_LIGHT, TEXT, MUTED, ON, SELECTED;
    public static boolean night;
    static { load(false); }

    private Ui() {}

    private static void load(boolean dark) {
        night = dark;
        if (dark) { // AMOLED: true black
            BG = 0xFF000000; BAR = 0xFF000000; CARD = 0xFF121212; CARD2 = 0xFF262626;
            ACCENT_LIGHT = 0xFF60A5FA; TEXT = 0xFFF5F5F5; MUTED = 0xFF9CA3AF; ON = 0xFF38BDF8; SELECTED = 0xFF1E3A8A;
        } else {
            BG = 0xFFF3F6FB; BAR = 0xFFFFFFFF; CARD = 0xFFFFFFFF; CARD2 = 0xFFE3E9F2;
            ACCENT_LIGHT = 0xFF1D4ED8; TEXT = 0xFF0F172A; MUTED = 0xFF64748B; ON = 0xFF0369A1; SELECTED = 0xFFDBEAFE;
        }
    }

    public static void loadColors(Context c) { load(Prefs.sp(c).getBoolean("night", false)); }

    /** Call at the start of every screen: picks light or AMOLED night colours and styles the system bars. */
    public static void apply(Activity a) {
        load(Prefs.sp(a).getBoolean("night", false));
        a.setTheme(night ? R.style.AppTheme_Night : R.style.AppTheme);
        android.view.Window w = a.getWindow();
        w.setStatusBarColor(BAR);
        w.setNavigationBarColor(BAR);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility();
        if (night) f &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        else f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        d.setSystemUiVisibility(f);
    }

    private static boolean isAccent(int color) { return color == ACCENT || color == 0xFF1D4ED8; }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static void setBg(View v, int color, float radiusDp) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(night ? 0x33FFFFFF : 0x22000000), round(v.getContext(), color, radiusDp), null));
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView button(Context c, String label, int color, View.OnClickListener l) {
        TextView b = text(c, label, 16, isAccent(color) ? WHITE : TEXT);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        setBg(b, color, 14);
        b.setClickable(true);
        if (l != null) b.setOnClickListener(v -> { haptic(v); l.onClick(v); });
        return b;
    }

    /** A big square tile with an emoji icon and a label. */
    public static LinearLayout tile(Context c, String icon, String label, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        setBg(t, CARD, 20);
        if (!night) t.setElevation(dp(c, 1.5f));
        int p = dp(c, 14);
        t.setPadding(p, p, p, p);
        TextView i = text(c, icon, 34, TEXT);
        i.setGravity(Gravity.CENTER);
        TextView n = text(c, label, 15, TEXT);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(c, 6), 0, 0);
        t.addView(i);
        t.addView(n);
        t.setClickable(true);
        t.setOnClickListener(v -> { haptic(v); l.onClick(v); });
        return t;
    }

    /** Tile showing a picture (e.g. a real app icon) instead of an emoji. */
    public static LinearLayout tile(Context c, android.graphics.Bitmap img, String fallback, String label, View.OnClickListener l) {
        LinearLayout t = tile(c, fallback, label, l);
        if (img != null) {
            android.widget.ImageView iv = new android.widget.ImageView(c);
            iv.setImageBitmap(img);
            t.removeViewAt(0);
            t.addView(iv, 0, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        }
        return t;
    }

    public static void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
    }

    public static LinearLayout.LayoutParams margins(Context c, int w, int h, int m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        int d = dp(c, m);
        p.setMargins(d, d, d, d);
        return p;
    }

    /** Standard screen: a top bar with back arrow + title, and a content area. Returns the content area. */
    public static LinearLayout screen(Activity a, String title, boolean scroll) {
        LinearLayout root = vbox(a);
        root.setBackgroundColor(BG);
        LinearLayout bar = hbox(a);
        bar.setBackgroundColor(BAR);
        int p = dp(a, 8);
        bar.setPadding(p, p, p, p);
        TextView back = text(a, "←", 24, TEXT);
        back.setPadding(dp(a, 12), dp(a, 4), dp(a, 16), dp(a, 4));
        back.setOnClickListener(v -> a.finish());
        bar.addView(back);
        TextView t = text(a, title, 20, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.setTag("bar");
        root.addView(bar, lp(-1, -2));

        LinearLayout content = vbox(a);
        int cp = dp(a, 14);
        content.setPadding(cp, cp, cp, cp);
        if (scroll) {
            ScrollView sv = new ScrollView(a);
            sv.setFillViewport(true);
            sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
            root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        a.setContentView(root);
        return content;
    }

    /** The title bar created by {@link #screen}, for adding buttons on the right. */
    public static LinearLayout bar(Activity a) {
        View content = a.findViewById(android.R.id.content);
        return (LinearLayout) ((ViewGroup) content).getChildAt(0).findViewWithTag("bar");
    }

    public static TextView barButton(Activity a, String label, View.OnClickListener l) {
        TextView b = text(a, label, 15, ACCENT_LIGHT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
        b.setOnClickListener(l);
        bar(a).addView(b);
        return b;
    }

    public static TextView section(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 13, ACCENT_LIGHT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 4), dp(c, 18), 0, dp(c, 8));
        return t;
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    public static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
    }
}
