# Music Discovery: the Ladder

Search in the Music tab becomes discovery. Searching a **genre**, an **artist** or an **album** can give a *gateway ladder*: five albums ordered from nearest your current taste to the purest form of the target sound. The ladder is music and genre based only.

## Concept

Every music search treats a genre as a filter or a dump of tracks. Genre is hard to get into because you don't know where to start. A ladder gives you a path.

Each candidate artist gets two scores:

- **Purity**: how central the artist is to the target. Their tag votes for the target divided by their total tag votes, with fans as a tie-break.
- **Proximity**: how close the artist is to *you*. Similarity between their tag vector and your footprint, built from your saved artists' genres, tags, mood and style. The target itself is removed from both vectors, so proximity measures what you share *beyond* the target.

Five rungs are chosen so purity rises while proximity falls. Rung 1 is a bridge from your taste (high proximity, low purity). Rung 5 is the core sound (high purity, low proximity).

### Three kinds of query, one ladder

| Query | Target vector | Modes |
|---|---|---|
| Genre ("shoegaze") | the genre's tag centroid | into |
| Artist ("My Bloody Valentine") | that artist's tag vector, artist excluded from candidates | **into** (never heard them) or **out from** (already love them) |
| Album ("Loveless") | the album's mood, style and era blended with its artist's tags | into / out from |

Only the target vector changes. One function covers all three: `buildLadder(target, footprint, candidates, dial)`.

### Behaviour

- **Familiarity dial** (Known to Unknown): at Known the ladder starts close to your taste; at Unknown the first rung starts halfway up the genre's purity range, so the whole ladder sits further out.
- **Feedback** per rung: too far, too close, skip, love. The dial shifts and the remaining rungs re-rank.
- **Progress**: climbed rungs are stored; saving an artist records "found via ladder".
- **Cold start**: with no Library, rung 1 is the most accessible artist in the genre and the dial becomes purity only. For an artist query the query itself is the seed.
- **Why line** per rung, from a template and no AI: "shares *dream pop* and *calm* with your saved Cocteau Twins, but louder."
- **Audition**: moving over a rung plays the hook (Left and Right since the HTPC layout; it was Up and Down) (repeated synced-lyric lines give the chorus; fixed-offset fallback) and the room takes on the artist's palette and `MotionProfile`.

## Data

- One MusicBrainz artist search by tag returns about 100 candidates with tag counts: a single paced call (the client already waits 1.1 s between calls).
- Albums come from Deezer top tracks and YouTube Music (existing `selectTopAlbums` and play path).
- Thin tags: fall back to Deezer related artists, then to Library neighbours.
- MusicBrainz tags are noisy and have no official genre hierarchy. "Nearness" comes from tag co-occurrence, checked against Deezer related artists, not from a hand-built genre tree.

## Where it sits in the app

The first result row in `MusicTabScreen` gets a **Ladder** button that reads the query ("Ladder into Shoegaze", "Climb into / out from My Bloody Valentine", "Ladder from this record"). Code lives in `music/library/` as pure data with unit tests (like `ShelfModel`), plus one small `// fork:music` hook.

## Roadmap

Each phase fits one session, leaves the app working, and can be the last one you do. Phases 0, 1, 2 and 8 are pure logic with no network or UI.

