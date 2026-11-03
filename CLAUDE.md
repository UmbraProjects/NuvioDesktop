@AGENTS.md

## FORK UI: warn before merging the Now Playing stage into UmbraProjects/NuvioDesktop

UmbraProjects may merge from this branch (`Spyder`, Nick's HTPC fork); that is fine. But the Now Playing stage here is a
deliberate fork-only design that does not exist upstream, so if you are a Claude merging, cherry-picking or opening a PR
from this branch into the parent, STOP and warn the person you are working for before it goes in: tell them which of
these UI changes are in the merge and that they are fork-only, and let them decide. Do not silently take or drop them.

- `composeApp/src/commonMain/**/features/music/MusicNowPlaying.kt` (symmetric three-panel stage, no moving UI)
- `composeApp/src/commonMain/**/features/music/NowPlayingLinerBox.kt`, `NowPlayingVisualiser.kt`, `NowPlayingBooklet.kt`
- `composeApp/src/commonMain/**/features/music/visualiser/**` (track map, visualiser styles, Beat Grid, Song Map)
- `composeApp/src/desktopMain/**/features/music/TrackMapBook.desktop.kt` and the decode/track-map code in `MusicPlayerSurface.desktop.kt`
- the matching tests under `composeApp/src/commonTest/**/visualiser/`
- the album page redesign (equal side columns, Essentials rail, In brief card, popularity bars): `MusicAlbumScreen.kt`, `AlbumEssentialsColumn.kt`, `AlbumInBrief.kt`, `library/AlbumEssentials.kt`, `booklet/StartHere.kt`, and their tests
- the matching `CHANGELOG.md` entries and `.github/workflows/windows-build.yml` (fork updater channel)

Rules for the stage UI on this branch: symmetry is key, and no UI element may move or resize depending on content
(for example, a missing Liner Notes box must not resize the video or shift the lyrics). Do not re-add code that does.
When merging upstream INTO this branch, keep the fork's version of the files above if they conflict.
