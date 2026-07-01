This update brings new badges on the hero for more information in choosing what you watch. This includes awards, release status, trending, cult, ability to highlight directors/studios and more. Most of this logic is ported from my poster project PostersPlus (also open source), check it out if you want this info available on other platforms. 

This update hopefully fixes anime ID/metadata issues forever by incorporating Fribb's anime-lists project to solve ID/metadata issues when attempting to use anime specific structures (such as Kitsu) with Trakt. If you spot any issues here, let me know.

There's a key fix to desktop scaling that I never realized was happening. If you thought "this doesn't look like the video for me, it's so small" this is the fix for that. Effectively I developed it at 200% desktop scaling and only tested resolution scaling, not desktop scaling so anyone with a setting of anything other than 200% didn't have the correct layout.

They're some other bits and bobs as well, mostly requests from users. If you have any, you can find me on Reddit, Discord ( PostersPlus discord works or AIOStreams) or open an issue/PR on GH but I'm more likely to see the others first. If anyone has ideas for the badge system that they'd like to see displayed, ideally it can be calculated from TMDB or Mdblist to avoid using any additional APIs.

The changelog covers as much as I remember but they're definitely alot of improvements I forgot about.

# Changelog

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
