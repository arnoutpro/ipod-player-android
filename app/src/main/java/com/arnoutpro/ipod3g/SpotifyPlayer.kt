package com.arnoutpro.ipod3g

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.Empty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plays through the Spotify app on this phone, controlled with the Web API and woken with the App
 * Remote SDK. The iPod shows what Spotify reports, polled while it is the active source.
 *
 * Everything runs on the main thread except the network calls, which go through [io].
 */
class SpotifyPlayer(private val spotify: Spotify, private val host: MainActivity) : Playback {

    /** What to play: a song within a playlist or album, a plain list of songs, or a radio. */
    class Request private constructor(
        internal val contextUri: String?,
        internal val trackUri: String?,
        internal val index: Int,
        internal val uris: List<String>?,
        /** Moves what's playing to the speaker instead of starting something. */
        internal val transfer: Boolean,
        /** For a transfer: keep playing there, or arrive paused. */
        internal val keepPlaying: Boolean,
        /** Spotify's own radio: a station link only the Spotify app itself can play. */
        internal val radio: Boolean = false,
        /** Where in the song to start, e.g. when moving to another speaker. */
        internal val positionMs: Int = 0,
    ) {
        companion object {
            /** trackUri sits at index in the playlist or album; null plays it from the top. */
            @JvmStatic
            fun inContext(contextUri: String?, trackUri: String?, index: Int) =
                Request(contextUri, trackUri, index, null, false, true)

            /** Plays songs from start onwards, e.g. an artist's songs or Songs A-Z. */
            @JvmStatic
            fun songs(uris: List<String>, start: Int) = Request(null, uris[start], start, uris, false, true)

            /** Spotify's radio of songs like this artist or song (a spotify:artist: or spotify:track: link). */
            @JvmStatic
            fun radio(uri: String) =
                Request(null, "spotify:station:" + uri.removePrefix("spotify:"), -1, null, false, true, true)

            internal fun transfer(keepPlaying: Boolean) = Request(null, null, -1, null, true, keepPlaying)

            /** Carries on with this song where it was, e.g. on another speaker. */
            internal fun resume(contextUri: String?, trackUri: String, positionMs: Int, keepPlaying: Boolean) =
                if (contextUri != null) Request(contextUri, trackUri, -1, null, false, keepPlaying, false, positionMs)
                else Request(null, trackUri, 0, listOf(trackUri), false, keepPlaying, false, positionMs)
        }
    }

    /** A Spotify Connect device: a speaker, TV, computer or this phone. */
    class Speaker internal constructor(
        @JvmField val id: String,
        @JvmField val name: String,
        @JvmField val type: String,
        @JvmField val phone: Boolean,
    )

    private val scope = MainScope()

    private var has = false
    private var playing = false
    private var shuffle = false
    private var repeat = PlayerService.REPEAT_OFF
    private var key = ""
    private var title = ""
    private var artist = ""
    private var album = ""
    private var art: String? = null
    private var position = 0
    private var duration = 0
    private var positionAt = 0L
    private var status: String? = null
    private var inFlight = false
    private var remote: SpotifyAppRemote? = null

    /** The Spotify Connect device to play on, or null for this phone. */
    private var targetId: String? = host.prefs().getString("spSpeaker", null)
    private var speakerName: String? = null

    /** A Sonos group playing Spotify through its own Spotify account instead, or null. */
    private var sonos: Sonos.Group? = loadSonos()
    private val sonosSpotify = SonosSpotify(host.prefs())
    /** The playlist or album last started, to carry on with it on another speaker. */
    private var context: String? = null
    /** The song whose cover and names were fetched for the Sonos, and those names. */
    private var artKey = ""
    private var spTitle = ""
    private var spArtist = ""
    private var spAlbum = ""
    /** Counts Sonos polls: play mode and volume are read on every fifth. */
    private var sonosPolls = 0
    private var volume = 0
    private var volumeSetAt = 0L

    /**
     * A song list without a playlist or album behind it (All Songs, Songs A-Z). The iPod plays it
     * one song at a time instead of filling Spotify's queue, which can't be cleared and so kept
     * songs from earlier lists in front of new ones.
     */
    private var list: List<String>? = null
    private var listIndex = 0
    private var listSongNearEnd = false
    private var listSongPlaying = false
    private var listStartedAt = 0L
    /** What Spotify calls the list's song: it may play another version (relinking), under another ID. */
    private var listKey: String? = null
    private var keyAtStart = ""
    private var endCheckJob: Job? = null

