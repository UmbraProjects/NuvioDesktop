# Changelog

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
