# Changelog

## 1.14.0 - 2026-09-05

### Added

- **Discover** - a new section (and optional navigation-bar tab) that builds recommendation rows from what you have already watched, without needing another addon. Built-in rows include Because You Watched, Finish What You Started, More Like Your Favourites, Hidden Gems, and Trending In your most-watched genres, each with its own row count or on/off switch. Rows can be dragged into the order you want or hidden, watched titles can be left out, and whole genres can be excluded from both the rows and the taste profile behind them. Built rows are cached for an hour and persist across launches so the tab opens quickly.
- **Discover Custom Rows** - build a row from your own TMDB search instead of an addon catalog: films, shows, or both, any combination of genres (matching any or all of them), sort order, minimum rating and vote count, release status, original language, US certification, runtime range, release-year range, production companies, cast, and crew. Custom rows live on Discover only, can be edited in place, and can be promoted onto Home as a normal live collection.
- **Discover AI Rows** - optionally generate rows by asking a language model, using your own key for OpenAI, Anthropic, or any OpenAI-compatible endpoint such as OpenRouter, Groq, Ollama, or LM Studio. Presets cover hidden gems, comfort watches, acclaimed-but-missed, because-you-just-watched, and clustering your favourites, alongside free-form prompts, with suggested rows worked out from local watch history. Nothing is sent until you press Generate, a consent screen states exactly what leaves the machine (watched titles by name only), the key is stored locally and never synced, and suggestions are matched against TMDB so invented titles are dropped.
- **Discover Export and Import** - any built row can be handed elsewhere: saved as a `nuvio-discover-catalog` file, published into an MDBList static list where AIOMetadata, BingeCat, and similar services can read it as an ordinary catalog, copied as IDs for BingeCat's Bulk Add, or exported as a Nuvio collection file for AIOMetadata. Importing a file with a query behind it produces a live custom row; a fixed list comes in frozen exactly as it was exported, and imported lists can be renamed or trimmed.
- **Game Mode** - press `G` anywhere in the app to switch between your games and the rest of Nuvio. Games are matched against IGDB using a Twitch client ID and secret, with an optional SteamGridDB key for transparent clear logos, and the shelf can use a black-shelf or full-screen backdrop.
- **First-Run Setup Wizard** - new installs are walked through display mode with full-size previews, TMDB and MDBList keys, trailer placement and start behavior, and keyboard controls, ending on a summary and the shortcuts page. It can be re-run at any time from Account settings, and new-install defaults were revised throughout.
- **Update Channels** - Updates settings now carry a Stable/Nightly channel picker, a "switch build" action that replaces the current build with the latest one on the chosen channel, and an option to install updates automatically without a manual extraction step. Nightly builds are labelled as such in the build information line.
- **Stream Preparation In Advance** - streams can be searched ahead of time when a details page is opened, and optionally when a Continue Watching item is highlighted, with a configurable expiry for prepared results and an option to resolve the debrid link for the top result as well, so playback starts without waiting on the provider. Series prepare the next-up episode. Off by default, since it costs extra provider requests and triggers AIOStreams preloading when that is enabled.
- **App Font** - the whole application, including the player controls, can use any installed system font instead of the bundled JetBrains Sans, with a searchable picker and live preview.
- **Accent Gradient** - custom themes can set a second accent stop and a gradient direction (horizontal, vertical, or either diagonal), applied across accent surfaces. Setting the second stop to the accent color keeps accents flat.
- **Focused Poster Highlight** - the focused or hovered poster can be marked with a white border, an accent border, a brightness lift, or a light sweep, and the choice applies to Home, episode lists, Continue Watching, and Collections.
- **Shuffle Rows On Title Click** - clicking a Home catalog row's title reshuffles it, pulling a few extra pages first so the row deals in titles you have not scrolled to. The order lasts until restart. Suggested by Tick; off by default.
- **Configurable Seek Step** - the seek buttons, arrow keys, and double-tap gesture jump by a distance you choose, and the on-screen labels name that distance.
- **In-Player Notification Position** - the transient player pills for playback speed, volume, and aspect ratio can sit in the centre or at top centre, clear of the existing corner information and of the notch.
- **Always Show Top Bar** - an Appearance option keeps the navigation bar on screen instead of fading it out.
- **Remaining Time Toggle** - clicking the duration to the right of the seek bar swaps it between total and remaining time.
- **Poster Hover Preview** - upstream's hover card is merged in: resting the pointer on a poster opens a card with its backdrop, description, and quick actions. It is on by default in Basic, can be enabled in Adaptive, is remembered per display mode, and is deliberately unavailable in TV mode, where that information is already on screen.
- **Basic Mode Trailers** - Basic mode can play hero trailers full screen.
- **TV Mode Full Backdrop** - the backdrop can extend to the bottom of the screen with the rows floating over it, instead of ending above the shelf and fading to black.
- **Automatic Skip Acceptance** - the skip prompt can be accepted for you at three levels: manual as before, chapter-based using the file's own chapter markers, or any source including community and API timings, whose accuracy varies. Outros always wait for a press.
- **Custom Subtitle Colours** - subtitle colors accept a hex value (`#RRGGBB`, or `#AARRGGBB` to set opacity as well) alongside the presets.
- **ASS/SSA Subtitle Control** - styled subtitles can be left exactly as authored, resized only, or fully overridden with your size, position, colour, and font. Override is the only mode that can move them, at the cost of misplacing signs and flattening karaoke effects.
- **Settings Category Customization** - categories you do not use can be hidden and are still reachable from search, names can be changed by double-clicking them, and the Configure icon beside the heading can be hidden.
- **Cloud Library Window and Episode Chooser** - the debrid cloud library lists newest first within a configurable "added within" period, and opening a season folder now offers a list of episodes instead of immediately playing S01E01.
- **Western Animation and 4K Shader Controls** - anime enhancement can optionally treat anything tagged Animation as anime, and can be skipped automatically on sources that are already 4K, where the upscaling shaders gain little and can overwhelm the GPU. A manual force with `F10` still applies.
- **QualiCache Release Trust** - a minimum release-group trust tier (High, Medium, Low) controls which releases QualiCache is allowed to use.
- **Discord Episode Artwork Choice** - episodes can show the series poster or the episode still in Rich Presence, with the other used as a fallback when the first is missing.
- **Search History Clearing** - recent searches can be cleared from the media search.
- **Local Library Browser** - local library customization opens in its own modal with far less vertical scrolling, and Settings shows how many titles are unmatched.

### Improved

- **Image Caching** - Coil builds no disk cache on the JVM, so every image was re-fetched over the network on a cold start and animated collection art was re-downloaded whenever a card left the viewport. Nuvio now keeps its own on-disk image cache, and the in-memory decoded-artwork budget is a fraction of physical RAM (96-384 MB) instead of the fixed 76.8 MB that Coil's non-Android default worked out to, which a single 1440p Search or Library screen could evict within one row.
- **Browsing and Startup Performance** - a broad optimization pass across catalog loading, row rendering, and image decoding, plus reduced RAM use, fewer API calls, idle heap trimming, and a separate pass on playback start-up time.
- **TMDB Request Pacing** - TMDB traffic goes through a single client with its own lanes, shared rate-limit back-off, and single-flight deduplication, plus an on-disk external-ID cache, so large Discover builds and catalog resolution no longer starve each other.
- **Download and Install Size** - the Windows player runtime was being packed into every download twice; only the copy beside `Nuvio.exe` is ever loaded, so the duplicate is gone and the download is over 100 MB smaller.
- **Settings Presentation** - several rounds of visual work, a large cull of redundant explanatory text, sidebar icons 15% larger, and the first click on the settings panel no longer stalls.
- **Next Up Accuracy** - upcoming-episode countdowns interpret release timestamps consistently across providers, so "airs in" times and new-episode badges land on the right day.
- **Continue Watching Artwork** - a forced Continue Watching resync retries image URLs that failed earlier in the same session instead of leaving the card blank.
- **Build Information** - the build line reports version, build code, channel, and build timestamp.
- **Smooth Scrolling** - mouse-wheel scrolling is eased in Basic and Adaptive modes.
- **Audio Track Labels** - the audio track list shows each track's language in its subtext.
- **Marked-Watched Feedback** - marking something watched during playback now confirms it on screen.
- **Source Selection Availability** - the select-source action is always offered on Home Continue Watching and in the episode selector, regardless of the local-library playback preference.
- **System Tray** - the tray icon is drawn from a multi-resolution image and carries a modern tooltip and menu.
- **SIMKL** - Continue Watching no longer filters out watched movies, so a rewatch does not vanish partway through, and rewatches can be started from the poster options menu on Home.

### Fixed

- **Next Episode With Certain Anime** - autoplay could pick the wrong next episode for some anime.
- **Anime Intro Skip** - skip lookups for anime IDs reached no provider and returned nothing.
- **Trailers** - hero and details trailers work again, and start faster than before.
- **Discord Rich Presence Artwork** - posters and thumbnails now resolve, including titles identified by Kitsu ID, and custom poster services no longer break the image.
- **Continue Watching Crash** - dismissing a Continue Watching item could crash the app.
- **Player Context Menu** - the right-click menu no longer closes when using nested dynamic options.
- **Player Episode Thumbnails** - in-player episode artwork occasionally failed to load.
- **Volume Changes** - adjusting volume no longer brings the whole player UI back on screen.
- **Catalog Row Scrolling** - horizontal scrolling over a large list of catalogs during initial load no longer misbehaves.
- **Low Seek Values** - a custom seek distance set to a low value now works correctly.
- **Right-Click Download Year** - the wrong release year could be used when downloading from the right-click menu.
- **Local Library Parsing** - corrected a filename parsing failure during local library scans.
- **Game Mode Launch Text** - launch text is drawn in white rather than black against the dark popup background.
- **Miscellaneous** - fixed an intermittent crash reported by a user, and a possible TorBox failure that could not be reproduced locally.

## 1.13.0 - 2026-08-15

### Added

