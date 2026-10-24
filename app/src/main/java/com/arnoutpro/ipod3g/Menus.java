package com.arnoutpro.ipod3g;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import java.util.ArrayList;
import java.util.List;

/** Builds the 3rd generation iPod menu tree. */
public final class Menus {
    private static final int[] BACKLIGHT_SECONDS = {0, 2, 5, 10, 15, 20, 30, -1};

    private final Lcd lcd;
    private final MainActivity host;
    private final SharedPreferences prefs;

    public Menus(Lcd lcd) {
        this.lcd = lcd;
        this.host = lcd.host;
        this.prefs = host.prefs();
    }

    private static ListScreen.Item item(String label) { return new ListScreen.Item(label); }

    private ListScreen.Item sub(String label, java.util.function.Supplier<Screen> screen) {
        return item(label).arrow().onSelect(() -> lcd.push(screen.get()));
    }

    // ---- roots -----------------------------------------------------------------------------

    public Screen root() {
        if (!host.hasMusicPermission()) {
            List<ListScreen.Item> items = new ArrayList<>();
            items.add(item("Allow Music Access").arrow().onSelect(host::requestMusicPermission));
            return new ListScreen(lcd, "iPod", items);
        }
        return new ListScreen(lcd, "iPod", this::mainItems);
    }

    private List<ListScreen.Item> mainItems() {
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(sub("Playlists", this::playlists));
        items.add(sub("Browse", this::browse));
        items.add(sub("Spotify", this::spotify));
        items.add(sub("Extras", this::extras));
        items.add(sub("Settings", this::settings));
        items.add(item("Shuffle Songs").onSelect(() -> {
            PlayerService ps = host.service();
            List<Track> all = host.library().songs();
            if (ps == null || all.isEmpty()) return;
            ps.shuffleAll(all);
            lcd.showNowPlaying();
        }));
        items.add(item("Backlight").onSelect(host::toggleBacklight));
        Playback pb = host.playback();
        if (pb != null && pb.hasTrack()) items.add(sub("Now Playing", () -> new NowPlayingScreen(lcd)));
        return items;
    }

    // ---- playlists -------------------------------------------------------------------------

    private Screen playlists() {
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(sub("On-The-Go", this::onTheGo));
        items.add(sub("Recently Added", () -> songs("Recently Added", host.library().recentlyAdded(100), false)));
        items.add(sub("Top 25 Most Played", () -> songs("Top 25 Most Played", topPlayed(), false)));
        return new ListScreen(lcd, "Playlists", items);
    }

    private List<Track> topPlayed() {
        List<Track> all = new ArrayList<>();
        for (Track t : host.library().songs()) if (PlayerService.playCount(host, t) > 0) all.add(t);
        all.sort((a, b) -> Integer.compare(PlayerService.playCount(host, b), PlayerService.playCount(host, a)));
        return all.size() > 25 ? new ArrayList<>(all.subList(0, 25)) : all;
    }

