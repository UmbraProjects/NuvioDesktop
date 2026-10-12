package com.nuvio.app.features.simkl

import com.nuvio.app.features.trakt.TraktCalendarEntry
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SimklCalendarCacheTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun activities(
        shows: String? = null,
        anime: String? = null,
        movies: String? = null,
    ) = SimklActivities(
        tvShows = SimklCategoryActivity(all = shows),
        anime = SimklCategoryActivity(all = anime),
        movies = SimklCategoryActivity(all = movies),
    )

    @Test
    fun `stamp changes when anime or movies change, not only shows`() {
        val base = simklCalendarActivitiesStamp(activities(shows = "s1", anime = "a1", movies = "m1"))
        assertEquals(base, simklCalendarActivitiesStamp(activities(shows = "s1", anime = "a1", movies = "m1")))
        assertNotEquals(base, simklCalendarActivitiesStamp(activities(shows = "s1", anime = "a2", movies = "m1")))
        assertNotEquals(base, simklCalendarActivitiesStamp(activities(shows = "s1", anime = "a1", movies = "m2")))
    }

    @Test
    fun `stamp is null when nothing is reported`() {
        assertNull(simklCalendarActivitiesStamp(null))
        assertNull(simklCalendarActivitiesStamp(activities()))
    }

    @Test
    fun `items survive the disk round trip losslessly`() {
        val entries = listOf(
            TraktCalendarEntry(
                dateKey = "2026-09-20",
                type = "series",
                contentId = "tt0903747",
                title = "Breaking Bad",
                posterUrl = "https://wsrv.nl/?url=poster.jpg",
                seasonNumber = 5,
                episodeNumber = 14,
                episodeTitle = "Ozymandias",
            ),
            TraktCalendarEntry(
                dateKey = "2026-10-01",
                type = "movie",
                contentId = "tmdb:12345",
                title = "Untitled",
                posterUrl = null,
            ),
        )
        val item = SimklCalendarCachedItem(
            info = info(SimklCalendarItemInfo.KIND_TV, 1).copy(ids = SimklMediaIds(simkl = 1, imdb = "tt0903747")),
            fetchedAtEpochMs = 5L,
            entries = entries.map { it.toCached() },
        )
        val encoded = json.encodeToString(
            SimklCalendarCacheEntry.serializer(),
            SimklCalendarCacheEntry(
                builtAtEpochMs = 1L,
                profileId = 1,
                activitiesStamp = "shows=s1|anime=|movies=m1",
                changesCheckedAtEpochMs = 7L,
                items = listOf(item),
            ),
        )
        val decoded = json.decodeFromString(SimklCalendarCacheEntry.serializer(), encoded)
        assertEquals(SimklCalendarCacheEntry.VERSION, decoded.version)
        assertEquals(7L, decoded.changesCheckedAtEpochMs)
        assertEquals(listOf(item), decoded.items)
        assertEquals(entries, decoded.items.single().entries.map { it.toEntry() })
    }

    @Test
    fun `a v1 calendar file is a version miss, not a crash`() {
        val decoded = json.decodeFromString(
            SimklCalendarCacheEntry.serializer(),
            """{"version":1,"builtAtEpochMs":1,"profileId":1,"entries":[{"dateKey":"2026-09-20","type":"series","contentId":"tt1","title":"x"}]}""",
        )
        assertNotEquals(SimklCalendarCacheEntry.VERSION, decoded.version)
    }

    private fun info(kind: String, id: Int) = SimklCalendarItemInfo(
        kind = kind,
        simklId = id,
        contentId = "simkl:$id",
        title = "Item $id",
    )

    private fun cached(info: SimklCalendarItemInfo, fetchedAt: Long = 100L) =
        SimklCalendarCachedItem(info = info, fetchedAtEpochMs = fetchedAt)

    @Test
    fun `an unchanged library with no catalog changes fetches nothing`() {
        val library = listOf(info("tv", 1), info("anime", 2), info("movie", 3))
        val toFetch = simklCalendarItemsToFetch(
            library = library,
            cached = library.associate { it.key to cached(it) },
            changes = SimklChangesResponse(),
            refreshAll = false,
        )
        assertEquals(emptyList(), toFetch)
    }

    @Test
    fun `only new, failed, re-described and changed items are fetched`() {
        val kept = info("tv", 1)
        val failed = info("tv", 2)
        val renamed = info("anime", 3)
        val changedMovie = info("movie", 4)
        val brandNew = info("tv", 5)
        val sameIdOtherBucket = info("tv", 4)
        val cache = mapOf(
            kept.key to cached(kept),
            failed.key to cached(failed, fetchedAt = 0L),
            renamed.key to cached(renamed.copy(title = "Old title")),
            changedMovie.key to cached(changedMovie),
            sameIdOtherBucket.key to cached(sameIdOtherBucket),
        )
        val toFetch = simklCalendarItemsToFetch(
            library = listOf(kept, failed, renamed, changedMovie, brandNew, sameIdOtherBucket),
            cached = cache,
            // id 4 changed as a MOVIE only; the show that happens to share the number is untouched.
            changes = SimklChangesResponse(movies = listOf(4)),
            refreshAll = false,
        )
        assertEquals(listOf(failed, renamed, changedMovie, brandNew), toFetch)
    }

    @Test
    fun `refreshAll fetches every library item`() {
        val library = listOf(info("tv", 1), info("movie", 2))
        val toFetch = simklCalendarItemsToFetch(
            library = library,
            cached = library.associate { it.key to cached(it) },
            changes = null,
            refreshAll = true,
        )
        assertEquals(library, toFetch)
    }

    @Test
    fun `an empty changes response decodes`() {
        val decoded = json.decodeFromString(SimklChangesResponse.serializer(), "{}")
        assertEquals(SimklChangesResponse(), decoded)
        val some = json.decodeFromString(SimklChangesResponse.serializer(), """{"shows":[17465],"anime":[39687]}""")
        assertEquals(listOf(17465), some.shows)
        assertEquals(emptyList(), some.movies)
    }

    @Test
    fun `an older file without the new fields still reaches the version check`() {
        val decoded = json.decodeFromString(SimklCalendarCacheEntry.serializer(), """{"version":0}""")
        assertEquals(0, decoded.version)
        assertEquals(SimklCalendarCacheEntry.UNKNOWN_PROFILE, decoded.profileId)
    }
}
