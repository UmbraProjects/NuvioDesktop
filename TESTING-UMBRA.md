# Testing checklist (windows-tv-adaptive)

What has been tried on the TV for this branch's own work, and what is left. Claude keeps this file; results are
recorded the same way as in `TESTING.md`:

- `- [x] check (date)` works, `- [ ] **BROKEN** check (date): what was said` does not, `- [~] check (date): ...` works
  with a follow-up. A fix for a broken or follow-up line adds a fresh `- [ ]` re-check under it.
- New checks for work on this branch go here, not in `TESTING.md`. `TESTING.md` came in with the merge of Spyder's
  branch and is Nick's checklist for his fork (phone remote, Tonight, music redesigns); leave it to him.
- At the start of a session, offer the open checks here as clickable questions.

## Logging build for the optimization audit (2026-11-13)

Nothing here changes how the app behaves; these runs fill the log (`Docs/plans/optimization-audit-2026-11.md`).
Perf logging must be on (an empty file named `perf-log` in the Nuvio data folder) for the first check.

- [x] One normal launch on the HTPC, left on Home for a minute, then closed (records where the launch freeze sits and what the first requests are) (2026-11-13): JVM and native both run; the freeze did not happen on either
- [~] An evening driven only by the controller; each time the arrows or OK stop doing anything, note the time and the screen before fixing it by revisiting the tab (2026-11-13): done with the keyboard; the dropped-keys bug hardly showed (it may need mouse and keyboard together), but these did:
  - [ ] **BROKEN** Search: open a result, go Back, press Right: an empty "Movies Search - Movies" catalog opens, and the arrows do nothing on it (Escape leaves)
    - [x] Re-check after the fix (2026-11-13): with Hide watched on, search for a film you have watched; Right past the row's last poster opens a page that lists it, and the arrows and Enter work on that page
  - [ ] **BROKEN** Discover, 00:47: going too far Right loses navigation; every arrow is then ignored, and hovering a poster does not bring it back (a shortcut to another tab and back does)
    - [x] Re-check after the fix (2026-11-13): on Discover's filter bar, Right stops at the last filter and Left at the first; Down still returns to the posters
  - [ ] **BROKEN** Artist page: the mouse scroll guard makes it impossible to go back up the page with the keyboard; the album page's order of stops is odd
    - [~] Re-check after the fix (2026-11-13): on an artist page's panels, Up from the top row goes back to the hero and stays there (2026-11-13): works, but it scrolls half way, stops, then scrolls again; not smooth like the wheel
      - [ ] Re-check: Up from the panels to the hero, and Down from the hero to the panels, is one smooth move like a wheel notch; Up and Down inside a panel's own list still scroll that list normally
    - [ ] Album page: say which stops are in the wrong order (not changed yet)
  - [ ] **BROKEN** Tonight: where Left means "never show me this again", the Back button cannot be reached with the keyboard; Escape works but is not shown as its key
    - [ ] Re-check after the fix (2026-11-13): the Back pill on every Tonight mode reads "Esc  Back"
  - [ ] **BROKEN** Music player panel, 01:02: B does not leave it and neither does Escape; only the mouse does (may be the same bug as the panel sometimes not minimising)
    - [~] Re-check after the fix (2026-11-13): with focus in the music bar, Escape, Backspace and the pad's B return to the page; in the bar's options panel (Shuffle, ...) they close it (2026-11-13): works, but closing with B feels slow, as if it waits out the mouse's grace period
      - [ ] Re-check: with the docked player opened by keyboard or pad, B / Escape folds it at once; with the mouse, slipping off its edge for a moment still does not fold it
  - [ ] **BROKEN** Player: the on-screen controls cannot be reached with the keyboard, only shortcuts; wanted: Enter (the pad's OK) to focus the bar and the arrows to move along it
    - [x] Re-check after the fix (2026-11-13): while a film plays, Enter shows the controls with a ring on Play; Left / Right walk the row, Up reaches the timeline (Left / Right seek there) and the clock above it, Enter presses, Escape leaves; Space still pauses
    - [x] Enter on Subtitles or Audio opens the panel with the ring inside it; the arrows walk it, Enter picks, Escape returns to the button
    - [x] Left alone for eight seconds, the ring goes and the controls hide; moving or clicking the mouse also ends it
    - [ ] Pad: A is still play / pause in the player, so the pad has no button for this yet (needs a decision)
- [x] The app left idle on Home for ten minutes with Library auto-download on (records why its check runs every minute) (2026-11-13): answered by the launch logs, one failed grab's retry has been due for 4.7 days and wakes the check each minute
- [ ] Nothing about focus, keys or the controller feels different from before this build (ongoing, checked during normal use)
- [ ] After opening any page by key (a catalog, details, an album), the arrows work at once without a click (the covered screen no longer takes the new page's focus away) (ongoing)
- [ ] KeyRoute lines now name an open menu or dialog instead of saying NOT DELIVERED, and give the place of the focused control on an UNHANDLED key

## Optimization audit, first fix batch (2026-11-13)

Perf logging on (`perf-log` in the Nuvio data folder) makes these easy to see in `nuvio.log`.

- [~] With Library auto-download on and left idle on Home for ten minutes, the log no longer shows `sweep starting` every minute (at most one, then `retry ... not run by the check: PUSH_BACK` or `EXPIRE` for the stuck grab) (2026-11-13): no longer every minute, and only the one title is checked, but Station Eleven S1E1 was pushed back by five minutes each time, so it still woke every five minutes
  - [x] Re-check: idle ten minutes; one `not run by the check: PUSH_BACK (title was checked)` line for Station Eleven, then no more `sweep starting` for six hours (2026-11-13)
- [ ] Library auto-download still grabs a newly aired episode of a monitored series at its usual check, and Check now still works
- [x] Cloud library (TorBox): titles and posters show as before; on the second launch they appear at once, and the log has far fewer TMDB `search/` requests in the first minute (2026-11-13): 713 searches on the first launch, none on the next two
- [ ] Cloud library: a fansub file (`[Erai-raws] ...`, `[SubsPlease] ...`) now shows its show's title and poster
- [ ] Anime rows and heroes still show their season art and posters
- [ ] Up Next still lists the next episode for shows in progress, with the episode's title and picture; finished shows still do not appear

## Cloud library (2026-11-13)

- [ ] **BROKEN** TorBox shows under Library (2026-11-13): missing on the 01:57 and 03:09 launches although Debrid settings said connected; the log said "no connected service offers a cloud library"
  - [x] Re-check after the fix (2026-11-13): launch three or four times; TorBox's items are under Library every time, and the log has `refresh Torbox: N items` on each launch

## Optimization audit, hero batch (2026-11-13)

- [ ] The hero still shows badges, cast, director / writer / studio, ratings and genre hover sub-genres for the title on screen, and for the next one as the carousel moves on
- [ ] Moving quickly through several heroes, then back to one: its trailer plays (no ten-minute wait for one that was skipped)
- [ ] Second launch of the day: the hero's badges and credits are there at once

## Log check after a test pass (2026-11-13)

Turn perf logging on first (an empty file named `perf-log` in the Nuvio data folder, `%LOCALAPPDATA%/NuvioHTPC`), then:
launch, a minute on Home, move through a few heroes, open the cloud library, leave it idle ten minutes, close; launch
again and close. Claude reads `nuvio.log` for these:

- [~] Launch burst: the first `PerfNet` Home window after launch is well under the 1,500 to 1,800 requests of Nov 13 (target under 150 on the second launch) (2026-11-13): 1,199 on the first launch (cache filling, 713 of them filename searches), 359 and 401 on the next two; the rest is mostly 120 TMDB season requests from full meta fetches of series with a Continue Watching / Up Next card
- [x] `hero bundle loaded: N entries` on the second launch, with N > 0; `PerfWork` shows `HeroBundle.hit.*` counts (2026-11-13): 534 then 550 entries; 985 hits, 22 misses
- [x] `filename lookups loaded: N matches` on the second launch; few or no TMDB `search/` requests after it (2026-11-13): 83 matches, 312 misses kept; no searches
- [~] Auto-download: no `sweep starting` every minute while idle; the stuck grab gets one `not run by the check` line (2026-11-13): every five minutes instead; fixed again, see the re-check above
- [x] No `[hero trailer] extractor failed ... left the composition` lines (2026-11-13): none
- [x] Kitsu: about half the `kitsu.io/api/edge/anime` requests of before for the same rows (2026-11-13): 92 on the first launch, 36 on the next
- [~] `no-primary-action from the addon's episode list` lines for finished series, and fewer `episode decoration` lines (2026-11-13): none, because finished series were already remembered on disk from earlier launches; the quick check cost 9 extra add-on requests and the 19 decorations were for series that do get a card
- [x] `No meta for ... (empty answer)` at most once per title per launch (2026-11-13): none seen

## Optimization audit, season requests (2026-11-13)

- [x] Up Next cards still show the next episode's title and picture, including a show whose next episode starts a new season (2026-11-13)
- [x] Continue Watching cards still show their episode's title and picture (2026-11-13)
- [x] An episode's details page and the player's episode list still show titles and pictures for every season (2026-11-13)
- [~] Log: the first `PerfNet` Home window on a warm launch has far fewer than 120 `tv/{n}/season/{n}` requests; `episode decoration` lines say `asked for [n, n+1]` or `[n]`, not `all`, for the cards (2026-11-13): 54; the cards asked for their seasons, but 45 decorations still asked for all, from the startup retry of five Up Next cards with no thumbnail, all for episodes that have not aired
  - [ ] Re-check: the log's `ARTWORK-RETRY` line no longer lists cards whose episode has not aired, and next to no `asked for all` lines at launch

## Anime artwork on disk (2026-11-13)

- [ ] Anime rows and heroes still show their season backdrops and posters
- [ ] Log, second launch: `anime artwork loaded: N pictures` with N > 0, `PerfWork` shows `AnimeArtworkDisk.hit`, and the first Home `PerfNet` window has far fewer `kitsu.io/api/edge/anime` and `graphql.anilist.co` requests than the 126 and 35 of 03:59

## Focus router, step 1: the fallback (2026-11-13)

Covers Home (with Search, Library and Discover), catalog pages and details pages only.

- [x] Mixed mouse and keyboard on Home: click around (a poster's menu, a filter menu picked with the mouse, empty space), then press an arrow: it moves the selection at once, without a click or revisiting the tab (2026-11-13): stress-tested, held up
- [ ] **BROKEN** YouTube, after a handful of quick inputs (2026-11-13): navigation stopped (shortcuts still worked); the log shows Up put focus on a control at the top right, outside the tab, where every arrow (70 of them) went to no one. YouTube had no layer, so the fallback could not help
  - [ ] Re-check after the fix (YouTube now has a layer): the same quick inputs; if focus lands somewhere dead, the next arrow brings it back to the control last used in the tab, and the log says `focus put back on YouTube`
- [ ] The same on a catalog page and on a details page (2026-11-13: never stepped in during the stress test; on catalog pages without the adaptive hero and on details pages with keyboard mode off the screens had no layer. Catalog's poster layout now has one)
- [ ] Typing in Search: Up and Down do not pull the cursor out of the search field
- [ ] With a menu open (a poster's menu, a filter menu), the arrows still move inside the menu, not on the page behind it
- [ ] Nothing feels different when focus was never lost (no double moves, no jumps back to the first row)
- [ ] Log: a `went to no one ... focus put back on Home, key sent again` line whenever it stepped in; `PerfWork` counts `KeyLayers.reclaimed`

## Focus router, step 2 (2026-11-13)

- [ ] Home after coming back from Settings, from a details page, after the setup panel closes and after a filter menu closes (with the mouse): the first arrow moves the selection (no dead press)
- [ ] Music (tab, album, artist, full player): after focus is lost (a section swap, a panel closing), the first arrow both brings focus back and moves
- [ ] Settings: the same; the ring lands on the selected category or the control last used
- [ ] Sources picker (docked on a details page): arrows keep working after clicking around it
- [ ] A details page with keyboard mode off: after clicking empty space, an arrow returns to the control last used
- [ ] Menus (a filter menu, the profile menu, a settings drop-down, a stream's actions menu): Left and Right inside them never throw focus back to the page behind
- [ ] Log: `focus put back on Music` / `Settings` / `Sources` / `Details` lines appear when it steps in

## Focus router, step 3 (2026-11-13)

- [ ] Tonight: in each mode, after clicking with the mouse (empty space, a button), the next arrow moves the selection; Back still leaves
- [ ] Live TV: the same; typing in its search box keeps the cursor in the box
- [ ] Calendar: coming back to it quickly, and after clicking around, arrows work at once
- [ ] A dialog (an add-on's settings, a confirm prompt): after clicking empty space inside it, an arrow returns to its last control; while it is open, arrows never act on the page behind

## Focus router: hotkeys after panel switches (2026-11-13)

- [ ] **BROKEN** Hotkeys keep working across panel switches (2026-11-13): broke 5+ times; YouTube -> Library -> Home in a row was enough; an arrow brought them back
  - [ ] Re-check after the fix: switch YouTube -> Library -> Home -> Settings -> Music several times quickly, then use a hotkey without pressing an arrow first; it works every time, and the log shows `focus settled on <screen> (shown)`
  - [ ] After picking from a filter menu with the mouse, a hotkey works straight away (`focus settled on Home (a menu closed)`)
  - [ ] Typing in Search or Live TV's search box: letters go into the box, never to a hotkey
  - [ ] **BROKEN** Home, I (Live TV), L (2026-11-13): Library opened and switched straight back to Home, every time
    - [ ] Re-check after the fix: Home, I, L stays on Library; L again goes back to Home; the same with D (Discover) from Live TV, Music or YouTube
- [x] Steps 2 and 3 in general (2026-11-13): works well apart from the items below

## Settings shortcut and Music keyboard fixes (2026-11-13)

- [ ] **BROKEN** Music: Escape in a search's results does not leave the search; the box had to be focused and cleared by hand (2026-11-13)
  - [ ] Re-check after the fix: search on the Music tab, press Enter, move into the results, press Escape: the search clears, the Library comes back and the arrows work on it at once
- [ ] **BROKEN** Music, Crates: Down from the view switch lands in the middle of the covers, not on the shelf list (2026-11-13)
  - [ ] Re-check after the fix: Down from the Crates pill lands on the open shelf in the list on the left (or on All shelves in Artists / All albums)
- [ ] **BROKEN** Music, Crates: the page dots and arrows take keyboard focus but show nothing (2026-11-13)
  - [ ] Re-check after the fix: the arrows never stop on the dots or the page arrows; clicking them still turns the page
- [ ] O opens Settings from Home, Library, Music, YouTube and Live TV; O on Settings goes back to Home; O while typing in a search box types an o; Settings > Keyboard shortcuts lists it and lets it be changed
- [ ] **BROKEN** S, type something, Escape (2026-11-13): closed the search history but left the search open, which felt clunky
  - [ ] Re-check after the fix: S, type, Escape closes the search in one press and Home's arrows work at once; the same in the Music, YouTube and Live TV search (clears the tab's search and returns to its page)


## First launch, theme songs and Continue Watching bar (2026-11-13)

- [ ] On a PC that has run the official Nuvio but never Nuvio HTPC (or after renaming `%LOCALAPPDATA%\NuvioHTPC` away), the first launch opens the window promptly and shows the setup wizard; Always Show Top Bar is on
- [ ] With Always Show Top Bar on, the top bar stays visible on Home, Library and Discover without hovering; off, it fades until hovered
- [ ] Station 19's details page no longer plays the Friends theme (it may play nothing, or the show's "Hero" song); Friends still plays its own theme
- [ ] Continue Watching (landscape cards): the progress bar runs flush along the card's bottom edge, rounded into the corners, white on a dark track, no glow; the time-left badge and the source shortcut button are not covered by it
- [ ] Continue Watching in poster style: the same edge bar instead of the old pill
- [ ] Library: titles saved from a details page whose metadata addon serves badge posters ("#79 Today", "2026 · Horror · ★ 64") now show plain posters like the rest of the row

## Discover anime and tags, lights out behind Home trailers (2026-11-13)

- [ ] Discover, TMDB browser: the sparkle button steps Anime: included → only → excluded (tooltip says which); Only shows just anime, Excluded drops anime but keeps Western animation (Arcane, Bluey); Clear filters leaves the choice as it is
- [ ] Discover, Tags: search "heist", pick it, the row shows heist titles; two tags show titles with either, "Must match every tag" narrows to both; with Only anime on, the menu says every tag must match
- [ ] Discover, Trending sort with a tag or the anime choice still filters the trending list
- [ ] TV Mode with the ambient background on: when the Home hero trailer starts, the coloured wash around it goes black at the same moment the trailer appears (not a beat after) (or to the theme colour if Settings > Details' trailer background is Theme; unchanged on Backdrop), and comes back when the trailer ends
- [ ] The same on Search, Library and a Collection with the ambient wash

## Panel ring and controller rebinding (2026-11-13)

- [ ] Keyboard: on Home press R; a ring of every panel in the nav bar's order (then Settings) opens in the middle, each tile numbered 1-0; pressing a number switches to that panel
- [ ] Keyboard: with the ring open, Left / Right step round it (accent ring on the aimed tile, a short accent arc on the centre ring points at it), Enter switches, Escape or R closes; no key reaches the page underneath while it is open
- [ ] Keyboard + mouse: hold R, move the mouse towards a panel, let go of R: it switches; a plain tap of R leaves the ring open with its numbers
- [ ] Mouse: hold the middle button anywhere, the ring opens at the pointer; drag towards a panel and let go: it switches; let go in the middle after a long hold: it closes; a quick middle click leaves it open to click a tile
- [ ] Mouse: with the ring open a left click switches to the aimed panel, a right click closes it, and the click never reaches the page underneath
- [ ] Controller: hold LB, the ring opens after a moment (at once if a stick is pushed); tilt either stick towards a panel, the slot under it lights up; let go of LB: it switches
- [ ] Controller: a quick tap of LB still opens Library as before; inside the ring A switches, B closes, the D-pad steps round
- [ ] Controller: letting the stick spring back before letting go of LB still switches to the slot last aimed at
- [ ] The ring follows Settings > Appearance > Navigation order and the tabs that are switched off (no Live TV slot when Live TV is hidden); Settings is always the last slot
- [ ] Picking What to watch from the ring opens the Tonight picker; picking a panel from a details page goes back to the tabs and opens it
- [ ] The ring does nothing during playback (R, LB hold and the middle button all leave the player alone)
- [ ] Home with a hero trailer playing: opening the ring stops the trailer, so the ring is not hidden under the video
- [ ] Known gap to check: a details page with its trailer playing; does the trailer cover part of the ring?
- [ ] Settings > Keyboard shortcuts > Panel ring: Controller button (Off, LB, RB, ...), Hold the middle mouse button and Ring size (small / medium / large) each take effect at once and survive a restart
- [ ] Settings > Keyboard shortcuts > Navigation lists "Open the panel ring" as R and it can be rebound
- [ ] Settings > Keyboard shortcuts > Controller · Buttons: clicking a button opens a picker with While browsing and During playback; a change shows "Changed" on the row and works on the pad at once
- [ ] Controller · Buttons: Reset default in the picker, and Reset on the section heading, put the buttons back; a rebind survives a restart and is per profile
- [ ] Settings search: "ring" and "rebind" find the new rows
- [ ] Look (restyled after "looks a bit cheap"): the panels are dark, nearly opaque circles, the centre is a thin ring with a short arc pointing at the aimed panel, and the highlight uses the app's accent colour (blue on a blue theme, not yellow); with the keyboard each circle has a small number badge at its upper right

## Picked from Spyder's branch (2026-11-13)

- [ ] Trailers: a title whose trailer used to come up empty (the log showed `LOGIN_REQUIRED`, e.g. `RYjnanieeMQ`) now plays its trailer in the Home hero and on its details page; ordinary trailers still play as before
- [ ] Tonight: a title Tonight found in Trending (not in your library) opens its details and plays instead of "Failed to load", in Three Cards, Knockout and The Map
- [ ] Phone remote (low priority; Spyder's own checks are in `TESTING.md`): long-press menus on posters and episodes, every Tonight mode mirrored on the phone, the "Ends at" clock on the film screen. Needs a remote APK built from this branch: the `android-remote.yml` workflow only runs on a branch named `Spyder`
