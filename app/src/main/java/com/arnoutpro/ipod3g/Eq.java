package com.arnoutpro.ipod3g;

import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;

import java.util.Arrays;

/** The 7-band equalizer: saved band gains, presets and the audio effect on the player's session. */
public final class Eq {
    public static final int BANDS = 7, MAX_DB = 12;
    public static final String[] LABELS = {"60", "150", "400", "1k", "2.4k", "6k", "15k"};
    public static final String[] NAMES = {"60 Hz", "150 Hz", "400 Hz", "1 kHz", "2.4 kHz", "6 kHz", "15 kHz"};
    // Upper edge of each band, halfway (on a log scale) to the next centre.
    private static final float[] CUTOFF = {95, 245, 630, 1550, 3800, 9500, 20000};

    public static final String[] PRESETS = {
            "Off", "Bass Booster", "Treble Booster", "Rock", "Pop", "Vocal", "Spoken Word"};
    private static final int[][] PRESET_GAINS = {
            {0, 0, 0, 0, 0, 0, 0},
            {6, 5, 2, 0, 0, 0, 0},
            {0, 0, 0, 0, 2, 5, 6},
            {5, 3, -1, -2, 1, 3, 5},
            {-1, 1, 3, 4, 2, 0, -1},
            {-2, -1, 1, 4, 4, 2, 0},
            {-4, -2, 1, 4, 3, 1, -3},
    };
    public static final int CUSTOM = -1;

    private final SharedPreferences prefs;
    private final int[] gains = new int[BANDS];
    private int preset;
    private DynamicsProcessing fx;

    Eq(SharedPreferences prefs, int audioSession) {
        this.prefs = prefs;
        preset = prefs.getInt("eqPreset", 0);
        String[] saved = prefs.getString("eqGains", "").split(",");
        for (int i = 0; i < BANDS && saved.length == BANDS; i++) {
            try { gains[i] = Integer.parseInt(saved[i]); } catch (NumberFormatException ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, BANDS, false, 0, false, 0, true)
                        // Catches the peaks a boost would otherwise clip.
                        .setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(
                                true, true, 0, 1f, 60f, 10f, -1f, 0f))
                        .build();
                fx = new DynamicsProcessing(0, audioSession, config);
            } catch (RuntimeException e) {
                fx = null; // the device has no such effect
            }
        }
        apply();
    }

    public boolean supported() { return fx != null; }

    public int gain(int band) { return gains[band]; }

    /** The preset in use, or {@link #CUSTOM} once a band has been changed by hand. */
    public int preset() { return preset; }

    public String label() { return preset == CUSTOM ? "Custom" : PRESETS[preset]; }

    public boolean setGain(int band, int db) {
        db = Math.max(-MAX_DB, Math.min(MAX_DB, db));
        if (db == gains[band]) return false;
        gains[band] = db;
        preset = CUSTOM;
        for (int p = 0; p < PRESET_GAINS.length; p++) {
            if (Arrays.equals(gains, PRESET_GAINS[p])) preset = p;
        }
        save();
        return true;
    }

    public void usePreset(int p) {
        System.arraycopy(PRESET_GAINS[p], 0, gains, 0, BANDS);
        preset = p;
        save();
    }

    private void save() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < BANDS; i++) s.append(i == 0 ? "" : ",").append(gains[i]);
        prefs.edit().putInt("eqPreset", preset).putString("eqGains", s.toString()).apply();
        apply();
    }

    private void apply() {
        if (fx == null || Build.VERSION.SDK_INT < 28) return;
        boolean flat = true;
        for (int i = 0; i < BANDS; i++) {
            fx.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, CUTOFF[i], gains[i]));
            if (gains[i] != 0) flat = false;
        }
        fx.setEnabled(!flat);
    }

    void release() {
        if (fx != null) fx.release();
        fx = null;
    }
}