- **Windows Media Controls** - Windows playback now registers a system media session with title and artwork, so hardware media keys and Windows controls can play, pause, stop, or move between episodes even when Nuvio is not focused. Nuvio can consequently appear alongside browser media sessions in Windows audio controls; an existing taskbar pin may need to be recreated once if Windows retains the old app identity.
- **Close to Notification Area** - a new Appearance option makes the window's close button hide Nuvio in the system tray, whose menu can reopen or fully exit the app. The power-off action in Settings still exits normally.
- **Desktop Settings Backups** - Account settings can save all desktop preference files as a ZIP, either with credentials included or with passwords, tokens, API keys, sign-in sessions, and configured addon URLs removed for safer sharing and storage.
- **Configurable Playback Speed Toggle** - the `R` shortcut now flips between two user-defined speeds from 0.5x to 4x. It also stays in sync after playback speed is changed through the player UI.
- **SDH Subtitle Preference** - an optional playback preference prioritizes subtitles identified as SDH, closed captions, hearing impaired, or hard of hearing when several tracks match the preferred language.
- **Metadata Rework Following Upstream** - metadata handling now follows upstream's implementation across normal catalogs, Continue Watching, Local Library, and related playback flows. This is a broad change: anime can use IMDb, MyAnimeList, or Kitsu identity, with the choice shared by Local Library and Continue Watching; metadata-derived IDs, anime mapping, watched/progress matching, episode handling, and downstream enrichment all use the selected model. IMDb remains the most addon-compatible choice, while MAL and Kitsu require metadata addons that support those IDs.
- **Random Play** - an optional Home catalog can pick a random movie, series, anime movie, or anime series from loaded catalogs. It supports standard and anime-specific genre filters, a minimum IMDb rating, optional Collections catalogs, and opening details or starting playback immediately. Anime detection now also uses catalog types and native anime IDs, improving results from addons whose items are otherwise labelled as ordinary movies or series.
- **Episode Search** - series and anime details can search episodes by title, description, or season/episode number using the search hotkey. On desktop, the search field is built into Play so it remains usable while a trailer is playing.
- **Landscape Poster Cards** - Home, Search, Library, and TV mode can use landscape posters while Collections keep portrait posters if desired. Landscape cards can optionally show text titles and a catalog rating badge, disabled or formatted out of 10 or out of 100.
- **Post-Credits Discovery Badge** - movie discovery metadata can show a badge for mid-credits, post-credits, or both types of stinger scenes when MDBList keywords identify them.
- **Portable Settings Sync Controls** - Account settings can now opt individual categories into profile synchronization, including appearance, Home catalogs, stream display, debrid, metadata, content preferences, Trakt, and notifications. Fork-specific desktop settings remain local to the device.
- **Remembered Windowed Desktop Layout** - desktop builds can start windowed and remember the last valid window size, position, and maximized state.
- **Low VRAM Mode** - a new playback setting (Off / Auto / On, default Auto) trims the video rendering pipeline to bilinear scaling with no debanding, dithering, or HDR peak detection. Auto turns it on for integrated graphics and GPUs reporting under 2 GB of video memory, where the full pipeline could exhaust video memory on 4K files and crash the player. Anime4K and custom shader chains are left untouched.

### Improved

- **Addon-Supplied Landscape Posters** - landscape cards now use purpose-built 16:9 art when a metadata addon provides it, such as AIOMetadata's Landscape URL Pattern, instead of cropping a backdrop. Because that art already has the title composited into it, Nuvio no longer draws its own logo or text title over the card. Titles the addon has no landscape art for still fall back to the previous backdrop-and-logo card.
- **Subtitle Selection and Persistence** - manually selected built-in and addon subtitles are remembered across episodes, including season-pack playback, and delayed addon results no longer overwrite a viewer's choice. The subtitle menu now shows language, addon, and track IDs, marks the active choice with a focus border instead of a tick, and reveals truncated track information on hover.
- **Subtitle Matching and Automation** - automatic selection now handles exact regional language matches, SDH preference, `tv`/`series` addon compatibility, dual-subtitle Original and Device languages, forced-subtitle behavior, and external-player forwarding more reliably. Subtitle filters also avoid hiding every track when no concrete preferred language can be resolved.
- **Episode and Season Navigation** - the in-player season selector accepts mouse-wheel scrolling and keeps the selected season in view, making shows with many seasons easier to browse.
- **Provider Watched Feedback** - when the selected tracking provider confirms that a single episode was marked watched, Nuvio now shows a success message naming that provider.
- **Network and Studio Browsing** - clicking a network or studio logo on Details now opens its titles through the shared Home catalog renderer, so TV Mode, Adaptive Hero, loading, and pagination behavior apply consistently.
- **Continue Watching Episode Artwork** - missing episode thumbnails are rechecked on app startup, a forced Continue Watching resync, the first progress update, and episode completion, allowing newly published stills to replace blank artwork.
- **Home Hero Information** - truncated plot synopses now start a teleprompter-style scroll after the focused item has remained selected for four seconds.
- **Home, Search, and Addon Loading** - Home hotkeys behave correctly after returning from Calendar, search exposes more applicable content, and catalog rows can be scrolled while plugins/addons are still loading without the list jumping around.
- **Continue Watching** - items can open Details instead of immediately playing, and Continue Watching no longer gets stuck during search. The row also avoids starting playback unless the relevant autoplay setting is enabled.
- **Local Library and Resolved Catalog Metadata** - local files and filename-resolved entries now populate the same useful metadata as regular catalog items, including artwork and episode information. Local Library also respects the TMDB enrichment setting for proper backdrops.
- **Custom Poster Services** - local-library poster templates can use raw Stremio IDs plus TMDB and MDBList API-key placeholders alongside the available media IDs, and the settings page can test a configured template against a known title.
- **Details Dialogs and Trailers** - opening a Details dialog now pauses and minimizes the trailer so the dialog remains visible. Right-click Add to Library is provider-agnostic instead of being hard-coded to Trakt.
- **Poster Service Compatibility** - Posters+ receives raw Stremio IDs through `{id}`, allowing its rewritten anime handling to work for Kitsu-backed catalogs without the `{kitsu_id?}` workaround.

### Fixed

- **Landscape Tiles in Catalog Grids** - opening a landscape catalog with See All no longer squashes its tiles to a narrower shape than the Home rows use, so grid and shelf now crop the same 16:9 image identically.
- **Discovery Language Flags** - language discovery badges now use recognizable flag geometry instead of simplified colour bands that rendered several countries incorrectly, while unknown languages retain a globe fallback.
- **Details Page Logos** - Details now falls back to the lightweight metadata path when normal enrichment returns no logo, avoiding title-text fallbacks for items whose logo is available elsewhere in Nuvio.
- **Windows Borderless Fullscreen Insets** - corrected stale AWT frame insets that could offset and clip the Compose UI or native video surface after entering borderless fullscreen.
- **Autoplay and Local Files** - downloaded local-library files no longer bypass the configured autoplay behavior, and Continue Watching avoids instant playback unless explicitly enabled.
- **Episode Ratings** - IMDb episode ratings work again.
- **Audio Track Selection** - selecting an audio track now reaches the native player reliably and keeps the selected track in sync.
- **Player Focus and Auto-Hide** - the Skip Intro button no longer steals focus or prevents the player UI from hiding.
- **Home and Calendar Hotkeys** - keyboard actions no longer get into a bad state after navigating through Home and returning from Calendar.

## 1.12.0 - 2026-08-01

### Added

- **MDBList and Floppy (Yamtrack Fork) Tracking** - playback can now scrobble to MDBList or to a self-hosted Floppy instance using the supported scrobble-API fork. MDBList can also provide watched history, Calendar data, Continue Watching sessions, and MyAnimeList ratings; Floppy includes instance URL/API-token setup and connection testing. Continue Watching can be sourced from Nuvio Sync, Trakt, SIMKL, or MDBList, with a separate option to include or exclude Nuvio Sync when seeding the next-up row.
- **Unified Tracking Sources and Ratings** - Library, Calendar, and Continue Watching now each have a single provider selector, with authenticated fallbacks and migration of existing choices. Watched history from connected providers can be imported without a Nuvio account, manual watched changes are written to the selected Library provider, and MDBList, SIMKL, or Floppy users can rate titles from details or an optional movie/finale prompt.
- **Series Quick Actions** - series details can now start a random episode or mark the whole series watched or unwatched from the hero actions.
- **Library Auto-Downloads** - monitor movies and series from their detail pages or from the Local Library, choose the destination folder, and have Nuvio periodically search for and download missing content. Series can monitor all missing episodes, selected episodes plus future releases, or selected episodes only; movies can download when a source becomes available.
- **Library Download Controls** - added configurable check intervals, app-start checks, post-release delays, concurrent-download limits, aggregate bandwidth limits, per-movie and per-episode size limits, and an option to pause automatic downloads while playing. The new Auto Downloads page also shows monitored titles and download activity, with pause/resume, cancel, retry, force-start, replacement, and history-clearing actions.
- **Manual Debrid Library Grabs** - paste a magnet, infohash, or supported debrid link, inspect the files inside a cached source, map files to a movie or episode, and download the selected files into a Local Library folder. Supports Torbox and Premiumize source inspection.
- **Season Pack Episode Picker** - the stream right-click menu gained "Select episodes from pack": the pack's magnet is opened once on Torbox/Premiumize, the files inside are listed with editable episode numbers, and only the ticked episodes are downloaded into the chosen Local Library folder. Every file starts selected, with Select all and Select none beside the selection total, and season/episode numbers read as plain text that turns into a field when clicked.
- **Stream Scoring** - added an opt-in scoring profile that can rank or reject sources using quality, resolution, HDR, audio, channels, codec, release group, release flags, editions, language, debrid cache status, and file size. The settings page includes guided preferences, size bands, minimum-score filtering, a release-name test bench, score previews, and per-source score breakdowns.
- **Scored Playback Selection** - scoring can control the first automatic source, source-list ordering, merged/deduplicated source views, an optional HTPC source tab, next-episode autoplay, auto-download selection, and failover selection. Scores and rejection reasons can be shown on stream cards and in the player's Sources panel, with a darker score background for improved readability.
- **QualiCache Quality Badges** - an optional QualiCache server can add notable release badges to the Home hero, including 4K Blu-ray, 4K WEB-DL, Dolby Vision, HDR, Dolby Atmos, and DTS, with separate visibility controls for resolution, dynamic range, and audio badges.
- **SkipDB Segment Support** - SkipDB's approved intro, recap, outro, and preview data is downloaded and cached locally for fast playback lookups. Timestamp submissions can use a personal SkipDB key or an anonymous key created in Nuvio.
- **Library Sorting** - the Library can sort items by default order, recently added, or title, with the sort control integrated into the search bar and available from the Library navbar action.
- **Local Anime Episode Repair** - local anime titles can now open a Kitsu-assisted episode-mapping tool to correct episode assignments without changing the files, folders, or sidecars on disk. Kitsu entries can also be repaired through season downloads, including TMDB multi-season mapping, Kitsu auto-matching, and automatic renumbering for single-season entries.
- **Detail Page Enhancements** - added selectable Normal, Cinematic, and Dominant Colour backgrounds, plus optional IMDb ratings on episode cards, adapted from upstream.
- **TV Mode Row Navigation** - added clickable row-jump dots, optional catalog row numbers, configurable dot placement, per-catalog marker colours, and optional row-name appending for quickly navigating long TV-mode home screens. Dots also cover collections, respect the row-number setting, and show an instant tooltip while moving between rows.
- **Direct Playback Metadata** - pasted web streams and dropped local files now parse filenames for a cleaner title, year, season, episode, and episode title, so the player HUD and related metadata have more useful context. When possible, matching artwork is also found through active searchable addon catalogs.
- **Filename-Only Catalog Lookups** - catalogs that return raw release filenames instead of metadata (TorBox, the AIOStreams library addon) now have their rows looked up on TMDB by parsed name and year, with one or two years of tolerance on either side, so they show the real title and poster instead of the file name on a blank card. Resolved rows switch to portrait poster cards, and resolved posters go through the custom poster service when one is configured. Rows that do not match confidently are left untouched. Needs a TMDB API key; toggle on the TMDB settings page.
- **Cloud Library Titles** - the built-in TorBox/Premiumize cloud library section resolves its torrent names the same way, so the Library shows real titles and posters instead of release names. Resolution happens in the background and fills in as it completes, so the section still appears immediately. The same option can resolve AIOStreams library and TorBox cloud catalogs to artwork.
- **Season Pack Indicator** - stream rows detected as season packs now show a small layers icon beneath the score.
- **Torrent Name and Contents Lookup** - already-resolved debrid rows are labelled with the single file the addon picked, hiding whether the source behind them is a whole season. Torbox's cache check now returns both the torrent name and its file listing, in one batched call per addon that never delays the stream list, so a pack is identified by counting the episodes actually inside it rather than by reading a filename — and without adding anything to your account.
- **Automatic Season Pack Expansion** - when an automatic download lands on a season pack the debrid service can list, the other wanted episodes are taken from that same pack instead of being searched for one per check cycle. Only episodes already being monitored are added, and each is tracked like any other automatic grab. Controlled by "Take Whole Season Packs" in Auto Downloads, on by default.
- **One Season Action** - "Download season" and "Select episodes from pack" are now a single menu entry. It reads the source's file listing in one provider call where that is possible (Torbox, Premiumize), which is far faster than the old per-episode addon search and cannot silently drop episodes; anything that cannot be listed still uses the per-episode search, and a failed listing falls back to it rather than giving up. The entry appears on any episode row that can be opened on the debrid service, not only on detected packs, and reads "Inspect contents" there — so a missed detection costs a click instead of blocking the route.
- **SIMKL Daily Visit** - an optional toggle on the SIMKL settings page opens simkl.com in the default browser on the first app start of each day, so the visit streak that unlocks free SIMKL Pro is not missed. The day resets at midnight UTC and later launches the same day are skipped.

