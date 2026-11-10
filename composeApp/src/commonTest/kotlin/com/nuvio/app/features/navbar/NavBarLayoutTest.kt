package com.nuvio.app.features.navbar

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class NavBarLayoutTest {

    @Test
    fun theDefaultPutsFilmsFirstAndMusicThenGamesLast() {
        assertEquals(DEFAULT_NAV_BAR_ORDER, parseNavBarOrder(null))
        assertEquals(
            listOf("home", "search", "tonight", "discover", "library", "livetv", "youtube", "music", "games"),
            parseNavBarOrder("").map { it.key },
        )
    }

    @Test
    fun aSavedOrderKeepsItsOrderAndGainsNewEntriesInTheirDefaultPlace() {
        // Saved before Games existed, with Music moved to the front: Games follows Music, the entry
        // it comes after by default, wherever Music now is.
        val parsed = parseNavBarOrder("music,home,search,discover,library,livetv,youtube,bogus")
        assertEquals(
            listOf("music", "games", "home", "search", "tonight", "discover", "library", "livetv", "youtube"),
            parsed.map { it.key },
        )
    }

    @Test
    fun slotsNarrowAsTheBarFillsButNotPastAFloor() {
        assertEquals(64.dp, navBarSlotWidth(4))
        assertEquals(64.dp, navBarSlotWidth(6))
        assertEquals(60.dp, navBarSlotWidth(7))
        assertEquals(52.dp, navBarSlotWidth(9))
        assertEquals(50.dp, navBarSlotWidth(12))
    }
}
