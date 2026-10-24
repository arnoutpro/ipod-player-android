package com.arnoutpro.ipod3g;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A classic iPod menu: highlighted row, arrows for sub menus and a scroll bar. */
public class ListScreen extends Screen {

    public static class Item {
        public final String label;
        public boolean arrow;
        public Supplier<String> value;
        public Runnable action;
        public Runnable hold;

        public Item(String label) { this.label = label; }

        public Item arrow() { arrow = true; return this; }
        public Item value(Supplier<String> v) { value = v; return this; }
        public Item onSelect(Runnable r) { action = r; return this; }
        public Item onHold(Runnable r) { hold = r; return this; }
    }

    protected final List<Item> items = new ArrayList<>();
    private Supplier<List<Item>> source;
    private String emptyText = "";
    private int sel, top;
    private long selSince = System.currentTimeMillis();
    private long lastTick;
    private boolean scrolling;

    public ListScreen(Lcd lcd, String title, List<Item> items) {
        super(lcd, title);
        this.items.addAll(items);
    }

    /** A list rebuilt each time it is shown (e.g. the main menu with "Now Playing"). */
    public ListScreen(Lcd lcd, String title, Supplier<List<Item>> source) {
        super(lcd, title);
        this.source = source;
        this.items.addAll(source.get());
    }

    public ListScreen empty(String text) { emptyText = text; return this; }

    @Override
    public void shown() {
        if (source != null) {
            items.clear();
            items.addAll(source.get());
            sel = Math.min(sel, Math.max(0, items.size() - 1));
            clampTop();
        }
    }

    private int rows(int h) { return h / Lcd.ROW_H; }

    private void clampTop() {
        int rows = rows(Lcd.H - Lcd.TITLE_H);
        if (sel < top) top = sel;
        if (sel >= top + rows) top = sel - rows + 1;
        top = Math.max(0, Math.min(top, Math.max(0, items.size() - rows)));
    }

    @Override
    public boolean scroll(int d, long now) {
        if (items.isEmpty()) return false;
        // Spin fast through long lists, like the real wheel's acceleration.
        long dt = now - lastTick;
        lastTick = now;
        int step = 1;
        if (items.size() > 40 && dt < 45) step = items.size() > 400 ? 6 : 3;
        int old = sel;
        sel = Math.max(0, Math.min(items.size() - 1, sel + d * step));
        if (sel == old) return false;
        selSince = now;
        clampTop();
        return true;
    }

    @Override
    public void select() {
        if (sel < items.size() && items.get(sel).action != null) items.get(sel).action.run();
    }

    @Override
    public void selectHold() {
        if (sel < items.size() && items.get(sel).hold != null) items.get(sel).hold.run();
    }

    @Override
    public long frameDelay(long now) { return scrolling ? 33 : -1; }

    @Override
    public void draw(Canvas c, int w, int h, long now) {
        scrolling = false;
        Paint text = lcd.text;
        if (items.isEmpty()) {
            float tw = text.measureText(emptyText);
            c.drawText(emptyText, (w - tw) / 2f, Lcd.baseline(text, 0, h), text);
            return;
        }
        int rows = rows(h);
        boolean bar = items.size() > rows;
        int right = bar ? w - 14 : w;

        for (int r = 0; r < rows; r++) {
            int i = top + r;
            if (i >= items.size()) break;
            Item it = items.get(i);
            int y = r * Lcd.ROW_H;
            boolean hi = i == sel;
            int fg = hi ? lcd.bg : Lcd.INK;
            if (hi) c.drawRect(0, y, right, y + Lcd.ROW_H, lcd.ink);
            text.setColor(fg);

            float valueW = 0;
            String v = it.value != null ? it.value.get() : null;
            if (v != null && !v.isEmpty()) {
                valueW = text.measureText(v) + 14;
                c.drawText(v, right - 10 - text.measureText(v), Lcd.baseline(text, y, Lcd.ROW_H), text);
            }
            float arrowW = it.arrow ? 22 : 0;
            if (it.arrow) {
                Path p = new Path();
                float ax = right - 16, ay = y + Lcd.ROW_H / 2f;
                p.moveTo(ax, ay - 7);
                p.lineTo(ax + 8, ay);
                p.lineTo(ax, ay + 7);
                p.close();
                Paint ap = hi ? bgPaint() : lcd.ink;
                c.drawPath(p, ap);
            }
            float maxW = right - 10 - 8 - arrowW - valueW;
            float base = Lcd.baseline(text, y, Lcd.ROW_H);
            if (hi && text.measureText(it.label) > maxW) {
                c.save();
                c.clipRect(0, y, 8 + maxW, y + Lcd.ROW_H);
                scrolling = lcd.marqueeLeft(c, text, it.label, 8, maxW, base, now, selSince);
                c.restore();
            } else {
                c.drawText(Lcd.fit(text, it.label, maxW), 8, base, text);
            }
            text.setColor(Lcd.INK);
        }

        if (bar) {
            float x = w - 12;
            c.drawRect(x, 1, w - 1, h - 1, lcd.stroke);
            float track = h - 6;
            float knob = Math.max(12, track * rows / items.size());
            float y0 = 3 + (track - knob) * top / Math.max(1, items.size() - rows);
            c.drawRect(x + 2, y0, w - 3, y0 + knob, lcd.ink);
        }
    }

    private final Paint bgPaint = new Paint();

    private Paint bgPaint() {
        bgPaint.setColor(lcd.bg);
        return bgPaint;
    }
}
