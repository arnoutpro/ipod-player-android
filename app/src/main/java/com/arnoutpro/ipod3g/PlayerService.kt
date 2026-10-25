package com.arnoutpro.ipod3g

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.io.IOException
import kotlin.random.Random

/** Plays the queue in the background and exposes it to the lock screen and headsets. */
class PlayerService : Service(), Playback {

    fun interface Listener {
        fun onPlayerChanged()
    }

    inner class LocalBinder : Binder() {
        fun service(): PlayerService = this@PlayerService
    }

    private val binder: IBinder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()

    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantPlay = false
    private var consecutiveErrors = 0

    private var queue: List<Track> = emptyList()
    private var order = IntArray(0) // play order -> queue index
    private var pos = -1            // index into order

    private var shuffle = false
    private var repeat = REPEAT_OFF

    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var resumeOnFocusGain = false
    private var foreground = false
    private var noisyRegistered = false
    private lateinit var prefs: SharedPreferences

    // One audio session for every track, so the equalizer stays attached between songs.
    private var audioSession = 0
    private lateinit var eq: Eq

    // Casting to a Chromecast or Sonos: songs play there instead, fetched from the phone by the server.
    private var cast: CastContext? = null
    private var remote: RemoteSpeaker? = null
    private lateinit var server: MusicServer
    private var pendingSeek = 0
    private var castLeftAt = 0

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = pause()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = isPlaying()
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.25f, 0.25f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (resumeOnFocusGain) play()
                resumeOnFocusGain = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ipod", MODE_PRIVATE)
        shuffle = prefs.getBoolean("shuffle", false)
        repeat = prefs.getInt("repeat", REPEAT_OFF)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audioSession = audio.generateAudioSessionId()
        eq = Eq(prefs, audioSession)
        server = MusicServer(contentResolver)
        try {
            val c = CastContext.getSharedInstance(this)
            cast = c
            c.sessionManager.addSessionManagerListener(castListener, CastSession::class.java)
            val running = c.sessionManager.currentCastSession
            if (running != null && running.isConnected) startCast(running)
        } catch (e: RuntimeException) {
            cast = null // no Google Play services: no casting
        }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(musicAttributes())
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)

        session = MediaSession(this, "iPod")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onStop() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
            override fun onSeekTo(ms: Long) = seekTo(ms.toInt())
        })
        session.setSessionActivity(openAppIntent())
        session.isActive = true
        updateSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaying()) stopSelf()
    }

    override fun onDestroy() {
        releasePlayer()
        eq.release()
        cast?.sessionManager?.removeSessionManagerListener(castListener, CastSession::class.java)
        remote?.release(false)
        server.stop()
        abandonFocus()
        setNoisy(false)
        session.release()
        super.onDestroy()
    }

    // ---- listeners -------------------------------------------------------------------------

    fun eq(): Eq = eq

    fun addListener(l: Listener) {
        if (l !in listeners) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun changed() {
        updateSession()
        updateNotification()
        for (l in ArrayList(listeners)) l.onPlayerChanged()
    }

    // ---- queue -----------------------------------------------------------------------------

    /** Replaces the queue and starts playing [start] (or a random song if -1 and shuffling). */
    fun playQueue(tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return
        queue = ArrayList(tracks)
        pos = buildOrder(if (start < 0) Random.nextInt(queue.size) else start)
        consecutiveErrors = 0
        startTrack(true)
    }

    /** "Shuffle Songs" from the main menu: everything, shuffled, regardless of the setting. */
    fun shuffleAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val old = shuffle
        shuffle = true
        playQueue(tracks, -1)
        shuffle = old
    }

    /** Builds the play order with queue index [first] playing now; returns its position. */
    private fun buildOrder(first: Int): Int {
        val n = queue.size
        if (!shuffle) {
            order = IntArray(n) { it }
            return first
        }
        val rest = (0 until n).filter { it != first }.shuffled()
        order = IntArray(n) { if (it == 0) first else rest[it - 1] }
        return 0
    }

    fun current(): Track? = if (pos < 0 || pos >= order.size) null else queue[order[pos]]

    fun positionInQueue() = pos

    fun queueSize() = queue.size

    override fun hasTrack() = current() != null

    override fun trackKey() = current()?.id?.toString() ?: ""

    override fun title() = current()?.title ?: ""

    override fun artist() = current()?.artist ?: ""

    override fun album() = current()?.album ?: ""

    override fun counter(): String {
        val n = "${pos + 1} of ${queue.size}"
        return if (casting()) "$n on ${speaker()}" else n
    }

    // ---- casting ---------------------------------------------------------------------------

    private fun casting() = remote != null

    override fun speaker(): String? = remote?.name()

    override fun speakerVolume() = remote?.volume() ?: 0

    override fun setSpeakerVolume(percent: Int) {
        val r = remote ?: return
        r.setVolume(percent.coerceIn(0, 100))
        changed()
    }

    /** Stops casting and carries on on this phone, paused where the speaker was. */
    fun stopCasting() {
        if (remote is CastSpeaker) {
            cast?.sessionManager?.endCurrentSession(true) // castListener finishes up
        } else if (remote != null) {
            endRemote(true)
        }
    }

    /** Plays the phone's own music on this Sonos group. */
    fun castToSonos(g: Sonos.Group) {
        if (castingTo(g)) return
        val old = remote
        startRemote(SonosSpeaker(g, remoteListener))
        if (old is CastSpeaker) cast?.sessionManager?.endCurrentSession(true)
    }

    /** True while this Sonos group is the speaker. */
    fun castingTo(g: Sonos.Group): Boolean = (remote as? SonosSpeaker)?.group == g

    private val castListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) = startCast(s)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = startCast(s)
        override fun onSessionEnding(s: CastSession) = castEnding(s)
        override fun onSessionEnded(s: CastSession, error: Int) = castEnded(s)
        override fun onSessionSuspended(s: CastSession, reason: Int) = castEnded(s)
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStartFailed(s: CastSession, error: Int) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
    }

    private val remoteListener = object : RemoteSpeaker.Listener {
        override fun onChanged() = changed()

        override fun onFinished() {
            if (repeat == REPEAT_ONE) {
                loadRemote(0, true)
            } else {
                wantPlay = true
                next()
            }
        }
    }

    private fun startCast(s: CastSession) {
        if ((remote as? CastSpeaker)?.session === s) return
        if (s.remoteMediaClient == null) return
        startRemote(CastSpeaker(s, remoteListener))
    }

    /** A speaker was picked: move the song there, at the same spot, playing or paused. */
    private fun startRemote(r: RemoteSpeaker) {
        val at = getPosition()
        val play = wantPlay
        remote?.release(true)
        releasePlayer()
        remote = r
        if (hasTrack()) loadRemote(at, play)
        setNoisy(false)
        changed()
    }

    private fun castEnding(s: CastSession) {
        val r = remote
        if (r is CastSpeaker && r.session === s) castLeftAt = r.position()
    }

    private fun castEnded(s: CastSession) {
        // Ignore a session that ended because another speaker took over.
        val r = remote
        if (r !is CastSpeaker || r.session !== s) return
        val at = castLeftAt
        r.release(false)
        remote = null
        resumeHere(at)
    }

    /** Leaves the speaker and carries on on this phone from where it was, paused. */
    private fun endRemote(stop: Boolean) {
        val r = remote ?: return
        val at = r.position()
        r.release(stop)
        remote = null
        resumeHere(at)
    }

    private fun resumeHere(at: Int) {
        server.stop() // no speaker needs it now; publish() starts it again
        if (hasTrack()) {
            startTrack(false)
            pendingSeek = at
        }
        changed()
    }

    private fun loadRemote(positionMs: Int, play: Boolean) {
        val t = current() ?: return
        val r = remote ?: return
        try {
            val url = server.publish(t)
            wantPlay = play
            r.load(url, server.type(), t, positionMs, play)
            if (play) goForeground() // keeps the server running in the background
        } catch (e: IOException) {
            stopCasting() // no Wi-Fi: play here instead
        }
    }

    override fun isShuffle() = shuffle

    override fun repeatMode() = repeat

    fun setShuffle(on: Boolean) {
        if (on == shuffle) return
        shuffle = on
        prefs.edit().putBoolean("shuffle", on).apply()
        if (hasTrack()) pos = buildOrder(order[pos])
        changed()
    }

    fun setRepeat(mode: Int) {
        repeat = mode
        prefs.edit().putInt("repeat", mode).apply()
        changed()
    }

    // ---- transport -------------------------------------------------------------------------

    fun isPlaying(): Boolean {
        remote?.let { return it.isPlaying() }
        val p = player
        return p != null && prepared && p.isPlaying
    }

    /** True while the user wants sound, even if the track is still loading. */
    override fun isPlayingOrLoading() = wantPlay && hasTrack()

    fun play() {
        if (!hasTrack()) return
        val r = remote
        if (r != null) {
            wantPlay = true
            if (r.idle()) loadRemote(0, true) else r.play()
            goForeground()
            changed()
            return
        }
        if (!requestFocus()) return
        wantPlay = true
        val p = player
        if (p != null && prepared) p.start() else if (p == null) startTrack(true)
        goForeground()
        setNoisy(true)
        changed()
    }

    fun pause() {
        wantPlay = false
        remote?.pause()
        val p = player
        if (p != null && prepared && p.isPlaying) p.pause()
        setNoisy(false)
        leaveForeground()
        changed()
    }

    override fun toggle() {
        if (isPlayingOrLoading()) pause() else play()
    }

    override fun next() {
        if (!hasTrack()) return
        when {
            pos + 1 < order.size -> pos++
            repeat == REPEAT_ALL -> pos = 0
            else -> return stopAtEnd()
        }
        startTrack(wantPlay)
    }

    override fun previous() {
        if (!hasTrack()) return
        if (getPosition() > 3000 || pos == 0 && repeat != REPEAT_ALL) return seekTo(0)
        pos = if (pos > 0) pos - 1 else order.size - 1
        startTrack(wantPlay)
    }

    override fun seekTo(ms: Int) {
        val r = remote
        if (r != null) {
            r.seek(ms.coerceIn(0, maxOf(0, getDuration())))
            changed()
            return
        }
        val p = player
        if (p != null && prepared) {
            p.seekTo(ms.coerceIn(0, maxOf(0, getDuration())))
            changed()
        }
    }

    override fun getPosition(): Int {
        remote?.let { return it.position() }
        val p = player
        return if (p != null && prepared) p.currentPosition else 0
    }

    override fun getDuration(): Int {
        remote?.let { if (it.duration() > 0) return it.duration() }
        val p = player
        if (p != null && prepared) return p.duration
        return current()?.durationMs?.toInt() ?: 0
    }

    private fun stopAtEnd() {
        // Like the real thing: stop and rewind to the first song of the list.
        pos = 0
        wantPlay = false
        releasePlayer()
        setNoisy(false)
        leaveForeground()
        changed()
    }

    private fun startTrack(autoPlay: Boolean) {
        releasePlayer()
        val t = current() ?: return
        wantPlay = autoPlay
        bumpPlayCount(t)
        if (casting()) {
            loadRemote(0, autoPlay)
            changed()
            return
        }
        if (autoPlay && !requestFocus()) wantPlay = false

        val mp = MediaPlayer()
        player = mp
        prepared = false
        mp.setAudioAttributes(musicAttributes())
        mp.audioSessionId = audioSession
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setOnPreparedListener { m ->
            if (m !== player) return@setOnPreparedListener
            prepared = true
            consecutiveErrors = 0
            if (pendingSeek > 0) m.seekTo(pendingSeek)
            pendingSeek = 0
            if (wantPlay) m.start()
            changed()
        }
        mp.setOnCompletionListener { m ->
            if (m !== player) return@setOnCompletionListener
            if (repeat == REPEAT_ONE) {
                m.seekTo(0)
                m.start()
            } else {
                wantPlay = true
                next()
            }
        }
        mp.setOnErrorListener { m, _, _ ->
            if (m === player) {
                if (++consecutiveErrors >= minOf(order.size, 10)) stopAtEnd() else main.post { next() }
            }
            true
        }
        try {
            mp.setDataSource(this, t.uri)
            mp.prepareAsync()
        } catch (e: Exception) {
            if (++consecutiveErrors >= minOf(order.size, 10)) stopAtEnd() else main.post { next() }
            return
        }
        if (wantPlay) {
            goForeground()
            setNoisy(true)
        }
        changed()
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        prepared = false
        try {
            p.release()
        } catch (ignored: Exception) {
        }
    }

    // ---- play counts (for "Top 25 Most Played") --------------------------------------------

    private fun bumpPlayCount(t: Track) {
        val k = "pc_${t.id}"
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }

    // ---- audio focus / noisy ---------------------------------------------------------------

    private fun requestFocus() = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        audio.abandonAudioFocusRequest(focusRequest)
    }

    private fun setNoisy(on: Boolean) {
        if (on == noisyRegistered) return
        noisyRegistered = on
        if (on) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        else unregisterReceiver(noisy)
    }

    // ---- media session / notification ------------------------------------------------------

    private fun updateSession() {
        val t = current()
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        val state = when {
            t == null -> PlaybackState.STATE_NONE
            isPlayingOrLoading() -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, getPosition().toLong(), if (isPlaying()) 1f else 0f)
                .build()
        )
        if (t != null) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, getDuration().toLong())
                    .build()
            )
        }
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(a: String, code: Int): PendingIntent {
        val i = Intent(this, PlayerService::class.java).setAction(a)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(): Notification {
        val t = current()
        val playing = isPlayingOrLoading()
        val prev = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Previous", action(ACTION_PREV, 1)
        ).build()
        val toggle = Notification.Action.Builder(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "Pause" else "Play", action(ACTION_TOGGLE, 2)
        ).build()
        val next = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_next), "Next", action(ACTION_NEXT, 3)
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t?.title ?: "iPod")
            .setContentText(if (t != null) "${t.artist} — ${t.album}" else "")
            .setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .addAction(prev).addAction(toggle).addAction(next)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun goForeground() {
        if (foreground) return
        val n = buildNotification()
        try {
            // Make sure we outlive the activity binding while music plays.
            startService(Intent(this, PlayerService::class.java))
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            foreground = true
        } catch (e: Exception) {
            // Starting a foreground service from the background can be refused; keep playing anyway.
        }
    }

    private fun leaveForeground() {
        if (!foreground) return
        foreground = false
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun updateNotification() {
        if (!hasTrack()) return
        val nm = getSystemService(NotificationManager::class.java)
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (ignored: SecurityException) {
            // Notifications not allowed: the app still works.
        }
    }

    companion object {
        const val REPEAT_OFF = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2

        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 3
        private const val ACTION_TOGGLE = "com.arnoutpro.ipod3g.TOGGLE"
        private const val ACTION_NEXT = "com.arnoutpro.ipod3g.NEXT"
        private const val ACTION_PREV = "com.arnoutpro.ipod3g.PREV"

        private fun musicAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        @JvmStatic
        fun playCount(ctx: Context, t: Track): Int =
            ctx.getSharedPreferences("ipod", MODE_PRIVATE).getInt("pc_${t.id}", 0)
    }
}
