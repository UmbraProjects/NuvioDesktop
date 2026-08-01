package com.nuvio.app.features.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One source at a time, and an upgrade that keeps what the user was already seeing.
 */
class ContinueWatchingSourceTest {
    @Test
    fun `a disconnected provider falls back to local, not to another provider`() {
        // The old chain fell through MDBList → SIMKL → Trakt, so disconnecting one silently handed
        // Continue Watching to whichever was next. A selection is now a selection.
        assertEquals(
            ContinueWatchingSource.LOCAL,
            resolveContinueWatchingSource(ContinueWatchingSource.MDBLIST) { false },
        )
        assertEquals(
            ContinueWatchingSource.LOCAL,
            resolveContinueWatchingSource(ContinueWatchingSource.MDBLIST) {
                it == TrackingProviderId.SIMKL
            },
        )
        assertEquals(
            ContinueWatchingSource.MDBLIST,
            resolveContinueWatchingSource(ContinueWatchingSource.MDBLIST) {
                it == TrackingProviderId.MDBLIST
            },
        )
    }

    @Test
    fun `local needs no provider`() {
        assertEquals(
            ContinueWatchingSource.LOCAL,
            resolveContinueWatchingSource(ContinueWatchingSource.LOCAL) { false },
        )
        assertNull(ContinueWatchingSource.LOCAL.providerId)
    }

    @Test
    fun `migration keeps whichever source was actually winning`() {
        // Same precedence the old chain used, so the upgrade is invisible.
        assertEquals(
            ContinueWatchingSource.MDBLIST,
            migratedContinueWatchingSource(true, simklWasCwSource = true, traktWasCwSource = true),
        )
        assertEquals(
            ContinueWatchingSource.SIMKL,
            migratedContinueWatchingSource(false, simklWasCwSource = true, traktWasCwSource = true),
        )
        assertEquals(
            ContinueWatchingSource.TRAKT,
            migratedContinueWatchingSource(false, simklWasCwSource = false, traktWasCwSource = true),
        )
        assertEquals(
            ContinueWatchingSource.LOCAL,
            migratedContinueWatchingSource(false, simklWasCwSource = false, traktWasCwSource = false),
        )
    }

    @Test
    fun `stored ids round-trip and unknown values do not resolve`() {
        ContinueWatchingSource.entries.forEach { source ->
            assertEquals(source, ContinueWatchingSource.fromStorage(source.storageId))
        }
        assertEquals(ContinueWatchingSource.YAMTRACK, ContinueWatchingSource.fromStorage("yamtrack"))
        assertNull(ContinueWatchingSource.fromStorage(null))
    }

    @Test
    fun `every source maps to at most one provider`() {
        val providers = ContinueWatchingSource.entries.mapNotNull { it.providerId }

        assertEquals(providers.size, providers.distinct().size)
    }
}