### Improved

- **Downloads** - downloads now support resumable partial files, progress speed and ETA reporting, disk-space preflight checks, configurable bandwidth throttling, multiple connections where supported, safer video-file validation, grouped movie/show views, and bulk season/show deletion actions. The download panel is also reachable from the Local Library.
- **Stream and Debrid Metadata** - debrid filters and stream rows now use a shared trait detector, keeping resolution, source quality, HDR, audio, codec, language, release-group, and size information consistent. Anime and animation use a lower implausible-size threshold, and surround-sound detection no longer mistakes 5.1 Mbps or 7.1 Mbps for channel layouts.
- **Stream Failover** - failover now uses stable source identities across refreshed stream lists, avoids retrying the same source, respects advertised audio-language preferences, and no longer gets stuck. The retry timeout is configurable, and the failover notification names the addon that was selected next.
- **Local Library Management** - local-library settings now organize titles into default or custom catalogs with title search, catalog filtering, and more tolerant title matching for accents and punctuation. Anime matching and Kitsu mappings are more reliable, anime entries go into the default anime catalog instead of Unsorted, and the matching dialog shows the file path. Playback can prefer the local file or open the source picker when both are available; the long-press action provides the other choice. Local-library filters persist when opening details, clicking a local poster opens details, and anime and regular titles remain separated in the library view.
- **Playback Track Selection** - added an Original-language preference backed by TMDB metadata and optional name-based rejection filters for signs, songs, karaoke, forced subtitles, commentary, descriptive audio, and visually impaired tracks. Rejected tracks are hidden and never selected automatically.
- **Playback Presentation** - added a paused source/provider overlay, configurable Sources shortcut placement, an optional startup playback-info panel, subtitle blur/italic/outline/shadow controls, and an SVP debug overlay. The active stream is pinned at the top of Sources, the player UI fades sooner, the full-screen control appears on hover, left/right navigation reaches controls beside Play, source/episode transitions are more consistent, and the parental guide sits below the title information instead of covering it.
- **Desktop Appearance** - added a windowed-start preference, more custom-theme surface colours, adjustable poster-card depth with per-surface controls, and an enlarged poster preview in action menus. Right-click poster zoom now also blurs the background and applies to Continue Watching and collections, poster downscaling uses a better resampler, labels are easier to read, and the desktop scrollbar is hidden in TV mode.
- **Home and Search Navigation** - search positions now survive returning from details for the same query, TV-mode keyboard navigation supports page and edge jumps, and focused heroes avoid showing a stale title while artwork or metadata is being enriched. Back now moves between the main Home, Search, Library, and Settings panels; episode lists and collections support the same Home/End/Page Up/Page Down navigation, and Mouse 5 acts as Shift for horizontal scrolling.
- **Metered Playback Buffering** - pausing with the Metered buffer preset now stops unnecessary prefetching while preserving enough data to resume, then restores the normal buffer budget on resume or seek.
- **Collection Navigation** - folder/collection layouts now remember their own scroll and focus position when returning from a details page, without carrying the position into a different collection. Catalog row labels, numbering, and poster zoom behavior also apply consistently to collections.
- **Discovery and Catalog Presentation** - discovery labels no longer repeat the addon name, duplicate catalog names are disambiguated with movie/show suffixes, discovery badges render more cleanly, and important settings are preselected as favourites for new users.
- **Recommendations and Poster Services** - Trakt users can choose Trakt or TMDB as the source for More Like This recommendations, and Library posters can be routed through a configurable custom poster-service URL template.
- **Settings and MPV Configuration** - settings sliders are smoother, Advanced MPV configuration can replace the built-in cache options, and the player now uses a browser-like user agent for sources that reject MPV's default identity.
- **Windows Freeze Diagnostics** - packaged Windows builds now keep bounded JVM safepoint and garbage-collection logs and move completed logs into the normal Nuvio log folder on the next launch, making intermittent UI freezes easier to diagnose.
- **Trakt Status** - the Trakt settings now explain that VIP is required and that the integration is effectively at end-of-life for maintenance.
- **CW Provider Moved to CW Section** - your provider for continue watching is now a single selector under continue watching.

### Fixed

- **Playback source matching** - fixed the playing source row and failover history being lost when addon URLs, labels, or provider metadata changed between refreshes.
- **Episode-aware source handling** - improved episode identity and title matching for local-library and background stream searches, reducing unrelated local files and wrong-episode results. An episode whose title matches its series title no longer prevents other episodes from returning.
- **Direct-link playback labels** - cloud/debrid URLs that use opaque path names can now use their `filename` query parameter when it is available instead of showing the raw object ID.
- **Trailer and Catalog Requests** - trailers now use the detail page's retry behavior, empty catalogs no longer trigger repeated API calls, and the search box no longer accidentally registers application hotkeys.
- **Player and Addon UI** - the stream-selector addon list can be scrolled, the built-in addons tab reports its state correctly when subtitles are active, player submenus close reliably, and an index-out-of-bounds crash in player navigation is prevented.
- **SVP and Thumbnail Playback** - SVP no longer activates on debrid failure messages, and the seek-thumbnail MPV instance is lazy, disabled for metered playback, and uncached for other presets.
- **Local Playback Indicators** - downloaded episodes now show their Local Library indicator on episode thumbnails, with corrected mapping and labels for right-click downloads.

## 1.11.1 - 2026-07-17

### Added

- **Merged Upstream P2P** - P2P flag's been switched on, the server bundled (same as upstream uses) and it should now work.
- **Native Crash Diagnostics** - packaged Windows builds now capture the JVM's native crash report and minidump (faulting module and stack) when the app terminates unexpectedly, and automatically file them alongside `nuvio.log` under `%LOCALAPPDATA%\NuvioHTPC\logs` on the next launch, so silent crash-to-desktop reports can be diagnosed straight from a normal log bundle without hunting through system folders.

### Improved

- **Plugin Stability** - capped how many plugin scrapers execute at the same time so loading streams with a large plugin set no longer spins up dozens of JavaScript engine instances at once - a suspected cause of rare silent crashes during stream loading. Addon-only setups are unaffected.

### Fixed

- **Keyboard Navigation on Adaptive Mode** - could cause the posters to get stuck behind the hero.
- **Anime Mapping** - should now properly map for CW and local library.

## 1.11.0 - 2026-07-17

### Added

