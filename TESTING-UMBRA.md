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

