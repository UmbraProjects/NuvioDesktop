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
    fun `entries survive the disk round trip losslessly`() {
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
        val encoded = json.encodeToString(
            SimklCalendarCacheEntry.serializer(),
            SimklCalendarCacheEntry(
                builtAtEpochMs = 1L,
                profileId = 1,
                activitiesStamp = "shows=s1|anime=|movies=m1",
                entries = entries.map { it.toCached() },
            ),
        )
        val decoded = json.decodeFromString(SimklCalendarCacheEntry.serializer(), encoded)
        assertEquals(SimklCalendarCacheEntry.VERSION, decoded.version)
        assertEquals(entries, decoded.entries.map { it.toEntry() })
    }

    @Test
    fun `an older file without the new fields still reaches the version check`() {
        val decoded = json.decodeFromString(SimklCalendarCacheEntry.serializer(), """{"version":0}""")
        assertEquals(0, decoded.version)
        assertEquals(SimklCalendarCacheEntry.UNKNOWN_PROFILE, decoded.profileId)
    }
}
