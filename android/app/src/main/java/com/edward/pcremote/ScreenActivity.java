package com.edward.pcremote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Live view of the PC screen that you can also control by touch. */
public class ScreenActivity extends BaseActivity {
    private static final int[][] QUALITY = {{960, 40}, {1280, 55}, {1600, 68}, {0, 80}}; // width (0 = full), jpeg quality
    private static final String[] QNAME = {"Low", "Medium", "High", "Best"};

    private ScreenView view;
    private KeyboardBar kb;
    private android.widget.HorizontalScrollView quick;
    private TextView fps, qBtn;
    private int q;
    private int frames;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        keepTheme = true;
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        q = Prefs.screenQuality(this);
        if (!conn.isWifi()) q = 0;

        LinearLayout root = Ui.vbox(this);
        root.setBackgroundColor(0xFF000000);
        kb = new KeyboardBar(this);
        root.addView(kb.inputView, Ui.lp(-1, -2));
        FrameLayout stage = new FrameLayout(this);
        view = new ScreenView(this);
        stage.addView(view, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = Ui.hbox(this);
        bar.setBackgroundColor(0xAA0B1220);
        int p = Ui.dp(this, 6);
        bar.setPadding(p, p, p, p);
        bar.addView(barBtn("←", v -> finish()));
        bar.addView(barBtn("⌨", v -> kb.toggle()));
        qBtn = barBtn("Quality: " + QNAME[q], v -> cycleQuality());
        bar.addView(qBtn);
        bar.addView(barBtn("Right-click", v -> { view.rightNext = true; Ui.toast(this, "Tap where you want to right-click"); }));
        fps = Ui.text(this, "", 12, Ui.MUTED);
        fps.setGravity(Gravity.END);
        bar.addView(fps, new LinearLayout.LayoutParams(0, -2, 1));
        stage.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        quick = QuickKeys.row(this);
        quick.setBackgroundColor(0xFF0B1220);
        quick.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 6));
        root.addView(quick, Ui.lp(-1, -2));
        root.addView(kb.keysView, Ui.lp(-1, -2));
        setContentView(root);

        if (!conn.isWifi()) Ui.toast(this, "Over Bluetooth the screen view is slow. Use Wi-Fi or hotspot for smooth video.");
        h.postDelayed(fpsTick, 1000);
    }

    private TextView barBtn(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 15, Ui.WHITE);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setOnClickListener(l);
        return t;
    }

    private final Runnable fpsTick = new Runnable() {
        @Override public void run() {
            fps.setText(frames + " fps");
            frames = 0;
            h.postDelayed(this, 1000);
        }
    };

    private void cycleQuality() {
        q = (q + 1) % QUALITY.length;
        Prefs.put(this, "screen_q", q);
        qBtn.setText("Quality: " + QNAME[q]);
        conn.send(Conn.msg("scr_cfg", "w", QUALITY[q][0], "q", QUALITY[q][1]));
    }

    @Override protected void onResume() {
        super.onResume();
        QuickKeys.fill(this, (LinearLayout) quick.getChildAt(0));
        conn.frameSink = jpeg -> {
            Bitmap bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            conn.send(Conn.msg("fr")); // ask for the next picture straight away
            if (bm != null) h.post(() -> { frames++; view.setFrame(bm); });
        };
        conn.send(Conn.msg("scr_start", "w", QUALITY[q][0], "q", QUALITY[q][1]));
        // two requests in flight keeps the stream flowing while one frame travels
        conn.send(Conn.msg("fr"));
        conn.send(Conn.msg("fr"));
    }

    @Override protected void onReconnected() {
        conn.send(Conn.msg("scr_start", "w", QUALITY[q][0], "q", QUALITY[q][1]));
        conn.send(Conn.msg("fr"));
        conn.send(Conn.msg("fr"));
    }

    @Override protected void onPause() {
        QuickKeys.releaseNow();
        conn.frameSink = null;
        conn.send(Conn.msg("scr_stop"));
        super.onPause();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (kb.isShown()) kb.hide(); else super.onBackPressed();
    }

    /** Draws the PC picture; 1 finger = mouse, 2 fingers = zoom/pan (or scroll when not zoomed). */
    final class ScreenView extends View {
        private Bitmap frame;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix m = new Matrix();
        private float zoom = 1f, panX, panY;
        boolean rightNext;

        // gesture state
        private float downX, downY, lastX, lastY, lastSpan, scrollRem;
        private long downT;
        private boolean moved, dragging, multi;
        private boolean holding, heldThisTouch;
        private final Runnable longPress = () -> {
            if (!moved && !multi) {
                sendAt(downX, downY);
                conn.click("l", "down");   // picked up: move your finger, then tap to drop
                holding = true; heldThisTouch = true;
                Ui.haptic(this);
                Ui.toast(getContext(), "Picked up · tap where you want to drop it");
            }
        };

        ScreenView(Context c) { super(c); }

        void setFrame(Bitmap b) {
            Bitmap old = frame;
            frame = b;
            invalidate();
            if (old != null && old != b) old.recycle();
        }

        private RectF imageRect() {
            if (frame == null) return new RectF(0, 0, getWidth(), getHeight());
            float s = Math.min(getWidth() / (float) frame.getWidth(), getHeight() / (float) frame.getHeight()) * zoom;
            float w = frame.getWidth() * s, hh = frame.getHeight() * s;
            float x = (getWidth() - w) / 2f + panX, y = (getHeight() - hh) / 2f + panY;
            return new RectF(x, y, x + w, y + hh);
        }

        @Override protected void onDraw(Canvas c) {
            if (frame == null || frame.isRecycled()) {
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(Ui.MUTED);
                t.setTextSize(Ui.dp(getContext(), 16));
                t.setTextAlign(Paint.Align.CENTER);
                c.drawText("Waiting for the PC screen…", getWidth() / 2f, getHeight() / 2f, t);
                return;
            }
            RectF r = imageRect();
            m.setRectToRect(new RectF(0, 0, frame.getWidth(), frame.getHeight()), r, Matrix.ScaleToFit.FILL);
            c.drawBitmap(frame, m, paint);
        }

        private void sendAt(float x, float y) {
            RectF r = imageRect();
            double nx = (x - r.left) / r.width(), ny = (y - r.top) / r.height();
            conn.moveTo(Math.max(0, Math.min(1, nx)), Math.max(0, Math.min(1, ny)));
        }

        private void clampPan() {
            if (zoom <= 1f) { zoom = 1f; panX = panY = 0; return; }
            RectF r = imageRect();
            float maxX = Math.max(0, (r.width() - getWidth()) / 2f), maxY = Math.max(0, (r.height() - getHeight()) / 2f);
            panX = Math.max(-maxX, Math.min(maxX, panX));
            panY = Math.max(-maxY, Math.min(maxY, panY));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    QuickKeys.releaseNow();
                    downX = lastX = e.getX(); downY = lastY = e.getY();
                    downT = System.currentTimeMillis();
                    moved = dragging = multi = false;
                    heldThisTouch = false;
                    if (!holding) postDelayed(longPress, 450);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multi = true;
                    removeCallbacks(longPress);
                    if (dragging) { conn.click("l", "up"); dragging = false; }
                    lastSpan = span(e);
                    lastX = midX(e); lastY = midY(e);
                    scrollRem = 0;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (multi && e.getPointerCount() >= 2) {
                        float sp = span(e), mx = midX(e), my = midY(e);
                        float scale = lastSpan > 0 ? sp / lastSpan : 1f;
                        if (Math.abs(scale - 1f) > 0.004f || zoom > 1f) {
                            zoom = Math.max(1f, Math.min(5f, zoom * scale));
                            panX += mx - lastX; panY += my - lastY;
                            clampPan();
                            invalidate();
                        }
                        if (zoom <= 1.01f) { // not zoomed: two-finger drag scrolls the PC
                            scrollRem += (my - lastY) * 1.5f;
                            int s = (int) scrollRem;
                            scrollRem -= s;
                            if (s != 0) conn.scroll(Prefs.invertScroll(getContext()) ? -s : s, 0);
                        }
                        lastSpan = sp; lastX = mx; lastY = my;
                    } else if (!multi) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (!moved && Math.hypot(dx, dy) > Ui.dp(getContext(), 8)) {
                            moved = true;
                            removeCallbacks(longPress);
                        }
                        if (moved) sendAt(e.getX(), e.getY()); // the pointer follows your finger
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    boolean tap = !moved && !multi && System.currentTimeMillis() - downT < 450;
                    if (holding && !heldThisTouch) {
                        if (tap) { sendAt(e.getX(), e.getY()); conn.click("l", "up"); holding = false; Ui.haptic(this); }
                    } else if (!holding && tap) {
                        sendAt(e.getX(), e.getY());
                        conn.click(rightNext ? "r" : "l", "click");
                        rightNext = false;
                    }
                    dragging = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    if (dragging) conn.click("l", "up");
                    dragging = false;
                    break;
            }
            return true;
        }

        private float span(MotionEvent e) { return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)); }
        private float midX(MotionEvent e) { return (e.getX(0) + e.getX(1)) / 2f; }
        private float midY(MotionEvent e) { return (e.getY(0) + e.getY(1)) / 2f; }
    }
}
