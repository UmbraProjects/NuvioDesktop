package com.nuvio.app.features.youtube

import com.nuvio.app.features.player.PlayerBrowsePip
import com.nuvio.app.features.player.desktop.desktopAppFullscreenState
import com.nuvio.app.features.player.desktop.desktopPictureInPictureState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeWatchFullscreenTest {
    @BeforeTest
    fun setUp() {
        desktopPictureInPictureState.value = false
        desktopAppFullscreenState.value = false
        PlayerBrowsePip.setActive(false)
        YouTubeWatchLayout.end()
    }

    @AfterTest
    fun tearDown() {
        desktopPictureInPictureState.value = false
        desktopAppFullscreenState.value = false
        PlayerBrowsePip.setActive(false)
        YouTubeWatchLayout.end()
    }

    @Test
    fun fullscreenButtonInClassicPipDoesNotLeaveWatchPageWindowedMode() {
        YouTubeWatchLayout.begin()
        YouTubeWatchLayout.backToPage()
        assertTrue(YouTubeWatchLayout.available.value)
        assertTrue(YouTubeWatchLayout.windowed.value)

        desktopPictureInPictureState.value = true
        assertFalse(handleYouTubeFullscreenButton(null))
        assertTrue(YouTubeWatchLayout.windowed.value)
        assertFalse(YouTubeWatchLayout.fullFromPage)
    }

    @Test
    fun fullscreenButtonInBrowsePipDoesNotLeaveWatchPageWindowedMode() {
        YouTubeWatchLayout.begin()
        YouTubeWatchLayout.backToPage()
        assertTrue(YouTubeWatchLayout.windowed.value)

        PlayerBrowsePip.setActive(true)
        assertFalse(handleYouTubeFullscreenButton(null))
        assertTrue(YouTubeWatchLayout.windowed.value)
        assertFalse(YouTubeWatchLayout.fullFromPage)
    }

    @Test
    fun escapeInPipDoesNotMutateWatchPageState() {
        YouTubeWatchLayout.begin()
        YouTubeWatchLayout.goFull(madeFullscreen = false)
        assertFalse(YouTubeWatchLayout.windowed.value)

        desktopPictureInPictureState.value = true
        assertFalse(handleYouTubeEscape(null))
        assertFalse(YouTubeWatchLayout.windowed.value)
        assertTrue(YouTubeWatchLayout.fullFromPage)
    }
}
