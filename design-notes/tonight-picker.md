# Movies & TV "Tonight" picker

Decided with the user on Nov 10, 2026. **All phases (0 to 5) built on Nov 10** (behind Settings > Random Play > Tonight picker > "Open on Tonight", off by default; not yet tried on the TV, checks in `TESTING.md`).

## The problem

The user and his partner get decision paralysis choosing a film or show. The current Movies & TV layout looks like Netflix (rows of posters) and he finds it boring; he wants something that stands out.

Hard requirement: every mode must work with **one remote and one person**, and just as well with two people sharing that remote. Two-phone or two-player ideas were rejected because they fail when watching alone.

## Mockups

The Design canvas "Nuvio: Choosing Tonight" in the user's Claude artifacts has nine 1280x720 TV screens: the launcher plus the eight modes below. Posters there are placeholder colour blocks; real artwork comes from the addons.

## Structure

The Movies & TV tab opens on a **Tonight launcher**:
- eight mode tiles in a 4 x 2 grid;
- the mode used last time is highlighted;
- a "Just browse everything" link keeps the old rows reachable;
- Back from any mode returns to the launcher.

## The eight modes (all kept)

1. **Three Questions**: three full-screen either/or questions, one tap each (e.g. *Laugh or think? Something you know or something new? Under two hours?*). A counter shows the list shrinking ("214 → 41 titles left"); Back skips a question. **When done, it goes straight to Three Cards.**
2. **Three Cards**: only three titles, dealt face up and deliberately different: Safe, New to you, Wildcard. Each shows year, length and finishing time ("ends 23:11"). One reshuffle per night; there is never a fourth card. OK plays; holding OK plays the trailer.
3. **Knockout**: eight titles in a bracket, two at a time head to head, three rounds. Left/Right keeps one; Down plays a 20 s taste of each; Back undoes the last pick. The winner plays straight away.
4. **The Map**: no rows. Titles sit on a field: across is Light ↔ Heavy, up is Comfort ↔ Something new. A glowing lens steered by the remote enlarges the posters near it and blurs the rest; a side panel shows the title under the lens.
5. **Ends By**: starts from the clock ("It's 21:10. Bed at 23:30."). A timeline from now to bedtime shows films as bars and shows as episode blocks (e.g. "3 eps · 22:40"). Anything that runs past bedtime sinks to the bottom, striped. Left/Right moves bedtime.
6. **Channel Flick**: trailers play full screen back to back, like channel surfing. OK plays the title, Left means never show it again, Up saves it for later, Right is the next channel. Builds on the pre-show trailer engine.
7. **Coin Toss**: Nuvio picks one title and starts a 10-second countdown; doing nothing plays it. Left vetoes and tosses again, and the vetoes teach it a lean ("Heat: too long, Tár: too heavy → leaning shorter and lighter").
8. **The Pile**: library items never started, drawn as a stack of spines, newest on top; older ones gather dust and fade. The selected one pulls out with how long it has waited, and offers "Finally watch it" or "Let it go" (off the pile for good).

## Decisions (user, Nov 10)

