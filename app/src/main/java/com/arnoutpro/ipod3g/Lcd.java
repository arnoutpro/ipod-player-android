package com.arnoutpro.ipod3g;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.ArrayList;

/**
 * The 3rd generation iPod display: renders a low resolution screen (twice the original
 * 160x128) without anti-aliasing, so it keeps the chunky pixel look when scaled up.
 */
public final class Lcd {
    public static final int W = 320, H = 292; // title bar and seven rows
    public static final int TITLE_H = 36, ROW_H = 36;
    private static final long SLIDE_MS = 170;

    public static final int BG_LIT = 0xFFCAD7D9;
    public static final int BG_DARK = 0xFF9FA69C;
    public static final int INK = 0xFF1E2226;

    public final MainActivity host;
    public final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(bitmap);

    public final Paint text = new Paint();
    public final Paint small = new Paint();
    public final Paint big = new Paint();
    public final Paint ink = new Paint();
    public final Paint stroke = new Paint();
    public final Paint dither = new Paint();

    private final ArrayList<Screen> stack = new ArrayList<>();
    private Screen leaving;
    private int slideDir;
    private long slideStart;

    public int bg = BG_LIT;

    public Lcd(MainActivity host) {
        this.host = host;
        Typeface bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        for (Paint p : new Paint[]{text, small, big}) {
            p.setAntiAlias(false);
            p.setSubpixelText(false);
            p.setTypeface(bold);
            p.setColor(INK);
        }
        text.setTextSize(21);
        small.setTextSize(17);
        big.setTextSize(64);
        ink.setAntiAlias(false);
        ink.setColor(INK);
        stroke.setAntiAlias(false);
        stroke.setColor(INK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2);

        // The 3G progress bar is filled with a checkerboard dither.
        Bitmap chk = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
                chk.setPixel(x, y, ((x / 2 + y / 2) % 2 == 0) ? INK : 0x00000000);
        dither.setShader(new BitmapShader(chk, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    // ---- navigation ------------------------------------------------------------------------

    public Screen top() { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    public void push(Screen s) {
        leaving = top();
        slideDir = 1;
        slideStart = System.currentTimeMillis();
        stack.add(s);
        s.shown();
        host.redraw();
    }

    public void pop() {
        if (stack.size() <= 1) return;
        leaving = stack.remove(stack.size() - 1);
        slideDir = -1;
        slideStart = System.currentTimeMillis();
        top().shown();
        host.redraw();
    }

    /** Replaces the whole stack, without animation. */
    public void reset(Screen root) {
        stack.clear();
        leaving = null;
        stack.add(root);
        root.shown();
        host.redraw();
    }

    public boolean atRoot() { return stack.size() <= 1; }

    /** Shows Now Playing, reusing an open one instead of stacking another. */
    public void showNowPlaying() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i) instanceof NowPlayingScreen) {
                if (i == stack.size() - 1) return;
                while (stack.size() > i + 1) stack.remove(stack.size() - 1);
                leaving = null;
                top().shown();
                host.redraw();
                return;
            }
        }
        push(new NowPlayingScreen(this));
    }

    // ---- input -----------------------------------------------------------------------------

    public boolean scroll(int d, long now) {
        Screen s = top();
        return s != null && s.scroll(d, now);
    }

    public void select() { if (top() != null) top().select(); }

    public void selectHold() { if (top() != null) top().selectHold(); }

    /** Returns false when already at the main menu. */
    public boolean menu() {
        Screen s = top();
        if (s == null) return false;
        if (s.menu()) return true;
        if (atRoot()) return false;
        pop();
        return true;
    }

    // ---- rendering -------------------------------------------------------------------------

    /** Renders into {@link #bitmap}; returns the delay until the next frame, or -1. */
    public long render(boolean lit, long now) {
        bg = lit ? BG_LIT : BG_DARK;
        Canvas c = canvas;
        c.drawColor(bg);
        Screen s = top();
        if (s == null) return -1;

        long delay = s.frameDelay(now);
        int ch = H - TITLE_H;
        float p = 1f;
        if (leaving != null) {
            p = Math.min(1f, (now - slideStart) / (float) SLIDE_MS);
            if (p >= 1f) leaving = null;
            else delay = 16;
        }
        drawTitleBar(c, (leaving != null && p < 0.5f) ? leaving.title() : s.title());

        c.save();
        c.clipRect(0, TITLE_H, W, H);
        c.translate(0, TITLE_H);
        if (leaving != null) {
            float e = 1f - (1f - p) * (1f - p);
            float off = e * W * slideDir;
            c.save();
            c.translate(-off, 0);
            leaving.draw(c, W, ch, now);
            c.restore();
            c.translate(W * slideDir - off, 0);
        }
        s.draw(c, W, ch, now);
        c.restore();
        return delay;
    }