- **New Desktop Player UI** - introduced a purpose-built desktop playback experience around the native MPV video surface. The new interface replaces the mobile-derived HUD with a responsive desktop layout, redesigned playback controls, clock and end-time display, episode and source browsers, subtitle and audio panels, contextual settings, preset feedback, and mouse-and-keyboard-focused interaction. 
- **Picture-in-Picture (PiP) Mode (Desktop)** - play media in a compact, floating window on top of other applications. Includes inline pause/resume and seek controls, native window dragging and 8-direction resizing, and memory of user-adjusted PiP size and placement for the current session.
- **Right-Click Player Context Menu** - a full nested menu on the video surface covering playback, subtitles, audio, video, and window controls, including a curated "Advanced (mpv)" submenu (debanding, deinterlacing, sharpening, upscaling, and loudness normalization presets) so those tweaks no longer require hand-typing raw mpv options, quick toggles for stream failover and the MPV diagnostics overlay, a copy-stream-link action, and a persisted "close menu after selecting" preference.
- **Automatic Stream Failover** - optional (off by default) setting that automatically retries the next untried source in place when the current one errors out, never starts within a configurable timeout, or turns out to be an addon's diagnostic/placeholder video. A rate-limit-aware guard scopes retries to the specific throttled provider, so a 429 from one debrid service doesn't cause rapid-fire retries across every other source.
- **In-Place Portable Updates** - Portable installs now update themselves. The verified download is staged, swapped into the install directory by a small detached helper once the app exits, and Nuvio relaunches automatically - replacing the previous "unzip it yourself" step. Falls back to the old manual-reveal flow for dev builds, non-ASCII install paths, or unwritable directories. Controlled by a new "Install updates automatically" toggle in Account settings (default on).
- **Seek Thumbnail Previews** - hovering over the player seek timeline displays a live, frame-accurate thumbnail preview generated on-the-fly by a background media engine instance, including the chapter name when the file has chapters.
- **Advanced MPV Controls** - custom mpv options box now has Off/Add/Replace/Full modes that govern exactly how your hand-written options interact with Nuvio's own built-in mpv configuration, loading cleanly before or in place of the built-ins depending on the mode.
- **Player & App UI Scale** - added independent scale sliders for the in-player HUD (Playback settings, -50% to +50%) and for the rest of the desktop app (Appearance settings, -25% to +25%, with an option to also apply it to the Details screen), stacking on top of the existing automatic per-resolution/HiDPI scaling.
- **Fine Playback Speed Increments** - optional setting that changes the speed shortcut/button step to 0.1x increments instead of jumping between fixed presets.
- **Previous Episode Navigation** - added a Previous Episode action (context menu and shortcuts) alongside the existing Next Episode controls.
- **Built-in Subtitle Language Filter** - the preferred-subtitle-language filter that already applied to addon-supplied subtitles now also applies to the Built-In subtitle tab, so embedded tracks get pared down to your preferred languages the same way.
- **Subtitle Drop Shadow** - added a drop shadow toggle under Subtitle Style settings to cast a soft drop shadow behind subtitle text for improved legibility against bright backdrops.
- **Always Show Playback Clock** - added an option to keep the current time and estimated content end time visible after the rest of the player interface fades out.
- **Local Anime Classification & Kitsu Matching** - added dedicated "Anime Movies" and "Anime Series" shelves with custom pink and orange colors for local libraries, powered by Kitsu.io matching and search, and supporting manual overrides. Sibling seasons are mapped together to surface local files on sibling anime pages.
- **Playback Control Panels** - added desktop selectors for aspect ratio (Fit, Fill, Zoom), audio and subtitle tracks, sources, episodes, subtitle styling, and a live MPV technical diagnostics overlay.
- **Rebindable Shortcuts Expanded** - alternate play/pause, toggle mute, skip intro/outro, and the MPV diagnostics overlay toggle can now be rebound like other player shortcuts. Core navigation (Home, Search, Library, Calendar, Fullscreen, Back) also now works as a global shortcut from anywhere in the app instead of only on specific screens.
- **Paste-to-Play** - pasting (`Ctrl+V`) a direct http/https video link outside of playback now launches it immediately, alongside the existing drag-and-drop support.
- **Verbose MPV Logging** - added an opt-in toggle (Advanced settings) to write mpv's verbose internal log to its own file for troubleshooting, kept separate from the app's normal diagnostic logs.
- **Resync Continue Watching** - added a manual action to force a fresh pull of Continue Watching from Trakt/SIMKL if it looks stale.
- **Discovery is Back** - discovery is now built into the search panel, click the compass or press tab.
- **Chapter Based Autoskip** - if there's no introdb timestamps, the application can read chapters and trigger the skip prompt based on those.

### Improved

- **Playback Speed Selection** - redesigned the context menu around immediate 1x, 1.5x, 2x, 2.5x, 3x, 3.5x, and 4x choices. Hovering a choice exposes the nearby 0.1x increments through 3.9x without requiring repeated slower/faster actions.
- **Discord Rich Presence modes and artwork** - redesigned integration into three selectable modes: Disabled, Watching (sharing playback only), and Full (sharing browsing, library, details, source selection, and playback activity), resolving potential status leaks. Poster artwork now fits within Discord's image area instead of being cropped or zoomed, paused playback shows a frozen time summary instead of a live-drifting Discord clock, and existing installs using the old on/off toggle are migrated automatically to Full.
- **Desktop Resume Prompts** - replaced floating resume alerts with a custom modal resume dialog on desktop. When using Adaptive Hero or TV layouts, the prompt embeds directly on the Home banner, holding focus and blocking catalog transitions until confirmed or dismissed. The prompt is no longer offered once a title is effectively finished (90%+ watched).
- **Desktop Stream Context Menus** - replaced mobile-style bottom sheets with native right-click desktop context menus for copying stream links, downloading (now saved through the browser rather than the internal downloads queue), and launching external players.
- **Search Image Prewarming** - dynamically enqueues image pre-fetching for the top results of each content category during active searching, loading relevant artwork instantly.
- **Robust Progress and Scrobble Sync** - prevented premature player close events from overwriting existing progress with 0% data, and filtered out temporary engine duration placeholders to avoid premature completion scrobbles.
- **Player HUD & Focus refinement** - added on-screen volume/mute pills, integrated a dedicated subtitle delay reset button, constrained double-click fullscreen toggles to the video surface only, and refined shortcut key focus release.
- **Player Source Affinity** - stream lists and autoplay now respect the initial source choice. Local-started playback keeps local files first and falls back to the first online stream when no local file is available; stream-started playback continues using online sources and excludes local files from autoplay.
- **Robust Next-Episode Autoplay** - implemented multi-sample position stability checks to avoid premature triggers during track changes or seeks, verification of genuine end-of-file, and safeguards that prevent failed resume seeks or temporary last-frame positions from skipping an episode. Episode resolution is now anime-numbering-aware, correctly following shows whose absolute episode count is split across multiple seasons/TVDB entries instead of rolling into a specials bucket by mistake.
- **Faster Playback Startup** - reduced MPV's initial cache pause so stable streams begin playing much sooner, scaling the startup pause window with playback speed rather than a flat delay, while retaining the app's three buffer presets for overall cache and readahead sizing.
- **Metadata Caching & Scope Optimization** - transitioned MdbList fetches to an app-lifetime coroutine scope to preserve API quota on cancelled home catalog scrolls, and introduced a 30-minute negative cache for network/API failures.
- **Local Library Configuration** - Anime Movies and Anime Series now appear in both Basic mode and the Advanced "All" view. Matching actions are correctly labelled "Search Kitsu", while compact poster status text uses "Not matched" and explains the scrobble limitation in the editor.
- **Plugin Provider Status** - the global providers toggle now displays an explicit Enabled or Disabled label alongside its color state.
- **Native Runtime Packaging** - isolated native DLL dependencies and Python libraries beside `Nuvio.exe` in jpackage app images, allowing the complete packaged runtime to run without extracting executables or requiring a writable installation directory. Also dropped the unused whisper/ggml audio-transcription stubs entirely (previously replaced with no-op placeholders to prevent crashes) by patching them out of the bundled FFmpeg build directly, shaving further size and build complexity.
- **Persistent MPV Diagnostics** - moved MPV logs beside `nuvio.log` under `%LOCALAPPDATA%\NuvioHTPC\logs`, retaining the latest five player sessions so an autoplayed next episode cannot erase the previous failure. Media URL paths, queries, and embedded credentials are redacted before writing.
- **Desktop Networking** - swapped the desktop networking stack to OkHttp with an IPv4-first resolver (avoiding a Windows IPv6 stall some users were hitting), longer timeouts, and safer handling of oversized or malformed responses from addons and plugins.
- **Plugin Compatibility** - plugin JSON/text handling is now more forgiving of malformed responses, encoding mistakes (garbled titles), and Stremio's nested proxy-header format, so more third-party plugins keep working instead of silently failing.

### Fixed

- **Crash during rapid stream replacement** - serialized native MPV creation and destruction across the process. Rapidly switching plugin-provided streams could previously initialize a new player while the outgoing instance was still tearing down shared VapourSynth state, causing an access violation inside `libvapoursynth.dll`.
- **Playback failures lingering in the player** - major startup failures now return immediately to stream selection and show a compact playback-error toast inside the selector. The playback screen is retained only when a provider diagnostic video successfully plays.
- **Unreported debrid rate limits** - FFmpeg HTTP 429 failures and supported addon rate-limit placeholders now report the specific "Debrid Rate Limited" error instead of failing silently or appearing as an unrecognized format.
- **Failed streams skipping episodes** - MPV seek and loading errors can no longer masquerade as a completed file, trigger next-episode autoplay, or scrobble failed playback as watched.
- **Provider Diagnostic Videos** - added provider-neutral recognition of addon diagnostic and error videos, including known Comet and StremThru endpoint formats. Successfully resolved diagnostic videos can be viewed normally but never register watch progress or remote scrobbles, and can now trigger automatic stream failover instead of being mistaken for real playback.
- **Episode-mismatched streams no longer offered** - streams whose filename or title explicitly declares a different episode than the one you're watching (a source of wrong-episode plays, especially on anime) are now filtered out before they reach the stream list.
- **Built-in subtitle tab accumulating stale tracks** - subtitles injected by addons no longer leak into and pile up in the Built-In subtitle list across a session.
- **Wrong subtitle/audio track carrying over between episodes** - track memory now matches by language and name first instead of raw track position, so the next episode doesn't inherit whatever happened to be track #2 on the last one.
- **Subtitle track selection race** - fixed a race between persisted track preference, the native track list, and addon auto-fetch that could cause a stray reseek/rebuffer right after starting playback.
- **Anime franchise/season mapping guessing wrong silently** - when a title's local numbering doesn't confidently map to a specific season/episode, Nuvio no longer guesses - proper multi-season matching now handles shows whose absolute episode count is split across several TVDB seasons (e.g. Pokémon).
- **SIMKL Continue Watching showing the wrong title/thumbnail for some anime** - now matches by episode title instead of trusting SIMKL's own numbering.
- **Local files could answer for an unrelated episode** - a local movie or episode file could incorrectly respond to a different show's episode lookup; local playback now confirms it actually owns the requested episode before answering.
- **Color picker triggering antivirus false positives** - the custom theme color picker no longer launches a PowerShell command to show the native color dialog (a common AV behavioral-heuristic trigger); it now uses the same picker already used on other platforms.
- **Broken parental guide ratings** - switched to a working ratings source after the previous one stopped responding.
- **Settings search returning stale results** - reopening settings search could show results left over from a previous query.
- **Trakt IDs using the full `myanimelist:` prefix** (not just `mal:`) now resolve correctly.

## 1.10.0 - 2026-07-10

### Added

- **Local Library support** - point Nuvio at folders on your PC to scan and play local movies and TV shows. Local titles integrate directly into the Library alongside Trakt/SIMKL (offering Movies/Shows rows in Basic layout or custom colored rows in Advanced layout). Local files also appear in the player's source selector, with solo local sources bypassing the picker to play immediately. Features automatic TMDB matching and manual fix-matching via search or IMDb/TMDB ID overrides.

- **Dual Subtitles (Desktop)** - play primary and secondary subtitle tracks simultaneously, displaying the secondary language at the top of the player overlay while keeping the primary track at the bottom.

- **Interactive Chapter Markers & Tooltips** - added visible chapter marker ticks on the seek timeline. Hovering over a timeline segment displays a tooltip with the current chapter's title.

- **Chapter-based Auto-skip Fallback** - introduced a parser that scans embedded chapters in file/stream headers to detect intro/outro segments (like `Opening` or `Credits`) and prompts to skip them as a fallback when community-sourced timings are missing.

- **Adjustable Hero Height** - added a "Hero height" slider under Home Layout settings to scale the Adaptive Hero banner size from 75% to 175%. Title logos and text layout dynamically scale to match the chosen height.

- **Smooth Mouse Wheel Scrolling** - added a "Smooth scrolling" setting to vertically glide through home catalog lists with eased mouse-wheel animation rather than rigid, immediate jumps.

