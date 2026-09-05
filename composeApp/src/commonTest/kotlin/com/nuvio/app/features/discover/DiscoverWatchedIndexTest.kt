package com.nuvio.app.features.discover

import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun watched(
    id: String,
    type: String = "series",
    season: Int? = null,
    episode: Int? = null,
) = WatchedItem(id = id, type = type, name = "X", season = season, episode = episode, markedAtEpochMs = 1L)

class DiscoverWatchedIndexTest {

    @Test
    fun `one watched episode marks the whole show as met`() {
        // The case that made recommendations keep suggesting finished shows: history stores rows
        // per episode, so a show-level lookup against per-episode keys matched almost nothing.
        val keys = buildWatchedParentKeys(listOf(watched("tt10986410", season = 1, episode = 4)))

        assertTrue(watchedParentKey("series", "tt10986410") in keys)
    }

    @Test
    fun `unaired episodes cannot keep a finished show out of the index`() {
        // Every aired episode watched, three announced episodes not. Nothing here counts episodes,
        // which is the point — a completeness test would call this show unfinished forever.
        val history = (1..10).map { ep -> watched("tt10986410", season = 1, episode = ep) }

        val keys = buildWatchedParentKeys(history)

        assertTrue(watchedParentKey("series", "tt10986410") in keys)
    }

    @Test
    fun `movies key on themselves`() {
        val keys = buildWatchedParentKeys(listOf(watched("tt0068646", type = "movie")))

        assertTrue(watchedParentKey("movie", "tt0068646") in keys)
        assertFalse(watchedParentKey("series", "tt0068646") in keys)
    }

    @Test
    fun `history stored as anime answers a series lookup`() {
        val keys = buildWatchedParentKeys(listOf(watched("kitsu:5605", type = "anime", episode = 3)))

        assertTrue(watchedParentKey("series", "kitsu:5605") in keys)
    }

    @Test
    fun `movie and series id spaces stay separate`() {
        val keys = buildWatchedParentKeys(listOf(watched("tmdb:1234", type = "movie")))

        assertFalse(watchedParentKey("series", "tmdb:1234") in keys)
    }

    @Test
    fun `blank ids are not indexed`() {
        assertTrue(buildWatchedParentKeys(listOf(watched(""))).isEmpty())
    }
}
