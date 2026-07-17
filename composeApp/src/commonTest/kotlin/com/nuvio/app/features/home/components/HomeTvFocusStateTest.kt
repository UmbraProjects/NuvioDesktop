package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTvFocusStateTest {
    @Test
    fun restoredItemIsReportedWhenRestoredSectionBecomesActive() {
        val changes = mutableListOf<Pair<Int, Int>>()
        val focus = HomeTvFocusState { section, item -> changes += section to item }

        focus.restoreItemIndex(section = 3, index = 7)
        focus.sectionIndex = 3

        assertEquals(7, focus.itemIndex)
        assertEquals(3 to 7, changes.last())
    }

    @Test
    fun itemMovesArePersistedForTheActiveSection() {
        val changes = mutableListOf<Pair<Int, Int>>()
        val focus = HomeTvFocusState { section, item -> changes += section to item }
        focus.sectionIndex = 2

        focus.moveItem(delta = 1, itemCount = 10)

        assertEquals(1, focus.itemIndex)
        assertEquals(2 to 1, changes.last())
    }
}
