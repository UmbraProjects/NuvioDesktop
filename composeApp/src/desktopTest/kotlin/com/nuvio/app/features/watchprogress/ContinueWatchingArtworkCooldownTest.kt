package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinueWatchingArtworkCooldownTest {

    @Test
    fun `requestArtworkRefreshIfDue respects cooldown and manual requestArtworkRefresh always bumps`() {
        ContinueWatchingEnrichmentCache.clearAll()
        val before = ContinueWatchingEnrichmentCache.artworkRefreshRequests.value
        val t0 = 1_700_000_000_000L
        val cooldown = 6L * 60L * 60L * 1000L

        // First call after clear is due immediately.
        assertTrue(ContinueWatchingEnrichmentCache.requestArtworkRefreshIfDue(cooldownMs = cooldown, nowEpochMs = t0))
        assertEquals(before + 1, ContinueWatchingEnrichmentCache.artworkRefreshRequests.value)

        // Repeated startup within cooldown is skipped.
        assertFalse(ContinueWatchingEnrichmentCache.requestArtworkRefreshIfDue(cooldownMs = cooldown, nowEpochMs = t0 + 60_000L))
        assertEquals(before + 1, ContinueWatchingEnrichmentCache.artworkRefreshRequests.value)

        // Manual resync always refreshes and resets the timestamp.
        ContinueWatchingEnrichmentCache.requestArtworkRefresh(nowEpochMs = t0 + 120_000L)
        assertEquals(before + 2, ContinueWatchingEnrichmentCache.artworkRefreshRequests.value)

        // Still within cooldown of the manual refresh.
        assertFalse(ContinueWatchingEnrichmentCache.requestArtworkRefreshIfDue(cooldownMs = cooldown, nowEpochMs = t0 + cooldown))
        assertEquals(before + 2, ContinueWatchingEnrichmentCache.artworkRefreshRequests.value)

        // Past cooldown of the manual refresh: due again.
        assertTrue(ContinueWatchingEnrichmentCache.requestArtworkRefreshIfDue(cooldownMs = cooldown, nowEpochMs = t0 + 120_000L + cooldown))
        assertEquals(before + 3, ContinueWatchingEnrichmentCache.artworkRefreshRequests.value)
    }
}
