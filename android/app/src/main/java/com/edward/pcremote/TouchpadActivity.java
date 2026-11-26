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

/** Touchpad, mouse buttons, air mouse and keyboard. */
public class TouchpadActivity extends BaseActivity implements SensorEventListener {
    private KeyboardBar kb;
    private TextView airBtn, hint;
    private boolean air;
    private SensorManager sm;
    private Sensor gyro;
    private long lastGyroTs;
    private float airRemX, airRemY;

    // touch state
    private float lastX, lastY, remX, remY, scrollRem, scrollRemX;
    private long downTime;
    private boolean moved, twoFinger, dragging;
    private float density;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable longPress = () -> {
        dragging = true;
        Ui.haptic(findViewById(android.R.id.content));
        conn.click("l", "down");
        if (hint != null) hint.setText("Dragging… lift your finger to drop");
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        LinearLayout c = Ui.screen(this, "Mouse & keyboard", false);
        c.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), 0);

        // top tools
        LinearLayout tools = Ui.hbox(this);
        TextView kbBtn = Ui.button(this, "⌨  Keyboard", Ui.CARD2, v -> kb.toggle());
        airBtn = Ui.button(this, "✈  Air mouse", Ui.CARD2, v -> setAir(!air));
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, -2, 1);
        t1.setMarginEnd(Ui.dp(this, 8));
        tools.addView(kbBtn, t1);
        tools.addView(airBtn, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(tools, Ui.lp(-1, -2));

        // pad
        FrameLayout pad = new FrameLayout(this);
        pad.setBackground(Ui.round(this, Ui.CARD, 22));
        hint = Ui.text(this, "Move: 1 finger · Click: tap · Right-click: 2-finger tap\nScroll: 2 fingers · Drag: hold, then move", 13, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        pad.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        hint.setPadding(0, 0, 0, Ui.dp(this, 14));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, 0, 1);
        plp.topMargin = Ui.dp(this, 10);
        c.addView(pad, plp);
        pad.setOnTouchListener((v, e) -> { onPad(e); return true; });

        // buttons
        LinearLayout btns = Ui.hbox(this);
        btns.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        btns.addView(mouseButton("Left", "l"), lpw(2, true));
        btns.addView(mouseButton("Middle", "m"), lpw(1, true));
        btns.addView(mouseButton("Right", "r"), lpw(2, false));
        c.addView(btns, Ui.lp(-1, Ui.dp(this, 76)));

        kb = new KeyboardBar(this);
        kb.setVisibility(View.GONE);
        ((LinearLayout) c.getParent()).addView(kb, Ui.lp(-1, -2));

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        gyro = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyro == null) airBtn.setAlpha(0.4f);

        if (getIntent().getBooleanExtra("keyboard", false)) kb.showKeyboard();
    }

    private LinearLayout.LayoutParams lpw(float w, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, w);
        if (gap) lp.setMarginEnd(Ui.dp(this, 8));
        return lp;
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView mouseButton(String label, String which) {
        TextView t = Ui.button(this, label, Ui.CARD2, null);
        t.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { Ui.haptic(v); v.setPressed(true); conn.click(which, "down"); }
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { v.setPressed(false); conn.click(which, "up"); }
            return true;
        });
        return t;
    }

    private float speed() { return 0.6f + Prefs.mouseSpeed(this) / 40f; }

    private void onPad(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY();
                downTime = System.currentTimeMillis();
                moved = false; twoFinger = false; dragging = false;
                remX = remY = scrollRem = scrollRemX = 0;
                h.postDelayed(longPress, 450);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                twoFinger = true;
                h.removeCallbacks(longPress);
                lastX = avgX(e); lastY = avgY(e);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // keep going with the remaining finger without a jump
                lastX = e.getPointerCount() > 2 ? avgX(e) : e.getX(e.getActionIndex() == 0 ? 1 : 0);
                lastY = e.getPointerCount() > 2 ? avgY(e) : e.getY(e.getActionIndex() == 0 ? 1 : 0);
                break;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getPointerCount() > 1 ? avgX(e) : e.getX();
                float y = e.getPointerCount() > 1 ? avgY(e) : e.getY();
                float dx = (x - lastX) / density, dy = (y - lastY) / density;
                lastX = x; lastY = y;
                if (Math.abs(dx) + Math.abs(dy) > 1.2f) { moved = true; if (!dragging) h.removeCallbacks(longPress); }
                if (e.getPointerCount() >= 2) {
                    float s = (0.5f + Prefs.scrollSpeed(this) / 50f) * 6f;
                    if (Prefs.invertScroll(this)) s = -s;
                    scrollRem += dy * s;
                    scrollRemX += -dx * s;
                    int sy = (int) scrollRem, sx = (int) scrollRemX;
                    scrollRem -= sy; scrollRemX -= sx;
                    if (Math.abs(sy) > 0 || Math.abs(sx) > 0) conn.scroll(sy, Math.abs(sx) > Math.abs(sy) * 2 ? sx : 0);
                } else {
                    float v = (float) Math.hypot(dx, dy);
                    float accel = 1f + Math.min(v, 30f) * 0.09f; // faster flicks travel further
                    remX += dx * speed() * accel * 1.6f;
                    remY += dy * speed() * accel * 1.6f;
                    int mx = (int) remX, my = (int) remY;
                    remX -= mx; remY -= my;
                    conn.move(mx, my);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                h.removeCallbacks(longPress);
                long dt = System.currentTimeMillis() - downTime;
                if (dragging) {
                    conn.click("l", "up");
                    hint.setText("Move: 1 finger · Click: tap · Right-click: 2-finger tap\nScroll: 2 fingers · Drag: hold, then move");
                } else if (!moved && e.getActionMasked() == MotionEvent.ACTION_UP && dt < 300) {
                    conn.click(twoFinger ? "r" : "l", "click");
                }
                dragging = false;
                break;
            }
        }
    }

    private static float avgX(MotionEvent e) { float s = 0; for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i); return s / e.getPointerCount(); }
    private static float avgY(MotionEvent e) { float s = 0; for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i); return s / e.getPointerCount(); }

    // ---------------- air mouse ----------------

    private void setAir(boolean on) {
        if (on && gyro == null) { Ui.toast(this, "This phone has no gyroscope for air mouse"); return; }
        air = on;
        Ui.setBg(airBtn, on ? Ui.ACCENT : Ui.CARD2, 14);
        airBtn.setText(on ? "✈  Air mouse ON" : "✈  Air mouse");
        if (on) {
            lastGyroTs = 0;
            sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
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

    @Override protected void onPause() {
        if (air) setAir(false);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (kb.isShown2()) kb.hideKeyboard(); else super.onBackPressed();
    }
}
