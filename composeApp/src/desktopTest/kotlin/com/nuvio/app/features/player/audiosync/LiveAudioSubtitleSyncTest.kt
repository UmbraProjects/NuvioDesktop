package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveAudioSubtitleSyncTest {
    private val lines = listOf(
        Triple(10_000L, 12_000L, "early"),
        Triple(2_399_000L, 2_401_000L, "straddles the tune-in"),
        Triple(2_500_000L, 2_503_000L, "after"),
    )

    @Test
    fun `lines are moved by the guide's offset and those already past are dropped`() {
        val moved = LiveAudioSubtitleSync.shifted(lines, 2_400_000L)
        assertEquals(listOf(Triple(0L, 1_000L, "straddles the tune-in"), Triple(100_000L, 103_000L, "after")), moved)
    }

    @Test
    fun `the guesses are the guide's offset at the film's speed and sped up to 25 frames`() {
        val guesses = LiveAudioSubtitleSync.guesses(2_400_000L)
        assertEquals(2, guesses.size)
        assertEquals(2_400_000L, guesses[0])
        assertTrue(abs(guesses[1] - 2_502_502L) <= 1, guesses.toString())
    }

    @Test
    fun `a mapping found on moved lines places the film's own lines the same`() {
        val offset = 2_400_000L
        val scale = 23.976 / 25.0
        val onMoved = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, scale, 1_500.0)))
        val onFilm = LiveAudioSubtitleSync.unshift(onMoved, offset)
        for (moved in listOf(0L, 60_000L, 600_000L)) {
            val film = moved + offset
            val expected = onMoved.mediaTimeUs(moved * 1_000L)!!
            val actual = onFilm.mediaTimeUs(film * 1_000L)!!
            assertTrue(abs(expected - actual) <= 1_000L, "at $moved: $expected vs $actual")
        }
    }
}
