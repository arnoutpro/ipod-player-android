package com.arnoutpro.ipod3g

import android.graphics.Canvas
import android.graphics.Paint

/** Settings > EQ > Bands: seven bars; the wheel sets the selected band, the centre moves on. */
class EqScreen(lcd: Lcd) : Screen(lcd, "Equalizer") {
    private val bgPaint = Paint()
    private val bgStroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private var band = 3

    private fun eq(): Eq? = lcd.host.service()?.eq()

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val eq = eq() ?: return
        val text = lcd.text
        val small = lcd.small
        if (!eq.supported()) {
            val s = "Not available"
            c.drawText(s, (w - text.measureText(s)) / 2f, Lcd.baseline(text, 0f, h.toFloat()), text)
            return
        }

        // The selected band and its gain, like the volume bar's readout.
        val g = eq.gain(band)
        c.drawText(Eq.NAMES[band], 10f, 26f, text)
        val db = (if (g > 0) "+" else "") + g + " dB"
        c.drawText(db, w - 10 - text.measureText(db), 26f, text)

        val top = 44f
        val bottom = h - 34f
        val mid = (top + bottom) / 2f
        val unit = (bottom - top) / 2f / Eq.MAX_DB
        val colW = (w - 8) / Eq.BANDS.toFloat()
        bgPaint.color = lcd.bg
        bgStroke.color = lcd.bg

        for (i in 0 until Eq.BANDS) {
            val x0 = 4 + i * colW
            val cx = x0 + colW / 2f
            val hi = i == band
            if (hi) c.drawRect(x0 + 2, top - 8, x0 + colW - 2, bottom + 8, lcd.ink)
            val fill = if (hi) bgPaint else lcd.ink
            val y = mid - eq.gain(i) * unit
            val bw = colW * 0.16f
            // The slider's track, filled from 0 dB to the level, with a handle on top.
            c.drawRect(cx - bw, top, cx + bw, bottom, if (hi) bgStroke else lcd.stroke)
            c.drawRect(cx - bw, minOf(mid, y), cx + bw, maxOf(mid, y), fill)
            c.drawRect(cx - bw - 7, mid - 1, cx - bw - 2, mid + 1, fill) // 0 dB tick
            c.drawRect(cx + bw + 2, mid - 1, cx + bw + 7, mid + 1, fill)
            c.drawRect(cx - bw - 5, y - 4, cx + bw + 5, y + 4, fill)

            small.color = Lcd.INK
            val l = Eq.LABELS[i]
            c.drawText(l, cx - small.measureText(l) / 2f, h - 8f, small)
        }
    }

    override fun scroll(delta: Int, now: Long): Boolean {
        val eq = eq() ?: return false
        return eq.supported() && eq.setGain(band, eq.gain(band) + delta)
    }

    override fun select() {
        band = (band + 1) % Eq.BANDS
    }

    /** Holding the centre button puts the band back to 0 dB. */
    override fun selectHold() {
        eq()?.setGain(band, 0)
    }
}
