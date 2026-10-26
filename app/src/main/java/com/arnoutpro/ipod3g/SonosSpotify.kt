package com.arnoutpro.ipod3g

import android.content.SharedPreferences
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader

/**
 * Plays Spotify on a Sonos group through the speakers' own Spotify account, the way Sonos's share
 * links work: the songs go into the group's queue and the speakers stream them from Spotify
 * themselves, so it works without Spotify Connect. Spotify has to be added in the Sonos app.
 * Everything here blocks: call it on a background thread.
 */
class SonosSpotify(private val prefs: SharedPreferences) {

    /** What the group is playing; uri is a spotify:track: link, or null for anything else. */
    class State(
        @JvmField val playing: Boolean,
        @JvmField val uri: String?,
        @JvmField val title: String,
        @JvmField val artist: String,
        @JvmField val album: String,
        @JvmField val position: Int,
        @JvmField val duration: Int,
        @JvmField val shuffle: Boolean,
        @JvmField val repeat: Int,
        @JvmField val volume: Int,
    )

    /** Sonos's number for Spotify, which differs by region; 0 until known. */
    private var service = prefs.getInt(PREF, 0)

    /** Plays an album or playlist, from trackUri on (or from the top when it isn't found). */
    @Throws(IOException::class)
    fun playContext(g: Sonos.Group, contextUri: String, trackUri: String?) {
        av(g, "RemoveAllTracksFromQueue", ID)
        enqueue(g, contextUri)
        start(g, trackUri?.let { find(g, it) } ?: 1)
    }

    /** Plays this one song; [add] puts the songs after it in the queue. */
    @Throws(IOException::class)
    fun playSong(g: Sonos.Group, uri: String) {
        av(g, "RemoveAllTracksFromQueue", ID)
        enqueue(g, uri)
        start(g, 1)
    }

    /** Adds songs to the end of the queue, skipping any Sonos won't take. */
    fun add(g: Sonos.Group, uris: List<String>) {
        val svc = service.takeIf { it != 0 } ?: return
        for (u in uris) {
            try {
                addToQueue(g, u, svc)
            } catch (ignored: IOException) {
            }
        }
    }

    @Throws(IOException::class)
    fun state(g: Sonos.Group): State {
        val transport = Sonos.value(av(g, "GetTransportInfo", ID), "CurrentTransportState")
        val p = av(g, "GetPositionInfo", ID)
        val meta = Sonos.value(p, "TrackMetaData").orEmpty()
        val id = TRACK_ID.find(Sonos.value(p, "TrackURI").orEmpty())?.groupValues?.get(1)
        val mode = Sonos.value(av(g, "GetTransportSettings", ID), "PlayMode") ?: "NORMAL"
        val volume = Sonos.value(Sonos.soap(g.host, GROUP_VOLUME, "GroupRenderingControl", "GetGroupVolume", ID), "CurrentVolume")
        return State(
            transport == "PLAYING" || transport == "TRANSITIONING",
            id?.let { "spotify:track:$it" },
            // The parser drops the dc: and upnp: prefixes.
            Sonos.value(meta, "title").orEmpty(),
            Sonos.value(meta, "creator").orEmpty(),
            Sonos.value(meta, "album").orEmpty(),
            maxOf(0, Sonos.parseTime(Sonos.value(p, "RelTime"))),
            maxOf(0, Sonos.parseTime(Sonos.value(p, "TrackDuration"))),
            mode.startsWith("SHUFFLE"),
            when (mode) {
                "REPEAT_ONE", "SHUFFLE_REPEAT_ONE" -> PlayerService.REPEAT_ONE
                "REPEAT_ALL", "SHUFFLE" -> PlayerService.REPEAT_ALL
                else -> PlayerService.REPEAT_OFF
            },
            volume?.toIntOrNull() ?: -1,
        )
    }

    @Throws(IOException::class)
    fun play(g: Sonos.Group) {
        av(g, "Play", "$ID<Speed>1</Speed>")
    }

    @Throws(IOException::class)
    fun pause(g: Sonos.Group) {
        av(g, "Pause", ID)
    }

    @Throws(IOException::class)
    fun next(g: Sonos.Group) {
        av(g, "Next", ID)
    }

    @Throws(IOException::class)
    fun previous(g: Sonos.Group) {
        av(g, "Previous", ID)
    }

    @Throws(IOException::class)
    fun seek(g: Sonos.Group, ms: Int) {
        av(g, "Seek", "$ID<Unit>REL_TIME</Unit><Target>${Sonos.time(ms)}</Target>")
    }

