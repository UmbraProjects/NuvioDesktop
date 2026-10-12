package com.nuvio.app.features.library

import com.nuvio.app.features.discover.TmdbBrowseCompany
import com.nuvio.app.features.discover.TmdbBrowseStatus
import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Library filter rules, against the Nuvio Tracker's library.ts they were ported from. */
class LibraryBrowseTest {
    private val today = "2026-10-12"

    private val show = LibraryItem(id = "tt0944947", type = "series", name = "Game of Thrones", savedAtEpochMs = 3)
    private val film = LibraryItem(id = "tt0133093", type = "movie", name = "The Matrix", savedAtEpochMs = 1)
    private val unknown = LibraryItem(id = "kitsu:1", type = "series", name = "Cowboy Bebop", savedAtEpochMs = 2)

    private val facts = mapOf(
        libraryItemKey(show.id, show.type) to LibraryTitleFacts(
            genres = listOf("Drama", "Sci-Fi & Fantasy"), year = 2011, releaseDate = "2011-04-17", runtime = 60,
            originalLanguage = "en", companyIds = listOf(76043), networkIds = listOf(49), status = "Ended",
            voteAverage = 8.5, voteCount = 27922, popularity = 197.5, numberOfEpisodes = 73,
        ),
        libraryItemKey(film.id, film.type) to LibraryTitleFacts(
            genres = listOf("Action", "Science Fiction"), year = 1999, releaseDate = "1999-03-31", runtime = 136,
            originalLanguage = "en", companyIds = listOf(79), status = "Released",
            voteAverage = 8.2, voteCount = 26000, popularity = 80.0,
        ),
    )

    private fun watchedEpisodes(count: Int) = (1..count).map { i ->
        WatchedItem(id = show.id, type = "series", name = show.name, season = 1 + (i - 1) / 10, episode = 1 + (i - 1) % 10, markedAtEpochMs = 0)
    }

    @Test
    fun `genres match any by default and all when asked`() {
        val any = LibraryBrowseFilters(genres = listOf("Drama", "Action"))
        assertTrue(any.matches(facts.values.first(), today))
        val all = any.copy(allGenres = true)
        assertFalse(all.matches(facts.values.first(), today))
    }

    @Test
    fun `a title without details passes no detail filter but every watch filter`() {
        val filters = LibraryBrowseFilters(minRating = 5.0)
        assertFalse(filters.matches(null, today))
        assertTrue(LibraryBrowseFilters(watch = LibraryWatchFilter.Unwatched).matches(null, today))
    }

    @Test
    fun `status follows the tracker's groupings`() {
        val gotFacts = facts.values.first()
        assertTrue(LibraryBrowseFilters(status = TmdbBrowseStatus.Ended).matches(gotFacts, today))
        assertFalse(LibraryBrowseFilters(status = TmdbBrowseStatus.Returning).matches(gotFacts, today))
        assertTrue(LibraryBrowseFilters(status = TmdbBrowseStatus.HomeRelease).matches(gotFacts, today))
        assertFalse(LibraryBrowseFilters(status = TmdbBrowseStatus.Upcoming).matches(gotFacts, today))
    }

    @Test
    fun `studio and network match on any id`() {
        val gotFacts = facts.values.first()
        assertTrue(LibraryBrowseFilters(companies = listOf(TmdbBrowseCompany(76043, "x"), TmdbBrowseCompany(1, "y"))).matches(gotFacts, today))
        assertTrue(LibraryBrowseFilters(networks = listOf(213, 49)).matches(gotFacts, today))
        assertFalse(LibraryBrowseFilters(networks = listOf(213)).matches(gotFacts, today))
    }

    @Test
    fun `a show is watched once every episode is`() {
        val factsForShow = facts[libraryItemKey(show.id, show.type)]
        val noProgress = emptySet<String>()
        assertEquals(LibraryWatchFilter.Unwatched, libraryWatchState(show, factsForShow, emptyMap(), noProgress))
        assertEquals(LibraryWatchFilter.InProgress, libraryWatchState(show, factsForShow, buildWatchedByTitle(watchedEpisodes(10)), noProgress))
        assertEquals(LibraryWatchFilter.Watched, libraryWatchState(show, factsForShow, buildWatchedByTitle(watchedEpisodes(73)), noProgress))
    }

    @Test
    fun `a film is watched once it has a watched row, in progress while part-played`() {
        val watched = buildWatchedByTitle(listOf(WatchedItem(id = film.id, type = "movie", name = film.name, markedAtEpochMs = 0)))
        assertEquals(LibraryWatchFilter.Watched, libraryWatchState(film, null, watched, emptySet()))
        assertEquals(LibraryWatchFilter.InProgress, libraryWatchState(film, null, emptyMap(), setOf("movie:${film.id}")))
    }

    @Test
    fun `filtered sections drop what is left empty`() {
        val sections = listOf(
            LibrarySection(type = "movie", displayTitle = "Movies", items = listOf(film)),
            LibrarySection(type = "series", displayTitle = "Series", items = listOf(show, unknown)),
        )
        val result = filterLibrarySections(sections, LibraryBrowseFilters(networks = listOf(49)), facts, emptyMap(), emptySet(), today)
        assertEquals(listOf("Series"), result.map { it.displayTitle })
        assertEquals(listOf(show), result.single().items)
    }

    @Test
    fun `fact sorts put unknown titles last`() {
        val items = listOf(unknown, film, show)
        val byRating = sortLibraryItems(items, LibrarySortOption.TOP_RATED, LibrarySourceMode.LOCAL, facts)
        assertEquals(listOf(show, film, unknown), byRating)
        val oldest = sortLibraryItems(items, LibrarySortOption.OLDEST, LibrarySourceMode.LOCAL, facts)
        assertEquals(listOf(film, show, unknown), oldest)
        val newest = sortLibraryItems(items, LibrarySortOption.NEWEST, LibrarySourceMode.LOCAL, facts)
        assertEquals(listOf(show, film, unknown), newest)
    }

    @Test
    fun `watch filter alone needs no details`() {
        assertFalse(LibraryBrowseFilters(watch = LibraryWatchFilter.Watched).needsFacts)
        assertTrue(LibraryBrowseFilters(language = "en").needsFacts)
        assertEquals(LibraryWatchFilter.Watched, LibraryBrowseFilters(watch = LibraryWatchFilter.Watched, language = "en").clearedFilters().watch)
    }
}
