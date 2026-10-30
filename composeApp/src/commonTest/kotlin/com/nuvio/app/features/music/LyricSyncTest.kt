package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricSyncTest {
    private fun alignment(
        shiftMs: Double = 12_000.0,
        peak: Double = 0.7,
        prominence: Double = 0.4,
        lines: Int = 16,
        voice: Double = 30.0,
        edge: Boolean = false,
    ) = LyricAlignment(shiftMs, peak, prominence, lines, voice, edge)

    @Test
    fun theSameCutNeedsNoSync() {
        assertNull(lyricSearchRange(200_000L, 200_000L))
        assertNull(lyricSearchRange(200_000L, 201_900L))
        assertNull(lyricSearchRange(200_000L, 198_200L))
    }

    @Test
    fun aLongerStreamStartsLateByAtMostTheExtraLength() {
        val range = assertNotNull(lyricSearchRange(200_000L, 212_000L))
        assertTrue(0L in range && 12_000L in range)
        assertTrue(range.first < 0L && range.first >= -5_000L)
        assertTrue(range.last in 12_000L..17_000L)
    }

    @Test
    fun aShorterStreamSearchesNearZeroAndBelow() {
        val range = assertNotNull(lyricSearchRange(200_000L, 190_000L))
        assertTrue(0L in range && -10_000L in range)
        assertTrue(range.last <= 5_000L)
    }

    @Test
    fun unknownLengthsSearchAWideRange() {
        val range = assertNotNull(lyricSearchRange(0L, 230_000L))
        assertTrue(-20_000L in range && 60_000L in range)
        assertEquals(range, lyricSearchRange(200_000L, 0L))
    }

    @Test
    fun theRangeNeverPassesTheCap() {
        val range = assertNotNull(lyricSearchRange(100_000L, 600_000L))
        assertTrue(range.last <= 120_000L && range.first >= -120_000L)
    }

    @Test
    fun sectionLabelsAndBlankLinesAreNotSung() {
        assertTrue(isSungLyric("hold me close"))
        assertTrue(isSungLyric("(oh oh oh)"))
        assertTrue(!isSungLyric(""))
        assertTrue(!isSungLyric("♪"))
        assertTrue(!isSungLyric("[Chorus]"))
    }

    @Test
    fun sungCuesSkipGapsAndStayBeforeTheNextLine() {
        val lines = listOf(
            LyricLine(10_000L, "first line of the song"),
            LyricLine(16_000L, ""),
            LyricLine(30_000L, "[Verse 2]"),
            LyricLine(31_000L, "second"),
        )
        val cues = lyricSungCues(lines)
        assertEquals(listOf(10_000L, 31_000L), cues.map { it.first })
        assertTrue(cues[0].second > cues[0].first && cues[0].second <= 16_000L)
    }

    @Test
    fun theWindowCoversTheFirstLineWhereverItCouldSit() {
        val lines = listOf(LyricLine(15_000L, "a"), LyricLine(20_000L, "b"))
        val window = assertNotNull(lyricDecodeWindow(lines, -4_000L..16_000L, playedDurationMs = 300_000L))
        assertTrue(window.fromMs <= 15_000L - 4_000L)
        assertTrue(window.fromMs + window.lengthMs >= 15_000L + 16_000L)
        assertTrue(window.lengthMs <= 120_000L)
    }

    @Test
    fun theWindowStopsAtTheEndOfTheStream() {
        val lines = listOf(LyricLine(15_000L, "a"))
        val window = assertNotNull(lyricDecodeWindow(lines, 0L..60_000L, playedDurationMs = 90_000L))
        assertTrue(window.fromMs + window.lengthMs <= 90_000L)
    }

    @Test
    fun noSungLineMeansNoWindow() {
        assertNull(lyricDecodeWindow(listOf(LyricLine(0L, ""), LyricLine(5_000L, "[Intro]")), 0L..10_000L, 200_000L))
    }

    @Test
    fun aConvincingAlignmentIsAccepted() {
        assertEquals(12_000L, acceptLyricAlignment(alignment(shiftMs = 12_000.4)))
        assertEquals(-1_700L, acceptLyricAlignment(alignment(shiftMs = -1_700.0)))
    }

    @Test
    fun aShiftNearNothingIsNoShift() {
        assertEquals(0L, acceptLyricAlignment(alignment(shiftMs = -125.0)))
        assertEquals(0L, acceptLyricAlignment(alignment(shiftMs = 399.0)))
    }

    @Test
    fun weakOrThinEvidenceIsTurnedDown() {
        assertNull(acceptLyricAlignment(alignment(peak = 0.05)))
        assertNull(acceptLyricAlignment(alignment(prominence = 0.03)))
        assertNull(acceptLyricAlignment(alignment(lines = 3)))
        assertNull(acceptLyricAlignment(alignment(voice = 2.0)))
        assertNull(acceptLyricAlignment(alignment(edge = true)))
    }

    @Test
    fun shiftingMovesEveryLineAndNeverBelowZero() {
        val lines = listOf(LyricLine(1_000L, "a"), LyricLine(5_000L, "b"))
        assertEquals(listOf(13_000L, 17_000L), lines.shiftedBy(12_000L).map { it.timeMs })
        assertEquals(listOf(0L, 3_000L), lines.shiftedBy(-2_000L).map { it.timeMs })
        assertTrue(lines.shiftedBy(0L) === lines)
    }

    @Test
    fun onlySyncedLyricsHaveTimingToShift() {
        val synced = Lyrics.Synced(listOf(LyricLine(1_000L, "a")))
        assertEquals(Lyrics.Synced(listOf(LyricLine(4_000L, "a"))), synced.shiftedBy(3_000L))
        val plain = Lyrics.Plain(listOf("a"))
        assertTrue(plain.shiftedBy(3_000L) === plain)
        assertTrue(Lyrics.Instrumental.shiftedBy(3_000L) === Lyrics.Instrumental)
    }

    @Test
    fun offsetsReadAsSignedSeconds() {
        assertEquals("+12.4s", formatLyricOffset(12_400L))
        assertEquals("-0.5s", formatLyricOffset(-500L))
        assertEquals("+0.0s", formatLyricOffset(0L))
    }
}
