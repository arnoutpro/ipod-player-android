package com.arnoutpro.ipod3g

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.exp

/** The little "tick" the click wheel makes, synthesised so no sound files are needed. */
class Clicker {
    private var track: AudioTrack? = null

    init {
        val n = RATE / 200 // 5 ms
        val pcm = ShortArray(n) { i ->
            val env = exp(-i / 18.0)
            val v = (if (i < 3) 1.0 else if (i % 6 < 3) 0.55 else -0.55) * env
            (v * 9000).toInt().toShort()
        }
        track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build()
                .also { it.write(pcm, 0, n) }
        } catch (e: Exception) {
            null
        }
    }

    fun click() {
        val t = track ?: return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (ignored: IllegalStateException) {
        }
    }

    fun release() {
        track?.release()
        track = null
    }

    private companion object {
        const val RATE = 44100
    }
}
