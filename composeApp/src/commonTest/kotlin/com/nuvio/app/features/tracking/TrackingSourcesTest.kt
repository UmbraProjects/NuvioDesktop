package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.trakt.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackingSourcesTest {
    @Test
    fun `remote watch source falls back when its provider is disconnected`() {
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            effectiveTrackingWatchProgressSource(WatchProgressSource.TRAKT) { false },
        )
        assertEquals(
            WatchProgressSource.TRAKT,
            effectiveTrackingWatchProgressSource(WatchProgressSource.TRAKT) {
                it == TrackingProviderId.TRAKT
            },
        )
    }

    @Test
    fun `remote library source falls back to local when disconnected`() {
        assertEquals(
            LibrarySourceMode.LOCAL,
            effectiveTrackingLibrarySourceMode(LibrarySourceMode.SIMKL) { false },
        )
        assertEquals(
            LibrarySourceMode.SIMKL,
            effectiveTrackingLibrarySourceMode(LibrarySourceMode.SIMKL) {
                it == TrackingProviderId.SIMKL
            },
        )
    }

    @Test
    fun `a connected sibling provider does not satisfy another source`() {
        assertEquals(
            LibrarySourceMode.LOCAL,
            effectiveTrackingLibrarySourceMode(LibrarySourceMode.TRAKT) {
                it == TrackingProviderId.SIMKL
            },
        )
    }

    @Test
    fun `local sources have no remote provider`() {
        assertNull(WatchProgressSource.NUVIO_SYNC.trackingProviderId)
        assertNull(LibrarySourceMode.LOCAL.trackingProviderId)
    }

    @Test
    fun `provider ids round-trip through their storage names`() {
        TrackingProviderId.entries.forEach { provider ->
            assertEquals(provider, TrackingProviderId.fromStorage(provider.storageId))
            assertEquals(provider, TrackingProviderId.fromStorage(provider.name))
        }
        assertNull(TrackingProviderId.fromStorage("letterboxd"))
        assertNull(TrackingProviderId.fromStorage(null))
    }
}
