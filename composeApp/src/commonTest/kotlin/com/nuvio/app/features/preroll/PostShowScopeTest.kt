package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostShowScopeTest {
    /** [seasons] seasons of [perSeason] episodes, everything before (watchedUpToSeason, watchedUpToEpisode) watched. */
    private fun show(
        seasons: Int = 3,
        perSeason: Int = 10,
        watchedThrough: Pair<Int, Int> = seasons to perSeason - 1,
    ): List<PostShowEpisode> = (1..seasons).flatMap { s ->
        (1..perSeason).map { e ->
            PostShowEpisode(s, e, watched = s < watchedThrough.first || (s == watchedThrough.first && e <= watchedThrough.second))
        }
    }

    @Test
    fun `a mid-season episode is not a finale`() {
        assertNull(postShowScopeFor("Ended", show(), 2, 5, null))
    }

    @Test
    fun `last episode of an earlier season is a season finale`() {
        val scope = postShowScopeFor("Ended", show(watchedThrough = 1 to 9), 1, 10, "Pilot Again")
        assertEquals(PostShowScope.SeasonFinale(1, 10, "Pilot Again"), scope)
    }

    @Test
    fun `last episode of the last season of an ended show is the series finale`() {
        assertEquals(PostShowScope.SeriesFinale(3, 10, null), postShowScopeFor("Ended", show(), 3, 10, null))
        assertEquals(PostShowScope.SeriesFinale(3, 10, null), postShowScopeFor("Canceled", show(), 3, 10, "  "))
    }

    @Test
    fun `a show that is still going only has season finales`() {
        assertTrue(postShowScopeFor("Returning Series", show(), 3, 10, null) is PostShowScope.SeasonFinale)
        assertTrue(postShowScopeFor(null, show(), 3, 10, null) is PostShowScope.SeasonFinale)
    }

    @Test
    fun `an unwatched earlier episode of the same season shuts the gate`() {
        val eps = show().map { if (it.season == 3 && it.episode == 4) it.copy(watched = false) else it }
        assertNull(postShowScopeFor("Ended", eps, 3, 10, null))
    }

    @Test
    fun `earlier seasons are not required`() {
        val eps = show().map { if (it.season < 3) it.copy(watched = false) else it }
        assertTrue(postShowScopeFor("Ended", eps, 3, 10, null) is PostShowScope.SeriesFinale)
    }

    @Test
    fun `specials never qualify and never block`() {
        val specials = listOf(PostShowEpisode(0, 1, watched = false), PostShowEpisode(0, 2, watched = false))
        assertNull(postShowScopeFor("Ended", show() + specials, 0, 2, null))
        assertTrue(postShowScopeFor("Ended", show() + specials, 3, 10, null) is PostShowScope.SeriesFinale)
    }

    @Test
    fun `an episode that is not in the list gives nothing`() {
        assertNull(postShowScopeFor("Ended", show(), 3, 11, null))
        assertNull(postShowScopeFor("Ended", emptyList(), 1, 1, null))
    }

    @Test
    fun `a one episode season is its own finale`() {
        val eps = listOf(PostShowEpisode(1, 1, false), PostShowEpisode(2, 1, false))
        assertEquals(PostShowScope.SeasonFinale(1, 1, null), postShowScopeFor("Returning Series", eps, 1, 1, null))
    }
}
