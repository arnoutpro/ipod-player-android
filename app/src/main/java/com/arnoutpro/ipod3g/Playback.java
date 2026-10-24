package com.arnoutpro.ipod3g;

/** What Now Playing and the buttons control: the phone's own music or Spotify. */
public interface Playback {
    boolean hasTrack();

    /** True while the user wants sound, even if the song is still loading. */
    boolean isPlayingOrLoading();

    /** Changes whenever another song starts, so scrolling titles start over. */
    String trackKey();

    String title();

    String artist();

    String album();

    /** The small line at the top left of Now Playing, e.g. "3 of 12". */
    String counter();

    int getPosition();

    int getDuration();

    boolean isShuffle();

    /** One of {@link PlayerService#REPEAT_OFF}, REPEAT_ONE or REPEAT_ALL. */
    int repeatMode();

    void toggle();

    void next();

    void previous();

    void seekTo(int ms);

    /** The speaker it plays on when that isn't this phone (e.g. Spotify Connect), else null. */
    default String speaker() { return null; }

    /** That speaker's own volume, 0-100. */
    default int speakerVolume() { return 0; }

    default void setSpeakerVolume(int percent) { }
}
