package com.nuvio.app.features.music.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Not an assertion: draws the artist room for a sample artist and saves a PNG, so the layout can be
 * looked at without running the app (artwork does not load here, so the fallbacks show). Written to
 * build/artist-room-preview.png.
 */
@OptIn(ExperimentalTestApi::class)
class ArtistRoomPreviewTest {
    @Test
    fun drawsTheRoom() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val releases = (0 until 9).map { i ->
            LibraryRelease(
                key = "r$i",
                title = listOf("In Our Bones", "Past Lives", "fever", "Bonus Tracks", "Gravity (The Acoustic Sessions)", "Wildfire", "Not Over You", "Good Time", "Till Death & Back")[i],
                kind = if (i % 3 == 0) ReleaseKind.Album else if (i % 3 == 1) ReleaseKind.Single else ReleaseKind.EP,
                year = "${2012 + i * 2}",
                ytBrowseId = if (i == 4) null else "MPREb_$i",
                trackCount = 10,
            )
        }
        val artist = LibraryArtist(
            id = "UCx",
            name = "Against The Current",
            type = "Group",
            country = "United States",
            beginYear = "2011",
            genres = listOf("rock", "alternative rock", "pop"),
            fans = 92_100,
            rating = 4.8,
            releases = releases,
            sources = listOf("YouTube Music", "MusicBrainz", "Deezer"),
            biography = "Against the Current is an American pop rock band from Poughkeepsie, New York.",
            topAlbumKeys = listOf("r0", "r3", "r6"),
        )
        val discography = buildDiscography(releases, playableOnly = false, filter = ReleaseFilter.All)
        setContent {
            NuvioTheme {
                Box(Modifier.size(1600.dp, 900.dp)) {
                    ArtistRoom(
                        artist = artist, discography = discography, latest = releases.last(), playTarget = releases.last(),
                        canShuffle = true, notice = null, saved = true, refreshing = null, working = false,
                        filter = ReleaseFilter.All, playableOnly = false, demosOpen = false,
                        onBack = {}, onPlay = {}, onShuffle = {}, onPlayTop = {}, topLabel = "Top 5", onAbout = {}, onLinks = {},
                        onOpenArtist = {}, onRefresh = {}, onRemove = {}, onOpen = {}, onPlayableOnly = {}, onFilter = {}, onToggleDemos = {},
                    )
                }
            }
        }
        waitForIdle()
        val out = File("build/artist-room-preview.png").absoluteFile
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
        // And further down: the releases with the timeline beside them.
        onNodeWithTag("artistTimeline").performScrollTo()
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/artist-room-releases.png").absoluteFile)
        println("PREVIEW ${out.path}")
    }

    @Test
    fun theFilterButtonBringsItsPanelIntoView() = runDesktopComposeUiTest(width = 1600, height = 900) {
        val releases = (0 until 30).map { i -> LibraryRelease(key = "r$i", title = "Release $i", kind = ReleaseKind.Single, year = "${1990 + i}", ytBrowseId = "MPREb_$i") }
        val artist = LibraryArtist(id = "UCx", name = "Someone", releases = releases)
        val discography = buildDiscography(releases, playableOnly = true, filter = ReleaseFilter.All)
        setContent {
            NuvioTheme {
                ArtistRoom(
                    artist = artist, discography = discography, latest = releases.last(), playTarget = releases.last(),
                    canShuffle = true, notice = null, saved = true, refreshing = null, working = false,
                    filter = ReleaseFilter.All, playableOnly = true, demosOpen = false,
                    onBack = {}, onPlay = {}, onShuffle = {}, onPlayTop = {}, topLabel = null, onAbout = {}, onLinks = {},
                    onOpenArtist = {}, onRefresh = {}, onRemove = {}, onOpen = {}, onPlayableOnly = {}, onFilter = {}, onToggleDemos = {},
                )
            }
        }
        waitForIdle()
        onNodeWithContentDescription("Filter releases").performClick()
        waitForIdle()
        // The panel's playable / all switch is on screen, not below the fold.
        onNodeWithText("Playable 30", substring = true).assertIsDisplayed()
    }
}
