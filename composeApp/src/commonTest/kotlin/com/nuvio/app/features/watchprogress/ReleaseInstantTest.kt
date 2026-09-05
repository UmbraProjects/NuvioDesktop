package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.domain.isReleasedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The regression these cover: "Stuart Fails to Save the Universe" S1E7 counted down as either
 * "In 4 days" or "In 5 days" on the same afternoon, depending on whether the TMDB enrichment
 * request had timed out. TMDB said `2026-09-03` (the US network date); the addon said
 * `2026-09-04T01:00:00.000Z` (the same 9pm-ET drop, as an instant).
 */
class ReleaseInstantTest {

    @Test
    fun `date-only release pins to local midnight`() {
        val resolved = resolveReleaseInstant("2026-09-03")

        assertEquals("2026-09-03", resolved?.localIsoDate)
        assertEquals(false, resolved?.hasTimeOfDay)
        assertEquals(CurrentDateProvider.startOfLocalDayEpochMs("2026-09-03"), resolved?.epochMs)
    }

    @Test
    fun `timestamped release resolves to the local day it lands on`() {
        val resolved = resolveReleaseInstant("2026-09-04T01:00:00.000Z")

        assertEquals(1788483600000L, resolved?.epochMs)
        assertEquals(true, resolved?.hasTimeOfDay)
        // Whatever the test machine's zone, the local date must be the one that instant falls on —
        // the old code took substringBefore('T') and called it Sept 4 everywhere.
        assertEquals(CurrentDateProvider.localIsoDateAt(1788483600000L), resolved?.localIsoDate)
    }

    @Test
    fun `unusable values resolve to nothing`() {
        assertNull(resolveReleaseInstant(null))
        assertNull(resolveReleaseInstant("   "))
        assertNull(resolveReleaseInstant("invalid-date"))
        assertNull(resolveReleaseInstant("2026"))
        // isoCalendarDateOrNull accepts day 31 in any month, so this reaches the local-midnight
        // conversion and has to fail there rather than throw.
        assertNull(resolveReleaseInstant("2026-02-31"))
    }

    @Test
    fun `tmdb air date does not overwrite the addon's timestamp for the same airing`() {
        assertEquals(
            "2026-09-04T01:00:00.000Z",
            preferPreciseReleaseDate(
                addonReleased = "2026-09-04T01:00:00.000Z",
                tmdbAirDate = "2026-09-03",
            ),
        )
    }

    @Test
    fun `tmdb air date wins when it is a real reschedule`() {
        assertEquals(
            "2026-09-17",
            preferPreciseReleaseDate(
                addonReleased = "2026-09-04T01:00:00.000Z",
                tmdbAirDate = "2026-09-17",
            ),
        )
    }

    @Test
    fun `tmdb air date wins when the addon has nothing more precise`() {
        assertEquals("2026-09-03", preferPreciseReleaseDate(null, "2026-09-03"))
        assertEquals("2026-09-03", preferPreciseReleaseDate("2026-09-04", "2026-09-03"))
        assertEquals("2026-09-03", preferPreciseReleaseDate("garbage", "2026-09-03"))
    }

    @Test
    fun `missing tmdb air date leaves the addon value alone`() {
        assertEquals("2026-09-04T01:00:00.000Z", preferPreciseReleaseDate("2026-09-04T01:00:00.000Z", null))
        assertEquals("2026-09-04T01:00:00.000Z", preferPreciseReleaseDate("2026-09-04T01:00:00.000Z", "  "))
    }

    @Test
    fun `local release date is what has-it-aired checks compare against`() {
        val raw = "2026-09-04T01:00:00.000Z"
        val localDay = CurrentDateProvider.localIsoDateAt(1788483600000L)

        assertEquals(localDay, localReleaseDateOrNull(raw))
        assertTrue(isReleasedBy(todayIsoDate = localDay, releasedDate = raw))

        // Date-only and non-date values keep their old string behaviour exactly.
        assertEquals("2026-09-03", localReleaseDateOrNull("2026-09-03"))
        assertEquals("2026-02-31", localReleaseDateOrNull("2026-02-31"))
        assertNull(localReleaseDateOrNull("2026"))
        assertNull(localReleaseDateOrNull("2026-"))
        assertTrue(isReleasedBy(todayIsoDate = "2026-08-30", releasedDate = "2026-"))
    }

    @Test
    fun `days between iso dates counts calendar days`() {
        assertEquals(0, isoDaysBetween("2026-08-30", "2026-08-30"))
        assertEquals(5, isoDaysBetween("2026-08-30", "2026-09-04"))
        assertEquals(-1, isoDaysBetween("2026-09-01", "2026-08-31"))
        assertNull(isoDaysBetween("nonsense", "2026-09-04"))
    }

    @Test
    fun `a timestamped release stays on one countdown all day`() {
        // Both forms of the same airing must now produce the same number of days, whichever
        // source answered — that is the whole point of the fix.
        val fromAddon = resolveReleaseInstant("2026-09-04T01:00:00.000Z")!!
        val chosen = preferPreciseReleaseDate("2026-09-04T01:00:00.000Z", "2026-09-03")
        assertEquals(fromAddon.localIsoDate, resolveReleaseInstant(chosen)!!.localIsoDate)
        assertTrue(resolveReleaseInstant(chosen)!!.hasTimeOfDay)
    }
}
