package com.arnoutpro.ipod3g

import android.graphics.Canvas
import android.text.format.DateFormat
import java.util.Date

/** Extras > Clock. */
class ClockScreen(lcd: Lcd) : Screen(lcd, "Clock") {

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val d = Date(now)
        val h24 = DateFormat.is24HourFormat(lcd.host)
        val time = DateFormat.format(if (h24) "H:mm" else "h:mm", d).toString()
        val sub = if (h24) "" else DateFormat.format("a", d).toString()
        val date = DateFormat.format("EEE d MMM yyyy", d).toString()
        val tw = lcd.big.measureText(time)
        val sw = if (sub.isEmpty()) 0f else lcd.text.measureText(sub) + 8
        val x = (w - tw - sw) / 2f
        val dy = maxOf(0, h - 220) / 2f // laid out for 220 px, kept centred
        c.drawText(time, x, 110 + dy, lcd.big)
        if (sub.isNotEmpty()) c.drawText(sub, x + tw + 8, 110 + dy, lcd.text)
        c.drawRect(30f, 130 + dy, w - 30f, 132 + dy, lcd.ink)
        c.drawText(date, (w - lcd.text.measureText(date)) / 2f, 166 + dy, lcd.text)
    }

    override fun frameDelay(now: Long): Long = 1000 - now % 1000
}
