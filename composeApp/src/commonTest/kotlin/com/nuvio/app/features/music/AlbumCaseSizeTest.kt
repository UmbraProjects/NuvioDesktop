package com.nuvio.app.features.music

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlbumCaseSizeTest {
    @Test
    fun caseKeepsItsOldSizeUpToTheGrowthPoint() {
        assertEquals(340f, albumCaseSize(840.dp).value)
        assertEquals(340f, albumCaseSize(1200.dp).value)
        assertEquals(340f, albumCaseSize(1500.dp).value)
    }

    @Test
    fun caseGrowsWithTheWindowAndStopsAtItsMaximum() {
        assertTrue(albumCaseSize(1700.dp).value > 340f)
        assertTrue(albumCaseSize(1920.dp).value in 415f..420f)
        assertEquals(420f, albumCaseSize(2560.dp).value)
        assertEquals(420f, albumCaseSize(3840.dp).value)
        var last = 0f
        for (w in 840..3000 step 20) {
            val size = albumCaseSize(w.dp).value
            assertTrue(size >= last, "the case never shrinks as the window widens ($w)")
            last = size
        }
    }

    @Test
    fun objectColumnKeepsItsOldWidthAtTheSmallestCase() {
        assertEquals(520f, albumObjectColumnWidth(340.dp).value)
        assertEquals(624f, albumObjectColumnWidth(420.dp).value, 0.01f)
    }

    @Test
    fun theColumnAlwaysHoldsTheCaseAndItsDisc() {
        // Column padding is 32 dp at the start and 12 dp at the end; the disc slides out to 1.3 times the case.
        for (w in 840..3000 step 20) {
            val case = albumCaseSize(w.dp)
            val room = albumObjectColumnWidth(case).value - 44f
            assertTrue(room >= case.value * 1.3f, "case and disc fit the column at $w")
        }
    }

    @Test
    fun theRecordColumnStaysWideEnoughOnWideWindowsWithMoreFrom() {
        // With More from beside it: window - object column - More from column - the record's own side paddings.
        val moreColumn = 150f * 2 + 16f + 56f
        for (w in 1600..3000 step 100) {
            val column = albumObjectColumnWidth(albumCaseSize(w.dp)).value
            val end = (column + 12f - moreColumn).coerceAtLeast(24f)
            val record = w - column - moreColumn - 12f - end
            assertTrue(record >= 480f, "tracks keep their 480 dp at $w (got $record)")
        }
    }
}
