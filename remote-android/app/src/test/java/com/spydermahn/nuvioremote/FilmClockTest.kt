package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilmClockTest {
    private val minute = 60_000L

    @Test
    fun endsAtIsNowPlusWhatIsLeft() {
        assertEquals(1_000L + 100 * minute, filmEndsAt(10 * minute, 110 * minute, null, 1_000L))
    }

    @Test
    fun speedShortensWhatIsLeft() {
        assertEquals(50 * minute, filmEndsAt(0L, 100 * minute, 200, 0L))
        assertEquals(80 * minute, filmEndsAt(0L, 100 * minute, 125, 0L))
    }

    @Test
    fun positionPastTheEndEndsNow() {
        assertEquals(5L, filmEndsAt(200 * minute, 100 * minute, 100, 5L))
    }

    @Test
    fun noTimelineHasNoEnd() {
        assertNull(filmEndsAt(0L, 0L, 100, 0L))
    }

    @Test
    fun runtimeInWords() {
        assertEquals("1h 50m", runtimeWords(110 * minute))
        assertEquals("2h", runtimeWords(120 * minute))
        assertEquals("48m", runtimeWords(48 * minute))
        assertEquals("under a minute", runtimeWords(20_000L))
    }
}
