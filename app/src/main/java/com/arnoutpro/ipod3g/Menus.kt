package com.arnoutpro.ipod3g

import android.content.pm.PackageManager
import androidx.mediarouter.media.MediaRouter
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Builds the 3rd generation iPod menu tree. */
class Menus(private val lcd: Lcd) {
    private val host = lcd.host
    private val prefs = host.prefs()

    private fun item(label: String) = ListScreen.Item(label)

    private fun sub(label: String, screen: () -> Screen) = item(label).arrow().onSelect { lcd.push(screen()) }

    // ---- roots -----------------------------------------------------------------------------

    fun root(): Screen {
        if (!host.hasMusicPermission()) {
            return ListScreen(lcd, "iPod", listOf(item("Allow Music Access").arrow().onSelect { host.requestMusicPermission() }))
        }
        return ListScreen(lcd, "iPod") { mainItems() }
    }

    private fun mainItems(): List<ListScreen.Item> {
        val items = mutableListOf(
            sub("Playlists", ::playlists),
            sub("Browse", ::browse),
            sub("Spotify", ::spotify),
            sub("Extras", ::extras),
            sub("Settings", ::settings),
            item("Shuffle Songs").onSelect {
                val ps = host.service()
                val all = host.library().songs()
                if (ps == null || all.isEmpty()) return@onSelect
                ps.shuffleAll(all)
                lcd.showNowPlaying()
            },
            item("Backlight").onSelect { host.toggleBacklight() },
        )
        if (host.playback()?.hasTrack() == true) items.add(sub("Now Playing") { NowPlayingScreen(lcd) })
        return items
    }

    // ---- playlists -------------------------------------------------------------------------

    private fun playlists(): Screen = ListScreen(
        lcd, "Playlists",
        listOf(
            sub("On-The-Go", ::onTheGo),
            sub("Recently Added") { songs("Recently Added", host.library().recentlyAdded(100), false) },
            sub("Top 25 Most Played") { songs("Top 25 Most Played", topPlayed(), false) },
        ),
    )

