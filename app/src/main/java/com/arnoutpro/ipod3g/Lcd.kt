package com.arnoutpro.ipod3g

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface

/**
 * The 3rd generation iPod display: renders a low resolution screen (twice the original
 * 160x128) without anti-aliasing, so it keeps the chunky pixel look when scaled up.
 */
class Lcd(@JvmField val host: MainActivity) {
    @JvmField val bitmap: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    @JvmField val text = Paint()
    @JvmField val small = Paint()
    @JvmField val big = Paint()
    @JvmField val ink = Paint()
    @JvmField val stroke = Paint()
    @JvmField val dither = Paint()

    private val stack = ArrayList<Screen>()
    private var leaving: Screen? = null
    private var slideDir = 0
    private var slideStart = 0L

    @JvmField var bg = BG_LIT
    private val playIcon = Path()

    init {
        val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        for (p in arrayOf(text, small, big)) {
            p.isAntiAlias = false
            p.isSubpixelText = false
            p.typeface = bold
            p.color = INK
        }
        text.textSize = 21f
        small.textSize = 17f
        big.textSize = 64f
        ink.isAntiAlias = false
        ink.color = INK
        stroke.isAntiAlias = false
        stroke.color = INK
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 2f

        // The 3G progress bar is filled with a checkerboard dither.
        val chk = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        for (y in 0 until 4) {
            for (x in 0 until 4) chk.setPixel(x, y, if ((x / 2 + y / 2) % 2 == 0) INK else 0)
        }
        dither.shader = BitmapShader(chk, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    // ---- navigation ------------------------------------------------------------------------

    fun top(): Screen? = stack.lastOrNull()

    fun push(s: Screen) {
        leaving = top()
        slideDir = 1
        slideStart = System.currentTimeMillis()
        stack.add(s)
        s.shown()
        host.redraw()
    }

    fun pop() {
        if (stack.size <= 1) return
        leaving = stack.removeAt(stack.size - 1)
        slideDir = -1
        slideStart = System.currentTimeMillis()
        top()?.shown()
        host.redraw()
    }

    /** Replaces the whole stack, without animation. */
    fun reset(root: Screen) {
        stack.clear()
        leaving = null
        stack.add(root)
        root.shown()
        host.redraw()
    }

    fun atRoot() = stack.size <= 1

    /** Shows Now Playing, reusing an open one instead of stacking another. */
    fun showNowPlaying() {
        val i = stack.indexOfLast { it is NowPlayingScreen }
        if (i < 0) {
            push(NowPlayingScreen(this))
            return
        }
        if (i == stack.size - 1) return
        while (stack.size > i + 1) stack.removeAt(stack.size - 1)
        leaving = null
        top()?.shown()
        host.redraw()
    }

    // ---- input -----------------------------------------------------------------------------

    fun scroll(d: Int, now: Long) = top()?.scroll(d, now) ?: false

    fun select() {
        top()?.select()
    }

    fun selectHold() {
        top()?.selectHold()
    }

    /** Returns false when already at the main menu. */
    fun menu(): Boolean {
        val s = top() ?: return false
        if (s.menu()) return true
        if (atRoot()) return false
        pop()
        return true
    }

    // ---- rendering -------------------------------------------------------------------------

    /** Renders into [bitmap]; returns the delay until the next frame, or -1. */
    fun render(lit: Boolean, now: Long): Long {
        bg = if (lit) BG_LIT else BG_DARK
        val c = canvas
        c.drawColor(bg)
        val s = top() ?: return -1

        var delay = s.frameDelay(now)
        val ch = H - TITLE_H
        var p = 1f
        var out = leaving
        if (out != null) {
            p = minOf(1f, (now - slideStart) / SLIDE_MS.toFloat())
            if (p >= 1f) {
                leaving = null
                out = null
            } else {
                delay = 16
            }
        }
        drawTitleBar(c, if (out != null && p < 0.5f) out.title() else s.title())

        c.save()
        c.clipRect(0, TITLE_H, W, H)
        c.translate(0f, TITLE_H.toFloat())
        if (out != null) {
            val e = 1f - (1f - p) * (1f - p)
            val off = e * W * slideDir
            c.save()
            c.translate(-off, 0f)
            out.draw(c, W, ch, now)
            c.restore()
            c.translate(W * slideDir - off, 0f)
        }
        s.draw(c, W, ch, now)
        c.restore()
        return delay
    }

    private fun drawTitleBar(c: Canvas, title: String) {
        val ps = host.playback()
        // Play state indicator on the left.
        if (ps != null && ps.hasTrack()) {
            if (ps.isPlayingOrLoading()) {
                val tri = playIcon
                tri.reset()
                tri.moveTo(8f, 10f)
                tri.lineTo(20f, 17f)
                tri.lineTo(8f, 24f)
                tri.close()
                c.drawPath(tri, ink)
            } else {
                c.drawRect(8f, 10f, 12f, 24f, ink)
                c.drawRect(16f, 10f, 20f, 24f, ink)
            }
        }
        // Battery on the right.
        val level = host.batteryLevel()
        val bx = W - 44f
        val by = 10f
        val bw = 32f
        val bh = 15f
        c.drawRect(bx, by, bx + bw, by + bh, stroke)
        c.drawRect(bx + bw + 1, by + 4, bx + bw + 4, by + bh - 4, ink)
        val fill = (bw - 6) * level.coerceIn(0, 100) / 100f
        c.drawRect(bx + 3, by + 3, bx + 3 + fill, by + bh - 3, if (host.isCharging()) dither else ink)
        if (host.isCharging()) c.drawRect(bx + 3, by + 3, bx + 3 + fill, by + bh - 3, stroke)

        val t = fit(text, title, W - 120f)
        val tw = text.measureText(t)
        c.drawText(t, (W - tw) / 2f, baseline(text, 0f, TITLE_H - 2f), text)
        c.drawRect(0f, TITLE_H - 3f, W.toFloat(), TITLE_H - 1f, ink)
    }

    // ---- text helpers ----------------------------------------------------------------------

    /**
     * Draws text centred in width w; when too wide it scrolls like a marquee.
     * Returns true when it is scrolling (so the caller keeps animating).
     */
    fun marquee(c: Canvas, p: Paint, s: String, x: Float, w: Float, baseline: Float, now: Long, since: Long): Boolean {
        val tw = p.measureText(s)
        if (tw <= w) {
            c.drawText(s, x + (w - tw) / 2f, baseline, p)
            return false
        }
        val cycle = tw + 60
        val t = maxOf(0, now - since - 1200)
        val off = (t * 0.05f) % cycle
        c.save()
        c.clipRect(x, baseline - p.textSize * 1.3f, x + w, baseline + p.textSize * 0.5f)
        c.drawText(s, x - off, baseline, p)
        c.drawText(s, x - off + cycle, baseline, p)
        c.restore()
        return true
    }

    /** Left-aligned marquee used for long highlighted menu rows. */
    fun marqueeLeft(c: Canvas, p: Paint, s: String, x: Float, w: Float, baseline: Float, now: Long, since: Long): Boolean {
        val tw = p.measureText(s)
        if (tw <= w) {
            c.drawText(s, x, baseline, p)
            return false
        }
        val cycle = tw + 60
        val t = maxOf(0, now - since - 1000)
        val off = (t * 0.05f) % cycle
        c.drawText(s, x - off, baseline, p)
        c.drawText(s, x - off + cycle, baseline, p)
        return true
    }

    companion object {
        const val W = 320 // title bar and seven rows
        const val H = 292
        const val TITLE_H = 36
        const val ROW_H = 36
        private const val SLIDE_MS = 170L

        const val BG_LIT = 0xFFCAD7D9.toInt()
        const val BG_DARK = 0xFF9FA69C.toInt()
        const val INK = 0xFF1E2226.toInt()

        /** Baseline that vertically centres the paint's text in [top, top+height]. */
        @JvmStatic
        fun baseline(p: Paint, top: Float, height: Float): Float {
            val fm = p.fontMetrics
            return top + (height - (fm.descent - fm.ascent)) / 2f - fm.ascent
        }

        /** Shortens text with an ellipsis so it fits in maxW. */
        @JvmStatic
        fun fit(p: Paint, s: String, maxW: Float): String {
            if (p.measureText(s) <= maxW) return s
            val n = p.breakText(s, true, maxW - p.measureText("..."), null)
            return s.substring(0, maxOf(0, n)).trim() + "..."
        }
    }
}
