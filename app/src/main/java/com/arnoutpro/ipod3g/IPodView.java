package com.arnoutpro.ipod3g;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Draws the whole iPod and turns touches on the wheel and buttons into iPod input. */
@SuppressLint("ViewConstructor")
public class IPodView extends View implements SensorEventListener {
    /** Height of the face's content in units of {@link #bw}, without spare space. */
    private static final float CONTENT_H = 0.07f + 0.80f * Lcd.H / Lcd.W + 0.07f + 0.06f + 0.18f + 0.06f + 0.72f + 0.08f;
    private static final float WHEEL_STEP_DEG = 15f;
    private static final long HOLD_MS = 650, SEEK_REPEAT_MS = 180;
    private static final int GLOW = 0xFFFF4A26, GLOW_OFF = 0xFFA9A9A4;

    private static final int B_PREV = 0, B_MENU = 1, B_PLAY = 2, B_NEXT = 3;
    private static final int T_NONE = -1, T_WHEEL = 10, T_CENTER = 11;

    private final MainActivity host;
    public final Lcd lcd;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lcdPaint = new Paint(); // no filtering: crisp pixels
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect lcdRect = new Rect();
    private final RectF body = new RectF(), bezel = new RectF();
    private final float[] btnX = new float[4];
    private float btnY, btnR, wheelX, wheelY, wheelR, centerR, bw;
    private Shader bodyShader, wheelShader, centerShader, btnShader, glassShader, glossShader;
    // Holes in the acrylic top layer that the buttons and the wheel sit sunk in.
    private final Shader[] btnWall = new Shader[4], btnEdge = new Shader[4];
    private Shader wheelWall, wheelEdge, centerWall, centerEdge;
    private float btnHole, wheelHole, centerHole;

    // Light from the room's ceiling, followed with the gravity sensor: the direction on screen
    // towards it, and how far the phone is tilted up from lying flat (0..1).
    private float gravX, gravY = SensorManager.GRAVITY_EARTH * 0.8f;
    private float lightX, lightY = -1, lightTilt = 0.8f;
    private final Matrix glossMatrix = new Matrix();

    // touch state
    private int target = T_NONE;
    private int pressed = T_NONE;
    private float lastAngle, accum;
    private float downX, downY;
    private boolean holdFired;
    private int seekDir;

    // backlight
    private boolean lit = true;
    private boolean litAtDown = true;
    // Turned off from the Backlight menu: stays dark until turned on there again.
    private boolean forcedOff;
    private long lastActivity;

    private final Runnable holdRunnable = this::onHold;
    private final Runnable seekRunnable = new Runnable() {
        @Override public void run() {
            PlayerService ps = host.service();
            if (seekDir == 0 || ps == null) return;
            ps.seekTo(ps.getPosition() + seekDir * 5000);
            handler.postDelayed(this, SEEK_REPEAT_MS);
        }
    };
    private final Runnable backlightCheck = this::checkBacklight;
    private final Runnable frame = this::invalidate;

    public IPodView(MainActivity host) {
        super(host);
        this.host = host;
        this.lcd = new Lcd(host);
        lcdPaint.setFilterBitmap(false);
        lcdPaint.setAntiAlias(false);
        glyph.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        glyph.setTextAlign(Paint.Align.CENTER);
        setHapticFeedbackEnabled(true);
        wake();
    }

    // ---- layout ----------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // The face fills the whole screen. Everything scales with one unit, taken from the
        // width unless the screen is too short for the stack, and spare height is shared
        // out between the sections.
        body.set(0, 0, w, h);
        bw = Math.min(w, h / CONTENT_H);
        float spare = h - bw * CONTENT_H;
        float cx = w / 2f;

        float lw = bw * 0.80f, lh = lw * Lcd.H / Lcd.W;
        float pad = bw * 0.035f;
        float bezelTop = bw * 0.07f + spare * 0.30f;
        float lx = cx - lw / 2f, ly = bezelTop + pad;
        lcdRect.set(Math.round(lx), Math.round(ly), Math.round(lx + lw), Math.round(ly + lh));
        bezel.set(lcdRect.left - pad, lcdRect.top - pad, lcdRect.right + pad, lcdRect.bottom + pad);