| # | Phase | What gets built | Done when | Status |
|---|---|---|---|---|
| 0 | **Tag vocabulary** | `LadderTags.kt`: normalise tags and aliases ("post punk", "postpunk", "post-punk" become one), drop junk ("seen live", eras, places). | Unit tests pass for alias and junk cases. | **Done** |
| 1 | **Scoring** | `LadderModel.kt`: footprint vector from `LibraryArtist` genres, tags, mood, style; purity and proximity, target excluded from vectors. | Tests on fake artists show the right artist scoring closest. | **Done** |
| 2 | **Rung selection** | `buildLadder(target, footprint, candidates, dial)`: five rungs with purity up and proximity down; template "why" line; dial; no-Library fallback. | A fake candidate set gives a sensible ladder at three dial settings. | **Done** |
| 3 | **Genre candidates** | One MusicBrainz tag search (about 100 artists with tag counts) through the paced client, parser and cache. | A canned-JSON test parses, and one live call returns candidates. | **Done** (live call not yet tried) |
| 4 | **Artist target** | Target vector from an artist's tags, falling back to Deezer related artists. "Into" and "out from" modes. | The same `buildLadder` runs on an artist query. | **Done** (4a into, 4b out from and Deezer fallback) |
| 5 | **Search card and screen** | "Ladder into / out from..." button on the first result in `MusicTabScreen` (one `// fork:music` hook) and a basic five-rung screen with covers and reason lines. | You can search, open a ladder and read it. | **Done** (builds in Gradle; not checked on screen) |
| 6 | **Play and save** | Rung plays through the existing top-album and play path. Saving records "found via ladder". | Tapping a rung plays it, and saving works. | **Done** (builds in Gradle; not checked on screen) |
| 7 | **Feedback and progress** | Too far / too close / skip / love shift the dial and re-rank. Climbed rungs are stored. | Feedback changes remaining rungs; the ladder survives a restart. | **Done** (builds in Gradle; not checked on screen) |
| 8 | **Hook finder** | Pure function: repeated synced-lyric lines give the chorus time; fixed-offset fallback. | Tests on sample LRC files find the chorus. | **Done** |
| 9 | **Audition** | Moving over a rung seeks to the hook; palette and `MotionProfile` change the room. | Moving between rungs auditions each one. | **Done** (builds in Gradle; not checked on screen) |
| 10 | **Album query** | Blend an album's mood, style and era into the target vector. | An album query gives a different ladder from its artist's. | **Done** (builds in Gradle; not checked on screen) |

## Risks

- **Tag noise**: phase 0 handles spelling and junk, but sparse tags on small artists remain (fallbacks above).
- **Rate limit**: MusicBrainz allows one request per second. Compute neighbourhoods lazily, cache them, and prefer one search call over many lookups.
- **Playability**: some rungs will have no YouTube Music release. Show them, flagged, and pick a fallback album.
- **Compiling and testing**: the app builds through Gradle (owner-confirmed 2026-10-27, covering the Library views through C4 and the ladder code they link to). Phases 0 to 10 pure logic was also checked earlier with a standalone Kotlin compiler (166 ladder tests for phases 0 to 7 and 10, plus 21 and 20 for phases 8 and 9). What is not recorded is a Gradle run of these tests or a look at the ladder screen on a real display. CI now runs every `features.music`, `features.preroll` and `*Trivia*` test on each build (`windows-build.yml`), so a failing ladder test shows up there.

## Phase 0 notes (done)

`composeApp/src/commonMain/.../music/library/LadderTags.kt` and `commonTest/.../LadderTagsTest.kt`.

- `genreKey(raw)`: comparison key (case, spacing, punctuation ignored, "&" read as "and").
- `canonicalGenre(raw)`: fixed readable name for known genres and aliases; unknown genres kept lower case; null for junk.
- `isNonGenreTag(raw)`: blank, too short or long, eras, places, opinions and listener habits. Matches the whole tag only, so "uk garage" passes while "uk" does not.
- `sameGenre(a, b)`, `normalizeGenres(list)`, `normalizeWeightedGenres(votes)` (variants add their counts, sorted by votes then name).
- Phase 1 should compare genres by `genreKey` and show `canonicalGenre`.
- The alias and junk lists are plain sets at the top of the file; extend them as real tags show up.

## Phase 1 notes (done)

`music/library/LadderModel.kt` and `LadderModelTest.kt` (23 tests; with phase 0 that is 36, all passing).

