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

[![Nuvio TV Mode Demo](https://img.youtube.com/vi/aindWO1cJJU/maxresdefault.jpg)](https://youtu.be/aindWO1cJJU)

This fork is unaffiliated with the Nuvio team. It is intended to be a fork focused solely on the best possible experience for Windows. Don't worry about the commit disparity, Nuvio's team makes many small commits while I bundle everything into a single release commit. Their method is more professional for open source projects but I'm going for simplicity and not working on this with anyone else. 

Here is a non-exhaustive feature list:

**Home, Browsing & Discovery**

- Adaptive Hero with backdrop tinting and manual vertical positioning, plus a full TV Mode UI for full-screen browsing.
- Customizable hero badges for awards, festivals, critic signals, release status, language, trending/cult/true-story metadata, notable studios/directors, short films, mini series, binge-ready shows, new releases, post-credits scenes, and QualiCache release quality.
- Custom hero discovery configuration and badge images, so studios, directors, labels, placement, size, priority, and artwork can be extended without changing the code.
- Trailers in the Home hero, Search, Library, Collections, or full screen, with configurable autoplay delay, mute, and volume controls.
- Search and Library as full home-mode screens with Adaptive Hero and TV Mode layouts, infinite scrolling, image prewarming, persistent search and collection positions, and page/edge keyboard jumps.
- Landscape or portrait poster cards with optional titles and catalog rating badges; Collections can keep their own portrait-card preference.
- A provider-backed Calendar (press `C`) with upcoming and recent airings/releases in a month-view poster grid.
- A Discover section built from your own watch history, with Because You Watched, Finish What You Started, More Like Your Favourites, Hidden Gems, and Trending In rows, per-row counts, drag-to-reorder, hidden rows, excluded genres, and a persistent one-hour row cache.
- Discover custom rows built from a TMDB search of your own - genres, sort order, minimum rating and votes, release status, original language, US certification, runtime, years, studios, cast, and crew - editable in place and promotable onto Home as a live collection.
- Optional AI-generated Discover rows using your own OpenAI, Anthropic, or OpenAI-compatible key, with presets, free-form prompts, history-based suggestions, explicit consent about what is sent, and TMDB matching so invented titles are dropped.
- Discover rows can be exported as catalog files, published to an MDBList static list, copied for BingeCat's Bulk Add, or exported for AIOMetadata; imported queries return as live rows and imported lists stay frozen.
- Discovery directly inside Search through the compass or `Tab`, plus Random Play for movies, series, anime movies, and anime series with genre, rating, Collections, details, and immediate-play filters.
- Poster hover previews with backdrop, description, and quick actions, remembered per display mode, and a focused-poster highlight of a white border, accent border, brightness lift, or light sweep across Home, episode lists, Continue Watching, and Collections.
- Clicking a row title can reshuffle that row, pulling extra pages first so it deals in titles you have not scrolled to.
- Full-screen hero trailers in Basic mode, and a TV Mode full backdrop that runs to the bottom of the screen with the rows floating over it.
- Episode search across series and anime details by title, description, or season/episode number.
- TV Mode row-jump dots, optional row numbers, per-catalog marker colours, row-name labels, and tooltips for navigating long Home and Collection screens.
- Selectable detail-page backgrounds (Normal, Cinematic, and Dominant Colour), optional IMDb episode ratings, long-synopsis teleprompter scrolling, and detail-page trailer previews.
- TMDB/TheTVDB hero-art source controls, a first-launch API-key helper, and larger poster options.
- A Game Mode toggled with `G`, matching your local games against IGDB with a Twitch client ID and secret, optional SteamGridDB clear logos, and a black-shelf or full-screen backdrop.

**Tracking, Metadata & Integrations**

- Trakt and SIMKL alongside MDBList and self-hosted Floppy (Yamtrack fork), with dual scrobbling, watched history, ratings, Calendar, Continue Watching, and provider fallbacks.
- Unified provider selectors for Library, Calendar, and Continue Watching, including watched-history import without a Nuvio account, manual watched-state syncing, and optional SIMKL daily-visit automation.
- Anime metadata identity selection using IMDb, MyAnimeList, or Kitsu IDs, shared by catalogs, Continue Watching, Local Library, watch progress, episode matching, and enrichment. A bundled Fribb mapping database keeps AniDB, AniList, Kitsu, MyAnimeList, SIMKL, IMDb, TMDB, and TVDB numbering aligned.
- Local anime classification with dedicated Anime Movies and Anime Series shelves, Kitsu matching, manual overrides, sibling-season mapping, and a Kitsu-assisted local episode-repair tool.
- Filename-only catalog and cloud-library resolution through TMDB, turning raw TorBox, Premiumize, and library addons into real titles and artwork. Custom poster-service URLs can use media IDs and TMDB/MDBList API-key placeholders, and can be tested from Settings.
- Portable account-sync controls for choosing which categories sync to a profile; fork-specific desktop settings remain local. Optional P2P support is bundled for compatible sources.

**Library, Downloads & Sources**

- Local Library support for scanning and playing movies and TV shows from PC folders, with automatic TMDB matching, manual fix-matching, anime support, custom catalogs, search/filtering, persistent filters, and local-versus-online playback preference.
- Library sorting by default order, recently added, or title, with controls in the search bar and Library navigation. Series details also offer random-episode and mark-watched/unwatched actions.
- Library Auto-Downloads for monitored movies and series, with configurable release delays, check intervals, startup checks, episode-selection modes, concurrency, bandwidth, size limits, pause-while-playing, and a full activity/history page.
- Manual Debrid Library Grabs from magnets, infohashes, or supported links, including cached-source inspection, file-to-movie/episode mapping, and downloads into a Local Library folder.
- Season-pack inspection and episode selection for TorBox and Premiumize, with editable episode numbers, Select all/none, one-season actions, season-pack indicators, torrent/file-content lookup, and automatic expansion of monitored episodes from a downloaded pack.
- A debrid cloud library listed newest first within a configurable "added within" window, where opening a season folder offers an episode chooser instead of starting S01E01.
- Optional advance stream preparation on entering a details page or highlighting a Continue Watching item, with a configurable result expiry and an option to resolve the top result's debrid link ahead of playback.
- Opt-in Stream Scoring that ranks or rejects sources using quality, resolution, HDR, audio, codec, release metadata, language, debrid cache status, and file size. Scores can control source ordering, merged views, first-play selection, failover, next-episode autoplay, and auto-downloads, with score breakdowns and rejection reasons in the UI.
- Resumable downloads with speed/ETA, disk-space checks, throttling, multiple connections where supported, safer validation, grouped movie/show views, bulk deletion, and Local Library access to the download panel.

**Desktop Player & Playback**

- A purpose-built desktop player UI with responsive controls, clock and end-time display, episode and source browsers, subtitle and audio panels, contextual settings, preset feedback, and mouse/keyboard interaction.
- Desktop Picture-in-Picture mode with inline pause/seek controls, draggable and resizable floating windows, and remembered size and position.
- A nested right-click player menu for playback, subtitles, audio, video, window controls, curated advanced MPV options, stream failover, diagnostics, and copying stream links.
- Optional automatic stream failover that retries untried sources after errors, startup timeouts, diagnostic videos, or provider-specific rate limits.
- Tuned MPV configuration with three color profiles, Anime4K shaders and anime-tuned scaling/deband from [Stremio Kai](https://github.com/allecsc/Stremio-Kai), SVP frame interpolation, NVIDIA RTX Video True HDR, and optional external shader libraries.
- Binge mode, source affinity, reliable next-episode autoplay, Previous Episode navigation, playback speed defaults with optional 0.1x increments, volume boost up to 200%, and Low Data/Balanced/Resilient buffer presets.
- Seek-bar thumbnail previews with chapter names, interactive chapter markers, SkipDB segment support, and chapter-based intro/outro/preview skip prompts when community timestamps are unavailable. Skip prompts can be accepted automatically from chapter markers alone or from any timing source, with outros always waiting for a press.
- A configurable seek step for the seek buttons, arrow keys, and double-tap gesture, a duration readout that swaps to remaining time on click, and player notification pills positioned in the centre or at top centre.
- Advanced MPV configuration modes (Off, Add, Replace, and Full), verbose MPV logging, persistent redacted player logs, and a live diagnostics overlay.
- Independent desktop-app and player-HUD scaling controls, an always-visible playback clock, built-in subtitle-language filtering, subtitle drop-shadow styling, dual subtitles, original-language preference, and name-based subtitle/audio rejection filters.
- Hex-value custom subtitle colours, and ASS/SSA styling left as authored, resized only, or fully overridden with your own size, position, colour, and font.
- Anime enhancement controls covering whether Western animation counts as anime and whether shaders are skipped on sources that are already 4K, with `F10` still forcing them on.
- Desktop playback panels for aspect ratio, audio, subtitles, sources, episodes, subtitle styling, and technical diagnostics.
- Paste a direct HTTP(S) video link with `Ctrl+V` or drop a local video file from Explorer to play it immediately. Direct links and local files derive cleaner title, year, season, episode, and episode-title metadata from filenames.
- Portable installations can stage and install verified updates automatically when the app exits, with a manual fallback for development or unwritable installs. A Stable/Nightly channel picker can switch the current build to the latest one on either channel.

**Input, Profiles & Desktop Experience**

- Keyboard controls throughout the application, rebindable player shortcuts, global Home/Search/Library/Calendar/Fullscreen/Back shortcuts, and enhanced mouse scrolling and drag behavior.
- Hotkeys are listed and rebindable within the application.
- Custom desktop resume dialogs, richer Discord Rich Presence modes and artwork, and desktop-native stream context menus for copying, downloading through the browser, and launching external players.
- Remembered windowed size, position, and maximized state, configurable desktop and player UI scale, and a Windows-friendly native player runtime.
- A first-run setup wizard covering display mode, TMDB and MDBList keys, trailers, and controls, re-runnable at any time from Account settings.
- Any installed system font can replace the bundled one throughout the app and the player controls, and custom themes can apply a two-stop accent gradient in four directions.
- Settings categories can be hidden, renamed, and reordered, with hidden pages still reachable from settings search; the navigation bar can keep its top bar visible and show or hide the Discover tab.
- An on-disk image cache and a RAM-proportional decoded-artwork cache, alongside pooled and rate-limited TMDB requests, keep browsing fast without re-fetching artwork on every cold start.
- Native Windows crash reports and minidumps, bounded JVM freeze diagnostics, and the latest five redacted MPV sessions collected under `%LOCALAPPDATA%\NuvioHTPC\logs`.

The design is partly inspired by Nuvio TV and Stremio Kai. 

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

## Upstream - Star History

<a href="https://www.star-history.com/#NuvioMedia/NuvioDesktop&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=NuvioMedia/NuvioDesktop&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=NuvioMedia/NuvioDesktop&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=NuvioMedia/NuvioDesktop&type=date&legend=top-left" />
 </picture>
</a>

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
