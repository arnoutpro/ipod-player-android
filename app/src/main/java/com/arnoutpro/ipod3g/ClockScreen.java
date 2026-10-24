package com.arnoutpro.ipod3g;

import android.graphics.Canvas;
import android.text.format.DateFormat;

import java.util.Date;

/** Extras > Clock. */
public class ClockScreen extends Screen {
    public ClockScreen(Lcd lcd) { super(lcd, "Clock"); }

    @Override
    public void draw(Canvas c, int w, int h, long now) {
        Date d = new Date(now);
        boolean h24 = DateFormat.is24HourFormat(lcd.host);
        String time = DateFormat.format(h24 ? "H:mm" : "h:mm", d).toString();
        String sub = h24 ? "" : DateFormat.format("a", d).toString();
        String date = DateFormat.format("EEE d MMM yyyy", d).toString();
        float tw = lcd.big.measureText(time);
        float sw = sub.isEmpty() ? 0 : lcd.text.measureText(sub) + 8;
        float x = (w - tw - sw) / 2f;
        float dy = Math.max(0, h - 220) / 2f; // laid out for 220 px, kept centred
        c.drawText(time, x, 110 + dy, lcd.big);
        if (!sub.isEmpty()) c.drawText(sub, x + tw + 8, 110 + dy, lcd.text);
        c.drawRect(30, 130 + dy, w - 30, 132 + dy, lcd.ink);
        c.drawText(date, (w - lcd.text.measureText(date)) / 2f, 166 + dy, lcd.text);
    }

    @Override
    public long frameDelay(long now) { return 1000 - now % 1000; }
}
