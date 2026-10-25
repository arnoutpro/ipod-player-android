# iPod 3G Player

*Serene listening, the way it used to feel.*

A music player for Android that looks and works like the **3rd generation iPod** from 2003.
It has the grey-blue LCD with its pixel font, the four red-lit touch buttons and the touch wheel,
under a clear acrylic layer. It plays the music on your phone and, if you like, your Spotify
library, on the phone or on your Sonos speakers, Chromecasts and Spotify Connect devices.

<p align="center">
  <img src="docs/screenshots/main-menu.png" width="220" alt="Main menu, white">
  &nbsp;
  <img src="docs/screenshots/now-playing.png" width="220" alt="Now Playing">
  &nbsp;
  <img src="docs/screenshots/black-finish.png" width="220" alt="Black finish">
</p>

Not affiliated with Apple or Spotify. A personal hobby project, sideloaded rather than published
on the Play Store.

---

## Why

Modern music apps want your attention. They show feeds, suggestions, notifications, videos and
banners, and put a new screen in front of you every time you go looking for a song. Somewhere
along the way, listening to music became one more thing to scroll through.

The iPod got this right. It did one thing: you picked an album, turned the wheel, pressed play,
and that was it. There was nothing else on the screen to pull you away.

This app brings that back. It's made for **serene listening sessions with no distractions from
the app**: no feed, no recommendations, no ads, nothing competing for your attention. What you
get is the **look and feel of the 3rd generation iPod**, down to the click of the wheel and the
glow of the buttons, for that vintage experience.

It isn't a museum piece, though. Under the old face it has the things you'd miss today: your
**Spotify** library browsed like an iPod, music sent to your **Sonos** speakers or a **Chromecast**,
Spotify Connect, a 7-band equalizer, and a dark finish for the evening.

### Who it's for

- **Anyone who wants to just listen:** put on an album and let it play, without the app asking
  for anything.
- **iPod nostalgics:** people who remember the wheel, the pixel font and the satisfying click,
  and miss how simple it felt.
- **Vintage tech lovers:** if you like the feel of old hardware, this is a 2003 iPod you carry on
  today's phone.
- **Spotify and Sonos households:** keep your streaming library and your speakers, but control
  them from something calmer than the usual apps.

---

## Contents

