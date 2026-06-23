# Changelog

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