    @Throws(IOException::class)
    fun setVolume(g: Sonos.Group, percent: Int) {
        // A snapshot first keeps the speakers' volumes in proportion within the group.
        Sonos.soap(g.host, GROUP_VOLUME, "GroupRenderingControl", "SnapshotGroupVolume", ID)
        Sonos.soap(g.host, GROUP_VOLUME, "GroupRenderingControl", "SetGroupVolume", "$ID<DesiredVolume>$percent</DesiredVolume>")
    }

    // ---- queue -------------------------------------------------------------------------------

    /**
     * Adds a song, album or playlist with the right Spotify service number: the one that worked
     * before, the speaker's own Spotify account, or else each region's number in turn.
     */
    private fun enqueue(g: Sonos.Group, uri: String) {
        val tries = LinkedHashSet<Int>()
        if (service != 0) tries.add(service)
        account(g)?.let { tries.add(it) }
        tries.addAll(SERVICES)
        for (svc in tries) {
            try {
                addToQueue(g, uri, svc)
            } catch (e: IOException) {
                continue
            }
            if (svc != service) {
                service = svc
                prefs.edit().putInt(PREF, svc).apply()
            }
            return
        }
        throw IOException("Add Spotify in the Sonos app")
    }

    /** The Spotify account's service number from the speaker's status page, if it lists one. */
    private fun account(g: Sonos.Group): Int? = try {
        ACCOUNT.findAll(Sonos.get(g.host, "/status/accounts"))
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in SERVICES }
    } catch (e: IOException) {
        null
    }

    private fun addToQueue(g: Sonos.Group, uri: String, svc: Int) {
        val enc = uri.replace(":", "%3a")
        // Albums and playlists go in as containers, songs on their own.
        val (prefix, key) = when (uri.split(":").getOrNull(1)) {
            "album" -> "x-rincon-cpcontainer:1004206c" to "1004206c"
            "playlist" -> "x-rincon-cpcontainer:1006206c" to "1006206c"
            else -> "" to "00032020"
        }
        val didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"$key$enc\" parentID=\"-1\" restricted=\"true\"><dc:title></dc:title>" +
            "<upnp:class>object</upnp:class>" +
            "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">" +
            "SA_RINCON${svc}_X_#Svc$svc-0-Token</desc></item></DIDL-Lite>"
        val reply = av(
            g, "AddURIToQueue",
            ID + "<EnqueuedURI>${Sonos.escape(prefix + enc)}</EnqueuedURI>" +
                "<EnqueuedURIMetaData>${Sonos.escape(didl)}</EnqueuedURIMetaData>" +
                "<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued>" +
                "<EnqueueAsNext>0</EnqueueAsNext>",
        )
        if (Sonos.value(reply, "NumTracksAdded") == "0") throw IOException("Nothing added")
    }

    /** Plays the queue from song n (counting from 1). */
    private fun start(g: Sonos.Group, n: Int) {
        av(g, "SetAVTransportURI", ID + "<CurrentURI>x-rincon-queue:${g.uuid}#0</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        av(g, "Seek", "$ID<Unit>TRACK_NR</Unit><Target>$n</Target>")
        av(g, "Play", "$ID<Speed>1</Speed>")
    }

    /** Where a song sits in the queue (from 1), or null. */
    private fun find(g: Sonos.Group, trackUri: String): Int? {
        val reply = Sonos.soap(
            g.host, CONTENT, "ContentDirectory", "Browse",
            "<ObjectID>Q:0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>res</Filter>" +
                "<StartingIndex>0</StartingIndex><RequestedCount>2000</RequestedCount><SortCriteria></SortCriteria>",
        )
        val result = Sonos.value(reply, "Result") ?: return null
        val id = trackUri.substringAfterLast(':')
        try {
            val x = Xml.newPullParser()
            x.setInput(StringReader(result))
            var n = 0
            var ev = x.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && x.name == "item") n++
                if (ev == XmlPullParser.START_TAG && x.name == "res" && x.nextText().contains(id)) return n
                ev = x.next()
            }
        } catch (ignored: XmlPullParserException) {
        }
        return null
    }

    private fun av(g: Sonos.Group, action: String, args: String) = Sonos.soap(g.host, AV, "AVTransport", action, args)

    private companion object {
        const val AV = "/MediaRenderer/AVTransport/Control"
        const val GROUP_VOLUME = "/MediaRenderer/GroupRenderingControl/Control"
        const val CONTENT = "/MediaServer/ContentDirectory/Control"
        const val ID = "<InstanceID>0</InstanceID>"
        const val PREF = "sonosSpotifyService"

        /** Spotify's service numbers on Sonos: Europe and most of the world, then the Americas. */
        val SERVICES = listOf(2311, 3079)
        val ACCOUNT = Regex("Type=\"(\\d+)\"")
        val TRACK_ID = Regex("spotify%3atrack%3a([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
    }
}
