package com.arnoutpro.ipod3g;

import android.net.Uri;

/** One song from the device's MediaStore. */
public final class Track {
    public final long id;
    public final Uri uri;
    public final String title;
    public final String artist;
    public final String album;
    public final String genre;
    public final String composer;
    public final int trackNo;
    public final long durationMs;
    public final long dateAdded;

    public Track(long id, Uri uri, String title, String artist, String album, String genre,
                 String composer, int trackNo, long durationMs, long dateAdded) {
        this.id = id;
        this.uri = uri;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.genre = genre;
        this.composer = composer;
        this.trackNo = trackNo;
        this.durationMs = durationMs;
        this.dateAdded = dateAdded;
    }
}