    private List<Track> onTheGoTracks() {
        List<Track> out = new ArrayList<>();
        String ids = prefs.getString("otg", "");
        for (String s : ids.split(",")) {
            if (s.isEmpty()) continue;
            try {
                Track t = host.library().byId(Long.parseLong(s));
                if (t != null) out.add(t);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private void addToOnTheGo(Track t) {
        String ids = prefs.getString("otg", "");
        prefs.edit().putString("otg", ids.isEmpty() ? String.valueOf(t.id) : ids + "," + t.id).apply();
        host.buzz();
    }

    private Screen onTheGo() {
        return new ListScreen(lcd, "On-The-Go", () -> {
            List<Track> list = onTheGoTracks();
            List<ListScreen.Item> items = songItems(list);
            if (!list.isEmpty()) {
                items.add(item("Clear Playlist").onSelect(() -> {
                    prefs.edit().remove("otg").apply();
                    lcd.pop();
                }));
            }
            return items;
        }).empty("Hold Select on a song");
    }

    // ---- spotify ---------------------------------------------------------------------------

    private Screen spotify() { return new ListScreen(lcd, "Spotify", this::spotifyItems); }

    private List<ListScreen.Item> spotifyItems() {
        Spotify sp = host.spotify();
        List<ListScreen.Item> items = new ArrayList<>();
        if (!sp.connected()) {
            items.add(item("Connect to Spotify").arrow().onSelect(host::connectSpotify));
            if (host.spotifyError() != null) items.add(item(host.spotifyError()));
            return items;
        }
        items.add(sub("Playlists", this::spotifyPlaylists));
        items.add(sub("Albums", this::spotifyAlbums));
        items.add(sub("Artists", this::spotifyArtists));
        items.add(sub("Liked Songs", () -> spotifySongs("Liked Songs",
                "spotify:user:" + sp.userId() + ":collection", "/me/tracks", "No Liked Songs")));
        items.add(item("Disconnect").value(sp::userName).onSelect(host::disconnectSpotify));
        return items;
    }

    private interface Loader { List<ListScreen.Item> load(ListScreen s) throws IOException, JSONException; }

    private interface Row { ListScreen.Item of(JSONObject o) throws JSONException; }

    /** A list that fills in as Spotify answers; errors show where the rows would be. */
    private Screen spotifyList(String title, String empty, Loader load) {
        ListScreen s = new ListScreen(lcd, title, new ArrayList<>()).empty("Loading...");
        host.spotify().call(() -> load.load(s), (items, err) -> {
            s.empty(err != null ? err : empty);
            if (items != null) s.setItems(items);
            host.redraw();
        });
        return s;
    }

    /** Turns a paged Spotify list into rows, showing each page as soon as it arrives. */
    private List<ListScreen.Item> rows(ListScreen s, String path, String wrapper, Row row)
            throws IOException, JSONException {
        List<ListScreen.Item> items = new ArrayList<>();
        host.spotify().all(path, wrapper, page -> {
            for (JSONObject o : page) {
                ListScreen.Item it = row.of(o);
                if (it != null) items.add(it);
            }
            List<ListScreen.Item> sofar = new ArrayList<>(items);
            host.runOnUiThread(() -> {
                s.setItems(sofar);
                host.redraw();
            });
        });
        return items;
    }

    private Screen spotifyPlaylists() {
        Spotify sp = host.spotify();
        return spotifyList("Playlists", "No Playlists", s -> rows(s, "/me/playlists", null, p -> {
            String name = p.optString("name"), uri = p.optString("uri");
            JSONObject owner = p.optJSONObject("owner");
            // Spotify only lets this app read the songs of playlists you own or share.
            boolean mine = p.optBoolean("collaborative")
                    || owner != null && owner.optString("id").equals(sp.userId());
            String path = "/playlists/" + p.optString("id") + "/items";
            return sub(name, () -> mine ? spotifySongs(name, uri, path, "No Songs") : followed(name, uri));
        }));
    }

    /** A playlist you follow: Spotify hides its songs from this app but still plays it. */
    private Screen followed(String name, String uri) {
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(item("Play Playlist").onSelect(() -> host.playSpotify(uri, null, -1)));
        items.add(item("Songs hidden by Spotify"));
        return new ListScreen(lcd, name, items);
    }

    private Screen spotifyAlbums() {
        return spotifyList("Albums", "No Albums", s -> rows(s, "/me/albums", null, o -> {
            JSONObject a = o.optJSONObject("album");
            return a == null ? null : spotifyAlbum(a);
        }));
    }

    private ListScreen.Item spotifyAlbum(JSONObject a) {
        String name = a.optString("name"), uri = a.optString("uri");
        String path = "/albums/" + a.optString("id") + "/tracks";
        return sub(name, () -> spotifySongs(name, uri, path, "No Songs"));
    }

    private Screen spotifyArtists() {
        return spotifyList("Artists", "No Artists", s -> rows(s, "/me/following?type=artist", "artists", a -> {
            String name = a.optString("name");
            String path = "/artists/" + a.optString("id") + "/albums?include_groups=album,single";
            return sub(name, () -> spotifyList(name, "No Albums", s2 -> rows(s2, path, null, this::spotifyAlbum)));
        }));
    }

    /** Songs of a playlist, album or Liked Songs; picking one plays the rest of the list too. */
    private Screen spotifySongs(String title, String contextUri, String path, String empty) {
        return spotifyList(title, empty, s -> {
            int[] local = {0}, position = {0};
            List<ListScreen.Item> items = rows(s, path, null, o -> {
                int at = position[0]++; // its place in the playlist or album, local files included
                // Playlists wrap each song in "item" (formerly "track"); albums list them bare.
                JSONObject t = o.optJSONObject("item");
                if (t == null) t = o.optJSONObject("track");
                if (t == null) t = o;
                String uri = t.optString("uri");
                if (o.optBoolean("is_local") || t.optBoolean("is_local")) {
                    local[0]++;
                    return null;
                }
                if (uri.isEmpty() || uri.equals("null")) return null;
                return item(t.optString("name")).onSelect(() -> host.playSpotify(contextUri, uri, at));
            });
            // Files from your own computer live in the playlist but can't be streamed.
            if (items.isEmpty() && local[0] > 0) items.add(item("Only local files"));
            return items;
        });
    }

    // ---- browse ----------------------------------------------------------------------------

    private Screen browse() {
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(sub("Artists", this::artists));
        items.add(sub("Albums", () -> albums("Albums", host.library().albums())));
        items.add(sub("Genres", this::genres));
        items.add(sub("Songs", () -> songs("Songs", host.library().songs(), true)));
        items.add(sub("Composers", this::composers));
        return new ListScreen(lcd, "Browse", items);
    }

    private Screen artists() {
        MusicLibrary lib = host.library();
        List<ListScreen.Item> items = new ArrayList<>();
        for (String a : lib.artists()) items.add(sub(a, () -> artist(a, lib.byArtist(a))));
        return new ListScreen(lcd, "Artists", items).empty("No Artists");
    }

    /** An artist: "All" followed by their albums. */
    private Screen artist(String name, List<Track> tracks) {
        MusicLibrary lib = host.library();
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(sub("All", () -> songs(name, tracks, false)));
        for (String al : lib.albumsOf(tracks)) {
            items.add(sub(al, () -> {
                List<Track> sel = new ArrayList<>();
                for (Track t : tracks) if (t.album.equals(al)) sel.add(t);
                return songs(al, sel, false);
            }));
        }
        return new ListScreen(lcd, name, items);
    }

    private Screen albums(String title, List<String> names) {
        MusicLibrary lib = host.library();
        List<ListScreen.Item> items = new ArrayList<>();
        for (String al : names) items.add(sub(al, () -> songs(al, lib.byAlbum(al), false)));
        return new ListScreen(lcd, title, items).empty("No Albums");
    }

    private Screen genres() {
        MusicLibrary lib = host.library();
        List<ListScreen.Item> items = new ArrayList<>();
        for (String g : lib.genres()) {
            items.add(sub(g, () -> {
                List<Track> inGenre = lib.byGenre(g);
                List<ListScreen.Item> artists = new ArrayList<>();
                artists.add(sub("All", () -> songs(g, inGenre, false)));
                for (String a : lib.artistsOf(inGenre)) {
                    List<Track> mine = new ArrayList<>();
                    for (Track t : inGenre) if (t.artist.equals(a)) mine.add(t);
                    artists.add(sub(a, () -> artist(a, mine)));
                }
                return new ListScreen(lcd, g, artists);
            }));
        }
        return new ListScreen(lcd, "Genres", items).empty("No Genres");
    }

    private Screen composers() {
        MusicLibrary lib = host.library();
        List<ListScreen.Item> items = new ArrayList<>();
        for (String c : lib.composers()) items.add(sub(c, () -> songs(c, lib.byComposer(c), false)));
        return new ListScreen(lcd, "Composers", items).empty("No Composers");
    }

    private Screen songs(String title, List<Track> list, boolean all) {
        return new ListScreen(lcd, title, songItems(list)).empty(all ? "No Music Found" : "No Songs");
    }

    private List<ListScreen.Item> songItems(List<Track> list) {
        List<ListScreen.Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            Track t = list.get(i);
            items.add(item(t.title)
                    .onSelect(() -> {
                        PlayerService ps = host.service();
                        if (ps == null) return;
                        ps.playQueue(list, index);
                        lcd.showNowPlaying();
                    })
                    .onHold(() -> addToOnTheGo(t)));
        }
        return items;
    }

    // ---- extras ----------------------------------------------------------------------------

    private Screen extras() {
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(sub("Clock", () -> new ClockScreen(lcd)));
        items.add(sub("Games", () -> {
            List<ListScreen.Item> games = new ArrayList<>();
            games.add(sub("Brick", () -> new BrickScreen(lcd)));
            return new ListScreen(lcd, "Games", games);
        }));
        return new ListScreen(lcd, "Extras", items);
    }

    // ---- settings --------------------------------------------------------------------------

    private Screen settings() {
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(sub("About", this::about));
        items.add(item("Shuffle")
                .value(() -> host.service() != null && host.service().isShuffle() ? "Songs" : "Off")
                .onSelect(() -> {
                    PlayerService ps = host.service();
                    if (ps != null) ps.setShuffle(!ps.isShuffle());
                    host.redraw();
                }));
        items.add(item("Repeat")
                .value(() -> {
                    PlayerService ps = host.service();
                    int m = ps == null ? 0 : ps.repeatMode();
                    return m == PlayerService.REPEAT_ONE ? "One" : m == PlayerService.REPEAT_ALL ? "All" : "Off";
                })
                .onSelect(() -> {
                    PlayerService ps = host.service();
                    if (ps != null) ps.setRepeat((ps.repeatMode() + 1) % 3);
                    host.redraw();
                }));
        items.add(item("EQ").arrow()
                .value(() -> host.service() != null ? host.service().eq().label() : "")
                .onSelect(() -> lcd.push(eq())));
        items.add(item("Backlight Timer").arrow()
                .value(() -> backlightLabel(host.backlightSeconds()))
                .onSelect(() -> lcd.push(backlightTimer())));
        items.add(item("Clicker")
                .value(() -> host.clickerOn() ? "On" : "Off")
                .onSelect(() -> {
                    host.setClicker(!host.clickerOn());
                    host.redraw();
                }));
        items.add(item("Color")
                .value(() -> finishLabel(host.finishMode()))
                .onSelect(() -> {
                    host.setFinishMode((host.finishMode() + 1) % 3);
                    host.redraw();
                }));
        items.add(item("Rescan Music").onSelect(host::rescan));
        return new ListScreen(lcd, "Settings", items);
    }

    static String finishLabel(int m) {
        if (m == MainActivity.FINISH_BLACK) return "Black";
        if (m == MainActivity.FINISH_AUTO) return "Auto";
        return "White";
    }

    static String backlightLabel(int s) {
        if (s == 0) return "Off";
        if (s < 0) return "Always On";
        return s + " Seconds";
    }

    /** Settings > EQ: the bands to set by hand, then presets to start from. */
    private Screen eq() {
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(item("Bands").arrow().onSelect(() -> lcd.push(new EqScreen(lcd))));
        for (int i = 0; i < Eq.PRESETS.length; i++) {
            int p = i;
            items.add(item(Eq.PRESETS[p])
                    .value(() -> host.service() != null && host.service().eq().preset() == p ? "✓" : "")
                    .onSelect(() -> {
                        if (host.service() != null) host.service().eq().usePreset(p);
                        host.redraw();
                    }));
        }
        return new ListScreen(lcd, "EQ", items);
    }

    private Screen backlightTimer() {
        List<ListScreen.Item> items = new ArrayList<>();
        for (int s : BACKLIGHT_SECONDS) {
            items.add(item(backlightLabel(s))
                    .value(() -> host.backlightSeconds() == s ? "✓" : "")
                    .onSelect(() -> {
                        host.setBacklightSeconds(s);
                        lcd.pop();
                    }));
        }
        return new ListScreen(lcd, "Backlight", items);
    }

    private Screen about() {
        MusicLibrary lib = host.library();
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(item("iPod 3G Player"));
        String songs = String.valueOf(lib.songs().size());
        String artists = String.valueOf(lib.artists().size());
        String albums = String.valueOf(lib.albums().size());
        items.add(item("Songs").value(() -> songs));
        items.add(item("Artists").value(() -> artists));
        items.add(item("Albums").value(() -> albums));
        items.add(item("Version").value(() -> "1.0"));
        return new ListScreen(lcd, "About", items);
    }
}