- **Detail page Discovery Badges** - added a toggle settings switch under the Meta settings page to hide or show award, festival, and critic badges at the top of media information pages.

### Improved

- **Rich Discord Rich Presence** - enhanced the Discord Rich Presence integration to display media artwork (TMDB posters) directly in the activity state, render a progress duration indicator (e.g. `24:15 / 45:00`), and set the activity type properly to "Watching" with media titles mapped to the asset tooltips.

- **Unified AppData directory & migration** - unified application folders under a single Local AppData path (`NuvioHTPC` instead of `Nuvio`). An automatic migration script seamlessly moves existing preferences, cache data, custom badges, and files from legacy paths (including roaming folders) to the new unified path on startup. This is intended to prevent clashes with the official version or any other forks that could exist at some point.

- **Scrobble-stop CW sync** - added a short delay after playback stops before refreshing remote Continue Watching catalogs, giving SIMKL and Trakt servers a grace period to process the scrobble-stop before the local list is refreshed.

- **Adaptive Hero layout details** - hid basic paging indicator dots in Adaptive, Ambient, and TV modes where a sliding carousel is not used.

## 1.9.1 - 2026-07-09

### Added

- **Custom shader library / external MPV shaders** - added a Shader Library configuration under Playback Settings to register custom `.glsl` or `.hook` shader files/directories. Once configured, custom shaders can be selected as the Anime Enhancement mode and cycled in-player using the `F10` hotkey.

- **Random episode rewatch mode** - added a Random Episode action to series detail pages for comfort shows where sequence does not matter. Random rewatches start from the beginning, skip progress/scrobbling, avoid specials by default, and keep autoplay picking random main-season episodes instead of the next sequential one.

### Improved

- **Atomic preferences and cache storage** - shifted preferences and cache persistence to write to a temporary sibling file and atomically swap it in. This prevents mid-write app crashes from corrupting settings or clearing large cached files like MDBList/cast metadata.

- **Next-episode autoplay reliability** - forced a fresh fetch when loading autoplay streams, preventing binge mode from stalling on stale/empty cached results. Added a dedicated `BingeAdvance` logging pipeline and window-state listeners to diagnose background compose recomposition pauses during minimization.

- **Persistent player volume** - player volume now carries over cleanly between episodes within the same viewing session, resolving the issue where fresh player instances would default to 100% volume and cause audio spikes or display incorrect volume levels in the overlay.

- **Autoplay delay & manual trailers** - reshaped hero trailer playback options to control delay and autoplay under a unified setting. Setting the delay to "Manual" (0 seconds) disables autoplay while keeping manual trailer playback active (using the `T` key) in hero or full-screen modes.

- **Adaptive hero fallback backdrops** - when no hero catalogs are selected, Adaptive Hero and ambient backdrops now seed their content from the top browsing rows. This guarantees the background artwork adjusts to focused items instead of remaining blank.

- **Cleaner detail metadata layout** - blank and non-positive runtime values are now fully filtered out of detail-page metadata, preventing orphaned bullet separators from showing up next to missing values.

### Fixed

- **Metadata cache bloat** - implemented active cleanup of expired entries and legacy key schemes in MDBList ratings and cast caches, preventing database files from growing unbounded.

## 1.9.0 - 2026-07-07

### Added

- **New desktop Settings panel** - Settings now has a proper desktop layout with a persistent category sidebar, top-bar navigation back to Home/Search/Library, active-profile access, a right-side favorites panel, and quicker access to update notes. It should feel much less like a stretched mobile settings list and more like something built for a PC.

- **Settings favorites and sidebar reordering** - right-click a settings heading to pin it to the desktop favorites panel, then jump back to it later without digging through pages. The desktop category list can also be reordered with a deliberate long-press drag, and both favorites and ordering are saved per profile.

- **Keyboard Shortcuts settings** - Settings now includes a dedicated Keyboard Shortcuts page with a reference for navigation and player controls. Rebindable player actions can be changed from there, reset individually or all at once, and stored per profile while fixed keys like arrows, `K`, and `Tab` keep their normal fallback behavior.

- **WASD navigation / TKL mode** - added an optional keyboard-navigation mode for compact keyboards without arrow clusters. When enabled, W/A/S/D move focus through browsing screens and Search moves from `S` to `Q`, while text fields still type normally.

- **Custom theme accent color** - Appearance now has a Custom theme option with a native desktop color picker and saved custom palette values, so the app accent no longer has to come from the fixed theme list.

- **Desktop Addons and Plugins managers** - Addons and Plugins now get denser desktop-specific management views with search, filtering, inline add/install flows, and easier enable/disable/configure/remove actions. Collections was not changed, it's quite a bit of work and I think most people use the website anyway. 

- **Experimental audio passthrough (bitstream)** - added an option in Playback settings to send compressed audio formats (AC3, DTS, E-AC3, TrueHD, DTS-HD) untouched to an AV receiver over HDMI or S-PDIF (desktop only). This needs user testing, I don't have a reciever to verify it works.

- **Detail trailer background modes ("lights out")** - added "Black (lights out)" and "Backdrop" background mode choices for detail-page hero trailers. "Black" fills the screen surround with a flat black background to eliminate distracting themes or gradients, while "Backdrop" dims the artwork to a dark wash.

- **Detail trailer keyboard controls** - added shortcuts for active detail-page trailers: `M` to toggle mute, `[` / `]` to adjust volume, and `P` to toggle the cast/crew People Panel.

### Improved

- **Settings organization** - Playback, Appearance, Home Layout, Meta Screen, Streams, Integrations, Poster Card Style, Continue Watching, Collections, Addons, and Plugins have been reshuffled into cleaner pages with more inline controls and fewer extra bottom sheets where desktop did not need them.

- **Settings search and scrolling** - settings search uses shorter labels, new anchors, and better page routing for the reorganized layout, so deep links land closer to the actual control you searched for.

- **Desktop navigation options** - desktop navigation layout is now a direct choice row in Appearance instead of a separate sheet, and the new settings top bar can show column guides while tuning the desktop settings layout.

- **SDR/HDR color-profile clarity** - Color Profile is now labeled as an SDR color profile, and HDR playback leaves SDR-only color grades neutral unless the video is being tonemapped down to SDR. This avoids crushing HDR shadows with SDR-tuned brightness/contrast offsets.

- **Poster card controls** - Poster Card Style controls are now available directly from Appearance with a reset action, making poster sizing and shape adjustments easier to reach.

- **Profile-scoped settings state** - settings favorites, sidebar order, custom theme values, and player shortcut bindings now reload cleanly when switching profiles.

- **Detail page keyboard focus reclaim** - focus is now automatically reclaimed back to the details screen when a preview trailer finishes or is dismissed, preventing keyboard input from getting lost.

### Fixed

- **External player** - fixed external media players (like VLC, PotPlayer MPC, and MPV) on Windows. The app now queries the registry (`App Paths`) to find player installation paths and launches them in a detached state. This prevents players from displaying black screens or freezing when their OS stdout/stderr pipe buffers fill up.

- **Metadata enrichment races on Search/Library heroes** - suppressed catalog art and metadata transiently on Search and Library hero items until TMDB/TVDB enrichment completes, preventing incorrect or flashing art at startup.

- **Home screen trailer keyboard controls** - added `M` to toggle mute and `[` / `]` to adjust volume for home hero trailers, matching the detail screen behavior and preventing focus issues.

- **Hero trailer keyboard focus stealing** - Home hero trailers no longer install a second global keyboard dispatcher or keep OS focus after interacting with the native video/chrome. This fixes navigation keys disappearing or getting stuck after actions like muting a trailer and then pressing arrows.

- **Hero trailer mute/volume chrome on Home** - the Home hero trailer overlay now handles stop, mute, and volume as trailer-specific controls, keeps the shared trailer audio state in sync, and reflects programmatic volume changes on the overlay slider.

- **HDR playback getting unintended SDR color grading** - HDR passthrough content no longer receives Cinematic/Vivid SDR equalizer offsets, which could make HDR video look too dark or over-processed.

- **Debrid wording** - "Connected Services" labels that specifically meant debrid/cloud-library services now say "Debrid Services", so the Integrations area is less ambiguous.

- **SVP Crash** - if a path was set to another SVP installation this caused an immediate crash, this should be fixed now by ignoring any paths set and forcing the bundled defaults.

- **Misc bugs** - various bugs have been fixed and forgotten.

## 1.8.0 - 2026-07-06

### Added

- **New desktop media info screen** - details pages now have a proper desktop/HTPC layout instead of stretching the mobile-style sections into a big window. The hero area can carry the overview, action buttons, cast, production credits, media details, seasons/episodes, trailers, collection items, and more-like-this rows in a denser fixed-height view, with keyboard/remote navigation across the whole screen.

- **Detail-page hero trailer controls** - metadata screens can now play trailers directly in the hero area or full screen, with Settings controls for playback area, auto-play delay/manual mode, and whether new trailers start with sound. Manual trailer clicks also use the faster hero trailer surface on desktop instead of booting the full player just to preview a trailer.

- **Collections in the Home hero rotation** - collections now use their backdrops properly instead of remaining on the default hero backdrop, assuming the creator created backdrops for them.

- **Hero trailer volume chrome** - hero trailers now get in-overlay stop, mute, and volume controls, and the chosen trailer volume is shared between Home and detail-page trailer previews.

### Improved

- **Trailer failure messages** - when YouTube says a trailer is region-blocked, age-gated, removed, or private, Nuvio now shows that specific reason instead of a generic "No playable trailer stream found" retry loop. Unknown/transient failures still offer retry.

- **Home catalog compatibility** - addon catalogs that explicitly set `showInHome: false` are now respected, so collection-only or utility catalogs do not clutter the Home catalog list.

- **Collection and Home ambient backdrops** - the blurred ambient backdrop renderer is now shared and downsampled before blur, which keeps the same look while avoiding the heavy full-resolution blur cost on 2K/4K desktop displays.

- **Desktop startup path** - profile startup work is split into critical and deferred phases, native player bridge loading is warmed off the UI thread, and startup fullscreen waits for the real window before applying native chrome. Opening the app should feel less prone to early stalls or weird first-window placement.

- **Hero trailer audio** - trailer previews now use audio normalization to smooth out wildly inconsistent YouTube trailer loudness, and Home/detail previews avoid heavy SVP/VapourSynth processing that does not belong on short preview videos.

### Fixed

- **Slow stream-to-stream player handoff** - replacing an active native player no longer waits inline for the outgoing mpv/WebView instance to finish disposing, avoiding multi-second delays before the next selected stream attaches.

- **Series episode detection when addons omit season fields** - episode sorting, primary play/resume labels, future-season filtering, and next-episode resolution can now infer `SxxExx` / `1x02` style numbers from titles or IDs when explicit season/episode fields are missing.

