package com.arnoutpro.ipod3g;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/** A short notice in the middle of the LCD that closes by itself, or with MENU. */
public class MessageScreen extends Screen {
    private static final long SHOW_MS = 3000;

    private final String message;
    private long shownAt;

    public MessageScreen(Lcd lcd, String title, String message) {
        super(lcd, title);
        this.message = message;
    }

    @Override
    public void shown() { shownAt = System.currentTimeMillis(); }

    @Override
    public void draw(Canvas c, int w, int h, long now) {
        Paint text = lcd.text;
        List<String> lines = wrap(text, message, w - 24);
        float lh = text.getFontSpacing();
        float top = (h - lh * lines.size()) / 2f;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            c.drawText(l, (w - text.measureText(l)) / 2f, Lcd.baseline(text, top + i * lh, lh), text);
        }
    }

    @Override
    public long frameDelay(long now) {
        long left = shownAt + SHOW_MS - now;
        if (left <= 0) {
            if (lcd.top() == this) lcd.pop();
            return -1;
        }
        return left;
    }

    /** Breaks text into lines that fit maxW, at spaces. */
    private static List<String> wrap(Paint p, String s, float maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && p.measureText(next) > maxW) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
