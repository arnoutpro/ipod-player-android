package com.arnoutpro.ipod3g;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Reads all music from MediaStore and answers the iPod "Browse" questions. */
public final class MusicLibrary {
    public static final String UNKNOWN_ARTIST = "Unknown Artist";
    public static final String UNKNOWN_ALBUM = "Unknown Album";
    public static final String UNKNOWN_GENRE = "Unknown Genre";
    public static final String UNKNOWN_COMPOSER = "Unknown Composer";

    private final List<Track> songs = new ArrayList<>();

    /** Sorts like an iPod: case-insensitive and ignoring a leading "The ". */
    public static final Comparator<String> NAME_ORDER = (a, b) -> sortKey(a).compareTo(sortKey(b));

    public static final Comparator<Track> TITLE_ORDER = (a, b) -> NAME_ORDER.compare(a.title, b.title);

    private static final Comparator<Track> ALBUM_ORDER = (a, b) -> {
        int c = NAME_ORDER.compare(a.album, b.album);
        if (c != 0) return c;
        c = Integer.compare(a.trackNo, b.trackNo);
        return c != 0 ? c : NAME_ORDER.compare(a.title, b.title);
    };

    static String sortKey(String s) {
        String k = s.toLowerCase(Locale.ROOT).trim();
        if (k.startsWith("the ")) k = k.substring(4);
        return k;
    }

    public static MusicLibrary load(Context ctx) {
        MusicLibrary lib = new MusicLibrary();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        boolean hasGenre = Build.VERSION.SDK_INT >= 30;
        List<String> cols = new ArrayList<>();
        cols.add(MediaStore.Audio.Media._ID);
        cols.add(MediaStore.Audio.Media.TITLE);
        cols.add(MediaStore.Audio.Media.ARTIST);
        cols.add(MediaStore.Audio.Media.ALBUM);
        cols.add(MediaStore.Audio.Media.TRACK);
        cols.add(MediaStore.Audio.Media.DURATION);
        cols.add(MediaStore.Audio.Media.DATE_ADDED);
        cols.add(MediaStore.Audio.Media.COMPOSER);
        if (hasGenre) cols.add(MediaStore.Audio.Media.GENRE);

        try (Cursor c = ctx.getContentResolver().query(base, cols.toArray(new String[0]),
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)) {
            if (c == null) return lib;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String title = clean(c.getString(1), "Untitled");
                String artist = clean(c.getString(2), UNKNOWN_ARTIST);
                String album = clean(c.getString(3), UNKNOWN_ALBUM);
                int track = c.isNull(4) ? 0 : c.getInt(4) % 1000; // MediaStore encodes disc*1000+track
                long dur = c.getLong(5);
                long added = c.getLong(6);
                String composer = clean(c.getString(7), UNKNOWN_COMPOSER);
                String genre = hasGenre ? clean(c.getString(8), UNKNOWN_GENRE) : UNKNOWN_GENRE;
                lib.songs.add(new Track(id, ContentUris.withAppendedId(base, id), title, artist,
                        album, genre, composer, track, dur, added));
            }
        } catch (SecurityException e) {
            // No permission yet: an empty library.
        }
        lib.songs.sort(TITLE_ORDER);
        return lib;
    }

    private static String clean(String s, String fallback) {
        if (s == null) return fallback;
        s = s.trim();
        if (s.isEmpty() || s.equals("<unknown>")) return fallback;
        return s;
    }

    public List<Track> songs() { return Collections.unmodifiableList(songs); }

    public boolean isEmpty() { return songs.isEmpty(); }

    public List<String> artists() { return names(songs, t -> t.artist); }

    public List<String> albums() { return names(songs, t -> t.album); }

    public List<String> genres() { return names(songs, t -> t.genre); }

    public List<String> composers() { return names(songs, t -> t.composer); }

    public List<String> albumsOf(List<Track> tracks) { return names(tracks, t -> t.album); }

    public List<String> artistsOf(List<Track> tracks) { return names(tracks, t -> t.artist); }

    public List<Track> byArtist(String artist) { return filter(t -> t.artist.equals(artist), ALBUM_ORDER); }

    public List<Track> byAlbum(String album) { return filter(t -> t.album.equals(album), ALBUM_ORDER); }

    public List<Track> byGenre(String genre) { return filter(t -> t.genre.equals(genre), TITLE_ORDER); }

    public List<Track> byComposer(String composer) { return filter(t -> t.composer.equals(composer), TITLE_ORDER); }

    public List<Track> recentlyAdded(int max) {
        List<Track> out = new ArrayList<>(songs);
        out.sort((a, b) -> Long.compare(b.dateAdded, a.dateAdded));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    public Track byId(long id) {
        for (Track t : songs) if (t.id == id) return t;
        return null;
    }

    public interface Pred { boolean test(Track t); }

    private interface Key { String of(Track t); }

    private List<Track> filter(Pred p, Comparator<Track> order) {
        List<Track> out = new ArrayList<>();
        for (Track t : songs) if (p.test(t)) out.add(t);
        out.sort(order);
        return out;
    }

    private static List<String> names(List<Track> tracks, Key key) {
        Set<String> set = new TreeSet<>(NAME_ORDER);
        for (Track t : tracks) set.add(key.of(t));
        return new ArrayList<>(new LinkedHashSet<>(set));
    }
}
