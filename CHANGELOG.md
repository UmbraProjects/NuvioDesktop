# Changelog

## 1.1.1 - 2026-06-20

### Improved

- Improved Windows trailer quality for high-resolution displays by preferring high-bitrate unthrottled 1440p streams, then 2160p, 1080p, and the best available lower-quality fallback.

### Fixed

- Fixed profile settings sync being able to erase device-local Trakt client credentials, which could leave the Trakt library stuck on loading placeholders. Trakt credentials are no longer included in synced profile settings, and cached library content remains available during temporary credential failures.
- Fixed switching sources from the desktop player's Sources panel freezing playback or leaving the native player permanently disposed.

## 1.1.0 - 2026-06-20

### Added

- Added Auto Play Trailer for the Adaptive Hero and TV Mode home layouts: once an item stays focused for a short, configurable delay (selectable from 1 to 15 seconds), its trailer replaces the hero artwork. Trailers can optionally play with sound and full screen, and pressing `T` plays or dismisses the focused item's trailer on demand.
- Added a Fork Enhancements settings page that gathers this fork's HTPC-focused options — HDR mode, color profile, default playback speed, binge mode, extra-large posters, and home layout — into a single place.

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
