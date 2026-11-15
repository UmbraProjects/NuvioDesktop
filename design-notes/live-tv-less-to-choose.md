# Live TV: less to choose

Brainstormed and mocked up with Nick on Nov 13, 2026. **Only the triage (screen 1) is built** (Nov 13, see its entry
below); the rest is not. Nick will do this phase later and asked for
everything to be kept: the ideas, the mockups and how to build them. Nothing here has been decided beyond "keep it";
the build order below is a recommendation, and the first job of the session that picks this up is to ask Nick which
screens go in.

## The problem

Live TV is streams and a guide, with about 12,000 channels to pick from. Nick's words: he wants "a revolutionary way
of picking channels, seeing what's on, something to stop the brain rot". The On Now wall (built Nov 13, see below) sorts
the menu better, but it is still a menu. The idea of this phase: **a screen that offers less and has an end.**

Standing rules that apply (from earlier phases):
- Same styling as the rest of the app: the settings glass (`NuvioGlass`), the settings sky background, JetBrains Sans,
  the floating top bar, the accent-glass button for the main action, the thin focus ring.
- **Symmetry**: equal side panels around a centre, or equal columns. Nothing moves or resizes depending on content.
- Every mode works with one remote and one person (keyboard, pad, mouse).
- New screens go behind a Settings switch, off by default, until Nick is happy.
- Checks go in `TESTING.md` in the same commit; fork-only files go on the list in `CLAUDE.md`.

## Mockups

- **This phase** (12 screens, 1280x720, glass and symmetric): Design canvas "Nuvio Live TV: Less to choose",
  https://claude.ai/artifact/QwVJnxGB91tAoFkKBXRyFc
- **The phase before** (On Now, built): Design canvas "Nuvio Live TV: On Now",
  https://claude.ai/artifact/3dT9ur6LxdebcXV4JzNRpG

Both are in Nick's Claude artifacts. All names, times and counts in them are sample data, and the accent is Ocean blue
(each screen has an accent tweak). Layout used on every screen: a centred heading under the top bar, then a 296 px
glass panel, a centre, and a 296 px glass panel, with 32 px outer margins and 24 px gaps; or three equal columns; the
three "brakes" are a centred glass card over the dimmed player.

## What already exists to build on

Built Nov 13 behind Settings > Live TV > On Now (`IptvSettings.onNow`), all under `features/iptv/`:

- `IptvChannelMerge.kt`: copies of a channel folded into one `IptvMergedChannel` (best copy first, the rest as
  fallbacks). The lineup below should be a list of merged channels, never raw copies.
- `IptvOnNow.kt`: `IptvProgrammeKind` (film, sport, news, kids from the guide's categories, kept on
  `IptvEpgEntry.kind`), `IptvOnNow.films/sport`, `IptvUsuals` (channels by time of day from `IptvSettings.plays`),
  `IptvSportMatch` (a followed match to the channel showing it), `IptvCatchup` (Xtream catch-up links), and the wall
  model (`IptvWall`, `IptvWallRow`, `IptvWallItem`).
- `IptvOnNowScreen.kt`: the tiles, the programme page with Start from the beginning, `IptvFilmArt` (TMDB poster,
  backdrop, blurb and year by title), `buildIptvWall` / `rememberIptvWall` (the model rebuilt off the UI thread).
- `IptvScreen.kt`: the sidebar sections, the wall's highlight (`wallRow`, `wallCol`) and keys, the open page (`detail`).
- `IptvPlayback.kt`: `Active.alternates`, `Active.catchup`, `launchCatchup`, `playCatchup`, `finishCatchup`.
- `LiveScoreEngine.followed`: the followed matches as a flow.
- `IptvEpgRepository.guideEntries(playlist, channel)`: every programme in the guide window, 24 h back and 36 h ahead
  (`XmltvGuide.WINDOW_BEFORE_MS` / `WINDOW_AFTER_MS`). Following programmes a week ahead needs the window widened.
- Elsewhere in the app: the Tonight picker (`features/tonight/`: Three Cards, Coin Toss, Ends By and its bedtime),
  voice search (`core/ui/VoiceSearchButton`), the AI client used by Tonight's Map (Groq), the phone remote's
  notifications, and `features/librarypvr/` (scheduled recording).

