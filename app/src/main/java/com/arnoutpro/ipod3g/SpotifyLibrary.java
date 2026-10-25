package com.arnoutpro.ipod3g;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Your Spotify library (Liked Songs, saved albums and followed artists) grouped by artist and
 * album like the iPod's Browse menu. Kept in a file so browsing is instant, and refreshed in the
 * background.
 */
public final class SpotifyLibrary {
    private static final String FILE = "spotify-library.json";
    private static final long FRESH_MS = 10 * 60_000;
    private static final int MAX_LIKED = 10_000;

    public static final class Song {
        public final String uri, name, artist, artistId, album, albumUri, albumArtist;
        final int disc, track;

        Song(String uri, String name, String artist, String artistId, String album, String albumUri,
             String albumArtist, int disc, int track) {
            this.uri = uri;
            this.name = name;
            this.artist = artist;
            this.artistId = artistId;
            this.album = album;
            this.albumUri = albumUri;
            this.albumArtist = albumArtist;
            this.disc = disc;
            this.track = track;
        }
    }

    public static final class Album {
        public final String uri, name, artist;
        /** The whole album when saved, otherwise just the liked songs from it. */
        public final List<Song> songs = new ArrayList<>();
        boolean saved;

        Album(String uri, String name, String artist) {
            this.uri = uri;
            this.name = name;
            this.artist = artist;
        }
    }

    public static final class Artist {
        public final String name;
        public String id;
        public final List<Song> songs = new ArrayList<>();
        public final List<Album> albums = new ArrayList<>();

        Artist(String name) { this.name = name; }
    }

    /** One consistent set of lists; replaced as a whole whenever the library changes. */
    public static final class Snapshot {
        public final List<Song> liked;
        public final List<Song> songs = new ArrayList<>();
        public final List<Album> albums = new ArrayList<>();
        public final List<Artist> artists = new ArrayList<>();

        Snapshot(List<Song> liked) { this.liked = liked; }
    }

    private static final Comparator<Song> TRACK_ORDER = (a, b) -> a.disc != b.disc
            ? Integer.compare(a.disc, b.disc) : Integer.compare(a.track, b.track);

    private final Spotify spotify;
    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onChange;
    private Snapshot now = new Snapshot(new ArrayList<>());
    private volatile boolean loading;
    private volatile long loadedAt;

    // What Spotify sent, kept to save and to rebuild the snapshot from.
    private List<Song> likedRaw = new ArrayList<>();
    private List<Album> savedRaw = new ArrayList<>();
    private Map<String, String> followedRaw = new LinkedHashMap<>();

    public SpotifyLibrary(Spotify spotify, File dir, Runnable onChange) {
        this.spotify = spotify;
        this.file = new File(dir, FILE);
        this.onChange = onChange;
        // Reading a few thousand songs takes a moment: do it off the main thread, on the same
        // thread as refreshes, so a refresh always comes after it.
        if (file.exists()) {
            loading = true;
            spotify.callLong(this::load, (snap, err) -> {
                loading = false;
                if (snap != null && now.liked.isEmpty()) now = snap;
                onChange.run();
            });
        }
    }

    public Snapshot get() { return now; }

    public boolean loading() { return loading; }

    /** Fetches the library again unless it was fetched in the last few minutes. */
    public void refresh() {
        if (loading || !spotify.connected()) return;
        if (System.currentTimeMillis() - loadedAt < FRESH_MS && !now.liked.isEmpty()) return;
        loading = true;
        boolean first = now.liked.isEmpty();
        spotify.callLong(() -> fetch(first), (snap, err) -> {
            loading = false;
            if (snap != null) {
                now = snap;
                loadedAt = System.currentTimeMillis();
            }
            onChange.run();
        });
    }