    private void drawTitleBar(Canvas c, String title) {
        Playback ps = host.playback();
        // Play state indicator on the left.
        if (ps != null && ps.hasTrack()) {
            if (ps.isPlayingOrLoading()) {
                Path tri = new Path();
                tri.moveTo(8, 10);
                tri.lineTo(20, 17);
                tri.lineTo(8, 24);
                tri.close();
                c.drawPath(tri, ink);
            } else {
                c.drawRect(8, 10, 12, 24, ink);
                c.drawRect(16, 10, 20, 24, ink);
            }
        }
        // Battery on the right.
        int level = host.batteryLevel();
        float bx = W - 44, by = 10, bw = 32, bh = 15;
        c.drawRect(bx, by, bx + bw, by + bh, stroke);
        c.drawRect(bx + bw + 1, by + 4, bx + bw + 4, by + bh - 4, ink);
        float fill = (bw - 6) * Math.max(0, Math.min(100, level)) / 100f;
        c.drawRect(bx + 3, by + 3, bx + 3 + fill, by + bh - 3, host.isCharging() ? dither : ink);
        if (host.isCharging()) c.drawRect(bx + 3, by + 3, bx + 3 + fill, by + bh - 3, stroke);

        String t = fit(text, title, W - 120);
        float tw = text.measureText(t);
        c.drawText(t, (W - tw) / 2f, baseline(text, 0, TITLE_H - 2), text);
        c.drawRect(0, TITLE_H - 3, W, TITLE_H - 1, ink);
    }

    // ---- text helpers ----------------------------------------------------------------------

    /** Baseline that vertically centres the paint's text in [top, top+height]. */
    public static float baseline(Paint p, float top, float height) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return top + (height - (fm.descent - fm.ascent)) / 2f - fm.ascent;
    }

    /** Shortens text with an ellipsis so it fits in maxW. */
    public static String fit(Paint p, String s, float maxW) {
        if (p.measureText(s) <= maxW) return s;
        int n = p.breakText(s, true, maxW - p.measureText("..."), null);
        return s.substring(0, Math.max(0, n)).trim() + "...";
    }

    /**
     * Draws text centred in width w; when too wide it scrolls like a marquee.
     * Returns true when it is scrolling (so the caller keeps animating).
     */
    public boolean marquee(Canvas c, Paint p, String s, float x, float w, float baseline, long now, long since) {
        float tw = p.measureText(s);
        if (tw <= w) {
            c.drawText(s, x + (w - tw) / 2f, baseline, p);
            return false;
        }
        float gap = 60;
        float cycle = tw + gap;
        long t = Math.max(0, now - since - 1200);
        float off = (t * 0.05f) % cycle;
        c.save();
        c.clipRect(x, baseline - p.getTextSize() * 1.3f, x + w, baseline + p.getTextSize() * 0.5f);
        c.drawText(s, x - off, baseline, p);
        c.drawText(s, x - off + cycle, baseline, p);
        c.restore();
        return true;
    }

    /** Left-aligned marquee used for long highlighted menu rows. */
    public boolean marqueeLeft(Canvas c, Paint p, String s, float x, float w, float baseline, long now, long since) {
        float tw = p.measureText(s);
        if (tw <= w) {
            c.drawText(s, x, baseline, p);
            return false;
        }
        float cycle = tw + 60;
        long t = Math.max(0, now - since - 1000);
        float off = (t * 0.05f) % cycle;
        c.drawText(s, x - off, baseline, p);
        c.drawText(s, x - off + cycle, baseline, p);
        return true;
    }

    public void roundBox(Canvas c, RectF r, Paint p) {
        c.drawRoundRect(r, 4, 4, p);
    }
}