Left over from the On Now phase, not built: reminders, picking a copy by hand, "Split them apart", "Hide scores", the
goal pop-up's one-press jump to the channel, and seeking inside a recording beyond the player's rewind buffer.

## The twelve screens

Each entry: what it is, the rules, and what it needs.

### Shrink the 12,000

**1. Triage, once. BUILT Nov 13** as three questions (countries, kinds, your first ten) behind a Triage button in the
Live TV header: `IptvTriage.kt`, `IptvTriageScreen.kt`, `IptvSettings.triage`. There is no language question (the
playlist has no language data; the country does the job), "your first ten" stars favourites until the lineup exists,
and the player's channel switcher is not narrowed yet. What follows is the original plan.

Four questions, one per screen: countries, languages, kinds of channel, then "your first ten".
A counter shows what is left ("1,280 of 12,014"). Nothing is deleted; everything stays in search; it can be redone
from Settings.
- Needs: region and language per channel. Regions exist (`IptvRegions.categoryRegions`, from category prefixes).
  Language does not: derive it from the region, the category name, or the XMLTV `<display-name lang>` /
  `<title lang>` attribute (the parser drops attributes on those today). Kinds: classify **categories** by name
  (reuse the word lists in `IptvOnNow`: `filmPlace`, `sportPlace`, plus news, kids, music, shopping, religious, adult,
  radio, "24/7").
- Store: `IptvTriage(regions, languages, kinds)` per playlist in `IptvSettings`.

**2. Your 40.** (BUILT Nov 13 as a sidebar section behind Settings > Live TV > Your 40: cap fixed at 40, numbers stay put until moved, menu on a "..." beside the star, Never again, typed numbers follow the lineup. Not built: overnight weeding, rebuild, the side panels of the mockup, the player banner's number.) The whole of Live TV as a numbered lineup of about 40 merged channels, like a television. Side panels
show how it got there (triage, copies merged, what you watch, added by hand) and what is kept out (never again, dead,
unwatched). OK watches; hold OK moves, renumbers or drops.
- Rules: lineup = channels added by hand, then the most watched over 30 days (`IptvSettings.plays`), filled from the
  triage up to the cap. A channel thrown out with "never again" can never come back by itself.
- Store: `lineup: List<String>` (channel refs in order, so the position is the number) and `never: Set<String>` per
  playlist. Lineup numbers replace provider numbers for typed channel numbers in the player
  (`IptvPlaybackSession.byNumber`) and for channel up/down (the zap list) while the switch is on.
- **Never again** is one key on any channel or programme tile and in the player; add it to the long-press / context
  menu rather than a new key.
- **Overnight weeding**: a background job that opens each lineup channel for a few seconds (HEAD or a short GET
  through `IptvHttp`, honouring the proxy and the account's connection limit: one at a time, never while something is
  playing), counts failures per night, and drops a channel after three bad nights (it moves to "Dead", not deleted).
  Only lineup and recently watched channels are tested; testing 12,000 would get the account banned.

### Stop joining things halfway

**3. Departure board.** (BUILT Nov 13 as the Starting soon sidebar section behind Settings > Live TV > Starting soon: three buckets with a countdown, Remind me and Switch to it on the programme page; the reminder card is informational, no OK yet, and there is no "Tune in at" panel.) Only things that have not started, leaving within 15, 30 or 60 minutes, with a countdown.
The right panel has "Tune in at 20:00": carry on with what is on and Nuvio switches at the start.
- Needs: `guideEntries` for lineup channels, filtered to `startMs in now..now+window`. Pure function plus a test.
- **Tune in at** is the reminder feature: a small scheduler (`IptvReminders`: programme key, channel ref, start) that
  fires a card on the Compose overlay and on the player's HUD (the live score pop-up already draws on both, see
  `LiveScoreOverlay` and `LiveScorePlatform.showOnPlayers`), with OK to switch; when the user asked to be tuned
  automatically it switches by itself through `IptvPlaybackRequests.play`. Persist reminders so a restart keeps them.

