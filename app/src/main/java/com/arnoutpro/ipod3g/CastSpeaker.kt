package com.arnoutpro.ipod3g

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import java.io.IOException
import kotlin.math.roundToInt

/** A Chromecast, Google TV or Nest speaker, through a Cast session. */
class CastSpeaker(val session: CastSession, private val listener: RemoteSpeaker.Listener) : RemoteSpeaker {
    private val client: RemoteMediaClient = session.remoteMediaClient!!
    private var finishHandled = false

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val st = client.mediaStatus
            if (st != null && st.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                st.idleReason == MediaStatus.IDLE_REASON_FINISHED && !finishHandled
            ) {
                finishHandled = true
                listener.onFinished()
            }
            listener.onChanged()
        }
    }

    init {
        client.registerCallback(callback)
    }

    override fun name(): String = session.castDevice?.friendlyName ?: "Chromecast"

    override fun load(url: String, type: String, t: Track, positionMs: Int, play: Boolean) {
        val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, t.title)
            putString(MediaMetadata.KEY_ARTIST, t.artist)
            putString(MediaMetadata.KEY_ALBUM_TITLE, t.album)
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(type)
            .setStreamDuration(t.durationMs)
            .setMetadata(md)
            .build()
        finishHandled = false
        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(info).setAutoplay(play).setCurrentTime(positionMs.toLong()).build()
        )
    }

    override fun play() {
        client.play()
    }

    override fun pause() {
        client.pause()
    }

    override fun seek(ms: Int) {
        client.seek(MediaSeekOptions.Builder().setPosition(ms.toLong()).build())
    }

    override fun isPlaying() = client.isPlaying

    override fun idle(): Boolean {
        val st = client.mediaStatus
        return st == null || st.playerState == MediaStatus.PLAYER_STATE_IDLE
    }

    override fun position() = client.approximateStreamPosition.toInt()

    override fun duration() = client.streamDuration.toInt()

    override fun volume() = (session.volume * 100).roundToInt()

    override fun setVolume(percent: Int) {
        try {
            session.volume = percent / 100.0
        } catch (ignored: IOException) {
        } catch (ignored: IllegalStateException) {
        }
    }

    /** Ending the session itself is up to the Cast framework (the player calls endCurrentSession). */
    override fun release(stop: Boolean) {
        client.unregisterCallback(callback)
    }
}
