package com.arnoutpro.ipod3g;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.io.IOException;

/** A Chromecast, Google TV or Nest speaker, through a Cast session. */
final class CastSpeaker implements RemoteSpeaker {
    final CastSession session;
    private final RemoteMediaClient client;
    private final Listener listener;
    private boolean finishHandled;

    private final RemoteMediaClient.Callback callback = new RemoteMediaClient.Callback() {
        @Override public void onStatusUpdated() {
            MediaStatus st = client.getMediaStatus();
            if (st != null && st.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE
                    && st.getIdleReason() == MediaStatus.IDLE_REASON_FINISHED && !finishHandled) {
                finishHandled = true;
                listener.onFinished();
            }
            listener.onChanged();
        }
    };

    CastSpeaker(CastSession session, Listener listener) {
        this.session = session;
        this.client = session.getRemoteMediaClient();
        this.listener = listener;
        client.registerCallback(callback);
    }

    @Override public String name() {
        return session.getCastDevice() != null ? session.getCastDevice().getFriendlyName() : "Chromecast";
    }

    @Override public void load(String url, String type, Track t, int positionMs, boolean play) {
        MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
        md.putString(MediaMetadata.KEY_TITLE, t.title);
        md.putString(MediaMetadata.KEY_ARTIST, t.artist);
        md.putString(MediaMetadata.KEY_ALBUM_TITLE, t.album);
        MediaInfo info = new MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(type)
                .setStreamDuration(t.durationMs)
                .setMetadata(md)
                .build();
        finishHandled = false;
        client.load(new MediaLoadRequestData.Builder()
                .setMediaInfo(info).setAutoplay(play).setCurrentTime(positionMs).build());
    }

    @Override public void play() { client.play(); }

    @Override public void pause() { client.pause(); }

    @Override public void seek(int ms) { client.seek(new MediaSeekOptions.Builder().setPosition(ms).build()); }

    @Override public boolean isPlaying() { return client.isPlaying(); }

    @Override public boolean idle() {
        MediaStatus st = client.getMediaStatus();
        return st == null || st.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE;
    }

    @Override public int position() { return (int) client.getApproximateStreamPosition(); }

    @Override public int duration() { return (int) client.getStreamDuration(); }

    @Override public int volume() { return (int) Math.round(session.getVolume() * 100); }

    @Override public void setVolume(int percent) {
        try {
            session.setVolume(percent / 100.0);
        } catch (IOException | IllegalStateException ignored) { }
    }

    /** Ending the session itself is up to the Cast framework (the player calls endCurrentSession). */
    @Override public void release(boolean stop) {
        client.unregisterCallback(callback);
    }
}
