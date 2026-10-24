package com.nuvio.app.features.discover

import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NOW = 1_800_000_000_000L
private const val DAY = 24L * 60 * 60 * 1000

private fun episode(
    id: String,
    season: Int,
    episode: Int,
    daysAgo: Long,
    name: String = "Show",
) = WatchedItem(
    id = id,
    type = "series",
    name = name,
    season = season,
    episode = episode,
    markedAtEpochMs = NOW - daysAgo * DAY,
)

private fun movie(id: String, daysAgo: Long, name: String = "Film") = WatchedItem(
    id = id,
    type = "movie",
    name = name,
    markedAtEpochMs = NOW - daysAgo * DAY,
)

class DiscoverSeedsTest {

    @Test
    fun `a whole series collapses to one seed regardless of episode count`() {
        val history = (1..40).map { ep -> episode("tt0903747", season = 1, episode = ep, daysAgo = 5) }

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        assertEquals(1, seeds.size)
        assertEquals("tt0903747", seeds.single().parentId)
        assertEquals(40, seeds.single().episodesWatched)
    }

    @Test
    fun `one long series does not outweigh several films`() {
        val history = (1..60).map { ep -> episode("tt0903747", season = 1, episode = ep, daysAgo = 5) } +
            movie("tt0068646", daysAgo = 4) +
            movie("tt0111161", daysAgo = 3)

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        // The point of the collapse: three titles watched, three seeds, not sixty-two.
        assertEquals(3, seeds.size)
        assertEquals(1, seeds.count { it.parentId == "tt0903747" })
    }

    @Test
    fun `seeds are ordered most recently watched first`() {
        val history = listOf(
            movie("tt-old", daysAgo = 30),
            movie("tt-new", daysAgo = 1),
            movie("tt-mid", daysAgo = 10),
        )

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        assertEquals(listOf("tt-new", "tt-mid", "tt-old"), seeds.map { it.parentId })
    }

    @Test
    fun `a series takes the timestamp of its most recent episode`() {
        val history = listOf(
            episode("tt1", season = 1, episode = 1, daysAgo = 30),
            episode("tt1", season = 2, episode = 8, daysAgo = 2),
        )

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        assertEquals(NOW - 2 * DAY, seeds.single().lastWatchedAtEpochMs)
    }

    @Test
    fun `duplicate episode rows from an import do not inflate the episode count`() {
        // A provider import can land on top of the user's own local mark for the same episode.
        val history = listOf(
            episode("tt1", season = 1, episode = 1, daysAgo = 5),
            episode("tt1", season = 1, episode = 1, daysAgo = 4).copy(importedFrom = "trakt"),
            episode("tt1", season = 1, episode = 2, daysAgo = 4),
        )

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        assertEquals(2, seeds.single().episodesWatched)
    }

    @Test
    fun `history older than the window is ignored`() {
        val history = listOf(
            movie("tt-recent", daysAgo = 10),
            movie("tt-ancient", daysAgo = 900),
        )

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        assertEquals(listOf("tt-recent"), seeds.map { it.parentId })
    }

    @Test
    fun `unsupported content types are dropped`() {
        val history = listOf(
            movie("tt-film", daysAgo = 1),
            WatchedItem(id = "ch1", type = "channel", name = "Some Channel", markedAtEpochMs = NOW),
        )

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        assertEquals(listOf("tt-film"), seeds.map { it.parentId })
    }

    @Test
    fun `movies and series with colliding ids stay separate seeds`() {
        // TMDB movie 1234 and tv 1234 are unrelated titles; the group key must carry the type.
        val history = listOf(
            movie("tmdb:1234", daysAgo = 1),
            episode("tmdb:1234", season = 1, episode = 1, daysAgo = 2),
        )

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        assertEquals(2, seeds.size)
        assertTrue(seeds.any { it.type == "movie" } && seeds.any { it.type == "series" })
    }

    @Test
    fun `title comes from the most recent named row, not just the most recent row`() {
        // Some watch-history rows are written with an empty name; the newest row being one of them
        // rendered a headless "Because you watched" row.
        val history = listOf(
            episode("tt0121955", season = 1, episode = 1, daysAgo = 30, name = "South Park"),
            episode("tt0121955", season = 26, episode = 4, daysAgo = 1, name = ""),
        )

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        assertEquals("South Park", seeds.single().title)
        // The timestamp still comes from the genuinely newest row.
        assertEquals(NOW - DAY, seeds.single().lastWatchedAtEpochMs)
    }

    @Test
    fun `title is left blank when no row has one so the caller can recover it`() {
        val history = listOf(
            episode("tt0121955", season = 1, episode = 1, daysAgo = 5, name = ""),
            episode("tt0121955", season = 1, episode = 2, daysAgo = 4, name = ""),
        )

        val seeds = collapseWatchedToSeedCandidates(history, now = NOW)

        assertEquals("", seeds.single().title)
    }

    @Test
    fun `tmdb media type keeps movie and tv namespaces apart`() {
        assertEquals("movie", tmdbMediaTypeFor("movie"))
        assertEquals("tv", tmdbMediaTypeFor("series"))
        assertEquals("tv", tmdbMediaTypeFor("anime"))
    }
}