    private fun topPlayed(): List<Track> = host.library().songs()
        .map { it to PlayerService.playCount(host, it) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(25)
        .map { it.first }

    private fun onTheGoTracks(): List<Track> = prefs.getString("otg", "")!!.split(",")
        .mapNotNull { it.toLongOrNull() }
        .mapNotNull { host.library().byId(it) }

    private fun addToOnTheGo(t: Track) {
        val ids = prefs.getString("otg", "")!!
        prefs.edit().putString("otg", if (ids.isEmpty()) t.id.toString() else "$ids,${t.id}").apply()
        host.buzz()
    }

    private fun onTheGo(): Screen = ListScreen(lcd, "On-The-Go") {
        val list = onTheGoTracks()
        val items = songItems(list)
        if (list.isNotEmpty()) {
            items.add(
                item("Clear Playlist").onSelect {
                    prefs.edit().remove("otg").apply()
                    lcd.pop()
                }
            )
        }
        items
    }.empty("Hold Select on a song")

    // ---- speakers --------------------------------------------------------------------------

    /**
     * Settings > Speakers, also opened by holding the centre button on Now Playing. Where the
     * music plays: this phone, a Spotify Connect device (for Spotify), or another output like
     * Bluetooth picked in Android's own switcher (for everything the phone plays).
     */
    fun speakers(): Screen {
        var spotify: List<SpotifyPlayer.Speaker> = emptyList()
        val s = ListScreen(lcd, "Speakers", speakerItems(spotify))
        val rebuild = Runnable {
            s.setItems(speakerItems(spotify))
            host.redraw()
        }
        host.findCastSpeakers(rebuild)
        host.findSonos(rebuild)
        if (host.spotify().connected()) {
            val player = host.spotifyPlayer()
            host.spotify().call(player::speakers) { list, _ ->
                if (list != null) spotify = list
                rebuild.run()
            }
        }
        return s
    }

    private fun casting() = host.service()?.speaker() != null

    private fun noSpotifyHere() {
        lcd.push(MessageScreen(lcd, "Speakers", "Spotify doesn't play on Cast speakers. This one plays your own music."))
    }

    private fun speakerItems(spotify: List<SpotifyPlayer.Speaker>): List<ListScreen.Item> {
        val player = host.spotifyPlayer()
        val items = ArrayList<ListScreen.Item>()
        items.add(
            item("This Phone")
                .value { if (player.target() == null && player.sonosTarget() == null && !casting()) "✓" else "" }
                .onSelect {
                    player.useSpeaker(null)
                    if (casting()) host.service()?.stopCasting()
                    host.redraw()
                }
        )
        // A Sonos group plays the phone's own music from the iPod's server, and Spotify through
        // the speakers' own Spotify account: picked while Spotify plays, it's Spotify's speaker.
        for (g in host.sonosSpeakers()) {
            items.add(
                item(g.name)
                    .value {
                        val on = if (host.spotifyPlaying()) player.sonosTarget() == g else host.service()?.castingTo(g) == true
                        if (on) "✓" else "Sonos"
                    }
                    .onSelect {
                        if (host.spotifyPlaying()) player.useSonos(g) else host.service()?.castToSonos(g)
                        host.redraw()
                    }
            )
        }
        // Cast devices play the phone's own music only: Spotify streams into the Spotify app, so
        // there is nothing to send them.
        for (r: MediaRouter.RouteInfo in host.castSpeakers()) {
            items.add(
                item(r.name)
                    .value { if (r.isSelected) "✓" else if (host.spotifyPlaying()) "No Spotify" else "Cast" }
                    .onSelect {
                        if (host.spotifyPlaying()) noSpotifyHere() else host.castTo(r)
                        host.redraw()
                    }
            )
        }
        for (sp in spotify) {
            if (sp.phone) continue
            items.add(
                item(sp.name)
                    .value { if (sp.id == player.target()) "✓" else "Spotify" }
                    .onSelect {
                        player.useSpeaker(sp)
                        host.redraw()
                    }
            )
        }
        items.add(item("Other Outputs").arrow().onSelect { host.showOutputSwitcher() })
        return items
    }

    // ---- spotify ---------------------------------------------------------------------------

    private fun spotify(): Screen = ListScreen(lcd, "Spotify") { spotifyItems() }

    private fun spotifyItems(): List<ListScreen.Item> {
        val sp = host.spotify()
        val items = ArrayList<ListScreen.Item>()
        if (!sp.connected()) {
            items.add(item("Connect to Spotify").arrow().onSelect { host.connectSpotify() })
            host.spotifyError()?.let { items.add(item(it)) }
            return items
        }
        // Liked Songs, saved albums and followed artists, browsed like the iPod's own music.
        val lib = host.spotifyLibrary()
        lib.refresh()
        if (host.sixthGen()) {
            items.add(
                sub("Cover Flow") {
                    CoverFlowScreen(lcd) {
                        lib.get().albums.map { a ->
                            CoverFlowScreen.Album(a.name, a.artist, a.songs.firstNotNullOfOrNull { it.image }) { spotifyAlbum(a) }
                        }
                    }
                }
            )
        }
        items.add(sub("Playlists", ::spotifyPlaylists))
        items.add(
            sub("Artists") {
                libraryList("Artists", "No Artists") { lib.get().artists.map { a -> sub(a.name) { spotifyArtist(a) } } }
            }
        )
        items.add(
            sub("Albums") {
                libraryList("Albums", "No Albums") { lib.get().albums.map { a -> sub(a.name) { spotifyAlbum(a) } } }
            }
        )
        items.add(sub("Songs") { libraryList("Songs", "No Liked Songs") { songRows(lib.get().songs) } })
        items.add(
            sub("Recently Liked") {
                libraryList("Recently Liked", "No Liked Songs") {
                    // Spotify's own order, played as Liked Songs so it carries on through all of them.
                    val liked = "spotify:user:" + sp.userId() + ":collection"
                    lib.get().liked.mapIndexed { at, song ->
                        spotifySong(song.name, song.uri, SpotifyPlayer.Request.inContext(liked, song.uri, at))
                    }
                }
            }
        )
        items.add(item("Disconnect").value { sp.userName() }.onSelect { host.disconnectSpotify() })
        return items
    }

    /** A list from the Spotify library, which fills in while the library is still loading. */
    private fun libraryList(title: String, none: String, rows: () -> List<ListScreen.Item>): Screen {
        val lib = host.spotifyLibrary()
        var s: ListScreen? = null
        s = ListScreen(lcd, title) {
            s?.empty(if (lib.loading()) "Loading..." else none)
            rows()
        }
        return s.empty(if (lib.loading()) "Loading..." else none)
    }

    /** An artist: their radio, songs, albums, and more of their albums straight from Spotify. */
    private fun spotifyArtist(a: SpotifyLibrary.Artist): Screen {
        val items = ArrayList<ListScreen.Item>()
        a.id?.let { id -> items.add(item("Start Radio").onSelect { radio("spotify:artist:$id", a.name) }) }
        if (a.songs.isNotEmpty()) items.add(sub("All Songs") { ListScreen(lcd, a.name, songRows(a.songs)) })
        for (al in a.albums) items.add(sub(al.name) { spotifyAlbum(al) })
        a.id?.let { id ->
            val path = "/artists/$id/albums?include_groups=album,single"
            items.add(sub("More on Spotify") { spotifyList(a.name, "No Albums") { s -> rows(s, path, ::spotifyAlbum) } })
        }
        return ListScreen(lcd, a.name, items)
    }

    /** An album from the library: the whole album when saved, otherwise your liked songs from it. */
    private fun spotifyAlbum(al: SpotifyLibrary.Album): Screen = ListScreen(
        lcd, al.name,
        al.songs.map { song ->
            val at = if (song.disc == 1) song.track - 1 else -1 // its place on the album
            spotifySong(song.name, song.uri, SpotifyPlayer.Request.inContext(al.uri, song.uri, at))
        },
    )

    /** Songs without a playlist or album around them: picking one plays it and the ones after. */
    private fun songRows(songs: List<SpotifyLibrary.Song>): List<ListScreen.Item> {
        val uris = songs.map { it.uri }
        return songs.mapIndexed { at, song -> spotifySong(song.name, song.uri, SpotifyPlayer.Request.songs(uris, at)) }
    }

    /** A Spotify song row: select plays it, holding the centre button starts its radio. */
    private fun spotifySong(name: String, uri: String, play: SpotifyPlayer.Request) =
        item(name).onSelect { host.playSpotify(play) }.onHold { radio(uri, name) }

    /** Plays Spotify's radio for an artist or song link; name is what Now Playing calls it after. */
    private fun radio(uri: String, name: String) {
        host.buzz()
        host.playSpotify(SpotifyPlayer.Request.radio(uri, name))
    }

    /** A list that fills in as Spotify answers; errors show where the rows would be. */
    private fun spotifyList(title: String, empty: String, load: (ListScreen) -> List<ListScreen.Item>): Screen {
        val s = ListScreen(lcd, title, emptyList()).empty("Loading...")
        host.spotify().call({ load(s) }) { items, err ->
            s.empty(err ?: empty)
            if (items != null) s.setItems(items)
            host.redraw()
        }
        return s
    }

    /** Turns a paged Spotify list into rows, showing each page as soon as it arrives. */
    @Throws(IOException::class, JSONException::class)
    private fun rows(s: ListScreen, path: String, row: (JSONObject) -> ListScreen.Item?): List<ListScreen.Item> {
        val items = ArrayList<ListScreen.Item>()
        host.spotify().all(path, null) { page ->
            page.mapNotNullTo(items, row)
            val sofar = ArrayList(items)
            host.runOnUiThread {
                s.setItems(sofar)
                host.redraw()
            }
        }
        return items
    }

    private fun spotifyPlaylists(): Screen {
        val sp = host.spotify()
        return spotifyList("Playlists", "No Playlists") { s ->
            rows(s, "/me/playlists") { p ->
                val name = p.optString("name")
                val uri = p.optString("uri")
                // Spotify only lets this app read the songs of playlists you own or share.
                val mine = p.optBoolean("collaborative") || p.optJSONObject("owner")?.optString("id") == sp.userId()
                val path = "/playlists/" + p.optString("id") + "/items"
                sub(name) { if (mine) spotifySongs(name, uri, path, "No Songs") else followed(name, uri) }
            }
        }
    }

    /** A playlist you follow: Spotify hides its songs from this app but still plays it. */
    private fun followed(name: String, uri: String): Screen = ListScreen(
        lcd, name,
        listOf(
            item("Play Playlist").onSelect { host.playSpotify(SpotifyPlayer.Request.inContext(uri, null, -1)) },
            item("Songs hidden by Spotify"),
        ),
    )

    private fun spotifyAlbum(a: JSONObject): ListScreen.Item {
        val name = a.optString("name")
        val uri = a.optString("uri")
        val path = "/albums/" + a.optString("id") + "/tracks"
        return sub(name) { spotifySongs(name, uri, path, "No Songs") }
    }

    /** Songs of a playlist or album from Spotify; picking one plays the rest of the list too. */
    private fun spotifySongs(title: String, contextUri: String, path: String, empty: String): Screen =
        spotifyList(title, empty) { s ->
            var local = 0
            var position = 0
            val items = rows(s, path) { o ->
                val at = position++ // its place in the playlist or album, local files included
                // Playlists wrap each song in "item" (formerly "track"); albums list them bare.
                val t = o.optJSONObject("item") ?: o.optJSONObject("track") ?: o
                val uri = t.optString("uri")
                if (o.optBoolean("is_local") || t.optBoolean("is_local")) {
                    local++
                    null
                } else if (uri.isEmpty() || uri == "null") {
                    null
                } else {
                    spotifySong(t.optString("name"), uri, SpotifyPlayer.Request.inContext(contextUri, uri, at))
                }
            }
            // Files from your own computer live in the playlist but can't be streamed.
            if (items.isEmpty() && local > 0) items + item("Only local files") else items
        }

    // ---- browse ----------------------------------------------------------------------------

    private fun browse(): Screen = ListScreen(
        lcd, "Browse",
        listOfNotNull(
            // The iPod classic's album carousel, in the look it came with.
            if (host.sixthGen()) sub("Cover Flow", ::coverFlow) else null,
            sub("Artists", ::artists),
            sub("Albums") { albums("Albums", host.library().albums()) },
            sub("Genres", ::genres),
            sub("Songs") { songs("Songs", host.library().songs(), true) },
            sub("Composers", ::composers),
        ),
    )

    /** Your own albums in Cover Flow; each cover comes from the album's first song. */
    private fun coverFlow(): Screen = CoverFlowScreen(lcd) {
        val lib = host.library()
        lib.albums().map { al ->
            val songs = lib.byAlbum(al)
            CoverFlowScreen.Album(al, songs.firstOrNull()?.artist.orEmpty(), songs.firstOrNull()?.uri?.toString()) {
                songs(al, lib.byAlbum(al), false)
            }
        }
    }

    private fun artists(): Screen {
        val lib = host.library()
        return ListScreen(lcd, "Artists", lib.artists().map { a -> sub(a) { artist(a, lib.byArtist(a)) } }).empty("No Artists")
    }

    /** An artist: "All" followed by their albums. */
    private fun artist(name: String, tracks: List<Track>): Screen {
        val items = mutableListOf(sub("All") { songs(name, tracks, false) })
        for (al in host.library().albumsOf(tracks)) {
            items.add(sub(al) { songs(al, tracks.filter { it.album == al }, false) })
        }
        return ListScreen(lcd, name, items)
    }

    private fun albums(title: String, names: List<String>): Screen {
        val lib = host.library()
        return ListScreen(lcd, title, names.map { al -> sub(al) { songs(al, lib.byAlbum(al), false) } }).empty("No Albums")
    }

    private fun genres(): Screen {
        val lib = host.library()
        val items = lib.genres().map { g ->
            sub(g) {
                val inGenre = lib.byGenre(g)
                val artists = mutableListOf(sub("All") { songs(g, inGenre, false) })
                for (a in lib.artistsOf(inGenre)) {
                    val mine = inGenre.filter { it.artist == a }
                    artists.add(sub(a) { artist(a, mine) })
                }
                ListScreen(lcd, g, artists)
            }
        }
        return ListScreen(lcd, "Genres", items).empty("No Genres")
    }

    private fun composers(): Screen {
        val lib = host.library()
        return ListScreen(lcd, "Composers", lib.composers().map { c -> sub(c) { songs(c, lib.byComposer(c), false) } })
            .empty("No Composers")
    }

    private fun songs(title: String, list: List<Track>, all: Boolean): Screen =
        ListScreen(lcd, title, songItems(list)).empty(if (all) "No Music Found" else "No Songs")

    private fun songItems(list: List<Track>): MutableList<ListScreen.Item> = list.mapIndexedTo(ArrayList()) { index, t ->
        item(t.title)
            .onSelect {
                val ps = host.service() ?: return@onSelect
                ps.playQueue(list, index)
                lcd.showNowPlaying()
            }
            .onHold { addToOnTheGo(t) }
    }

    // ---- extras ----------------------------------------------------------------------------

    private fun extras(): Screen = ListScreen(
        lcd, "Extras",
        listOf(
            sub("Clock") { ClockScreen(lcd) },
            sub("Games") { ListScreen(lcd, "Games", listOf(sub("Brick") { BrickScreen(lcd) })) },
        ),
    )

    // ---- settings --------------------------------------------------------------------------

    private fun settings(): Screen = ListScreen(
        lcd, "Settings",
        listOf(
            sub("About", ::about),
            item("Shuffle")
                .value { if (host.service()?.isShuffle() == true) "Songs" else "Off" }
                .onSelect {
                    host.service()?.let { it.setShuffle(!it.isShuffle()) }
                    host.redraw()
                },
            item("Repeat")
                .value {
                    when (host.service()?.repeatMode() ?: PlayerService.REPEAT_OFF) {
                        PlayerService.REPEAT_ONE -> "One"
                        PlayerService.REPEAT_ALL -> "All"
                        else -> "Off"
                    }
                }
                .onSelect {
                    host.service()?.let { it.setRepeat((it.repeatMode() + 1) % 3) }
                    host.redraw()
                },
            item("EQ").arrow()
                .value { host.service()?.eq()?.label() ?: "" }
                .onSelect { lcd.push(eq()) },
            sub("Speakers", ::speakers),
            item("Backlight Timer").arrow()
                .value { backlightLabel(host.backlightSeconds()) }
                .onSelect { lcd.push(backlightTimer()) },
            item("Clicker")
                .value { if (host.clickerOn()) "On" else "Off" }
                .onSelect {
                    host.setClicker(!host.clickerOn())
                    host.redraw()
                },
            item("Menu Style")
                .value { if (host.sixthGen()) "6th Gen" else "3rd Gen" }
                .onSelect {
                    host.setSixthGen(!host.sixthGen())
                    host.redraw()
                },
            item("Color")
                .value { finishLabel(host.finishMode()) }
                .onSelect {
                    host.setFinishMode((host.finishMode() + 1) % 3)
                    host.redraw()
                },
            item("Rescan Music").onSelect { host.rescan() },
        ),
    )

    /** Settings > EQ: the bands to set by hand, then presets to start from. */
    private fun eq(): Screen {
        val items = mutableListOf(item("Bands").arrow().onSelect { lcd.push(EqScreen(lcd)) })
        Eq.PRESETS.forEachIndexed { p, name ->
            items.add(
                item(name)
                    .value { if (host.service()?.eq()?.preset() == p) "✓" else "" }
                    .onSelect {
                        host.service()?.eq()?.usePreset(p)
                        host.redraw()
                    }
            )
        }
        return ListScreen(lcd, "EQ", items)
    }

    private fun backlightTimer(): Screen = ListScreen(
        lcd, "Backlight",
        BACKLIGHT_SECONDS.map { s ->
            item(backlightLabel(s))
                .value { if (host.backlightSeconds() == s) "✓" else "" }
                .onSelect {
                    host.setBacklightSeconds(s)
                    lcd.pop()
                }
        },
    )

    private fun about(): Screen {
        val lib = host.library()
        val songs = lib.songs().size.toString()
        val artists = lib.artists().size.toString()
        val albums = lib.albums().size.toString()
        // The real version, e.g. 1.0.23: the last number is the GitHub build it came from.
        val v = try {
            host.packageManager.getPackageInfo(host.packageName, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }
        return ListScreen(
            lcd, "About",
            listOf(
                item("iPod Player Android"),
                item("Songs").value { songs },
                item("Artists").value { artists },
                item("Albums").value { albums },
                item("Version").value { v },
                // Opens the arnout.pro page with the brand mark.
                item("Made by").value { "@arnoutpro" }.arrow().onSelect { lcd.push(MakerScreen(lcd, v)) },
            ),
        )
    }

    private companion object {
        val BACKLIGHT_SECONDS = intArrayOf(0, 2, 5, 10, 15, 20, 30, -1)

        fun finishLabel(m: Int) = when (m) {
            MainActivity.FINISH_BLACK -> "Black"
            MainActivity.FINISH_AUTO -> "Auto"
            else -> "White"
        }

        fun backlightLabel(s: Int) = when {
            s == 0 -> "Off"
            s < 0 -> "Always On"
            else -> "$s Seconds"
        }
    }
}
