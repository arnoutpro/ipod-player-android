package com.arnoutpro.ipod3g

import android.graphics.Canvas

/** One page on the iPod display, below the title bar. */
abstract class Screen protected constructor(
    protected val lcd: Lcd,
    protected var title: String,
) {
    fun title(): String = title

    /** Draws the content area; the canvas origin is its top-left corner. */
    abstract fun draw(c: Canvas, w: Int, h: Int, now: Long)

    /** A wheel tick; returns true when something changed (and the clicker should click). */
    open fun scroll(delta: Int, now: Long): Boolean = false

    open fun select() {}

    open fun selectHold() {}

    /** Returns true when MENU was handled here; otherwise the screen is closed. */
    open fun menu(): Boolean = false

    /** Called each time the screen comes to the front. */
    open fun shown() {}

    /** Milliseconds until the next redraw is needed, or -1 when the screen is static. */
    open fun frameDelay(now: Long): Long = -1
}
