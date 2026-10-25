package com.arnoutpro.ipod3g

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.PlayerApi
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

    /** What to play: a song within a playlist or album, or a plain list of songs. */
    class Request private constructor(
        internal val contextUri: String?,
        internal val trackUri: String?,
        internal val index: Int,
        internal val uris: List<String>?,
        /** Moves what's playing to the speaker instead of starting something. */
        internal val transfer: Boolean,
        /** For a transfer: keep playing there, or arrive paused. */
        internal val keepPlaying: Boolean,
    ) {
        companion object {
            /** trackUri sits at index in the playlist or album; null plays it from the top. */
            @JvmStatic
            fun inContext(contextUri: String?, trackUri: String?, index: Int) =
                Request(contextUri, trackUri, index, null, false, true)

            /** Plays songs from start onwards, e.g. an artist's songs or Songs A-Z. */
            @JvmStatic
            fun songs(uris: List<String>, start: Int): Request {
                val queue = ArrayList(uris.subList(start, minOf(uris.size, start + QUEUE_SONGS + 1)))
                return Request(null, queue[0], -1, queue, false, true)
            }

            internal fun transfer(keepPlaying: Boolean) = Request(null, null, -1, null, true, keepPlaying)
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
    private var position = 0
    private var duration = 0
    private var positionAt = 0L
    private var status: String? = null
    private var inFlight = false
    private var remote: SpotifyAppRemote? = null

    /** The Spotify Connect device to play on, or null for this phone. */
    private var targetId: String? = host.prefs().getString("spSpeaker", null)
    private var speakerName: String? = null
    private var volume = 0
    private var volumeSetAt = 0L

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
        // Spotify accepts a plain list of songs over the Web API but then plays nothing, so lists
        // always go through the App Remote connection.
        if (rq.uris == null) {
            val device = try {
                io { findTarget() }
            } catch (e: IOException) {
                return fail(message(e))
            } catch (e: JSONException) {
                return fail(message(e))
            }
            if (device != null) return playOn(device, rq)
        }

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

    /** Plays the first song of a list and queues the ones after it. */
    private suspend fun playSongs(r: SpotifyAppRemote, rq: Request) {
        val player = r.playerApi
        try {
            player.play(rq.trackUri).awaitDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail("Can't start Spotify")
        }
        status = null
        scope.launch { queue(player, rq.uris.orEmpty()) }
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

    /** Queues one song at a time, so they keep their order. */
    private suspend fun queue(player: PlayerApi, uris: List<String>) {
        for (i in 1 until minOf(uris.size, QUEUE_SONGS + 1)) {
            try {
                player.queue(uris[i]).awaitDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
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

    /** Sends Spotify to a speaker (null for this phone) from now on, moving what's playing. */
    fun useSpeaker(sp: Speaker?) {
        targetId = if (sp == null || sp.phone) null else sp.id
        host.prefs().edit().putString("spSpeaker", targetId).apply()
        if (host.playback() === this && has) play(Request.transfer(playing))
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
                read(io { spotify.get("/me/player") })
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
            command("PUT", "/me/player/volume?volume_percent=$volume")
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
        command("PUT", if (playing) "/me/player/play" else "/me/player/pause")
    }

    override fun next() = command("POST", "/me/player/next")

    /**
     * Like the iPod: after the first few seconds ⏮ restarts the song, before that it goes back. On
     * the first song of a list Spotify refuses (403), and the iPod restarts it instead.
     */
    override fun previous() {
        if (getPosition() > 3000) return seekNow(0)
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
        command("PUT", "/me/player/seek?position_ms=$position")
    }

    /** Scrubbing calls this on every wheel tick, so only the last position is sent. */
    override fun seekTo(ms: Int) {
        position = ms.coerceIn(0, duration)
        positionAt = SystemClock.elapsedRealtime()
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(350)
            command("PUT", "/me/player/seek?position_ms=${getPosition()}")
        }
    }

    private companion object {
        const val SPOTIFY_APP = "com.spotify.music"
        const val POLL_MS = 2000L
        const val CONNECT_TIMEOUT_MS = 6000L
        const val QUEUE_SONGS = 30

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
