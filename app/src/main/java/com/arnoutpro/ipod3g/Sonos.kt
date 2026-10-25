package com.arnoutpro.ipod3g

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/**
 * Finds Sonos speakers on the Wi-Fi and talks to them with their own UPnP control protocol, the
 * same one the Sonos app uses. Everything here blocks: call it on a background thread.
 */
object Sonos {
    private const val SEARCH = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 1\r\n" +
        "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"

    /** A Sonos group (or a single speaker), controlled through its coordinator. */
    class Group internal constructor(
        @JvmField val name: String,
        @JvmField val host: String,
        @JvmField val uuid: String,
    ) {
        override fun equals(other: Any?) = other is Group && other.uuid == uuid

        override fun hashCode() = uuid.hashCode()
    }

    /** Searches the network for about two seconds and returns the Sonos groups found. */
    @JvmStatic
    @Throws(IOException::class)
    fun discover(ctx: Context): List<Group> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("ipod-sonos")
        lock.acquire()
        val hosts = LinkedHashSet<String>()
        try {
            MulticastSocket().use { socket ->
                val msg = SEARCH.toByteArray(Charsets.US_ASCII)
                val group = InetAddress.getByName("239.255.255.250")
                socket.soTimeout = 400
                val end = System.currentTimeMillis() + 2200
                var sent = 0
                while (System.currentTimeMillis() < end) {
                    if (sent < 3) {
                        socket.send(DatagramPacket(msg, msg.size, group, 1900))
                        sent++
                    }
                    val p = DatagramPacket(ByteArray(2048), 2048)
                    try {
                        socket.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val reply = String(p.data, 0, p.length, Charsets.UTF_8)
                    if (!reply.lowercase(Locale.ROOT).contains("sonos")) continue
                    val address = p.address?.hostAddress ?: continue
                    hosts.add("$address:1400")
                }
            }
        } finally {
            lock.release()
        }
        for (host in hosts) {
            try {
                return groups(host)
            } catch (ignored: IOException) {
                // Try the next speaker.
            } catch (ignored: XmlPullParserException) {
            }
        }
        return emptyList()
    }

    /** Asks one speaker for the household's groups, named after their rooms. */
    private fun groups(host: String): List<Group> {
        val reply = soap(host, "/ZoneGroupTopology/Control", "ZoneGroupTopology", "GetZoneGroupState", "")
        val state = value(reply, "ZoneGroupState") ?: throw IOException("No groups")
        val out = ArrayList<Group>()
        val x = Xml.newPullParser()
        x.setInput(StringReader(state))
        var coordinator: String? = null
        var coordinatorName: String? = null
        var coordinatorHost: String? = null
        var others = 0
        var ev = x.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && x.name == "ZoneGroup") {
                coordinator = x.getAttributeValue(null, "Coordinator")
                coordinatorName = null
                coordinatorHost = null
                others = 0
            } else if (ev == XmlPullParser.START_TAG && x.name == "ZoneGroupMember" &&
                // Hidden members are surround speakers and subs; a Boost has no sound at all.
                x.getAttributeValue(null, "Invisible") != "1" &&
                x.getAttributeValue(null, "IsZoneBridge") != "1"
            ) {
                val uuid = x.getAttributeValue(null, "UUID")
                if (uuid != null && uuid == coordinator) {
                    coordinatorName = x.getAttributeValue(null, "ZoneName")
                    val location = URL(x.getAttributeValue(null, "Location"))
                    coordinatorHost = location.host + ":" + location.port
                } else {
                    others++
                }
            } else if (ev == XmlPullParser.END_TAG && x.name == "ZoneGroup") {
                if (coordinatorName != null && coordinatorHost != null && coordinator != null) {
                    val name = if (others > 0) "$coordinatorName +$others" else coordinatorName
                    out.add(Group(name, coordinatorHost, coordinator))
                }
            }
            ev = x.next()
        }
        out.sortWith { a, b -> MusicLibrary.NAME_ORDER.compare(a.name, b.name) }
        return out
    }

    // ---- SOAP --------------------------------------------------------------------------------

    /** Calls a Sonos control action and returns the reply's XML. */
    @JvmStatic
    @Throws(IOException::class)
    fun soap(host: String, path: String, service: String, action: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"urn:schemas-upnp-org:service:$service:1\">$args" +
            "</u:$action></s:Body></s:Envelope>"
        val b = body.toByteArray(Charsets.UTF_8)
        // A plain socket, not HttpURLConnection: Android blocks unencrypted HTTP for the app, and
        // Sonos only speaks plain HTTP. This keeps that block for everything else.
        val colon = host.lastIndexOf(':')
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host.substring(0, colon), host.substring(colon + 1).toInt()), 3000)
            socket.soTimeout = 5000
            val head = "POST $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                "SOAPACTION: \"urn:schemas-upnp-org:service:$service:1#$action\"\r\n" +
                "Content-Length: ${b.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(b)
            out.flush()
            val reply = socket.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            val split = reply.indexOf("\r\n\r\n")
            if (!reply.startsWith("HTTP/") || split < 0) throw IOException("Sonos didn't answer")
            val status = reply.substring(0, reply.indexOf("\r\n"))
            val headers = reply.substring(0, split).lowercase(Locale.ROOT)
            var content = reply.substring(split + 4)
            if (headers.contains("transfer-encoding: chunked")) content = unchunk(content)
            if (!status.contains(" 200 ")) throw IOException("Sonos error: $status")
            return content
        }
    }

    private fun unchunk(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val eol = s.indexOf("\r\n", i)
            if (eol < 0) break
            val size = s.substring(i, eol).split(";")[0].trim().toIntOrNull(16) ?: break
            if (size == 0) break
            out.append(s, eol + 2, minOf(s.length, eol + 2 + size))
            i = eol + 2 + size + 2
        }
        return out.toString()
    }

    /** The text of the first element with this name in a SOAP reply (unescaped). */
    @JvmStatic
    fun value(xml: String, tag: String): String? {
        try {
            val x = Xml.newPullParser()
            x.setInput(StringReader(xml))
            var ev = x.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && x.name == tag) return x.nextText()
                ev = x.next()
            }
        } catch (ignored: XmlPullParserException) {
        } catch (ignored: IOException) {
        }
        return null
    }

    @JvmStatic
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
