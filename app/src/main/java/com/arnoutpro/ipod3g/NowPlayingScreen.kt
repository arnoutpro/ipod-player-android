package com.arnoutpro.ipod3g

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.AudioManager
import java.util.Locale

/** "Now Playing": song info, dithered progress bar, wheel for volume, select to scrub. */
class NowPlayingScreen(lcd: Lcd) : Screen(lcd, "Now Playing") {
    private var mode = NORMAL
    private var modeTouched = 0L
    private var scrubPos = -1
    private var trackSince = 0L
    private var lastTrackKey = ""
    private var scrolling = false
    private val audio = lcd.host.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // Reused while drawing, so a frame creates no garbage.
    private val path = Path()
    private val bgPaint = Paint()

    // The 6th gen look: cover art on the left, a blue progress bar.
    private val quad = CoverQuad()
    private val reflFade = Paint()
    private var reflBg = 0
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFFB4B8BE.toInt()
    }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, BAR_H, 0xFF7BBBF8.toInt(), 0xFF1F6FD8.toInt(), Shader.TileMode.CLAMP)
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun shown() {
        mode = NORMAL
        trackSince = System.currentTimeMillis()
    }

    override fun scroll(delta: Int, now: Long): Boolean {
        val ps = lcd.host.playback()
        if (ps == null || !ps.hasTrack()) return false
        if (mode == SCRUB) {
            val dur = ps.getDuration()
            val step = maxOf(1000, dur / 100)
            if (scrubPos < 0) scrubPos = ps.getPosition()
            scrubPos = (scrubPos + delta * step).coerceIn(0, maxOf(0, dur))
            ps.seekTo(scrubPos)
            modeTouched = now
            return true
        }
        mode = VOLUME
        modeTouched = now
        if (ps.speaker() != null) return speakerVolume(ps, delta)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val nv = (vol + delta).coerceIn(0, max)
        if (nv == vol) return false
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
        return true
    }

    /** The phone's volume buttons while playing on a speaker: show the bar and set its volume. */
    fun volumeKey(d: Int) {
        val ps = lcd.host.playback()
        if (ps?.speaker() == null) return
        mode = VOLUME
        modeTouched = System.currentTimeMillis()
        speakerVolume(ps, d)
    }

    override fun select() {
        val ps = lcd.host.playback()
        if (ps == null || !ps.hasTrack()) return
        if (mode == SCRUB) {
            mode = NORMAL
        } else {
            mode = SCRUB
            scrubPos = ps.getPosition()
            modeTouched = System.currentTimeMillis()
        }
    }

    /** Holding the centre button opens Speakers, to pick where the music plays. */
    override fun selectHold() {
        lcd.push(lcd.host.menus().speakers())
    }

    override fun frameDelay(now: Long): Long {
        if (scrolling || mode != NORMAL) return 33
        // Otherwise only the times change: redraw just after the song's next whole second.
        val ps = lcd.host.playback()
        if (ps == null || !ps.isPlayingOrLoading()) return 1000
        return 1000L - ps.getPosition() % 1000 + 20
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val ps = lcd.host.playback()
        val text = lcd.text
        val small = lcd.small
        scrolling = false
        if (ps == null || !ps.hasTrack()) {
            val s = "No Song Playing"
            c.drawText(s, (w - text.measureText(s)) / 2f, Lcd.baseline(text, 0f, h.toFloat()), text)
            return
        }
        if (mode == VOLUME && now - modeTouched > VOLUME_SHOW_MS) mode = NORMAL
        if (mode == SCRUB && now - modeTouched > SCRUB_IDLE_MS) mode = NORMAL
        if (mode != SCRUB) scrubPos = -1

        if (ps.trackKey() != lastTrackKey) {
            lastTrackKey = ps.trackKey()
            trackSince = now
        }

        if (lcd.modern) {
            drawModern(c, ps, w, h, now)
            return
        }

        c.drawText(Lcd.fit(small, ps.counter(), w - 90f), 8f, 22f, small)
        drawModeIcons(c, ps, w)

        // Laid out for 220 px; extra height goes half above the titles and half above the bar.
        val extra = maxOf(0, h - 220).toFloat()
        val base1 = 66 + extra / 2f
        val lh = 30f
        val m1 = lcd.marquee(c, text, ps.title(), 8f, w - 16f, base1, now, trackSince)
        val m2 = lcd.marquee(c, text, ps.artist(), 8f, w - 16f, base1 + lh, now, trackSince)
        val m3 = lcd.marquee(c, text, ps.album(), 8f, w - 16f, base1 + 2 * lh, now, trackSince)
        scrolling = m1 || m2 || m3
        // e.g. "Artist Radio: 10cc", centred just above the bar.
        ps.source()?.let {
            val s = Lcd.fit(small, it, w - 16f)
            c.drawText(s, (w - small.measureText(s)) / 2f, 150 + extra - 12, small)
        }

        val dur = maxOf(1, ps.getDuration())
        val at = if (mode == SCRUB && scrubPos >= 0) scrubPos else ps.getPosition()
        val bx = 16f
        val bw = w - 32f
        val by = 150 + extra
        val bh = 14f

        if (mode == VOLUME) {
            val frac = if (ps.speaker() != null) {
                ps.speakerVolume() / 100f
            } else {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxOf(1, max).toFloat()
            }
            speaker(c, 10f, by + bh / 2f, false)
            speaker(c, w - 26f, by + bh / 2f, true)
            bar(c, 40f, by, w - 80f, bh, frac, false)
            return
        }

        bar(c, bx, by, bw, bh, at / dur.toFloat(), mode == SCRUB)
        val tb = by + bh + 28
        c.drawText(time(at), bx, tb, text)
        val rem = "-" + time(maxOf(0, dur - at))
        c.drawText(rem, bx + bw - text.measureText(rem), tb, text)
    }

    /**
     * The iPod classic's Now Playing: the cover turned slightly away with a faint reflection
     * below it, the song beside it, and the times either side of the bar at the bottom.
     */
    private fun drawModern(c: Canvas, ps: Playback, w: Int, h: Int, now: Long) {
        val text = lcd.text
        drawModeIcons(c, ps, w)

        val cover = lcd.covers.get(ps.artUri()) ?: lcd.covers.placeholder()
        val size = 118f
        val x0 = 16f
        val y0 = 22f
        // The right edge is further away: narrower and shorter.
        val x1 = x0 + size * 0.86f
        quad.draw(c, cover, artPaint, false, x0, y0, x1, y0 + size * TILT, x1, y0 + size * (1 - TILT), x0, y0 + size)

        // The reflection: the cover mirrored below its bottom edge, fading out.
        val reflH = size * 0.32f
        val bottom = y0 + size
        c.save()
        c.clipRect(x0, bottom - size * TILT, x1, bottom + reflH)
        quad.draw(c, cover, artPaint, true, x0, bottom, x1, bottom - size * TILT, x1, bottom + size * (1 - 3 * TILT), x0, bottom + size)
        c.restore()
        if (reflBg != lcd.bg) {
            reflBg = lcd.bg
            reflFade.shader = LinearGradient(
                0f, bottom, 0f, bottom + reflH,
                (lcd.bg and 0xFFFFFF) or 0xA0000000.toInt(), lcd.bg, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(x0, bottom - size * TILT, x1 + 1, bottom + reflH + 1, reflFade)

        val tx = 160f
        val tw = w - tx - 8
        val m1 = line(c, text, ps.title(), tx, tw, y0 + 26, now)
        val m2 = line(c, text, ps.artist(), tx, tw, y0 + 26 + LINE, now)
        val m3 = line(c, text, ps.album(), tx, tw, y0 + 26 + 2 * LINE, now)
        c.drawText(Lcd.fit(text, ps.counter(), tw), tx, y0 + 26 + 3.4f * LINE, text)
        ps.source()?.let { c.drawText(Lcd.fit(text, it, tw), tx, y0 + 26 + 4.4f * LINE, text) }
        scrolling = m1 || m2 || m3

        val by = h - 44f
        val tb = Lcd.baseline(text, by - 6, BAR_H + 12)
        if (mode == VOLUME) {
            speaker(c, 12f, by + BAR_H / 2f, false)
            speaker(c, w - 30f, by + BAR_H / 2f, true)
            modernBar(c, 44f, by, w - 88f, volumeFraction(ps), false)
            return
        }
        val dur = maxOf(1, ps.getDuration())
        val at = if (mode == SCRUB && scrubPos >= 0) scrubPos else ps.getPosition()
        val elapsed = time(at)
        val rem = "-" + time(maxOf(0, dur - at))
        c.drawText(elapsed, 10f, tb, text)
        c.drawText(rem, w - 10 - text.measureText(rem), tb, text)
        modernBar(c, 62f, by, w - 124f, at / dur.toFloat(), mode == SCRUB)
    }

    /** A left-aligned line that scrolls when it doesn't fit; returns true while scrolling. */
    private fun line(c: Canvas, p: Paint, s: String, x: Float, w: Float, base: Float, now: Long): Boolean {
        if (p.measureText(s) <= w) {
            c.drawText(s, x, base, p)
            return false
        }
        c.save()
        c.clipRect(x, base - p.textSize * 1.3f, x + w, base + p.textSize * 0.5f)
        val moving = lcd.marqueeLeft(c, p, s, x, w, base, now, trackSince)
        c.restore()
        return moving
    }

    private fun modernBar(c: Canvas, x: Float, y: Float, w: Float, frac: Float, scrub: Boolean) {
        val r = BAR_H / 2f
        c.drawRoundRect(x, y, x + w, y + BAR_H, r, r, lcd.track)
        val fx = x + w * frac.coerceIn(0f, 1f)
        c.save()
        c.translate(0f, y) // the gradient runs from the bar's top
        if (fx > x) c.drawRoundRect(x, 0f, maxOf(fx, x + BAR_H), BAR_H, r, r, barFill)
        c.restore()
        c.drawRoundRect(x, y, x + w, y + BAR_H, r, r, frame)
        if (scrub) {
            knob.color = Color.WHITE
            c.drawCircle(fx, y + r, r + 3, knob)
            c.drawCircle(fx, y + r, r + 3, frame)
        }
    }

    private fun volumeFraction(ps: Playback): Float = if (ps.speaker() != null) {
        ps.speakerVolume() / 100f
    } else {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxOf(1, max).toFloat()
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, diamond: Boolean) {
        c.drawRect(x, y, x + w, y + h, lcd.stroke)
        val fx = x + 2 + (w - 4) * frac.coerceIn(0f, 1f)
        c.drawRect(x + 2, y + 2, fx, y + h - 2, lcd.dither)
        if (diamond) {
            val p = path
            p.reset()
            val cy = y + h / 2f
            val s = h * 0.75f
            p.moveTo(fx, cy - s)
            p.lineTo(fx + s, cy)
            p.lineTo(fx, cy + s)
            p.lineTo(fx - s, cy)
            p.close()
            bgPaint.color = lcd.bg
            c.drawPath(p, bgPaint)
            c.drawPath(p, lcd.stroke)
        } else {
            c.drawRect(fx - 2, y + 2, fx, y + h - 2, lcd.ink)
        }
    }

    private fun speaker(c: Canvas, x: Float, cy: Float, loud: Boolean) {
        val p = path
        p.reset()
        p.moveTo(x, cy - 3)
        p.lineTo(x + 4, cy - 3)
        p.lineTo(x + 9, cy - 8)
        p.lineTo(x + 9, cy + 8)
        p.lineTo(x + 4, cy + 3)
        p.lineTo(x, cy + 3)
        p.close()
        c.drawPath(p, lcd.ink)
        if (loud) {
            c.drawRect(x + 12, cy - 3, x + 14, cy + 3, lcd.ink)
            c.drawRect(x + 16, cy - 6, x + 18, cy + 6, lcd.ink)
        }
    }

    private fun drawModeIcons(c: Canvas, ps: Playback, w: Int) {
        var x = w - 10f
        if (ps.repeatMode() != PlayerService.REPEAT_OFF) {
            // A small loop; "1" next to it for repeat one.
            x -= 20
            c.drawRect(x, 8f, x + 18, 20f, lcd.stroke)
            c.drawRect(x + 12, 5f, x + 15, 11f, lcd.ink)
            if (ps.repeatMode() == PlayerService.REPEAT_ONE) {
                c.drawText("1", x - 12, 22f, lcd.small)
                x -= 12
            }
            x -= 8
        }
        if (ps.isShuffle()) {
            x -= 22
            c.drawLine(x, 8f, x + 14, 20f, lcd.stroke)
            c.drawLine(x, 20f, x + 14, 8f, lcd.stroke)
            val a = path
            a.reset()
            a.moveTo(x + 14, 4f)
            a.lineTo(x + 20, 8f)
            a.lineTo(x + 14, 12f)
            a.close()
            a.moveTo(x + 14, 16f)
            a.lineTo(x + 20, 20f)
            a.lineTo(x + 14, 24f)
            a.close()
            c.drawPath(a, lcd.ink)
        }
    }

    companion object {
        private const val NORMAL = 0
        private const val VOLUME = 1
        private const val SCRUB = 2
        private const val VOLUME_SHOW_MS = 1800L
        private const val SCRUB_IDLE_MS = 4000L
        private const val BAR_H = 12f
        private const val TILT = 0.05f
        private const val LINE = 22f

        /** Playing on another speaker: set its own volume, not the phone's. */
        fun speakerVolume(ps: Playback, d: Int): Boolean {
            val nv = (ps.speakerVolume() + d * 5).coerceIn(0, 100)
            if (nv == ps.speakerVolume()) return false
            ps.setSpeakerVolume(nv)
            return true
        }

        private fun time(ms: Int): String {
            val s = ms / 1000
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }
}