- `TagProfile`: genre key to weight, scaled to length 1. Moods use a `mood:` key prefix so they never collide with a genre.
- `tagProfile(genres, tags, mood, style)` for lists with no counts (`LibraryArtist.tagProfile()`): earlier genres weigh more, genres weigh more than tags (first 8 kept), mood and style add a little.
- `tagProfileFromVotes(votes, mood, style)` for MusicBrainz-style hits: votes scaled so the top tag is 1.0.
- `footprint(artists)`: summed profiles, a favourite counts twice. Empty when there is no Library (phase 2 falls back to purity only).
- `purity(votes, targetKey)`: the target's share of the candidate's merged genre votes.
- `proximity(candidate, footprint, excluded)`: cosine similarity with the target removed from both sides.
- `targetGenreKey(raw)`: turns what the person typed into the key ("DnB" and "drum and bass" meet).
- Weights are named constants at the top of the file (`GENRE_WEIGHT`, `TAG_WEIGHT`, `DESCRIPTOR_WEIGHT`, `FAVOURITE_FACTOR`); tune them once real artists run through.
- Verified with a standalone Kotlin compiler against a minimal stand-in for `LibraryArtist` (same field names). Not yet built through your Gradle project.

## Phase 2 notes (done)

`music/library/LadderBuilder.kt` and `LadderBuilderTest.kt` (24 tests; 60 across phases 0 to 2, all passing).

- `buildLadder(target, artists, candidates, dial = 0.0, rungs = 5)` returns a `Ladder(target, rungs, coldStart)`. Each `LadderRung` has the step (1 up), the candidate, purity, proximity, the saved artist it most resembles (`anchorName`) and a one-sentence `why`.
- A rung's target purity is read off where the pool's purities actually fall (quantiles), so a pool with few near-pure artists still fills the top rungs. The best candidate for each rung scores proximity, plus a small playable bonus and a tiny popularity bonus, minus a penalty for missing the rung's purity (`PURITY_FIT_PENALTY`, measured against the pool's range). Rungs fill in order; a candidate takes at most one. Final rungs are sorted so purity always rises.
- Candidates under 15% target share are skipped when enough others exist; if none clears the bar, the best of the rest is used.
- Saved artists never stand on a rung (matched by id and by name). Unplayable artists can still take a rung when they fit best (`candidate.playable` is carried for the UI to flag).
- Cold start (no Library, or nothing beyond the target in it): proximity is zero, rungs follow purity, and popularity breaks ties so the bridge is the most accessible artist.
- The `why` line is a template: "Closest to your taste: shares X and Y with your saved A." / "Shares X with your saved A, with more G." / "The core of G ..." / "A way into G: tagged ...". Same inputs always give the same ladder.
- An unusable target (blank, or not a genre) or a pool with none of the genre gives an empty ladder.
- Known limit: the sample pool shows that proximity is often 0 for middle and top rungs when the Library is narrow (nothing in common outside the target). That is expected; phase 4's Deezer related-artist fallback and richer footprints will help.
- Constants at the top of the file (`MIN_PURITY`, `MAX_DIAL_START`, `PURITY_FIT_PENALTY`, bonuses) are the tuning knobs.

## Phase 3 notes (done)

`LadderCandidates.kt`, `MusicBrainzClient.searchByTag` (one paced call, about 100 artists with tag votes), `parseMbTagSearch`.

- `LadderCandidateCache` keeps a genre's candidates for a day, an empty or thin answer (under 5) for an hour, 24 genres at once. An unreachable source is never cached. Entries are keyed by `genreKey`, so "post punk" and "Post-Punk" share one.
- Not yet tried against the live MusicBrainz API.

## Phase 4 notes (done)

`LadderArtistTarget.kt` (4a), `LadderRelated.kt` and `LadderOutFrom.kt` (4b).

