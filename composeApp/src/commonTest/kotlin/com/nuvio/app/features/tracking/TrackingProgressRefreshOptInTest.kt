package com.nuvio.app.features.tracking

import com.nuvio.app.features.mdblist.MdbListScrobbleAdapter
import com.nuvio.app.features.simkl.SimklScrobbleAdapter
import com.nuvio.app.features.yamtrack.YamtrackScrobbleAdapter
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins which shipped scrobblers re-anchor a running session.
 *
 * A provider that extrapolates position from the last `start` it received drifts for as long as
 * playback runs above 1x, and the drift is invisible in code review — it only shows up as a live
 * card that is minutes behind. Declaring the interval is the whole opt-in, so it is asserted here
 * rather than left to be noticed during playback.
 */
class TrackingProgressRefreshOptInTest {
    @Test
    fun `providers with a live session card re-anchor periodically`() {
        listOf(
            SimklScrobbleAdapter,
            MdbListScrobbleAdapter,
            YamtrackScrobbleAdapter,
        ).forEach { scrobbler ->
            val interval = assertNotNull(
                scrobbler.progressRefreshIntervalMs,
                "${scrobbler.providerId} must opt into progress refresh",
            )
            // Loose bounds on purpose: the point is that the cadence stays inside the same order of
            // magnitude, not that any particular value is sacred.
            assertTrue(
                interval in 60_000L..10 * 60 * 1000L,
                "${scrobbler.providerId} refresh interval $interval ms is outside 1–10 minutes",
            )
        }
    }
}
