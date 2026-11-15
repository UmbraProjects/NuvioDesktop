package com.nuvio.app.features.discover

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Only anime's second search, Japanese animation, for the anime TMDB never keyworded (2026-11-15). */
class TmdbAnimeSearchTest {
    private val today = LocalDate.parse("2026-10-12")
    private val heist = TmdbBrowseKeyword(10051, "heist")
    private val timeTravel = TmdbBrowseKeyword(4379, "time travel")

    private fun language(query: TmdbBrowseQuery) =
        tmdbAnimeLanguageParams(query, query.media, page = 1, region = "GB", today = today)

    @Test
    fun `only anime also asks for Japanese animation, without the keyword`() {
        val p = language(TmdbBrowseQuery(anime = TmdbBrowseAnime.Only))!!
        assertEquals("ja", p["with_original_language"])
        assertEquals("16", p["with_genres"])
        assertNull(p["with_keywords"])
        assertNull(p["without_keywords"])
    }

    @Test
    fun `include and exclude stay one search`() {
        assertNull(language(TmdbBrowseQuery()))
        assertNull(language(TmdbBrowseQuery(anime = TmdbBrowseAnime.Exclude)))
    }

    @Test
    fun `another language or Animation left out rules the second search out`() {
        assertNull(language(TmdbBrowseQuery(anime = TmdbBrowseAnime.Only, language = "ko")))
        assertNull(language(TmdbBrowseQuery(anime = TmdbBrowseAnime.Only, withoutGenres = listOf(16))))
        assertEquals("ja", language(TmdbBrowseQuery(anime = TmdbBrowseAnime.Only, language = "ja"))!!["with_original_language"])
    }

    @Test
    fun `genres are added to Animation when TMDB can say so`() {
        assertEquals("16,28", language(TmdbBrowseQuery(anime = TmdbBrowseAnime.Only, genres = listOf(28)))!!["with_genres"])
        assertEquals("16,28,35", language(TmdbBrowseQuery(anime = TmdbBrowseAnime.Only, genres = listOf(28, 35), allGenres = true))!!["with_genres"])
        // Any of several: "16,28|35" would count only the first group, so the list goes as it is (Animation checked after).
        assertEquals("28|35", language(TmdbBrowseQuery(anime = TmdbBrowseAnime.Only, genres = listOf(28, 35)))!!["with_genres"])
    }

    @Test
    fun `tags must all match in both searches`() {
        val q = TmdbBrowseQuery(anime = TmdbBrowseAnime.Only, keywords = listOf(heist, timeTravel))
        assertEquals("10051,4379", language(q)!!["with_keywords"])
        assertEquals("210024,10051,4379", tmdbBrowseParams(q, q.media, 1, "GB", today)["with_keywords"])
    }

    @Test
    fun `the overlap count asks for both at once`() {
        val q = TmdbBrowseQuery(anime = TmdbBrowseAnime.Only)
        val p = tmdbAnimeOverlapParams(q, q.media, "GB", today)!!
        assertEquals("210024", p["with_keywords"])
        assertEquals("ja", p["with_original_language"])
        assertEquals("16", p["with_genres"])
        assertNull(tmdbAnimeOverlapParams(TmdbBrowseQuery(), TmdbBrowseMedia.Movie, "GB", today))
    }

    @Test
    fun `two ordered pages merge in order, each keeping its own order`() {
        val desc = compareByDescending<Int> { it }
        assertEquals(listOf(9, 8, 7, 5, 4, 2, 1), mergeInOrder(listOf(9, 7, 4, 1), listOf(8, 5, 2), desc))
        assertEquals(listOf(3, 2), mergeInOrder(emptyList(), listOf(3, 2), desc))
        // Equal keys: the first list's item goes first.
        val byTens = compareByDescending<Int> { it / 10 }
        assertEquals(listOf(21, 22, 11), mergeInOrder(listOf(21, 11), listOf(22), byTens))
    }
}
