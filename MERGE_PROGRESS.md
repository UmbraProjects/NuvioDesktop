# Nuvio fork merge - progress checkpoint (2026-10-24)

Target: upstream 1.16.0 (windows-tv-adaptive) + your music-library fork, with your code moved into
modules and only small tagged hooks (`// fork:<module>`) left in upstream files.
Find every hook with: `grep -rn "fork:" composeApp/src`

## Done so far
- Started from pristine upstream 1.16.0 and added all 94 of your new files (music, preroll/pre-show,
  themesong, trailer quality, tests).
- Found a Stage 1 omission: `composeApp/build.gradle.kts` is changed by you (music source-dir wiring,
  official-backend fallback, CI escape hatches). Upstream did not touch it, so yours is taken as is.
- 22 files taken from you directly (upstream unchanged) or merged cleanly by 3-way merge.
- Strings: your 32 strings moved to `values/strings_preshow.xml` (upstream already uses
  `strings_party.xml`), so `strings.xml` no longer conflicts. No ID collisions.
- Trailer-quality module, relocated out of upstream files:
  - `details/HeroTrailerRanking.kt` - `rankHeroTrailers` built on upstream's `selectHeroTrailer`
    (so upstream's new language-first ranking is kept; no edit to HeroTrailerSelector.kt).
  - `trailer/SharpestTrailerResolver.kt` - the "find a sharper upload" logic; `HeroTrailerMetadataService`
    now needs only a ~6 line hook.
  - `fullCommonMain/.../trailer/YouTubeInnerTube.kt` - YouTube search + YouTube Music requests as
    extension functions; `InAppYouTubeExtractor` carries only a 3-line session accessor and the
    height/reliable stamp. Ported to upstream's new nullable API key.

## Update (later session)
Items 1-5 of the old to-do list are done: HomeHeroTrailerSurface (helper: PreshowPlaybackSupport.kt),
NativePlayerController initMpvOptions + controls.js/css/Bridge merge, MusicPlayerSurface, App.kt
(hooks + features/music/MusicAppHooks.kt + features/preroll/PrerollGateHook.kt), MetaDetailsScreen
(themesong/DetailsThemeSong.kt), TmdbService.fetchBackdropUrls (tmdb/TmdbBackdrops.kt), CHANGELOG (both kept).

## Still to do
- Semantic review of clean merges (Settings*, StreamsScreen, ServerConfiguration).
- Compile locally; fix whatever the compiler finds. Hook inventory: grep -rn "fork:" composeApp/src

## Known limits / decisions
- This sandbox cannot reach Maven/Gradle, so NOTHING has been compiled. Build and run your logic tests locally.
- YouTube search now sends a fixed web client version constant instead of scraping it (see
  `YouTubeInnerTube.kt`); bump if YouTube rejects it.
- The tree in this zip is INCOMPLETE: files listed under "Still to do" are still upstream's version or your
  old version and will not compile until finished.

## Update (phase 8, 2026-10-27)
Music ladder phase 8 (hook finder) checked: `HookFinder.kt` + `HookFinderTest.kt`, 21 tests pass with a standalone Kotlin compiler. Pure logic only; nothing calls it yet (phase 9). Still uncompiled through Gradle: the Compose screen and the other edits listed above.

## Update (phase 9, 2026-10-27)
Music ladder phase 9 (audition) written: `LadderAudition.kt`, `LadderRoom.kt`, edits to `MusicLadderScreen.kt`, `LadderPlay.kt` (`resolveRungAlbum`) and `MusicPlayer.play` (`startPositionMs`). Pure logic and the player are covered by tests run with a standalone Kotlin compiler (20 new audition tests, 3 new player tests, existing player and `LadderPlayTest` still pass). Still uncompiled through Gradle: `LadderRoom.kt`, the key handling and row changes in `MusicLadderScreen.kt`, plus everything listed above. Build locally and run `Ladder*Test`, `HookFinderTest` and `MusicPlayerTest` before phase 10.

## Update (lyric sync, 2026-10-28)
Three lyric sync changes. Pure logic and `MusicLyricSync` are covered by tests run with a standalone Kotlin compiler (45 pass, incl. the existing `LyricSyncTest` and `MusicLyricSyncTest` non-audio tests); the two JSON codec tests, the Compose changes and the LRCLIB client are not compiled through Gradle.
- **Version by stream length:** `Lyrics.Synced` now carries LRCLIB's `duration`. `LyricsRepository.forStream` asks LRCLIB for the other timed versions only when the stream's length differs from the lyrics' (cached per track), and swaps one in only when it is the stream's own cut (within 2.5 s). The sync reference length is now the lyrics' own, not the YouTube track's. `LyricVersion.kt` (`rememberStreamLyrics`) is used by the Stage view and the corner card.
- **Saved offsets:** the person's nudges and taps are stored per `videoId:audio|video` (`LyricSyncStorage`, store `nuvio_music_lyric_sync`) with the lyrics' length; ignored if the lyrics are now a different length. Reset forgets it. Auto-found offsets are not saved.
- **Tap to sync:** "Tap to sync" chip, then tap the line you hear; `tapSyncOffsetMs` (350 ms reaction allowance).
