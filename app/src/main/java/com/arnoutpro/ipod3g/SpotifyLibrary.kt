package com.arnoutpro.ipod3g

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import com.arnoutpro.ipod3g.Spotify.Companion.stringOrNull
import java.io.IOException

/**
 * Your Spotify library (Liked Songs, saved albums and followed artists) grouped by artist and
 * album like the iPod's Browse menu. Kept in a file so browsing is instant, and refreshed in the
 * background.
 */
class SpotifyLibrary(private val spotify: Spotify, dir: File, private val onChange: Runnable) {

    class Song internal constructor(
        @JvmField val uri: String,
        @JvmField val name: String,
        @JvmField val artist: String,
        @JvmField val artistId: String?,
        @JvmField val album: String,
        @JvmField val albumUri: String,
        @JvmField val albumArtist: String,
        @JvmField val disc: Int,
        @JvmField val track: Int,
        /** The album cover's https link, or null. */
        @JvmField val image: String?,
    )

    class Album internal constructor(
        @JvmField val uri: String,
        @JvmField val name: String,
        @JvmField val artist: String,
    ) {
        /** The whole album when saved, otherwise just the liked songs from it. */
        @JvmField val songs: MutableList<Song> = ArrayList()
        internal var saved = false
    }

    class Artist internal constructor(@JvmField val name: String) {
        @JvmField var id: String? = null
        @JvmField val songs: MutableList<Song> = ArrayList()
        @JvmField val albums: MutableList<Album> = ArrayList()
    }

    /** One consistent set of lists; replaced as a whole whenever the library changes. */
    class Snapshot internal constructor(@JvmField val liked: List<Song>) {
        @JvmField val songs: MutableList<Song> = ArrayList()
        @JvmField val albums: MutableList<Album> = ArrayList()
        @JvmField val artists: MutableList<Artist> = ArrayList()
    }

    private val file = File(dir, FILE)
    private val main = Handler(Looper.getMainLooper())
    private var now = Snapshot(emptyList())
    @Volatile private var loading = false
    @Volatile private var loadedAt = 0L

    // What Spotify sent, kept to save and to rebuild the snapshot from.
    private var likedRaw: List<Song> = emptyList()
    private var savedRaw: List<Album> = emptyList()
    private var followedRaw: Map<String, String?> = emptyMap()

    init {
        // Reading a few thousand songs takes a moment: do it off the main thread, on the same
        // line as refreshes, so a refresh always comes after it.
        if (file.exists()) {
            loading = true
            spotify.callLong({ load() }) { snap, _ ->
                loading = false
                if (snap != null && now.liked.isEmpty()) now = snap
                onChange.run()
            }
        }
    }

    fun get(): Snapshot = now

    fun loading(): Boolean = loading

    /** Fetches the library again unless it was fetched in the last few minutes. */
    fun refresh() {
        if (loading || !spotify.connected()) return
        if (System.currentTimeMillis() - loadedAt < FRESH_MS && now.liked.isNotEmpty()) return
        loading = true
        val first = now.liked.isEmpty()
        spotify.callLong({ fetch(first) }) { snap, _ ->
            loading = false
            if (snap != null) {
                now = snap
                loadedAt = System.currentTimeMillis()
            }
            onChange.run()
        }
    }

    /** Forgets everything, e.g. on disconnect. */
    fun clear() {
        now = Snapshot(emptyList())
        likedRaw = emptyList()
        savedRaw = emptyList()
        followedRaw = emptyMap()
        loadedAt = 0
        file.delete()
    }

    // ---- fetching (background thread) --------------------------------------------------------

