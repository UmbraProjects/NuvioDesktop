package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimklContinueWatchingWindowTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun `a day cap produces a cutoff that many days back`() {
        assertEquals(
            now - 30L * day,
            simklContinueWatchingCutoffMs(daysCap = 30, nowEpochMs = now),
        )
    }

    @Test
    fun `the all-history cap disables the window`() {
        assertEquals(
            SIMKL_NO_CW_CUTOFF,
            simklContinueWatchingCutoffMs(daysCap = SIMKL_CW_DAYS_CAP_ALL, nowEpochMs = now),
        )
    }

    @Test
    fun `a negative cap is treated as no window rather than a future cutoff`() {
        assertEquals(
            SIMKL_NO_CW_CUTOFF,
            simklContinueWatchingCutoffMs(daysCap = -5, nowEpochMs = now),
        )
    }

    @Test
    fun `the default window is 30 days`() {
        assertEquals(30, SIMKL_DEFAULT_CW_DAYS_CAP)
        assertEquals(
            now - 30L * day,
            simklContinueWatchingCutoffMs(SIMKL_DEFAULT_CW_DAYS_CAP, now),
        )
    }

    @Test
    fun `an undated row falls outside every window`() {
        // Watching-list seeds with no parseable last_watched_at carry 0, not "now". Stamping them
        // with the current time made a years-old show pass a 30-day window and sort to the top.
        val cutoff = simklContinueWatchingCutoffMs(daysCap = 30, nowEpochMs = now)
        assertTrue(0L < cutoff, "an undated row must not satisfy lastUpdatedEpochMs >= cutoff")
    }
}
