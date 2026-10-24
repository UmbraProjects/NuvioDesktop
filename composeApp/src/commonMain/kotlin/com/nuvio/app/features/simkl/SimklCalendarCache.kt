package com.nuvio.app.features.simkl

import com.nuvio.app.features.trakt.TraktCalendarEntry
import kotlinx.serialization.Serializable

/**
 * On-disk form of the SIMKL calendar.
 *
 * The calendar is one request per followed show plus one per watchlisted movie — a couple of
 * hundred for a full library — and it was fetched again on every launch because the repository
 * only ever cached in memory. `/sync/activities` was already consulted before each load; this makes
 * that check worth something across launches, so a restart with nothing changed costs one request.
 *
 * Follows the Discover row cache: [VERSION] is checked on load and a mismatch is a miss, and
 * **every field needs a default** so an older file fails the version check rather than
 * deserialisation (see `DiscoverRowCacheEntry`).
 */
@Serializable
internal data class SimklCalendarCacheEntry(
    val version: Int = VERSION,
    val builtAtEpochMs: Long = 0L,
    /** The calendar is the profile's own SIMKL library, so another profile's file is not stale — it is somebody else's. */
    val profileId: Int = UNKNOWN_PROFILE,
    /** [simklCalendarActivitiesStamp] at build time; a different stamp now means the library changed. */
    val activitiesStamp: String? = null,
    val entries: List<SimklCalendarCachedEntry> = emptyList(),
) {
    companion object {
        const val VERSION = 1
        const val UNKNOWN_PROFILE = -1
    }
}

/** Deliberately not a serialised [TraktCalendarEntry]: that class is shared UI state, not a file format. */
@Serializable
internal data class SimklCalendarCachedEntry(
    val dateKey: String,
    val type: String,
    val contentId: String,
    val title: String,
    val posterUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
)

internal fun TraktCalendarEntry.toCached(): SimklCalendarCachedEntry = SimklCalendarCachedEntry(
    dateKey = dateKey,
    type = type,
    contentId = contentId,
    title = title,
    posterUrl = posterUrl,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeTitle = episodeTitle,
)

internal fun SimklCalendarCachedEntry.toEntry(): TraktCalendarEntry = TraktCalendarEntry(
    dateKey = dateKey,
    type = type,
    contentId = contentId,
    title = title,
    posterUrl = posterUrl,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeTitle = episodeTitle,
)

/**
 * The activities stamp that decides whether the calendar needs refetching.
 *
 * The old check compared `tv_shows.all` alone, which is blind to anime (SIMKL reports it as its own
 * category, the same trap [simklWatchingSeedActivitiesStamp] documents) and to the movie watchlist
 * that supplies the release entries. That was tolerable while a restart discarded the cache; with
 * the cache on disk it would be permanent.
 */
internal fun simklCalendarActivitiesStamp(activities: SimklActivities?): String? {
    if (activities == null) return null
    val parts = listOf(
        "shows" to activities.tvShows?.all,
        "anime" to activities.anime?.all,
        "movies" to activities.movies?.all,
    )
    if (parts.all { (_, value) -> value == null }) return null
    return parts.joinToString("|") { (name, value) -> "$name=${value.orEmpty()}" }
}

/** Persists the fetched SIMKL calendar across launches. */
internal expect object SimklCalendarCacheStorage {
    fun load(): String?
    fun save(json: String?)
}
