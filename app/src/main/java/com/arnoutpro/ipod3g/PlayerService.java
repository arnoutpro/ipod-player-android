package com.arnoutpro.ipod3g;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Plays the queue in the background and exposes it to the lock screen and headsets. */
public class PlayerService extends Service implements Playback {
    public static final int REPEAT_OFF = 0, REPEAT_ONE = 1, REPEAT_ALL = 2;

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 3;
    private static final String ACTION_TOGGLE = "com.arnoutpro.ipod3g.TOGGLE";
    private static final String ACTION_NEXT = "com.arnoutpro.ipod3g.NEXT";
    private static final String ACTION_PREV = "com.arnoutpro.ipod3g.PREV";

    public interface Listener { void onPlayerChanged(); }

    public class LocalBinder extends Binder {
        public PlayerService service() { return PlayerService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    private MediaPlayer player;
    private boolean prepared;
    private boolean wantPlay;
    private int consecutiveErrors;

    private List<Track> queue = new ArrayList<>();
    private int[] order = new int[0]; // play order -> queue index
    private int pos = -1;             // index into order

    private boolean shuffle;
    private int repeat;

    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean resumeOnFocusGain;
    private boolean foreground;
    private boolean noisyRegistered;
    private SharedPreferences prefs;
    // One audio session for every track, so the equalizer stays attached between songs.
    private int audioSession;
    private Eq eq;

    // Casting to a Chromecast or Sonos: songs play there instead, fetched from the phone by the server.
    private CastContext cast;
    private RemoteSpeaker remote;
    private MusicServer server;
    private int pendingSeek;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(); }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnFocusGain = isPlaying();
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null) player.setVolume(0.25f, 0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (player != null) player.setVolume(1f, 1f);
                if (resumeOnFocusGain) play();
                resumeOnFocusGain = false;
                break;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("ipod", MODE_PRIVATE);
        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", REPEAT_OFF);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioSession = audio.generateAudioSessionId();
        eq = new Eq(prefs, audioSession);
        server = new MusicServer(getContentResolver());
        try {
            cast = CastContext.getSharedInstance(this);
            cast.getSessionManager().addSessionManagerListener(castListener, CastSession.class);
            CastSession running = cast.getSessionManager().getCurrentCastSession();
            if (running != null && running.isConnected()) startCast(running);
        } catch (RuntimeException e) {
            cast = null; // no Google Play services: no casting
        }
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(musicAttributes())
                .setOnAudioFocusChangeListener(focusListener, main)
                .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "iPod");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long ms) { seekTo((int) ms); }
        });
        session.setSessionActivity(openAppIntent());
        session.setActive(true);
        updateSession();
    }

    private static AudioAttributes musicAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_TOGGLE: toggle(); break;
                case ACTION_NEXT: next(); break;
                case ACTION_PREV: previous(); break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying()) stopSelf();
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        eq.release();
        if (cast != null) cast.getSessionManager().removeSessionManagerListener(castListener, CastSession.class);
        if (remote != null) remote.release(false);
        server.stop();
        abandonFocus();
        setNoisy(false);
        session.release();
        super.onDestroy();
    }

    // ---- listeners -------------------------------------------------------------------------

    public Eq eq() { return eq; }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        updateSession();
        updateNotification();
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerChanged();
    }

    // ---- queue -----------------------------------------------------------------------------

    /** Replaces the queue and starts playing {@code start} (or a random song if -1 and shuffling). */
    public void playQueue(List<Track> tracks, int start) {
        if (tracks.isEmpty()) return;
        queue = new ArrayList<>(tracks);
        if (start < 0) start = new Random().nextInt(queue.size());
        pos = buildOrder(start);
        consecutiveErrors = 0;
        startTrack(true);
    }

    /** "Shuffle Songs" from the main menu: everything, shuffled, regardless of the setting. */
    public void shuffleAll(List<Track> tracks) {
        if (tracks.isEmpty()) return;
        boolean old = shuffle;
        shuffle = true;
        playQueue(tracks, -1);
        shuffle = old;
    }

    /** Builds the play order with queue index {@code first} playing now; returns its position. */
    private int buildOrder(int first) {
        int n = queue.size();
        order = new int[n];
        if (!shuffle) {
            for (int i = 0; i < n; i++) order[i] = i;
            return first;
        }
        List<Integer> rest = new ArrayList<>();
        for (int i = 0; i < n; i++) if (i != first) rest.add(i);
        Collections.shuffle(rest);
        order[0] = first;
        for (int i = 1; i < n; i++) order[i] = rest.get(i - 1);
        return 0;
    }

    public Track current() {
        if (pos < 0 || pos >= order.length) return null;
        return queue.get(order[pos]);
    }

    public int positionInQueue() { return pos; }

    public int queueSize() { return queue.size(); }

    @Override public boolean hasTrack() { return current() != null; }

    @Override public String trackKey() { Track t = current(); return t == null ? "" : String.valueOf(t.id); }

    @Override public String title() { Track t = current(); return t == null ? "" : t.title; }

    @Override public String artist() { Track t = current(); return t == null ? "" : t.artist; }

    @Override public String album() { Track t = current(); return t == null ? "" : t.album; }

    @Override public String counter() {
        String n = (pos + 1) + " of " + queue.size();
        return casting() ? n + " on " + speaker() : n;
    }

    // ---- casting ---------------------------------------------------------------------------

    private boolean casting() { return remote != null; }

    @Override public String speaker() { return remote == null ? null : remote.name(); }

    @Override public int speakerVolume() { return remote == null ? 0 : remote.volume(); }

    @Override public void setSpeakerVolume(int percent) {
        if (remote == null) return;
        remote.setVolume(Math.max(0, Math.min(100, percent)));
        changed();
    }

    /** Stops casting and carries on on this phone, paused where the speaker was. */
    public void stopCasting() {
        if (remote instanceof CastSpeaker) {
            cast.getSessionManager().endCurrentSession(true); // castListener finishes up
        } else if (remote != null) {
            endRemote(true);
        }
    }

    /** Plays the phone's own music on this Sonos group. */
    public void castToSonos(Sonos.Group g) {
        if (remote instanceof SonosSpeaker && ((SonosSpeaker) remote).group.equals(g)) return;
        RemoteSpeaker old = remote;
        startRemote(new SonosSpeaker(g, remoteListener));
        if (old instanceof CastSpeaker) cast.getSessionManager().endCurrentSession(true);
    }

    /** True while this Sonos group is the speaker. */
    public boolean castingTo(Sonos.Group g) {
        return remote instanceof SonosSpeaker && ((SonosSpeaker) remote).group.equals(g);
    }

    private final SessionManagerListener<CastSession> castListener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarted(CastSession s, String id) { startCast(s); }
        @Override public void onSessionResumed(CastSession s, boolean wasSuspended) { startCast(s); }
        @Override public void onSessionEnding(CastSession s) { castEnding(s); }
        @Override public void onSessionEnded(CastSession s, int error) { castEnded(s); }
        @Override public void onSessionSuspended(CastSession s, int reason) { castEnded(s); }
        @Override public void onSessionStarting(CastSession s) { }
        @Override public void onSessionStartFailed(CastSession s, int error) { }
        @Override public void onSessionResuming(CastSession s, String id) { }
        @Override public void onSessionResumeFailed(CastSession s, int error) { }
    };

    private final RemoteSpeaker.Listener remoteListener = new RemoteSpeaker.Listener() {
        @Override public void onChanged() { changed(); }

        @Override public void onFinished() {
            if (repeat == REPEAT_ONE) loadRemote(0, true);
            else {
                wantPlay = true;
                next();
            }
        }
    };

    private void startCast(CastSession s) {
        if (remote instanceof CastSpeaker && ((CastSpeaker) remote).session == s) return;
        if (s.getRemoteMediaClient() == null) return;
        startRemote(new CastSpeaker(s, remoteListener));
    }

    /** A speaker was picked: move the song there, at the same spot, playing or paused. */
    private void startRemote(RemoteSpeaker r) {
        int at = getPosition();
        boolean play = wantPlay;
        if (remote != null) remote.release(true);
        releasePlayer();
        remote = r;
        if (hasTrack()) loadRemote(at, play);
        setNoisy(false);
        changed();
    }

    private int castLeftAt;

    private void castEnding(CastSession s) {
        if (remote instanceof CastSpeaker && ((CastSpeaker) remote).session == s) castLeftAt = remote.position();
    }

    private void castEnded(CastSession s) {
        // Ignore a session that ended because another speaker took over.
        if (!(remote instanceof CastSpeaker) || ((CastSpeaker) remote).session != s) return;
        int at = castLeftAt;
        remote.release(false);
        remote = null;
        resumeHere(at);
    }

    /** Leaves the speaker and carries on on this phone from where it was, paused. */
    private void endRemote(boolean stop) {
        int at = remote.position();
        remote.release(stop);
        remote = null;
        resumeHere(at);
    }

    private void resumeHere(int at) {
        if (hasTrack()) {
            startTrack(false);
            pendingSeek = at;
        }
        changed();
    }

    private void loadRemote(int positionMs, boolean play) {
        Track t = current();
        if (t == null || remote == null) return;
        try {
            String url = server.publish(t);
            wantPlay = play;
            remote.load(url, server.type(), t, positionMs, play);
            if (play) goForeground(); // keeps the server running in the background
        } catch (IOException e) {
            stopCasting(); // no Wi-Fi: play here instead
        }
    }

    public boolean isShuffle() { return shuffle; }

    public int repeatMode() { return repeat; }

    public void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        prefs.edit().putBoolean("shuffle", on).apply();
        if (hasTrack()) pos = buildOrder(order[pos]);
        changed();
    }

    public void setRepeat(int mode) {
        repeat = mode;
        prefs.edit().putInt("repeat", mode).apply();
        changed();
    }

    // ---- transport -------------------------------------------------------------------------

    public boolean isPlaying() {
        if (casting()) return remote.isPlaying();
        return player != null && prepared && player.isPlaying();
    }

    /** True while the user wants sound, even if the track is still loading. */
    public boolean isPlayingOrLoading() { return wantPlay && hasTrack(); }

    public void play() {
        if (!hasTrack()) return;
        if (casting()) {
            wantPlay = true;
            if (remote.idle()) loadRemote(0, true);
            else remote.play();
            goForeground();
            changed();
            return;
        }
        if (!requestFocus()) return;
        wantPlay = true;
        if (player != null && prepared) player.start();
        else if (player == null) startTrack(true);
        goForeground();
        setNoisy(true);
        changed();
    }

    public void pause() {
        wantPlay = false;
        if (casting()) remote.pause();
        if (player != null && prepared && player.isPlaying()) player.pause();
        setNoisy(false);
        leaveForeground();
        changed();
    }

    public void toggle() {
        if (isPlayingOrLoading()) pause(); else play();
    }

    public void next() {
        if (!hasTrack()) return;
        if (pos + 1 < order.length) pos++;
        else if (repeat == REPEAT_ALL) pos = 0;
        else { stopAtEnd(); return; }
        startTrack(wantPlay);
    }

    public void previous() {
        if (!hasTrack()) return;
        if (getPosition() > 3000 || pos == 0 && repeat != REPEAT_ALL) {
            seekTo(0);
            return;
        }
        pos = pos > 0 ? pos - 1 : order.length - 1;
        startTrack(wantPlay);
    }

    public void seekTo(int ms) {
        if (casting()) {
            remote.seek(Math.max(0, Math.min(ms, getDuration())));
            changed();
            return;
        }
        if (player != null && prepared) {
            player.seekTo(Math.max(0, Math.min(ms, getDuration())));
            changed();
        }
    }

    public int getPosition() {
        if (casting()) return remote.position();
        return player != null && prepared ? player.getCurrentPosition() : 0;
    }

    public int getDuration() {
        if (casting() && remote.duration() > 0) return remote.duration();
        if (player != null && prepared) return player.getDuration();
        Track t = current();
        return t == null ? 0 : (int) t.durationMs;
    }

    private void stopAtEnd() {
        // Like the real thing: stop and rewind to the first song of the list.
        pos = 0;
        wantPlay = false;
        releasePlayer();
        setNoisy(false);
        leaveForeground();
        changed();
    }

    private void startTrack(boolean autoPlay) {
        releasePlayer();
        Track t = current();
        if (t == null) return;
        wantPlay = autoPlay;
        bumpPlayCount(t);
        if (casting()) {
            loadRemote(0, autoPlay);
            changed();
            return;
        }
        if (autoPlay && !requestFocus()) wantPlay = false;

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        prepared = false;
        mp.setAudioAttributes(musicAttributes());
        mp.setAudioSessionId(audioSession);
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(m -> {
            if (m != player) return;
            prepared = true;
            consecutiveErrors = 0;
            if (pendingSeek > 0) m.seekTo(pendingSeek);
            pendingSeek = 0;
            if (wantPlay) m.start();
            changed();
        });
        mp.setOnCompletionListener(m -> {
            if (m != player) return;
            if (repeat == REPEAT_ONE) {
                m.seekTo(0);
                m.start();
            } else {
                wantPlay = true;
                next();
            }
        });
        mp.setOnErrorListener((m, what, extra) -> {
            if (m != player) return true;
            if (++consecutiveErrors >= Math.min(order.length, 10)) stopAtEnd();
            else main.post(this::next);
            return true;
        });
        try {
            mp.setDataSource(this, t.uri);
            mp.prepareAsync();
        } catch (Exception e) {
            if (++consecutiveErrors >= Math.min(order.length, 10)) { stopAtEnd(); return; }
            main.post(this::next);
            return;
        }
        if (wantPlay) {
            goForeground();
            setNoisy(true);
        }
        changed();
    }

    private void releasePlayer() {
        if (player != null) {
            MediaPlayer p = player;
            player = null;
            prepared = false;
            try { p.release(); } catch (Exception ignored) { }
        }
    }

    // ---- play counts (for "Top 25 Most Played") --------------------------------------------

    private void bumpPlayCount(Track t) {
        String k = "pc_" + t.id;
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply();
    }

    public static int playCount(Context ctx, Track t) {
        return ctx.getSharedPreferences("ipod", MODE_PRIVATE).getInt("pc_" + t.id, 0);
    }

    // ---- audio focus / noisy ---------------------------------------------------------------

    private boolean requestFocus() {
        return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() { audio.abandonAudioFocusRequest(focusRequest); }

    private void setNoisy(boolean on) {
        if (on == noisyRegistered) return;
        noisyRegistered = on;
        if (on) registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        else unregisterReceiver(noisy);
    }

    // ---- media session / notification ------------------------------------------------------

    private void updateSession() {
        Track t = current();
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;
        int state = t == null ? PlaybackState.STATE_NONE
                : isPlayingOrLoading() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, getPosition(), isPlaying() ? 1f : 0f)
                .build());
        if (t != null) {
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, getDuration())
                    .build());
        }
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Track t = current();
        boolean playing = isPlayingOrLoading();
        Notification.Action prev = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Previous", action(ACTION_PREV, 1)).build();
        Notification.Action toggle = new Notification.Action.Builder(
                Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                playing ? "Pause" : "Play", action(ACTION_TOGGLE, 2)).build();
        Notification.Action next = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_next), "Next", action(ACTION_NEXT, 3)).build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t != null ? t.title : "iPod")
                .setContentText(t != null ? t.artist + " — " + t.album : "")
                .setContentIntent(openAppIntent())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .addAction(prev).addAction(toggle).addAction(next)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private void goForeground() {
        if (foreground) return;
        Notification n = buildNotification();
        try {
            // Make sure we outlive the activity binding while music plays.
            startService(new Intent(this, PlayerService.class));
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            foreground = true;
        } catch (Exception e) {
            // Starting a foreground service from the background can be refused; keep playing anyway.
        }
    }

    private void leaveForeground() {
        if (!foreground) return;
        foreground = false;
        stopForeground(STOP_FOREGROUND_DETACH);
    }

    private void updateNotification() {
        if (!hasTrack()) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        try {
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException ignored) {
            // Notifications not allowed: the app still works.
        }
    }
}
