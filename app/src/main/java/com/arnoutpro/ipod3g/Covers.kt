package com.arnoutpro.ipod3g

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Cover art for Now Playing: read from the song file (own songs) or downloaded (Spotify), on a
 * background thread. [get] returns null until it has arrived, then the screen is redrawn.
 */
class Covers(private val host: MainActivity) {
    private val cache = LruCache<String, Bitmap>(12)
    private val missing = HashSet<String>()
    private val loading = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()

    /** The cover for a content:// song or an https:// image, or null while loading or when there is none. */
    fun get(key: String?): Bitmap? {
        if (key.isNullOrEmpty()) return null
        cache.get(key)?.let { return it }
        if (key in missing || !loading.add(key)) return null
        worker.execute {
            val bmp = try {
                if (key.startsWith("http")) download(key) else fromSong(Uri.parse(key))
            } catch (e: Exception) {
                null
            }
            host.runOnUiThread {
                loading.remove(key)
                if (bmp != null) cache.put(key, bmp) else missing.add(key)
                host.redraw()
            }
        }
        return null
    }

    /** True once a cover turned out not to exist, so the caller can try another. */
    fun isMissing(key: String?) = key != null && key in missing

    private fun fromSong(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return host.contentResolver.loadThumbnail(uri, Size(SIZE, SIZE), null)
            } catch (ignored: Exception) {
                // No thumbnail: try the picture inside the file.
            }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(host, uri)
            return r.embeddedPicture?.let { decode(it) }
        } finally {
            r.release()
        }
    }

    private fun download(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        try {
            return decode(c.inputStream.use { it.readBytes() })
        } finally {
            c.disconnect()
        }
    }

    /** Decodes at about [SIZE] pixels, so a large cover doesn't take much memory. */
    private fun decode(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= SIZE) sample *= 2
        o.inJustDecodeBounds = false
        o.inSampleSize = sample
        return BitmapFactory.decodeByteArray(b, 0, b.size, o)
    }

    companion object {
        private const val SIZE = 400

        /** From Spotify's list of a cover's sizes (largest first): the smallest of at least 300 pixels. */
        @JvmStatic
        fun pick(images: JSONArray?): String? {
            if (images == null || images.length() == 0) return null
            var best = images.optJSONObject(0)
            for (i in 1 until images.length()) {
                val im = images.optJSONObject(i) ?: continue
                if (im.optInt("width", 0) >= 300) best = im
            }
            return best?.optString("url")?.ifEmpty { null }
        }
    }
}