    /** Forgets everything, e.g. on disconnect. */
    public void clear() {
        now = new Snapshot(new ArrayList<>());
        likedRaw = new ArrayList<>();
        savedRaw = new ArrayList<>();
        followedRaw = new LinkedHashMap<>();
        loadedAt = 0;
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // ---- fetching (background thread) --------------------------------------------------------

    private Snapshot fetch(boolean first) throws IOException, JSONException {
        List<Song> liked = new ArrayList<>();
        int[] pages = {0};
        spotify.all("/me/tracks", null, MAX_LIKED, page -> {
            for (JSONObject o : page) {
                Song s = song(o.optJSONObject("track"), null);
                if (s != null) liked.add(s);
            }
            // The first time, show songs while the rest is still coming in.
            if (first && ++pages[0] % 5 == 1) {
                Snapshot partial = build(new ArrayList<>(liked), new ArrayList<>(), new LinkedHashMap<>());
                main.post(() -> {
                    now = partial;
                    onChange.run();
                });
            }
        });
        List<Album> saved = new ArrayList<>();
        for (JSONObject o : spotify.all("/me/albums", null)) {
            JSONObject a = o.optJSONObject("album");
            if (a == null) continue;
            Album album = new Album(a.optString("uri"), a.optString("name"), firstArtist(a)[0]);
            album.saved = true;
            JSONObject tracks = a.optJSONObject("tracks");
            JSONArray items = tracks == null ? null : tracks.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                Song s = song(items.optJSONObject(i), a);
                if (s != null) album.songs.add(s);
            }
            saved.add(album);
        }
        Map<String, String> followed = new LinkedHashMap<>();
        for (JSONObject a : spotify.all("/me/following?type=artist", "artists")) {
            followed.put(a.optString("name"), a.optString("id"));
        }
        synchronized (this) {
            likedRaw = liked;
            savedRaw = saved;
            followedRaw = followed;
            save();
        }
        return build(liked, saved, followed);
    }

    /** A song from a track object; album is given for album track lists, which leave it out. */
    private static Song song(JSONObject t, JSONObject album) {
        if (t == null || t.optBoolean("is_local")) return null;
        String uri = t.optString("uri");
        if (uri.isEmpty() || uri.equals("null")) return null;
        JSONObject a = album != null ? album : t.optJSONObject("album");
        String[] artist = firstArtist(t);
        return new Song(uri, t.optString("name"), artist[0], artist[1],
                a == null ? "" : a.optString("name"), a == null ? "" : a.optString("uri"),
                a == null ? artist[0] : firstArtist(a)[0],
                t.optInt("disc_number", 1), t.optInt("track_number"));
    }

    private static String[] firstArtist(JSONObject o) {
        JSONArray artists = o.optJSONArray("artists");
        JSONObject a = artists != null && artists.length() > 0 ? artists.optJSONObject(0) : null;
        return a == null ? new String[]{MusicLibrary.UNKNOWN_ARTIST, null}
                : new String[]{a.optString("name"), a.optString("id", null)};
    }

    // ---- grouping ----------------------------------------------------------------------------

    private static Snapshot build(List<Song> liked, List<Album> saved, Map<String, String> followed) {
        Snapshot s = new Snapshot(liked);
        Map<String, Album> albums = new LinkedHashMap<>();
        for (Album a : saved) {
            Album copy = new Album(a.uri, a.name, a.artist);
            copy.saved = true;
            copy.songs.addAll(a.songs);
            albums.put(a.uri, copy);
        }
        for (Song song : liked) {
            Album a = albums.get(song.albumUri);
            if (a == null) {
                a = new Album(song.albumUri, song.album, song.albumArtist);
                albums.put(song.albumUri, a);
            }
            if (!a.saved) a.songs.add(song);
        }

        Map<String, Artist> artists = new HashMap<>();
        Map<Artist, Set<String>> seen = new HashMap<>();
        for (Song song : liked) {
            Artist ar = artist(artists, song.artist);
            if (ar.id == null) ar.id = song.artistId;
            if (seen.computeIfAbsent(ar, k -> new HashSet<>()).add(song.uri)) ar.songs.add(song);
            Album a = albums.get(song.albumUri);
            if (a != null && !ar.albums.contains(a)) ar.albums.add(a);
        }
        for (Album a : albums.values()) {
            Collections.sort(a.songs, TRACK_ORDER);
            Artist ar = artist(artists, a.artist);
            if (!ar.albums.contains(a)) ar.albums.add(a);
            if (!a.saved) continue;
            for (Song song : a.songs) {
                Artist sa = artist(artists, song.artist);
                if (sa.id == null) sa.id = song.artistId;
                if (seen.computeIfAbsent(sa, k -> new HashSet<>()).add(song.uri)) sa.songs.add(song);
            }
        }
        for (Map.Entry<String, String> f : followed.entrySet()) artist(artists, f.getKey()).id = f.getValue();

        for (Artist ar : artists.values()) {
            ar.albums.sort((x, y) -> MusicLibrary.NAME_ORDER.compare(x.name, y.name));
            ar.songs.sort((x, y) -> {
                int c = MusicLibrary.NAME_ORDER.compare(x.album, y.album);
                return c != 0 ? c : TRACK_ORDER.compare(x, y);
            });
        }
        s.artists.addAll(artists.values());
        s.artists.sort((x, y) -> MusicLibrary.NAME_ORDER.compare(x.name, y.name));
        s.albums.addAll(albums.values());
        s.albums.sort((x, y) -> MusicLibrary.NAME_ORDER.compare(x.name, y.name));
        s.songs.addAll(liked);
        s.songs.sort((x, y) -> MusicLibrary.NAME_ORDER.compare(x.name, y.name));
        return s;
    }

