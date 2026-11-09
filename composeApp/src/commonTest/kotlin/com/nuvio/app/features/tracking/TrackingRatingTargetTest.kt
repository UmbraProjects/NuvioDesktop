package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackingRatingTargetTest {
    @Test
    fun `MDBList library source targets native MDBList ratings`() {
        assertEquals(
            TrackingProviderId.MDBLIST,
            trackingRatingProviderFor(LibrarySourceMode.MDBLIST),
        )
        assertEquals("MDBList", trackingRatingProviderDisplayName(TrackingProviderId.MDBLIST))
    }

    @Test
    fun `providers without rating writers are not offered`() {
        assertNull(trackingRatingProviderFor(LibrarySourceMode.LOCAL))
        assertNull(trackingRatingProviderFor(LibrarySourceMode.TRAKT))
    }

    @Test
    fun `same as library follows the library source`() {
        assertEquals(
            TrackingProviderId.SIMKL,
            trackingRatingProviderFor(TrackingRatingSource.LIBRARY, LibrarySourceMode.SIMKL),
        )
        assertNull(trackingRatingProviderFor(TrackingRatingSource.LIBRARY, LibrarySourceMode.TRAKT))
    }

    @Test
    fun `a named service wins over the library source`() {
        assertEquals(
            TrackingProviderId.MDBLIST,
            trackingRatingProviderFor(TrackingRatingSource.MDBLIST, LibrarySourceMode.TRAKT),
        )
        assertEquals(
            TrackingProviderId.YAMTRACK,
            trackingRatingProviderFor(TrackingRatingSource.YAMTRACK, LibrarySourceMode.SIMKL),
        )
    }

    @Test
    fun `off rates nowhere`() {
        assertNull(trackingRatingProviderFor(TrackingRatingSource.OFF, LibrarySourceMode.SIMKL))
    }

    @Test
    fun `stored choices round trip and unknown values are rejected`() {
        TrackingRatingSource.entries.forEach {
            assertEquals(it, TrackingRatingSource.fromStorage(it.storageId))
        }
        assertNull(TrackingRatingSource.fromStorage("trakt"))
    }
}
