# Nuvio Remote

An Android app that drives Nuvio HTPC from a phone over the home Wi-Fi. It works as a remote, shows what is playing,
browses the library and music, and can wake the PC. It is part of the `Spyder` branch (Nick's HTPC fork) and is not in
upstream Nuvio.

- **Download:** the APK in the [NuvioRemote release](https://github.com/spydermahn1234/NuvioDesktop/releases/tag/NuvioRemote).
  It is rebuilt by `.github/workflows/android-remote.yml` on every push to `Spyder` that touches `remote-android/` or that workflow.
- **Needs:** Android 8 or newer, and a Nuvio build from the same branch. Most features need the PC and the phone on
  the same build: an older PC ignores what it does not know, and an older phone never asks for it.

## Getting connected

1. In Nuvio, turn on **Settings › Keyboard shortcuts › Phone remote**. It shows a six-digit pairing code and a QR code.
2. In the app, either tap **Scan the TV's code** and point the camera at the QR code (no typing, no permission
   asked), or pick the PC from the list (the app finds it on the network) and type the code.
3. The phone is remembered. Next time it connects by itself.

No PC to hand? **Try the demo TV** on the PC list connects to a pretend Nuvio that runs inside the app (code
`123456`). It speaks the real protocol, plays pretend films and albums, has an invented library, timed lyrics and a
queue, and can "sleep" to try Wake. A strip shows what it was sent. This is for testing away from the PC.

## What it does

**Remote tab**
- Touchpad (swipe to move a tile, tap for OK) or D-pad, with Back, Home, Search (opens the phone keyboard) and More.
  The touchpad or D-pad choice is remembered.
- Works everywhere in Nuvio, including Settings (arrow keys there show a focus ring in the theme's colour).
- **During a film:** a Now playing card, Subtitles and Audio tiles (tap for the next track), back 10s / play-pause /
  ahead 10s, Back, Sources, More. **Sources** shows the TV's source list on the phone, in the same order, and tapping
  one plays it.
- **During music:** the cover, title and artist, a seek bar, previous / play-pause / next, and Back, Queue, Lyrics,
  Dark, More.
  - **Queue** lists what the TV will play. Tap a song to play it; Play next and Remove work on songs still to come.
  - **Lyrics** shows the stage's *Loud* style: each line big in its own font and colour, with the words landing as
    they are sung. It uses the source's own word times when it has them. A line is never cut; it is split into rows
    and fitted to the box. The **List** tag switches to a scrolling list.
- **Dark** turns the phone black and dims it (Low / Medium / High) so it does not light up the room. Tap for play or
  pause, swipe left and right to seek, swipe up and down for volume.
- Lock-screen and notification controls while something plays.
- The app takes the TV's theme colours and follows them when they change.

**Watch tab**
- **Continue watching** from the PC. **Resume** plays it on the TV straight away, from the same source and the same
  place.
- The library as posters, with the same sections as Nuvio's Library tab. Tapping a film opens it on the TV. Tapping a
  show lists its seasons and episodes on the phone, with progress; tapping an episode plays it.
- **Hold** a poster, a Continue watching card, an episode or a season tag for the TV's long-press menu as a glass
  sheet: mark watched or unwatched (an episode, the earlier episodes, a season, earlier seasons, or a whole film or show),
  add to or remove from the library (or all libraries), start from the beginning, choose a source on the TV, start a
  Simkl rewatch, add to a playlist (a picker on the phone, with a season choice for a whole show, or a new playlist),
  an AI recap of the story so far, Not interested, Remove from Continue watching, and Details on the TV. The TV words
  the rows, so they match what it would offer.
- **Search** covers your library first. Once typing pauses, it also covers everything Nuvio's Search tab finds in every
  addon. The mic searches by voice.

**Music tab**
- Albums and artists from the PC. An album lists its songs under Play, Shuffle and Add to queue; tap a song to play the
  album from it. An artist has Shuffle all and Radio.

**Other**
- **Tonight:** while the TV shows the Tonight picker (Movies & TV's "How shall we choose tonight?"), the Remote tab
  mirrors it: start Three Questions or Three Cards, answer each question with a big tap (Left, Right, Either is fine,
  Back), and tap a card to play it on the TV (Details opens its page; Reshuffle once a night). Since Nov 11 every mode is
  on the phone too: the launcher lists all eight; Coin Toss, Knockout and Channel Flick have big buttons for the TV's
  keys, Ends By and The Pile are lists (tap to play, Let go asks first), and The Map is a touchpad that moves the TV's
  lens. The header tag still swaps to the pad. Needs a Nuvio from Nov 11, 2026 or later for the modes.
- **Send to Nuvio:** share a YouTube or YouTube Music link from any app and it plays on the TV.
- **Wake:** when the PC is asleep or off, the app offers to wake it with Wake-on-LAN. The PC sends its network card and
  broadcast addresses while connected. Wake-on-LAN must be on in the PC's BIOS and network adapter settings.

## How it talks to Nuvio

Plain TCP on port 47321 (or the next free one above), one UTF-8 line per message. Every free-text field is
URL-encoded. The phone finds PCs with a UDP broadcast on 47320. The handshake and now-playing lines are described at the top of
`composeApp/src/desktopMain/kotlin/com/nuvio/app/features/remote/PhoneRemoteProtocol.kt`; the library, music and
source lines are built in `PhoneRemoteLibraryLines.kt` and answered in `PhoneRemoteServer.kt`. The app's side is
`RemoteClient.kt` and `LibraryLines.kt`. **Change both sides together.**

| Area | Phone sends | PC sends |
| --- | --- | --- |
| Pairing | `HELLO`, `PAIR` | `WELCOME`, `PIN_REQUIRED`, `PAIRED`, `BAD_PIN`, `LOCKED` |
| Keys | `KEY`, `TEXT`, `SEEK`, `VOLUME`, `PING` | `PONG` |
| Now playing | `SUBSCRIBE` | `STATE` / `IDLE`, `THEME`, `WAKE`, `SOURCES`, `LYRICS` |
| Library | `LIBRARY`, `OPEN_TITLE`, `CONTINUE`, `RESUME`, `EPISODES`, `PLAY_EPISODE`, `SEARCH` | `LIBRARY`, `CONTINUE`, `EPISODES`, `SEARCH_RESULTS` |
| Music | `PLAY_ALBUM`, `ALBUM_TRACKS`, `PLAY_TRACK`, `ARTIST`, `QUEUE`, `QUEUE_PLAY`, `QUEUE_NEXT`, `QUEUE_REMOVE` | `ALBUM_TRACKS`, `QUEUE` |
| Sources | `PICK_SOURCE` | `SOURCES` |
| Tonight | `TONIGHT_DO` | `TONIGHT` (after `SUBSCRIBE`, whenever it changes) |
| Long-press menus | `MENU`, `DO` | `MENU`, `DONE`, `PLAYLISTS`, `RECAP` |
| Links | `OPEN` | `OPENING`, `CANT_OPEN` |

The QR code holds `nuvioremote://pair?n=<name>&p=<port>&c=<code>&a=<addresses>`. The app tries each address until one
answers.

## Building

```bash
cd remote-android
./gradlew :app:assembleRelease
```

With the repository secrets `NUVIO_REMOTE_KEYSTORE_B64` and `NUVIO_REMOTE_KEYSTORE_PASSWORD` set, every build is
signed with the same key, so a new APK installs over the old one. Without them each build gets a throwaway key, and the
old app has to be uninstalled first. The unit tests (`DemoTvTest`, `PairingLinkTest`, `WakeOnLanTest`) run with
`./gradlew testReleaseUnitTest`, as CI does before every build.

What has been tried on the TV, and what is still open, is in [`TESTING.md`](../TESTING.md) under *Phone remote*.
