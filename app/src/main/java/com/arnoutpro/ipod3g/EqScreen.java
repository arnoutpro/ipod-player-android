package com.arnoutpro.ipod3g;

import android.graphics.Canvas;
import android.graphics.Paint;

/** Settings > EQ > Bands: seven bars; the wheel sets the selected band, the centre moves on. */
public class EqScreen extends Screen {
    private final Paint bgPaint = new Paint(), bgStroke = new Paint();
    private int band = 3;

    public EqScreen(Lcd lcd) {
        super(lcd, "Equalizer");
        bgStroke.setStyle(Paint.Style.STROKE);
        bgStroke.setStrokeWidth(2);
    }

    private Eq eq() {
        PlayerService ps = lcd.host.service();
        return ps == null ? null : ps.eq();
    }

    @Override
    public void draw(Canvas c, int w, int h, long now) {
        Eq eq = eq();
        Paint text = lcd.text, small = lcd.small;
        if (eq == null) return;
        if (!eq.supported()) {
            String s = "Not available";
            c.drawText(s, (w - text.measureText(s)) / 2f, Lcd.baseline(text, 0, h), text);
            return;
        }

        // The selected band and its gain, like the volume bar's readout.
        int g = eq.gain(band);
        c.drawText(Eq.NAMES[band], 10, 26, text);
        String db = (g > 0 ? "+" : "") + g + " dB";
        c.drawText(db, w - 10 - text.measureText(db), 26, text);

        float top = 44, bottom = h - 34, mid = (top + bottom) / 2f;
        float unit = (bottom - top) / 2f / Eq.MAX_DB;
        float colW = (w - 8) / (float) Eq.BANDS;
        bgPaint.setColor(lcd.bg);
        bgStroke.setColor(lcd.bg);

        for (int i = 0; i < Eq.BANDS; i++) {
            float x0 = 4 + i * colW, cx = x0 + colW / 2f;
            boolean hi = i == band;
            if (hi) c.drawRect(x0 + 2, top - 8, x0 + colW - 2, bottom + 8, lcd.ink);
            Paint fill = hi ? bgPaint : lcd.ink;
            float y = mid - eq.gain(i) * unit;
            float bw = colW * 0.16f;
            // The slider's track, filled from 0 dB to the level, with a handle on top.
            c.drawRect(cx - bw, top, cx + bw, bottom, hi ? bgStroke : lcd.stroke);
            c.drawRect(cx - bw, Math.min(mid, y), cx + bw, Math.max(mid, y), fill);
            c.drawRect(cx - bw - 7, mid - 1, cx - bw - 2, mid + 1, fill); // 0 dB tick
            c.drawRect(cx + bw + 2, mid - 1, cx + bw + 7, mid + 1, fill);
            c.drawRect(cx - bw - 5, y - 4, cx + bw + 5, y + 4, fill);

            small.setColor(Lcd.INK);
            String l = Eq.LABELS[i];
            c.drawText(l, cx - small.measureText(l) / 2f, h - 8, small);
        }
    }

    @Override
    public boolean scroll(int d, long now) {
        Eq eq = eq();
        return eq != null && eq.supported() && eq.setGain(band, eq.gain(band) + d);
    }

    @Override
    public void select() { band = (band + 1) % Eq.BANDS; }

    /** Holding the centre button puts the band back to 0 dB. */
    @Override
    public void selectHold() {
        Eq eq = eq();
        if (eq != null) eq.setGain(band, 0);
    }
}
