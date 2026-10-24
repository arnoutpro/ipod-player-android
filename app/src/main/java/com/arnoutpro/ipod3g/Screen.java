package com.arnoutpro.ipod3g;

import android.graphics.Canvas;

/** One page on the iPod display, below the title bar. */
public abstract class Screen {
    protected final Lcd lcd;
    protected String title;

    protected Screen(Lcd lcd, String title) {
        this.lcd = lcd;
        this.title = title;
    }

    public String title() { return title; }

    /** Draws the content area; the canvas origin is its top-left corner. */
    public abstract void draw(Canvas c, int w, int h, long now);

    /** A wheel tick; returns true when something changed (and the clicker should click). */
    public boolean scroll(int delta, long now) { return false; }

    public void select() { }

    public void selectHold() { }

    /** Returns true when MENU was handled here; otherwise the screen is closed. */
    public boolean menu() { return false; }

    /** Called each time the screen comes to the front. */
    public void shown() { }

    /** Milliseconds until the next redraw is needed, or -1 when the screen is static. */
    public long frameDelay(long now) { return -1; }
}