**4. Earlier today, ready to play.** (BUILT Nov 13 in Decide for me, without the day list.) Programmes already shown on the lineup that the provider still has, best first,
each played from its start. A day list on the left goes back as far as the catch-up does.
- Needs: `IptvCatchup.available` (Xtream only; M3U `catchup=` attributes are not parsed yet and would widen this),
  `guideEntries` for past programmes. Past the guide's 24 h window there is no data: either widen
  `WINDOW_BEFORE_MS` to the largest `archiveDays`, or ask `get_simple_data_table` per channel on demand.
- "Best first" rule: followed programmes, then films (with a TMDB match), then kinds the viewer watches most, with
  repeats and shows under 10 minutes dropped. Keep it a pure function so it can be tuned.

**5. Follow programmes.** (BUILT Nov 13 in Decide for me: Follow on a programme's page, matched over the guide's window only, no suggestions.) Star a programme, a team or "any Bond film" once; Nuvio finds it each week on whichever
channel has it. The list shows where each was found; one with nothing this week is dimmed.
- Needs: a title index over the whole guide, not just the lineup (all 12,000 channels' programmes, title to channel
  and time). The guide is already cut to the playlist's channels at parse time, so build the index in
  `XmltvGuide.build` or in one pass after load, off the UI thread, and keep it in memory only.
- Matching: normalised title equality first, then "contains" for teams (reuse `IptvSportMatch.mentions`) and series
  names. Prefer a lineup channel, then the best copy (`IptvChannelMerge.rank`).
- Store: `follows: List<IptvFollow(text, kind)>`. Suggestions come from `IptvSettings.plays` joined with what was on
  (needs the programme title recorded with each play: add `title` to `IptvPlay`).

### Decide for me

**6. Three things.** (BUILT Nov 13 in Decide for me; bedtime is in the rules, not wired.) Three equal cards: SAFE (the usual at this hour), STARTS SOON (from the first minute, over before
bedtime), WILDCARD (off the lineup, tied to something known about the viewer). One reshuffle a night; never a fourth.
- Reuse Tonight's Three Cards rules (`TonightDeal.kt`) and its one-reshuffle-per-night state. SAFE from `IptvUsuals`,
  STARTS SOON from the departure board, WILDCARD from films and followed things outside the lineup.

**7. I have 25 minutes.** (BUILT Nov 13 in Decide for me, as a row of five choices and three rows of tiles.) A dial (15, 25, 45, 60, 90 minutes, or "until 21:00") and three equal columns: already on
and ending in time; starting and ending in time; from catch-up and short enough.
- Pure filtering over the same guide data; the dial value is the only state. Nothing may be offered that runs over,
  except shown dimmed with how far over ("30 min, 5 over").

**8. Tonight's running order. (BUILT Nov 13 in Running order and Just ask: rules only, no AI, no catch-up gaps, no dimming at the end.)** One proposed evening from all the channels, ending before bedtime; approve once and
Nuvio changes channel at each start. Any key takes over; at the end the screen dims and nothing starts by itself.
- Built from: usuals per hour, followed teams and programmes, films, bedtime (reuse Tonight's Ends By bedtime
  setting). Gaps are filled from catch-up ("from catch-up, shown 20:45"). Something that would run past bedtime is
  offered for tomorrow, not trimmed.
- Needs the reminder scheduler from screen 3 (auto-switch) and a "running order active" state the player can show.
- Rules first so it works with no AI key; the AI may reorder or explain, cached, as Tonight's Map does.

**9. Just ask. (BUILT Nov 13 in Running order and Just ask: rules only, no AI, no catch-up gaps, no dimming at the end.)** A typed or spoken line ("something funny, half an hour") and **one** answer with a "Not that"
button; side panels show earlier asks and why this was chosen.
- Rules first: parse a duration, a kind and names out of the text and run the screen 7 filter plus the follow
  matcher. With an AI key (Groq, as Tonight uses, behind the same consent row in
  `settings/DiscoverAiSettingsSection.kt`) send the request and a short list of candidates and take its pick.
- Voice: `VoiceSearchButton` / `VoiceSearchCommands`, as the Live TV filter box already does.

### Brakes

**10. Zap budget. (BUILT Nov 13 in Brakes: two answers on the zap card, the week shown in Settings.)** Five channel changes in two minutes and a card stops the flicking with three picks, plus "Keep
flicking" and "Turn Live TV off". Once an evening; can be switched off.
- Hook: `switchIptvChannel` / `zapTo` in `PlayerScreenRuntimeIptv.kt` (count changes in a sliding window). The card
  is drawn by the player's HUD (`controls.js`, the way `window.nuvioShowScoreCard` is) so it shows over the video.

