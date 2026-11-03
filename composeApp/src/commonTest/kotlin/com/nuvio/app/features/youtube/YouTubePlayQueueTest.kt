package com.nuvio.app.features.youtube

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubePlayQueueTest {
    private fun video(id: String) = YouTubeVideo(videoId = id, title = id, channel = null, details = null, duration = null, thumbnailUrl = null)

    private val list = listOf(video("a"), video("b"), video("c"))

    @AfterTest
    fun reset() {
        YouTubePlayQueue.stop()
        YouTubeWatchQueue.clear()
    }

    @Test
    fun closingAVideoNearItsEndDoesNotPlayTheNext() {
        YouTubePlayQueue.start("List", list, 0)
        YouTubePlayQueue.onClosedNearEnd("a")

        assertNull(YouTubePlayQueue.takeNext())
        // The playlist stays where it was, for its banner and the next video opened from it.
        assertEquals(0, YouTubePlayQueue.state.value?.index)
    }

    @Test
    fun closingNearTheEndDropsAnAutoplayPick() {
        YouTubePlayQueue.handOverFromPlayer("a", video("b"))
        YouTubePlayQueue.onClosedNearEnd("a")

        assertNull(YouTubePlayQueue.state.value)
        assertNull(YouTubePlayQueue.takeNext())
    }

    @Test
    fun anotherVideoEndingOrOpeningLeavesOrStopsIt() {
        YouTubePlayQueue.start("List", list, 0)
        // Something else closed (not the queue's current video): nothing moves.
        YouTubePlayQueue.onClosedNearEnd("b")
        assertEquals(0, YouTubePlayQueue.state.value?.index)
        // The queue's own video opening keeps it; anything else ends it.
        YouTubePlayQueue.onOpened("a")
        assertEquals(0, YouTubePlayQueue.state.value?.index)
        YouTubePlayQueue.onOpened("zzz")
        assertNull(YouTubePlayQueue.state.value)
    }

    @Test
    fun videosQueuedDuringAVideoPlayAfterIt() {
        YouTubeWatchQueue.add(video("q1"))
        YouTubeWatchQueue.add(video("q2"))

        assertEquals(true, YouTubePlayQueue.followsOn("a"))
        val state = YouTubePlayQueue.state.value
        assertEquals(listOf("a", "q1", "q2"), state?.videos?.map { it.videoId })
        assertEquals(true, state?.watchQueue)
    }

    @Test
    fun theQueueReplacesAutoplaysPickButNotAPickedVideo() {
        YouTubeWatchQueue.add(video("q1"))
        YouTubePlayQueue.handOverFromPlayer("a", video("auto"), autoplay = true)
        YouTubePlayQueue.followsOn("a")
        assertEquals("q1", YouTubePlayQueue.state.value?.videos?.getOrNull(1)?.videoId)

        YouTubePlayQueue.handOverFromPlayer("a", video("picked"))
        YouTubePlayQueue.followsOn("a")
        assertEquals("picked", YouTubePlayQueue.state.value?.videos?.getOrNull(1)?.videoId)
    }

    @Test
    fun nothingQueuedLeavesNothingToFollow() {
        assertEquals(false, YouTubePlayQueue.followsOn("a"))
        assertNull(YouTubePlayQueue.state.value)
    }
}
