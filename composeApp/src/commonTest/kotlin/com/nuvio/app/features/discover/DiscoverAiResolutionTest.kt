package com.nuvio.app.features.discover

import com.nuvio.app.features.tmdb.TmdbSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Matching a suggested title to a real one (plan §5).
 *
 * The case that matters most is the last one: TMDB returns a result for almost any query, so
 * without a title check an invented film becomes a real recommendation wearing the wrong name.
 */
class DiscoverAiResolutionTest {
    private fun movie(id: Int, title: String, year: Int?, popularity: Double = 1.0) =
        TmdbSearchResult(
            id = id,
            title = title,
            releaseDate = year?.let { "$it-01-01" },
            popularity = popularity,
            mediaType = "movie",
        )

    @Test
    fun `an exact title wins over a more popular partial one`() {
        val match = pickBestTmdbMatch(
            title = "Alien",
            year = null,
            results = listOf(
                movie(1, "Aliens", 1986, popularity = 90.0),
                movie(2, "Alien", 1979, popularity = 10.0),
            ),
        )

        assertEquals(2, match?.id)
    }

    @Test
    fun `the year breaks a tie between remakes`() {
        val match = pickBestTmdbMatch(
            title = "Suspiria",
            year = 2018,
            results = listOf(
                movie(1, "Suspiria", 1977, popularity = 50.0),
                movie(2, "Suspiria", 2018, popularity = 20.0),
            ),
        )

        assertEquals(2, match?.id)
    }

    @Test
    fun `a year one out still counts as the same title`() {
        // Release years disagree across regions often enough that an exact match is too strict.
        val match = pickBestTmdbMatch("Parasite", 2020, listOf(movie(1, "Parasite", 2019)))

        assertEquals(1, match?.id)
    }

    @Test
    fun `case, punctuation and a leading article do not prevent a match`() {
        listOf("the matrix", "The  Matrix", "The Matrix!", "Matrix").forEach { asked ->
            val match = pickBestTmdbMatch(asked, null, listOf(movie(1, "The Matrix", 1999)))
            assertEquals(1, match?.id, "failed for: $asked")
        }
    }

    @Test
    fun `popularity decides when nothing else does`() {
        val match = pickBestTmdbMatch(
            title = "Heat",
            year = null,
            results = listOf(movie(1, "Heat", 1995, 5.0), movie(2, "Heat", 2024, 80.0)),
        )

        assertEquals(2, match?.id)
    }

    @Test
    fun `a short title does not match a long one that merely contains it`() {
        // Containment alone would make "Alien" match this, and the row would show the wrong film.
        val match = pickBestTmdbMatch(
            title = "Alien",
            year = null,
            results = listOf(movie(1, "Alien vs Predator Requiem Extended", 2007)),
        )

        assertNull(match)
    }

    @Test
    fun `an invented title resolves to nothing rather than to whatever search returned`() {
        // The model hallucinated; TMDB answered with a real, unrelated film. Dropping it is the
        // only honest outcome — the alternative is a recommendation with someone else's reason.
        val match = pickBestTmdbMatch(
            title = "The Quiet Horizon",
            year = 2019,
            results = listOf(movie(1, "Quiet Place", 2018), movie(2, "Horizon Line", 2020)),
        )

        assertNull(match)
    }

    @Test
    fun `no results is not a match`() {
        assertNull(pickBestTmdbMatch("Anything", 2000, emptyList()))
    }

    @Test
    fun `a blank title never matches`() {
        assertNull(pickBestTmdbMatch("   ", null, listOf(movie(1, "Something", 2000))))
        assertNull(pickBestTmdbMatch("!!!", null, listOf(movie(1, "Something", 2000))))
    }

    @Test
    fun `a disagreeing year loses to a result with no year at all`() {
        val match = pickBestTmdbMatch(
            title = "Dune",
            year = 2021,
            results = listOf(movie(1, "Dune", 1984, popularity = 90.0), movie(2, "Dune", null, 1.0)),
        )

        assertEquals(2, match?.id)
    }

    @Test
    fun `normalisation strips articles only when there is something left`() {
        assertEquals("matrix", normalizeTitleForMatch("The Matrix"))
        assertEquals("the", normalizeTitleForMatch("The"))
        assertEquals("", normalizeTitleForMatch("   "))
    }
}