    private static Artist artist(Map<String, Artist> artists, String name) {
        return artists.computeIfAbsent(MusicLibrary.sortKey(name), k -> new Artist(name));
    }

    // ---- the file ----------------------------------------------------------------------------

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("at", System.currentTimeMillis());
            o.put("liked", songs(likedRaw));
            JSONArray albums = new JSONArray();
            for (Album a : savedRaw) {
                albums.put(new JSONObject().put("uri", a.uri).put("name", a.name)
                        .put("artist", a.artist).put("songs", songs(a.songs)));
            }
            o.put("albums", albums);
            JSONArray followed = new JSONArray();
            for (Map.Entry<String, String> f : followedRaw.entrySet()) {
                followed.put(new JSONArray().put(f.getKey()).put(f.getValue()));
            }
            o.put("followed", followed);
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException ignored) {
            // Only a cache: the next refresh fetches it again.
        }
    }

    /** Reads the saved library; background thread. */
    private Snapshot load() {
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int n = 0;
            for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
            JSONObject o = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
            List<Song> liked = songs(o.getJSONArray("liked"));
            List<Album> saved = new ArrayList<>();
            JSONArray albums = o.getJSONArray("albums");
            for (int i = 0; i < albums.length(); i++) {
                JSONObject a = albums.getJSONObject(i);
                Album album = new Album(a.getString("uri"), a.getString("name"), a.getString("artist"));
                album.saved = true;
                album.songs.addAll(songs(a.getJSONArray("songs")));
                saved.add(album);
            }
            Map<String, String> followed = new LinkedHashMap<>();
            JSONArray f = o.getJSONArray("followed");
            for (int i = 0; i < f.length(); i++) {
                followed.put(f.getJSONArray(i).getString(0), f.getJSONArray(i).optString(1, null));
            }
            synchronized (this) {
                likedRaw = liked;
                savedRaw = saved;
                followedRaw = followed;
            }
            loadedAt = o.optLong("at");
            return build(liked, saved, followed);
        } catch (IOException | JSONException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    private static JSONArray songs(List<Song> list) {
        JSONArray a = new JSONArray();
        for (Song s : list) {
            a.put(new JSONArray().put(s.uri).put(s.name).put(s.artist).put(s.artistId == null ? "" : s.artistId)
                    .put(s.album).put(s.albumUri).put(s.albumArtist).put(s.disc).put(s.track));
        }
        return a;
    }

    private static List<Song> songs(JSONArray a) throws JSONException {
        List<Song> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONArray s = a.getJSONArray(i);
            String id = s.getString(3);
            out.add(new Song(s.getString(0), s.getString(1), s.getString(2), id.isEmpty() ? null : id,
                    s.getString(4), s.getString(5), s.getString(6), s.getInt(7), s.getInt(8)));
        }
        return out;
    }
}
