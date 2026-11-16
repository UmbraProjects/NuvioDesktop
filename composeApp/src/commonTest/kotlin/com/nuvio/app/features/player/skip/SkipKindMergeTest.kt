package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipKindMergeTest {
    private fun seg(start: Double, end: Double, type: String, provider: String) =
        SkipInterval(startTime = start, endTime = end, type = type, provider = provider)

    @Test
    fun laterSourceFillsOnlyMissingKinds() {
        val introDb = listOf(seg(90.0, 180.0, "intro", "introdb"))
        val animeSkip = listOf(
            seg(0.0, 85.0, "recap", "animeskip"),
            seg(95.0, 175.0, "op", "animeskip"), // an intro again: IntroDB's stays
            seg(1300.0, 1390.0, "ed", "animeskip"),
        )
        val merged = fillMissingSkipKinds(introDb, animeSkip)
        assertEquals(listOf("recap", "intro", "ed"), merged.map { it.type })
        assertEquals("introdb", merged.first { it.type == "intro" }.provider)
    }

    @Test
    fun aFillerThatOverlapsAKeptSegmentIsDropped() {
        val kept = listOf(seg(0.0, 100.0, "intro", "skipdb"))
        val merged = fillMissingSkipKinds(kept, listOf(seg(50.0, 120.0, "recap", "animeskip")))
        assertEquals(kept, merged)
    }

    @Test
    fun touchingAtASeamIsFine() {
        val kept = listOf(seg(85.0, 175.0, "intro", "skipdb"))
        val merged = fillMissingSkipKinds(kept, listOf(seg(0.0, 86.0, "recap", "animeskip")))
        assertEquals(2, merged.size)
    }

    @Test
    fun outroSourcesFollowTheMode() {
        val api = seg(1300.0, 1390.0, "ed", "animeskip")
        assertTrue(SkipAutoAcceptMode.ANY_SOURCE.acceptsSource(api))
        assertFalse(SkipAutoAcceptMode.CHAPTERS.acceptsSource(api))
        assertFalse(SkipAutoAcceptMode.MANUAL.acceptsSource(api))
        // accepts() itself still never takes an outro: that is the opt-in's job.
        assertFalse(SkipAutoAcceptMode.ANY_SOURCE.accepts(api))
    }
}
