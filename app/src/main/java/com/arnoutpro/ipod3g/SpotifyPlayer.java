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

/**
 * Plays through the Spotify app on this phone, controlled with the Web API and woken with the App
 * Remote SDK. The iPod shows what Spotify reports, polled while it is the active source.
 */
public final class SpotifyPlayer implements Playback {
    private static final String SPOTIFY_APP = "com.spotify.music";
    private static final long POLL_MS = 2000, CONNECT_TIMEOUT_MS = 6000;

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

    /**
     * Plays a song in its playlist or album (contextUri), starting at trackUri (which sits at
     * index in that list), or from the top when trackUri is null.
     */
    public void play(String contextUri, String trackUri, int index) {
        status = "Starting Spotify...";
        has = true;
        host.redraw();
        spotify.call(this::findPhone, (device, err) -> {
            if (err != null) fail(err);
            else if (device != null) playOn(device, contextUri, trackUri);
            // Spotify only lists this phone while its app is awake: wake it in the background.
            else wake(contextUri, trackUri, index);
        });
    }

    /** Starts the song on this phone through the Web API. */
    private void playOn(String device, String contextUri, String trackUri) {
        spotify.call(() -> {
            JSONObject body = new JSONObject();
            if (contextUri != null) {
                body.put("context_uri", contextUri);
                if (trackUri != null) body.put("offset", new JSONObject().put("uri", trackUri));
            } else {
                body.put("uris", new JSONArray().put(trackUri));
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
    private void wake(String contextUri, String trackUri, int index) {
        if (remote != null && remote.isConnected()) {
            awake(remote, contextUri, trackUri, index);
            return;
        }
        connect(false, contextUri, trackUri, index);
    }

    /**
     * Connecting with the authorization screen allowed brings Spotify to the front, so first try
     * without it; only the very first time, before Spotify has said yes, is it needed.
     */
    private void connect(boolean authView, String contextUri, String trackUri, int index) {
        ConnectionParams params = new ConnectionParams.Builder(Spotify.CLIENT_ID)
                .setRedirectUri(Spotify.REDIRECT)
                .showAuthView(authView)
                .build();
        // Spotify sometimes never answers; don't leave the iPod waiting.
        boolean[] answered = {false};
        Runnable timeout = () -> {
            if (answered[0]) return;
            answered[0] = true;
            openAndPlay(contextUri, trackUri);
        };
        main.postDelayed(timeout, CONNECT_TIMEOUT_MS);
        SpotifyAppRemote.connect(host, params, new Connector.ConnectionListener() {
            @Override public void onConnected(SpotifyAppRemote r) {
                remote = r;
                if (answered[0]) return;
                answered[0] = true;
                main.removeCallbacks(timeout);
                awake(r, contextUri, trackUri, index);
            }

            @Override public void onFailure(Throwable t) {
                if (answered[0]) return;
                answered[0] = true;
                main.removeCallbacks(timeout);
                if (!authView && t instanceof UserNotAuthorizedException) {
                    connect(true, contextUri, trackUri, index);
                } else {
                    openAndPlay(contextUri, trackUri);
                }
            }
        });
    }

    /** Spotify is awake: wait for it to list this phone, then play the exact song there. */
    private void awake(SpotifyAppRemote r, String contextUri, String trackUri, int index) {
        spotify.call(() -> {
            String device = findPhone();
            for (int i = 0; i < 8 && device == null; i++) {
                sleep(500);
                device = findPhone();
            }
            return device;
        }, (device, err) -> {
            if (device != null) playOn(device, contextUri, trackUri);
            else playRemote(r, contextUri, trackUri, index);
        });
    }

    /** Plays through the SDK itself; its song positions follow shuffle, so only a fallback. */
    private void playRemote(SpotifyAppRemote r, String contextUri, String trackUri, int index) {
        PlayerApi player = r.getPlayerApi();
        CallResult<Empty> call = contextUri != null && trackUri != null && index >= 0
                ? player.skipToIndex(contextUri, index)
                : player.play(contextUri != null ? contextUri : trackUri);
        call.setResultCallback(empty -> {
            status = null;
            // If Spotify still came to the front while waking up, come back.
            if (!host.isFront()) backToIPod();
            main.postDelayed(this::refresh, 800);
            host.redraw();
        }).setErrorCallback(t -> openAndPlay(contextUri, trackUri));
    }

    /** The old way: open Spotify on screen until it registers, then come back and play. */
    private void openAndPlay(String contextUri, String trackUri) {
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
            else playOn(device, contextUri, trackUri);
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