**11. The end card. (BUILT Nov 13 in Brakes: two answers on the zap card, the week shown in Settings.)** When a programme ends nothing starts by itself: "Stop here" (focused) or "One more", which
offers exactly one thing.
- Hook: the programme boundary from the guide for the playing channel (`rememberIptvControlState` already knows now
  and next), and `finishCatchup` for a recording that ran out. Live channels do not stop, so the card appears over the
  running channel and "Stop here" leaves the player.
- Careful: this must not fire on every boundary while someone is watching a channel for the evening. Suggested rule:
  only at the end of something the viewer **chose** (from the board, Three things, the running order, Earlier today,
  Start over), never after plain channel surfing.

**12. Honest tally. (BUILT Nov 13 in Brakes: two answers on the zap card, the week shown in Settings.)** On leaving Live TV: time watched, programmes watched to the end, channel changes, and this week
against last. Kept on this PC only.
- Store: a small daily log (`IptvWatchLog`: minutes, changes, finished) beside the settings, not in them; cap it at
  a few months. The music play log (`MusicPlayLog`) is the pattern to copy.

## Suggested build order

Each phase stands on its own and can be switched on by itself.

1. **Your 40** with never again (screens 2, then 1). Everything else reads the lineup, so it comes first. Triage can
   follow, since the lineup works from viewing and hand-picking alone.
2. **Departure board and reminders** (3). The scheduler it brings is needed by the running order.
3. **Three things and I have 25 minutes** (6, 7). Cheap: rules over data that already exists.
4. **Earlier today and Follow** (4, 5). Needs the wider guide window and the title index.
5. **The brakes** (10, 11, 12). Player-side work; the end card needs the care noted above.
6. **Running order and Just ask** (8, 9). Last: they lean on everything before them.
7. **Overnight weeding**. (BUILT Nov 13: lineup and recents, 02:00 to 05:00, three bad nights, no Dead list.) Separate because it talks to the provider unattended.

Where the screens live: the On Now sidebar already has On now, Sport and Films. Add the new ones as sidebar sections
in the same way (`IptvWall.SECTIONS`, `IptvSidebarItem`), or, if Nick wants the symmetric three-panel layout to
replace the sidebar layout, as a launcher of tiles like Tonight's. **Ask him**: the mockups use the symmetric layout
with no sidebar, which is a bigger change than adding sections.

## Open questions for Nick

- Does the symmetric three-panel layout replace the current sidebar layout of Live TV, or sit beside it?
- Is 40 the right size, and is the cap fixed or a setting?
- Should "Tune in at" switch by itself, or always ask with a card first?
- The end card: only after something chosen (suggested), or after every programme?
- Is the tally wanted at all, and should it ever say anything unprompted?
- Which screens are in, and which were only worth seeing?

## How this was built and checked last time

The cloud sessions cannot compile the app (Gradle's download sites are blocked) and Nick's PC is often not linked.
What worked for the On Now phase:

- Pure rules in their own file with unit tests; a standalone `kotlinc` (from the Kotlin GitHub release, the version in
  `gradle/libs.versions.toml`) compiled and ran them locally with a stub `@Serializable`.
- A work branch `claude/<name>` with a branch-only workflow that copies the setup steps of `windows-build.yml`, runs
  `:composeApp:desktopTest --tests "com.nuvio.app.features.iptv.*"` and publishes nothing. The commit adding that
  workflow is left out when the work is moved to Spyder.
- A Compose UI test in `desktopTest` (`IptvOnNowScreenTest`, after `IptvScreenTest`) that serves a small M3U and XMLTV
  from a mock server, drives the screen with keys and clicks, and saves screenshots to `build/test-screenshots`.
- Run artifacts cannot be downloaded from the cloud session, so the workflow also pushes the screenshots to a
  throwaway `<branch>-shots` branch, which can be fetched and looked at.
- Only when that run is green are the commits put on Spyder (the Windows build then publishes Nightly).
- Two scratch branches from that run, `claude/live-tv-on-now` and `claude/live-tv-on-now-shots`, could not be deleted
  from the session and may still be on GitHub; they are safe to delete.
