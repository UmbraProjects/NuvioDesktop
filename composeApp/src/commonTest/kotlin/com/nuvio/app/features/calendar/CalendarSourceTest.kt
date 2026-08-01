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
    fun `with nothing connected the selection is kept so the connect prompt names it`() {
        assertEquals(
            CalendarSource.MDBLIST,
            resolveCalendarSource(CalendarSource.MDBLIST) { false },
        )
    }
}