- **Continue Watching artwork after startup** - watch-progress metadata resolution now gives addons a brief startup grace period before filling missing posters/backdrops, reducing the chance of blank or low-quality Continue Watching art from racing ahead of metadata providers.

- **Home hero trailer keyboard focus** - scrolling a default Home hero trailer out of view can no longer leave the native video surface holding OS focus and swallowing keyboard input afterward.

- **Collection entry hover jump** - opening an adaptive collection screen no longer lets the stationary mouse cursor immediately snap focus to whichever row happens to appear under it during the screen transition.

## 1.7.4 - 2026-07-04

### Added

- **Richer Discord Rich Presence states** - Discord now shows what you're doing outside active playback too: browsing Nuvio, searching, viewing Library, opening detail pages, browsing catalogs, choosing a stream, and starting a stream. Actual playback still takes priority once the video is running, including the existing title/episode/timeline behavior.

- **Interface Renderer setting** - desktop now exposes a restart-required renderer picker for the Compose UI backend. OpenGL remains the default, with Direct3D 11 available as a compatibility option for systems where OpenGL causes fullscreen or driver weirdness.

### Improved

- **Settings search and Fork Enhancements shortcuts** - more fork-specific settings are now searchable and deep-link directly to the right control, including hero badge options, adaptive hero positioning, trailer delay, anime auto/SVP toggles, Discord Rich Presence, poster size, TVDB/SIMKL attribution, and the new interface renderer setting.

- **Direct autoplay stream selection** - instant/unlimited autoplay can now react as addon, plugin, and debrid results arrive instead of waiting for slower providers to finish. Persisted binge-group matches still get first chance before the timeout behavior opens up to the normal fallback selection.

- **Profile switcher overflow** - the profile switcher popup can now scroll horizontally when there are enough profiles to overflow the available space.

- **TMDB trending badge lookup** - trending checks now consider both movie and TV trending lists for ambiguous/anime entries, load the first two TMDB pages per media type, and retry sooner after partial refresh failures instead of treating an empty side as fresh for hours.

- **Updated desktop app icon** - refreshed the bundled Windows/macOS/Linux app icon assets used by the packaged desktop app. Be warned that Windows caches this, to get the new icon un-pin from taskbar and either restart Windows explorer or restart the computer. It gets rid of the random black background and just uses the Nuvio logo with a transparent background.

### Fixed

- **TV Mode collection poster sizing** - collection folder shelves now use the same fill-the-shelf sizing logic as Home TV Mode, so synced/mobile poster label preferences no longer shrink collection posters unexpectedly.

- **Discord Rich Presence blank gap while playback starts** - after picking a stream, Discord now shows "Starting stream" until the player reports real playback, instead of briefly clearing presence between stream selection and playback.

- **SVP/player timing cleanup** - speed changes now leave buffer preset scaling to the desktop controller instead of the native bridge fighting the selected preset, and returning from SVP restores mpv's motion-compensation value correctly.

- **Signed-out cached profile startup** - cached profiles no longer silently bypass the sign-in gate while the app is online and auth has failed/expired. Offline cached access still works, and users already inside the app are not kicked out mid-session just because auth is resolving.

- **Watch progress sync without a real Nuvio session** - local watch-progress pushes now skip Nuvio Sync when there is no authenticated non-anonymous session, avoiding noisy unauthorized sync attempts.

## 1.7.3 - 2026-07-03

### Added

- **Per-season anime backdrops for Kitsu catalogs** - a Kitsu search result for a sequel season (SAO II, Alicization, etc.) used to show the exact same backdrop as season 1, because TMDB/TVDB treat the whole franchise as one show/ID. Sequels, specials, and split-cour parts now pull their own season-specific art from Kitsu (falling back to AniList if Kitsu has nothing), while season-1 entries and anime movies keep the faster TMDB/addon art path since it's already correct for them. No API key required for this.

- **TMDB now defaults to "TMDB for everything" for hero art when you add a key** - entering a TMDB API key (Settings or the onboarding popup) now turns on TMDB enrichment and sets Hero Backdrop & Logo to TMDB for everything automatically, if you haven't already chosen a source yourself. Most metadata/search addons (and Trakt in particular) aren't built to be artwork providers and return no backdrop at all, falling back to a stretched poster - TMDB is consistently better once you have a key, so it's the sensible default now instead of something you had to find and flip yourself. Note this setting only affects Search and Library hero art - the Home page always uses your addon's own images, which is also now spelled out directly in Settings.

### Fixed

- **Kitsu catalogs picking the wrong season/episode entirely** - each Kitsu search result is actually one season of a franchise (SAO, SAO II, Alicization, War of Underworld... are all separate Kitsu IDs sharing one TVDB show), but opening one and picking a different season played, scrobbled, and labeled everything under the season you originally opened. Season 2/3 picks looked like they worked but silently mislabeled themselves too (S1E1 under a season-2 result showed as "S02E01" and scrobbled as season 2 to both Trakt and SIMKL). Nuvio now recognizes when you're navigating across a franchise's seasons and routes streams, scrobbles, and on-screen episode titles to the correct season's real entry - all the way through.

- **SIMKL Continue Watching showing completely wrong thumbnails/titles for anime movies, and failing to resume** - SIMKL sometimes reports anime movies without labeling them as anime, so Nuvio trusted SIMKL's (unreliable, for anime) IMDb ID and could resolve a totally unrelated title's art and metadata - for one user this showed a Jeff Foxworthy stand-up special as the thumbnail for a Sword Art Online movie. Resuming pulled nonsense streams for the same reason. Fixed by cross-checking against the bundled anime ID database instead of trusting SIMKL's own labeling, and by no longer gating title/poster lookup on that label.

- **"No metadata available" on some anime movies from a fresh app start** - fallout from the SIMKL fix above: title/poster were only read from SIMKL's dedicated anime payload field, which doesn't exist when SIMKL reports the movie under its plain movie listing. Session caching was masking this until a full restart. Now reads whichever field SIMKL actually provided.

- **Home page force-refreshing every catalog from every addon on every visit** - a Cinemeta compatibility fix landed with an unconditional full refresh that fired every time you returned to Home, discarding the whole session cache each time. Now only force-refreshes when your catalog list actually changed (addon installed/removed, etc.) - normal navigation is back to using the cache and feels noticeably snappier.

- **Cinemeta's genre/year catalogs (Popular by Genre, etc.) not showing up on Home at all** - catalogs whose only required filter is a genre selection were being skipped entirely rather than defaulting to a genre. These, along with other addons needing similar required filters, now load using their first available option and show up as normal rows.

- **Blank or ugly hero art on the Home page for catalogs that don't supply a real backdrop (public domain movie catalogs and similar)** - catalog-only addons like Cinemeta often return a poster but no proper wide backdrop for these, which used to just show blank or a stretched poster on the Home hero. Nuvio now backfills a real backdrop and logo for these specifically (via TMDB and Metahub), targeted narrowly at titles actually missing one - everything else on Home still gets its art straight from your addon as always. This is separate from the Hero Backdrop & Logo setting above, which only touches Search and Library.

- **Occasional crash parsing catalog metadata from addons that return unexpected JSON shapes** - some fields (genres in particular) could arrive as something other than a plain value depending on the addon, which crashed the parse instead of just skipping that field.

### Improved

- **Reduced unnecessary API calls for anime scrobbling and Kitsu ID lookups** - SIMKL's anime-ID enrichment (Kitsu/MAL ID lookup) is now cached instead of repeating the same lookup on every scrobble start and stop, and the anime ID mapping database (used constantly for Kitsu catalogs and dual-scrobble) is now parsed once in the background at launch instead of on your first stream/episode click, so that first click doesn't stall.

## 1.7.2 - 2026-07-01

### Added

- **Discord Rich Presence** - optional Windows desktop integration that shows what you're currently watching on your Discord profile, including title, episode label, paused/playing state, and the remaining-time timeline when available. Uses the bundled Nuvio HTPC Discord application identity, so users only need to turn it on from Settings -> Integrations.

### Improved

- **SVP** - SVP now actually works, I had a bunch of babble here about everything that changed but it doesn't really matter. I managed to convince myself it was working previously through I don't know, jedi mind tricks?

- **Cut Release by ~100MB** - I was bundling way more dlls than required, even adding a bunch of stuff for SVP it was still possible to cut it down. It's still larger than the official desktop but that's to be expected, SVP is adding quite a bit.

### Fixed

- **SVP with faster playback speeds** - SVP interpolation now automatically backs out at high playback speeds and restores when speed returns closer to normal, avoiding the severe lag/audio desync path seen around 2x playback.

- **Windows native player runtime loading** - hardened bundled libmpv loading so the app doesn't fall back to unrelated MSYS2 DLLs on developer machines, added retries for transient DLL load failures, and replaced crashing unused whisper/ggml runtime dependencies with no-op stubs so playback can initialize reliably.

- **Anime enhancement hotkeys not always taking effect** - F10/F7 now force the anime enhancement/SVP choice for the current playback session even when the title was not auto-detected as anime yet.

- **Borderless fullscreen sometimes leaving the video surface black until resize** - toggling fullscreen now forces a redraw after the native window transition settles.

- **Early player exit could mark an item as ended** - suppressed mpv's transient startup EOF signal so backing out during the first moments of playback does not incorrectly mark the title complete or remove it from Continue Watching.

## 1.7.1 - 2026-07-01

### Fixed

- **Sync/login broken after Nuvio's backend migration** - Nuvio switched their account/sync backend recently. This build now points at the new one, so signing in and syncing works again. If you were stuck logged out, update and sign back in.

- **Account page showing "Sign Out" while already signed out** - the button wasn't checking your actual sign-in state, so it always showed "Sign Out" even when you weren't signed in. Now shows "Sign In" correctly, and it actually works.

- **Signing out wiped ALL local desktop settings, not just account data** - this was the nasty one behind the above bug: signing out deleted the entire local settings folder, including everything that has nothing to do with your Nuvio account - playback settings (HDR, color profile, buffer presets, volume boost, anime enhancements), Adaptive Hero/TV Mode settings, and your TVDB/SIMKL connections. None of that syncs to your account in the first place, so wiping it just forced you to redo it all for nothing. Sign-out now only clears the stuff that's actually tied to your account.

- **MPV playback (and hero trailers) not filling the screen at certain desktop scaling percentages** - a side effect of last version's desktop viewport scaling fix: the native video surface was inheriting the same "make UI look bigger" density meant for buttons/text, so it ended up sized as a fraction of the real window instead of the real screen. Fixed - video now always fills the space it's given, independent of that scaling.

