package com.arnoutpro.ipod3g

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.abs

/**
 * Cover Flow, as on the iPod classic: the albums' covers in a row on black, the chosen one facing
 * you and the others turned aside, all with a reflection. The wheel flips through them and the
 * centre button opens the album.
 */
class CoverFlowScreen(lcd: Lcd, private val source: () -> List<Album>) : Screen(lcd, "Cover Flow") {

    /** One album: its name, artist, where its cover comes from, and the list it opens. */
    class Album(val name: String, val artist: String, val art: String?, val open: () -> Screen)

    override val dark get() = true

    private var albums: List<Album> = emptyList()
    private var sel = 0
    /** Where the row is: slides towards [sel] instead of jumping. */
    private var pos = 0f
    private var lastFrame = 0L

    private val quad = CoverQuad()
    private val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val reflPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = 90 }
    private val fade = Paint()
    private var fadeTop = -1f
    private val name = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grey = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun shown() {
        name.set(lcd.text)
        name.color = WHITE
        grey.set(lcd.text)
        grey.color = GREY
        albums = source()
        sel = sel.coerceIn(0, maxOf(0, albums.size - 1))
        pos = sel.toFloat()
    }

    override fun scroll(delta: Int, now: Long): Boolean {
        val old = sel
        sel = (sel + delta).coerceIn(0, maxOf(0, albums.size - 1))
        return sel != old
    }

    override fun select() {
        albums.getOrNull(sel)?.let { lcd.push(it.open()) }
    }

    override fun frameDelay(now: Long): Long = if (abs(pos - sel) > 0.001f) 16 else -1

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        c.drawColor(BLACK)
        if (albums.isEmpty()) {
            val s = "No Albums"
            c.drawText(s, (w - name.measureText(s)) / 2f, Lcd.baseline(name, 0f, h.toFloat()), name)
            return
        }

        // Ease towards the chosen album, quicker the further it is.
        val dt = (now - lastFrame).coerceIn(0, 100)
        lastFrame = now
        pos += (sel - pos) * minOf(1f, dt / 90f)
        if (abs(pos - sel) < 0.002f) pos = sel.toFloat()

        val size = minOf(w * 0.48f, h - 92f)
        val cy = 14 + size / 2
        val from = maxOf(0, (pos - SIDE).toInt())
        val to = minOf(albums.size - 1, (pos + SIDE).toInt() + 1)

        // Reflections first, faded into the black, then the covers: furthest first, so the one
        // in front ends up on top.
        val order = (from..to).sortedByDescending { abs(it - pos) }
        for (i in order) drawCover(c, i, w, cy, size, true)
        val bottom = cy + size / 2
        if (fadeTop != bottom) {
            fadeTop = bottom
            fade.shader = LinearGradient(0f, bottom - size * 0.2f, 0f, bottom + size * 0.35f, 0, BLACK, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, bottom - size * 0.2f, w.toFloat(), h.toFloat(), fade)
        for (i in order) drawCover(c, i, w, cy, size, false)

        val a = albums[sel]
        val y = bottom + size * 0.3f
        centred(c, name, a.name, w, y)
        centred(c, grey, a.artist, w, y + 22)
    }

    private fun centred(c: Canvas, p: Paint, s: String, w: Int, top: Float) {
        val t = Lcd.fit(p, s, w - 20f)
        c.drawText(t, (w - p.measureText(t)) / 2f, Lcd.baseline(p, top, 24f), p)
    }

    /**
     * One cover where it stands now: facing you in the middle, turned aside further out. Between
     * the two (while sliding) everything in between is blended, so it turns smoothly.
     */
    private fun drawCover(c: Canvas, i: Int, w: Int, cy: Float, size: Float, reflection: Boolean) {
        val d = i - pos
        val t = minOf(1f, abs(d)) // 0 facing you, 1 turned aside
        val side = if (d < 0) -1 else 1
        val width = size * (1 - 0.6f * t)
        val cx = w / 2f + side * (t * (size * 0.5f + 12) + maxOf(0f, abs(d) - 1) * STEP)
        val near = size * (1 - 0.06f * t) // the edge towards the middle
        val far = size * (1 - 0.26f * t)
        val left = cx - width / 2
        val right = cx + width / 2
        val hl = if (side > 0) near else far
        val hr = if (side > 0) far else near
        if (right < 0 || left > w) return

        val a = albums[i]
        val b = lcd.covers.get(a.art) ?: lcd.covers.placeholder()
        if (reflection) {
            quad.draw(
                c, b, reflPaint, true,
                left, cy + hl / 2, right, cy + hr / 2,
                right, cy + hr / 2 + hr, left, cy + hl / 2 + hl,
            )
            return
        }
        quad.draw(c, b, coverPaint, false, left, cy - hl / 2, right, cy - hr / 2, right, cy + hr / 2, left, cy + hl / 2)
    }

    private companion object {
        /** How many covers show on each side. */
        const val SIDE = 5
        /** Space between the covers turned aside. */
        const val STEP = 26f
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF9A9FA6.toInt()
    }
}
