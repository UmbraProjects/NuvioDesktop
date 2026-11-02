package com.nuvio.app.features.youtube

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubePlayQueueTest {
    private fun video(id: String) = YouTubeVideo(videoId = id, title = id, channel = null, details = null, duration = null, thumbnailUrl = null)

    private val list = listOf(video("a"), video("b"), video("c"))

    @AfterTest
    fun reset() = YouTubePlayQueue.stop()

    @Test
    fun aFinishedVideoHandsOutTheNextOnce() {
        YouTubePlayQueue.start("List", list, 0)
        YouTubePlayQueue.onFinished("a")

        assertEquals("b", YouTubePlayQueue.takeNext()?.videoId)
        assertNull(YouTubePlayQueue.takeNext())
        assertEquals(1, YouTubePlayQueue.state.value?.index)
    }

    @Test
    fun theLastVideoEndsTheQueue() {
        YouTubePlayQueue.start("List", list, 2)
        YouTubePlayQueue.onFinished("c")

        assertNull(YouTubePlayQueue.state.value)
        assertNull(YouTubePlayQueue.takeNext())
    }

    @Test
    fun anotherVideoEndingOrOpeningLeavesOrStopsIt() {
        YouTubePlayQueue.start("List", list, 0)
        // Something else finished (not the queue's current video): nothing moves.
        YouTubePlayQueue.onFinished("b")
        assertEquals(0, YouTubePlayQueue.state.value?.index)
        // The queue's own video opening keeps it; anything else ends it.
        YouTubePlayQueue.onOpened("a")
        assertEquals(0, YouTubePlayQueue.state.value?.index)
        YouTubePlayQueue.onOpened("zzz")
        assertNull(YouTubePlayQueue.state.value)
    }
}
