package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklWatchingSeedCacheTest {
    @Test
    fun `unchanged activities refetch after restart when cache is not loaded`() {
        assertFalse(
            shouldReuseSimklWatchingSeedCache(
                latestActivitiesAt = "2026-07-31T18:00:00Z",
                savedActivitiesAt = "2026-07-31T18:00:00Z",
                hasLoadedWatchingSeeds = false,
            ),
        )
    }

    @Test
    fun `unchanged activities reuse a successfully loaded empty cache`() {
        assertTrue(
            shouldReuseSimklWatchingSeedCache(
                latestActivitiesAt = "2026-07-31T18:00:00Z",
                savedActivitiesAt = "2026-07-31T18:00:00Z",
                hasLoadedWatchingSeeds = true,
            ),
        )
    }

    @Test
    fun `changed or unavailable activities do not reuse cache`() {
        assertFalse(
            shouldReuseSimklWatchingSeedCache(
                latestActivitiesAt = "2026-07-31T19:00:00Z",
                savedActivitiesAt = "2026-07-31T18:00:00Z",
                hasLoadedWatchingSeeds = true,
            ),
        )
        assertFalse(
            shouldReuseSimklWatchingSeedCache(
                latestActivitiesAt = null,
                savedActivitiesAt = "2026-07-31T18:00:00Z",
                hasLoadedWatchingSeeds = true,
            ),
        )
    }
}

class SimklWatchingSeedActivitiesStampTest {
    private fun activities(
        showsWatching: String? = null,
        showsCompleted: String? = null,
        animeWatching: String? = null,
        animeCompleted: String? = null,
    ) = SimklActivities(
        tvShows = SimklCategoryActivity(watching = showsWatching, completed = showsCompleted),
        anime = SimklCategoryActivity(watching = animeWatching, completed = animeCompleted),
    )

    @Test
    fun `an episode marked on a show already being watched changes the stamp`() {
        // Only `all` moves: the show stays in the watching list, so no status stamp does.
        fun at(all: String) = SimklActivities(
            tvShows = SimklCategoryActivity(all = all, watching = "2026-09-01T10:00:00Z"),
        )
        val before = simklWatchingSeedActivitiesStamp(at("2026-09-01T10:00:00Z"))
        val after = simklWatchingSeedActivitiesStamp(at("2026-11-15T01:35:00Z"))
        assertNotEquals(before, after)
        assertFalse(
            shouldReuseSimklWatchingSeedCache(
                latestActivitiesAt = after,
                savedActivitiesAt = before,
                hasLoadedWatchingSeeds = true,
            ),
        )
    }

    @Test
    fun `anime progress changes the stamp even when tv shows are untouched`() {
        val before = simklWatchingSeedActivitiesStamp(
            activities(showsWatching = "2026-09-01T10:00:00Z", animeWatching = "2026-09-01T10:00:00Z"),
        )
        val after = simklWatchingSeedActivitiesStamp(
            activities(showsWatching = "2026-09-01T10:00:00Z", animeWatching = "2026-09-09T21:14:00Z"),
        )
        assertNotEquals(before, after)
        assertFalse(
            shouldReuseSimklWatchingSeedCache(
                latestActivitiesAt = after,
                savedActivitiesAt = before,
                hasLoadedWatchingSeeds = true,
            ),
        )
    }

    @Test
    fun `a show completed elsewhere changes the stamp`() {
        val before = simklWatchingSeedActivitiesStamp(
            activities(showsWatching = "2026-09-01T10:00:00Z", showsCompleted = "2026-08-01T10:00:00Z"),
        )
        val after = simklWatchingSeedActivitiesStamp(
            activities(showsWatching = "2026-09-01T10:00:00Z", showsCompleted = "2026-09-09T21:14:00Z"),
        )
        assertNotEquals(before, after)
    }

    @Test
    fun `an unchanged account keeps the stamp stable`() {
        val stamp = { simklWatchingSeedActivitiesStamp(
            activities(
                showsWatching = "2026-09-01T10:00:00Z",
                showsCompleted = "2026-08-01T10:00:00Z",
                animeWatching = "2026-07-01T10:00:00Z",
                animeCompleted = "2026-06-01T10:00:00Z",
            ),
        ) }
        assertEquals(stamp(), stamp())
        assertTrue(
            shouldReuseSimklWatchingSeedCache(
                latestActivitiesAt = stamp(),
                savedActivitiesAt = stamp(),
                hasLoadedWatchingSeeds = true,
            ),
        )
    }

    @Test
    fun `missing activities fall back to always refetching`() {
        assertNull(simklWatchingSeedActivitiesStamp(null))
        assertNull(simklWatchingSeedActivitiesStamp(SimklActivities()))
        assertNull(simklWatchingSeedActivitiesStamp(activities()))
    }

    @Test
    fun `a partially populated payload still yields a comparable stamp`() {
        val stamp = simklWatchingSeedActivitiesStamp(activities(animeWatching = "2026-09-09T21:14:00Z"))
        assertNotNull(stamp)
        assertNotEquals(stamp, simklWatchingSeedActivitiesStamp(activities(showsWatching = "2026-09-09T21:14:00Z")))
    }
}

class SimklWatchedHistoryActivitiesStampTest {
    @Test
    fun `the account-wide stamp is preferred and tracks any change`() {
        val before = simklWatchedHistoryActivitiesStamp(SimklActivities(all = "2026-09-09T21:00:00Z"))
        val after = simklWatchedHistoryActivitiesStamp(SimklActivities(all = "2026-09-09T21:14:00Z"))
        assertNotNull(before)
        assertNotEquals(before, after)
        assertEquals(before, simklWatchedHistoryActivitiesStamp(SimklActivities(all = "2026-09-09T21:00:00Z")))
    }

    @Test
    fun `a payload without the account-wide stamp falls back to the per-category ones`() {
        val stamp = simklWatchedHistoryActivitiesStamp(
            SimklActivities(anime = SimklCategoryActivity(all = "2026-09-09T21:14:00Z")),
        )
        assertNotNull(stamp)
        assertNotEquals(
            stamp,
            simklWatchedHistoryActivitiesStamp(
                SimklActivities(anime = SimklCategoryActivity(all = "2026-09-09T22:00:00Z")),
            ),
        )
    }

    @Test
    fun `an unusable payload never suppresses the fetch`() {
        assertNull(simklWatchedHistoryActivitiesStamp(null))
        assertNull(simklWatchedHistoryActivitiesStamp(SimklActivities()))
        assertNull(simklWatchedHistoryActivitiesStamp(SimklActivities(all = "")))
    }
}
