package com.arnoutpro.ipod3g;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;

import java.util.Locale;

/** "Now Playing": song info, dithered progress bar, wheel for volume, select to scrub. */
public class NowPlayingScreen extends Screen {
    private static final int NORMAL = 0, VOLUME = 1, SCRUB = 2;
    private static final long VOLUME_SHOW_MS = 1800, SCRUB_IDLE_MS = 4000;

    private int mode = NORMAL;
    private long modeTouched;
    private int scrubPos = -1;
    private long trackSince;
    private String lastTrackKey = "";
    private boolean scrolling;
    private final AudioManager audio;

    public NowPlayingScreen(Lcd lcd) {
        super(lcd, "Now Playing");
        audio = (AudioManager) lcd.host.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void shown() {
        mode = NORMAL;
        trackSince = System.currentTimeMillis();
    }

    @Override
    public boolean scroll(int d, long now) {
        Playback ps = lcd.host.playback();
        if (ps == null || !ps.hasTrack()) return false;
        if (mode == SCRUB) {
            int dur = ps.getDuration();
            int step = Math.max(1000, dur / 100);
            if (scrubPos < 0) scrubPos = ps.getPosition();
            scrubPos = Math.max(0, Math.min(dur, scrubPos + d * step));
            ps.seekTo(scrubPos);
            modeTouched = now;
            return true;
        }
        mode = VOLUME;
        modeTouched = now;
        if (ps.speaker() != null) {
            // Playing on another speaker: set its own volume, not the phone's.
            int nv = Math.max(0, Math.min(100, ps.speakerVolume() + d * 5));
            if (nv == ps.speakerVolume()) return false;
            ps.setSpeakerVolume(nv);
            return true;
        }
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int nv = Math.max(0, Math.min(max, vol + d));
        if (nv == vol) return false;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0);
        return true;
    }

    @Override
    public void select() {
        Playback ps = lcd.host.playback();
        if (ps == null || !ps.hasTrack()) return;
        if (mode == SCRUB) {
            mode = NORMAL;
        } else {
            mode = SCRUB;
            scrubPos = ps.getPosition();
            modeTouched = System.currentTimeMillis();
        }
    }

    @Override
    public long frameDelay(long now) { return scrolling || mode != NORMAL ? 33 : 250; }

    @Override
    public void draw(Canvas c, int w, int h, long now) {
        Playback ps = lcd.host.playback();
        Paint text = lcd.text, small = lcd.small;
        scrolling = false;
        if (ps == null || !ps.hasTrack()) {
            String s = "No Song Playing";
            c.drawText(s, (w - text.measureText(s)) / 2f, Lcd.baseline(text, 0, h), text);
            return;
        }
        if (mode == VOLUME && now - modeTouched > VOLUME_SHOW_MS) mode = NORMAL;
        if (mode == SCRUB && now - modeTouched > SCRUB_IDLE_MS) mode = NORMAL;
        if (mode != SCRUB) scrubPos = -1;

        if (!ps.trackKey().equals(lastTrackKey)) {
            lastTrackKey = ps.trackKey();
            trackSince = now;
        }

        c.drawText(Lcd.fit(small, ps.counter(), w - 90), 8, 22, small);
        drawModeIcons(c, ps, w);

        // Laid out for 220 px; extra height goes half above the titles and half above the bar.
        float extra = Math.max(0, h - 220);
        float base1 = 66 + extra / 2f, lh = 30;
        boolean m1 = lcd.marquee(c, text, ps.title(), 8, w - 16, base1, now, trackSince);
        boolean m2 = lcd.marquee(c, text, ps.artist(), 8, w - 16, base1 + lh, now, trackSince);
        boolean m3 = lcd.marquee(c, text, ps.album(), 8, w - 16, base1 + 2 * lh, now, trackSince);
        scrolling = m1 || m2 || m3;

        int dur = Math.max(1, ps.getDuration());
        int at = mode == SCRUB && scrubPos >= 0 ? scrubPos : ps.getPosition();
        float bx = 16, bw = w - 32, by = 150 + extra, bh = 14;

        if (mode == VOLUME) {
            float frac;
            if (ps.speaker() != null) {
                frac = ps.speakerVolume() / 100f;
            } else {
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                frac = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) Math.max(1, max);
            }
            speaker(c, 10, by + bh / 2f, false);
            speaker(c, w - 26, by + bh / 2f, true);
            bar(c, 40, by, w - 80, bh, frac, false);
            return;
        }