- **ASS/SSA subtitles** - subtitle styling was forcing *every* subtitle track, including ASS/SSA, through the plain color/font/size settings, discarding the file's own positioning, layout, and any animation effects entirely. For real ASS content that could mean broken positioning or effects rendering as a flat static frame instead of the file's actual styling. Detected automatically now - plain-text formats (SRT/VTT) still get your style settings applied as before, but ASS/SSA is left completely alone and rendered exactly as authored, no exceptions. Removed the "Use libass" toggle and render-mode picker from desktop's subtitle settings - that only ever controlled Android's separate WASM subtitle renderer and had zero effect here, which was actively misleading since desktop always renders ASS/SSA natively via mpv now with no user-facing setting needed. If a track is ASS/SSA, it's handled correctly automatically - there's nothing to configure.

- **Hero trailers not working in Search, Library, or Collections** - autoplay and the manual `T` shortcut were both silently gated off outside the Home tab, and Collections in particular had no keyboard handling wired up at all for a trailer's native surface. Fixed across all of them.

- **Keyboard navigation snapping the hero back to an old position while a trailer played** - pressing an arrow key mid-trailer could jump focus back to wherever it was when the trailer started, discarding any mouse-wheel/hover navigation that happened while it played. Fixed.

- **Collection adaptive hero freezing after the 18th tile** - hovering tiles past the row's preview cap stopped updating the hero backdrop and got stuck on the first tile. Fixed.

- **Hero badge placement not applying inside Collections** - your configured badge position (e.g. top-right vertical) was ignored there and always fell back to the bottom-of-backdrop default. Fixed.

- **Blank/black hero backdrop for addons that don't supply a banner image** - added a poster fallback for the hero backdrop so it can't render nothing if an addon doesn't supply one. Couldn't reproduce the original report on retest (Cinemeta and others rendered backdrops fine), so this is a defensive fix for the gap rather than a confirmed root cause - let me know if you still see a black backdrop anywhere.

### Improved

- **TV Mode now overrides settings that don't work with it** - Poster size, landscape poster mode, continue-watching card style, hide labels, and hide catalog underline all get forced to sensible fixed values while TV Mode is active (extra-large continue-watching cards, landscape off, "Card" style, labels always shown, underline always hidden), since combining them with TV Mode's fixed-size shelf caused broken layouts. Your actual saved preferences aren't touched - they reapply exactly as you left them the moment you turn TV Mode back off.

## 1.7.0 - 2026-07-01

### Added

Note: hero info badges feature requires Mdblist integration enabled for some of the badges, though not all. The API call to Mdblist is still only one, it pulls the keywords at the same time as it gets ratings so you're not spending double the API hits. Mdblist is used for some award data, while other awards are hardcoded and don't use API. Cult classic, true story also use it. You'll lose about ~25% of the badges without Mdblist. 

- **Hero info badges** - the hero can now show small contextual badges for awards, festivals, critic signals, release status, language, trending/cult/true-story metadata, notable studios/directors, short films, mini series, binge-ready shows, and new releases. These can appear at the bottom of the backdrop or as a horiziontal/vertical stack in the top right. If you hover an icon with the mouse, you'll get some information about what the badge is for. 

- **Customizable hero badge settings** - Settings now includes controls for enabling hero badges, badge placement, badge size, badge priority order, and whether release-status badges should only appear when something may be unavailable to watch.

- **Custom hero discovery config** - the app can load a user `hero_discovery.json` so notable studios and directors can be extended without touching the code. It can also load your own custom badges to replace the defaults shipped. Place badges or json at: %AppDataLocal%\Nuvio\badges <-- create it first. See readme for complete instructions.

- **Fribb anime ID mapping** - added a bundled anime mapping database based on Fribb's anime lists, letting Nuvio translate between AniDB, AniList, Kitsu, MyAnimeList, SIMKL, IMDb, TMDB, and TVDB IDs. This means that Kitsu catalogs should work properly, as well as dual scrobble between Trakt and SIMKL for anime (as well as live action.) Let me know if you have any issues here.

- **Infinite scrolling in collections** - collection row views now use the same load-more behavior as home catalog rows instead of stopping at a small preview with a View All button.

- **Manual adaptive hero backdrop position** - simple "backdrop vertical position" slider in Homescreen settings instead, so you can tune it once to whatever looks best for your library and it'll stay put.

- **API key setup prompt** - if you haven't set a TMDB or Mdblist API key, you'll now get a one-time popup on launch explaining what you're missing (lower quality backdrops/logos, missing ratings and info badges) with fields to add them right there, saved to the same place as the normal settings. You can dismiss it for the session or permanently.

### Improved

- **Hero ambient background across catalog modes** - the ambient blurred hero wash now works in catalog-style modes instead of only the main home page.

- **Desktop viewport scaling** - large desktop displays now scale the UI density against a 1920x1080 reference, making HTPC/high-resolution layouts read more naturally.

- **Fewer redundant API calls** - hero discovery was sometimes hitting TMDB twice for the same movie's release info, and a couple of other spots were re-fetching/re-parsing data on every single item instead of caching it. Home should feel a little lighter now, especially on TMDB rate limits.

### Fixed

- **Anime episode identity mapping** - episode IDs can now be rewritten with mapped season numbers and episode offsets, improving stream lookup and progress tracking for anime entries that use different numbering across services.

- **SIMKL scrobble overlap handling** - SIMKL scrobbling now handles duplicate/overlap responses more gracefully, including SIMKL's short per-user scrobble lock window.

- **Wrong backdrop/logo/cast on a handful of titles** - Added a title/year sanity check before trusting a TMDB match.

- **Auto-advance to the next episode could silently stop working** - a few edge cases (next episode not found in a stale list, a resolved stream going stale) could leave auto-advance permanently disabled for the rest of the session with no error shown. Fixed, plus added a safety net so it can no longer get stuck for good.

- **Hero title jumping position on titles with no logo** - titles that fall back to plain text (no logo artwork) rendered in a different vertical spot than titles with a logo, making everything below the title jump around when scrolling the hero. Fixed.

- **"TMDB (movies) + TheTVDB" hero image toggle staying checked (but greyed out) after removing your TVDB key** - it now automatically falls back to a working option instead of sitting in a confusing half-state.

- **Trakt episode remapping ignoring the playing video's ID** - when an addon's season/episode numbers didn't line up with Trakt's, a bad guard was forcing the remapper to trust the (potentially wrong) season/episode instead of the video ID it was actually given, defeating the point of the video-ID lookup. Fixed.

## 1.6.0 - 2026-06-25

### Added

- **Search as a home-mode screen** - the Search tab now uses the full TV Mode / Adaptive Hero layout instead of the previous flat list. A search field appears in the navigation bar when Search is active; results populate the hero carousel and catalog rows. The home page remains fully visible while the query field is empty - no content changes until a search is submitted. 
- **Library as a home-mode screen** - the Library tab now uses the same TV Mode / Adaptive Hero layout as the home page.
- **hero image source setting** (Settings → TMDB → Hero Backdrop & Logo). Three options:
  - **Addon (default)** - images come from whatever your addons provide.
  - **TMDB for everything** - backdrops and logos fetched directly from TMDB at original quality for all content. Requires a TMDB API key.
  - **TMDB (movies) + TheTVDB (TV & anime)** - TMDB for movies, TheTVDB v4 for TV series and anime backdrops and clearlogos. Requires both a TMDB API key and a TheTVDB API key (obtainable free at thetvdb.com/api-information). TheTVDB images are selected using the same algorithm as AIOMetadata: language-neutral (`lang=null`) backdrops are preferred to avoid artwork with overlaid text.
- **TheTVDB API key field** (Settings → TMDB → TheTVDB API Key) for the TMDB + TheTVDB image mode above.
- **SVP (SmoothVideo Project) support for anime** - when SVP is active, frame interpolation is now applied to anime content alongside the existing Anime4K shader pipeline.

### Improved

- **Navigation bar** - redesigned as a compact frosted-glass pill with four equal icon-only quadrants (Home, Search, Library, Profile). Every quadrant is fully clickable with a clear active state. Activating Search crossfades the quadrants into an inline search field of the same shape and size, keeping the whole experience cohesive. The design feels more native to a PC/HTPC context than the previous mobile-style tab bar.
- **Starring section** - actors who are also credited as creator or producer (e.g. Steve Carell in *The Office US*) are no longer incorrectly filtered from the cast list. Only people who appear exclusively in crew roles are hidden.
- **Misc improvements** - various improvements across the board, I didn't keep a log of everything honestly.

### Fixed

- **Anime scrobbling** - watch progress and completion events for anime titles were not being reliably reported to Trakt and SIMKL. Improvements have been made, however SIMKL expects anime specific IDs while Trakt doesn't support them. You should use the IDs matched with the service that's most important to you until I can find a way to easily convert on the fly from Kitsu back to TVDB for example.
- **Hotkeys not working in Library mode** - `C` (calendar), `H` (home), and other keyboard shortcuts were silently ignored because the content area never received keyboard focus when Library mode opened.
- **Switching to Search scrolling the home page to the top** - the visible content and scroll position are now fully preserved when entering Search mode.
- **Misc fixes** - switching tabs no longer changes any visible content until a search query produces results or the Library data is ready.

### Attribution

- Added **TheTVDB** to Settings → Licenses & Attribution. TheTVDB requires attribution for free-tier API access: *"This product uses the TVDB API but is not endorsed or certified by TVDB."*
- Added **SIMKL** to Settings → Licenses & Attribution.

## 1.5.0 - 2026-06-23

### Added

- Added **SIMKL integration** - connect via Settings → SIMKL using a PIN code (no password required). Once connected, SIMKL can replace or supplement Trakt across the following areas:
  - **Scrobbling** - watch progress is reported to SIMKL in real time alongside Trakt. Both services receive start/stop events simultaneously.
  - **Library** - enable "Use SIMKL as Library" to show your SIMKL watchlist (plan-to-watch) as the app library, split into My Shows, My Movies, and My Anime sections.
  - **Continue Watching** - enable "Use SIMKL for Continue Watching" to drive the CW section from SIMKL playback sessions. In-progress items and up-next suggestions both come from SIMKL, with images fetched from your installed addon providers. Items can be dismissed (calls `DELETE /sync/playback/{id}`). A configurable day cap (14 / 30 / 60 / 90 / 180 / 365 days, or all time) prevents old watches from flooding the list. CW entries are sorted most-recently-watched first.
  - **Calendar** - enable "Use SIMKL for Calendar" to populate the existing calendar screen with upcoming episodes from shows you follow on SIMKL. Fetches episode schedules directly from each show you are watching or plan to watch, so smaller shows that don't appear in the global CDN calendar are still included.
