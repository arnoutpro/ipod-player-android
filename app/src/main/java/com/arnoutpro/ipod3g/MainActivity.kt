package com.arnoutpro.ipod3g

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.media.MediaRouter2
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import java.io.IOException

class MainActivity : Activity(), PlayerService.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var view: IPodView
    private lateinit var lcd: Lcd
    private lateinit var menus: Menus
    private lateinit var prefs: SharedPreferences
    private lateinit var clicker: Clicker
    private var service: PlayerService? = null
    private lateinit var spotify: Spotify
    private lateinit var spotifyPlayer: SpotifyPlayer
    private lateinit var spotifyLibrary: SpotifyLibrary

    /** Spotify is what Now Playing and the buttons control, until local music plays again. */
    private var spotifyActive = false
    private var spotifyError: String? = null
    private var front = false
    private var router: MediaRouter? = null
    private var castSelector: MediaRouteSelector? = null
    private var library = MusicLibrary()
    private var battery = 100
    private var charging = false
    private var libraryRequested = false
    private var sonos: List<Sonos.Group> = emptyList()
    private var sonosSearching = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, b: IBinder) {
            val s = (b as PlayerService.LocalBinder).service()
            service = s
            s.addListener(this@MainActivity)
            refreshScreen()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            battery = if (scale > 0) level * 100 / scale else level
            charging = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_CHARGING
            redraw()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = getSharedPreferences("ipod", MODE_PRIVATE)
        spotify = Spotify(prefs)
        spotifyPlayer = SpotifyPlayer(spotify, this)
        spotifyLibrary = SpotifyLibrary(spotify, filesDir) { spotifyLibraryChanged() }
        try {
            // Started here too, so picking a Chromecast always opens a cast session.
            CastContext.getSharedInstance(this)
            router = MediaRouter.getInstance(this)
            castSelector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastOptionsProvider.RECEIVER))
                .build()
        } catch (e: RuntimeException) {
            router = null // no Google Play services: no casting
        }
        clicker = Clicker()
        view = IPodView(this)
        lcd = view.lcd
        menus = Menus(lcd)
        setContentView(view)
        if (Build.VERSION.SDK_INT >= 29) {
            // The face has its own black finish; don't let the system (or ColorOS) recolour it.
            view.isForceDarkAllowed = false
            window.decorView.isForceDarkAllowed = false
        }
        applyFinish()
        goFullScreen()
        volumeControlStream = AudioManager.STREAM_MUSIC

        lcd.reset(menus.root())
        bindService(Intent(this, PlayerService::class.java), connection, BIND_AUTO_CREATE)
        if (!hasMusicPermission()) requestMusicPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Spotify's login page comes back here through [Spotify.REDIRECT]. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.toString().startsWith(Spotify.REDIRECT)) return
        intent.data = null
        spotifyError = "Connecting..."
        refreshScreen()
        spotify.finishLogin(data) { _, err ->
            spotifyError = err
            if (err == null) spotifyLibrary.refresh()
            refreshScreen()
        }
    }

    /** Rebuilds the open menu, e.g. the Spotify menu once signed in. */
    private fun refreshScreen() {
        lcd.top()?.shown()
        redraw()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        applyFinish() // Auto follows the system's dark mode
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    /** Hides the status and navigation bars; a swipe from the edge shows them briefly. */
    @Suppress("DEPRECATION")
    private fun goFullScreen() {
        val w = window
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = w.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            w.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system settings after granting access there.
        if (!libraryRequested && hasMusicPermission()) loadLibrary()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        front = true
        if (spotifyActive) {
            spotifyPlayer.startPolling()
        } else if (service?.isPlayingOrLoading() != true) {
            // Spotify may be playing (or paused) from before, here or on a speaker: pick it up.
            spotifyPlayer.probe {
                if (spotifyActive || service?.isPlayingOrLoading() == true) return@probe
                spotifyActive = true
                spotifyPlayer.startPolling()
                refreshScreen()
            }
        }
        view.wake()
        view.startTilt()
    }

    override fun onPause() {
        unregisterReceiver(batteryReceiver)
        front = false
        view.stopTilt()
        spotifyPlayer.stopPolling()
        super.onPause()
    }

    override fun onDestroy() {
        service?.removeListener(this)
        unbindService(connection)
        clicker.release()
        spotifyPlayer.release()
        super.onDestroy()
    }

    /** While music plays on a speaker, the volume buttons set that speaker's volume. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val d = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> 0
        }
        val pb = playback()
        if (d != 0 && pb != null && pb.speaker() != null) {
            view.wake()
            val top = lcd.top()
            if (top is NowPlayingScreen) top.volumeKey(d) else NowPlayingScreen.speakerVolume(pb, d)
            redraw()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Back behaves like MENU")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Back behaves like MENU; at the main menu leave the app but keep the music going.
        if (!lcd.menu()) moveTaskToBack(true)
    }

    override fun onPlayerChanged() {
        if (spotifyActive && service?.isPlayingOrLoading() == true) {
            // Local music took over (Spotify pauses itself when it loses audio focus; on a Sonos
            // the iPod pauses it).
            spotifyPlayer.localTookOver()
            spotifyActive = false
            spotifyPlayer.stopPolling()
        }
        redraw()
    }

    // ---- library & permissions ---------------------------------------------------------------

    fun hasMusicPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun requestMusicPermission() {
        val asked = prefs.getInt("permAsked", 0)
        prefs.edit().putInt("permAsked", asked + 1).apply()
        if (asked >= 2) {
            // Android stops showing the dialog after two refusals; open the app's settings instead.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            return
        }
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        if (code == REQ_PERMS && hasMusicPermission()) {
            lcd.reset(menus.root())
            loadLibrary()
        }
    }

    private fun loadLibrary() {
        libraryRequested = true
        Thread({
            val lib = MusicLibrary.load(applicationContext)
            main.post {
                library = lib
                lcd.reset(menus.root())
            }
        }, "library").start()
    }

    fun rescan() = loadLibrary()

    // ---- things the screens need ------------------------------------------------------------

    fun service(): PlayerService? = service

    /** True while the iPod is on screen. */
    fun isFront() = front

    /** What Now Playing and the buttons control right now. */
    fun playback(): Playback? = if (spotifyActive) spotifyPlayer else service

    /** True while Spotify, not the phone's own music, is what Now Playing and the buttons control. */
    fun spotifyPlaying() = spotifyActive

    fun spotify() = spotify

    fun menus() = menus

    fun spotifyLibrary() = spotifyLibrary

    fun spotifyPlayer() = spotifyPlayer

    /** Chromecasts and other Cast devices found on the network. */
    fun castSpeakers(): List<MediaRouter.RouteInfo> {
        val r = router ?: return emptyList()
        val selector = castSelector ?: return emptyList()
        return r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
    }

    /** Looks for Cast devices for a while, calling changed as they come and go. */
    fun findCastSpeakers(changed: Runnable) {
        val r = router ?: return
        val selector = castSelector ?: return
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = changed.run()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = changed.run()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = changed.run()
            override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = changed.run()
        }
        r.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        // Scanning costs battery: stop after a while.
        main.postDelayed({ r.removeCallback(cb) }, 20_000)
    }

    /** The Sonos groups found last time. */
    fun sonosSpeakers(): List<Sonos.Group> = sonos

    /** Searches the Wi-Fi for Sonos speakers (about two seconds), then calls changed. */
    fun findSonos(changed: Runnable) {
        if (sonosSearching) return
        sonosSearching = true
        Thread({
            val found = try {
                Sonos.discover(this)
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            main.post {
                sonosSearching = false
                if (found != null) sonos = found
                changed.run()
            }
        }, "sonos-search").start()
    }

    /** Plays the phone's own music on this Cast device. */
    fun castTo(route: MediaRouter.RouteInfo) {
        router?.selectRoute(route)
    }

    /** Android's own picker for Bluetooth and other audio outputs of this phone. */
    fun showOutputSwitcher() {
        if (Build.VERSION.SDK_INT >= 34 && MediaRouter2.getInstance(this).showSystemOutputSwitcher()) return
        try {
            startActivity(
                Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", packageName)
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    /** More of the Spotify library arrived: rebuild the list on screen to show it. */
    private fun spotifyLibraryChanged() {
        val top = lcd.top()
        if (top is ListScreen) top.shown()
        redraw()
    }

    /** The last sign-in problem, shown in the Spotify menu. */
    fun spotifyError() = spotifyError

    fun connectSpotify() {
        spotifyError = null
        spotify.login(this)
    }

    fun disconnectSpotify() {
        spotify.logout()
        spotifyLibrary.clear()
        if (spotifyActive) {
            spotifyActive = false
            spotifyPlayer.stopPolling()
        }
        refreshScreen()
    }

    /** Plays Spotify songs and switches the iPod over to them. */
    fun playSpotify(rq: SpotifyPlayer.Request) {
        service?.let { if (it.isPlayingOrLoading()) it.pause() }
        spotifyActive = true
        spotifyPlayer.play(rq)
        spotifyPlayer.startPolling()
        lcd.showNowPlaying()
    }

    fun library() = library

    fun prefs() = prefs

    fun clicker() = clicker

    fun batteryLevel() = battery

    fun isCharging() = charging

    fun redraw() {
        if (::view.isInitialized) view.invalidate()
    }

    fun clickerOn() = prefs.getBoolean("clicker", true)

    fun setClicker(on: Boolean) {
        prefs.edit().putBoolean("clicker", on).apply()
    }

    /** The iPod classic (6th gen) look: colour, smooth text and album art beside the top menus. */
    fun sixthGen() = prefs.getBoolean("split", false) // named after the first, two-column design

    fun setSixthGen(on: Boolean) {
        prefs.edit().putBoolean("split", on).apply()
    }

    /** 0 = off, -1 = always on, otherwise seconds. */
    fun backlightSeconds() = prefs.getInt("backlight", 10)

    fun setBacklightSeconds(s: Int) {
        prefs.edit().putInt("backlight", s).apply()
        view.wake()
    }

    fun toggleBacklight() = view.toggleBacklight()

    fun finishMode() = prefs.getInt("finish", FINISH_WHITE)

    fun setFinishMode(m: Int) {
        prefs.edit().putInt("finish", m).apply()
        applyFinish()
    }

    private fun applyFinish() {
        val m = finishMode()
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        view.setFinish(if (m == FINISH_BLACK || (m == FINISH_AUTO && night)) Finish.BLACK else Finish.WHITE)
    }

    /** A short buzz to confirm e.g. adding a song to On-The-Go. */
    fun buzz() {
        val v = getSystemService(Vibrator::class.java)
        if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        private const val REQ_PERMS = 1
        const val FINISH_WHITE = 0
        const val FINISH_BLACK = 1
        const val FINISH_AUTO = 2
    }
}
