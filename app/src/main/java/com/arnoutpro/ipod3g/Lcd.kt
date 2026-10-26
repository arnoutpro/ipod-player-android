package com.arnoutpro.ipod3g

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * The 3rd generation iPod display: renders a low resolution screen (twice the original
 * 160x128) without anti-aliasing, so it keeps the chunky pixel look when scaled up.
 */
class Lcd(@JvmField val host: MainActivity) {
    private val lo: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val loCanvas = Canvas(lo)

    // The 6th gen look is drawn at three times the size, so its smooth text stays sharp.
    private val hi: Bitmap = Bitmap.createBitmap(W * HI, H * HI, Bitmap.Config.ARGB_8888)
    private val hiCanvas = Canvas(hi).apply { scale(HI.toFloat(), HI.toFloat()) }

    /** True for the iPod classic (6th/7th gen) look: split menus, colour and smooth text. */
    var modern = false
        private set

    val bitmap: Bitmap get() = if (modern) hi else lo

    /** The title bar and menu rows: the classic's are slimmer. */
    val titleH: Int get() = if (modern) MODERN_TITLE_H else TITLE_H
    val rowH: Int get() = if (modern) MODERN_ROW_H else ROW_H

    @JvmField val text = Paint()
    @JvmField val small = Paint()
    @JvmField val big = Paint()
    @JvmField val ink = Paint()
    @JvmField val stroke = Paint()
    @JvmField val dither = Paint()
    @JvmField val covers = Covers(host)

    // Paints of the 6th gen look.
    @JvmField val select = Paint()
    @JvmField val selectDim = Paint()
    @JvmField val track = Paint()
    @JvmField val knob = Paint()
    @JvmField val line = Paint()
    private val titleText = Paint()
    private val titleBg = Paint()
    private val battery = Paint()
    private val playBlue = Paint()
    private val shadow = Paint()
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    // The art beside the 6th gen menus.
    /** Milliseconds until the art beside the menu needs redrawing, or -1 when it isn't shown. */
    private var previewDelay = -1L
    private var previewKey: String? = null
    private var previewSince = 0L
    private var previewArt: Bitmap? = null
    private var previewOld: Bitmap? = null
    private var previewArtSince = 0L
    private val previewRect = RectF()
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val stack = ArrayList<Screen>()
    private var leaving: Screen? = null
    private var slideDir = 0
    private var slideStart = 0L

    @JvmField var bg = BG_LIT
    private val playIcon = Path()