        bar(c, bx, by, bw, bh, at / (float) dur, mode == SCRUB);
        float tb = by + bh + 28;
        c.drawText(time(at), bx, tb, text);
        String rem = "-" + time(Math.max(0, dur - at));
        c.drawText(rem, bx + bw - text.measureText(rem), tb, text);
    }

    private void bar(Canvas c, float x, float y, float w, float h, float frac, boolean diamond) {
        frac = Math.max(0, Math.min(1, frac));
        RectF r = new RectF(x, y, x + w, y + h);
        c.drawRect(r, lcd.stroke);
        float fx = x + 2 + (w - 4) * frac;
        if (diamond) {
            Path p = new Path();
            float cy = y + h / 2f, s = h * 0.75f;
            p.moveTo(fx, cy - s);
            p.lineTo(fx + s, cy);
            p.lineTo(fx, cy + s);
            p.lineTo(fx - s, cy);
            p.close();
            c.drawRect(x + 2, y + 2, fx, y + h - 2, lcd.dither);
            Paint bgp = new Paint();
            bgp.setColor(lcd.bg);
            c.drawPath(p, bgp);
            c.drawPath(p, lcd.stroke);
        } else {
            c.drawRect(x + 2, y + 2, fx, y + h - 2, lcd.dither);
            c.drawRect(fx - 2, y + 2, fx, y + h - 2, lcd.ink);
        }
    }

    private void speaker(Canvas c, float x, float cy, boolean loud) {
        Path p = new Path();
        p.moveTo(x, cy - 3);
        p.lineTo(x + 4, cy - 3);
        p.lineTo(x + 9, cy - 8);
        p.lineTo(x + 9, cy + 8);
        p.lineTo(x + 4, cy + 3);
        p.lineTo(x, cy + 3);
        p.close();
        c.drawPath(p, lcd.ink);
        if (loud) {
            c.drawRect(x + 12, cy - 3, x + 14, cy + 3, lcd.ink);
            c.drawRect(x + 16, cy - 6, x + 18, cy + 6, lcd.ink);
        }
    }

    private void drawModeIcons(Canvas c, Playback ps, int w) {
        float x = w - 10;
        if (ps.repeatMode() != PlayerService.REPEAT_OFF) {
            // A small loop; "1" next to it for repeat one.
            x -= 20;
            c.drawRect(x, 8, x + 18, 20, lcd.stroke);
            c.drawRect(x + 12, 5, x + 15, 11, lcd.ink);
            if (ps.repeatMode() == PlayerService.REPEAT_ONE) {
                c.drawText("1", x - 12, 22, lcd.small);
                x -= 12;
            }
            x -= 8;
        }
        if (ps.isShuffle()) {
            x -= 22;
            Paint p = lcd.stroke;
            c.drawLine(x, 8, x + 14, 20, p);
            c.drawLine(x, 20, x + 14, 8, p);
            Path a = new Path();
            a.moveTo(x + 14, 4); a.lineTo(x + 20, 8); a.lineTo(x + 14, 12); a.close();
            Path b = new Path();
            b.moveTo(x + 14, 16); b.lineTo(x + 20, 20); b.lineTo(x + 14, 24); b.close();
            c.drawPath(a, lcd.ink);
            c.drawPath(b, lcd.ink);
        }
    }

    static String time(int ms) {
        int s = ms / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
