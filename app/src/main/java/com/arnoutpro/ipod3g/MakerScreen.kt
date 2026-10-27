package com.arnoutpro.ipod3g

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.net.Uri

/**
 * Settings > About > Made by: the arnout.pro A brand mark in LCD ink, with the maker's name and
 * the version. The centre button opens the maker's GitHub page.
 */
class MakerScreen(lcd: Lcd, private val version: String) : Screen(lcd, "arnout.pro") {

    private val mark: Bitmap = BitmapFactory.decodeResource(lcd.host.resources, R.drawable.arnout_mark)

    // The mask is white; this paints it in the LCD's ink instead.
    private val markPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Lcd.INK, PorterDuff.Mode.SRC_IN)
    }
    private val dst = RectF()

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        val text = lcd.text
        val small = lcd.small

        // The mark fills most of the screen above two lines of text.
        val textBlock = text.fontSpacing + small.fontSpacing + 14
        val markH = (h - textBlock - 24).coerceAtMost(170f)
        val markW = markH * mark.width / mark.height
        val top = 14f
        dst.set((w - markW) / 2f, top, (w + markW) / 2f, top + markH)
        c.drawBitmap(mark, null, dst, markPaint)

        val y = dst.bottom + 10
        center(c, text, "@arnoutpro", w, Lcd.baseline(text, y, text.fontSpacing))
        center(c, small, "iPod Player Android $version", w,
            Lcd.baseline(small, y + text.fontSpacing, small.fontSpacing))
    }

    private fun center(c: Canvas, p: Paint, s: String, w: Int, baseline: Float) {
        c.drawText(s, (w - p.measureText(s)) / 2f, baseline, p)
    }

    override fun select() {
        lcd.host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/arnoutpro")))
    }
}
