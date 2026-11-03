package com.nuvio.app.features.youtube

import com.nuvio.app.features.player.skip.DateComponents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeWatchDatesTest {
    // A Tuesday.
    private val today = DateComponents(2026, 11, 3)

    @Test
    fun historyHeadingsBecomeDays() {
        assertEquals("2026-11-03", youTubeHistoryDate("Today", today))
        assertEquals("2026-11-02", youTubeHistoryDate("Yesterday", today))
        // Weekdays are the past week's: Sunday was two days back, Tuesday a week back.
        assertEquals("2026-11-01", youTubeHistoryDate("Sunday", today))
        assertEquals("2026-10-27", youTubeHistoryDate("Tuesday", today))
        assertEquals("2026-10-12", youTubeHistoryDate("Oct 12", today))
        // A day later in the year than today is last year's.
        assertEquals("2025-12-24", youTubeHistoryDate("Dec 24", today))
        assertEquals("2024-03-05", youTubeHistoryDate("Mar 5, 2024", today))
        assertNull(youTubeHistoryDate("Watch later", today))
    }

    @Test
    fun formatsWriteTheDayAsChosen() {
        assertEquals("3 Nov 2026", formatYouTubeWatchDate("2026-11-03", YouTubeDateFormat.DayMonthYear))
        assertEquals("Nov 3, 2026", formatYouTubeWatchDate("2026-11-03", YouTubeDateFormat.MonthDayYear))
        assertEquals("2026-11-03", formatYouTubeWatchDate("2026-11-03", YouTubeDateFormat.Iso))
        assertEquals("03/11/2026", formatYouTubeWatchDate("2026-11-03", YouTubeDateFormat.DayMonthYearNumeric))
        assertEquals("11/03/2026", formatYouTubeWatchDate("2026-11-03", YouTubeDateFormat.MonthDayYearNumeric))
    }

    @Test
    fun civilDaysRoundTrip() {
        for (date in listOf(DateComponents(1970, 1, 1), DateComponents(2024, 2, 29), DateComponents(2026, 11, 3))) {
            assertEquals(date, civilFromDays(daysFromCivil(date)))
        }
        assertEquals(0L, daysFromCivil(DateComponents(1970, 1, 1)))
    }

    @Test
    fun csvFieldsAreQuotedOnlyWhenNeeded() {
        assertEquals("plain", csvField("plain"))
        assertEquals("\"a, b\"", csvField("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
    }

    @Test
    fun historyPagesKeepEachVideosDayHeading() {
        fun video(id: String) = """{"videoRenderer":{"videoId":"$id","title":{"runs":[{"text":"V $id"}]}}}"""
        fun section(heading: String, vararg ids: String) =
            """{"itemSectionRenderer":{"header":{"itemSectionHeaderRenderer":{"title":{"runs":[{"text":"$heading"}]}}},"contents":[${ids.joinToString(",") { video(it) }}]}}"""
        val body = """{"contents":{"sectionListRenderer":{"contents":[${section("Today", "aaaaaaaaaaa")},${section("Oct 12", "bbbbbbbbbbb", "ccccccccccc")}]}}}"""

        val page = parseYouTubeFeed(body)

        assertEquals(3, page.videos.size)
        assertEquals(mapOf("aaaaaaaaaaa" to "Today", "bbbbbbbbbbb" to "Oct 12", "ccccccccccc" to "Oct 12"), page.sections)
    }
}