    private fun fetch(first: Boolean): Snapshot {
        val liked = ArrayList<Song>()
        var pages = 0
        spotify.all("/me/tracks", null, MAX_LIKED) { page ->
            page.mapNotNullTo(liked) { song(it.optJSONObject("track"), null) }
            // The first time, show songs while the rest is still coming in.
            if (first && ++pages % 5 == 1) {
                val partial = build(ArrayList(liked), emptyList(), emptyMap())
                main.post {
                    now = partial
                    onChange.run()
                }
            }
        }
        val saved = ArrayList<Album>()
        for (o in spotify.all("/me/albums", null)) {
            val a = o.optJSONObject("album") ?: continue
            val album = Album(a.optString("uri"), a.optString("name"), firstArtist(a).first)
            album.saved = true
            val items = a.optJSONObject("tracks")?.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) song(items!!.optJSONObject(i), a)?.let { album.songs.add(it) }
            saved.add(album)
        }
        val followed = LinkedHashMap<String, String?>()
        for (a in spotify.all("/me/following?type=artist", "artists")) followed[a.optString("name")] = a.optString("id")
        synchronized(this) {
            likedRaw = liked
            savedRaw = saved
            followedRaw = followed
            save()
        }
        return build(liked, saved, followed)
    }

    // ---- the file ----------------------------------------------------------------------------

    private fun save() {
        try {
            val o = JSONObject()
            o.put("at", System.currentTimeMillis())
            o.put("liked", toJson(likedRaw))
            val albums = JSONArray()
            for (a in savedRaw) {
                albums.put(JSONObject().put("uri", a.uri).put("name", a.name).put("artist", a.artist).put("songs", toJson(a.songs)))
            }
            o.put("albums", albums)
            val followed = JSONArray()
            for ((name, id) in followedRaw) followed.put(JSONArray().put(name).put(id))
            o.put("followed", followed)
            file.writeText(o.toString(), Charsets.UTF_8)
        } catch (ignored: IOException) {
            // Only a cache: the next refresh fetches it again.
        } catch (ignored: JSONException) {
        }
    }

    /** Reads the saved library; background thread. */
    private fun load(): Snapshot? = try {
        val o = JSONObject(file.readText(Charsets.UTF_8))
        val liked = fromJson(o.getJSONArray("liked"))
        val saved = ArrayList<Album>()
        val albums = o.getJSONArray("albums")
        for (i in 0 until albums.length()) {
            val a = albums.getJSONObject(i)
            val album = Album(a.getString("uri"), a.getString("name"), a.getString("artist"))
            album.saved = true
            album.songs.addAll(fromJson(a.getJSONArray("songs")))
            saved.add(album)
        }
        val followed = LinkedHashMap<String, String?>()
        val f = o.getJSONArray("followed")
        for (i in 0 until f.length()) followed[f.getJSONArray(i).getString(0)] = f.getJSONArray(i).optString(1, null)
        synchronized(this) {
            likedRaw = liked
            savedRaw = saved
            followedRaw = followed
        }
        // Saved before covers were kept: fetch again on the next refresh to get them.
        loadedAt = if (liked.isNotEmpty() && liked.all { it.image == null }) 0 else o.optLong("at")
        build(liked, saved, followed)
    } catch (e: IOException) {
        file.delete()
        null
    } catch (e: JSONException) {
        file.delete()
        null
    }

    private companion object {
        const val FILE = "spotify-library.json"
        const val FRESH_MS = 10 * 60_000L
        const val MAX_LIKED = 10_000

        val TRACK_ORDER = compareBy<Song>({ it.disc }, { it.track })

        /** A song from a track object; album is given for album track lists, which leave it out. */
        fun song(t: JSONObject?, album: JSONObject?): Song? {
            if (t == null || t.optBoolean("is_local")) return null
            val uri = t.optString("uri")
            if (uri.isEmpty() || uri == "null") return null
            val a = album ?: t.optJSONObject("album")
            val (artist, artistId) = firstArtist(t)
            return Song(
                uri, t.optString("name"), artist, artistId,
                a?.optString("name") ?: "", a?.optString("uri") ?: "",
                a?.let { firstArtist(it).first } ?: artist,
                t.optInt("disc_number", 1), t.optInt("track_number"),
                Covers.pick(a?.optJSONArray("images")),
            )
        }

        fun firstArtist(o: JSONObject): Pair<String, String?> {
            val artists = o.optJSONArray("artists")
            val a = if (artists != null && artists.length() > 0) artists.optJSONObject(0) else null
            return if (a == null) MusicLibrary.UNKNOWN_ARTIST to null else a.optString("name") to a.stringOrNull("id")
        }

        // ---- grouping ------------------------------------------------------------------------

        fun build(liked: List<Song>, saved: List<Album>, followed: Map<String, String?>): Snapshot {
            val s = Snapshot(liked)
            val albums = LinkedHashMap<String, Album>()
            for (a in saved) {
                val copy = Album(a.uri, a.name, a.artist)
                copy.saved = true
                copy.songs.addAll(a.songs)
                albums[a.uri] = copy
            }
            for (song in liked) {
                val a = albums.getOrPut(song.albumUri) { Album(song.albumUri, song.album, song.albumArtist) }
                if (!a.saved) a.songs.add(song)
            }

            val artists = HashMap<String, Artist>()
            val seen = HashMap<Artist, MutableSet<String>>()
            fun artist(name: String) = artists.getOrPut(MusicLibrary.sortKey(name)) { Artist(name) }
            fun addSong(ar: Artist, song: Song) {
                if (ar.id == null) ar.id = song.artistId
                if (seen.getOrPut(ar) { HashSet() }.add(song.uri)) ar.songs.add(song)
            }
            for (song in liked) {
                val ar = artist(song.artist)
                addSong(ar, song)
                val a = albums[song.albumUri]
                if (a != null && a !in ar.albums) ar.albums.add(a)
            }
            for (a in albums.values) {
                a.songs.sortWith(TRACK_ORDER)
                val ar = artist(a.artist)
                if (a !in ar.albums) ar.albums.add(a)
                if (!a.saved) continue
                for (song in a.songs) addSong(artist(song.artist), song)
            }
            for ((name, id) in followed) artist(name).id = id

            for (ar in artists.values) {
                ar.albums.sortWith { x, y -> MusicLibrary.NAME_ORDER.compare(x.name, y.name) }
                ar.songs.sortWith { x, y ->
                    val c = MusicLibrary.NAME_ORDER.compare(x.album, y.album)
                    if (c != 0) c else TRACK_ORDER.compare(x, y)
                }
            }
            s.artists.addAll(artists.values)
            s.artists.sortWith { x, y -> MusicLibrary.NAME_ORDER.compare(x.name, y.name) }
            s.albums.addAll(albums.values)
            s.albums.sortWith { x, y -> MusicLibrary.NAME_ORDER.compare(x.name, y.name) }
            s.songs.addAll(liked)
            s.songs.sortWith { x, y -> MusicLibrary.NAME_ORDER.compare(x.name, y.name) }
            return s
        }

        fun toJson(list: List<Song>): JSONArray {
            val a = JSONArray()
            for (s in list) {
                a.put(
                    JSONArray().put(s.uri).put(s.name).put(s.artist).put(s.artistId ?: "")
                        .put(s.album).put(s.albumUri).put(s.albumArtist).put(s.disc).put(s.track).put(s.image ?: "")
                )
            }
            return a
        }

        fun fromJson(a: JSONArray): List<Song> = (0 until a.length()).map { i ->
            val s = a.getJSONArray(i)
            val id = s.getString(3)
            Song(
                s.getString(0), s.getString(1), s.getString(2), id.ifEmpty { null },
                s.getString(4), s.getString(5), s.getString(6), s.getInt(7), s.getInt(8),
                s.optString(9).ifEmpty { null },
            )
        }
    }
}