- [Why](#why)
- [Install](#install)
- [The face](#the-face)
- [Controls](#controls)
- [Menus](#menus)
- [Now Playing](#now-playing)
- [Equalizer](#equalizer)
- [Spotify](#spotify)
- [Speakers](#speakers)
- [Settings](#settings)
- [Extras](#extras)
- [Background playback](#background-playback)
- [Building it yourself](#building-it-yourself)
- [How it's built](#how-its-built)

---

## Install

Every push to `main` builds a signed APK with GitHub Actions and publishes it as a release.

1. On your phone, open the latest [release](../../releases).
2. Download `iPod3G-v1.0.N.apk` and open it.
3. Allow "Install unknown apps" for your browser when Android asks.
4. Open **iPod** and allow access to your music.

Every build is signed with the same key, so a new release installs over the old one and keeps
your settings, playlists and Spotify sign-in.

**Requirements:**
- Android 8.0 or later; the equalizer needs Android 9 or later.
- Spotify needs the Spotify app and Spotify Premium.
- Playing on Sonos or Chromecast needs the phone and speaker on the same Wi-Fi; Chromecast also
  needs Google Play services.

The APK is about 4 MB, mostly Google's Cast libraries.

---

## The face

The whole screen is the front of the iPod, drawn in code and scaled to fit any phone.

- **The LCD:** 320 × 292 pixels with a title bar and seven menu rows, a crisp pixel font and the
  original grey-blue backlight. The backlight goes darker greenish-grey when it turns off.
- **Acrylic top layer:** like the real thing, the face sits under a clear layer. The buttons, the
  wheel and the centre button are sunk into holes in it. Each hole's wall casts a soft shadow, and
  its polished edge catches the light. A faint gloss runs across the face.
- **Light that follows the phone:** the light comes from the ceiling of the room, read from the
  gravity sensor. Everything lit is brightest on the side facing it: the shadows and edge
  highlights in the holes, the shading of the body, the gloss on the acrylic and the reflection on
  the screen glass. Tilt the phone and they all swing round, with the gloss sliding the other way
  like a reflection. Lay it flat and the light comes from straight above, so the face looks almost
  even.
- **Red-lit buttons:** the four touch buttons glow red while the backlight is on and turn grey
  when it's off.
- **Two finishes:** the original white, or a black version that never existed. See
  [Color](#settings).

---

## Controls

| Control | What it does |
| --- | --- |
| **Wheel** | Scroll through menus. Spins faster through long lists. On Now Playing it sets the volume. |
| **Centre button** | Select. On Now Playing it switches the wheel to scrubbing through the song. |
| **Hold centre on Now Playing** | Open Speakers, to pick where the music plays. |
| **Hold centre on a song** | Add it to the On-The-Go playlist (you'll feel a short buzz). |
| **MENU** | Back one level. The phone's back gesture does the same. |
| **⏮ / ⏭** | Previous / next song. Hold to rewind / fast-forward. |
| **▶❙❙** | Play / pause. Hold for 2 seconds to sleep: the music pauses and the backlight turns off. |

Every wheel step and button press makes the iPod's little click, and a light vibration if your phone
has one. The click can be turned off under Settings › Clicker.

---

## Menus

The menus follow the original iPod, plus a Spotify menu.

| Menu | What's in it |
| --- | --- |
| **Playlists** | On-The-Go (your own quick playlist), Recently Added, Top 25 Most Played |
| **Browse** | Artists, Albums, Genres, Songs, Composers |
| **Spotify** | Playlists, Artists, Albums, Songs, Recently Liked, Disconnect. See [Spotify](#spotify). |
| **Extras** | Clock, Games › Brick |
| **Settings** | About, Shuffle, Repeat, EQ, Speakers, Backlight Timer, Clicker, Color, Rescan Music |
| **Shuffle Songs** | Plays your whole library in random order |
| **Backlight** | Turns the backlight on or off |
| **Now Playing** | Appears once something is playing |

Lists are sorted like an iPod: case-insensitive, and ignoring a leading "The". Long names scroll
sideways when highlighted. Your music is read from the phone's media library, so the songs in
`Music/` and other music folders show up. After adding songs, use Settings › Rescan Music.

**On-The-Go:** hold the centre button on any song to add it. The playlist is saved, and has a
"Clear Playlist" item at the bottom.

---

## Now Playing

<img src="docs/screenshots/now-playing.png" width="220" align="right" alt="Now Playing">

- The position in the list ("3 of 12"), with shuffle and repeat icons on the right.
- Song title, artist and album. Long ones scroll.
- The dithered progress bar of the 3rd generation, with elapsed and remaining time.
- **Wheel:** volume, with a volume bar that disappears after a moment.
- **Centre button:** scrubbing. A diamond appears on the bar and the wheel moves through the song.
  Press the centre button again, or wait a few seconds, to go back.
- The title bar shows ▶ or ❙❙ on every screen while something is loaded.

<br clear="right">

---

## Equalizer

<img src="docs/screenshots/equalizer.png" width="220" align="right" alt="Equalizer bands">

Settings › **EQ** has a 7-band equalizer:

- **Bands:** seven sliders at 60 Hz, 150 Hz, 400 Hz, 1 kHz, 2.4 kHz, 6 kHz and 15 kHz, each from
  −12 to +12 dB.
  - **Wheel:** raises or lowers the highlighted band in 1 dB steps.
  - **Centre button:** moves to the next band.
  - **Hold centre:** puts the band back to 0 dB.
- **Presets:** Off, Bass Booster, Treble Booster, Rock, Pop, Vocal and Spoken Word. Pick one to set
  all seven bands, then fine-tune under Bands. The ✓ shows the active preset. Once you change a band
  by hand, Settings shows "Custom".

It uses Android's DynamicsProcessing effect with a limiter, so big boosts don't distort. It stays
on from song to song. When every band is at 0 dB, the effect switches itself off completely. It
applies to the phone's own music; Spotify's sound comes from the Spotify app and isn't affected.

<br clear="right">

---

## Spotify

The **Spotify** menu lets you browse your Spotify library in iPod menus and play it through the
Spotify app on the same phone. The iPod's Now Playing and buttons control it.

**Connecting**

1. Open **Spotify › Connect to Spotify**. Spotify's login page opens in your browser.
2. Sign in and allow access. The browser sends you back to the iPod.
3. **Spotify › Disconnect** (it shows your Spotify name) signs you out and removes the tokens from
   the phone.

**Browsing**

Your Liked Songs, saved albums and followed artists are browsed like the iPod's own music:

- **Playlists:** your own and the ones you follow.
- **Artists:** everyone in your Liked Songs and saved albums, plus the artists you follow, A–Z.
  Each has **All Songs**, their albums in your library, and **More on Spotify** with all their
  albums and singles.
- **Albums:** the albums of your liked songs plus your saved albums, A–Z. A saved album shows the
  whole album; any other shows the songs you liked from it.
- **Songs:** all your liked songs, A–Z.
- **Recently Liked:** your liked songs in Spotify's order, newest first.

The library is loaded once in the background, about 20 songs per request, and kept on the phone,
so browsing is instant. It's refreshed when you open the Spotify menu, at most every 10 minutes.
The first load shows songs as they come in.

**Playing**

- Picking a song in a playlist or album plays it there, so the following songs play after it.
  In All Songs and Songs, the next 30 songs from the list are queued after it.
- The music comes out of **this phone**, never a computer or speaker that happens to be active on
  your account, unless you pick one under [Speakers](#speakers).
- If the Spotify app is asleep, the iPod wakes it in the background with Spotify's App Remote SDK,
  without showing it. Only if that fails does it open Spotify for a second and come back by itself.
- Now Playing shows "Spotify" in the corner ("Spotify on *speaker*" when it plays elsewhere), plus
  the song, progress, shuffle and repeat. Play/pause, previous, next, volume and scrubbing all work.
- Playing your own music again switches back automatically; Spotify pauses when it loses the audio.
- When you open the iPod while Spotify has a song playing or paused, here or on a speaker, Now
  Playing picks it up.

**Limits from Spotify's rules (2026)**

The app runs in Spotify's *development mode*, which comes with rules this app can't change:

- **Spotify Premium** is required, both for the app's owner and for playing songs.
- **At most 5 Spotify accounts** can use one registered app, and each has to be added by hand in
  the Spotify developer dashboard.
- **Playlists you follow but don't own:** Spotify hides their songs from apps like this one. They
  show **Play Playlist**, which plays the whole playlist, and "Songs hidden by Spotify".
- **Local files** in your Spotify playlists can't be streamed. A playlist with only local files says
  "Only local files".
- Spotify's logos aren't drawn on the pixel LCD. The word "Spotify" on Now Playing serves as the
  credit.

**Using your own Spotify app registration**

The app ships with the author's Client ID. To use your own (for example to add your own 5 users):

1. Create an app at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
   with the redirect URI `ipod3g://spotify-callback`, and tick **Web API** and **Android**.
2. Put its Client ID in `CLIENT_ID` in
   [`Spotify.java`](app/src/main/java/com/arnoutpro/ipod3g/Spotify.java) and build.

Sign-in uses the authorization code flow with PKCE, so the app never needs the client secret.

---

## Speakers

**Settings › Speakers** picks where the music plays. On Now Playing, hold the centre button to get
there straight away.

- **This Phone:** the default. Picking it while casting brings the music back to the phone, paused
  where the speaker left off.
- **Sonos and Cast devices:** Sonos speakers (marked "Sonos", listed per group by room name, e.g.
  "Kitchen +1") and Chromecasts, Google TVs and Nest speakers (marked "Cast") on your Wi-Fi. Pick
  one and the phone's own music plays there, from the same spot, playing or paused as it was. The
  rest of the queue follows, with shuffle, repeat, Next, Previous and scrubbing. Now Playing reads
  "3 of 12 on *speaker*" and the wheel sets its volume (for a Sonos group, the whole group's, in
  proportion). The speaker fetches each song from a small server in the app over your Wi-Fi; every
  song gets a random secret address, so nothing else on the network can browse your music. The
  equalizer doesn't apply while playing on a speaker. These speakers don't take Spotify: while
  Spotify is playing they show "No Spotify", and picking one says so instead of casting.
- **Spotify Connect devices:** every speaker, TV, computer or web player on your Spotify account
  shows up by name. Pick one and Spotify's music moves there straight away, playing or paused as it
  was, and new Spotify songs keep playing there. Now Playing then reads "Spotify on *speaker*", and
  the wheel sets that speaker's own volume in steps of 5%. If the speaker is switched off, songs
  play on the phone. This is for Spotify only.
- **Other Outputs:** opens Android's own output switcher for Bluetooth speakers and headphones and
  other outputs your phone supports. This works for everything, including the phone's own music.

**Volume:** while the music plays on a Sonos, Cast or Spotify Connect speaker, both the wheel on Now
Playing and the phone's volume buttons set that speaker's volume, in steps of 5%. The volume buttons
do this while the iPod is on screen; with the app in the background they control the phone as usual.

The phone and the speaker need to be on the same Wi-Fi. Sonos speakers are found and controlled
directly with Sonos's own network protocol, so nothing needs installing or linking. Cast devices
use Google's Default Media Receiver, which needs Google Play services on the phone. Spotify can't
send music to Sonos speakers from here: Spotify doesn't list them to other apps.

---

## Settings

<img src="docs/screenshots/settings.png" width="220" align="right" alt="Settings">

| Setting | What it does |
| --- | --- |
| **About** | Number of songs, artists and albums, and the version |
| **Shuffle** | Off / Songs |
| **Repeat** | Off / One / All |
| **EQ** | The equalizer, see [above](#equalizer) |
| **Speakers** | Where the music plays, see [Speakers](#speakers) |
| **Backlight Timer** | Off, 2, 5, 10, 15, 20 or 30 seconds, or Always On |
| **Clicker** | The click sound and vibration on/off |
| **Color** | White, Black or Auto (follows the phone's dark mode) |
| **Rescan Music** | Reads the phone's music library again |

**Backlight:** with a timer set, any touch lights the screen and buttons, and they go dark after
the chosen time. Main menu › Backlight turns it off by hand. It then stays off until you turn it on
there again.

**Color:** the black finish is an interpretation of a black 3rd generation iPod, which Apple never
made. It's gloss black plastic under the acrylic, with a graphite wheel and buttons and the same red
glow. Auto switches the moment the phone's dark mode changes, without losing your place in the menus.

<br clear="right">

---

## Extras

<p>
  <img src="docs/screenshots/clock.png" width="220" alt="Clock">
  &nbsp;
  <img src="docs/screenshots/brick.png" width="220" alt="Brick">
</p>

- **Clock:** the time and date in the big LCD font, in the 12- or 24-hour format of your phone.
- **Games › Brick:** the iPod's Breakout. Turn the wheel to move the paddle and press the centre
  button to serve. Score points for every brick, with 3 lives and new levels when the wall is
  cleared.

---

## Background playback

- Music keeps playing when you leave the app. A media notification and the lock screen show the
  song, with play/pause, previous and next.
- Headset buttons and Bluetooth controls work.
- Unplugging headphones pauses the music.
- Phone calls and other apps pause it and resume it, through Android's audio focus.
- The battery icon in the title bar shows the phone's real battery level.

---

## Building it yourself

You need JDK 17 and the Android SDK (Android Studio installs both).

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. If Gradle can't find the SDK, set
`ANDROID_HOME`, for example `ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleRelease`.

**Signing:** the APK is signed with `app/ipod3g.jks`, a key kept in this repo on purpose, so every
build, local or from GitHub, installs over the previous one. It's fine for a personal sideloaded
app; never use it for the Play Store.

**Version numbers:** the version code is GitHub's run number (`1.0.N`). A local build is version 1
unless you set `GITHUB_RUN_NUMBER`. Android refuses to install a lower version over a higher one, so
to test a local build over a release, build with the latest release's number or higher:

```bash
GITHUB_RUN_NUMBER=18 ./gradlew assembleRelease
```

**Libraries:**
- **Spotify's App Remote SDK** (Apache-2.0) lives in `app/libs/`, taken from
  [Spotify's GitHub releases](https://github.com/spotify/android-sdk/releases); it needs Gson.
- **Google's Cast SDK** comes from Google's Maven repository. It's pinned to 22.3.1, because 23.x
  needs Android Gradle plugin 9.1. It also needs AndroidX, which is switched on in
  `gradle.properties`.
- The build prints warnings about Kotlin metadata from the Cast SDK; they're harmless.

---

## How it's built

Plain Java and the Android framework, plus Spotify's App Remote SDK and Google's Cast SDK. No
layout files and no image assets: everything on screen is drawn on a `Canvas`.

| File | What it does |
| --- | --- |
| [`MainActivity`](app/src/main/java/com/arnoutpro/ipod3g/MainActivity.java) | Full-screen activity: permissions, settings, the finish, Spotify sign-in and which player is active |
| [`IPodView`](app/src/main/java/com/arnoutpro/ipod3g/IPodView.java) | Draws the face, acrylic and lighting; turns touches into wheel and button input; backlight and tilt sensor |
| [`Finish`](app/src/main/java/com/arnoutpro/ipod3g/Finish.java) | The white and black colour palettes |
| [`Lcd`](app/src/main/java/com/arnoutpro/ipod3g/Lcd.java) | The 320 × 292 LCD bitmap: title bar, screen stack and slide animation, fonts, marquee text |
| [`Screen`](app/src/main/java/com/arnoutpro/ipod3g/Screen.java), [`ListScreen`](app/src/main/java/com/arnoutpro/ipod3g/ListScreen.java) | A page on the LCD; the classic menu list with highlight, arrows and scroll bar |
| [`Menus`](app/src/main/java/com/arnoutpro/ipod3g/Menus.java) | The whole menu tree, including the Spotify menus |
| [`NowPlayingScreen`](app/src/main/java/com/arnoutpro/ipod3g/NowPlayingScreen.java), [`EqScreen`](app/src/main/java/com/arnoutpro/ipod3g/EqScreen.java), [`ClockScreen`](app/src/main/java/com/arnoutpro/ipod3g/ClockScreen.java), [`BrickScreen`](app/src/main/java/com/arnoutpro/ipod3g/BrickScreen.java) | The other screens |
| [`Playback`](app/src/main/java/com/arnoutpro/ipod3g/Playback.java) | What Now Playing and the buttons control: local music or Spotify |
| [`PlayerService`](app/src/main/java/com/arnoutpro/ipod3g/PlayerService.java) | Plays local music in the background, with the queue, shuffle, repeat, notification and media session |
| [`Eq`](app/src/main/java/com/arnoutpro/ipod3g/Eq.java) | The 7-band equalizer and presets |
| [`Spotify`](app/src/main/java/com/arnoutpro/ipod3g/Spotify.java), [`SpotifyPlayer`](app/src/main/java/com/arnoutpro/ipod3g/SpotifyPlayer.java) | Spotify sign-in (PKCE) and Web API calls; playing and controlling Spotify on this phone |
| [`MusicLibrary`](app/src/main/java/com/arnoutpro/ipod3g/MusicLibrary.java), [`Track`](app/src/main/java/com/arnoutpro/ipod3g/Track.java) | Reads the phone's music from MediaStore and answers the Browse questions |
| [`SpotifyLibrary`](app/src/main/java/com/arnoutpro/ipod3g/SpotifyLibrary.java) | Your Spotify library grouped by artist and album, kept in a file |
| [`MusicServer`](app/src/main/java/com/arnoutpro/ipod3g/MusicServer.java) | Serves the current song to a Sonos or Chromecast over Wi-Fi |
| [`RemoteSpeaker`](app/src/main/java/com/arnoutpro/ipod3g/RemoteSpeaker.java), [`CastSpeaker`](app/src/main/java/com/arnoutpro/ipod3g/CastSpeaker.java), [`CastOptionsProvider`](app/src/main/java/com/arnoutpro/ipod3g/CastOptionsProvider.java) | A network speaker for the player; the Chromecast version and the Cast SDK's setup |
| [`Sonos`](app/src/main/java/com/arnoutpro/ipod3g/Sonos.java), [`SonosSpeaker`](app/src/main/java/com/arnoutpro/ipod3g/SonosSpeaker.java) | Finds Sonos groups on the Wi-Fi (SSDP) and controls them over UPnP |
| [`Clicker`](app/src/main/java/com/arnoutpro/ipod3g/Clicker.java) | The click-wheel tick, synthesised so no sound files are needed |
