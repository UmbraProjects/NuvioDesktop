package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastEpisodeCountVisibilityTest {

    @Test
    fun recognisesRealityGenresAsTmdbAndImdbSpellThem() {
        assertTrue(isRealityShow(listOf("Reality")))
        assertTrue(isRealityShow(listOf("Drama", "Reality-TV")))
        assertTrue(isRealityShow(listOf("Game-Show")))
        assertTrue(isRealityShow(listOf("game show")))
        assertFalse(isRealityShow(listOf("Drama", "Comedy")))
        assertFalse(isRealityShow(emptyList()))
    }

    @Test
    fun hideRealityKeepsCountsEverywhereElse() {
        val reality = listOf("Reality")
        val drama = listOf("Drama")
        assertTrue(CastEpisodeCountVisibility.SHOW.showCounts(reality))
        assertFalse(CastEpisodeCountVisibility.HIDE_REALITY.showCounts(reality))
        assertTrue(CastEpisodeCountVisibility.HIDE_REALITY.showCounts(drama))
        assertFalse(CastEpisodeCountVisibility.HIDE.showCounts(drama))
    }

    @Test
    fun unknownStoredValueFallsBackToShow() {
        assertEquals(CastEpisodeCountVisibility.HIDE_REALITY, CastEpisodeCountVisibility.parse("HIDE_REALITY"))
        assertEquals(CastEpisodeCountVisibility.SHOW, CastEpisodeCountVisibility.parse("bogus"))
    }
}