- **Artist target (4a):** one paced `searchArtistWithTags` call; the typed name's exact match wins, else the best hit. The artist's most voted genre is the target, their top three genres are kept. Answers (found, no artist, no genre) are cached for a day; unreachable is not. The artist never stands on their own ladder (matched by id and name).
- **Deezer fallback (4b):** an artist with no usable tag asks Deezer for related artists (`DeezerClient.relatedByName`, exact name match, most fans wins among namesakes), looks up the first 4 on MusicBrainz (4 paced calls, exact name only), and pools their genres (`borrowGenres`: each artist's top tag scaled to 1.0, weight 1 / (1 + 0.25 x position)). At least 2 lenders are needed, else `NoGenre`. `ArtistTarget.borrowedFrom` names them for the screen.
- **Out from (4b):** for an artist the person already loves. It is `buildLadder` with the destination genre as the target, closeness measured against the artist alone (`anchors`), and why lines that name the artist (`from`). The destination is the artist's second genre, a chosen one, or, for a one-genre artist, the genre that most often sits beside theirs in the primary genre's candidates (on at least 2 of them). No neighbour gives `NoOutward`. The Library still decides who never stands on a rung.
- Entry points: `LadderArtistTargets.ladderInto` and `ladderOutFrom`; the pure versions take their sources as arguments so tests need no network.
- Not built: Library neighbours as a further fallback.

## Phase 5 notes (done)

`LadderScreenModel.kt` (pure), `MusicLadderScreen.kt`, `LadderArtwork.kt`, one `// fork:music` hook in `MusicTabScreen.kt`.

- **Search card:** a known genre (`isKnownGenre`, so "DnB" counts) gives "Ladder into X"; any other query on the Artists tab gives "Climb into X" and "Climb out from X" for the first result; the Albums tab gets the genre pill for a genre, and (phase 10) two record offers for anything else.
- **Screen:** replaces the tab body while open; Back link and system back. Each rung shows step, picture, name, why line and purity. Notes say when genres were borrowed or the Library is empty. Out-from shows the other destination genres as chips.
- Pictures come from Deezer's artist search (exact name only, cached for the session).
- Failures are messages, with "Try again" only for connection problems.

## Phase 6 notes (done)

`LadderPlay.kt`, `LibraryArtist.foundVia`, `MusicLibraryRepository.addFoundVia`.

- **Play:** the rung is matched to a YouTube Music artist by exact name. A saved artist plays their top album (`selectTopAlbums`); otherwise the first full album on their page, else the first release of any kind. No artist, no release, or no available track shows "No release to play here" on the row.
- **Save:** goes through the normal add flow and records `foundVia` ("ladder: Into shoegaze"). A saved artist keeps how it was first found, and a refresh keeps it.
- `foundVia` is stored but not yet shown on the artist page.

## Phase 7 notes (done)

`LadderProgress.kt`; two methods added to `MusicLibraryStorage` (`loadLadders`, `saveLadders`).

- **Feedback:** Too far moves the dial a quarter step toward Known, Too close toward Unknown, Love leaves it, Skip removes the artist from that ladder for good. Feedback on a rung already marked replaces its mark.
- **Re-rank:** marked rungs stay at the top (the last 4 are kept, so a rung is always left to build); the rest are rebuilt at the new dial from the cached candidates, with marked and skipped artists excluded. Purity rises within each part, not always across the join.
- **Progress:** stored per ladder (`progressKey`: kind plus spelling-insensitive name) and per profile as one JSON payload. "Start over" clears it.
- The dial and marks are read when a ladder opens and written on every change.

## Phase 8 notes (done)

`music/library/HookFinder.kt` and `HookFinderTest.kt` (21 tests, all passing with the standalone Kotlin compiler against the real `parseLrc` and `sungEndMs`).

- `findHook(lines, durationMs = 0)` returns a `Hook(startMs, lengthMs, source, lineIndex, lineCount)`; `hookFor(lyrics, durationMs)` takes whatever lyrics a track has (only `Lyrics.Synced` is searched). Same input, same answer.
- **Chorus:** lines are compared by letters and digits only. Every repeated run of consecutive lines is a candidate block; a block scores occurrences x letters, so a four-line chorus sung three times beats a one-line tag. Blocks under 8 letters ("oh oh") and gaps ("♪", blank) never count. Ties go to the earlier block.
- **Start and length:** first occurrence minus a 1.2 s lead-in, never into the end of the previous sung line and never before 0. Length is the block's sung length, held between 10 s and 30 s and inside the track.
- **Fallback (`HookSource.FixedOffset`):** 30% of the way in, 20 s long; 45 s in when the length is unknown; clamped for short tracks. Used for no lyrics, plain or instrumental lyrics, no repeats, or a chorus stamped past the end of the track.
- Lyrics over 600 lines are read only up to 600 (keeps the search cheap; a 3000-line test still finds a repeat).
- Phase 9 reads `Hook.startMs` to seek; nothing calls `findHook` yet.

## Phase 9 notes (done; builds in Gradle; not checked on screen)

`LadderAudition.kt` (pure logic, 20 tests in `LadderAuditionTest.kt`), `LadderRoom.kt` and `MusicLadderScreen.kt` (Compose), `LadderPlay.kt` (`resolveRungAlbum` shared with `playRung`), and `MusicPlayer.play(..., startPositionMs)` (3 new tests in `MusicPlayerTest`; all 19 player tests and the 7 `LadderPlayTest` tests pass with the standalone compiler).

- **Keys:** *(superseded 2026-10-28, see `Docs/HTPC_MUSIC_BLANK_SPACE.md`.)* Left and Right move over the rung cards and are silent. Enter, a pad's A (it injects Enter) or a click plays the rung's hook; a second press on the rung that is playing stops it, and a press on another rung moves on to it. Up and Down turn the familiarity dial, L loves, S skips. Nothing plays by itself: the 450 ms settle pause (`AUDITION_SETTLE_MS`) and its countdown ring were removed. The cursor is kept by candidate id, so a re-rank never moves it onto another artist. The list scrolls to keep it in view. (Originally Up and Down moved over the rungs and the rung under the cursor played after a pause.)
- **What plays:** the artist's album as phase 6 picks it, and on it the first real song (`pickAuditionTrack`: skips unavailable tracks, intros, interludes, skits and tracks under 90 s). The lyric comes from LRCLIB through `LyricsRepository`; `hookFor` gives the start. The song opens at the hook (the player's start position), so no intro is heard first, and it carries on afterwards. A start position is dropped when a different track plays instead of the requested one.
- **Row note:** "Alison · chorus from 0:18", or "... from 1:12 (no chorus found)" for the fixed offset. "Play album" on each row keeps the phase 6 behaviour (the album from its first track).
- **Room:** `LadderRoom` fades the ground to the artist's palette, re-tints the accent (as `MusicAccentScope` does) and runs Now Playing's `StageGlow` at the pace of `stageMotionSpec(motion)`. Reduced motion holds it still; a light theme keeps the app's colours. The palette is the Library's when the artist is saved, else sampled from their Deezer picture (cached for the session). Motion comes from `rungMotion`: the candidate's mood and style, else its top genres, joined with the saved artist's.
- The room follows the cursor at once and silently; audio starts only on a button press (`auditionPress` decides play or stop, 2 tests).
- Known limits: audition uses audio mode and the lyric time is for the studio cut, so a video upload with a different intro can land off the chorus (the lyric auto-sync work would help). It does not stop at the end of the hook. `LadderRoomPalettes` has no size cap (one entry per rung looked at).

