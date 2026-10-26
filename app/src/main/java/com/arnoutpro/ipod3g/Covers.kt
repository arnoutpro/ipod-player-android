package com.arnoutpro.ipod3g

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import org.json.JSONArray
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Cover art: read from the song file (own songs) or downloaded (Spotify), on a background thread.
 * [get] returns null until it has arrived, then the screen is redrawn.
 */
class Covers(private val host: MainActivity) {
    // Enough for a screenful of Cover Flow and a bit either side.
    private val cache = LruCache<String, Bitmap>(40)
    private val missing = HashSet<String>()
    /** Asked for and not loaded yet; the newest is loaded first, so the covers on screen come first. */
    private val waiting = ArrayDeque<String>()
    private var worker: Job? = null
    private val scope = MainScope()
    private var placeholder: Bitmap? = null

    /** The cover for a content:// song or an https:// image, or null while loading or when there is none. */
    fun get(key: String?): Bitmap? {
        if (key.isNullOrEmpty()) return null
        cache.get(key)?.let { return it }
        if (key in missing || key in waiting) return null
        waiting.addLast(key)
        // Flipping fast through Cover Flow passes covers nobody waits for any more.
        while (waiting.size > MAX_WAITING) waiting.removeFirst()
        if (worker?.isActive != true) worker = scope.launch { work() }
        return null
    }

    private suspend fun work() {
        while (waiting.isNotEmpty()) {
            val key = waiting.last()
            val bmp = withContext(Dispatchers.IO) {
                try {
                    if (key.startsWith("http")) download(key) else fromSong(Uri.parse(key))
                } catch (e: Exception) {
                    null
                }
            }
            waiting.remove(key)
            if (bmp != null) cache.put(key, bmp) else missing.add(key)
            host.redraw()
        }
    }

    /** A grey square with a note, like the iPod's own, for songs without a cover. */
    fun placeholder(): Bitmap {
        placeholder?.let { return it }
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val pc = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 300f, 0xFFC9CDD2.toInt(), 0xFF8E949B.toInt(), Shader.TileMode.CLAMP)
        pc.drawRect(0f, 0f, 300f, 300f, p)
        p.shader = null
        p.color = Color.WHITE
        p.textSize = 180f
        p.textAlign = Paint.Align.CENTER
        pc.drawText("\u266A", 150f, Lcd.baseline(p, 0f, 300f), p)
        placeholder = b
        return b
    }

    /** Stops loading when the iPod closes. */
    fun release() = scope.cancel()

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
        o.inPreferredConfig = Bitmap.Config.RGB_565 // covers have no transparency: half the memory
        return BitmapFactory.decodeByteArray(b, 0, b.size, o)
    }

    companion object {
        private const val SIZE = 400
        private const val MAX_WAITING = 16

        /** From Spotify's list of a cover's sizes (largest first): the smallest of at least 300 pixels. */
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

/**
 * Draws a cover into any four-cornered shape: turned in perspective, or mirrored below its bottom
 * edge as a reflection. Corners go top-left, top-right, bottom-right, bottom-left.
 */
class CoverQuad {
    private val matrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)

    fun draw(
        c: Canvas, b: Bitmap, paint: Paint, mirrored: Boolean,
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float,
    ) {
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val top = if (mirrored) h else 0f
        val bottom = if (mirrored) 0f else h
        src[0] = 0f; src[1] = top; src[2] = w; src[3] = top
        src[4] = w; src[5] = bottom; src[6] = 0f; src[7] = bottom
        dst[0] = ax; dst[1] = ay; dst[2] = bx; dst[3] = by
        dst[4] = cx; dst[5] = cy; dst[6] = dx; dst[7] = dy
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
        c.save()
        c.concat(matrix)
        c.drawBitmap(b, 0f, 0f, paint)
        c.restore()
    }
}
