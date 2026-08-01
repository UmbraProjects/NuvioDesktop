package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three MDBList switches are nested, not independent: ratings, then tracking, then Continue
 * Watching. Each inner one is meaningless without the one outside it, and the derived flags are
 * what the watch-progress and registry code branch on.
 */
class MdbListSettingsGatingTest {
    @Test
    fun `tracking needs a key`() {
        assertFalse(MdbListSettings(trackingEnabled = true, apiKey = "").isTrackingActive)
        assertTrue(MdbListSettings(trackingEnabled = true, apiKey = "k").isTrackingActive)
    }

    @Test
    fun `ratings and tracking are independent consents`() {
        val ratingsOnly = MdbListSettings(enabled = true, apiKey = "k")
        assertFalse(ratingsOnly.isTrackingActive)

        val trackingOnly = MdbListSettings(trackingEnabled = true, apiKey = "k")
        assertTrue(trackingOnly.isTrackingActive)
        assertFalse(trackingOnly.enabled)
    }

    @Test
    fun `continue watching requires tracking, not just the flag`() {
        assertFalse(
            MdbListSettings(asContinueWatchingSource = true, apiKey = "k").isContinueWatchingSource,
        )
        assertFalse(
            MdbListSettings(asContinueWatchingSource = true, trackingEnabled = true, apiKey = "")
                .isContinueWatchingSource,
        )
        assertTrue(
            MdbListSettings(asContinueWatchingSource = true, trackingEnabled = true, apiKey = "k")
                .isContinueWatchingSource,
        )
    }

    @Test
    fun `calendar source requires tracking, not just the flag`() {
        assertFalse(
            MdbListSettings(asCalendarSource = true, apiKey = "k").isCalendarSource,
        )
        assertFalse(
            MdbListSettings(asCalendarSource = true, trackingEnabled = true, apiKey = "")
                .isCalendarSource,
        )
        assertTrue(
            MdbListSettings(asCalendarSource = true, trackingEnabled = true, apiKey = "k")
                .isCalendarSource,
        )
    }

    @Test
    fun `an empty key leaves nothing active`() {
        val settings = MdbListSettings(
            enabled = true,
            trackingEnabled = true,
            asContinueWatchingSource = true,
            asCalendarSource = true,
            apiKey = "",
        )

        assertFalse(settings.hasApiKey)
        assertFalse(settings.isTrackingActive)
        assertFalse(settings.isContinueWatchingSource)
        assertFalse(settings.isCalendarSource)
    }
}
