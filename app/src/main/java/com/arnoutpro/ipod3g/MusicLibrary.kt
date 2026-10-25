package com.arnoutpro.ipod3g

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.util.Locale
import java.util.TreeSet

/** Reads all music from MediaStore and answers the iPod "Browse" questions. */
class MusicLibrary {
    private val songs = ArrayList<Track>()

    fun songs(): List<Track> = songs

    fun isEmpty() = songs.isEmpty()

    fun artists() = names(songs) { it.artist }

    fun albums() = names(songs) { it.album }

    fun genres() = names(songs) { it.genre }

    fun composers() = names(songs) { it.composer }

    fun albumsOf(tracks: List<Track>) = names(tracks) { it.album }

    fun artistsOf(tracks: List<Track>) = names(tracks) { it.artist }

    fun byArtist(artist: String) = songs.filter { it.artist == artist }.sortedWith(ALBUM_ORDER)

    fun byAlbum(album: String) = songs.filter { it.album == album }.sortedWith(ALBUM_ORDER)

    fun byGenre(genre: String) = songs.filter { it.genre == genre }.sortedWith(TITLE_ORDER)

    fun byComposer(composer: String) = songs.filter { it.composer == composer }.sortedWith(TITLE_ORDER)

    fun recentlyAdded(max: Int): List<Track> = songs.sortedByDescending { it.dateAdded }.take(max)

    fun byId(id: Long): Track? = songs.firstOrNull { it.id == id }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
        const val UNKNOWN_GENRE = "Unknown Genre"
        const val UNKNOWN_COMPOSER = "Unknown Composer"

        /** Sorts like an iPod: case-insensitive and ignoring a leading "The ". */
        @JvmField
        val NAME_ORDER: Comparator<String> = compareBy { sortKey(it) }

        @JvmField
        val TITLE_ORDER: Comparator<Track> = Comparator { a, b -> NAME_ORDER.compare(a.title, b.title) }

        private val ALBUM_ORDER: Comparator<Track> = Comparator<Track> { a, b -> NAME_ORDER.compare(a.album, b.album) }
            .thenBy { it.trackNo }
            .thenComparator { a, b -> NAME_ORDER.compare(a.title, b.title) }

        @JvmStatic
        fun sortKey(s: String): String {
            val k = s.lowercase(Locale.ROOT).trim()
            return if (k.startsWith("the ")) k.substring(4) else k
        }

        @JvmStatic
        fun load(ctx: Context): MusicLibrary {
            val lib = MusicLibrary()
            val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val hasGenre = Build.VERSION.SDK_INT >= 30
            val cols = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.COMPOSER,
            )
            if (hasGenre) cols.add(MediaStore.Audio.Media.GENRE)
            try {
                ctx.contentResolver.query(base, cols.toTypedArray(), MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        lib.songs.add(
                            Track(
                                id, ContentUris.withAppendedId(base, id),
                                clean(c.getString(1), "Untitled"),
                                clean(c.getString(2), UNKNOWN_ARTIST),
                                clean(c.getString(3), UNKNOWN_ALBUM),
                                if (hasGenre) clean(c.getString(8), UNKNOWN_GENRE) else UNKNOWN_GENRE,
                                clean(c.getString(7), UNKNOWN_COMPOSER),
                                if (c.isNull(4)) 0 else c.getInt(4) % 1000, // MediaStore encodes disc*1000+track
                                c.getLong(5),
                                c.getLong(6),
                            )
                        )
                    }
                }
            } catch (e: SecurityException) {
                // No permission yet: an empty library.
            }
            lib.songs.sortWith(TITLE_ORDER)
            return lib
        }

        private fun clean(s: String?, fallback: String): String {
            val t = s?.trim() ?: return fallback
            return if (t.isEmpty() || t == "<unknown>") fallback else t
        }

        private fun names(tracks: List<Track>, key: (Track) -> String): List<String> =
            tracks.mapTo(TreeSet(NAME_ORDER)) { key(it) }.toList()
    }
}
