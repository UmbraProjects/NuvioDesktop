package com.nuvio.app.features.tonight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** fork:tonight - the "Cards pick from the top" setting and titles borrowed from Discover's rows (Nov 12). */
class TonightPickFromAndDiscoverTest {
    // Twenty seen films, rated 6.0 to 7.9, so the Safe ranking is a clean ladder.
    private val seen = (0 until 20).map { film("s$it", listOf("Drama"), rating = 6.0 + it * 0.1, watched = true) }
    private val taste = TonightTaste.from(seen)

    private fun safeTitles(pickFrom: Int) =
        (1L..60L).map { seed -> tonightDeal(seen, taste, seed, pickFrom = pickFrom).first { it.slot == TonightSlot.Safe }.title.id }.toSet()

    @Test
    fun pickingFromTheTopOneAlwaysDealsTheBest() {
        assertEquals(setOf("s19"), safeTitles(1))
    }

    @Test
    fun aWiderTopGivesMoreVarietyButStaysWithinIt() {
        val five = safeTitles(5)
        val fifteen = safeTitles(15)
        assertTrue(five.size <= 5)
        assertTrue(fifteen.size > five.size)
        val topFifteen = (5 until 20).map { "s$it" }.toSet()
        assertTrue(topFifteen.containsAll(fifteen))
    }

    @Test
    fun aDiscoverTitleSaysWhyItWasSuggested() {
        val suggested = film("rec", listOf("Drama"), rating = 9.0, origins = setOf(TonightOrigin.Recommended))
            .copy(recommendedBy = "Because you watched Fargo")
        val pool = seen + suggested
        val new = tonightDeal(pool, TonightTaste.from(pool), seed = 1L, pickFrom = 1).first { it.slot == TonightSlot.New }
        assertEquals("rec", new.title.id)
        assertEquals("Because you watched Fargo", new.why)
        assertEquals("BECAUSE YOU WATCHED FARGO", suggested.flickOriginLine())
    }

    @Test
    fun eachSourceHasACardLine() {
        assertEquals("A top pick for you", TonightDiscoverSource.TopPicks.why("Top picks for you"))
        assertEquals("Because you watched Dark", TonightDiscoverSource.BecauseYouWatched.why("Because you watched Dark"))
        assertEquals("More like your favourites", TonightDiscoverSource.Favourites.why(""))
    }
}