        btnR = bw * 0.09f;
        btnY = bezel.bottom + bw * 0.06f + spare * 0.20f + btnR;
        float[] fx = {-1.5f, -0.5f, 0.5f, 1.5f};
        float spacing = bw * 0.235f;
        for (int i = 0; i < 4; i++) btnX[i] = cx + fx[i] * spacing;

        wheelR = bw * 0.36f;
        wheelX = cx;
        wheelY = btnY + btnR + bw * 0.06f + spare * 0.15f + wheelR;
        centerR = wheelR * 0.35f;

        // White plastic seen through the acrylic: a touch cooler and darker than pure white.
        bodyShader = new LinearGradient(body.left, body.top, body.right, body.bottom,
                new int[]{0xFFFAFBFB, 0xFFF3F4F4, 0xFFE8E9E8}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        wheelShader = new RadialGradient(wheelX, wheelY - wheelR * 0.3f, wheelR * 1.3f,
                0xFFFFFFFF, 0xFFF0F0EE, Shader.TileMode.CLAMP);
        centerShader = new LinearGradient(0, wheelY - centerR, 0, wheelY + centerR,
                0xFFF1F1EF, 0xFFFFFFFF, Shader.TileMode.CLAMP);
        btnShader = new LinearGradient(0, btnY - btnR, 0, btnY + btnR,
                0xFFF2F2F0, 0xFFFFFFFF, Shader.TileMode.CLAMP);
        glassShader = new LinearGradient(bezel.left, bezel.top, bezel.left + bezel.width() * 0.6f, bezel.bottom,
                0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        // A broad diagonal sheen on the acrylic surface, over everything.
        glossShader = new LinearGradient(0, 0, w, h * 0.55f,
                new int[]{0x20FFFFFF, 0x0AFFFFFF, 0x00FFFFFF, 0x07FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.3f, 0.48f, 0.56f, 0.7f}, Shader.TileMode.CLAMP);

        btnHole = btnR * 1.1f;
        wheelHole = wheelR + bw * 0.014f;
        centerHole = centerR + bw * 0.006f;
        buildHoles();
    }

    private void buildHoles() {
        if (bw == 0) return;
        for (int i = 0; i < 4; i++) {
            btnWall[i] = wallShader(btnX[i], btnY, btnHole, 0x20);
            btnEdge[i] = edgeShader(btnX[i], btnY, btnHole);
        }
        wheelWall = wallShader(wheelX, wheelY, wheelHole, 0x1C);
        wheelEdge = edgeShader(wheelX, wheelY, wheelHole);
        centerWall = wallShader(wheelX, wheelY, centerHole, 0x14);
        centerEdge = edgeShader(wheelX, wheelY, centerHole);
        // The sheen slides the other way as the phone tilts, like a reflection.
        glossMatrix.setTranslate(-lightX * lightTilt * bw * 0.12f, -lightY * lightTilt * bw * 0.12f);
        glossShader.setLocalMatrix(glossMatrix);
    }

    /** Shadow the hole's wall casts inwards, deepest on the side the light comes from. */
    private Shader wallShader(float x, float y, float hole, int alpha) {
        float off = hole * 0.14f * lightTilt, rad = hole + off;
        return new RadialGradient(x - lightX * off, y - lightY * off, rad,
                new int[]{0x00000000, 0x00000000, alpha << 24},
                new float[]{0f, (hole * 0.8f) / rad, 1f}, Shader.TileMode.CLAMP);
    }

    /** The cut edge of the acrylic: dark towards the light, catching it on the far side. */
    private Shader edgeShader(float x, float y, float hole) {
        float k = 0.35f + 0.65f * lightTilt;
        float dx = lightX * hole, dy = lightY * hole;
        return new LinearGradient(x + dx, y + dy, x - dx, y - dy,
                new int[]{(int) (0x1A * k) << 24, 0x06000000, (int) (0x90 * k) << 24 | 0xFFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    }

    // ---- tilt ------------------------------------------------------------------------------

    public void startTilt() {
        SensorManager sm = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (g == null) g = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (g != null) sm.registerListener(this, g, SensorManager.SENSOR_DELAY_UI);
    }

    public void stopTilt() {
        ((SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE)).unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        // Smooth it, then only redraw for a visible change.
        gravX += (e.values[0] - gravX) * 0.2f;
        gravY += (e.values[1] - gravY) * 0.2f;
        float len = (float) Math.hypot(gravX, gravY);
        float tilt = Math.min(1f, len / SensorManager.GRAVITY_EARTH);
        // The sensor's y points to the top of the screen, the canvas's y down.
        float nx = len > 0.01f ? gravX / len : 0, ny = len > 0.01f ? -gravY / len : -1;
        if (Math.abs(tilt - lightTilt) < 0.02f && Math.hypot(nx - lightX, ny - lightY) < 0.03f) return;
        lightX = nx;
        lightY = ny;
        lightTilt = tilt;
        buildHoles();
        invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ---- drawing ---------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.currentTimeMillis();
        long delay = lcd.render(lit, now);
        drawBody(c);
        drawScreen(c, now);
        for (int i = 0; i < 4; i++) drawButton(c, i);
        drawWheel(c);
        paint.setShader(glossShader);
        c.drawRect(body, paint);
        paint.setShader(null);

        if (delay >= 0) {
            handler.removeCallbacks(frame);
            handler.postDelayed(frame, Math.max(16, delay));
        }
    }

    private void drawBody(Canvas c) {
        paint.setShader(bodyShader);
        c.drawRect(body, paint);
        paint.setShader(null);
    }

    private void drawScreen(Canvas c, long now) {
        float r = bw * 0.03f;
        RectF rim = new RectF(bezel);
        rim.inset(-bw * 0.008f, -bw * 0.008f);
        paint.setColor(0xFFE2E2DF);
        c.drawRoundRect(rim, r * 1.3f, r * 1.3f, paint);
        paint.setColor(0xFF26292B);
        c.drawRoundRect(bezel, r, r, paint);

        c.drawBitmap(lcd.bitmap, null, lcdRect, lcdPaint);

        // Inner shadow along the top of the LCD, then the glass reflection.
        paint.setShader(new LinearGradient(0, lcdRect.top, 0, lcdRect.top + bw * 0.03f,
                0x50000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(lcdRect, paint);
        paint.setShader(glassShader);
        c.drawRoundRect(bezel, r, r, paint);
        paint.setShader(null);
    }

    private void drawButton(Canvas c, int i) {
        float x = btnX[i];
        boolean down = pressed == i;
        paint.setColor(0xFFE6E7E5); // floor of the hole, around the button
        c.drawCircle(x, btnY, btnHole, paint);
        paint.setShader(btnShader);
        c.drawCircle(x, btnY, btnR, paint);
        paint.setShader(null);
        if (down) {
            paint.setColor(0x22000000);
            c.drawCircle(x, btnY, btnR, paint);
        }

        int col = lit ? GLOW : GLOW_OFF;
        glyph.setColor(col);
        if (lit) glyph.setShadowLayer(btnR * 0.28f, 0, 0, 0xFFFF3A10);
        else glyph.clearShadowLayer();
        float s = btnR * 0.26f;
        Path p = new Path();
        switch (i) {
            case B_PREV:
                c.drawRect(x - s * 1.6f, btnY - s, x - s * 1.2f, btnY + s, glyph);
                tri(p, x - s * 1.2f, btnY, s, -1);
                tri(p, x + s * 0.2f, btnY, s, -1);
                c.drawPath(p, glyph);
                break;
            case B_NEXT:
                tri(p, x - s * 1.6f, btnY, s, 1);
                tri(p, x - s * 0.2f, btnY, s, 1);
                c.drawPath(p, glyph);
                c.drawRect(x + s * 1.2f, btnY - s, x + s * 1.6f, btnY + s, glyph);
                break;
            case B_PLAY:
                tri(p, x - s * 1.5f, btnY, s, 1);
                c.drawPath(p, glyph);
                c.drawRect(x + s * 0.25f, btnY - s, x + s * 0.7f, btnY + s, glyph);
                c.drawRect(x + s * 1.05f, btnY - s, x + s * 1.5f, btnY + s, glyph);
                break;
            case B_MENU:
                glyph.setTextSize(btnR * 0.42f);
                Paint.FontMetrics fm = glyph.getFontMetrics();
                c.drawText("MENU", x, btnY - (fm.ascent + fm.descent) / 2f, glyph);
                break;
        }
        drawHole(c, x, btnY, btnHole, btnWall[i], btnEdge[i]);
    }

    private void drawHole(Canvas c, float x, float y, float hole, Shader wall, Shader edge) {
        paint.setShader(wall);
        c.drawCircle(x, y, hole, paint);
        paint.setShader(edge);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(bw * 0.006f);
        c.drawCircle(x, y, hole, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
    }

    /** A triangle pointing left (dir -1) or right (dir 1), starting at x. */
    private static void tri(Path p, float x, float cy, float s, int dir) {
        float w = s * 1.4f;
        if (dir > 0) {
            p.moveTo(x, cy - s);
            p.lineTo(x + w, cy);
            p.lineTo(x, cy + s);
        } else {
            p.moveTo(x + w, cy - s);
            p.lineTo(x, cy);
            p.lineTo(x + w, cy + s);
        }
        p.close();
    }

    private void drawWheel(Canvas c) {
        paint.setColor(0xFFE6E7E5); // floor of the hole, around the wheel
        c.drawCircle(wheelX, wheelY, wheelHole, paint);
        paint.setShader(wheelShader);
        c.drawCircle(wheelX, wheelY, wheelR - bw * 0.004f, paint);
        paint.setShader(null);
        if (pressed == T_WHEEL) {
            paint.setColor(0x0C000000);
            c.drawCircle(wheelX, wheelY, wheelR, paint);
        }

        drawHole(c, wheelX, wheelY, wheelHole, wheelWall, wheelEdge);

        paint.setColor(0xFFE6E7E5);
        c.drawCircle(wheelX, wheelY, centerHole, paint);
        paint.setShader(centerShader);
        c.drawCircle(wheelX, wheelY, centerR - bw * 0.004f, paint);
        paint.setShader(null);
        if (pressed == T_CENTER) {
            paint.setColor(0x18000000);
            c.drawCircle(wheelX, wheelY, centerR, paint);
        }
        drawHole(c, wheelX, wheelY, centerHole, centerWall, centerEdge);
    }

    // ---- touch -----------------------------------------------------------------------------

    private int hitTest(float x, float y) {
        float d = (float) Math.hypot(x - wheelX, y - wheelY);
        if (d <= centerR * 1.05f) return T_CENTER;
        if (d <= wheelR * 1.12f) return T_WHEEL;
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(x - btnX[i], y - btnY) <= btnR * 1.2f) return i;
        }
        return T_NONE;
    }

    private float angle(float x, float y) {
        return (float) Math.toDegrees(Math.atan2(y - wheelY, x - wheelX));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                litAtDown = lit;
                wake();
                target = hitTest(x, y);
                pressed = target;
                downX = x;
                downY = y;
                holdFired = false;
                accum = 0;
                lastAngle = angle(x, y);
                if (target == T_CENTER || target == B_PREV || target == B_NEXT || target == B_PLAY) {
                    handler.postDelayed(holdRunnable, target == B_PLAY ? 2000 : HOLD_MS);
                }
                if (target >= 0 && target <= 3) feedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                // Sliding from the centre button onto the ring turns it into a scroll.
                if (target == T_CENTER && !holdFired && Math.hypot(x - downX, y - downY) > centerR * 0.6f
                        && Math.hypot(x - wheelX, y - wheelY) > centerR) {
                    handler.removeCallbacks(holdRunnable);
                    target = T_WHEEL;
                    pressed = T_WHEEL;
                    lastAngle = angle(x, y);
                    accum = 0;
                }
                if (target == T_WHEEL) {
                    if (Math.hypot(x - wheelX, y - wheelY) < centerR * 0.5f) return true; // too close to judge
                    float a = angle(x, y);
                    float d = a - lastAngle;
                    if (d > 180) d -= 360;
                    if (d < -180) d += 360;
                    lastAngle = a;
                    accum += d;
                    long now = System.currentTimeMillis();
                    while (Math.abs(accum) >= WHEEL_STEP_DEG) {
                        int dir = accum > 0 ? 1 : -1;
                        accum -= dir * WHEEL_STEP_DEG;
                        wake();
                        if (lcd.scroll(dir, now)) tick();
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(holdRunnable);
                int t = target;
                target = T_NONE;
                pressed = T_NONE;
                if (seekDir != 0) {
                    seekDir = 0;
                    handler.removeCallbacks(seekRunnable);
                } else if (!holdFired && t == hitTest(x, y)) {
                    press(t);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(holdRunnable);
                seekDir = 0;
                handler.removeCallbacks(seekRunnable);
                target = T_NONE;
                pressed = T_NONE;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void press(int t) {
        PlayerService ps = host.service();
        switch (t) {
            case T_CENTER:
                tick();
                lcd.select();
                break;
            case B_MENU:
                tick();
                lcd.menu();
                break;
            case B_PLAY:
                tick();
                if (ps != null) {
                    if (ps.hasTrack()) ps.toggle();
                    else if (!host.library().isEmpty()) {
                        ps.playQueue(host.library().songs(), 0);
                        lcd.showNowPlaying();
                    }
                }
                break;
            case B_PREV:
                tick();
                if (ps != null) ps.previous();
                break;
            case B_NEXT:
                tick();
                if (ps != null) ps.next();
                break;
        }
        invalidate();
    }

    private void onHold() {
        holdFired = true;
        PlayerService ps = host.service();
        switch (target) {
            case T_CENTER:
                feedback(HapticFeedbackConstants.LONG_PRESS);
                lcd.selectHold();
                break;
            case B_PLAY:
                // Holding play/pause puts the iPod to sleep.
                feedback(HapticFeedbackConstants.LONG_PRESS);
                if (ps != null) ps.pause();
                lit = false;
                break;
            case B_PREV:
            case B_NEXT:
                if (ps != null && ps.hasTrack()) {
                    seekDir = target == B_NEXT ? 1 : -1;
                    handler.post(seekRunnable);
                }
                break;
        }
        invalidate();
    }

    private void tick() {
        if (!host.clickerOn()) return;
        host.clicker().click();
        feedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void feedback(int type) {
        if (host.clickerOn()) performHapticFeedback(type);
    }

    // ---- backlight -------------------------------------------------------------------------

    /** Any touch lights the backlight for the configured time, unless it was turned off. */
    public void wake() {
        lastActivity = System.currentTimeMillis();
        if (forcedOff) return;
        int s = host.backlightSeconds();
        boolean was = lit;
        lit = s != 0;
        if (was != lit) invalidate();
        handler.removeCallbacks(backlightCheck);
        if (s > 0) handler.postDelayed(backlightCheck, s * 1000L + 50);
    }

    public void toggleBacklight() {
        // The touch that selected the menu item already woke the light, so toggle from before it.
        lit = !litAtDown;
        litAtDown = lit;
        forcedOff = !lit;
        handler.removeCallbacks(backlightCheck);
        lastActivity = System.currentTimeMillis();
        int s = host.backlightSeconds();
        if (lit && s > 0) handler.postDelayed(backlightCheck, s * 1000L + 50);
        invalidate();
    }

    private void checkBacklight() {
        int s = host.backlightSeconds();
        if (s > 0 && System.currentTimeMillis() - lastActivity >= s * 1000L) {
            lit = false;
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