    private var pollJob: Job? = null
    private var playJob: Job? = null
    private var volumeJob: Job? = null
    private var seekJob: Job? = null

    fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ---- starting a song -------------------------------------------------------------------

    fun play(rq: Request) {
        status = if (rq.transfer) "Switching speaker..." else "Starting Spotify..."
        has = true
        if (!rq.transfer) {
            context = rq.contextUri
            list = rq.uris
            listIndex = maxOf(0, rq.index)
            listSongNearEnd = false
            listSongPlaying = false
            listStartedAt = SystemClock.elapsedRealtime()
            listKey = null
            keyAtStart = key
        }
        host.redraw()
        playJob?.cancel()
        playJob = scope.launch { start(rq) }
    }

    /**
     * Plays on this phone (or the chosen speaker) through the Web API. Spotify only lists this
     * phone while its app is awake, so when it's missing the App Remote SDK wakes Spotify in the
     * background first. Opening Spotify on screen is the last resort.
     */
    private suspend fun start(rq: Request) {
        sonos?.let { return playOnSonos(it, rq) }
        // Spotify accepts a plain list of songs over the Web API but then plays nothing, so lists
        // always go through the App Remote connection.
        if (rq.uris == null && !rq.radio) {
            val device = try {
                io { findTarget() }
            } catch (e: IOException) {
                return fail(message(e))
            } catch (e: JSONException) {
                return fail(message(e))
            }
            if (device != null) return playOn(device, rq)
        }

        // The Web API doesn't take station links; the App Remote SDK passes them to Spotify.
        if (rq.radio) return playSongs(connectRemote() ?: return fail("Can't start radio"), rq)

        val r = connectRemote() ?: return openAndPlay(rq)
        if (rq.uris != null) return playSongs(r, rq)

        // Awake: wait for Spotify to list this phone, then play the exact song there (the SDK's
        // own song positions follow shuffle, so it's only the fallback).
        val device = findTargetOrNull() ?: run {
            var found: String? = null
            repeat(8) {
                if (found == null) {
                    delay(500)
                    found = findTargetOrNull()
                }
            }
            found
        }
        if (device != null) playOn(device, rq) else playRemote(r, rq)
    }

    /** Starts the song (or moves playback) on this device through the Web API. */
    private suspend fun playOn(device: String, rq: Request) {
        status = try {
            io {
                if (rq.transfer) {
                    val body = JSONObject().put("device_ids", JSONArray().put(device)).put("play", rq.keepPlaying)
                    spotify.send("PUT", "/me/player", body.toString())
                } else {
                    val body = JSONObject()
                    if (rq.contextUri != null) {
                        body.put("context_uri", rq.contextUri)
                        if (rq.trackUri != null) body.put("offset", JSONObject().put("uri", rq.trackUri))
                        if (rq.positionMs > 0) body.put("position_ms", rq.positionMs)
                    }
                    spotify.send("PUT", "/me/player/play?device_id=" + Spotify.enc(device), body.toString())
                }
            }
            null
        } catch (e: IOException) {
            message(e)
        } catch (e: JSONException) {
            message(e)
        }
        refreshSoon(600)
        host.redraw()
    }

