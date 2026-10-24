package com.arnoutpro.ipod3g;

import android.content.SharedPreferences;

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
        PlayerService ps = host.service();
        if (ps != null && ps.hasTrack()) items.add(sub("Now Playing", () -> new NowPlayingScreen(lcd)));
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