- **Placement on The Map: rules first, AI refines.** Rules (genre, runtime, rating for Light/Heavy; the user's watch history for Comfort/New) so it works with no AI key; when the AI (Groq) is available it nudges titles, cached per title like the album facts.
- **Ends By is also a filter everywhere**: a bedtime chip on every mode, as well as being its own mode.
- **Build first: the launcher plus Three Questions → Three Cards.**
- Per the standing rule, new screens are built behind a Settings switch, off by default, until the user is happy.

## Not to propose again

- Two-phone "Split Decision" and a Venn of two people's tastes (two-player only).
- Still dead ends from earlier: "The Evening" (a one-button planned night), Blind Date with a Film, Quiz Night.

## More decisions (user, Nov 10, planning)

- **Entry** (changed Nov 10 evening, after the first try on the TV: "once I'm in Tonight I cannot return to normal Nuvio"): a **What to watch** button beside Search in the top bar and sidebar (`NavBarEntry.Tonight`) opens Tonight over Home, Library or Discover; an **✕** on every Tonight screen and Back on the launcher close it (`TonightController`). "Open on Tonight" stays as an option (off by default) and only opens it once at start. The phone remote has a big "What to watch?" button on the Remote tab (`TONIGHT_DO open`).
- **Pool**: the user's library and watchlist, Continue watching / Next up, and trending / new releases. **Not** the Home rows or Discover's catalog rows; Discover's Top picks, Because you watched and More like your favourites rows can be added from Settings (Nov 12, off by default, up to 15 a row).
- **Never show / Let it go** share Discover's existing "Not interested" list (`discover/DiscoverNotInterestedStorage.kt`): one list across the app, with undo in Settings.
- **Three Questions**: the first question is always **"Film or show?"**; the other two are picked from a set and vary night to night.
- **Three Cards**: **Safe** = like what you finish; **New to you** = unseen but inside your taste; **Wildcard** = outside your usual taste.

## Build plan (phase order approved Nov 10)

Each phase ships behind Settings > "Tonight picker" (off by default) and adds its checks to `TESTING.md` in the same commit.

**Code to build on**: Random Play (`home/RandomPlay*.kt`) already resolves genres, film/show type and episode counts per title through `MetaDetailsRepository`; Discover has a taste profile (`DiscoverTasteProfile.kt`), a watched index (`DiscoverWatchedIndex.kt`) and the AI client; `MetaDetails.runtime` gives length; watched state is in `watched/WatchedRepository.kt`, progress in `watchprogress/`. CI only runs tests in listed packages, so **add `com.nuvio.app.features.tonight.*` to the test filter in `windows-build.yml`** in phase 0.

- **Phase 0: Foundation (no screens).** Package `features/tonight`. The pool from library + watchlist + Continue watching/Next up + trending/new; per-title facts (runtime, genres, rating, film/show, watched, date added). Pure rules for Light ↔ Heavy (genre, runtime, rating) and Comfort ↔ New (watch history), no AI. Bedtime setting, the shared Not interested hook, the Settings switch. Tests.
- **Phase 1: Launcher + Three Questions → Three Cards.** The launcher opening the app (eight tiles; unbuilt modes say "coming soon"; last-used highlighted). Three Questions ("Film or show?" then two from a rotating set; live "214 → 41" counter; Back skips). Three Cards (Safe / New to you / Wildcard; finishing time on each; one reshuffle a night; OK plays). The bedtime chip, shared by every mode from here on.
- **Phase 2: Coin Toss + Knockout.** Coin Toss: 10 s countdown, Left vetoes and tosses again, the "what your no's said" lean. Knockout: eight titles, three rounds, Back undoes, winner plays.
- **Phase 3: Channel Flick + trailer tastes.** Full-screen trailers back to back on the pre-show engine (sharpest-trailer probing); the same player gives Knockout's 20 s tastes and Three Cards' hold-OK trailer.
- **Phase 4: Ends By + The Pile.** The bedtime timeline as its own mode (episode blocks for shows, past-bedtime striped); the dusty stack of library items never started, with Finally watch it / Let it go.
- **Phase 5: The Map + AI refinement.** The lens on the field; with a Groq key the AI nudges each title's place once per title, cached forever, run in the background within a daily allowance (new consent target).

## How phases 0 and 1 were built (Nov 10)

- Code: `features/tonight/` (pure rules in `TonightRules.kt`, `TonightQuestions.kt`, `TonightDeal.kt`; the pool in `TonightPool.kt`; settings and the `nuvio_tonight` store in `TonightSettings.kt` + `TonightStorage.desktop.kt`; screens in `TonightScreen.kt`), the settings section `settings/TonightSettingsSection.kt` at the top of the Random Play page, and the overlay in `AppTabHost` (`App.kt`): while on, the Home tab shows `TonightHost` and Home stays composed behind it; "Just browse everything" sets `tonightBrowsing`; Home's scroll-to-top (pressing Home again) clears it.
- Rotating questions: Laugh or think, Something you know or new, Short and sweet or settle in (film under 100 min, episode up to 35), Recent or older (last three years), Cosy or edge of your seat. Order is shuffled by the night's date; a question is skipped if one answer would keep nothing. Keys: Left/Right answer at once, **Down = "Either is fine"**, Back goes back a question (the plan said Back skips; changed so Back can undo).
- Cards: Safe is a started or watched title (started ranks highest); New is unseen with taste at or above the median of unseen titles; Wildcard unseen and below it. The pick is random among the top five of each, preferring a genre not already on the table. Taste = genre weights from Continue watching (x2), watched (x1.5) and library (x1). The night runs to 6 am. OK plays via `DetailRoute(autoPlay = true)`, Down opens the page.
- Bedtime: films must end by bedtime (10 minutes grace; a half-watched film counts what is left); shows need one episode to fit; unknown lengths pass; under 20 minutes to bedtime the filter switches off.
- Pool enrichment: up to 60 titles missing genres, length or year are filled through `MetaDetailsRepository.fetchLightweightMeta` (four at a time); trending is 20 films and 20 shows from `TmdbBrowse` (Trending, the app's TMDB region). Rebuilt when ten minutes old and once the library has loaded.

## How phase 2 was built (Nov 10)

- **Coin Toss** (`TonightCoinToss.kt`, screen in `TonightModesScreen.kt`): the hat is the twelve strongest unvetoed titles (taste + rating), drawn by weight; each veto halves the chances of titles that share its trait. A veto's trait is whatever stands out most against tonight's pool: length (against the median of the same kind, weighted 1.5x because lightness already leans on length), heavy or light, seen before, unfamiliar trending, or film/show when the pool is mixed; nothing if nothing stands out. Length is matched against the vetoed title itself (as long, or longer). Keys: Left veto, OK play, Up hold/restart, Down page, Back launcher. 10 second countdown.
- **Knockout** (`TonightKnockout.kt`): `TonightBracket` (entrants + winners in play order; columns keep their width for drawing). Entrants: a shortlist of three times the bracket from the strongest, one per genre first, shuffled by the night. 8, 4 or 2 entrants depending on the pool. Winner plays after 5 s.

## How phase 3 was built (Nov 10)

- `TonightTrailerFrame` (`TonightTrailer.kt`) plays a title's trailer in a fixed 16:9 box through `HeroTrailerMetadataService.resolve` and `HeroTrailerPlayerSurface` (sound on, the heroes' shared volume, `keyboardNavigationEnabled = false` so the remote stays with Tonight). The video is a native window, so **no Compose text is drawn over it**: Channel Flick's labels sit under the frame (the mockup overlaid them). On a player error it tries the source's `fallback` chain, then drops the cached answer and reports "no trailer". Optional `limitMs` (20 s tastes) counts from when the video is ready.
- **Channel Flick** (`TonightFlick.kt`, `TonightFlickScreen.kt`): line-up = unseen first (+0.4), taste, rating, plus a seeded shuffle. Right next, Left = `DiscoverRecommendationsRepository.markNotInterested` + gone for the visit, Up = `LibraryRepository.addToAllLibraries` (not for titles already in the library) and next, OK play. A title with no trailer leaves the line-up for the visit only.
- **Knockout tastes**: Down plays 20 s of the left title, then the right, then back to the posters. **Three Cards**: OK now acts on release (`tonightKeysWithHold`); held for half a second it plays the selected card's trailer in place of the hand.

## How phase 4 was built (Nov 10)

- **Ends By** (`TonightEndsBy.kt`, `TonightEndsByPileScreen.kt`): rows from the whole pool (not bedtime-filtered) with a known length. A film is one bar; a show is one block per episode that fits (up to four) plus one faded block past bed. Order: fits first, then 0.5 x how much of the evening it fills + 0.3 taste + 0.2 rating. Axis from the hour before now to a whole hour at least 30 minutes past bed, 3 to 6 hours. Six rows visible in a moving window. Left/Right nudge bedtime (same setting as the chip).
- **The Pile**: library titles never started or watched with a saved date, newest first; dust rises from a week old to full at six months (dots and fading). Let it go needs Left twice and uses Discover's Not interested (the title stays in the library). Nine spines visible in a moving window.

## How phase 5 was built (Nov 10)

- **The Map** (`TonightMapRules.kt`, `TonightMapScreen.kt`): x = (1 - lightness) / 2 (light left), y = (1 - novelty) / 2 (new at the top), each with a small stable jitter per title (more vertically, as novelty comes in a few steps). The lens is 0.14 of the field's width in radius, moves 0.05 per arrow, starts at the average place of what the user keeps. Titles inside the lens show posters and grow toward its centre; the nearest one is "under the lens". Uses the bedtime-filtered pool.
- **AI** (`TonightMapAi.kt`): only Light/Heavy is refined (Comfort/New stays the user's history). Final lightness = 0.35 rules + 0.65 AI tone. Requests only while The Map is open, only with the switch on (new `AiConsentTarget.TonightMap`, so the shared consent dialog), twelve titles per request through `DiscoverAiClient`, 8 s apart, at most 25 a day, stops on a rate limit. Tones kept forever per title key in the `nuvio_tonight` store (`tones`); a title the AI skipped is asked again after a day.

## Tonight on the phone remote (Nov 10)

User's choice: **mirror the TV** for **Three Questions and Three Cards** (the launcher too); other modes stay on the TV. `TonightHost` publishes a `RemoteTonightScreen` (launcher / question / cards / other) to `RemoteTonight`; the PC sends it as one `TONIGHT` line (16 fields) on every change and after SUBSCRIBE; the phone's taps come back as `TONIGHT_DO <action> [n]` (questions, cards, left, right, either, back, play n, details n, reshuffle) and run in the host exactly as the TV's keys. On the phone the mirror replaces the Remote tab's body while nothing plays; the header tag swaps it for the pad. The demo TV does not imitate it yet.

**Nov 11: every mode on the phone** (user: "Can the remote have all picker modes??"; mirror Knockout, Coin Toss, Channel Flick, Ends By and The Pile; The Map as "drag the lens like a touchpad"). The host publishes only the launcher, questions and cards (the launcher now sends all eight tiles as cards: slot = mode name, flag = built; `TONIGHT_DO mode n` opens tile n). Each mode view publishes its own screen with `MirrorOnPhone(screen, onAction)` (`TonightScreen.kt`): taps go to `onAction` first (Ends By / Pile: `play n` plays row n, `letgo n` lets row n go at once, the phone having asked), and everything else becomes the TV's own key through `TonightBackBus`, which now carries any `TonightKey` (left, right, up, down, ok/play, back), so the phone runs exactly the key handlers the remote does. The `TONIGHT` line gained five trailing fields (extra, seconds, secondsTotal, paused, selected) and a sixth card field (flag); old apps read the first sixteen and five. Lists are capped at 30 rows. The Map sends the lens position in hundredths (number = x, countLeft = y) and the phone's `Touchpad` (now internal, 28 dp steps) sends up/down/left/right/ok.

## Still open

- The set of rotating questions for Three Questions.
- There is no Settings page yet to undo Not interested (Never show / Let it go); Discover has no undo either.
- The phone's demo TV has no Tonight mirror.
- What exactly counts as "your taste" for New to you vs Wildcard (Discover's taste profile is the first candidate).
