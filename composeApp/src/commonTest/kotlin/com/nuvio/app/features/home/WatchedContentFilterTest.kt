package com.nuvio.app.features.home

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WatchedContentFilterTest {

    @BeforeTest
    fun resetVerdicts() {
        WatchedContentFilter.resetSessionVerdicts()
    }

    @Test
    fun `a title already shown is not hidden by a mapping learned afterwards`() {
        // Watched long ago, so it is in the history from the start. The row addresses it by TMDB
        // id, whose IMDb id is not known yet when the row is first published.
        val history = listOf(watched(id = "tt7700001", type = "movie"))
        val item = MetaPreview(id = "tmdb:7700001", type = "movie", name = "late mapping")
        assertEquals(listOf(item), listOf(item).filterUnwatchedItems(WatchedContentFilter.forHistory(history)))

        // A poster or hero load learns the mapping while the row is on screen, then Home republishes.
        TmdbService.rememberTmdbToImdbForTest(7700001, "movie", "tt7700001")
        assertEquals(listOf(item), listOf(item).filterUnwatchedItems(WatchedContentFilter.forHistory(history)))

        // A page loaded from now on is judged with the mapping, so an unseen title is dropped.
        val unseen = MetaPreview(id = "tmdb:7700001", type = "series", name = "other namespace")
        WatchedContentFilter.resetSessionVerdicts()
        assertEquals(emptyList(), listOf(item).filterUnwatchedItems(WatchedContentFilter.forHistory(history)))
        assertEquals(listOf(unseen), listOf(unseen).filterUnwatchedItems(WatchedContentFilter.forHistory(history)))
    }

    @Test
    fun `a title the poster marks watched through an id alias is hidden`() {
        // The poster's tick answers under WatchedIdAliases (a `tmdb:` row for a `tt` import); the filter must too
        // (user report, Nov 23: rows showed ticked titles with Hide watched content on).
        com.nuvio.app.features.watched.WatchedIdAliases.replace(listOf(setOf("tt9900001", "tmdb:9900001")))
        try {
            val history = listOf(watched(id = "tt9900001", type = "series"))
            val item = MetaPreview(id = "tmdb:9900001", type = "series", name = "aliased")
            assertEquals(emptyList(), listOf(item).filterUnwatchedItems(WatchedContentFilter.forHistory(history)))
        } finally {
            com.nuvio.app.features.watched.WatchedIdAliases.clear()
        }
    }

    @Test
    fun `a title shown before being watched is hidden once it is watched`() {
        val before = listOf(watched(id = "tt1", type = "movie"))
        val items = listOf(preview("tt2"), preview("tt3"))
        assertEquals(items, items.filterUnwatchedItems(WatchedContentFilter.forHistory(before)))

        val after = before + watched(id = "tt2", type = "movie")
        assertEquals(listOf(preview("tt3")), items.filterUnwatchedItems(WatchedContentFilter.forHistory(after)))
    }

    @Test
    fun `a title never shown is judged against the full history`() {
        val before = listOf(watched(id = "tt1", type = "movie"))
        WatchedContentFilter.forHistory(before)
        val filter = WatchedContentFilter.forHistory(before)

        assertEquals(listOf(preview("tt2")), listOf(preview("tt1"), preview("tt2")).filterUnwatchedItems(filter))
    }

    @Test
    fun `one watched episode hides the whole series`() {
        val filter = WatchedContentFilter.forHistory(
            listOf(watched(id = "tt0903747", type = "series", season = 1, episode = 1)),
        )

        assertTrue(filter.isWatched(type = "series", id = "tt0903747"))
    }

    @Test
    fun `stored type aliases answer a catalog lookup`() {
        val filter = WatchedContentFilter.forHistory(
            listOf(
                watched(id = "tt1", type = "show"),
                watched(id = "kitsu:11", type = "anime"),
                watched(id = "tt2", type = "film"),
            ),
        )

        assertTrue(filter.isWatched(type = "series", id = "tt1"))
        assertTrue(filter.isWatched(type = "tv", id = "kitsu:11"))
        assertTrue(filter.isWatched(type = "movie", id = "tt2"))
    }

    @Test
    fun `movie and series namespaces stay apart`() {
        val filter = WatchedContentFilter.forHistory(listOf(watched(id = "tt1", type = "movie")))

        assertFalse(filter.isWatched(type = "series", id = "tt1"))
    }

    @Test
    fun `unknown ids and unmapped tmdb ids are kept`() {
        val filter = WatchedContentFilter.forHistory(listOf(watched(id = "tt1", type = "movie")))

        assertFalse(filter.isWatched(type = "movie", id = "tt9"))
        // No IMDb mapping has been learned for this TMDB id, so the filter must not guess.
        assertFalse(filter.isWatched(type = "movie", id = "tmdb:550"))
        assertFalse(filter.isWatched(type = "movie", id = "tmdb:not-a-number"))
    }

    @Test
    fun `filter keeps the same list instance when nothing is removed`() {
        val filter = WatchedContentFilter.forHistory(listOf(watched(id = "tt1", type = "movie")))
        val items = listOf(preview("tt2"), preview("tt3"))

        assertSame(items, items.filterUnwatchedItems(filter))
        assertSame(items, items.filterUnwatchedItems(null))
    }

    @Test
    fun `catalog section drops watched items and keeps its counts`() {
        val filter = WatchedContentFilter.forHistory(listOf(watched(id = "tt1", type = "movie")))
        val section = HomeCatalogSection(
            key = "addon:movie:popular",
            title = "Popular",
            subtitle = "Addon",
            addonName = "Addon",
            target = CatalogTarget.Addon(
                manifestUrl = "https://example.com/manifest.json",
                contentType = "movie",
                catalogId = "popular",
            ),
            items = listOf(preview("tt1"), preview("tt2")),
            availableItemCount = 2,
        )

        val result = section.filterUnwatchedItems(filter)

        assertEquals(listOf("tt2"), result.items.map { it.id })
        assertEquals(2, result.availableItemCount)
        assertSame(section, section.filterUnwatchedItems(null))
    }

    private fun watched(
        id: String,
        type: String,
        season: Int? = null,
        episode: Int? = null,
    ): WatchedItem = WatchedItem(
        id = id,
        type = type,
        name = id,
        season = season,
        episode = episode,
        markedAtEpochMs = 0L,
    )

    private fun preview(id: String): MetaPreview = MetaPreview(id = id, type = "movie", name = id)
}
