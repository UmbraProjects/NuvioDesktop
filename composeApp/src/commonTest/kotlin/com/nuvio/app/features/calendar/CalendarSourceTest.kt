package com.nuvio.app.features.calendar

import com.nuvio.app.features.tracking.CalendarSource
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.migratedCalendarSource
import com.nuvio.app.features.tracking.resolveCalendarSource
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarSourceTest {
    @Test
    fun `Trakt remains the default source`() {
        assertEquals(
            CalendarSource.TRAKT,
            migratedCalendarSource(
                mdbListWasCalendarSource = false,
                simklWasCalendarSource = false,
            ),
        )
    }

    @Test
    fun `SIMKL replaces Trakt when selected`() {
        assertEquals(
            CalendarSource.SIMKL,
            migratedCalendarSource(
                mdbListWasCalendarSource = false,
                simklWasCalendarSource = true,
            ),
        )
    }

    @Test
    fun `MDBList takes priority when multiple legacy flags are enabled`() {
        assertEquals(
            CalendarSource.MDBLIST,
            migratedCalendarSource(
                mdbListWasCalendarSource = true,
                simklWasCalendarSource = true,
            ),
        )
    }

    @Test
    fun `stored selection is case insensitive`() {
        assertEquals(CalendarSource.SIMKL, CalendarSource.fromStorage("Simkl"))
    }

    @Test
    fun `a connected selection is kept`() {
        assertEquals(
            CalendarSource.SIMKL,
            resolveCalendarSource(CalendarSource.SIMKL) { it == TrackingProviderId.SIMKL },
        )
    }

    @Test
    fun `an unconnected selection falls back to a connected provider`() {
        // The default is Trakt, which no longer issues API keys.
        assertEquals(
            CalendarSource.SIMKL,
            resolveCalendarSource(CalendarSource.TRAKT) { it == TrackingProviderId.SIMKL },
        )
    }

    @Test
    fun `with nothing connected the local calendar is used`() {
        assertEquals(
            CalendarSource.NUVIO,
            resolveCalendarSource(CalendarSource.MDBLIST) { false },
        )
    }

    @Test
    fun `the local calendar is kept even when a provider is connected`() {
        assertEquals(
            CalendarSource.NUVIO,
            resolveCalendarSource(CalendarSource.NUVIO) { true },
        )
    }
}