    /** The App Remote connection, connecting first if needed; null when Spotify won't connect. */
    private suspend fun connectRemote(): SpotifyAppRemote? {
        remote?.takeIf { it.isConnected }?.let { return it }
        // Connecting with the authorization screen allowed brings Spotify to the front, so first
        // try without it; only the very first time, before Spotify has said yes, is it needed.
        return try {
            connect(authView = false)
        } catch (e: UserNotAuthorizedException) {
            try {
                connect(authView = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** One connection attempt; Spotify sometimes never answers, so it gives up after a while. */
    private suspend fun connect(authView: Boolean): SpotifyAppRemote? {
        val params = ConnectionParams.Builder(Spotify.CLIENT_ID)
            .setRedirectUri(Spotify.REDIRECT)
            .showAuthView(authView)
            .build()
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                SpotifyAppRemote.connect(host, params, object : Connector.ConnectionListener {
                    override fun onConnected(r: SpotifyAppRemote) {
                        remote = r // keep it even if we gave up waiting
                        if (cont.isActive) cont.resume(r)
                    }

                    override fun onFailure(t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                })
            }
        }
    }

    /** Plays through the SDK itself. */
    private suspend fun playRemote(r: SpotifyAppRemote, rq: Request) {
        val player = r.playerApi
        val call = when {
            rq.transfer -> if (rq.keepPlaying) player.resume() else player.pause()
            rq.contextUri != null && rq.trackUri != null && rq.index >= 0 -> player.skipToIndex(rq.contextUri, rq.index)
            else -> player.play(rq.contextUri ?: rq.trackUri)
        }
        try {
            call.awaitDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return openAndPlay(rq)
        }
        status = null
        // If Spotify still came to the front while waking up, come back.
        if (!host.isFront()) backToIPod()
        refreshSoon(800)
        host.redraw()
    }

    /** Plays one song of a song list (the iPod moves through the list itself), or a radio. */
    private suspend fun playSongs(r: SpotifyAppRemote, rq: Request) {
        val player = r.playerApi
        try {
            player.play(rq.trackUri).awaitDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(if (rq.radio) "Can't start radio" else "Can't start Spotify")
        }
        status = null
        // The SDK plays on this phone; carry on on the chosen speaker.
        val target = targetId
        if (target != null) {
            scope.launch {
                try {
                    io {
                        if (findTarget() == target) {
                            val body = JSONObject().put("device_ids", JSONArray().put(target)).put("play", true)
                            spotify.send("PUT", "/me/player", body.toString())
                        }
                    }
                } catch (e: IOException) {
                    // It stays on the phone.
                } catch (e: JSONException) {
                }
            }
        }
        refreshSoon(800)
        host.redraw()
    }

    /** Plays song i of the current song list, or lets go of the list past its end. */
    private fun playListAt(i: Int) {
        val l = list ?: return
        if (i !in l.indices) {
            list = null
            return
        }
        play(Request.songs(l, i))
    }

    /**
     * Keeps a song list going: when the list's song ends, Spotify moves on to something else (its
     * autoplay, or songs left in its queue) or just stops, and the iPod starts the next song from
     * the list instead. A different song in the middle means it was picked elsewhere: the list ends.
     */
    private fun followList() {
        if (sonos != null) return // the Sonos plays its own queue
        val l = list ?: return
        if (status != null) return // still starting
        val grace = SystemClock.elapsedRealtime() - listStartedAt < START_GRACE_MS
        if (listKey == null) {
            // Spotify may still report the previous song for a moment; the first new one is ours.
            if (key == l[listIndex] || key != keyAtStart || !grace) listKey = key else return
        }
        if (key == listKey) {
            val nearEnd = duration > 0 && position >= duration - END_MARGIN_MS
            if (!playing && listSongPlaying && (nearEnd || listSongNearEnd || position == 0)) {
                return playListAt(listIndex + 1) // it played to the end and stopped
            }
            listSongNearEnd = nearEnd
            listSongPlaying = playing
            scheduleEndCheck()
        } else if (listSongNearEnd || (listSongPlaying && listIndex + 1 < l.size && key == l[listIndex + 1])) {
            playListAt(listIndex + 1)
        } else {
            list = null
            endCheckJob?.cancel()
        }
    }

    /**
     * Asks Spotify again right when the list's song is due to end, so the next one follows quickly.
     * This also keeps the list going while the iPod is in the background and doesn't poll.
     */
    private fun scheduleEndCheck() {
        endCheckJob?.cancel()
        if (!playing || duration <= 0) return
        val left = duration - getPosition()
        endCheckJob = scope.launch {
            delay(left.toLong() + 400)
            listSongNearEnd = true
            refresh()
        }
    }

    /** The old way: open Spotify on screen until it registers, then come back and play. */
    private suspend fun openAndPlay(rq: Request) {
        if (rq.uris != null) return fail("Can't start Spotify")
        if (!openSpotifyApp()) return
        var device: String? = null
        try {
            repeat(15) {
                if (device == null) {
                    delay(1000)
                    device = io { findTarget() }
                }
            }
        } catch (e: IOException) {
            return fail(message(e))
        } catch (e: JSONException) {
            return fail(message(e))
        } finally {
            backToIPod()
        }
        device?.let { playOn(it, rq) } ?: fail("Open Spotify first")
    }

    private fun fail(err: String) {
        status = err
        host.redraw()
    }

    /** Lets go of the Spotify app when the iPod closes. */
    fun release() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        scope.cancel()
    }

    // ---- speakers ----------------------------------------------------------------------------

    /**
     * The chosen speaker if it's on, otherwise this phone. Never another device, even an active
     * one: the music should come out of the iPod, not a computer's web player.
     */
    private fun findTarget(): String? {
        targetId?.let { target ->
            if (speakers().any { it.id == target }) return target
        }
        return findPhone() // the chosen speaker is off: play here
    }

    private suspend fun findTargetOrNull(): String? = try {
        io { findTarget() }
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    }

    /** Every Spotify Connect device on the account that can be controlled; background thread. */
    fun speakers(): List<Speaker> {
        val devices = spotify.get("/me/player/devices").optJSONArray("devices") ?: return emptyList()
        return (0 until devices.length())
            .map { devices.getJSONObject(it) }
            .filter { !it.optBoolean("is_restricted") }
            .map { Speaker(it.optString("id"), it.optString("name"), it.optString("type"), isPhone(it)) }
    }

    private fun isPhone(device: JSONObject): Boolean {
        if (device.optString("type") != "Smartphone") return false
        val name = Settings.Global.getString(host.contentResolver, "device_name")
        return name == null || name == device.optString("name")
    }

    /** The chosen speaker's id, or null for this phone. */
    fun target(): String? = targetId

    /** The Sonos group Spotify plays on, or null. */
    fun sonosTarget(): Sonos.Group? = sonos

    /** Sends Spotify to a speaker (null for this phone) from now on, moving what's playing. */
    fun useSpeaker(sp: Speaker?) {
        targetId = if (sp == null || sp.phone) null else sp.id
        host.prefs().edit().putString("spSpeaker", targetId).apply()
        val from = sonos ?: return run { if (host.playback() === this && has) play(Request.transfer(playing)) }
        // Leaving the Sonos: carry on with the song where it was.
        val rq = resumeHere()
        setSonos(null)
        quietly { sonosSpotify.pause(from) }
        rq?.let { play(it) }
    }

    /**
     * Plays Spotify on this Sonos group from now on, through the speakers' own Spotify account,
     * moving what's playing there.
     */
    fun useSonos(g: Sonos.Group) {
        if (sonos == g) return
        val from = sonos
        val rq = resumeHere()
        targetId = null
        host.prefs().edit().putString("spSpeaker", null).apply()
        setSonos(g)
        if (rq == null) return
        if (from != null) {
            quietly { sonosSpotify.pause(from) }
        } else {
            quietly { spotify.send("PUT", "/me/player/pause", null) } // on the phone or a Connect speaker
        }
        play(rq)
    }

    /** Local music took over: stop Spotify on the Sonos (on the phone it stops by itself). */
    fun localTookOver() {
        val g = sonos ?: return
        if (host.service()?.castingTo(g) == true) return // the local song replaces it there anyway
        quietly { sonosSpotify.pause(g) }
    }

    /** The song that's on now, to carry on with elsewhere; null when there is none. */
    private fun resumeHere(): Request? {
        if (host.playback() !== this || !has || status != null || !key.startsWith("spotify:track:")) return null
        return Request.resume(context, key, getPosition(), playing)
    }

    private fun setSonos(g: Sonos.Group?) {
        sonos = g
        artKey = ""
        host.prefs().edit().putString("spSonos", g?.let { "${it.uuid}\n${it.host}\n${it.name}" }).apply()
    }

    private fun loadSonos(): Sonos.Group? {
        val p = host.prefs().getString("spSonos", null)?.split("\n") ?: return null
        return if (p.size == 3) Sonos.Group(p[2], p[1], p[0]) else null
    }

    /** Runs a call on the Sonos; if it doesn't answer, finds it again (its address may have changed). */
    private suspend fun <T> onSonos(block: (Sonos.Group) -> T): T {
        val g = sonos ?: throw IOException("No Sonos")
        return try {
            io { block(g) }
        } catch (e: IOException) {
            val found = io { Sonos.discover(host) }.firstOrNull { it.uuid == g.uuid } ?: throw e
            if (sonos == g) setSonos(found)
            io { block(found) }
        }
    }

    /** A Sonos or Spotify call whose failure doesn't matter. */
    private fun quietly(block: () -> Unit) {
        scope.launch {
            try {
                io(block)
            } catch (e: IOException) {
            } catch (e: JSONException) {
            }
        }
    }

    /**
     * Starts the song on the Sonos. Albums and playlists go into its queue whole; Liked Songs and
     * song lists from the chosen song on, the first one straight away and the rest while it plays.
     */
    private suspend fun playOnSonos(g: Sonos.Group, rq: Request) {
        list = null
        if (rq.radio) return fail("No radio on Sonos")
        // The phone's own music playing on this Sonos lets go of it first.
        host.service()?.let {
            if (it.castingTo(g)) {
                it.stopCasting()
                delay(600)
            }
        }
        val liked = "spotify:user:" + spotify.userId() + ":collection"
        val songs: List<String>? = when {
            rq.transfer -> null
            rq.uris != null -> rq.uris.drop(maxOf(0, rq.index))
            rq.contextUri == liked -> likedFrom(rq.trackUri)
            rq.contextUri == null -> rq.trackUri?.let { listOf(it) }
            else -> null
        }
        status = try {
            onSonos { s ->
                when {
                    rq.transfer -> if (rq.keepPlaying) sonosSpotify.play(s) else sonosSpotify.pause(s)
                    songs != null -> sonosSpotify.playSong(s, songs[0])
                    else -> sonosSpotify.playContext(s, rq.contextUri!!, rq.trackUri)
                }
                if (rq.positionMs > 1000) sonosSpotify.seek(s, rq.positionMs)
                if (!rq.keepPlaying && !rq.transfer) sonosSpotify.pause(s)
            }
            null
        } catch (e: IOException) {
            message(e)
        }
        if (status == null && songs != null && songs.size > 1) {
            val rest = songs.subList(1, minOf(songs.size, MAX_SONOS_SONGS))
            quietly { sonosSpotify.add(sonos ?: g, rest) }
        }
        refreshSoon(800)
        host.redraw()
    }

    /** Liked Songs from this one on. */
    private fun likedFrom(trackUri: String?): List<String> {
        val all = host.spotifyLibrary().get().liked.map { it.uri }
        val i = all.indexOf(trackUri)
        return if (i < 0) listOfNotNull(trackUri) else all.subList(i, minOf(all.size, i + MAX_SONOS_SONGS))
    }

    private fun findPhone(): String? {
        val devices = spotify.get("/me/player/devices").optJSONArray("devices") ?: return null
        val name = Settings.Global.getString(host.contentResolver, "device_name")
        var phone: String? = null
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            if (d.optBoolean("is_restricted") || d.optString("type") != "Smartphone") continue
            // With several phones on the account, the one carrying this phone's name wins.
            if (name != null && name == d.optString("name")) return d.optString("id")
            if (phone == null || d.optBoolean("is_active")) phone = d.optString("id")
        }
        return phone
    }

    /** Opens the Spotify app; false (with a message) when it isn't installed. */
    private fun openSpotifyApp(): Boolean {
        val i = host.packageManager.getLaunchIntentForPackage(SPOTIFY_APP)
        if (i == null) {
            fail("Install Spotify")
            return false
        }
        host.startActivity(i)
        return true
    }

    private fun backToIPod() {
        host.startActivity(
            Intent(host, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- state -------------------------------------------------------------------------------

    /** Asks Spotify whether it has a song playing or paused anywhere; runs found if so. */
    fun probe(found: Runnable) {
        if (!spotify.connected()) return
        if (sonos != null) {
            scope.launch {
                val s = try {
                    onSonos { sonosSpotify.state(it, true) }
                } catch (e: IOException) {
                    return@launch
                }
                if (s.uri == null) return@launch
                readSonos(s)
                found.run()
            }
            return
        }
        scope.launch {
            val state = try {
                io { spotify.get("/me/player") }
            } catch (e: IOException) {
                return@launch
            } catch (e: JSONException) {
                return@launch
            }
            if (state.optJSONObject("item") == null) return@launch
            read(state)
            found.run()
        }
    }

    /** Asks Spotify what it is playing. */
    fun refresh() {
        if (inFlight || !spotify.connected()) return
        inFlight = true
        scope.launch {
            try {
                if (sonos != null) {
                    val settings = sonosPolls++ % 5 == 0
                    readSonos(onSonos { sonosSpotify.state(it, settings) })
                } else {
                    read(io { spotify.get("/me/player") })
                }
            } catch (e: IOException) {
                status = message(e)
            } catch (e: JSONException) {
                status = message(e)
            } finally {
                inFlight = false
                host.redraw()
            }
        }
    }

    private fun refreshSoon(ms: Long) {
        scope.launch {
            delay(ms)
            refresh()
        }
    }

    /** What the Sonos plays: the song comes from its queue, the cover from Spotify. */
    private fun readSonos(s: SonosSpotify.State) {
        speakerName = sonos?.name
        if (s.volume >= 0 && SystemClock.elapsedRealtime() - volumeSetAt > 3000) volume = s.volume
        if (s.uri == null && s.title.isEmpty()) {
            has = status != null
            playing = false
            return
        }
        status = null
        has = true
        key = s.uri ?: s.title
        // Sonos sometimes leaves out the names; Spotify's own fill the gaps.
        val known = s.uri != null && s.uri == artKey
        title = s.title.ifEmpty { if (known) spTitle else "" }
        artist = s.artist.ifEmpty { if (known) spArtist else "" }
        album = s.album.ifEmpty { if (known) spAlbum else "" }
        duration = s.duration
        position = s.position
        positionAt = SystemClock.elapsedRealtime()
        playing = s.playing
        s.shuffle?.let { shuffle = it }
        if (s.repeat >= 0) repeat = s.repeat
        if (s.uri == null) {
            art = null
        } else if (s.uri != artKey) {
            artKey = s.uri
            art = null
            spTitle = ""
            spArtist = ""
            spAlbum = ""
            fetchArt(s.uri)
        }
    }

    private fun fetchArt(uri: String) {
        scope.launch {
            val t = try {
                io { spotify.get("/tracks/" + uri.substringAfterLast(':')) }
            } catch (e: IOException) {
                return@launch
            } catch (e: JSONException) {
                return@launch
            }
            if (artKey != uri) return@launch
            val al = t.optJSONObject("album")
            art = Covers.pick(al?.optJSONArray("images"))
            spTitle = t.optString("name")
            spArtist = t.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            spAlbum = al?.optString("name").orEmpty()
            if (title.isEmpty()) title = spTitle
            if (artist.isEmpty()) artist = spArtist
            if (album.isEmpty()) album = spAlbum
            host.redraw()
        }
    }

    /** A control on the Sonos, then a fresh look at what it plays. */
    private fun sonosCommand(block: (Sonos.Group) -> Unit) {
        scope.launch {
            try {
                onSonos(block)
            } catch (e: IOException) {
                status = message(e)
            }
            refreshSoon(400)
            host.redraw()
        }
    }

    private fun read(state: JSONObject) {
        val device = state.optJSONObject("device")
        speakerName = if (device != null && !isPhone(device)) device.optString("name") else null
        // Right after the wheel moved, Spotify still reports the old volume: keep ours.
        if (device != null && SystemClock.elapsedRealtime() - volumeSetAt > 3000) {
            volume = device.optInt("volume_percent", volume)
        }
        val item = state.optJSONObject("item")
        if (item == null) {
            has = status != null // keep showing a pending "Starting Spotify..."
            playing = false
            return
        }
        status = null
        has = true
        key = item.optString("uri")
        title = item.optString("name")
        val artists = item.optJSONArray("artists")
        artist = if (artists != null && artists.length() > 0) artists.optJSONObject(0).optString("name") else ""
        album = item.optJSONObject("album")?.optString("name")
            ?: item.optJSONObject("show")?.optString("name")
            ?: ""
        art = Covers.pick(item.optJSONObject("album")?.optJSONArray("images") ?: item.optJSONArray("images"))
        duration = item.optInt("duration_ms")
        position = state.optInt("progress_ms")
        positionAt = SystemClock.elapsedRealtime()
        playing = state.optBoolean("is_playing")
        shuffle = state.optBoolean("shuffle_state")
        repeat = when (state.optString("repeat_state", "off")) {
            "track" -> PlayerService.REPEAT_ONE
            "context" -> PlayerService.REPEAT_ALL
            else -> PlayerService.REPEAT_OFF
        }
        followList()
    }

    private fun command(method: String, path: String) {
        scope.launch {
            try {
                io { spotify.send(method, path, null) }
            } catch (e: IOException) {
                status = message(e)
            } catch (e: JSONException) {
                status = message(e)
            }
            refreshSoon(400)
            host.redraw()
        }
    }

    // ---- Playback --------------------------------------------------------------------------

    override fun hasTrack() = has

    override fun isPlayingOrLoading() = playing

    override fun trackKey() = key

    override fun title() = status ?: title

    override fun artist() = if (status != null) "" else artist

    override fun album() = if (status != null) "" else album

    override fun artUri() = if (status != null) null else art

    /** Spotify's name stands in for "3 of 12": the credit its terms ask for. */
    override fun counter() = speakerName?.let { "Spotify on $it" } ?: "Spotify"

    override fun speaker(): String? = speakerName

    override fun speakerVolume() = volume

    /** The wheel calls this on every tick, so only the last volume is sent. */
    override fun setSpeakerVolume(percent: Int) {
        volume = percent.coerceIn(0, 100)
        volumeSetAt = SystemClock.elapsedRealtime()
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(300)
            val v = volume
            if (sonos != null) sonosCommand { sonosSpotify.setVolume(it, v) } else command("PUT", "/me/player/volume?volume_percent=$v")
        }
    }

    override fun getPosition(): Int {
        if (!playing) return position
        return minOf(duration.toLong(), position + SystemClock.elapsedRealtime() - positionAt).toInt()
    }

    override fun getDuration() = duration

    override fun isShuffle() = shuffle

    override fun repeatMode() = repeat

    override fun toggle() {
        position = getPosition()
        positionAt = SystemClock.elapsedRealtime()
        playing = !playing // show it straight away; the next poll corrects it if needed
        val p = playing
        if (sonos != null) return sonosCommand { if (p) sonosSpotify.play(it) else sonosSpotify.pause(it) }
        command("PUT", if (playing) "/me/player/play" else "/me/player/pause")
    }

    override fun next() {
        if (sonos != null) return sonosCommand { sonosSpotify.next(it) }
        val l = list
        if (l != null) {
            if (listIndex + 1 < l.size) playListAt(listIndex + 1)
            return
        }
        command("POST", "/me/player/next")
    }

    /**
     * Like the iPod: after the first few seconds ⏮ restarts the song, before that it goes back. On
     * the first song of a list Spotify refuses (403), and the iPod restarts it instead.
     */
    override fun previous() {
        if (getPosition() > 3000) return seekNow(0)
        if (sonos != null) return sonosCommand { sonosSpotify.previous(it) }
        if (list != null) return if (listIndex > 0) playListAt(listIndex - 1) else seekNow(0)
        scope.launch {
            try {
                io { spotify.send("POST", "/me/player/previous", null) }
            } catch (e: Spotify.ApiException) {
                if (e.status == 403) return@launch seekNow(0)
                status = message(e)
            } catch (e: IOException) {
                status = message(e)
            }
            refreshSoon(400)
            host.redraw()
        }
    }

    private fun seekNow(ms: Int) {
        seekJob?.cancel()
        position = ms.coerceIn(0, duration)
        positionAt = SystemClock.elapsedRealtime()
        val at = position
        if (sonos != null) return sonosCommand { sonosSpotify.seek(it, at) }
        command("PUT", "/me/player/seek?position_ms=$position")
    }

    /** Scrubbing calls this on every wheel tick, so only the last position is sent. */
    override fun seekTo(ms: Int) {
        position = ms.coerceIn(0, duration)
        positionAt = SystemClock.elapsedRealtime()
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(350)
            val at = getPosition()
            if (sonos != null) sonosCommand { sonosSpotify.seek(it, at) } else command("PUT", "/me/player/seek?position_ms=$at")
        }
    }

    private companion object {
        const val SPOTIFY_APP = "com.spotify.music"
        const val POLL_MS = 2000L
        const val CONNECT_TIMEOUT_MS = 6000L
        /** A song this close to its end counts as finished when Spotify moves on. */
        const val END_MARGIN_MS = 8000
        const val START_GRACE_MS = 5000L
        /** How many songs of a list go into the Sonos queue. */
        const val MAX_SONOS_SONGS = 100

        /** Runs a network call off the main thread. */
        suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        /** The same short messages Spotify.call() gives. */
        fun message(e: Exception) = when (e) {
            is JSONException -> "Unexpected reply"
            else -> e.message ?: "No connection"
        }

        /** Waits for an App Remote call to finish; its error becomes an exception. */
        suspend fun CallResult<Empty>.awaitDone() = suspendCancellableCoroutine { cont ->
            setResultCallback { if (cont.isActive) cont.resume(Unit) }
            setErrorCallback { t -> if (cont.isActive) cont.resumeWithException(t) }
        }
    }
}
