package com.edward.pcremote;

import android.annotation.SuppressLint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen touchpad. Keyboard pops up from the ⌨ button.
 * Tap = click · 2-finger tap = right-click · 3-finger tap = middle-click · 2 fingers = scroll
 * Tap and hold = pick up (drag) · tap again = drop.
 */
public class TouchpadActivity extends BaseActivity implements SensorEventListener {
    private static final String HINT = "Tap = click  ·  2-finger tap = right-click\n2 fingers = scroll  ·  Hold = pick up, tap again = drop";

    private KeyboardBar kb;
    private android.widget.HorizontalScrollView quick;
    private TextView airBtn, hint;
    private FrameLayout pad;
    private boolean air;
    private SensorManager sm;
    private Sensor gyro;
    private long lastGyroTs;
    private float airRemX, airRemY;

    private float lastX, lastY, remX, remY, scrollRem, scrollRemX, travel;
    private long downTime;
    private int maxFingers;
    private boolean holding;   // left button held down (picked up), waiting for a tap to drop
    private boolean heldThisTouch;
    private float density;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable longPress = () -> {
        holding = true;
        heldThisTouch = true;
        Ui.haptic(pad);
        conn.click("l", "down");
        showHolding(true);
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        LinearLayout c = Ui.screen(this, "Mouse", false);
        c.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        LinearLayout root = (LinearLayout) c.getParent();
        kb = new KeyboardBar(this);
        root.addView(kb.inputView, 1, Ui.lp(-1, -2)); // what you type shows at the top

        pad = new FrameLayout(this);
        pad.setBackground(Ui.round(this, Ui.CARD, 22));
        if (!Ui.night) pad.setElevation(Ui.dp(this, 1.5f));
        hint = Ui.text(this, HINT, 13, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, Ui.dp(this, 16));
        pad.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        c.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        pad.setOnTouchListener((v, e) -> { onPad(e); return true; });

        // always-visible quick keys (Alt+Tab, Alt+F4, Win…)
        quick = QuickKeys.row(this);
        c.addView(quick, Ui.lp(-1, -2));

        // bottom buttons
        LinearLayout bottom = Ui.hbox(this);
        bottom.setPadding(0, Ui.dp(this, 10), 0, 0);
        airBtn = Ui.button(this, "✈  Air mouse", Ui.CARD2, v -> setAir(!air));
        TextView kbBtn = Ui.button(this, "⌨  Keyboard", Ui.ACCENT, v -> kb.toggle());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1);
        bl.setMarginEnd(Ui.dp(this, 10));
        bottom.addView(airBtn, bl);
        bottom.addView(kbBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1));
        c.addView(bottom, Ui.lp(-1, -2));

        root.addView(kb.keysView, Ui.lp(-1, -2)); // special keys sit just above the phone keyboard

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        gyro = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyro == null) airBtn.setAlpha(0.4f);

        if (getIntent().getBooleanExtra("keyboard", false)) kb.show();
    }

    private void showHolding(boolean on) {
        hint.setText(on ? "Holding · move to drag · tap to drop" : HINT);
        hint.setTextColor(on ? Ui.ACCENT_LIGHT : Ui.MUTED);
    }

    private float speed() { return 0.6f + Prefs.mouseSpeed(this) / 40f; }

    private void onPad(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                QuickKeys.releaseNow(); // touching the pad picks the window when Alt+Tab-ing
                lastX = e.getX(); lastY = e.getY();
                downTime = System.currentTimeMillis();
                maxFingers = 1; travel = 0; heldThisTouch = false;
                remX = remY = scrollRem = scrollRemX = 0;
                if (!holding) h.postDelayed(longPress, 450);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                maxFingers = Math.max(maxFingers, e.getPointerCount());
                h.removeCallbacks(longPress);
                lastX = avgX(e, -1); lastY = avgY(e, -1);
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int gone = e.getActionIndex();
                lastX = avgX(e, gone); lastY = avgY(e, gone);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                float x = avgX(e, -1), y = avgY(e, -1);
                float dx = (x - lastX) / density, dy = (y - lastY) / density;
                lastX = x; lastY = y;
                travel += Math.abs(dx) + Math.abs(dy);
                if (travel > 4f) h.removeCallbacks(longPress);
                if (e.getPointerCount() >= 2) {
                    float s = (0.5f + Prefs.scrollSpeed(this) / 50f) * 6f;
                    if (Prefs.invertScroll(this)) s = -s;
                    scrollRem += dy * s;
                    scrollRemX += -dx * s;
                    int sy = (int) scrollRem, sx = (int) scrollRemX;
                    scrollRem -= sy; scrollRemX -= sx;
                    if (sy != 0 || sx != 0) conn.scroll(sy, Math.abs(sx) > Math.abs(sy) * 2 ? sx : 0);
                } else {
                    float v = (float) Math.hypot(dx, dy);
                    float accel = 1f + Math.min(v, 30f) * 0.09f; // quick flicks travel further
                    remX += dx * speed() * accel * 1.6f;
                    remY += dy * speed() * accel * 1.6f;
                    int mx = (int) remX, my = (int) remY;
                    remX -= mx; remY -= my;
                    conn.move(mx, my);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                h.removeCallbacks(longPress);
                long dt = System.currentTimeMillis() - downTime;
                boolean tap = travel < 4f && dt < 300;
                if (holding && !heldThisTouch) {
                    if (tap) { conn.click("l", "up"); holding = false; showHolding(false); Ui.haptic(pad); }
                } else if (!holding && tap) {
                    conn.click(maxFingers >= 3 ? "m" : maxFingers == 2 ? "r" : "l", "click");
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPress);
                break;
        }
    }

    /** Average finger position, optionally ignoring the finger that is lifting. */
    private static float avgX(MotionEvent e, int skip) {
        float s = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (i != skip) { s += e.getX(i); n++; }
        return n == 0 ? e.getX() : s / n;
    }
    private static float avgY(MotionEvent e, int skip) {
        float s = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (i != skip) { s += e.getY(i); n++; }
        return n == 0 ? e.getY() : s / n;
    }

    // ---------------- air mouse ----------------

    private void setAir(boolean on) {
        if (on && gyro == null) { Ui.toast(this, "This phone has no gyroscope for air mouse"); return; }
        air = on;
        airBtn.setText(on ? "✈  Air mouse ON" : "✈  Air mouse");
        Ui.setBg(airBtn, on ? Ui.ACCENT : Ui.CARD2, 14);
        airBtn.setTextColor(on ? Ui.WHITE : Ui.TEXT);
        if (on) {
            lastGyroTs = 0;
            sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST);
            Ui.toast(this, "Point and move the phone. Tap the pad to click.");
        } else sm.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent ev) {
        if (lastGyroTs == 0) { lastGyroTs = ev.timestamp; return; }
        float dt = (ev.timestamp - lastGyroTs) / 1e9f;
        lastGyroTs = ev.timestamp;
        if (dt <= 0 || dt > 0.1f) return;
        float gx = ev.values[0], gz = ev.values[2];
        if (Math.abs(gx) < 0.02f) gx = 0;
        if (Math.abs(gz) < 0.02f) gz = 0;
        float k = 600f + Prefs.airSpeed(this) * 24f; // pixels per radian
        airRemX += -gz * dt * k;
        airRemY += -gx * dt * k;
        int mx = (int) airRemX, my = (int) airRemY;
        airRemX -= mx; airRemY -= my;
        conn.move(mx, my);
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    @Override protected void onResume() {
        super.onResume();
        QuickKeys.fill(this, (LinearLayout) quick.getChildAt(0)); // pick up changes from the editor
    }

    @Override protected void onPause() {
        QuickKeys.releaseNow();
        if (air) setAir(false);
        if (holding) { conn.click("l", "up"); holding = false; showHolding(false); }
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (kb.isShown()) kb.hide(); else super.onBackPressed();
    }
}
