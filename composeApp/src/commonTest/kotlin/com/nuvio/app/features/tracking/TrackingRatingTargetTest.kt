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
}
