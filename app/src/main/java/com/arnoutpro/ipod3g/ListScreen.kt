package com.arnoutpro.ipod3g

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.util.function.Supplier

/** A classic iPod menu: highlighted row, arrows for sub menus and a scroll bar. */
open class ListScreen private constructor(
    lcd: Lcd,
    title: String,
    first: List<Item>,
    private val source: Supplier<List<Item>>?,
) : Screen(lcd, title) {

    class Item(@JvmField val label: String) {
        @JvmField var arrow = false
        @JvmField var value: Supplier<String?>? = null
        @JvmField var action: Runnable? = null
        @JvmField var hold: Runnable? = null

        fun arrow() = apply { arrow = true }

        fun value(v: Supplier<String?>) = apply { value = v }

        fun onSelect(r: Runnable) = apply { action = r }

        fun onHold(r: Runnable) = apply { hold = r }
    }

    constructor(lcd: Lcd, title: String, items: List<Item>) : this(lcd, title, items, null)

    /** A list rebuilt each time it is shown (e.g. the main menu with "Now Playing"). */
    constructor(lcd: Lcd, title: String, source: Supplier<List<Item>>) : this(lcd, title, source.get(), source)

    @JvmField protected val items = ArrayList(first)
    private var emptyText = ""
    private var sel = 0
    private var top = 0
    private var selSince = System.currentTimeMillis()
    private var lastTick = 0L
    private var scrolling = false
    private val bgPaint = Paint()
    private val arrow = Path()

    fun empty(text: String) = apply { emptyText = text }

    /** Replaces the rows, e.g. once a list loaded in the background arrives. */
    fun setItems(list: List<Item>) {
        items.clear()
        items.addAll(list)
        sel = minOf(sel, maxOf(0, items.size - 1))
        clampTop()
    }

    override fun shown() {
        source?.let { setItems(it.get()) }
    }

    private fun rows(h: Int) = h / Lcd.ROW_H

    private fun clampTop() {
        val rows = rows(Lcd.H - Lcd.TITLE_H)
        if (sel < top) top = sel
        if (sel >= top + rows) top = sel - rows + 1
        top = top.coerceIn(0, maxOf(0, items.size - rows))
    }

    override fun scroll(delta: Int, now: Long): Boolean {
        if (items.isEmpty()) return false
        // Spin fast through long lists, like the real wheel's acceleration.
        val dt = now - lastTick
        lastTick = now
        var step = 1
        if (items.size > 40 && dt < 45) step = if (items.size > 400) 6 else 3
        val old = sel
        sel = (sel + delta * step).coerceIn(0, items.size - 1)
        if (sel == old) return false
        selSince = now
        clampTop()
        return true
    }

    override fun select() {
        items.getOrNull(sel)?.action?.run()
    }

    override fun selectHold() {
        items.getOrNull(sel)?.hold?.run()
    }

    override fun frameDelay(now: Long): Long = if (scrolling) 33 else -1

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        scrolling = false
        val text = lcd.text
        if (items.isEmpty()) {
            val tw = text.measureText(emptyText)
            c.drawText(emptyText, (w - tw) / 2f, Lcd.baseline(text, 0f, h.toFloat()), text)
            return
        }
        val rows = rows(h)
        val bar = items.size > rows
        val right = if (bar) w - 14 else w

        for (r in 0 until rows) {
            val i = top + r
            if (i >= items.size) break
            val it = items[i]
            val y = r * Lcd.ROW_H
            val hi = i == sel
            if (hi) c.drawRect(0f, y.toFloat(), right.toFloat(), (y + Lcd.ROW_H).toFloat(), lcd.ink)
            text.color = if (hi) lcd.bg else Lcd.INK
            val base = Lcd.baseline(text, y.toFloat(), Lcd.ROW_H.toFloat())

            val arrowW = if (it.arrow) 22f else 0f
            var valueW = 0f
            val v = it.value?.get()
            if (!v.isNullOrEmpty()) {
                valueW = text.measureText(v) + 14
                // Ends just before the arrow when there is one.
                val end = if (it.arrow) right - 24 else right - 10
                c.drawText(v, end - text.measureText(v), base, text)
            }
            if (it.arrow) {
                arrow.reset()
                val ax = right - 16f
                val ay = y + Lcd.ROW_H / 2f
                arrow.moveTo(ax, ay - 7)
                arrow.lineTo(ax + 8, ay)
                arrow.lineTo(ax, ay + 7)
                arrow.close()
                c.drawPath(arrow, if (hi) bgPaint.apply { color = lcd.bg } else lcd.ink)
            }
            val maxW = right - 10 - 8 - arrowW - valueW
            if (hi && text.measureText(it.label) > maxW) {
                c.save()
                c.clipRect(0f, y.toFloat(), 8 + maxW, (y + Lcd.ROW_H).toFloat())
                scrolling = lcd.marqueeLeft(c, text, it.label, 8f, maxW, base, now, selSince)
                c.restore()
            } else {
                c.drawText(Lcd.fit(text, it.label, maxW), 8f, base, text)
            }
            text.color = Lcd.INK
        }

        if (bar) {
            val x = w - 12f
            c.drawRect(x, 1f, w - 1f, h - 1f, lcd.stroke)
            val track = h - 6f
            val knob = maxOf(12f, track * rows / items.size)
            val y0 = 3 + (track - knob) * top / maxOf(1, items.size - rows)
            c.drawRect(x + 2, y0, w - 3f, y0 + knob, lcd.ink)
        }
    }
}
