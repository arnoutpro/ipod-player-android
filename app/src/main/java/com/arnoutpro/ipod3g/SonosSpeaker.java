package com.arnoutpro.ipod3g;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * A Sonos group playing the phone's own music. Commands go to the group's coordinator; its state
 * is polled every second, which is also how the end of a song is noticed.
 */
final class SonosSpeaker implements RemoteSpeaker {
    private static final String AV = "/MediaRenderer/AVTransport/Control";
    private static final String GROUP_VOLUME = "/MediaRenderer/GroupRenderingControl/Control";
    private static final String ID = "<InstanceID>0</InstanceID>";
    private static final long POLL_MS = 1000;

    final Sonos.Group group;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // Last known state; written on the io thread, read on the main thread.
    private volatile boolean playing, loaded, sawPlaying, finishHandled;
    private volatile int position, duration, volume = -1;
    private volatile long positionAt, volumeSetAt;
    /** Counts loads, so a poll answered for the previous song is thrown away. */
    private volatile int generation;
    private boolean polling, inFlight;
    private int polls;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            if (!inFlight) {
                inFlight = true;
                SonosSpeaker.this.run(SonosSpeaker.this::readState, () -> inFlight = false);
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    SonosSpeaker(Sonos.Group group, Listener listener) {
        this.group = group;
        this.listener = listener;
        polling = true;
        main.post(poll);
    }

    @Override public String name() { return group.name; }

    @Override public void load(String url, String type, Track t, int positionMs, boolean play) {
        generation++;
        loaded = true;
        sawPlaying = false;
        finishHandled = false;
        playing = play;
        position = positionMs;
        positionAt = SystemClock.elapsedRealtime();
        duration = (int) t.durationMs;
        String didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" "
                + "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">"
                + "<item id=\"ipod\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>" + Sonos.escape(t.title) + "</dc:title>"
                + "<dc:creator>" + Sonos.escape(t.artist) + "</dc:creator>"
                + "<upnp:album>" + Sonos.escape(t.album) + "</upnp:album>"
                + "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + type + ":*\" duration=\"" + time((int) t.durationMs) + "\">"
                + Sonos.escape(url) + "</res></item></DIDL-Lite>";
        run(() -> {
            av("SetAVTransportURI", ID + "<CurrentURI>" + Sonos.escape(url) + "</CurrentURI>"
                    + "<CurrentURIMetaData>" + Sonos.escape(didl) + "</CurrentURIMetaData>");
            if (positionMs > 1000) seekNow(positionMs);
            if (play) av("Play", ID + "<Speed>1</Speed>");
            if (volume < 0) readVolume();
        }, null);
    }

    @Override public void play() {
        snapshotPosition();
        playing = true;
        run(() -> av("Play", ID + "<Speed>1</Speed>"), null);
    }

    @Override public void pause() {
        snapshotPosition();
        playing = false;
        run(() -> av("Pause", ID), null);
    }

    @Override public void seek(int ms) {
        position = ms;
        positionAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(sendSeek);
        main.postDelayed(sendSeek, 300); // the wheel calls this on every tick
    }

    private final Runnable sendSeek = () -> {
        int at = position();
        run(() -> seekNow(at), null);
    };

    @Override public boolean isPlaying() { return playing; }

    @Override public boolean idle() { return !loaded; }

    @Override public int position() {
        if (!playing) return position;
        return (int) Math.min(duration > 0 ? duration : Long.MAX_VALUE,
                position + SystemClock.elapsedRealtime() - positionAt);
    }

    @Override public int duration() { return duration; }

    @Override public int volume() { return Math.max(0, volume); }

    @Override public void setVolume(int percent) {
        volume = percent;
        volumeSetAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(sendVolume);
        main.postDelayed(sendVolume, 300);
    }

    private final Runnable sendVolume = () -> {
        int v = volume;
        run(() -> {
            // A snapshot first keeps the speakers' volumes in proportion within the group.
            Sonos.soap(host(), GROUP_VOLUME, "GroupRenderingControl", "SnapshotGroupVolume", ID);
            Sonos.soap(host(), GROUP_VOLUME, "GroupRenderingControl", "SetGroupVolume",
                    ID + "<DesiredVolume>" + v + "</DesiredVolume>");
        }, null);
    };

    @Override public void release(boolean stop) {
        polling = false;
        main.removeCallbacks(poll);
        main.removeCallbacks(sendSeek);
        main.removeCallbacks(sendVolume);
        if (stop) run(() -> av("Pause", ID), null);
        io.shutdown();
    }

    // ---- io thread ---------------------------------------------------------------------------

    private interface Job { void run() throws IOException; }

    /** Runs job on the io thread, then after on the main thread; Sonos errors are ignored. */
    private void run(Job job, Runnable after) {
        try {
            io.execute(() -> {
                try {
                    job.run();
                } catch (IOException ignored) {
                    // The speaker didn't answer; the next poll shows the real state.
                }
                main.post(() -> {
                    if (after != null) after.run();
                    listener.onChanged();
                });
            });
        } catch (RejectedExecutionException ignored) {
            // Released.
        }
    }

    private void readState() throws IOException {
        int g = generation;
        String info = av("GetTransportInfo", ID);
        String state = Sonos.value(info, "CurrentTransportState");
        String pos = av("GetPositionInfo", ID);
        int rel = parse(Sonos.value(pos, "RelTime"));
        int dur = parse(Sonos.value(pos, "TrackDuration"));
        if (g != generation) return; // a new song was loaded meanwhile
        if (rel >= 0) {
            position = rel;
            positionAt = SystemClock.elapsedRealtime();
        }
        if (dur > 0) duration = dur;
        if ("PLAYING".equals(state)) {
            playing = true;
            sawPlaying = true;
        } else if ("PAUSED_PLAYBACK".equals(state)) {
            playing = false;
        } else if ("STOPPED".equals(state)) {
            playing = false;
            // Stopped after playing, by itself: the song ended.
            if (loaded && sawPlaying && !finishHandled) {
                finishHandled = true;
                loaded = false;
                main.post(listener::onFinished);
            }
        }
        if (++polls % 5 == 0) readVolume();
    }

    private void readVolume() throws IOException {
        if (SystemClock.elapsedRealtime() - volumeSetAt < 3000) return; // keep the wheel's value
        String reply = Sonos.soap(group.host, GROUP_VOLUME, "GroupRenderingControl", "GetGroupVolume", ID);
        try {
            volume = Integer.parseInt(Sonos.value(reply, "CurrentVolume"));
        } catch (NumberFormatException | NullPointerException ignored) { }
    }

    private void seekNow(int ms) throws IOException {
        av("Seek", ID + "<Unit>REL_TIME</Unit><Target>" + time(ms) + "</Target>");
    }

    private String av(String action, String args) throws IOException {
        return Sonos.soap(group.host, AV, "AVTransport", action, args);
    }

    private String host() { return group.host; }

    private void snapshotPosition() {
        position = position();
        positionAt = SystemClock.elapsedRealtime();
    }

    private static String time(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** "0:03:12" to milliseconds, or -1. */
    private static int parse(String t) {
        if (t == null) return -1;
        String[] p = t.split(":");
        if (p.length != 3) return -1;
        try {
            return (Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60
                    + (int) Double.parseDouble(p[2])) * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
