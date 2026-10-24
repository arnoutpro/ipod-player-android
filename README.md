# ipodgen3

A music player for Android that looks and works like the 3rd generation iPod (2003):
the LCD with its pixel font, the four red-lit touch buttons and the touch wheel.

## Install

Every push builds a signed APK with GitHub Actions. Open the latest
[release](../../releases) on your phone, download `iPod3G-*.apk` and open it
(allow "Install unknown apps" for your browser). Not on the Play Store.

## Controls

| Control | What it does |
| --- | --- |
| Wheel | Scroll menus; on Now Playing it sets the volume |
| Centre button | Select; on Now Playing it toggles the scrubber (wheel seeks) |
| Hold centre on a song | Add it to the On-The-Go playlist |
| MENU | Back (the phone's back button does the same) |
| ⏮ / ⏭ | Previous / next song; hold to rewind / fast-forward |
| ▶❙❙ | Play / pause; hold for 2 s to sleep |

Menus: Playlists (On-The-Go, Recently Added, Top 25 Most Played), Browse
(Artists, Albums, Genres, Songs, Composers), Extras (Clock, Brick), Settings
(About, Shuffle, Repeat, Backlight Timer, Clicker, Rescan Music), Shuffle Songs,
Backlight and Now Playing.

Music is read from the phone's media library (anything in `Music/` etc.).
Playback continues in the background with lock-screen and headset controls.

## Build locally

    ./gradlew assembleRelease

The APK is signed with `app/ipod3g.jks`, a key that lives in this repo so every
build installs over the previous one. It is only meant for personal sideloading.
