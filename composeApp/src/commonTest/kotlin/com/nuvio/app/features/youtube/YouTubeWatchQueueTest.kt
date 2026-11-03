package com.nuvio.app.features.youtube

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeWatchQueueTest {
    private fun video(id: String) = YouTubeVideo(videoId = id, title = id, channel = null, details = null, duration = null, thumbnailUrl = null)

    @AfterTest
    fun reset() {
        YouTubeWatchQueue.clear()
        YouTubePlayQueue.stop()
    }

    @Test
    fun keepsOrderAndRefusesDuplicates() {
        assertTrue(YouTubeWatchQueue.add(video("a")))
        assertTrue(YouTubeWatchQueue.add(video("b")))
        assertFalse(YouTubeWatchQueue.add(video("a")))
        YouTubeWatchQueue.remove("a")
        assertEquals(listOf("b"), YouTubeWatchQueue.videos.value.map { it.videoId })
    }

    @Test
    fun aVideoAddedWhileTheQueuePlaysJoinsItsEnd() {
        YouTubeWatchQueue.add(video("a"))
        YouTubeWatchQueue.add(video("b"))
        YouTubePlayQueue.start(WATCH_QUEUE_NAME, YouTubeWatchQueue.videos.value, 0, watchQueue = true)

        YouTubeWatchQueue.add(video("c"))

        assertEquals(listOf("a", "b", "c"), YouTubePlayQueue.state.value?.videos?.map { it.videoId })
    }

    @Test
    fun anOrdinaryPlaylistDoesNotGrow() {
        YouTubePlayQueue.start("Mix", listOf(video("x"), video("y")), 0)
        YouTubeWatchQueue.add(video("c"))
        assertEquals(listOf("x", "y"), YouTubePlayQueue.state.value?.videos?.map { it.videoId })
    }
}
