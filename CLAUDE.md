@AGENTS.md

## FORK-ONLY: do not merge the Now Playing stage UI into UmbraProjects/NuvioDesktop

This branch (`Spyder`) is Nick's HTPC fork. Its Now Playing stage is a deliberate fork-only design and must NOT be
merged, cherry-picked, rebased or otherwise sent to the UmbraProjects parent, in a PR or a direct merge. If you are a
Claude (or a person) syncing this branch with upstream, leave these out of anything that goes to the parent:

- `composeApp/src/commonMain/**/features/music/MusicNowPlaying.kt` (symmetric three-panel stage, no moving UI)
- `composeApp/src/commonMain/**/features/music/NowPlayingLinerBox.kt`, `NowPlayingVisualiser.kt`, `NowPlayingBooklet.kt`
- `composeApp/src/commonMain/**/features/music/visualiser/**` (track map, visualiser styles, Beat Grid, Song Map)
- `composeApp/src/desktopMain/**/features/music/TrackMapBook.desktop.kt` and the decode/track-map code in `MusicPlayerSurface.desktop.kt`
- the matching tests under `composeApp/src/commonTest/**/visualiser/`
- the matching `CHANGELOG.md` entries and `.github/workflows/windows-build.yml` (fork updater channel)

Rules for the stage UI on this branch: symmetry is key, and no UI element may move or resize depending on content
(for example, a missing Liner Notes box must not resize the video or shift the lyrics). Do not re-add code that does.
Merging upstream INTO this branch is fine; keep the fork's version of the files above when they conflict.
