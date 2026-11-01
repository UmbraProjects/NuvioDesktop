package com.nuvio.app.features.preroll

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailerHistoryTest {
    private fun film(id: String, name: String = id) = MetaPreview(id = id, type = "movie", name = name)
    private fun key(id: String) = trailerHistoryKey("movie", id)

    @Test
    fun withNothingPlayedTheOrderIsUntouched() {
        val list = listOf(film("a"), film("b"), film("c"))
        assertEquals(list, orderByHistory(list, emptyMap()))
    }

    @Test
    fun playedTrailersGoToTheBackOldestFirst() {
        val list = listOf(film("a"), film("b"), film("c"), film("d"))
        val played = mapOf(key("a") to 300L, key("c") to 100L)
        assertEquals(listOf("b", "d", "c", "a"), orderByHistory(list, played).map { it.id })
    }

    @Test
    fun nothingIsRemovedWhenEverythingHasPlayed() {
        val list = listOf(film("a"), film("b"))
        val played = mapOf(key("a") to 2L, key("b") to 1L)
        assertEquals(listOf("b", "a"), orderByHistory(list, played).map { it.id })
    }

    @Test
    fun aMovieAndASeriesWithTheSameIdAreDifferentTitles() {
        val list = listOf(MetaPreview(id = "tt1", type = "series", name = "S"), film("tt1"))
        val played = mapOf(trailerHistoryKey("movie", "tt1") to 5L)
        assertEquals(listOf("series", "movie"), orderByHistory(list, played).map { it.type })
    }

    @Test
    fun recordingMovesATitleToTheNewestAndTrimsTheOldest() {
        var played = emptyMap<String, Long>()
        played = recordPlayed(played, "a", 1L, max = 3)
        played = recordPlayed(played, "b", 2L, max = 3)
        played = recordPlayed(played, "c", 3L, max = 3)
        played = recordPlayed(played, "a", 4L, max = 3)
        assertEquals(4L, played["a"])
        played = recordPlayed(played, "d", 5L, max = 3)
        assertEquals(3, played.size)
        assertFalse("b" in played)
        assertTrue("a" in played && "c" in played && "d" in played)
    }

    @Test
    fun yearIsTakenFromTheFrontOfTheReleaseString() {
        assertEquals("2008", yearOf("2008"))
        assertEquals("2008", yearOf("2008-2013"))
        assertNull(yearOf("TBA"))
        assertNull(yearOf(null))
    }
}