- Added **standard Anime4K presets** - Mode A, Mode B, and Mode C (Fast and HQ variants each), ported from the official [bloc97/Anime4K](https://github.com/bloc97/Anime4K) v4.0.1 preset chains. These join the existing Stremio-Kai presets (Optimized / Fast / HQ) in the F10 cycle and Fork Enhancements settings. Mode A is best for blurry/compressed sources, Mode B for already-clean sources, Mode C for heavily noisy or compressed sources.
- Added **auto-apply toggle** for Anime4K - the "Auto-apply to Anime" switch now appears below the preset picker whenever a preset other than Off is selected. When on, the chosen preset is applied only when the title is detected as anime; when off, the preset is always active regardless of content. Previously this behaviour was buried inside the single "Auto" mode with no way to choose a specific preset for auto-detection. Existing "Auto" users are automatically migrated to Optimized + auto-apply enabled.
- Added **local file drag-and-drop** - drag a video file from Explorer and drop it onto the Nuvio window to open it in the player immediately. Supports mp4, mkv, avi, mov, wmv, flv, webm, m4v, mpg, mpeg, ts, m2ts, mts, vob, ogv, 3gp, rm, rmvb, and more. Progress tracking and scrobbling are disabled for local files as we don't have the IDs.
- Added **NVIDIA RTX Video True HDR** (Settings → Playback → NVIDIA RTX VIDEO). When enabled, the player's NVIDIA video processor converts SDR content to HDR in real time using AI-based tone-mapping. Requires an RTX GPU and Windows HDR enabled in Display Settings. Off by default. HDR and Super Resolution can be active simultaneously. Requires a libmpv build from February 2026 or later - this release bundles an updated libmpv (master, June 2026) that includes the required `IMGFMT_X2BGR10` output format and `ID3D11VideoContext1` colour-space interface. Previous releases could not implement this feature: mpv 0.41 accepted the `nvidia-true-hdr` option but the VP kept an 8-bit output surface so the driver never engaged.

### Fixed

- Fixed **AVI files playing with sound but no video**. The player was attempting D3D11VA hardware decoding for all codecs (`hwdec-codecs=all`) while also disabling the software fallback (`vd-lavc-software-fallback=no`). For AVI containers - which often carry codecs D3D11VA doesn't support (MPEG-4 Visual / DivX, or H.264-in-AVI with non-standard header extradata) - hardware decoding would silently fail with no fallback, killing the video track while audio continued. `hwdec-codecs` is now restricted to codecs D3D11VA actually supports on Windows, and the no-fallback restriction is removed entirely.
- Fixed **continue watching tracking stopping after pause/resume**. When the generation check inside `emitTraktScrobbleStart`'s async path failed (due to a concurrent state change), it exited without resetting `hasRequestedScrobbleStartForCurrentItem`, permanently blocking all subsequent start events for that session.
- Fixed **final episode progress not reaching scrobble services**. When the 80% completion threshold fired mid-episode, `hasSentCompletionScrobbleForCurrentItem` blocked the video-end stop from sending - leaving services showing "5 minutes remaining" instead of complete. The final stop now always fires at ≥99% progress.

## 1.4.0 - 2026-06-22

### Added

- Added a **Trakt Calendar** (press `C` from the home screen, or the calendar icon next to "Trakt Library"). Shows all your upcoming and recent show airings and movie releases in a month-view poster grid. Arrow keys move between day cells; `Shift+Left/Right` change months; `Enter` opens the day's airing list; navigating to any title works from there. Month position is preserved when you return from a detail screen. (Unique to this fork.)
- Added **NVIDIA RTX Video Super Resolution** on Windows (Settings → Playback → NVIDIA RTX VIDEO). When enabled, the player uses NVIDIA's AI super-resolution scaler to upscale lower-resolution video toward your display's native resolution - the exact scale ratio is computed per file and applied as a `d3d11vpp` video filter. Requires an RTX GPU. Off by default. (Ported from upstream and adapted to integrate with this fork's Anime4K shader pipeline - VSR and Anime4K are mutually exclusive; Anime4K takes priority when active.)

### Fixed

- Fixed addon URLs containing special characters (e.g. `|` in Torrentio debrid configs, spaces, backslashes) causing broken streams or failed manifest loads. Addon and resource URLs are now percent-encoded at the correct boundary before sending. (Port of upstream fix `6dac9b2`.)

## 1.3.0 - 2026-06-21

### Added

- Added pagination to home catalog rows. Scrolling toward the end now loads the next page automatically, allowing mouse, trackpad, and TV/D-pad users to browse the full depth of supported catalogs instead of stopping at 18 items.

## 1.2.0 - 2026-06-21

### Added

- Added anime enhancements ported from Stremio Kai (with the Kai developer's blessing): Anime4K GLSL shaders ([bloc97/Anime4K](https://github.com/bloc97/Anime4K), MIT) plus anime-tuned scaling and debanding. They auto-apply to titles detected as anime by genre and can be cycled in the player with F10 (Auto/Off/Optimized/Fast/HQ) or set on the Fork Enhancements settings page. 
- Added volume boost up to 200% on the desktop player so quiet content can be amplified above 100%; press Up past 100% and the volume pill shows the boosted percentage.
- Added desktop buffer presets (Low Data, Balanced, Resilient) to tune how far ahead playback caches for your connection. Resilient is the least likely to buffer on network hiccups, while low data is the most likely.
- Added a buffered indicator to the player seek bar: a lighter band shows how far ahead playback is currently cached, so the effect of the buffer presets is visible.
- Added a Tab hotkey to skip the intro/outro on the desktop player, matching the official client. It only acts while the skip prompt is on screen; otherwise Tab behaves normally.

### Changed

- The home hero no longer shows a hover highlight over its artwork/logo/title when it can be clicked to open the title.

### Fixed

- Fixed the whole app turning into a black screen when the back button was clicked rapidly while leaving the streams list (back presses during the exit transition could pop past the root screen, leaving an empty view).

## 1.1.1 - 2026-06-20

### Improved

- Improved Windows trailer quality for high-resolution displays by preferring high-bitrate unthrottled 1440p streams, then 2160p, 1080p, and the best available lower-quality fallback.

### Fixed

- Fixed profile settings sync being able to erase device-local Trakt client credentials, which could leave the Trakt library stuck on loading placeholders. Trakt credentials are no longer included in synced profile settings, and cached library content remains available during temporary credential failures.
- Fixed switching sources from the desktop player's Sources panel freezing playback or leaving the native player permanently disposed.

## 1.1.0 - 2026-06-20

### Added

- Added Auto Play Trailer for the Adaptive Hero and TV Mode home layouts: once an item stays focused for a short, configurable delay (selectable from 1 to 15 seconds), its trailer replaces the hero artwork. Trailers can optionally play with sound and full screen, and pressing `T` plays or dismisses the focused item's trailer on demand.
- Added a Fork Enhancements settings page that gathers this fork's HTPC-focused options - HDR mode, color profile, default playback speed, binge mode, extra-large posters, and home layout - into a single place.

### Improved

- Flattened the General settings list: Addons, Plugins, Home Layout, Detail Page, Continue Watching, and Collections are now top-level entries instead of being nested behind intermediate pages, so each is reachable in a single click.
- General settings entries are now listed alphabetically.

### Fixed

- Fixed the settings search field being hard to reach on desktop, where it was only revealed by an overscroll/pull gesture that is impractical with a mouse. On desktop it is now shown immediately.
- Fixed settings search not matching several Home Layout options (TV Mode, Adaptive Hero, Hero Ambient Background) by name; they are now indexed and findable.

## 1.0.3 - 2026-06-19

### Added

- Added direct desktop player shortcuts: `C` cycles Fit/Fill/Zoom, `[` and `]` adjust playback speed, `S` cycles subtitle tracks, and `A` cycles audio tracks.
- Added keyboard-first Sources (`O`) and Episodes (`E`) panels with focused list navigation and selection.

### Improved

- Player shortcuts now work consistently whether keyboard focus belongs to the desktop window or the embedded player controls, including the `F8` HDR and `F9` color-profile shortcuts.
- The Episodes panel now opens on the currently playing season and supports season changes with Left/Right.

### Fixed

- Fixed episode thumbnails being recreated and visibly reloaded during routine player-state updates.
- Fixed keyboard panel state becoming stale after closing a panel with the mouse.

## 1.0.2 - 2026-06-19

### Added

- Added full-screen in-app trailer playback on desktop. Trailer playback no longer affects Continue Watching, watch progress, next-episode autoplay, or Trakt scrobbling.
- Added desktop HDR handling modes: display-aware Auto, forced SDR Tonemap, and source-metadata Passthrough.
- Added Neutral, Cinematic, and Vivid desktop color profiles. Profiles can also be cycled during playback with `F9`; `F8` cycles HDR modes.
- Added an on-screen indicator when changing HDR or color presets with a keyboard shortcut.
- Added subtitle font selection and persistence. Desktop users can select from their installed system fonts directly in the native player subtitle controls.
- Added custom Library poster-service templates for services such as PostersPlus, RPDB, and self-hosted alternatives. Templates support `{imdb_id}`, `{tmdb_id}`, and `{type}` placeholders.
- Added persistent rotating desktop diagnostic logs under `%LOCALAPPDATA%\Nuvio\logs`. The active log is `nuvio.log`, with three rotated backups retained.
- Added update checks and downloads for this fork's Windows portable ZIP releases. Downloaded archives are revealed in Explorer with safe manual replacement instructions.

### Improved

- Improved Windows video rendering with updated HDR tone mapping, gamut mapping, scaling, dithering, and debanding configuration.
- Improved accelerated playback reliability by scaling the streaming cache and readahead window with playback speed.
- Improved YouTube trailer extraction by preferring unthrottled streams, targeting high-quality 1080p video, and avoiding unnecessarily expensive high-frame-rate streams.
- Added native support for separate video and audio URLs in the Windows player, improving adaptive YouTube stream playback.
- Improved seek behavior near the end of a video to avoid invalid end-of-file seeks.
- Subtitle style and video profile changes now redraw immediately while playback is paused.
- Improved series metadata resolution by retaining useful trailers and links from supplemental add-ons while continuing to search for complete episode metadata.
- Improved TV-mode details navigation, including season selection, row transitions, focus reset, and scroll positioning.
- Improved keyboard, wheel, and drag navigation for the cast row on details pages.
- Improved hero responsiveness by prioritizing the currently displayed item while retaining background metadata prefetching with bounded concurrency.

### Fixed

- Fixed heroes appearing completely blank when a banner or logo URL is unavailable. Failed banners now fall back to posters, and failed logos fall back to the title.
- Fixed excessive simultaneous hero metadata requests that could delay artwork on a cold start.
- Added diagnostic entries for failed hero banners, posters, and logos, with URL query strings removed from logs.
- Fixed IntroDB integration to use its current `/intro` endpoint and response format.
- Fixed desktop trailer playback being constrained to the small embedded popup.
- Fixed some separate YouTube audio/video streams stalling or playing without audio in the Windows native player.
- Fixed saved subtitle appearance and delay settings being dropped during player startup or subtitle-track loading.
- Fixed desktop Trakt authentication leaking across local profiles; existing primary-profile authentication is migrated automatically.
- Fixed subtitle add-ons that declare singular `subtitle` resources or use `tv` instead of `series` aliases.
