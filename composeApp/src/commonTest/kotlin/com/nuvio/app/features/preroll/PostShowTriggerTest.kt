package com.nuvio.app.features.preroll

import com.nuvio.app.features.player.skip.SkipInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostShowTriggerTest {
    private val twoHours = 7_200_000L

    private fun outro(startSec: Double, endSec: Double) = SkipInterval(startSec, endSec, "outro", "introdb")
    private fun scene(startSec: Double) = SkipInterval(startSec, startSec + 30, "post-credits", "introdb")

    @Test
    fun `starts at the outro marker and ends at the end of the file`() {
        val w = postShowWindow(listOf(outro(6_600.0, 7_150.0)), twoHours)!!
        assertEquals(6_600_000L, w.startMs)
        assertEquals(twoHours, w.endMs)
    }

    @Test
    fun `ends where a post-credits scene begins`() {
        val w = postShowWindow(listOf(outro(6_600.0, 6_900.0), scene(7_000.0)), twoHours)!!
        assertEquals(7_000_000L, w.endMs)
    }

    @Test
    fun `an outro that starts too early is not the credits`() {
        val w = postShowWindow(listOf(outro(3_000.0, 3_100.0)), twoHours)!!
        assertEquals((twoHours * 0.96).toLong(), w.startMs)
    }

    @Test
    fun `without markers falls back to the last four percent`() {
        val w = postShowWindow(emptyList(), twoHours)!!
        assertEquals((twoHours * 0.96).toLong(), w.startMs)
        assertEquals(twoHours, w.endMs)
    }

    @Test
    fun `unknown duration or a tiny window gives nothing`() {
        assertNull(postShowWindow(emptyList(), 0L))
        assertNull(postShowWindow(listOf(outro(7_190.0, 7_200.0)), twoHours))
    }

    @Test
    fun `only arriving by playing counts`() {
        val w = PostShowWindow(6_600_000L, twoHours)
        assertTrue(postShowArrivedByPlaying(6_599_000L, 6_600_500L, w))
        assertFalse(postShowArrivedByPlaying(1_000_000L, 6_650_000L, w), "a seek")
        assertFalse(postShowArrivedByPlaying(6_700_000L, 6_701_000L, w), "already inside")
        assertFalse(postShowArrivedByPlaying(6_600_500L, 6_599_000L, w), "backwards")
    }

    @Test
    fun `window contains is half open`() {
        val w = PostShowWindow(10L, 20L)
        assertTrue(10L in w); assertFalse(20L in w); assertFalse(9L in w)
    }

    @Test
    fun `prefetch starts at half the runtime`() {
        assertFalse(shouldPrefetchPostShow(3_599_999L, twoHours))
        assertTrue(shouldPrefetchPostShow(3_600_000L, twoHours))
        assertFalse(shouldPrefetchPostShow(5_000_000L, 0L))
    }
}
