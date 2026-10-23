package com.nuvio.app.features.calendar

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuvioCalendarCacheTest {
    private val hour = 60 * 60 * 1000L
    private val now = 1_000L * 24 * hour

    private fun cached(fetchedAgoMs: Long, failed: Boolean = false, finished: Boolean = false) =
        NuvioCalendarCachedItem(fetchedAtEpochMs = now - fetchedAgoMs, failed = failed, finished = finished)

    @Test
    fun `a title never fetched is due`() {
        assertTrue(nuvioCalendarRefreshDue(null, now))
    }

    @Test
    fun `a running show is re-read after twelve hours`() {
        assertFalse(nuvioCalendarRefreshDue(cached(11 * hour), now))
        assertTrue(nuvioCalendarRefreshDue(cached(12 * hour), now))
    }

    @Test
    fun `a finished title waits thirty days`() {
        assertFalse(nuvioCalendarRefreshDue(cached(29 * 24 * hour, finished = true), now))
        assertTrue(nuvioCalendarRefreshDue(cached(30 * 24 * hour, finished = true), now))
    }

    @Test
    fun `a failed lookup is retried after six hours`() {
        assertFalse(nuvioCalendarRefreshDue(cached(5 * hour, failed = true), now))
        assertTrue(nuvioCalendarRefreshDue(cached(6 * hour, failed = true), now))
    }

    @Test
    fun `an ended show with nothing recent is finished`() {
        assertTrue(nuvioCalendarIsFinished(listOf("2024-01-01", "2024-03-01"), "Ended", "2026-10-13"))
    }

    @Test
    fun `an ended show with a recent episode is not finished yet`() {
        assertFalse(nuvioCalendarIsFinished(listOf("2026-09-01"), "Ended", "2026-10-13"))
    }

    @Test
    fun `a running show between seasons is not finished`() {
        assertFalse(nuvioCalendarIsFinished(listOf("2026-03-01"), "Returning Series", "2026-10-13"))
    }

    @Test
    fun `a show silent for over a year is finished whatever its status`() {
        assertTrue(nuvioCalendarIsFinished(listOf("2025-01-01"), "Continuing", "2026-10-13"))
    }

    @Test
    fun `a title with an upcoming date is not finished`() {
        assertFalse(nuvioCalendarIsFinished(listOf("2020-01-01", "2026-12-01"), "Ended", "2026-10-13"))
    }

    @Test
    fun `a movie without a date stays on the short schedule`() {
        assertFalse(nuvioCalendarIsFinished(emptyList(), "released", "2026-10-13"))
    }

    @Test
    fun `follows are library first, then shows from history, deduplicated`() {
        val library = listOf(
            LibraryItem(id = "tt1", type = "series", name = "Show", tmdbId = 10, savedAtEpochMs = 1),
            LibraryItem(id = "tt2", type = "movie", name = "Film", savedAtEpochMs = 2),
            LibraryItem(id = "torbox:abc", type = "movie", name = "File", metaLookupId = "tt9", savedAtEpochMs = 3),
            LibraryItem(id = "leaf:1", type = "sport", name = "Match", savedAtEpochMs = 4),
        )
        val watched = listOf(
            WatchedItem(id = "tt1", type = "series", name = "Show", season = 1, episode = 2, markedAtEpochMs = 5),
            WatchedItem(id = "tt3", type = "series", name = "Older", season = 1, episode = 1, markedAtEpochMs = 6),
            WatchedItem(id = "kitsu:7", type = "anime", name = "Anime", season = 1, episode = 1, markedAtEpochMs = 7),
            WatchedItem(id = "tt4", type = "movie", name = "Watched film", markedAtEpochMs = 8),
            WatchedItem(id = "upnext_tt5_trakt1", type = "series", name = "Junk", season = 1, episode = 1, markedAtEpochMs = 9),
        )

        val follows = nuvioCalendarFollows(library, watched)

        assertEquals(
            listOf("movie:tt2", "show:tt1", "show:kitsu:7", "show:tt3"),
            follows.map { it.key },
        )
        assertEquals(10, follows.first { it.contentId == "tt1" }.tmdbId)
        assertEquals("anime", follows.first { it.contentId == "kitsu:7" }.type)
    }

    private fun release(country: String, type: Int, date: String) = MovieReleaseDate(country, type, date)

    @Test
    fun `a limited opening dates the movie, not the day it goes wide`() {
        // TMDB's release_dates for You Can See Everything (1765072) on 2026-10-23.
        val releases = listOf(
            release("US", 1, "2026-09-06"),
            release("CA", 3, "2026-10-16"),
            release("GB", 3, "2026-11-20"),
            release("PL", 2, "2026-10-23"),
            release("US", 2, "2026-10-16"),
            release("US", 3, "2026-10-23"),
        )
        assertEquals("2026-10-16", pickMovieCalendarDate(releases))
    }

    @Test
    fun `digital wins over theatrical, US then GB then the earliest anywhere`() {
        val theatrical = listOf(release("US", 3, "2026-01-01"))
        assertEquals(
            "2026-03-01",
            pickMovieCalendarDate(theatrical + release("FR", 4, "2026-02-01") + release("GB", 4, "2026-03-01")),
        )
        assertEquals(
            "2026-04-01",
            pickMovieCalendarDate(theatrical + release("GB", 4, "2026-03-01") + release("US", 4, "2026-04-01")),
        )
        assertEquals("2026-02-01", pickMovieCalendarDate(theatrical + release("FR", 4, "2026-02-01")))
    }

    @Test
    fun `premieres are ignored, limited openings count anywhere`() {
        assertEquals(null, pickMovieCalendarDate(listOf(release("US", 1, "2026-09-06"))))
        assertEquals("2026-10-23", pickMovieCalendarDate(listOf(release("PL", 2, "2026-10-23"), release("TR", 3, "2026-10-30"))))
    }
}
