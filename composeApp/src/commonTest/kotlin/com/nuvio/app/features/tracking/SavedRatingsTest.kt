package com.nuvio.app.features.tracking

import com.nuvio.app.features.mdblist.MdbListRatingWriter
import com.nuvio.app.features.simkl.SimklRatingWriter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedRatingsTest {

    private val simklBody = """
        {
          "shows": [ { "user_rating": 5, "show": { "title": "The Last Ship", "ids": { "simkl": 42040, "imdb": "tt2402207", "tvdb": "269533" } } } ],
          "anime": [ { "user_rating": 10, "show": { "title": "Hunter x Hunter", "ids": { "simkl": 40398, "mal": "11061", "al": "11061" } } } ],
          "movies": [ { "user_rating": 6, "movie": { "title": "Maleficent", "ids": { "simkl": 195258, "imdb": "tt1587310", "tmdb": "102651" } } },
                      { "user_rating": 7, "movie": { "title": "Placeholder", "ids": { "imdb": "tt2250192" } } } ]
        }
    """.trimIndent()

    @Test
    fun simklRowsAreFoundByAnyId() {
        val rows = SimklRatingWriter.parseRatedRows(simklBody)
        assertEquals(6, rows.ratingFor(savedRatingIds("tmdb" to 102651)))
        assertEquals(5, rows.ratingFor(savedRatingIds("imdb" to "TT2402207")))
        assertEquals(10, rows.ratingFor(savedRatingIds("anilist" to 11061)))
        assertNull(rows.ratingFor(savedRatingIds("imdb" to "tt0000001")))
    }

    @Test
    fun simklPlaceholderImdbIdMatchesNothing() {
        val rows = SimklRatingWriter.parseRatedRows(simklBody)
        assertNull(rows.ratingFor(savedRatingIds("imdb" to "tt2250192")))
    }

    private val mdbListBody = """
        {
          "movies": [ { "rating": 8, "rated_at": "2026-01-01T00:00:00Z", "movie": { "ids": { "imdb": "tt1587310", "tmdb": 1399 } } } ],
          "shows": [ { "rating_precise": 6.6, "rating": 7, "rated_at": "2026-01-01T00:00:00Z", "show": { "ids": { "tmdb": 1399, "tvdb": 121361 } } } ],
          "episodes": [ { "rating": 9 } ],
          "pagination": { "has_more": true, "next_cursor": "abc" }
        }
    """.trimIndent()

    @Test
    fun mdbListKeepsMoviesAndShowsApart() {
        val page = MdbListRatingWriter.parseRatedPage(mdbListBody)
        // TMDB 1399 is a movie in one list and a show in the other.
        assertEquals(8, page.rows.ratingFor(savedRatingIds("tmdb" to 1399) + ("_kind" to "movie")))
        assertEquals(7, page.rows.ratingFor(savedRatingIds("tmdb" to 1399) + ("_kind" to "show")))
        assertEquals("abc", page.nextCursor)
        assertEquals(3, page.size)
    }

    @Test
    fun aWriteIsReadBackWithoutRefetching() = runTest {
        val cache = SavedRatingsCache()
        var fetches = 0
        val target = savedRatingIds("imdb" to "tt1") + ("_kind" to "movie")
        cache.rows("k") { fetches++; listOf(SavedRatingRow(savedRatingIds("imdb" to "tt1") + ("_kind" to "movie"), 4)) }
        cache.applyWrite(listOf("k"), target, 9)
        assertEquals(9, cache.rows("k") { fetches++; emptyList() }.ratingFor(target))
        cache.applyWrite(listOf("k"), target, null)
        assertNull(cache.rows("k") { fetches++; emptyList() }.ratingFor(target))
        assertEquals(1, fetches)
    }
}
