package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PosterLyricsTest {
    private val word = LyricWord("hello", from = 0.5f, to = 0.7f)

    @Test
    fun aWordWaitsUntilItIsSungThenLandsOverItsLandingTime() {
        assertEquals(0f, posterWordArrival(word, lineProgress = 0.4f, lineDurationMs = 4_000))
        assertEquals(0f, posterWordArrival(word, 0.5f, 4_000))
        // 190 ms after it starts, half way through its 380 ms landing.
        assertEquals(0.5f, posterWordArrival(word, 0.5f + 190f / 4_000f, 4_000), 0.001f)
        assertEquals(1f, posterWordArrival(word, 0.9f, 4_000))
    }

    @Test
    fun theLandingOvershootsAndSettlesOnOne() {
        assertEquals(0f, backOut(0f), 0.0001f)
        assertEquals(1f, backOut(1f), 0.0001f)
        assertTrue((1..9).map { backOut(it / 10f) }.any { it > 1f })
    }

    @Test
    fun tiltIsSteadyAndSmall() {
        for (line in 0..30) for (w in 0..12) {
            val t = posterTiltDegrees(line, w)
            assertEquals(t, posterTiltDegrees(line, w))
            assertTrue(kotlin.math.abs(t) <= 4.5f + 0.001f)
        }
    }

    @Test
    fun longerLinesAreSetSmaller() {
        assertTrue(posterFontSizeSp(10) > posterFontSizeSp(40))
        assertTrue(posterFontSizeSp(40) > posterFontSizeSp(60))
        assertTrue(posterFontSizeSp(60) > posterFontSizeSp(100))
    }
}

class LoudLyricsTest {
    @Test
    fun oneAndTwoWordLinesGetAChunkPerWord() {
        assertEquals(listOf(listOf(0)), loudChunks(listOf(5)))
        assertEquals(listOf(listOf(0), listOf(1)), loudChunks(listOf(3, 6)))
    }

    @Test
    fun longerLinesAreCutIntoTwoOrThreeChunksInOrder() {
        val five = loudChunks(listOf(2, 3, 3, 4, 5))
        assertEquals(2, five.size)
        val nine = loudChunks(listOf(4, 3, 5, 2, 8, 2, 8, 2, 4))
        assertEquals(3, nine.size)
        assertEquals((0..8).toList(), nine.flatten())
        assertEquals((0..4).toList(), five.flatten())
    }

    @Test
    fun noWordsNoChunks() {
        assertEquals(emptyList(), loudChunks(emptyList()))
    }

    @Test
    fun neighbouringLinesDifferInFaceColourAndEntrance() {
        for (i in 0..30) {
            assertNotEquals(loudFaceFor(i), loudFaceFor(i + 1))
            assertNotEquals(loudColourFor(i), loudColourFor(i + 1))
            assertNotEquals(loudEntranceFor(i), loudEntranceFor(i + 1))
        }
    }

    @Test
    fun aWordIsInvisibleBeforeItIsSungAndSettledAfter() {
        for (entrance in LoudEntrance.entries) {
            assertEquals(0f, loudPose(entrance, 0f).alpha)
            val done = loudPose(entrance, 1f)
            assertEquals(0f, done.dx, 0.001f)
            assertEquals(0f, done.dy, 0.001f)
            assertEquals(0f, done.rotation, 0.001f)
            assertEquals(1f, done.scale, 0.001f)
            assertEquals(1f, done.alpha, 0.001f)
        }
    }

    @Test
    fun theStyleChipCyclesThroughAllThree() {
        assertEquals(LyricStyle.Calm, LyricStyle.Scroll.next())
        assertEquals(LyricStyle.Loud, LyricStyle.Calm.next())
        assertEquals(LyricStyle.Scroll, LyricStyle.Loud.next())
    }

    @Test
    fun capitalsFacesAreTheComicAndPosterOnes() {
        assertEquals(listOf(false, true, true, false), (0..3).map(::loudFaceIsCapitals))
    }
}
