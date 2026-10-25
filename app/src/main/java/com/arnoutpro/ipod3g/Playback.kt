package com.arnoutpro.ipod3g

/** What Now Playing and the buttons control: the phone's own music or Spotify. */
interface Playback {
    fun hasTrack(): Boolean

    /** True while the user wants sound, even if the song is still loading. */
    fun isPlayingOrLoading(): Boolean

    /** Changes whenever another song starts, so scrolling titles start over. */
    fun trackKey(): String

    fun title(): String

    fun artist(): String

    fun album(): String

    /** The small line at the top left of Now Playing, e.g. "3 of 12". */
    fun counter(): String

    fun getPosition(): Int

    fun getDuration(): Int

    fun isShuffle(): Boolean

    /** One of [PlayerService.REPEAT_OFF], REPEAT_ONE or REPEAT_ALL. */
    fun repeatMode(): Int

    fun toggle()

    fun next()

    fun previous()

    fun seekTo(ms: Int)

    /** The speaker it plays on when that isn't this phone (e.g. Spotify Connect), else null. */
    fun speaker(): String? = null

    /** That speaker's own volume, 0-100. */
    fun speakerVolume(): Int = 0

    fun setSpeakerVolume(percent: Int) {}
}
