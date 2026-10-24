package com.arnoutpro.ipod3g;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements PlayerService.Listener {
    private static final int REQ_PERMS = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private IPodView view;
    private Lcd lcd;
    private Menus menus;
    private SharedPreferences prefs;
    private Clicker clicker;
    private PlayerService service;
    private Spotify spotify;
    private SpotifyPlayer spotifyPlayer;
    private SpotifyLibrary spotifyLibrary;
    /** Spotify is what Now Playing and the buttons control, until local music plays again. */
    private boolean spotifyActive;
    private String spotifyError;
    private boolean front;
    private MusicLibrary library = new MusicLibrary();
    private int battery = 100;
    private boolean charging;
    private boolean libraryRequested;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((PlayerService.LocalBinder) b).service();
            service.addListener(MainActivity.this);
            refreshMenu();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            battery = scale > 0 ? level * 100 / scale : level;
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING;
            redraw();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("ipod", MODE_PRIVATE);
        spotify = new Spotify(prefs);
        spotifyPlayer = new SpotifyPlayer(spotify, this);
        spotifyLibrary = new SpotifyLibrary(spotify, getFilesDir(), this::spotifyLibraryChanged);
        clicker = new Clicker();
        view = new IPodView(this);
        lcd = view.lcd;
        menus = new Menus(lcd);
        setContentView(view);
        if (Build.VERSION.SDK_INT >= 29) {
            // The face has its own black finish; don't let the system (or ColorOS) recolour it.
            view.setForceDarkAllowed(false);
            getWindow().getDecorView().setForceDarkAllowed(false);
        }
        applyFinish();
        goFullScreen();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        lcd.reset(menus.root());
        bindService(new Intent(this, PlayerService.class), connection, BIND_AUTO_CREATE);
        if (!hasMusicPermission()) requestMusicPermission();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Spotify's login page comes back here through {@link Spotify#REDIRECT}. */
    private void handleIntent(Intent intent) {
        android.net.Uri data = intent == null ? null : intent.getData();
        if (data == null || !data.toString().startsWith(Spotify.REDIRECT)) return;
        intent.setData(null);
        spotifyError = "Connecting...";
        refreshScreen();
        spotify.finishLogin(data, (r, err) -> {
            spotifyError = err;
            if (err == null) spotifyLibrary.refresh();
            refreshScreen();
        });
    }

    /** Rebuilds the open menu, e.g. the Spotify menu once signed in. */
    private void refreshScreen() {
        if (lcd.top() != null) lcd.top().shown();
        redraw();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyFinish(); // Auto follows the system's dark mode
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullScreen();
    }

    /** Hides the status and navigation bars; a swipe from the edge shows them briefly. */
    @SuppressWarnings("deprecation")
    private void goFullScreen() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the system settings after granting access there.
        if (!libraryRequested && hasMusicPermission()) loadLibrary();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        front = true;
        if (spotifyActive) {
            spotifyPlayer.startPolling();
        } else if (service == null || !service.isPlayingOrLoading()) {
            // Spotify may be playing (or paused) from before, here or on a speaker: pick it up.
            spotifyPlayer.probe(() -> {
                if (spotifyActive || service != null && service.isPlayingOrLoading()) return;
                spotifyActive = true;
                spotifyPlayer.startPolling();
                refreshScreen();
            });
        }
        view.wake();
        view.startTilt();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(batteryReceiver);
        front = false;
        view.stopTilt();
        spotifyPlayer.stopPolling();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (service != null) service.removeListener(this);
        unbindService(connection);
        clicker.release();
        spotifyPlayer.release();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Back behaves like MENU; at the main menu leave the app but keep the music going.
        if (!lcd.menu()) moveTaskToBack(true);
    }

    @Override
    public void onPlayerChanged() {
        if (spotifyActive && service != null && service.isPlayingOrLoading()) {
            // Local music took over (Spotify pauses itself when it loses audio focus).
            spotifyActive = false;
            spotifyPlayer.stopPolling();
        }
        redraw();
    }

    // ---- library & permissions ---------------------------------------------------------------

    public boolean hasMusicPermission() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    public void requestMusicPermission() {
        int asked = prefs.getInt("permAsked", 0);
        prefs.edit().putInt("permAsked", asked + 1).apply();
        if (asked >= 2) {
            // Android stops showing the dialog after two refusals; open the app's settings instead.
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", getPackageName(), null)));
            return;
        }
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERMS && hasMusicPermission()) {
            lcd.reset(menus.root());
            loadLibrary();
        }
    }

    private void loadLibrary() {
        libraryRequested = true;
        new Thread(() -> {
            MusicLibrary lib = MusicLibrary.load(getApplicationContext());
            main.post(() -> {
                library = lib;
                lcd.reset(menus.root());
            });
        }, "library").start();
    }

    public void rescan() {
        loadLibrary();
    }

    private void refreshMenu() {
        if (lcd.top() != null) lcd.top().shown();
        redraw();
    }

    // ---- things the screens need ------------------------------------------------------------

    public PlayerService service() { return service; }

    /** True while the iPod is on screen. */
    public boolean isFront() { return front; }

    /** What Now Playing and the buttons control right now. */
    public Playback playback() { return spotifyActive ? spotifyPlayer : service; }

    public Spotify spotify() { return spotify; }

    public SpotifyLibrary spotifyLibrary() { return spotifyLibrary; }

    public SpotifyPlayer spotifyPlayer() { return spotifyPlayer; }

    /** Android's own picker for Bluetooth and other audio outputs of this phone. */
    public void showOutputSwitcher() {
        if (Build.VERSION.SDK_INT >= 34
                && android.media.MediaRouter2.getInstance(this).showSystemOutputSwitcher()) return;
        try {
            startActivity(new Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", getPackageName()));
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    /** More of the Spotify library arrived: rebuild the list on screen to show it. */
    private void spotifyLibraryChanged() {
        if (lcd.top() instanceof ListScreen) lcd.top().shown();
        redraw();
    }

    /** The last sign-in problem, shown in the Spotify menu. */
    public String spotifyError() { return spotifyError; }

    public void connectSpotify() {
        spotifyError = null;
        spotify.login(this);
    }

    public void disconnectSpotify() {
        spotify.logout();
        spotifyLibrary.clear();
        if (spotifyActive) {
            spotifyActive = false;
            spotifyPlayer.stopPolling();
        }
        refreshScreen();
    }

    /** Plays Spotify songs and switches the iPod over to them. */
    public void playSpotify(SpotifyPlayer.Request rq) {
        if (service != null && service.isPlayingOrLoading()) service.pause();
        spotifyActive = true;
        spotifyPlayer.play(rq);
        spotifyPlayer.startPolling();
        lcd.showNowPlaying();
    }

    public MusicLibrary library() { return library; }

    public SharedPreferences prefs() { return prefs; }

    public Clicker clicker() { return clicker; }

    public int batteryLevel() { return battery; }

    public boolean isCharging() { return charging; }

    public void redraw() { if (view != null) view.invalidate(); }

    public boolean clickerOn() { return prefs.getBoolean("clicker", true); }

    public void setClicker(boolean on) { prefs.edit().putBoolean("clicker", on).apply(); }

    /** 0 = off, -1 = always on, otherwise seconds. */
    public int backlightSeconds() { return prefs.getInt("backlight", 10); }

    public void setBacklightSeconds(int s) {
        prefs.edit().putInt("backlight", s).apply();
        view.wake();
    }

    public void toggleBacklight() { view.toggleBacklight(); }

    public static final int FINISH_WHITE = 0, FINISH_BLACK = 1, FINISH_AUTO = 2;

    public int finishMode() { return prefs.getInt("finish", FINISH_WHITE); }

    public void setFinishMode(int m) {
        prefs.edit().putInt("finish", m).apply();
        applyFinish();
    }

    private void applyFinish() {
        int m = finishMode();
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        view.setFinish(m == FINISH_BLACK || (m == FINISH_AUTO && night) ? Finish.BLACK : Finish.WHITE);
    }

    /** A short buzz to confirm e.g. adding a song to On-The-Go. */
    public void buzz() {
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
