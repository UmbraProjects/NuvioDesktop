package com.nuvio.app.features.simkl

/**
 * Once-per-day gate for the optional "open simkl.com on startup" reminder.
 *
 * SIMKL grants free Pro to accounts that visit the site on 20 of 30 days in a month, so the
 * only thing that matters is whether the site has already been opened during the current UTC
 * day. Days are derived straight from epoch millis (no timezone lookup, no date parsing), which
 * puts the reset exactly at midnight UTC regardless of the machine's local timezone.
 */
internal object SimklDailyVisit {
    const val URL = "https://simkl.com"

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Days elapsed since the epoch in UTC; increments at midnight UTC. */
    fun utcEpochDay(epochMillis: Long): Long {
        val days = epochMillis / MILLIS_PER_DAY
        return if (epochMillis < 0 && epochMillis % MILLIS_PER_DAY != 0L) days - 1 else days
    }

    /**
     * True when the site has not been opened yet during the UTC day containing [nowMillis].
     * A [lastVisitEpochDay] in the future (clock moved backwards) also counts as due, so a bad
     * timestamp cannot suppress the reminder indefinitely.
     */
    fun isDue(lastVisitEpochDay: Long?, nowMillis: Long): Boolean =
        lastVisitEpochDay != utcEpochDay(nowMillis)
}
