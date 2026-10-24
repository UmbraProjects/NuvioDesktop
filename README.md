<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  [![License][license-shield]][license-url]

  <p>
    Nuvio HTPC (Home Theatre PC) is designed to be a premium Windows media client for either desk or couch.
    <br>To see it in action, click the image for a showcase video.<br/>
  </p> 
</div>

[![Nuvio TV Mode Demo](https://img.youtube.com/vi/txiM8_y2aD8/maxresdefault.jpg)](https://youtu.be/txiM8_y2aD8)

This fork is unaffiliated with the Nuvio team. Don't worry about the commit disparity, Nuvio's team makes many small commits while I bundle everything into a single release commit for simplicity as this is mostly a solo focused project.

# Home Theatre features

* Mouse, keyboard or controller friendly TV Mode with full-screen navigation, adaptive hero, row-jump navigation, cast and award information. This applies across all panels, not just home.
* Game Mode for browsing and launching your game library alongside your media.
* Automatic control of smart lights based on playback state for a theater like experience.
* Automatic dim and shutdown for OLED displays.
* Audio pass-through for a receiver.

# Discovery & Browsing

* Adaptive hero screens with trailers, configurable artwork, metadata badges, and multiple background modes.
* Discover built around your watch history, favorites, unfinished titles, hidden gems, trending content, and fully custom rows.
* Custom discovery queries can filter by genre, rating, votes, release status, language, certification, runtime, year, studio, cast, and crew.
* Optional AI-powered discovery using OpenAI, Anthropic, OpenRouter, etc.
* Built in calendar powered by either SIMKL or MDBList.
* Random play content or episode based on your criteria.

# Library & Downloads

* Local Library for movies and TV from PC folders, with automatic TMDB matching and manual fix-matching.
* Auto-Downloads for monitored movies and series with configurable delays, schedules, limits, concurrency, and bandwidth controls.
* Debrid library integration with TorBox and Premiumize, including season-pack inspection, episode selection, cached-source inspection, and cloud libraries.

# Desktop Playback

* MPV-based desktop player with extensive customization options built in, as well as the option to fully overwrite the MPV config.
* Unique playback UI with right click menu for quickly swapping options and a clock with a "media ends at" timer.
* Automatic stream failover, source affinity, binge mode, next-episode autoplay, and configurable buffering.
* Three color profiles thanks to Stremio Kai (used with Permission), custom color profile and HDR modes.
* Anime shaders (4K Anime, Stremio Kai, custom), SVP frame interpolation and NVIDIA RTX Video True HDR.
* Seek-bar thumbnails, chapters, SkipDB support, intro/outro skipping, dual subtitles, subtitle styling, playback speed controls, and volume boost.
* Desktop Picture-in-Picture, direct URL playback, local-file playback, and external-player integration.
* Stream Scoring can rank or reject sources based on quality, resolution, HDR, audio, codec, language, release metadata, cache status, and file size.

# Tracking & Metadata

* SIMKL, MDBList, and self-hosted Floppy/Yamtrack integrations with watch history, ratings, Calendar, Continue Watching, and multi scrobbling.
* Extensive anime metadata support across IMDb, MyAnimeList, Kitsu, AniDB, AniList, SIMKL, TMDB, and TVDB.
* Automatic anime numbering/matching through a bundled Fribb mapping database.
* Filename-only resolution for debrid and cloud libraries, turning raw files into properly identified titles and artwork.

# Customisation

* Custom hero discovery rules and metadata badges without modifying the application.
* Custom themes, accent gradients, system fonts, poster layouts, detail-page backgrounds, keyboard shortcuts, settings navigation, and player HUD scaling.
* Configurable provider preferences, source scoring, subtitle/audio filtering, playback behaviour, and discovery rows.

# Other

* The settings menu has been fully redesigned to function like a desktop client rather than mobile.
* Full hotkey remapping.
* Display expected quality in the hero via QualiCache.
* Application and player UI scaling sliders.
* Change the font of the application to any installed font.

The design of Nuvio HTPC is partly inspired by Nuvio TV and Stremio Kai. 

## Custom Hero Discovery Config & Badges

Nuvio HTPC can show hero info badges for awards, festivals, critic signals, release status, language, notable studios/directors, trending/cult/true-story metadata, short films, mini series, binge-ready shows, and new releases.

You can extend the studio/director list and override the bundled badge images without touching the code. Create this folder first:

```text
%LOCALAPPDATA%\NuvioHTPC\Badges
```

For most Windows users this expands to:

```text
C:\Users\<you>\AppData\Local\NuvioHTPC\Badges
```

### Custom `hero_discovery.json`

Place a file named `hero_discovery.json` in `%LOCALAPPDATA%\NuvioHTPC\Badges`.

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

## Upstream - About

Nuvio Desktop is a media client for browsing metadata, managing collections and watch progress, downloading media, and playing streams from user-installed extensions or user-provided sources.

## Upstream - Installation

Download the latest desktop build from [GitHub Releases](https://github.com/NuvioMedia/NuvioDesktop/releases/latest).

Release packages are provided for supported desktop platforms:

- Windows: MSI installer
- macOS: DMG installer
- Linux: DEB package, when available

## Upstream - Development

```bash
git clone https://github.com/NuvioMedia/NuvioDesktop.git
cd NuvioDesktop
```

Run from source:

```bash
./gradlew :composeApp:run
```

On Windows PowerShell:

```powershell
.\gradlew.bat :composeApp:run
```

Build a release package for the current host:

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS
```

Platform-specific packaging:

```bash
# Windows
./gradlew :composeApp:packageReleaseMsi --rerun-tasks

# macOS
./scripts/build-macos-release-dmgs.sh --package-only

# Linux
./gradlew :composeApp:packageReleaseDeb
```

## Upstream - Project Structure

- `composeApp/` contains the app code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific integrations.
- `composeApp/Configuration/DesktopVersion.properties` contains the desktop release version and build code.

## Upstream - Versioning

Desktop versions are set in `composeApp/Configuration/DesktopVersion.properties`.

```properties
VERSION_NAME=0.1.1-alpha
VERSION_CODE=1
```

Use the version helper when changing desktop release versions:

```bash
./scripts/set-version.sh --desktop 0.1.2-alpha --desktop-code 2
./scripts/set-version.sh --show
```

## Upstream - Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information, including our full disclaimer, third-party extension policy, and DMCA/Copyright information, please visit our [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Upstream - Built With

- Kotlin Multiplatform
- Compose Multiplatform
- Kotlin
- Compose Desktop packaging
- Native desktop player integrations

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[contributors-url]: https://github.com/NuvioMedia/NuvioDesktop/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[forks-url]: https://github.com/NuvioMedia/NuvioDesktop/network/members
[stars-shield]: https://img.shields.io/github/stars/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[stars-url]: https://github.com/NuvioMedia/NuvioDesktop/stargazers
[issues-shield]: https://img.shields.io/github/issues/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[issues-url]: https://github.com/NuvioMedia/NuvioDesktop/issues
[license-shield]: https://img.shields.io/github/license/NuvioMedia/NuvioDesktop.svg?style=for-the-badge
[license-url]: https://github.com/NuvioMedia/NuvioDesktop/blob/main/LICENSE
