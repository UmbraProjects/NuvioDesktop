package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricFitTest {
    @Test
    fun keepsTheIdealSizeWhenItFits() {
        assertEquals(64f, fitFontSizeSp(64f, 28f) { true })
    }

    @Test
    fun stepsDownUntilItFits() {
        assertEquals(50f, fitFontSizeSp(64f, 28f, 2f) { it <= 50f })
    }

    @Test
    fun stopsAtTheMinimumWhenNothingFits() {
        assertEquals(28f, fitFontSizeSp(64f, 28f, 2f) { false })
        // A step that does not divide the range evenly still lands on the floor, not below it.
        assertEquals(28f, fitFontSizeSp(64f, 28f, 5f) { false })
    }

    @Test
    fun neverRaisesAnIdealBelowTheMinimum() {
        assertEquals(22f, fitFontSizeSp(22f, 28f) { false })
        assertEquals(22f, fitFontSizeSp(22f, 28f) { true })
    }

    @Test
    fun aZeroStepStillEnds() {
        assertEquals(20f, fitFontSizeSp(30f, 20f, 0f) { false })
    }

    @Test
    fun wordCountKeepsAllWhenTheyFit() {
        assertEquals(9, fitWordCount(9) { true })
    }

    @Test
    fun wordCountDropsFromTheEnd() {
        assertEquals(4, fitWordCount(9) { it <= 4 })
    }

    @Test
    fun wordCountKeepsAtLeastOne() {
        assertEquals(1, fitWordCount(9) { false })
        assertEquals(0, fitWordCount(0) { true })
        assertEquals(1, fitWordCount(1) { false })
    }

    @Test
    fun rowCountKeepsEveryRowWhenTheyStaySmallEnough() {
        assertEquals(3, fitRowCount(listOf(80f, 80f, 80f), heightPx = 400f, minPx = 30f))
    }

    @Test
    fun rowCountDropsRowsThatWouldBeShrunkBelowTheMinimum() {
        // Four rows of 100 need ~392 px; with 150 px they would be shrunk to ~38%, under 60.
        val keep = fitRowCount(listOf(100f, 100f, 100f, 100f), heightPx = 150f, minPx = 60f)
        assertTrue(keep in 1..2, "kept $keep")
    }

    @Test
    fun rowCountKeepsOneEvenIfItCannotFit() {
        assertEquals(1, fitRowCount(listOf(100f, 100f), heightPx = 10f, minPx = 90f))
    }

    @Test
    fun ellipsisReplacesTrailingPunctuation() {
        assertEquals("hello…", withEllipsis("hello,"))
        assertEquals("hello world…", withEllipsis("hello world "))
    }

    @Test
    fun loudStackLeavesRoomForTheStripAndTheNextLine() {
        assertEquals(900f - LOUD_STRIP_BAND_DP, loudStackHeightDp(900f, hasNext = false))
        assertEquals(900f - LOUD_STRIP_BAND_DP - LOUD_NEXT_LINE_DP, loudStackHeightDp(900f, hasNext = true))
        assertEquals(80f, loudStackHeightDp(100f, hasNext = true))
        // Under a video the sync strip is at the top right: no band is kept at the foot.
        assertEquals(300f - LOUD_NEXT_LINE_DP, loudStackHeightDp(300f, hasNext = true, stripBandDp = 0f))
    }

    @Test
    fun threePanelsNeedWidthOnly() {
        assertTrue(stageUsesThreePanels(1920f))
        assertTrue(stageUsesThreePanels(1400f))
        assertFalse(stageUsesThreePanels(1399f))
    }
}
