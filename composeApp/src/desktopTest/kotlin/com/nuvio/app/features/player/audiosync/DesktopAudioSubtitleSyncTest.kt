package com.nuvio.app.features.player.audiosync

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopAudioSubtitleSyncTest {
    private val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 2_950.0)))

    private fun estimate(shiftMs: Double, scale: Double = 1.0) = SubtitleAudioAligner.Estimate(
        scale = scale,
        shiftMs = shiftMs,
        peak = 0.6,
        prominence = 0.2,
        analysedSeconds = 60.0,
        speechSeconds = 30.0,
        cueCount = 36,
    )

    @Test
    fun staleWithdrawalThatStillAgreesIsHeld() {
        val hold = EstimateHold()
        assertTrue(hold.onWithdrawn(model, estimate(2_934.0)))
        assertSame(model, hold.held)
    }

    @Test
    fun withdrawalThatMovedIsNotHeld() {
        val hold = EstimateHold()
        assertFalse(hold.onWithdrawn(model, estimate(4_200.0)))
        assertFalse(hold.onWithdrawn(model, estimate(2_950.0, scale = 25.0 / 24.0)))
        assertNull(hold.held)
    }

    @Test
    fun heldEstimateGoesAfterRepeatedContradiction() {
        val hold = EstimateHold()
        hold.onWithdrawn(model, estimate(2_940.0))
        assertFalse(hold.onSearching(estimate(9_000.0)))
        assertFalse(hold.onSearching(estimate(2_900.0)), "agreement resets the count")
        assertFalse(hold.onSearching(estimate(9_000.0)))
        assertFalse(hold.onSearching(estimate(-4_000.0)))
        assertTrue(hold.onSearching(estimate(9_000.0)))
        assertNull(hold.held)
    }

    @Test
    fun anotherSubtitleReusesTheStreamsSpots() = runBlocking {
        val dialogue = dialogue(Random(3))
        val decoded = ArrayList<Long>()
        // Unreadable spots still count as read: this checks which spots are requested, not VAD.
        suspend fun sync(sourceKey: String, cues: List<Triple<Long, Long, String>>) = DesktopAudioSubtitleSync.run(
            sourceKey = sourceKey,
            dialogue = cues,
            durationMs = FILM_MS,
            bytesPerSecond = null,
            positionMs = { 0L },
            decode = { startMs, _ -> decoded += startMs; null },
            onEvent = {},
        )

        assertIs<DesktopAudioSubtitleSync.Outcome.NoMatch>(sync("stream-a", dialogue))
        val firstRun = decoded.toList()
        assertEquals(12, firstRun.size, "three rounds of four")
        assertEquals(firstRun.size, firstRun.distinct().size, "no spot read twice")

        decoded.clear()
        val shifted = dialogue.map { (a, b, t) -> Triple(a + 1_500L, b + 1_500L, t) }
        assertIs<DesktopAudioSubtitleSync.Outcome.NoMatch>(sync("stream-a", shifted))
        assertTrue(decoded.isEmpty(), "same stream re-read $decoded")

        decoded.clear()
        sync("stream-b", dialogue)
        assertEquals(12, decoded.size, "a new stream samples afresh")
    }

    private fun dialogue(random: Random): List<Triple<Long, Long, String>> {
        val cues = ArrayList<Triple<Long, Long, String>>()
        var at = 60_000L
        while (at < FILM_MS - 60_000L) {
            val length = random.nextLong(800L, 4_000L)
            cues += Triple(at, at + length, "line ${cues.size}")
            at += length + random.nextLong(500L, 6_000L)
        }
        return cues
    }

    private companion object {
        const val FILM_MS = 100 * 60_000L
    }
}
