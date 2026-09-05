package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which seasons a trailer lookup asks for (plan §21.2, regression in §23).
 *
 * The first test is the one that matters: `seasonCount == 0` used to reach `coerceIn(1, 0)` and
 * throw, and because this runs inside the shared meta fetch it took the entire meta down with it.
 */
class TrailerSeasonScopeTest {
    private val all = TmdbMetadataService.TrailerScope.AllSeasons

    private fun single(preferred: Int?) = TmdbMetadataService.TrailerScope.SingleSeason(preferred)

    @Test
    fun `zero seasons asks for nothing and does not throw`() {
        assertEquals(emptyList(), trailerSeasonsFor(all, 0))
        assertEquals(emptyList(), trailerSeasonsFor(single(null), 0))
        assertEquals(emptyList(), trailerSeasonsFor(single(3), 0))
    }

    @Test
    fun `a negative season count is treated as none`() {
        assertEquals(emptyList(), trailerSeasonsFor(single(1), -1))
        assertEquals(emptyList(), trailerSeasonsFor(all, -5))
    }

    @Test
    fun `all seasons asks for every season`() {
        assertEquals(listOf(1, 2, 3), trailerSeasonsFor(all, 3))
        assertEquals(listOf(1), trailerSeasonsFor(all, 1))
    }

    @Test
    fun `a single season asks for exactly one`() {
        assertEquals(listOf(4), trailerSeasonsFor(single(4), 10))
    }

    @Test
    fun `no preferred season falls back to the first`() {
        // Where a show's trailer usually lives, and the right guess when the user has not started it.
        assertEquals(listOf(1), trailerSeasonsFor(single(null), 10))
    }

    @Test
    fun `a preferred season beyond the show is clamped into range`() {
        // Watch history can name a season TMDB does not have — different numbering, or a special.
        assertEquals(listOf(5), trailerSeasonsFor(single(99), 5))
        assertEquals(listOf(1), trailerSeasonsFor(single(0), 5))
        assertEquals(listOf(1), trailerSeasonsFor(single(-2), 5))
    }

    @Test
    fun `single season never asks for more than one request`() {
        (1..50).forEach { count ->
            assertEquals(1, trailerSeasonsFor(single(7), count).size, "failed at seasonCount=$count")
        }
    }
}
