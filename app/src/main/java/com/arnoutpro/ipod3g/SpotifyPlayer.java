package com.arnoutpro.ipod3g;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Plays through the Spotify app on this phone, controlled with the Web API. The iPod shows what
 * Spotify reports, polled while it is the active source.
 */
public final class SpotifyPlayer implements Playback {
    private static final String SPOTIFY_APP = "com.spotify.music";
    private static final long POLL_MS = 2000;

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
     * Plays a song in its playlist or album (contextUri), starting at trackUri, or from the top
     * when trackUri is null. Wakes the Spotify
     * app first when this phone isn't a Spotify device yet.
     */
    public void play(String contextUri, String trackUri) {
        status = "Starting Spotify...";
        has = true;
        host.redraw();
        spotify.call(() -> {
            String device = findPhone();
            boolean opened = false;
            if (device == null) {
                // Spotify only lists this phone once its app has been opened.
                opened = true;
                main.post(this::openSpotifyApp);
                for (int i = 0; i < 15 && device == null; i++) {
                    sleep(1000);
                    device = findPhone();
                }
            }
            if (opened) main.post(this::backToIPod);
            if (device == null) throw new IOException("Open Spotify first");
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