    init {
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

        // The classic's glossy blue highlight; the list you came from keeps a grey one.
        select.shader = LinearGradient(0f, 0f, 0f, MODERN_ROW_H.toFloat(), 0xFF6DB3F7.toInt(), 0xFF1F6FD8.toInt(), Shader.TileMode.CLAMP)
        selectDim.shader = LinearGradient(0f, 0f, 0f, MODERN_ROW_H.toFloat(), 0xFFDCDEE1.toInt(), 0xFFAEB2B8.toInt(), Shader.TileMode.CLAMP)
        titleBg.shader = LinearGradient(0f, 0f, 0f, MODERN_TITLE_H.toFloat(), 0xFFFCFCFC.toInt(), 0xFFC9CDD2.toInt(), Shader.TileMode.CLAMP)
        battery.shader = LinearGradient(0f, 6f, 0f, 16f, 0xFFB4F08C.toInt(), 0xFF3DAA1E.toInt(), Shader.TileMode.CLAMP)
        playBlue.shader = LinearGradient(0f, 6f, 0f, 16f, 0xFF8CC8FF.toInt(), 0xFF2A7BE0.toInt(), Shader.TileMode.CLAMP)
        track.color = 0xFFE6E7E9.toInt()
        knob.color = 0xFF9DA2A9.toInt()
        line.color = 0xFF9EA3AA.toInt()
        for (p in arrayOf(select, selectDim, titleBg, battery, playBlue, track, knob, line)) p.isAntiAlias = true
        shadow.shader = LinearGradient(0f, 0f, SHADOW_W, 0f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        titleText.isAntiAlias = true
        titleText.typeface = bold
        titleText.textSize = TITLE_FONT
        titleText.color = INK
    }

    /** Switches between the 3rd gen and the 6th gen look. */
    fun theme(m: Boolean) {
        if (m == modern) return
        modern = m
        for (p in arrayOf(text, small, big, ink, stroke)) {
            p.isAntiAlias = m
            p.isSubpixelText = m
        }
        // The 6th gen look uses one size everywhere.
        text.textSize = if (m) FONT else 21f
        small.textSize = if (m) FONT else 17f
        stroke.strokeWidth = if (m) 1.5f else 2f
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
        theme(host.splitMenus())
        bg = if (modern) (if (lit) MODERN_LIT else MODERN_DARK) else if (lit) BG_LIT else BG_DARK
        val c = if (modern) hiCanvas else loCanvas
        c.drawColor(bg)
        val s = top() ?: return -1

        var delay = s.frameDelay(now)
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
        c.clipRect(0, titleH, W, H)
        c.translate(0f, titleH.toFloat())
        previewDelay = -1
        if (out != null) {
            val e = 1f - (1f - p) * (1f - p)
            val off = e * W * slideDir
            c.save()
            c.translate(-off, 0f)
            drawPage(c, out, if (slideDir > 0) stack.size - 2 else stack.size, now)
            c.restore()
            c.translate(W * slideDir - off, 0f)
        }
        drawPage(c, s, stack.size - 1, now)
        c.restore()
        if (previewDelay >= 0 && (delay < 0 || delay > previewDelay)) delay = previewDelay
        return delay
    }

    /**
     * One page. In the 6th gen look the main menu and the menus right below it show the list on
     * the left half and slowly drifting album art on the right, like the iPod classic.
     */
    private fun drawPage(c: Canvas, sc: Screen, depth: Int, now: Long) {
        val ch = H - titleH
        if (!modern || sc !is ListScreen || depth > 1 || sc.hasValues()) {
            sc.draw(c, W, ch, now)
            return
        }
        val half = W / 2
        c.save()
        c.clipRect(0, 0, half, ch)
        sc.draw(c, half, ch, now)
        c.restore()
        c.save()
        c.clipRect(half, 0, W, ch)
        c.translate(half.toFloat(), 0f)
        drawPreview(c, W - half, ch, now, sc)
        // The menu casts a soft shadow over the art.
        c.drawRect(0f, 0f, SHADOW_W, ch.toFloat(), shadow)
        c.restore()
    }

    /**
     * Album art, changing every few seconds with a fade: random covers from your
     * own songs and your Spotify Liked Songs, or the playing song's while "Now Playing" is highlighted.
     */
    private fun drawPreview(c: Canvas, w: Int, h: Int, now: Long, list: ListScreen) {
        val ps = host.playback()
        val playing = list.selected()?.label == "Now Playing" && ps != null && ps.hasTrack()
        val songs = host.library().songs()
        val liked = host.spotifyLibrary().get().liked
        if (playing) {
            if (previewKey != ps!!.artUri()) {
                previewKey = ps.artUri()
                previewSince = now
            }
        } else if (songs.size + liked.size > 0 &&
            (previewKey == null || now - previewSince > PREVIEW_MS || covers.isMissing(previewKey))
        ) {
            val i = (Math.random() * (songs.size + liked.size)).toInt()
            previewKey = if (i < songs.size) songs[i].uri.toString() else liked[i - songs.size].image
            previewSince = now
        }
        val bmp = covers.get(previewKey)
        if (bmp != null && bmp !== previewArt) {
            previewOld = previewArt
            previewArt = bmp
            previewArtSince = now
        }
        val art = previewArt
        val fade = if (art == null) 1f else minOf(1f, (now - previewArtSince) / FADE_MS.toFloat())
        // Redraw for the fade, while a cover is still being picked, or when the next one is due.
        previewDelay = when {
            fade < 1f -> FADE_FRAME_MS
            playing -> -1
            previewKey == null || covers.isMissing(previewKey) -> FADE_FRAME_MS
            else -> maxOf(FADE_FRAME_MS, PREVIEW_MS - (now - previewSince))
        }
        if (art == null) {
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), selectDim)
            return
        }
        previewOld?.let { if (fade < 1f) cover(c, it, w, h, 255) }
        cover(c, art, w, h, (fade * 255).toInt())
        if (fade >= 1f) previewOld = null
    }

    /** Draws a cover filling the pane's height, centred (its sides are cut off). */
    private fun cover(c: Canvas, b: Bitmap, w: Int, h: Int, alpha: Int) {
        val size = maxOf(w, h).toFloat()
        val x = (w - size) / 2f
        val y = (h - size) / 2f
        previewRect.set(x, y, x + size, y + size)
        previewPaint.alpha = alpha
        c.drawBitmap(b, null, previewRect, previewPaint)
    }

    /** The classic's slim silver bar: the title on the left, play state and battery on the right. */
    private fun drawModernTitle(c: Canvas, title: String) {
        val th = MODERN_TITLE_H.toFloat()
        c.drawRect(0f, 0f, W.toFloat(), th, titleBg)
        c.drawRect(0f, th - 1, W.toFloat(), th, line)

        val bx = W - 30f
        val by = 6f
        c.drawRoundRect(bx, by, bx + 22, by + 10, 2f, 2f, battery)
        c.drawRoundRect(bx, by, bx + 22, by + 10, 2f, 2f, stroke)
        c.drawRect(bx + 22, by + 3, bx + 24, by + 7, stroke)
        val level = host.batteryLevel().coerceIn(0, 100)
        if (level < 100) {
            // The empty part of the battery.
            val fx = bx + 1 + 20 * level / 100f
            track.color = 0xFFF2F2F2.toInt()
            c.drawRect(fx, by + 1, bx + 21, by + 9, track)
            track.color = 0xFFE6E7E9.toInt()
        }

        val ps = host.playback()
        if (ps != null && ps.hasTrack()) {
            val x = bx - 18
            if (ps.isPlayingOrLoading()) {
                playIcon.reset()
                playIcon.moveTo(x, by)
                playIcon.lineTo(x + 10, by + 5)
                playIcon.lineTo(x, by + 10)
                playIcon.close()
                c.drawPath(playIcon, playBlue)
            } else {
                c.drawRect(x, by, x + 3.5f, by + 10, playBlue)
                c.drawRect(x + 6, by, x + 9.5f, by + 10, playBlue)
            }
        }
        c.drawText(fit(titleText, title, W - 70f), 8f, baseline(titleText, 0f, th), titleText)
    }

    private fun drawTitleBar(c: Canvas, title: String) {
        if (modern) {
            drawModernTitle(c, title)
            return
        }
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
        private const val HI = 3
        private const val MODERN_LIT = 0xFFFFFFFF.toInt()
        private const val MODERN_DARK = 0xFFB9BCC0.toInt()
        private const val FONT = 16f // bold, like the classic's Helvetica
        private const val TITLE_FONT = 14f
        private const val MODERN_TITLE_H = 22
        private const val MODERN_ROW_H = 27
        private const val PREVIEW_MS = 6000L
        private const val FADE_MS = 600L
        private const val FADE_FRAME_MS = 40L
        private const val SHADOW_W = 10f

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
