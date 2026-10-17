package com.example.tvremote;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/** Floating remote: a small draggable bubble; tap it to open a mini pad (Up/Down/Left/Right/OK/Back). */
public final class FloatService extends Service {
    public static volatile boolean running = false;
    static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23, BACK = 4, HOME = 3, VOLUP = 24, VOLDOWN = 25;
    private WindowManager wm;
    private BubbleView bubble;
    private PadView pad;
    private WindowManager.LayoutParams lp;
    static final float PW = 260f, PH = 264f;
    private float dp;
    private final Handler h = new Handler(Looper.getMainLooper());

    static void send(Context c, int code, int dir) {
        TvBridge b = TvBridge.getInstance();
        if (b == null) { Toast.makeText(c, "Open the Remote app first", Toast.LENGTH_SHORT).show(); return; }
        b.key(code, dir);
    }

    private void openApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); startActivity(i); }
        } catch (Throwable ignored) {}
    }

    public static void start(Context c) { c.startService(new Intent(c, FloatService.class)); }
    public static void stop(Context c) { c.stopService(new Intent(c, FloatService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (bubble == null && pad == null) build();
        return START_NOT_STICKY;
    }

    private void build() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        dp = getResources().getDisplayMetrics().density;
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        DisplayMetrics m = getResources().getDisplayMetrics();
        lp.x = m.widthPixels - (int) (46 * dp);
        lp.y = m.heightPixels / 2;
        bubble = new BubbleView(this);
        try { wm.addView(bubble, lp); running = true; }
        catch (Throwable t) { bubble = null; running = false; stopSelf(); }
    }

    private void showPad() {
        if (pad != null || wm == null) return;
        DisplayMetrics m = getResources().getDisplayMetrics();
        int pw = (int) (PW * dp), ph = (int) (PH * dp), bs = (int) (46 * dp);
        int cx = lp.x + bs / 2, cy = lp.y + bs / 2;
        lp.x = Math.max(0, Math.min(m.widthPixels - pw, cx - pw / 2));
        lp.y = Math.max(0, Math.min(m.heightPixels - ph, cy - ph / 2));
        try { wm.removeView(bubble); } catch (Throwable ignored) {}
        pad = new PadView(this);
        wm.addView(pad, lp);
    }

    private void hidePad() {
        if (pad == null) return;
        int pw = (int) (PW * dp), ph = (int) (PH * dp), bs = (int) (46 * dp);
        DisplayMetrics m = getResources().getDisplayMetrics();
        int cx = lp.x + pw / 2, cy = lp.y + ph / 2;
        try { wm.removeView(pad); } catch (Throwable ignored) {}
        pad = null;
        lp.x = cx < m.widthPixels / 2 ? 0 : m.widthPixels - bs;
        lp.y = Math.max(0, Math.min(m.heightPixels - bs, cy - bs / 2));
        wm.addView(bubble, lp);
    }

    @Override public void onDestroy() {
        running = false;
        try { if (bubble != null && bubble.isAttachedToWindow()) wm.removeView(bubble); } catch (Throwable ignored) {}
        try { if (pad != null) wm.removeView(pad); } catch (Throwable ignored) {}
        bubble = null; pad = null;
        super.onDestroy();
    }

    // ---------- shared drawing ----------
    private static void tri(Canvas c, Paint p, float cx, float cy, float r, int dir) {
        Path t = new Path();
        if (dir == 0) { t.moveTo(cx, cy - r); t.lineTo(cx + r, cy + r * .7f); t.lineTo(cx - r, cy + r * .7f); }
        else if (dir == 1) { t.moveTo(cx, cy + r); t.lineTo(cx + r, cy - r * .7f); t.lineTo(cx - r, cy - r * .7f); }
        else if (dir == 2) { t.moveTo(cx - r, cy); t.lineTo(cx + r * .7f, cy - r); t.lineTo(cx + r * .7f, cy + r); }
        else { t.moveTo(cx + r, cy); t.lineTo(cx - r * .7f, cy - r); t.lineTo(cx - r * .7f, cy + r); }
        t.close();
        c.drawPath(t, p);
    }

    private final class BubbleView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float dx, dy; private int sx, sy; private boolean moved;
        BubbleView(Context c) { super(c); setAlpha(.88f); }
        @Override protected void onMeasure(int w, int hh) { int s = (int) (46 * dp); setMeasuredDimension(s, s); }
        @Override protected void onDraw(Canvas c) {
            float s = getWidth(), r = s / 2f;
            p.setStyle(Paint.Style.FILL); p.setColor(0xF0191C24); c.drawCircle(r, r, r - 2 * dp, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * dp); p.setColor(0xFF3EE6A8); c.drawCircle(r, r, r - 2 * dp, p);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF);
            float o = s * .24f, k = s * .075f;
            tri(c, p, r, r - o, k, 0); tri(c, p, r, r + o, k, 1); tri(c, p, r - o, r, k, 2); tri(c, p, r + o, r, k, 3);
            p.setColor(0xFF3EE6A8); c.drawCircle(r, r, s * .07f, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; moved = false; return true;
                case MotionEvent.ACTION_MOVE: {
                    float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                    if (!moved && Math.abs(mx) + Math.abs(my) > 8 * dp) moved = true;
                    if (moved) { lp.x = sx + (int) mx; lp.y = sy + (int) my; try { wm.updateViewLayout(this, lp); } catch (Throwable ignored) {} }
                    return true; }
                case MotionEvent.ACTION_UP:
                    if (!moved) { performHaptic(); showPad(); }
                    else { // snap to nearest side edge
                        DisplayMetrics m = getResources().getDisplayMetrics();
                        lp.x = lp.x + getWidth() / 2 < m.widthPixels / 2 ? 0 : m.widthPixels - getWidth();
                        lp.y = Math.max(0, Math.min(m.heightPixels - getHeight(), lp.y));
                        try { wm.updateViewLayout(this, lp); } catch (Throwable ignored) {}
                    }
                    return true;
            }
            return true;
        }
        private void performHaptic() { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); }
    }

    private final class PadView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final int R_NONE = 0, R_HEAD = 1, R_TOG = 2, R_PAD = 3, R_VOL = 4, R_BACK = 5, R_HOME = 6, R_MIC = 7;
        private int mode = 0;                       // 0 = touchpad (default), 1 = D-pad
        private int region = R_NONE, zone = -1;     // zone: 0 up,1 left,2 right,3 down,4 ok
        private float rawX, rawY, sx, sy;           // drag of the whole panel
        private boolean dragged, padDown;
        private float lx, ly, ax, ay, total; private long t0; private int sent;
        private float vy, vacc, volOff; private boolean volUpDir;
        private int repCode;
        private final Runnable repeatTick = new Runnable() { public void run() {
            if (padDown && repCode != 0) { send(getContext(), repCode, 3); h.postDelayed(this, 110); } } };
        private final int[] zoneKeys = {UP, LEFT, RIGHT, DOWN, OK};
        private final RectF rHead = new RectF(), rPad = new RectF(), rVol = new RectF(), rBack = new RectF(), rHome = new RectF(), rMic = new RectF();
        private float tgx, tgy, tgr;

        PadView(Context c) { super(c); setAlpha(.95f); }
        @Override protected void onMeasure(int w, int hh) { setMeasuredDimension((int) (PW * dp), (int) (PH * dp)); }
        @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
            rHead.set(0, 0, w, 24 * dp);
            rVol.set(8 * dp, 24 * dp, 42 * dp, 204 * dp);
            rPad.set(48 * dp, 24 * dp, 252 * dp, 204 * dp);
            rBack.set(8 * dp, 212 * dp, 124 * dp, 256 * dp);
            rHome.set(130 * dp, 212 * dp, 188 * dp, 256 * dp);
            rMic.set(194 * dp, 212 * dp, 252 * dp, 256 * dp);
            tgx = rPad.left + 22 * dp; tgy = rPad.bottom - 22 * dp; tgr = 16 * dp;
        }
        // D-pad geometry (same shape as the in-app D-pad: OK box 6dp smaller on every side)
        private float bl() { return rPad.left + rPad.width() * .30f + 6 * dp; }
        private float br() { return rPad.left + rPad.width() * .70f - 6 * dp; }
        private float bt() { return rPad.top + rPad.height() * .28f + 6 * dp; }
        private float bb() { return rPad.top + rPad.height() * .72f - 6 * dp; }
        private float[] poly(int z) {
            float L = rPad.left, R = rPad.right, T = rPad.top, B = rPad.bottom, l = bl(), r = br(), t = bt(), b = bb();
            switch (z) {
                case 0: return new float[]{L, T, R, T, r, t, l, t};
                case 1: return new float[]{L, T, l, t, l, b, L, B};
                case 2: return new float[]{R, T, r, t, r, b, R, B};
                default: return new float[]{L, B, R, B, r, b, l, b};
            }
        }
        private boolean inPoly(float[] v, float x, float y) {
            boolean in = false;
            for (int i = 0, j = v.length - 2; i < v.length; j = i, i += 2)
                if (((v[i + 1] > y) != (v[j + 1] > y)) && (x < (v[j] - v[i]) * (y - v[i + 1]) / (v[j + 1] - v[i + 1]) + v[i])) in = !in;
            return in;
        }
        private int dpadZone(float x, float y) {
            if (x >= bl() && x <= br() && y >= bt() && y <= bb()) return 4;
            for (int z = 0; z < 4; z++) if (inPoly(poly(z), x, y)) return z;
            return -1;
        }
        private int hit(float x, float y) {
            if (rHead.contains(x, y)) return R_HEAD;
            if (rPad.contains(x, y)) { float dx = x - tgx, dy = y - tgy; return dx * dx + dy * dy <= tgr * tgr * 1.6f ? R_TOG : R_PAD; }
            if (rVol.contains(x, y)) return R_VOL;
            if (rBack.contains(x, y)) return R_BACK;
            if (rHome.contains(x, y)) return R_HOME;
            if (rMic.contains(x, y)) return R_MIC;
            return R_NONE;
        }
        private void stopRepeat() { h.removeCallbacks(repeatTick); repCode = 0; }
        private void armRepeat(int code) { stopRepeat(); repCode = code; h.postDelayed(repeatTick, 380); }

        // ----- drawing -----
        private void btn(Canvas c, RectF r, boolean on) {
            p.setStyle(Paint.Style.FILL); p.setColor(on ? 0xFF2F3544 : 0xFF1F232C);
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p);
        }
        private void chevron(Canvas c, float cx, float cy, int dir, float s) {
            Path t = new Path();
            if (dir == 0) { t.moveTo(cx - s, cy + s * .5f); t.lineTo(cx, cy - s * .5f); t.lineTo(cx + s, cy + s * .5f); }
            else if (dir == 3) { t.moveTo(cx - s, cy - s * .5f); t.lineTo(cx, cy + s * .5f); t.lineTo(cx + s, cy - s * .5f); }
            else if (dir == 1) { t.moveTo(cx + s * .5f, cy - s); t.lineTo(cx - s * .5f, cy); t.lineTo(cx + s * .5f, cy + s); }
            else { t.moveTo(cx - s * .5f, cy - s); t.lineTo(cx + s * .5f, cy); t.lineTo(cx - s * .5f, cy + s); }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f * dp); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(t, p);
        }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            // body
            p.setStyle(Paint.Style.FILL); p.setColor(0xF2141720); c.drawRoundRect(new RectF(0, 0, W, H), 20 * dp, 20 * dp, p);
            // header: grip + close
            p.setColor(0x66FFFFFF); for (int i = -1; i <= 1; i++) c.drawCircle(W / 2 + i * 9 * dp, 12 * dp, 1.6f * dp, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f * dp); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(0xAAFFFFFF);
            // open-main-app icon (small remote), top-left, opposite the close button
            float ox = 18 * dp, oy = 12 * dp;
            p.setStrokeWidth(1.7f * dp); p.setColor(0xCCFFFFFF);
            c.drawRoundRect(new RectF(ox - 4.5f * dp, oy - 8 * dp, ox + 4.5f * dp, oy + 8 * dp), 2.6f * dp, 2.6f * dp, p);
            p.setStyle(Paint.Style.FILL); c.drawCircle(ox, oy - 3.2f * dp, 1.5f * dp, p);
            p.setStyle(Paint.Style.STROKE); c.drawLine(ox - 1.8f * dp, oy + 2 * dp, ox + 1.8f * dp, oy + 2 * dp, p); c.drawLine(ox - 1.8f * dp, oy + 5 * dp, ox + 1.8f * dp, oy + 5 * dp, p);
            p.setStrokeWidth(2f * dp); p.setColor(0xAAFFFFFF);
            float xq = W - 18 * dp, yq = 12 * dp, q = 4 * dp;
            c.drawLine(xq - q, yq - q, xq + q, yq + q, p); c.drawLine(xq - q, yq + q, xq + q, yq - q, p);
            // pad
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF22262F); c.drawRoundRect(rPad, 18 * dp, 18 * dp, p);
            if (mode == 0) {
                p.setColor(0x55FFFFFF); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(15 * dp);
                c.drawText("touch pad", rPad.centerX(), rPad.centerY() + 5 * dp, p);
            } else {
                int save = c.save(); Path clip = new Path(); clip.addRoundRect(rPad, 18 * dp, 18 * dp, Path.Direction.CW); c.clipPath(clip);
                if (zone >= 0 && zone < 4) {
                    float[] v = poly(zone); Path pp = new Path(); pp.moveTo(v[0], v[1]); for (int i = 2; i < v.length; i += 2) pp.lineTo(v[i], v[i + 1]); pp.close();
                    p.setStyle(Paint.Style.FILL); p.setColor(0x4D22D3B8); c.drawPath(pp, p);
                }
                if (zone == 4) { p.setStyle(Paint.Style.FILL); p.setColor(0x4D22D3B8); c.drawRect(bl(), bt(), br(), bb(), p); }
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1f * dp); p.setColor(0x42FFFFFF);
                float L = rPad.left, R = rPad.right, T = rPad.top, B = rPad.bottom;
                c.drawLine(L, T, bl(), bt(), p); c.drawLine(R, T, br(), bt(), p); c.drawLine(L, B, bl(), bb(), p); c.drawLine(R, B, br(), bb(), p);
                c.drawRect(bl(), bt(), br(), bb(), p);
                p.setColor(0xCCFFFFFF);
                float cx = rPad.centerX(), cy = rPad.centerY(), s = 6 * dp;
                chevron(c, cx, T + rPad.height() * .14f, 0, s); chevron(c, cx, B - rPad.height() * .14f, 3, s);
                chevron(c, L + rPad.width() * .10f, cy, 1, s); chevron(c, R - rPad.width() * .10f, cy, 2, s);
                p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(15 * dp); p.setFakeBoldText(true); p.setColor(0xCCFFFFFF);
                c.drawText("OK", cx, cy + 5 * dp, p); p.setFakeBoldText(false);
                c.restoreToCount(save);
            }
            // pad <-> d-pad toggle (small teal circle, bottom-left of the pad)
            p.setStyle(Paint.Style.FILL); p.setColor(mode == 1 ? 0x2E22D3B8 : 0x1A22D3B8); c.drawCircle(tgx, tgy, tgr, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f * dp); p.setColor(mode == 1 ? 0x8C22D3B8 : 0x5522D3B8); c.drawCircle(tgx, tgy, tgr, p);
            p.setColor(mode == 1 ? 0xFF22D3B8 : 0x9922D3B8);
            if (mode == 0) { p.setStyle(Paint.Style.FILL); float o = 5.5f * dp, k = 2.2f * dp;
                tri(c, p, tgx, tgy - o, k, 0); tri(c, p, tgx, tgy + o, k, 1); tri(c, p, tgx - o, tgy, k, 2); tri(c, p, tgx + o, tgy, k, 3); }
            else { RectF tp = new RectF(tgx - 7 * dp, tgy - 5 * dp, tgx + 7 * dp, tgy + 5 * dp); c.drawRoundRect(tp, 2.5f * dp, 2.5f * dp, p); }
            // volume bar (red, left side)
            int sv = c.save(); Path vc = new Path(); vc.addRoundRect(rVol, 17 * dp, 17 * dp, Path.Direction.CW); c.clipPath(vc);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(rVol.left, rVol.top, rVol.right, rVol.bottom, 0xFFEF5B5B, 0xFF7D1B1B, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(rVol, p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * dp); p.setColor(0x33FFFFFF);
            for (float y = rVol.top + (volOff % (9 * dp)); y < rVol.bottom; y += 9 * dp) if (y >= rVol.top) c.drawLine(rVol.left, y, rVol.right, y, p);
            p.setColor(0x55000000);
            for (float y = rVol.top + (volOff % (9 * dp)) + 3 * dp; y < rVol.bottom; y += 9 * dp) if (y >= rVol.top) c.drawLine(rVol.left, y, rVol.right, y, p);
            c.restoreToCount(sv);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f * dp); p.setColor(0xCCFFFFFF);
            float vx = rVol.centerX(); c.drawLine(vx - 4 * dp, rVol.top + 14 * dp, vx + 4 * dp, rVol.top + 14 * dp, p); c.drawLine(vx, rVol.top + 10 * dp, vx, rVol.top + 18 * dp, p);
            c.drawLine(vx - 4 * dp, rVol.bottom - 14 * dp, vx + 4 * dp, rVol.bottom - 14 * dp, p);
            // bottom buttons
            btn(c, rBack, region == R_BACK); btn(c, rHome, region == R_HOME); btn(c, rMic, region == R_MIC);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f * dp); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(0xFFFFFFFF);
            float by = rBack.centerY(), bx = rBack.centerX() - 20 * dp;
            c.drawLine(bx - 6 * dp, by, bx + 6 * dp, by, p); c.drawLine(bx - 6 * dp, by, bx - 1 * dp, by - 5 * dp, p); c.drawLine(bx - 6 * dp, by, bx - 1 * dp, by + 5 * dp, p);
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.LEFT); p.setTextSize(14 * dp); p.setFakeBoldText(true);
            c.drawText("Back", bx + 12 * dp, by + 5 * dp, p); p.setFakeBoldText(false);
            // home
            p.setStyle(Paint.Style.STROKE); float hx = rHome.centerX(), hy = rHome.centerY();
            Path hp = new Path(); hp.moveTo(hx - 9 * dp, hy); hp.lineTo(hx, hy - 8 * dp); hp.lineTo(hx + 9 * dp, hy);
            hp.moveTo(hx - 6 * dp, hy - 2 * dp); hp.lineTo(hx - 6 * dp, hy + 8 * dp); hp.lineTo(hx + 6 * dp, hy + 8 * dp); hp.lineTo(hx + 6 * dp, hy - 2 * dp);
            c.drawPath(hp, p);
            // mic
            float mx = rMic.centerX(), my = rMic.centerY();
            c.drawRoundRect(new RectF(mx - 3.5f * dp, my - 9 * dp, mx + 3.5f * dp, my + 3 * dp), 3.5f * dp, 3.5f * dp, p);
            c.drawArc(new RectF(mx - 7 * dp, my - 5 * dp, mx + 7 * dp, my + 7 * dp), 0, 180, false, p);
            c.drawLine(mx, my + 7 * dp, mx, my + 10 * dp, p);
        }

        // ----- touch -----
        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            float x = e.getX(), y = e.getY();
            if (a == MotionEvent.ACTION_DOWN) {
                region = hit(x, y); dragged = false; zone = -1;
                switch (region) {
                    case R_HEAD:
                        if (x > getWidth() - 36 * dp) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); region = R_NONE; hidePad(); return true; }
                        if (x < 36 * dp) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); region = R_NONE; openApp(); hidePad(); return true; }
                        rawX = e.getRawX(); rawY = e.getRawY(); sx = lp.x; sy = lp.y; break;
                    case R_PAD:
                        if (mode == 0) { padDown = true; lx = x; ly = y; ax = 0; ay = 0; total = 0; t0 = System.currentTimeMillis(); sent = 0; }
                        else { zone = dpadZone(x, y); if (zone >= 0) { padDown = true; send(getContext(), zoneKeys[zone], 1); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); } }
                        break;
                    case R_VOL: vy = y; vacc = 0; break;
                    case R_BACK: send(getContext(), BACK, 3); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); break;
                    case R_HOME: send(getContext(), HOME, 3); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); break;
                    default: break;
                }
                invalidate(); return true;
            }
            if (a == MotionEvent.ACTION_MOVE) {
                if (region == R_HEAD) {
                    float mx = e.getRawX() - rawX, my = e.getRawY() - rawY;
                    DisplayMetrics m = getResources().getDisplayMetrics();
                    lp.x = Math.max(0, Math.min(m.widthPixels - getWidth(), (int) (sx + mx)));
                    lp.y = Math.max(0, Math.min(m.heightPixels - getHeight(), (int) (sy + my)));
                    try { wm.updateViewLayout(this, lp); } catch (Throwable ignored) {}
                } else if (region == R_PAD && mode == 0 && padDown) {
                    float dx = x - lx, dy = y - ly; lx = x; ly = y;
                    total += Math.abs(dx) + Math.abs(dy); ax += dx; ay += dy;
                    float need = (sent == 0 ? 42 : 72) * dp;
                    if (Math.abs(ax) >= need && Math.abs(ax) >= Math.abs(ay)) { int k = ax > 0 ? RIGHT : LEFT; send(getContext(), k, 3); armRepeat(k); sent++; ax = 0; ay = 0; }
                    else if (Math.abs(ay) >= need) { int k = ay > 0 ? DOWN : UP; send(getContext(), k, 3); armRepeat(k); sent++; ax = 0; ay = 0; }
                } else if (region == R_VOL) {
                    float dy = y - vy; vy = y; vacc += dy; volOff += dy;
                    float step = 14 * dp;
                    while (vacc <= -step) { vacc += step; send(getContext(), VOLUP, 3); }
                    while (vacc >= step) { vacc -= step; send(getContext(), VOLDOWN, 3); }
                    invalidate();
                }
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                boolean up = a == MotionEvent.ACTION_UP;
                if (region == R_PAD) {
                    stopRepeat();
                    if (mode == 0 && padDown && up && total < 10 * dp && System.currentTimeMillis() - t0 < 400) send(getContext(), OK, 3);
                    if (mode == 1 && zone >= 0) send(getContext(), zoneKeys[zone], 2);
                    padDown = false;
                } else if (region == R_TOG && up && hit(x, y) == R_TOG) {
                    mode = mode == 0 ? 1 : 0; performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                } else if (region == R_MIC && up && hit(x, y) == R_MIC) {
                    TvBridge b = TvBridge.getInstance();
                    if (b == null) Toast.makeText(getContext(), "Open the Remote app first", Toast.LENGTH_SHORT).show(); else b.voiceToggle();
                }
                region = R_NONE; zone = -1; invalidate();
                return true;
            }
            return true;
        }
    }
}
