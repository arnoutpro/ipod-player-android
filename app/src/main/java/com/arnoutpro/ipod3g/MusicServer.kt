package com.arnoutpro.ipod3g

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.FileInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale

/**
 * Lets a Chromecast or Sonos fetch the current song from this phone over Wi-Fi: a tiny HTTP server
 * with range requests for seeking. Only the current song is served, behind a random path, so
 * nobody else on the network can browse the phone's music.
 */
class MusicServer(private val resolver: ContentResolver) {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    private var server: ServerSocket? = null
    @Volatile private var song: Uri? = null
    @Volatile private var songPath: String? = null
    @Volatile private var songType: String = "audio/mpeg"

    /** Serves this song from now on; returns the address the speaker can fetch it from. */
    @Throws(IOException::class)
    fun publish(t: Track): String {
        val s = server ?: start()
        val ip = wifiAddress() ?: throw IOException("No Wi-Fi")
        song = t.uri
        songType = mimeType(t.uri)
        // An extension helps speakers like Sonos tell the format from the address.
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(songType)
        val path = "/$token/${t.id}" + (ext?.let { ".$it" } ?: "")
        songPath = path
        return "http://$ip:${s.localPort}$path"
    }

    fun type(): String = songType

    fun stop() {
        song = null
        try {
            server?.close()
        } catch (ignored: IOException) {
        }
        server = null
    }

    private fun start(): ServerSocket {
        val s = ServerSocket(0)
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try {
                    s.accept()
                } catch (e: IOException) {
                    return@Thread // closed
                }
                Thread({ serve(c) }, "music-request").start()
            }
        }, "music-server").apply {
            isDaemon = true
            start()
        }
        return s
    }

    private fun serve(c: Socket) {
        try {
            c.use { socket ->
                val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val request = input.readLine() ?: return
                var range: String? = null
                while (true) {
                    val h = input.readLine()
                    if (h.isNullOrEmpty()) break
                    if (h.lowercase(Locale.ROOT).startsWith("range:")) range = h.substring(6).trim()
                }
                val parts = request.split(" ")
                val out = socket.getOutputStream()
                val uri = song
                if (parts.size < 2 || uri == null || parts[1] != songPath) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    return
                }
                val pfd = resolver.openFileDescriptor(uri, "r") ?: return
                pfd.use {
                    FileInputStream(pfd.fileDescriptor).use { file ->
                        val size = pfd.statSize
                        var start = 0L
                        var end = size - 1
                        var partial = false
                        if (range != null && range.startsWith("bytes=")) {
                            val r = range.substring(6).split("-", limit = 2)
                            try {
                                if (r[0].isNotEmpty()) start = r[0].toLong()
                                if (r.size > 1 && r[1].isNotEmpty()) end = minOf(end, r[1].toLong())
                                else if (r[0].isEmpty() && r.size > 1) start = maxOf(0, size - r[1].toLong())
                                partial = true
                            } catch (ignored: NumberFormatException) {
                            }
                        }
                        val length = maxOf(0, end - start + 1)
                        val head = StringBuilder()
                            .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                            .append("Content-Type: ").append(songType).append("\r\n")
                            .append("Content-Length: ").append(length).append("\r\n")
                            .append("Accept-Ranges: bytes\r\n")
                            .append("Access-Control-Allow-Origin: *\r\n")
                            .append("Connection: close\r\n")
                        if (partial) head.append("Content-Range: bytes $start-$end/$size\r\n")
                        head.append("\r\n")
                        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                        if (parts[0] == "HEAD") return
                        file.channel.position(start)
                        val buf = ByteArray(64 * 1024)
                        var left = length
                        while (left > 0) {
                            val n = file.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            left -= n
                        }
                        out.flush()
                    }
                }
            }
        } catch (ignored: IOException) {
            // The speaker hung up, e.g. to seek: normal.
        }
    }

    private fun mimeType(uri: Uri): String {
        val t = resolver.getType(uri)
        return if (t != null && t.startsWith("audio/")) t else "audio/mpeg"
    }

    private companion object {
        /** This phone's address on the local network, preferring Wi-Fi. */
        fun wifiAddress(): String? {
            var any: String? = null
            try {
                for (ni in NetworkInterface.getNetworkInterfaces() ?: return null) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) {
                        if (a !is Inet4Address || !a.isSiteLocalAddress) continue
                        if (ni.name.startsWith("wlan")) return a.hostAddress
                        if (any == null) any = a.hostAddress
                    }
                }
            } catch (ignored: IOException) {
            }
            return any
        }
    }
}
