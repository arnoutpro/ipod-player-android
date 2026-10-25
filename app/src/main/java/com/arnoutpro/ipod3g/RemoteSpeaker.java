package com.arnoutpro.ipod3g;

/**
 * A speaker on the network that plays the phone's own music, fetching each song from
 * {@link MusicServer}: a Chromecast or a Sonos group.
 */
public interface RemoteSpeaker {
    interface Listener {
        /** Something worth redrawing changed. */
        void onChanged();

        /** The song played to the end. */
        void onFinished();
    }

    String name();

    /** Loads a song from url and starts it at positionMs, playing or paused. */
    void load(String url, String type, Track t, int positionMs, boolean play);

    void play();

    void pause();

    void seek(int ms);

    boolean isPlaying();

    /** Nothing loaded, e.g. after the last song ended. */
    boolean idle();

    int position();

    int duration();

    /** 0-100. */
    int volume();

    void setVolume(int percent);

    /** Stops listening; stop also stops the music on the speaker. */
    void release(boolean stop);
}
