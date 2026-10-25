package com.arnoutpro.ipod3g

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * A Sonos group playing the phone's own music. Commands go to the group's coordinator; its state
 * is polled every second, which is also how the end of a song is noticed.
 */
class SonosSpeaker(
    @JvmField val group: Sonos.Group,
    private val listener: RemoteSpeaker.Listener,
) : RemoteSpeaker {

    private val scope = MainScope()

    // Commands go out one at a time, in order.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Last known state; written on the io line, read on the main thread.
    @Volatile private var playing = false
    @Volatile private var loaded = false
    @Volatile private var sawPlaying = false
    @Volatile private var finishHandled = false
    @Volatile private var pos = 0
    @Volatile private var dur = 0
    @Volatile private var vol = -1
    @Volatile private var positionAt = 0L
    @Volatile private var volumeSetAt = 0L

    /** Counts loads, so a poll answered for the previous song is thrown away. */
    @Volatile private var generation = 0
    private var polls = 0

    private var seekJob: Job? = null
    private var volumeJob: Job? = null

    init {
        scope.launch {
            while (isActive) {
                send { readState() } // one poll at a time: the next starts when this one is done
                delay(POLL_MS)
            }
        }
    }

    override fun name(): String = group.name

    override fun load(url: String, type: String, t: Track, positionMs: Int, play: Boolean) {
        generation++
        loaded = true
        sawPlaying = false
        finishHandled = false
        playing = play
        pos = positionMs
        positionAt = SystemClock.elapsedRealtime()
        dur = t.durationMs.toInt()
        val didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"ipod\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${Sonos.escape(t.title)}</dc:title>" +
            "<dc:creator>${Sonos.escape(t.artist)}</dc:creator>" +
            "<upnp:album>${Sonos.escape(t.album)}</upnp:album>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$type:*\" duration=\"${time(t.durationMs.toInt())}\">" +
            "${Sonos.escape(url)}</res></item></DIDL-Lite>"
        scope.launch {
            send {
                av("SetAVTransportURI", ID + "<CurrentURI>${Sonos.escape(url)}</CurrentURI>" +
                    "<CurrentURIMetaData>${Sonos.escape(didl)}</CurrentURIMetaData>")
                if (positionMs > 1000) seekNow(positionMs)
                if (play) av("Play", "$ID<Speed>1</Speed>")
                if (vol < 0) readVolume()
            }
        }
    }

    override fun play() {
        snapshotPosition()
        playing = true
        scope.launch { send { av("Play", "$ID<Speed>1</Speed>") } }
    }

    override fun pause() {
        snapshotPosition()
        playing = false
        scope.launch { send { av("Pause", ID) } }
    }

    /** The wheel calls this on every tick, so only the last position is sent. */
    override fun seek(ms: Int) {
        pos = ms
        positionAt = SystemClock.elapsedRealtime()
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(300)
            val at = position()
            send { seekNow(at) }
        }
    }

    override fun isPlaying() = playing

    override fun idle() = !loaded

    override fun position(): Int {
        if (!playing) return pos
        val limit = if (dur > 0) dur.toLong() else Long.MAX_VALUE
        return minOf(limit, pos + SystemClock.elapsedRealtime() - positionAt).toInt()
    }

    override fun duration() = dur

    override fun volume() = maxOf(0, vol)

    override fun setVolume(percent: Int) {
        vol = percent
        volumeSetAt = SystemClock.elapsedRealtime()
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(300)
            val v = vol
            send {
                // A snapshot first keeps the speakers' volumes in proportion within the group.
                Sonos.soap(group.host, GROUP_VOLUME, "GroupRenderingControl", "SnapshotGroupVolume", ID)
                Sonos.soap(group.host, GROUP_VOLUME, "GroupRenderingControl", "SetGroupVolume",
                    "$ID<DesiredVolume>$v</DesiredVolume>")
            }
        }
    }

    override fun release(stop: Boolean) {
        if (stop) {
            // Sent on its own, since the scope that would carry it is ending.
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(io) {
                try {
                    av("Pause", ID)
                } catch (ignored: IOException) {
                }
            }
        }
        scope.cancel()
    }

    // ---- io line -----------------------------------------------------------------------------

    /** Runs job on the io line, then tells the listener; Sonos errors are ignored. */
    private suspend fun send(job: () -> Unit) {
        try {
            withContext(io) { job() }
        } catch (ignored: IOException) {
            // The speaker didn't answer; the next poll shows the real state.
        }
        listener.onChanged()
    }

    private fun readState() {
        val g = generation
        val info = av("GetTransportInfo", ID)
        val state = Sonos.value(info, "CurrentTransportState")
        val p = av("GetPositionInfo", ID)
        val rel = parse(Sonos.value(p, "RelTime"))
        val d = parse(Sonos.value(p, "TrackDuration"))
        if (g != generation) return // a new song was loaded meanwhile
        if (rel >= 0) {
            pos = rel
            positionAt = SystemClock.elapsedRealtime()
        }
        if (d > 0) dur = d
        when (state) {
            "PLAYING" -> {
                playing = true
                sawPlaying = true
            }
            "PAUSED_PLAYBACK" -> playing = false
            "STOPPED" -> {
                playing = false
                // Stopped after playing, by itself: the song ended.
                if (loaded && sawPlaying && !finishHandled) {
                    finishHandled = true
                    loaded = false
                    scope.launch { listener.onFinished() }
                }
            }
        }
        if (++polls % 5 == 0) readVolume()
    }

    private fun readVolume() {
        if (SystemClock.elapsedRealtime() - volumeSetAt < 3000) return // keep the wheel's value
        val reply = Sonos.soap(group.host, GROUP_VOLUME, "GroupRenderingControl", "GetGroupVolume", ID)
        Sonos.value(reply, "CurrentVolume")?.toIntOrNull()?.let { vol = it }
    }

    private fun seekNow(ms: Int) {
        av("Seek", "$ID<Unit>REL_TIME</Unit><Target>${time(ms)}</Target>")
    }

    private fun av(action: String, args: String) = Sonos.soap(group.host, AV, "AVTransport", action, args)

    private fun snapshotPosition() {
        pos = position()
        positionAt = SystemClock.elapsedRealtime()
    }

    private companion object {
        const val AV = "/MediaRenderer/AVTransport/Control"
        const val GROUP_VOLUME = "/MediaRenderer/GroupRenderingControl/Control"
        const val ID = "<InstanceID>0</InstanceID>"
        const val POLL_MS = 1000L

        fun time(ms: Int): String {
            val s = maxOf(0, ms / 1000)
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        }

        /** "0:03:12" to milliseconds, or -1. */
        fun parse(t: String?): Int {
            val p = t?.split(":") ?: return -1
            if (p.size != 3) return -1
            val h = p[0].toIntOrNull() ?: return -1
            val m = p[1].toIntOrNull() ?: return -1
            val s = p[2].toDoubleOrNull() ?: return -1
            return (h * 3600 + m * 60 + s.toInt()) * 1000
        }
    }
}
