package com.arnoutpro.ipod3g;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.PlayerApi;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.android.appremote.api.error.UserNotAuthorizedException;
import com.spotify.protocol.client.CallResult;
import com.spotify.protocol.types.Empty;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays through the Spotify app on this phone, controlled with the Web API and woken with the App
 * Remote SDK. The iPod shows what Spotify reports, polled while it is the active source.
 */
public final class SpotifyPlayer implements Playback {
    private static final String SPOTIFY_APP = "com.spotify.music";
    private static final long POLL_MS = 2000, CONNECT_TIMEOUT_MS = 6000;
    private static final int QUEUE_SONGS = 30;

    private final Spotify spotify;
    private final MainActivity host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean has, playing, shuffle;
    private int repeat = PlayerService.REPEAT_OFF;
    private String key = "", title = "", artist = "", album = "";
    private int position, duration;
    private long positionAt;
    private String status;
    private boolean polling, inFlight;
    private SpotifyAppRemote remote;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            if (polling) main.postDelayed(this, POLL_MS);
        }
    };

    public SpotifyPlayer(Spotify spotify, MainActivity host) {
        this.spotify = spotify;
        this.host = host;
    }

    public void startPolling() {
        if (polling) return;
        polling = true;
        main.post(poll);
    }

    public void stopPolling() {
        polling = false;
        main.removeCallbacks(poll);
    }

    /** What to play: a song within a playlist or album, or a plain list of songs. */
    public static final class Request {
        final String contextUri, trackUri;
        final int index;
        final List<String> uris;

        private Request(String contextUri, String trackUri, int index, List<String> uris) {
            this.contextUri = contextUri;
            this.trackUri = trackUri;
            this.index = index;
            this.uris = uris;
        }

        /** trackUri sits at index in the playlist or album; null plays it from the top. */
        public static Request inContext(String contextUri, String trackUri, int index) {
            return new Request(contextUri, trackUri, index, null);
        }

        /** Plays songs from start onwards, e.g. an artist's songs or Songs A-Z. */
        public static Request songs(List<String> uris, int start) {
            List<String> queue = new ArrayList<>(uris.subList(start, Math.min(uris.size(), start + QUEUE_SONGS + 1)));
            return new Request(null, queue.get(0), -1, queue);
        }
    }

    public void play(Request rq) {
        status = "Starting Spotify...";
        has = true;
        host.redraw();
        if (rq.uris != null) {
            // Spotify accepts a plain list of songs over the Web API but then plays nothing,
            // so lists go through the App Remote connection instead.
            wake(rq);
            return;
        }
        spotify.call(this::findPhone, (device, err) -> {
            if (err != null) fail(err);
            else if (device != null) playOn(device, rq);
            // Spotify only lists this phone while its app is awake: wake it in the background.
            else wake(rq);
        });
    }

    /** Starts the song on this phone through the Web API. */
    private void playOn(String device, Request rq) {
        spotify.call(() -> {
            JSONObject body = new JSONObject();
            if (rq.contextUri != null) {
                body.put("context_uri", rq.contextUri);
                if (rq.trackUri != null) body.put("offset", new JSONObject().put("uri", rq.trackUri));
            }
            spotify.send("PUT", "/me/player/play?device_id=" + Spotify.enc(device), body.toString());
            return null;
        }, (r, err) -> {
            status = err;
            main.postDelayed(this::refresh, 600);
            host.redraw();
        });
    }

    /**
     * Connects to the Spotify app with the App Remote SDK, which starts it without showing it.
     * Once awake, the phone appears as a Spotify device and plays through the Web API, which picks
     * the exact song even with shuffle on. Opening Spotify on screen is the last resort.
     */
    private void wake(Request rq) {
        if (remote != null && remote.isConnected()) {
            awake(remote, rq);
            return;
        }
        connect(false, rq);
    }

    /**
     * Connecting with the authorization screen allowed brings Spotify to the front, so first try
     * without it; only the very first time, before Spotify has said yes, is it needed.
     */
    private void connect(boolean authView, Request rq) {
        ConnectionParams params = new ConnectionParams.Builder(Spotify.CLIENT_ID)
                .setRedirectUri(Spotify.REDIRECT)
                .showAuthView(authView)
                .build();
        // Spotify sometimes never answers; don't leave the iPod waiting.
        boolean[] answered = {false};
        Runnable timeout = () -> {
            if (answered[0]) return;
            answered[0] = true;
            openAndPlay(rq);
        };
        main.postDelayed(timeout, CONNECT_TIMEOUT_MS);
        SpotifyAppRemote.connect(host, params, new Connector.ConnectionListener() {
            @Override public void onConnected(SpotifyAppRemote r) {
                remote = r;
                if (answered[0]) return;
                answered[0] = true;
                main.removeCallbacks(timeout);
                awake(r, rq);
            }

            @Override public void onFailure(Throwable t) {
                if (answered[0]) return;
                answered[0] = true;
                main.removeCallbacks(timeout);
                if (!authView && t instanceof UserNotAuthorizedException) {
                    connect(true, rq);
                } else {
                    openAndPlay(rq);
                }
            }
        });
    }

    /** Spotify is awake: wait for it to list this phone, then play the exact song there. */
    private void awake(SpotifyAppRemote r, Request rq) {
        if (rq.uris != null) {
            playSongs(r, rq);
            return;
        }
        spotify.call(() -> {
            String device = findPhone();
            for (int i = 0; i < 8 && device == null; i++) {
                sleep(500);
                device = findPhone();
            }
            return device;
        }, (device, err) -> {
            if (device != null) playOn(device, rq);
            else playRemote(r, rq);
        });
    }

    /** Plays through the SDK itself; its song positions follow shuffle, so only a fallback. */
    private void playRemote(SpotifyAppRemote r, Request rq) {
        PlayerApi player = r.getPlayerApi();
        CallResult<Empty> call = rq.contextUri != null && rq.trackUri != null && rq.index >= 0
                ? player.skipToIndex(rq.contextUri, rq.index)
                : player.play(rq.contextUri != null ? rq.contextUri : rq.trackUri);
        call.setResultCallback(empty -> {
            status = null;
            // If Spotify still came to the front while waking up, come back.
            if (!host.isFront()) backToIPod();
            main.postDelayed(this::refresh, 800);
            host.redraw();
        }).setErrorCallback(t -> openAndPlay(rq));
    }

    /** Plays the first song of a list and queues the ones after it. */
    private void playSongs(SpotifyAppRemote r, Request rq) {
        PlayerApi player = r.getPlayerApi();
        player.play(rq.trackUri).setResultCallback(empty -> {
            status = null;
            queue(player, rq.uris, 1);
            main.postDelayed(this::refresh, 800);
            host.redraw();
        }).setErrorCallback(t -> fail("Can't start Spotify"));
    }

    /** Queues one song at a time, so they keep their order. */
    private void queue(PlayerApi player, List<String> uris, int i) {
        if (i >= Math.min(uris.size(), QUEUE_SONGS + 1)) return;
        player.queue(uris.get(i)).setResultCallback(empty -> queue(player, uris, i + 1));
    }

    /** The old way: open Spotify on screen until it registers, then come back and play. */
    private void openAndPlay(Request rq) {
        if (rq.uris != null) {
            fail("Can't start Spotify");
            return;
        }
        spotify.call(() -> {
            main.post(this::openSpotifyApp);
            String device = null;
            for (int i = 0; i < 15 && device == null; i++) {
                sleep(1000);
                device = findPhone();
            }
            main.post(this::backToIPod);
            if (device == null) throw new IOException("Open Spotify first");
            return device;
        }, (device, err) -> {
            if (err != null) fail(err);
            else playOn(device, rq);
        });
    }

    private void fail(String err) {
        status = err;
        host.redraw();
    }

    /** Lets go of the Spotify app when the iPod closes. */
    public void release() {
        if (remote != null) SpotifyAppRemote.disconnect(remote);
        remote = null;
    }

    /**
     * This phone as a Spotify device. Never another device, even an active one: the music should
     * come out of the iPod, not a computer's web player.
     */
    private String findPhone() throws IOException, JSONException {
        JSONArray devices = spotify.get("/me/player/devices").optJSONArray("devices");
        String name = Settings.Global.getString(host.getContentResolver(), "device_name");
        String phone = null;
        for (int i = 0; devices != null && i < devices.length(); i++) {
            JSONObject d = devices.getJSONObject(i);
            if (d.optBoolean("is_restricted") || !"Smartphone".equals(d.optString("type"))) continue;
            // With several phones on the account, the one carrying this phone's name wins.
            if (name != null && name.equals(d.optString("name"))) return d.optString("id");
            if (phone == null || d.optBoolean("is_active")) phone = d.optString("id");
        }
        return phone;
    }

    private void openSpotifyApp() {
        Intent i = host.getPackageManager().getLaunchIntentForPackage(SPOTIFY_APP);
        if (i == null) {
            status = "Install Spotify";
            host.redraw();
            return;
        }
        host.startActivity(i);
    }

    private void backToIPod() {
        host.startActivity(new Intent(host, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** Asks Spotify what it is playing. */
    public void refresh() {
        if (inFlight || !spotify.connected()) return;
        inFlight = true;
        spotify.call(() -> spotify.get("/me/player"), (state, err) -> {
            inFlight = false;
            if (err != null) {
                status = err;
            } else {
                read(state);
            }
            host.redraw();
        });
    }

    private void read(JSONObject state) {
        JSONObject item = state.optJSONObject("item");
        if (item == null) {
            has = status != null; // keep showing a pending "Starting Spotify..."
            playing = false;
            return;
        }
        status = null;
        has = true;
        key = item.optString("uri");
        title = item.optString("name");
        JSONArray artists = item.optJSONArray("artists");
        artist = artists != null && artists.length() > 0 ? artists.optJSONObject(0).optString("name") : "";
        JSONObject alb = item.optJSONObject("album");
        album = alb != null ? alb.optString("name") : item.optJSONObject("show") != null
                ? item.optJSONObject("show").optString("name") : "";
        duration = item.optInt("duration_ms");
        position = state.optInt("progress_ms");
        positionAt = SystemClock.elapsedRealtime();
        playing = state.optBoolean("is_playing");
        shuffle = state.optBoolean("shuffle_state");
        String r = state.optString("repeat_state", "off");
        repeat = r.equals("track") ? PlayerService.REPEAT_ONE
                : r.equals("context") ? PlayerService.REPEAT_ALL : PlayerService.REPEAT_OFF;
    }

    private void command(String method, String path) {
        spotify.call(() -> {
            spotify.send(method, path, null);
            return null;
        }, (r, err) -> {
            if (err != null) status = err;
            main.postDelayed(this::refresh, 400);
            host.redraw();
        });
    }

    // ---- Playback --------------------------------------------------------------------------

    @Override public boolean hasTrack() { return has; }

    @Override public boolean isPlayingOrLoading() { return playing; }

    @Override public String trackKey() { return key; }

    @Override public String title() { return status != null ? status : title; }

    @Override public String artist() { return status != null ? "" : artist; }

    @Override public String album() { return status != null ? "" : album; }

    /** Spotify's name stands in for "3 of 12": the credit its terms ask for. */
    @Override public String counter() { return "Spotify"; }

    @Override public int getPosition() {
        if (!playing) return position;
        return (int) Math.min(duration, position + SystemClock.elapsedRealtime() - positionAt);
    }

    @Override public int getDuration() { return duration; }

    @Override public boolean isShuffle() { return shuffle; }

    @Override public int repeatMode() { return repeat; }

    @Override public void toggle() {
        position = getPosition();
        positionAt = SystemClock.elapsedRealtime();
        playing = !playing; // show it straight away; the next poll corrects it if needed
        command("PUT", playing ? "/me/player/play" : "/me/player/pause");
    }

    @Override public void next() { command("POST", "/me/player/next"); }

    @Override public void previous() { command("POST", "/me/player/previous"); }

    private final Runnable sendSeek = () -> command("PUT", "/me/player/seek?position_ms=" + getPosition());

    /** Scrubbing calls this on every wheel tick, so only the last position is sent. */
    @Override public void seekTo(int ms) {
        position = Math.max(0, Math.min(duration, ms));
        positionAt = SystemClock.elapsedRealtime();
        main.removeCallbacks(sendSeek);
        main.postDelayed(sendSeek, 350);
    }
}
