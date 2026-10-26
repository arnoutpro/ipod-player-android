package com.arnoutpro.ipod3g

import android.graphics.Canvas
import android.graphics.Color
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

    class Item(val label: String) {
        var arrow = false
        var value: Supplier<String?>? = null
        var action: Runnable? = null
        var hold: Runnable? = null

        fun arrow() = apply { arrow = true }

        fun value(v: Supplier<String?>) = apply { value = v }

        fun onSelect(r: Runnable) = apply { action = r }

        fun onHold(r: Runnable) = apply { hold = r }
    }

    constructor(lcd: Lcd, title: String, items: List<Item>) : this(lcd, title, items, null)

    /** A list rebuilt each time it is shown (e.g. the main menu with "Now Playing"). */
    constructor(lcd: Lcd, title: String, source: Supplier<List<Item>>) : this(lcd, title, source.get(), source)

    protected val items = ArrayList(first)

    private var emptyText = ""
    private var sel = 0
    private var top = 0
    private var selSince = System.currentTimeMillis()
    private var lastTick = 0L
    private var scrolling = false
    private val bgPaint = Paint()
    private val arrow = Path()
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }

    fun empty(text: String) = apply { emptyText = text }

    fun selected(): Item? = items.getOrNull(sel)

    /** True when a row shows a value (e.g. Settings), which needs the full width. */
    fun hasValues() = items.any { it.value != null }

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

    private fun rows(h: Int) = h / lcd.rowH

    private fun clampTop() {
        val rows = rows(Lcd.H - lcd.titleH)
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

        val rowH = lcd.rowH
        val chevronColumn = lcd.modern && items.any { it.arrow }
        for (r in 0 until rows) {
            val i = top + r
            if (i >= items.size) break
            val it = items[i]
            val y = r * rowH
            val hi = i == sel
            val fill = hi
            if (lcd.modern) {
                if (hi) {
                    // The gradient runs over one row, so draw it from the row's top.
                    c.save()
                    c.translate(0f, y.toFloat())
                    c.drawRect(0f, 0f, right.toFloat(), rowH.toFloat(), lcd.select)
                    c.restore()
                }
                text.color = if (fill) Color.WHITE else Lcd.INK
            } else {
                if (fill) c.drawRect(0f, y.toFloat(), right.toFloat(), (y + rowH).toFloat(), lcd.ink)
                text.color = if (fill) lcd.bg else Lcd.INK
            }
            val base = Lcd.baseline(text, y.toFloat(), rowH.toFloat())

            // The 6th gen look shows the chevron only on the highlighted row, so every row keeps
            // its column free and the values line up.
            val arrowW = if (it.arrow || chevronColumn) 22f else 0f
            var valueW = 0f
            val v = it.value?.get()
            if (!v.isNullOrEmpty()) {
                valueW = text.measureText(v) + 14
                // Ends just before the arrow when there is one.
                val end = if (it.arrow || chevronColumn) right - 24 else right - 10
                c.drawText(v, end - text.measureText(v), base, text)
            }
            if (it.arrow && lcd.modern) {
                // The classic only shows the chevron on the highlighted row.
                if (hi) {
                    val ax = right - 14f
                    val ay = y + rowH / 2f
                    c.drawLine(ax, ay - 5, ax + 5, ay, chevron)
                    c.drawLine(ax + 5, ay, ax, ay + 5, chevron)
                }
            } else if (it.arrow) {
                arrow.reset()
                val ax = right - 16f
                val ay = y + rowH / 2f
                arrow.moveTo(ax, ay - 7)
                arrow.lineTo(ax + 8, ay)
                arrow.lineTo(ax, ay + 7)
                arrow.close()
                c.drawPath(arrow, if (fill) bgPaint.apply { color = lcd.bg } else lcd.ink)
            }
            val maxW = right - 10 - 8 - arrowW - valueW
            if (fill && text.measureText(it.label) > maxW) {
                c.save()
                c.clipRect(0f, y.toFloat(), 8 + maxW, (y + rowH).toFloat())
                scrolling = lcd.marqueeLeft(c, text, it.label, 8f, maxW, base, now, selSince)
                c.restore()
            } else {
                c.drawText(Lcd.fit(text, it.label, maxW), 8f, base, text)
            }
            text.color = Lcd.INK
        }

        if (bar && lcd.modern) {
            // A slim grey bar with a rounded knob, like the classic's.
            val x = w - 9f
            c.drawRect(x, 0f, w.toFloat(), h.toFloat(), lcd.track)
            val knob = maxOf(14f, h * rows.toFloat() / items.size)
            val y0 = (h - knob) * top / maxOf(1, items.size - rows)
            c.drawRoundRect(x + 1.5f, y0 + 1.5f, w - 1.5f, y0 + knob - 1.5f, 3f, 3f, lcd.knob)
        } else if (bar) {
            val x = w - 12f
            c.drawRect(x, 1f, w - 1f, h - 1f, lcd.stroke)
            val track = h - 6f
            val knob = maxOf(12f, track * rows / items.size)
            val y0 = 3 + (track - knob) * top / maxOf(1, items.size - rows)
            c.drawRect(x + 2, y0, w - 3f, y0 + knob, lcd.ink)
        }
    }
}
