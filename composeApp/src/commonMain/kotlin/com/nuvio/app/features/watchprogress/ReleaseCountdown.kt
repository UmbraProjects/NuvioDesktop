package com.nuvio.app.features.watchprogress

/**
 * What an Up Next badge should say about an airing that has not happened yet.
 *
 * Kept as a description rather than a string so the thresholds are unit-testable without a
 * composition — the bug that produced this file ("In 5 days" on something 4 days and 1 hour away)
 * was a threshold bug, not a wording one.
 */
sealed interface ReleaseCountdown {
    data class InMinutes(val minutes: Int) : ReleaseCountdown
    data class InHours(val hours: Int) : ReleaseCountdown
    data class InDays(val days: Int) : ReleaseCountdown
    data object Today : ReleaseCountdown
    data object Tomorrow : ReleaseCountdown
    data class OnDate(val localIsoDate: String) : ReleaseCountdown
}

private const val MinuteMs = 60_000L
private const val HourMs = 60 * MinuteMs
private const val DayMs = 24 * HourMs

/** Below this, count in whole hours — 25h reads better than "tomorrow" for a 1am drop. */
private const val HoursCutoffMs = 48 * HourMs

/** Above this, a countdown stops being useful and the date is shown instead. */
private const val DaysCutoff = 7

/**
 * [nowMs] and [todayIsoDate] are passed in rather than read here so this stays a pure function of
 * its inputs; the caller supplies them from a ticker.
 */
fun releaseCountdown(
    release: ReleaseInstant,
    nowMs: Long,
    todayIsoDate: String,
): ReleaseCountdown? {
    // Without a time of day the only honest unit is the calendar day, so a bare date keeps the
    // "Today / Tomorrow / In N days" wording it has always had.
    if (!release.hasTimeOfDay) {
        val daysUntil = isoDaysBetween(from = todayIsoDate, to = release.localIsoDate) ?: return null
        return when {
            daysUntil < 0 -> null
            daysUntil == 0 -> ReleaseCountdown.Today
            daysUntil == 1 -> ReleaseCountdown.Tomorrow
            daysUntil <= DaysCutoff -> ReleaseCountdown.InDays(daysUntil)
            else -> ReleaseCountdown.OnDate(release.localIsoDate)
        }
    }

    val remainingMs = release.epochMs - nowMs
    if (remainingMs <= 0L) return null

    return when {
        // Rounded up, so a badge never reads "In 0m" while the episode is still 40 seconds out.
        remainingMs < HourMs -> ReleaseCountdown.InMinutes(
            ((remainingMs + MinuteMs - 1) / MinuteMs).toInt().coerceAtLeast(1),
        )
        // Whole units elapse, not calendar boundaries: 4 days and 1 hour away is "In 4 days",
        // where the old calendar-date subtraction called it 5.
        remainingMs < HoursCutoffMs -> ReleaseCountdown.InHours((remainingMs / HourMs).toInt())
        remainingMs < (DaysCutoff + 1) * DayMs -> ReleaseCountdown.InDays((remainingMs / DayMs).toInt())
        else -> ReleaseCountdown.OnDate(release.localIsoDate)
    }
}

/**
 * How long a badge showing [countdown] can go without being recomputed before it reads wrong.
 *
 * A badge is only ever wrong between ticks, so this is the accuracy knob: fast enough that a
 * minute counter moves, slow enough that a row of cards is not waking up constantly. The
 * day-and-longer case still needs a poll because a bare date changes meaning at local midnight.
 */
fun countdownRefreshIntervalMs(countdown: ReleaseCountdown?): Long = when (countdown) {
    is ReleaseCountdown.InMinutes -> 15 * 1_000L
    is ReleaseCountdown.InHours -> MinuteMs
    else -> 5 * MinuteMs
}
