package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkipDbSegmentMappingTest {

    private fun segment(
        startMs: Long?,
        endMs: Long?,
        match: String? = SkipDbMatch.EXACT,
    ) = SkipDbSegment(startMs = startMs, endMs = endMs, match = match)

    @Test
    fun `maps every populated kind in one answer`() {
        val intervals = SkipDbSegments(
            intro = segment(61_000, 91_000),
            recap = segment(5_000, 35_000),
            outro = segment(2_551_000, 2_588_000),
            preview = segment(2_588_000, 2_600_000),
        ).toSkipIntervals()

        assertEquals(listOf("intro", "recap", "outro", "preview"), intervals.map { it.type })
        assertTrue(intervals.all { it.provider == SKIPDB_PROVIDER })
    }

    @Test
    fun `converts milliseconds to seconds`() {
        val interval = SkipDbSegments(intro = segment(405_500, 428_500)).toSkipIntervals().single()

        assertEquals(405.5, interval.startTime)
        assertEquals(428.5, interval.endTime)
    }

    @Test
    fun `keeps kinds SkipDB has no data for out of the result`() {
        val intervals = SkipDbSegments(intro = segment(61_000, 91_000)).toSkipIntervals()

        assertEquals(listOf("intro"), intervals.map { it.type })
    }

    @Test
    fun `drops out-of-range answers so playback is not skipped against a different cut`() {
        val intervals = SkipDbSegments(
            intro = segment(405_500, 428_500, match = SkipDbMatch.OUT_OF_RANGE),
            outro = segment(2_551_000, 2_588_000, match = SkipDbMatch.EXACT),
        ).toSkipIntervals()

        assertEquals(listOf("outro"), intervals.map { it.type })
    }

    @Test
    fun `keeps shifted and agnostic answers`() {
        val intervals = SkipDbSegments(
            intro = segment(405_500, 428_500, match = SkipDbMatch.SHIFTED),
            outro = segment(2_551_000, 2_588_000, match = SkipDbMatch.AGNOSTIC),
        ).toSkipIntervals()

        assertEquals(listOf("intro", "outro"), intervals.map { it.type })
    }

    @Test
    fun `treats the zero sentinel as confirmation there is no segment, not an empty one`() {
        val intervals = SkipDbSegments(
            intro = segment(0, 0),
            outro = segment(2_551_000, 2_588_000),
        ).toSkipIntervals()

        assertEquals(listOf("outro"), intervals.map { it.type })
    }

    @Test
    fun `drops malformed and incomplete segments`() {
        val intervals = SkipDbSegments(
            intro = segment(91_000, 61_000),
            recap = segment(5_000, null),
            outro = segment(null, 2_588_000),
            preview = segment(100_000, 100_000),
        ).toSkipIntervals()

        assertTrue(intervals.isEmpty(), "expected no intervals, got $intervals")
    }

    @Test
    fun `an answer with nothing in it yields no intervals so the next provider is tried`() {
        assertTrue(SkipDbSegments().toSkipIntervals().isEmpty())
    }
}
