package com.nuvio.app.features.watched

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real anime-list data: SAO II is kitsu 8174 / imdb tt2250192 / TMDB tv 45782 / TVDB 259640, and
 * SAO the Movie: Ordinal Scale is kitsu 11423 / imdb tt5544384 / TMDB movie 413594.
 */
class AnimeWatchedKeyAliasesTest {

    private fun episode(id: String, season: Int, number: Int) = WatchedItem(
        id = id,
        type = "series",
        name = "Sword Art Online II",
        season = season,
        episode = number,
        markedAtEpochMs = 1L,
    )

    @Test
    fun `a kitsu-keyed episode also answers under every franchise id`() {
        val keys = animeAlternateWatchedKeys(listOf(episode("kitsu:8174", season = 2, number = 5)))

        assertContains(keys, watchedItemKey("series", "tt2250192", 2, 5))
        assertContains(keys, watchedItemKey("series", "tmdb:45782", 2, 5))
        assertContains(keys, watchedItemKey("series", "tvdb:259640", 2, 5))
    }

    @Test
    fun `an entry-local mark is lifted onto the franchise season`() {
        // A mark made on a Kitsu addon's own page: that page presents the entry as season 1, so
        // copying the coordinates onto a franchise id would badge the franchise's first season.
        // SAO II is franchise season 2, so its entry-local episode 5 is franchise S2E5.
        val keys = animeAlternateWatchedKeys(listOf(episode("kitsu:8174", season = 1, number = 5)))

        assertContains(keys, watchedItemKey("series", "tt2250192", 2, 5))
        assertFalse(keys.contains(watchedItemKey("series", "tt2250192", 1, 5)))
    }

    @Test
    fun `an entry mapping to season one is left alone`() {
        // The two spaces coincide there apart from a cour offset, and nothing records which space
        // the stored coordinates used — shifting on a guess would move an episode never offset.
        val keys = animeAlternateWatchedKeys(listOf(episode("kitsu:6589", season = 1, number = 5)))

        assertContains(keys, watchedItemKey("series", "tt2250192", 1, 5))
    }

    @Test
    fun `coordinates are copied, never re-derived`() {
        // SAO II is franchise season 2 and the stored coordinates are already franchise-numbered
        // (SimklWatchedRepository canonicalises before storing). Applying the season/offset maths
        // a second time would move this episode; the alias must leave it exactly where it is.
        val keys = animeAlternateWatchedKeys(listOf(episode("kitsu:8174", season = 2, number = 5)))

        assertFalse(keys.contains(watchedItemKey("series", "tt2250192", 1, 5)))
        assertTrue(keys.none { it.contains("tt2250192") && !it.endsWith(":2:5") })
    }

    @Test
    fun `an anime movie aliases onto its tmdb movie id, never a tv or tvdb id`() {
        val movie = WatchedItem(
            id = "kitsu:11423",
            type = "movie",
            name = "Sword Art Online the Movie: Ordinal Scale",
            markedAtEpochMs = 1L,
        )

        val keys = animeAlternateWatchedKeys(listOf(movie))

        assertContains(keys, watchedItemKey("movie", "tt5544384"))
        assertContains(keys, watchedItemKey("movie", "tmdb:413594"))
        assertTrue(keys.none { it.contains("tvdb:") })
    }

    @Test
    fun `franchise-keyed and unmapped items produce no aliases`() {
        val items = listOf(
            episode("tt2250192", season = 2, number = 5),
            episode("tmdb:45782", season = 2, number = 5),
            episode("kitsu:99999999", season = 1, number = 1),
        )

        assertEquals(emptySet(), animeAlternateWatchedKeys(items))
    }

    @Test
    fun `no items means no work`() {
        assertEquals(emptySet(), animeAlternateWatchedKeys(emptyList()))
    }
}
