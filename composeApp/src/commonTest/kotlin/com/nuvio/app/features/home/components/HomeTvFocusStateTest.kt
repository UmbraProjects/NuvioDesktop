package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTvFocusStateTest {
    @Test
    fun adaptiveScrollTargetsFocusedRowBelowConfiguredHero() {
        assertEquals(
            HomeTvLazyScrollTarget(itemIndex = 1, scrollOffset = -330),
            homeTvLazyScrollTarget(
                sectionIndex = 1,
                heroFocusable = true,
                adaptiveHeroHeightPx = 330,
            ),
        )
        assertEquals(
            HomeTvLazyScrollTarget(itemIndex = 3, scrollOffset = -770),
            homeTvLazyScrollTarget(
                sectionIndex = 3,
                heroFocusable = true,
                adaptiveHeroHeightPx = 770,
            ),
        )
    }

    @Test
    fun adaptiveHeroTargetReturnsToTopWithoutClearance() {
        assertEquals(
            HomeTvLazyScrollTarget(itemIndex = 0, scrollOffset = 0),
            homeTvLazyScrollTarget(
                sectionIndex = 0,
                heroFocusable = true,
                adaptiveHeroHeightPx = 770,
            ),
        )
    }

    @Test
    fun nonAdaptiveScrollTargetPreservesExistingRowMapping() {
        assertEquals(
            HomeTvLazyScrollTarget(itemIndex = 2, scrollOffset = 0),
            homeTvLazyScrollTarget(
                sectionIndex = 3,
                heroFocusable = true,
                adaptiveHeroHeightPx = 0,
            ),
        )
    }

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