## Phase 10 notes (done; builds in Gradle; not checked on screen)

`music/library/LadderAlbumTarget.kt` (pure logic, plus the live TheAudioDB source) and `LadderAlbumTargetTest.kt` (36 tests). Touches: `LadderBuilder.kt` (flavour), `LadderScreenModel.kt` (`LadderRequest.Album`, offers, loader), `LadderProgress.kt` (`progressKey`), `MusicSourceParsers.kt` (`parseMbTagSearch` reads the begin year), `MusicLadderScreen.kt` and `MusicTabScreen.kt` (one more `// fork:music` hook).

- **Target = a blend.** The artist's MusicBrainz tag votes (top tag scaled to 50) plus the record's own genre and style from TheAudioDB (genre starts at 100 and halves down the list; style starts at 30, as it is often broad, like "Rock/Pop"). A record's own genre therefore beats its artist's usual one: a dream pop record by an alternative rock band climbs into dream pop. The result is an `ArtistTarget`, so the target genre, out-from destinations and everything after are the phase 4 code.
- **Flavour (`LadderFlavour`).** The blended sound (genres, plus the record's mood and style) and the record's year. It never changes purity. It adds to a candidate's score for a rung: 0.6 x how much of the record's sound the candidate shares beyond the target genre, plus 0.3 x era fit (1.0 for the same year down to 0 at 25 years apart, from the artist's MusicBrainz begin year). Why lines get a second sentence: "Echoes the record's noise pop." and "Began around the same time (1991)." No flavour is the plain ladder (tested).
- **Year:** the search result's (YouTube Music), else TheAudioDB's.
- **Sources:** the artist through the phase 4 resolver (cached, Deezer fallback), then TheAudioDB by artist name and album title (`pickAudioDbAlbum`: exact title, else with a "(Deluxe Edition)" or "[Remastered]" tail dropped; a near title is never guessed). Answers are kept for the session. TheAudioDB failing is not an error: the ladder is built from the artist's genres and the year, and the screen says so.
- **Into / out from.** Into climbs into the blended genre. Out from leads into another of the record's genres (or a neighbour found by co-occurrence, as in 4b), closeness is measured against the record (mood and style included) and the why lines name the record. The same destination chips work. The record's artist never stands on the ladder.
- **Search card:** on the Albums tab, the first result gives "Climb into the sound of X" and "Climb out from X" (a genre query still gives the genre pill). Progress is stored per record, apart from the artist's ladder.
- **Known limits:** MusicBrainz tag search returns no mood, so a candidate's mood never matches the record's; mood only shapes out-from closeness through the anchor. Era uses the year an artist began, not when a record came out. A record by a band with no TheAudioDB entry follows its artist's genres and its year only.

## HTPC layout notes (2026-10-27)

The ladder screen, the artist page's "Where next?" strip, the shelf card row and the Stage widgets were reshaped for a 10-foot display. Status, the key map, decisions and an audited list of what is not built are in `Docs/HTPC_MUSIC_BLANK_SPACE.md`. The ladder screen builds in Gradle but has not been checked on a display.

- **Ladder screen** (`MusicLadderScreen.kt`): five large cards in a strip, a path rail, a detail panel with a two-ended closeness bar and the familiarity dial, large action buttons, and an end-of-ladder summary (Save all, Climb higher). The four per-row text buttons are gone; the same four `RungFeedback` values still go through `applyFeedback`, so the model and storage are unchanged. Up is "too close" (further out), Down is "too far" (closer).
- **Where next?** (`WhereNextStrip.kt`): `ladderOutFrom` on the artist, built when the artist page opens and cached per artist and direction for the session (`WhereNextCache`).
- **Also built:** the rung number turns into a stop mark while its hook plays, and Play as a playlist in the end summary (`playRungsAsPlaylist`: one album per rung through `MusicPlayer.playAlbums`).

- **Decisions recorded 2026-10-28:** audition on a button press only (done); three input devices (mouse, keyboard, controller); display adjustable between 1080p and 4K (the type scale still needs a UI scale tied to it); artist-page ladder to build lazily with a 7-day disk cache, low-priority queue, Refresh and an off switch (not built; today it builds when the page opens and lives for the session). Love and Skip have no pad button on the ladder screen because a pad's A is Enter and X, Y are app shortcuts.
