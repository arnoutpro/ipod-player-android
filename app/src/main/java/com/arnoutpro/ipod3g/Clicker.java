package com.arnoutpro.ipod3g;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** The little "tick" the click wheel makes, synthesised so no sound files are needed. */
public final class Clicker {
    private static final int RATE = 44100;
    private AudioTrack track;

    public Clicker() {
        int n = RATE / 200; // 5 ms
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            double env = Math.exp(-i / 18.0);
            double v = (i < 3 ? 1 : (i % 6 < 3 ? 0.55 : -0.55)) * env;
            pcm[i] = (short) (v * 9000);
        }
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(n * 2)
                    .build();
            track.write(pcm, 0, n);
        } catch (Exception e) {
            track = null;
        }
    }

    public void click() {
        if (track == null) return;
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop();
            track.reloadStaticData();
            track.play();
        } catch (IllegalStateException ignored) { }
    }

    public void release() {
        if (track != null) track.release();
        track = null;
    }
}
