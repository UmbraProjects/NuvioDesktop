<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  [![License][license-shield]][license-url]

  <p>
    Nuvio Desktop is a desktop media hub built with Kotlin Multiplatform and Compose Multiplatform.
    <br />
    Desktop app in development
  </p>

</div>

## HTPC Fork Changes

[![Nuvio TV Mode Demo](https://img.youtube.com/vi/N3eKjF7sm_o/maxresdefault.jpg)](https://www.youtube.com/watch?v=N3eKjF7sm_o)

This fork is unaffiliated with the Nuvio team. It is intended to be a fork focused solely on the best possible experience for Windows. 

Here is a non exhaustive feature list:

**Home & Browsing**

1. Adaptive Hero, with an option to tint the background based on the backdrop, plus a manual vertical position slider so you can tune framing to whatever looks best for your library.
2. Full TV Mode UI for full screen mode.
3. Hero info badges for awards, festivals, critic signals, release status, language, trending/cult/true-story metadata, notable studios/directors, short films, mini series, binge-ready shows, and new releases - fully customizable (placement, size, priority order) and extendable with your own badges/config, see below.
4. Trailers on the home screen in either the hero or full screen, with a configurable auto-play delay; they also play in MPV if opened in the media info screen.
5. Search and Library are now full home-mode screens (same TV Mode / Adaptive Hero layout as Home) instead of flat lists.
6. Infinite scrolling on home catalog rows and inside collections instead of stopping at a small "View All" preview.
7. A Trakt Calendar (press `C`) showing your upcoming and recent airings/releases in a month-view poster grid.
8. Hero image source setting to pull backdrops/logos straight from TMDB, or TMDB for movies + TheTVDB for TV/anime, applies only to search and library.
9. A one-time popup on first launch if you haven't set a TMDB or Mdblist API key, explaining what you're missing (lower quality backdrops/logos, fewer badges) with fields to add them right there.
10. Larger posters option.

**Tracking & Scrobbling**

11. SIMKL integration alongside Trakt - dual scrobbling, and the option to drive Library/Continue Watching/Calendar from SIMKL instead. A bundled Fribb anime ID mapping database translates between AniDB, AniList, Kitsu, MyAnimeList, SIMKL, IMDb, TMDB, and TVDB so anime numbering lines up properly across services.

**Input & Controls**

12. Keyboard controls for most of the application.
13. Improved mouse support, hold your mouse at the edge of a catalog to auto scroll, hold and drag to scroll fast, hold shift and use the mouse wheel.
14. Hotkeys for search and library.

**Playback**

15. Tuned MPV config with three color profiles, thanks to Allecsc the developer of [Stremio Kai](https://github.com/allecsc/Stremio-Kai) who gave permission for them to be used here.
16. Anime enhancements ported from Stremio Kai: Anime4K shaders ([bloc97/Anime4K](https://github.com/bloc97/Anime4K), MIT) plus anime-tuned scaling/deband, auto-applied for anime titles and toggleable with F10 (Auto/Off/Optimized/Fast/HQ), plus SVP frame interpolation support when SVP is running.
17. Binge mode to automatically trigger the next episode ASAP without manual input.
18. Volume boost up to 200% for quiet content (Up/Down past 100%).
19. Buffer presets (Low Data / Balanced / Resilient) to tune playback caching for your connection, with a seek-bar indicator showing how far ahead is cached.
20. NVIDIA RTX Video True HDR support on RTX GPUs.
21. Local file drag-and-drop - drop a video file from Explorer straight onto the window to play it immediately.
22. Playback speed defaults.
23. Probably other stuff I've forgotten about, generally just a huge list of improvements to PC.

The design is partly inspired by Nuvio TV and Stremio Kai. 

## Fork Hotkeys

Assume arrow keys for navigation, enter to confirm, backspace/escape to go backwards (escape is an official hotkey.)

Homepage:

- S to open the search panel (back to home if already in search)
- L to open the library panel (back to home if already in library)
- T to play the trailer (adaptive hero/TV mode only)
- C to open your Trakt or SIMKL calendar

Player:

- F7 toggles SVP
- F8 toggles HDR mode
- F9 toggles color profile
- F10 cycles anime shaders
- Tab to skip the intro/outro (only while the skip prompt is showing)
- A to toggle audio track
- S to toggle subtitles
- O for sources
- E for episodes
- Up/down to change volume (boosts up to 200% for quiet content)
- [] to change playback speed
- C to cycle aspect ratio

## Custom Hero Discovery Config & Badges

Nuvio HTPC can show hero info badges for awards, festivals, critic signals, release status, language, notable studios/directors, trending/cult/true-story metadata, short films, mini series, binge-ready shows, and new releases.

You can extend the studio/director list and override the bundled badge images without touching the code. Create this folder first:

```text
%LOCALAPPDATA%\Nuvio\Badges
```

For most Windows users this expands to:

```text
C:\Users\<you>\AppData\Local\Nuvio\Badges
```

### Custom `hero_discovery.json`

Place a file named `hero_discovery.json` in `%LOCALAPPDATA%\Nuvio\Badges`.

Example:

```json
{
  "version": 1,
  "mergeWithDefaults": true,
  "studios": {
    "Janus Films": "Janus Films",
    "Toho": "Toho"
  },
  "directors": {
    "Akira Kurosawa": "A. Kurosawa",
    "Kelly Reichardt": "Kelly Reichardt"
  }
}
```

- `mergeWithDefaults: true` keeps the built-in studios/directors and adds yours.
- `mergeWithDefaults: false` replaces the built-in studio/director lists with only your entries.
- The left side must match the studio or director name from the title metadata.
- The right side is the short label shown in the hero badge/tooltip.
- Restart Nuvio after changing this file.

### Custom Badge Images

Put image files directly in `%LOCALAPPDATA%\Nuvio\Badges`. Supported formats:

```text
.png
.jpg
.jpeg
.webp
```

Custom badge filenames are matched case-insensitively and ignore spaces/punctuation. For example, all of these can match the Metacritic badge:

```text
Metacritic.png
Must See.webp
must-see.jpg
```

Useful badge names/categories include:

```text
Best Picture.png
Best Picture Nominee.png
Golden Globe.png
Golden Globe Nominee.png
Emmy Winner.png
Emmy Nominee.png
Palme dOr.png
Golden Lion.png
Golden Bear.png
Peoples Choice.png
Metacritic.png
Must See.png
Cult Classic.png
Trending.png
Short Film.png
Mini Series.png
Binge Ready.png
True Story.png
New Release.png
Director.png
Studio.png
```

For director badges, you can also use the director name itself, such as:

```text
David Fincher.png
Christopher Nolan.webp
```

The custom image wins over the bundled default whenever its filename matches the badge label or category. Restart Nuvio after adding or replacing custom badge files.

This concludes the forks readme, anything beyond this point is from the official upstream Nuvio Desktop.

## About

Nuvio Desktop brings the Nuvio media experience to desktop. It keeps the playback-focused browsing, collection, watch progress, downloads, and Stremio addon ecosystem integration from Nuvio while adapting the app for desktop input, desktop storage, and native desktop playback.

The desktop app is built from the shared Kotlin Multiplatform codebase in [composeApp](./composeApp), with desktop-specific code in [composeApp/src/desktopMain](./composeApp/src/desktopMain). Desktop packaging is configured through Gradle, with development builds active for desktop hosts and broader platform coverage continuing over time.

## Platform Status

Current desktop builds are actively being developed. Linux support is planned for a later phase, and public release targets are not finalized yet.

## Installation

Public desktop releases are not available yet. Current builds are development builds.

When releases are ready, desktop builds will be published from the Nuvio Desktop repository.

## Development

Desktop development checkout:

```bash
git clone --branch Dev --recurse-submodules https://github.com/NuvioMedia/NuvioDesktop.git
cd NuvioDesktop
git submodule update --init --recursive MPVKit
git -C MPVKit fetch origin Nuvio
git -C MPVKit switch Nuvio
git -C MPVKit pull --ff-only
./gradlew :composeApp:run
```

Useful commands:

```bash
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:packageDistributionForCurrentOS
```

On macOS, the native player bridge uses MPVKit libmpv artifacts from the `MPVKit` submodule. For development, the submodule is configured to use [NuvioMedia/MPVKit](https://github.com/NuvioMedia/MPVKit) on the `Nuvio` branch. If you already have a checkout, sync and update it with:

```bash
git submodule sync MPVKit
git submodule update --init --recursive MPVKit
git -C MPVKit remote set-url origin https://github.com/NuvioMedia/MPVKit.git
git -C MPVKit fetch origin Nuvio
git -C MPVKit switch Nuvio
git -C MPVKit pull --ff-only
```

If Gradle reports missing MPVKit artifacts, build the macOS runtime before running the app:

```bash
cd MPVKit
make build platform=macos
```

You can also point Gradle at a separate MPVKit checkout:

```bash
./gradlew :composeApp:run -Pnuvio.mpvkit.dir=/absolute/path/to/MPVKit
```

## Project Structure

- `composeApp/` contains the Kotlin Multiplatform and Compose Multiplatform app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific app code, storage, settings, player integration, and desktop resources.
- `composeApp/src/desktopMain/native/macos/` and `composeApp/src/desktopMain/native/windows/` contain the native player bridges.
- `composeApp/src/desktopMain/resources/player-ui/` contains the desktop native player control UI.
- `composeApp/src/desktopMain/resources/icons/` contains desktop app icons for macOS, Windows, and Linux packaging.

## Desktop Player

Nuvio Desktop uses a native desktop player path with MPVKit/libmpv integration and desktop-owned controls. The desktop player is separate from the mobile Compose player surface so desktop behavior can match desktop input, keyboard, windowing, and playback expectations.

## Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including the full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit the [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Built With

- Kotlin Multiplatform
- Compose Multiplatform for Desktop
- Kotlin
- MPVKit and libmpv

<!-- MARKDOWN LINKS & IMAGES -->
[license-shield]: https://img.shields.io/github/license/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[license-url]: https://github.com/NuvioMedia/NuvioDesktop/blob/Dev/LICENSE
