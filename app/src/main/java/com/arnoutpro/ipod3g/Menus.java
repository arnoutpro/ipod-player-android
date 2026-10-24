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
        items.add(sub("Speakers", this::speakers));
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

    // ---- speakers --------------------------------------------------------------------------

    /**
     * Where the music plays: this phone, a Spotify Connect device (for Spotify), or another output
     * like Bluetooth picked in Android's own switcher (for everything the phone plays).
     */
    private Screen speakers() {
        List<List<SpotifyPlayer.Speaker>> spotify = new ArrayList<>();
        spotify.add(new ArrayList<>());
        ListScreen s = new ListScreen(lcd, "Speakers", speakerItems(spotify.get(0)));
        Runnable rebuild = () -> {
            s.setItems(speakerItems(spotify.get(0)));
            host.redraw();
        };
        host.findCastSpeakers(rebuild);
        if (host.spotify().connected()) {
            SpotifyPlayer player = host.spotifyPlayer();
            host.spotify().call(player::speakers, (list, err) -> {
                if (list != null) spotify.set(0, list);
                rebuild.run();
            });
        }
        return s;
    }

    private boolean casting() { return host.service() != null && host.service().speaker() != null; }

    private List<ListScreen.Item> speakerItems(List<SpotifyPlayer.Speaker> spotify) {
        SpotifyPlayer player = host.spotifyPlayer();
        List<ListScreen.Item> items = new ArrayList<>();
        items.add(item("This Phone")
                .value(() -> player.target() == null && !casting() ? "✓" : "")
                .onSelect(() -> {
                    player.useSpeaker(null);
                    if (casting()) host.service().stopCasting();
                    host.redraw();
                }));
        // Cast devices play the phone's own music.
        for (androidx.mediarouter.media.MediaRouter.RouteInfo r : host.castSpeakers()) {
            items.add(item(r.getName())
                    .value(() -> r.isSelected() ? "✓" : "Cast")
                    .onSelect(() -> {
                        host.castTo(r);
                        host.redraw();
                    }));
        }
        for (SpotifyPlayer.Speaker sp : spotify) {
            if (sp.phone) continue;
            items.add(item(sp.name)
                    .value(() -> sp.id.equals(player.target()) ? "✓" : "Spotify")
                    .onSelect(() -> {
                        player.useSpeaker(sp);
                        host.redraw();
                    }));
        }
        items.add(item("Other Outputs").arrow().onSelect(host::showOutputSwitcher));
        return items;
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
        // Liked Songs, saved albums and followed artists, browsed like the iPod's own music.
        SpotifyLibrary lib = host.spotifyLibrary();
        lib.refresh();
        items.add(sub("Playlists", this::spotifyPlaylists));
        items.add(sub("Artists", () -> libraryList("Artists", "No Artists", () -> {
            List<ListScreen.Item> rows = new ArrayList<>();
            for (SpotifyLibrary.Artist a : lib.get().artists) rows.add(sub(a.name, () -> spotifyArtist(a)));
            return rows;
        })));
        items.add(sub("Albums", () -> libraryList("Albums", "No Albums", () -> {
            List<ListScreen.Item> rows = new ArrayList<>();
            for (SpotifyLibrary.Album a : lib.get().albums) rows.add(sub(a.name, () -> spotifyAlbum(a)));
            return rows;
        })));
        items.add(sub("Songs", () -> libraryList("Songs", "No Liked Songs",
                () -> songRows(lib.get().songs))));
        items.add(sub("Recently Liked", () -> libraryList("Recently Liked", "No Liked Songs", () -> {
            // Spotify's own order, played as Liked Songs so it carries on through all of them.
            String liked = "spotify:user:" + sp.userId() + ":collection";
            List<ListScreen.Item> rows = new ArrayList<>();
            List<SpotifyLibrary.Song> songs = lib.get().liked;
            for (int i = 0; i < songs.size(); i++) {
                SpotifyLibrary.Song song = songs.get(i);
                int at = i;
                rows.add(item(song.name).onSelect(() ->
                        host.playSpotify(SpotifyPlayer.Request.inContext(liked, song.uri, at))));
            }
            return rows;
        })));
        items.add(item("Disconnect").value(sp::userName).onSelect(host::disconnectSpotify));
        return items;
    }

    /** A list from the Spotify library, which fills in while the library is still loading. */
    private Screen libraryList(String title, String none, java.util.function.Supplier<List<ListScreen.Item>> rows) {
        SpotifyLibrary lib = host.spotifyLibrary();
        ListScreen[] s = new ListScreen[1];
        s[0] = new ListScreen(lcd, title, () -> {
            if (s[0] != null) s[0].empty(lib.loading() ? "Loading..." : none);
            return rows.get();
        });
        s[0].empty(lib.loading() ? "Loading..." : none);
        return s[0];
    }

    /** An artist: their songs, their albums, and more of their albums straight from Spotify. */
    private Screen spotifyArtist(SpotifyLibrary.Artist a) {
        List<ListScreen.Item> items = new ArrayList<>();
        if (!a.songs.isEmpty()) {
            items.add(sub("All Songs", () -> new ListScreen(lcd, a.name, songRows(a.songs))));
        }
        for (SpotifyLibrary.Album al : a.albums) items.add(sub(al.name, () -> spotifyAlbum(al)));
        if (a.id != null) {
            String path = "/artists/" + a.id + "/albums?include_groups=album,single";
            items.add(sub("More on Spotify",
                    () -> spotifyList(a.name, "No Albums", s -> rows(s, path, null, this::spotifyAlbum))));
        }
        return new ListScreen(lcd, a.name, items);
    }

    /** An album from the library: the whole album when saved, otherwise your liked songs from it. */
    private Screen spotifyAlbum(SpotifyLibrary.Album al) {
        List<ListScreen.Item> items = new ArrayList<>();
        for (SpotifyLibrary.Song song : al.songs) {
            int at = song.disc == 1 ? song.track - 1 : -1; // its place on the album
            items.add(item(song.name).onSelect(() ->
                    host.playSpotify(SpotifyPlayer.Request.inContext(al.uri, song.uri, at))));
        }
        return new ListScreen(lcd, al.name, items);
    }

    /** Songs without a playlist or album around them: picking one plays it and the ones after. */
    private List<ListScreen.Item> songRows(List<SpotifyLibrary.Song> songs) {
        List<String> uris = new ArrayList<>();
        for (SpotifyLibrary.Song song : songs) uris.add(song.uri);
        List<ListScreen.Item> rows = new ArrayList<>();
        for (int i = 0; i < songs.size(); i++) {
            int at = i;
            rows.add(item(songs.get(i).name).onSelect(() ->
                    host.playSpotify(SpotifyPlayer.Request.songs(uris, at))));
        }
        return rows;
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
        items.add(item("Play Playlist").onSelect(() ->
                host.playSpotify(SpotifyPlayer.Request.inContext(uri, null, -1))));
        items.add(item("Songs hidden by Spotify"));
        return new ListScreen(lcd, name, items);
    }

    private ListScreen.Item spotifyAlbum(JSONObject a) {
        String name = a.optString("name"), uri = a.optString("uri");
        String path = "/albums/" + a.optString("id") + "/tracks";
        return sub(name, () -> spotifySongs(name, uri, path, "No Songs"));
    }

    /** Songs of a playlist or album from Spotify; picking one plays the rest of the list too. */
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
                return item(t.optString("name")).onSelect(() ->
                        host.playSpotify(SpotifyPlayer.Request.inContext(contextUri, uri, at)));
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
