package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseCountdownTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun timestamped(epochMs: Long) =
        ReleaseInstant(epochMs = epochMs, localIsoDate = "2026-09-04", hasTimeOfDay = true)

    private fun dateOnly(localIsoDate: String) =
        ReleaseInstant(epochMs = 0L, localIsoDate = localIsoDate, hasTimeOfDay = false)

    private fun countdownIn(remainingMs: Long): ReleaseCountdown? = releaseCountdown(
        release = timestamped(epochMs = 1_000_000_000_000L + remainingMs),
        nowMs = 1_000_000_000_000L,
        todayIsoDate = "2026-08-30",
    )

    @Test
    fun `four days and one hour out is four days, not five`() {
        // The original report: the calendar-date subtraction called this "In 5 days" because the
        // air date was five dates further down the calendar.
        assertEquals(ReleaseCountdown.InDays(4), countdownIn(4 * day + hour))
    }

    @Test
    fun `a drop just over a day out counts in hours`() {
        // Ted Lasso S4E5: 2026-09-02T04:00Z seen from 03:52 local on Sept 1.
        assertEquals(ReleaseCountdown.InHours(25), countdownIn(25 * hour + 8 * 60_000L))
        assertEquals(ReleaseCountdown.InHours(47), countdownIn(47 * hour + 59 * 60_000L))
        assertEquals(ReleaseCountdown.InDays(2), countdownIn(48 * hour))
    }

    @Test
    fun `the last hour counts in minutes and never reads zero`() {
        assertEquals(ReleaseCountdown.InMinutes(45), countdownIn(45 * 60_000L))
        assertEquals(ReleaseCountdown.InMinutes(1), countdownIn(40_000L))
        assertEquals(ReleaseCountdown.InMinutes(60), countdownIn(hour - 1))
        assertEquals(ReleaseCountdown.InHours(1), countdownIn(hour))
    }

    @Test
    fun `an airing that has happened has no countdown`() {
        assertNull(countdownIn(0L))
        assertNull(countdownIn(-hour))
    }

    @Test
    fun `beyond a week the date is shown instead`() {
        assertEquals(ReleaseCountdown.InDays(7), countdownIn(7 * day + hour))
        assertEquals(ReleaseCountdown.OnDate("2026-09-04"), countdownIn(8 * day))
    }

    @Test
    fun `a bare date keeps calendar-day wording`() {
        val today = "2026-08-30"
        assertEquals(ReleaseCountdown.Today, releaseCountdown(dateOnly("2026-08-30"), 0L, today))
        assertEquals(ReleaseCountdown.Tomorrow, releaseCountdown(dateOnly("2026-08-31"), 0L, today))
        assertEquals(ReleaseCountdown.InDays(5), releaseCountdown(dateOnly("2026-09-04"), 0L, today))
        assertEquals(
            ReleaseCountdown.OnDate("2026-09-30"),
            releaseCountdown(dateOnly("2026-09-30"), 0L, today),
        )
        assertNull(releaseCountdown(dateOnly("2026-08-29"), 0L, today))
    }

    @Test
    fun `the refresh interval matches the unit on screen`() {
        // A minutes badge has to move; a days badge only has to survive midnight.
        assertEquals(15_000L, countdownRefreshIntervalMs(ReleaseCountdown.InMinutes(5)))
        assertEquals(60_000L, countdownRefreshIntervalMs(ReleaseCountdown.InHours(5)))
        assertEquals(300_000L, countdownRefreshIntervalMs(ReleaseCountdown.InDays(5)))
        assertEquals(300_000L, countdownRefreshIntervalMs(ReleaseCountdown.Tomorrow))
        assertEquals(300_000L, countdownRefreshIntervalMs(null))
    }
}
