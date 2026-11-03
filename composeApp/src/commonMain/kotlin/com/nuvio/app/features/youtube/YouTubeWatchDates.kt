package com.nuvio.app.features.youtube

import com.nuvio.app.features.player.skip.DateComponents

/*
 * fork:youtube-history
 *
 * When a video was watched, as a day (no time): from the day headings of YouTube's History page
 * ("Today", "Yesterday", "Monday", "Oct 12", "Oct 12, 2024") and from playback in Nuvio. Kept as ISO
 * dates ("2026-11-03") and shown on the cards in the format chosen in Settings > YouTube account.
 */

/** Where the cards show the day a video was watched (Settings > YouTube account > Watched date). */
enum class YouTubeWatchedDateMode(val label: String) {
    Everywhere("Everywhere"),
    HistoryOnly("History tab only"),
    Off("Off"),
}

/** How that day is written. [example] is 3 November 2026 in the format, for the settings list. */
enum class YouTubeDateFormat(val example: String) {
    DayMonthYear("3 Nov 2026"),
    MonthDayYear("Nov 3, 2026"),
    Iso("2026-11-03"),
    DayMonthYearNumeric("03/11/2026"),
    MonthDayYearNumeric("11/03/2026"),
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

internal fun DateComponents.isoDate(): String =
    "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

internal fun parseIsoDate(iso: String): DateComponents? {
    val parts = iso.split('-').takeIf { it.size == 3 } ?: return null
    val (y, m, d) = parts.map { it.toIntOrNull() ?: return null }
    return DateComponents(y, m, d).takeIf { m in 1..12 && d in 1..31 }
}

/** [iso] written in [format]; the ISO text itself when it is not a date. */
internal fun formatYouTubeWatchDate(iso: String, format: YouTubeDateFormat): String {
    val date = parseIsoDate(iso) ?: return iso
    val dd = date.day.toString().padStart(2, '0')
    val mm = date.month.toString().padStart(2, '0')
    val month = MONTHS[date.month - 1]
    return when (format) {
        YouTubeDateFormat.DayMonthYear -> "${date.day} $month ${date.year}"
        YouTubeDateFormat.MonthDayYear -> "$month ${date.day}, ${date.year}"
        YouTubeDateFormat.Iso -> date.isoDate()
        YouTubeDateFormat.DayMonthYearNumeric -> "$dd/$mm/${date.year}"
        YouTubeDateFormat.MonthDayYearNumeric -> "$mm/$dd/${date.year}"
    }
}

/**
 * The day a History page heading stands for, as an ISO date, or null when it is not one. YouTube (asked
 * in English) names the last two days, then the weekday for the rest of the week, then "Oct 12", with
 * the year added once it is not this year's.
 */
internal fun youTubeHistoryDate(heading: String, today: DateComponents): String? {
    val text = heading.trim().lowercase()
    val todayDays = daysFromCivil(today)
    when (text) {
        "today" -> return today.isoDate()
        "yesterday" -> return civilFromDays(todayDays - 1).isoDate()
    }
    val weekday = WEEKDAYS.indexOf(text)
    if (weekday >= 0) {
        // 1970-01-01 was a Thursday (index 3 with Monday as 0). The most recent such day before today.
        val todayWeekday = ((todayDays % 7 + 7 + 3) % 7).toInt()
        val back = ((todayWeekday - weekday) + 7) % 7
        return civilFromDays(todayDays - (if (back == 0) 7 else back)).isoDate()
    }
    val match = MONTH_DAY.matchEntire(text) ?: return null
    val month = MONTHS.indexOfFirst { it.lowercase() == match.groupValues[1] } + 1
    if (month == 0) return null
    val day = match.groupValues[2].toInt()
    val year = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
        // No year: this year's, unless that day has not come yet (a heading from late last year).
        ?: DateComponents(today.year, month, day).let { if (daysFromCivil(it) > todayDays) today.year - 1 else today.year }
    return DateComponents(year, month, day).takeIf { day in 1..31 }?.isoDate()
}

private val MONTH_DAY = Regex("""^([a-z]{3})[a-z]*\.?\s+(\d{1,2})(?:,\s*(\d{4}))?$""")

/** Days since 1970-01-01 (Howard Hinnant's civil-date algorithm). */
internal fun daysFromCivil(date: DateComponents): Long {
    val y = (if (date.month <= 2) date.year - 1 else date.year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (date.month + 9) % 12
    val doy = (153 * mp + 2) / 5 + date.day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

internal fun civilFromDays(days: Long): DateComponents {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return DateComponents(year, month, day)
}
