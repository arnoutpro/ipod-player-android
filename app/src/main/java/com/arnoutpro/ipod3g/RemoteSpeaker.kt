package com.arnoutpro.ipod3g

/**
 * A speaker on the network that plays the phone's own music, fetching each song from
 * [MusicServer]: a Chromecast or a Sonos group.
 */
interface RemoteSpeaker {
    interface Listener {
        /** Something worth redrawing changed. */
        fun onChanged()

        /** The song played to the end. */
        fun onFinished()
    }

    fun name(): String

    /** Loads a song from url and starts it at positionMs, playing or paused. */
    fun load(url: String, type: String, t: Track, positionMs: Int, play: Boolean)

    fun play()

    fun pause()

    fun seek(ms: Int)

    fun isPlaying(): Boolean

    /** Nothing loaded, e.g. after the last song ended. */
    fun idle(): Boolean

    fun position(): Int

    fun duration(): Int

    /** 0-100. */
    fun volume(): Int

    fun setVolume(percent: Int)

    /** Stops listening; stop also stops the music on the speaker. */
    fun release(stop: Boolean)
}
