package com.arnoutpro.ipod3g

import android.content.SharedPreferences
import android.media.audiofx.DynamicsProcessing
import android.os.Build

/** The 7-band equalizer: saved band gains, presets and the audio effect on the player's session. */
class Eq(private val prefs: SharedPreferences, audioSession: Int) {
    private val gains = IntArray(BANDS)
    private var preset = prefs.getInt("eqPreset", 0)
    private var fx: DynamicsProcessing? = null

    init {
        val saved = prefs.getString("eqGains", "")!!.split(",")
        if (saved.size == BANDS) for (i in 0 until BANDS) saved[i].toIntOrNull()?.let { gains[i] = it }
        if (Build.VERSION.SDK_INT >= 28) {
            fx = try {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, BANDS, false, 0, false, 0, true,
                )
                    // Catches the peaks a boost would otherwise clip.
                    .setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
                    .build()
                DynamicsProcessing(0, audioSession, config)
            } catch (e: RuntimeException) {
                null // the device has no such effect
            }
        }
        apply()
    }

    fun supported() = fx != null

    fun gain(band: Int) = gains[band]

    /** The preset in use, or [CUSTOM] once a band has been changed by hand. */
    fun preset() = preset

    fun label(): String = if (preset == CUSTOM) "Custom" else PRESETS[preset]

    fun setGain(band: Int, db: Int): Boolean {
        val v = db.coerceIn(-MAX_DB, MAX_DB)
        if (v == gains[band]) return false
        gains[band] = v
        preset = PRESET_GAINS.indexOfFirst { it.contentEquals(gains) }.takeIf { it >= 0 } ?: CUSTOM
        save()
        return true
    }

    fun usePreset(p: Int) {
        PRESET_GAINS[p].copyInto(gains)
        preset = p
        save()
    }

    private fun save() {
        prefs.edit().putInt("eqPreset", preset).putString("eqGains", gains.joinToString(",")).apply()
        apply()
    }

    private fun apply() {
        val f = fx ?: return
        if (Build.VERSION.SDK_INT < 28) return
        for (i in 0 until BANDS) f.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, CUTOFF[i], gains[i].toFloat()))
        f.enabled = gains.any { it != 0 }
    }

    fun release() {
        fx?.release()
        fx = null
    }

    companion object {
        const val BANDS = 7
        const val MAX_DB = 12
        const val CUSTOM = -1

        @JvmField val LABELS = arrayOf("60", "150", "400", "1k", "2.4k", "6k", "15k")
        @JvmField val NAMES = arrayOf("60 Hz", "150 Hz", "400 Hz", "1 kHz", "2.4 kHz", "6 kHz", "15 kHz")
        @JvmField val PRESETS = arrayOf("Off", "Bass Booster", "Treble Booster", "Rock", "Pop", "Vocal", "Spoken Word")

        // Upper edge of each band, halfway (on a log scale) to the next centre.
        private val CUTOFF = floatArrayOf(95f, 245f, 630f, 1550f, 3800f, 9500f, 20000f)

        private val PRESET_GAINS = arrayOf(
            intArrayOf(0, 0, 0, 0, 0, 0, 0),
            intArrayOf(6, 5, 2, 0, 0, 0, 0),
            intArrayOf(0, 0, 0, 0, 2, 5, 6),
            intArrayOf(5, 3, -1, -2, 1, 3, 5),
            intArrayOf(-1, 1, 3, 4, 2, 0, -1),
            intArrayOf(-2, -1, 1, 4, 4, 2, 0),
            intArrayOf(-4, -2, 1, 4, 3, 1, -3),
        )
    }
}
