package com.arnoutpro.ipod3g

import android.graphics.Canvas
import android.graphics.Paint

/** A short notice in the middle of the LCD that closes by itself, or with MENU. */
class MessageScreen(lcd: Lcd, title: String, private val message: String) : Screen(lcd, title) {
    private var shownAt = 0L

    override fun shown() {
        shownAt = System.currentTimeMillis()
    }

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val text = lcd.text
        val lines = wrap(text, message, w - 24f)
        val lh = text.fontSpacing
        val top = (h - lh * lines.size) / 2f
        lines.forEachIndexed { i, l ->
            c.drawText(l, (w - text.measureText(l)) / 2f, Lcd.baseline(text, top + i * lh, lh), text)
        }
    }

    override fun frameDelay(now: Long): Long {
        val left = shownAt + SHOW_MS - now
        if (left <= 0) {
            if (lcd.top() === this) lcd.pop()
            return -1
        }
        return left
    }

    private companion object {
        const val SHOW_MS = 3000L

        /** Breaks text into lines that fit maxW, at spaces. */
        fun wrap(p: Paint, s: String, maxW: Float): List<String> {
            val lines = ArrayList<String>()
            var line = ""
            for (word in s.split(" ")) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && p.measureText(next) > maxW) {
                    lines.add(line)
                    line = word
                } else {
                    line = next
                }
            }
            if (line.isNotEmpty()) lines.add(line)
            return lines
        }
    }
}
