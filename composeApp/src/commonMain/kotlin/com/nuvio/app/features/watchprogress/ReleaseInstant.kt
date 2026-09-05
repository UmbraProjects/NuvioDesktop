package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import com.nuvio.app.features.watching.domain.isoCalendarDateOrNull

/**
 * A `released` string interpreted once, so every consumer agrees on what it means.
 *
 * Addons hand us two shapes for the same airing and they disagree by a calendar day:
 *  - TMDB `air_date` — `2026-09-03`, the date in the *network's* timezone, no time of day.
 *  - TVDB/Cinemeta — `2026-09-04T01:00:00.000Z`, the same 9pm-ET drop as a UTC instant.
 *
 * The old code took `substringBefore('T')` of the raw string, which made the second form land on
 * Sept 4 for every viewer on earth regardless of their timezone, and pinned the first form to UTC
 * midnight. Both are resolved here to a real instant and to the date it falls on *locally*.
 */
data class ReleaseInstant(
    val epochMs: Long,
    /** The calendar date this airing falls on in the viewer's timezone. */
    val localIsoDate: String,
    /** False when the source only gave us a date, so the instant is that day's local midnight. */
    val hasTimeOfDay: Boolean,
)

fun resolveReleaseInstant(raw: String?): ReleaseInstant? {
    val trimmed = raw?.trim()?.takeIf(String::isNotBlank) ?: return null

    val exactEpochMs = parseTraktIsoDateTimeToEpochMs(trimmed)
    if (exactEpochMs != null) {
        return ReleaseInstant(
            epochMs = exactEpochMs,
            localIsoDate = CurrentDateProvider.localIsoDateAt(exactEpochMs),
            hasTimeOfDay = true,
        )
    }

    val datePart = isoCalendarDateOrNull(trimmed) ?: return null
    val startOfDayMs = CurrentDateProvider.startOfLocalDayEpochMs(datePart) ?: return null
    return ReleaseInstant(
        epochMs = startOfDayMs,
        localIsoDate = datePart,
        hasTimeOfDay = false,
    )
}

/**
 * The calendar date an airing lands on in the viewer's timezone, or null if the string is not a
 * date at all (a bare year, an open-ended `2026-` range).
 *
 * This is the one conversion every "has it aired / how long until it airs" check should use.
 */
fun localReleaseDateOrNull(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf(String::isNotBlank) ?: return null

    // A value with no time of day is already a plain calendar date, so it needs no conversion —
    // and this runs over whole catalogs, so it should not pay for a timezone lookup per item.
    // It also keeps the old string behaviour for impossible dates like 2026-02-31, which have no
    // resolvable instant but were previously still compared as dates.
    if (!trimmed.contains('T')) {
        return isoCalendarDateOrNull(trimmed)
    }
    return resolveReleaseInstant(trimmed)?.localIsoDate
}

/**
 * Picks between the addon's own `released` and TMDB's `air_date` for the same episode.
 *
 * TMDB enrichment used to overwrite unconditionally, so the countdown flipped by a whole day
 * depending on whether the TMDB request happened to time out — the timestamped value survived on
 * failure and was replaced by the date-only one on success. When both describe the same airing the
 * timestamped one wins, because it is the only one that knows what time of day the episode drops.
 *
 * "Same airing" is a ±1 day window: the two forms are expected to disagree by a day (that is the
 * whole point), but a TMDB date further out than that is a genuine schedule correction and should
 * replace the addon's stale timestamp.
 */
fun preferPreciseReleaseDate(addonReleased: String?, tmdbAirDate: String?): String? {
    val tmdb = tmdbAirDate?.trim()?.takeIf(String::isNotBlank) ?: return addonReleased
    val addonInstant = resolveReleaseInstant(addonReleased)?.takeIf { it.hasTimeOfDay }
        ?: return tmdb

    val tmdbDate = isoCalendarDateOrNull(tmdb) ?: return addonReleased
    val driftDays = isoDaysBetween(from = addonInstant.localIsoDate, to = tmdbDate) ?: return tmdb
    return if (driftDays in -1..1) addonReleased else tmdb
}

/** Whole calendar days from [from] to [to], both `yyyy-MM-dd` in the same timezone. */
internal fun isoDaysBetween(from: String, to: String): Int? {
    val start = isoCalendarDateOrNull(from) ?: return null
    val end = isoCalendarDateOrNull(to) ?: return null
    return (com.nuvio.app.features.watching.domain.isoEpochDay(end) -
        com.nuvio.app.features.watching.domain.isoEpochDay(start)).toInt()
}
