package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklDailyVisitTest {
    // 2026-07-23T00:00:00Z
    private val julyTwentyThirdUtcMidnight = 1_784_764_800_000L
    private val dayMillis = 86_400_000L

    @Test
    fun `epoch day rolls over at midnight utc`() {
        val day = SimklDailyVisit.utcEpochDay(julyTwentyThirdUtcMidnight)

        assertEquals(day, SimklDailyVisit.utcEpochDay(julyTwentyThirdUtcMidnight + dayMillis - 1))
        assertEquals(day + 1, SimklDailyVisit.utcEpochDay(julyTwentyThirdUtcMidnight + dayMillis))
    }

    @Test
    fun `first visit of the day is due and later ones are not`() {
        val morning = julyTwentyThirdUtcMidnight + 8 * 60 * 60 * 1000L
        assertTrue(SimklDailyVisit.isDue(lastVisitEpochDay = null, nowMillis = morning))

        val stamped = SimklDailyVisit.utcEpochDay(morning)
        val lateEvening = julyTwentyThirdUtcMidnight + dayMillis - 1
        assertFalse(SimklDailyVisit.isDue(lastVisitEpochDay = stamped, nowMillis = lateEvening))
        assertTrue(
            SimklDailyVisit.isDue(
                lastVisitEpochDay = stamped,
                nowMillis = julyTwentyThirdUtcMidnight + dayMillis,
            ),
        )
    }

    @Test
    fun `a stamp from the future does not suppress the reminder`() {
        val stamp = SimklDailyVisit.utcEpochDay(julyTwentyThirdUtcMidnight) + 30

        assertTrue(SimklDailyVisit.isDue(stamp, julyTwentyThirdUtcMidnight))
    }
}
