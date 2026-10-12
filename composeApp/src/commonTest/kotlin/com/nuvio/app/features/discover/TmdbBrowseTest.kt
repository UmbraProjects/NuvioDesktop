package com.nuvio.app.features.discover

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The /discover parameters, against the Nuvio Tracker's browse.ts rules they were ported from. */
class TmdbBrowseTest {
    private val today = LocalDate.parse("2026-10-12")

    private fun params(query: TmdbBrowseQuery, media: TmdbBrowseMedia = query.media) =
        tmdbBrowseParams(query, media, page = 1, region = "GB", today = today)

    @Test
    fun `genres OR by default and AND when every genre must match`() {
        assertEquals("18|80", params(TmdbBrowseQuery(genres = listOf(18, 80)))["with_genres"])
        assertEquals("18,80", params(TmdbBrowseQuery(genres = listOf(18, 80), allGenres = true))["with_genres"])
    }

    @Test
    fun `top rated gets a vote floor unless one was set`() {
        assertEquals("200", params(TmdbBrowseQuery(sort = TmdbBrowseSort.TopRated))["vote_count.gte"])
        assertEquals("5000", params(TmdbBrowseQuery(sort = TmdbBrowseSort.TopRated, minVotes = 5000))["vote_count.gte"])
        assertNull(params(TmdbBrowseQuery())["vote_count.gte"])
    }

    @Test
    fun `newest means out already`() {
        val p = params(TmdbBrowseQuery(sort = TmdbBrowseSort.Newest))
        assertEquals("primary_release_date.desc", p["sort_by"])
        assertEquals("2026-10-12", p["primary_release_date.lte"])
    }

    @Test
    fun `tv uses first air dates and its own status codes`() {
        val p = params(TmdbBrowseQuery(media = TmdbBrowseMedia.Tv, yearFrom = 2010, yearTo = 2019, status = TmdbBrowseStatus.Ended))
        assertEquals("2010-01-01", p["first_air_date.gte"])
        assertEquals("2019-12-31", p["first_air_date.lte"])
        assertEquals("3|4", p["with_status"])
    }

    @Test
    fun `film-only filters stay off the tv endpoint`() {
        val q = TmdbBrowseQuery(certification = "R", status = TmdbBrowseStatus.InCinemas, networks = listOf(213))
        val tv = params(q, TmdbBrowseMedia.Tv)
        assertNull(tv["certification"])
        assertNull(tv["with_release_type"])
        assertEquals("213", tv["with_networks"])
        val movie = params(q, TmdbBrowseMedia.Movie)
        assertEquals("US", movie["certification_country"])
        assertEquals("R", movie["certification"])
        assertEquals("2|3", movie["with_release_type"])
        assertEquals("GB", movie["region"])
        assertNull(movie["with_networks"])
    }

    @Test
    fun `streaming services carry the region and subscription types`() {
        val p = params(TmdbBrowseQuery(providers = listOf(8, 337)))
        assertEquals("8|337", p["with_watch_providers"])
        assertEquals("GB", p["watch_region"])
        assertEquals("flatrate|free|ads", p["with_watch_monetization_types"])
    }

    @Test
    fun `coming soon starts tomorrow and drops any upper bound`() {
        val p = params(TmdbBrowseQuery(sort = TmdbBrowseSort.Newest, status = TmdbBrowseStatus.Upcoming))
        assertEquals("2026-10-13", p["primary_release_date.gte"])
        assertNull(p["primary_release_date.lte"])
    }

    @Test
    fun `whole-number ratings are sent without a decimal`() {
        assertEquals("7", params(TmdbBrowseQuery(minRating = 7.0))["vote_average.gte"])
        assertEquals("7.5", params(TmdbBrowseQuery(minRating = 7.5))["vote_average.gte"])
    }

    @Test
    fun `interleave alternates and runs on with the longer list`() {
        assertEquals(listOf(1, 10, 2, 20, 3), interleave(listOf(1, 2, 3), listOf(10, 20)))
    }

    @Test
    fun `studio names match by word or word start`() {
        assertTrue(browseNameRelevant("a24", "A24"))
        assertTrue(browseNameRelevant("warn", "Warner Bros. Pictures"))
        assertTrue(browseNameRelevant("studio ghibli", "Studio Ghibli"))
        assertFalse(browseNameRelevant("ghibli", "Studio Gainax"))
        assertTrue(browseNameRelevant("pathe", "Pathé"))
    }

    @Test
    fun `clearing filters keeps the media type and sort`() {
        val q = TmdbBrowseQuery(media = TmdbBrowseMedia.Tv, sort = TmdbBrowseSort.TopRated, genres = listOf(18), language = "ko")
        assertTrue(q.hasFilters)
        val cleared = q.clearedFilters()
        assertFalse(cleared.hasFilters)
        assertEquals(TmdbBrowseMedia.Tv, cleared.media)
        assertEquals(TmdbBrowseSort.TopRated, cleared.sort)
    }

    @Test
    fun `filter labels read like the tracker's`() {
        assertEquals("Genres", browseFilterSummary("Genres", emptyList()))
        assertEquals("Genres: Drama, Crime", browseFilterSummary("Genres", listOf("Drama", "Crime")))
        assertEquals("Genres: Drama +2", browseFilterSummary("Genres", listOf("Drama", "Crime", "War")))
        assertEquals("Rating: 7+, 1,000+ votes", browseRatingLabel(7.0, 1000))
        assertEquals("Year: 2010–…", browseYearLabel(2010, null))
        assertEquals("60–90 min", browseRuntimeLabel(60, 90))
        assertEquals("Length", browseRuntimeLabel(null, null))
    }
}

class DiscoverTypeGroupTest {
    @Test
    fun `relabelled types join movie and series, live tv stays apart`() {
        assertEquals("movie", com.nuvio.app.features.search.discoverTypeGroup("Films"))
        assertEquals("movie", com.nuvio.app.features.search.discoverTypeGroup("movie"))
        assertEquals("series", com.nuvio.app.features.search.discoverTypeGroup("TV"))
        assertEquals("series", com.nuvio.app.features.search.discoverTypeGroup("series"))
        assertEquals("tv", com.nuvio.app.features.search.discoverTypeGroup("tv"))
        assertEquals("anime", com.nuvio.app.features.search.discoverTypeGroup("anime"))
        assertEquals("sport", com.nuvio.app.features.search.discoverTypeGroup("sport"))
    }
}
