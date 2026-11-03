package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeGridFocusTest {

    @Test
    fun aRowMoveKeepsTheColumn() {
        // Four across: item 2 of row 5 (index 17) goes to item 2 of row 6 (index 21) and back.
        assertEquals(21, gridColumnTarget(from = 17, down = true, columns = 4, itemCount = 40))
        assertEquals(17, gridColumnTarget(from = 21, down = false, columns = 4, itemCount = 40))
    }

    @Test
    fun aShorterLastRowTakesTheLastItem() {
        // 10 items, four across: the last row holds 8 and 9.
        assertEquals(9, gridColumnTarget(from = 7, down = true, columns = 4, itemCount = 10))
        assertNull(gridColumnTarget(from = 9, down = true, columns = 4, itemCount = 10))
    }

    @Test
    fun theFirstRowLeavesUpToTheHeader() {
        assertNull(gridColumnTarget(from = 2, down = false, columns = 4, itemCount = 10))
    }
}
