package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertFalse
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
