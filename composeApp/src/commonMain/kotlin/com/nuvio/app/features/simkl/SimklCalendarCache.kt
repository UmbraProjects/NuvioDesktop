package com.nuvio.app.features.simkl

import com.nuvio.app.features.trakt.TraktCalendarEntry
import kotlinx.serialization.Serializable

/**
 * On-disk form of the SIMKL calendar.
 *
 * The calendar is one catalog request per followed show plus one per watchlisted movie — a couple
 * of hundred for a full library. v1 cached the finished calendar as one blob, so ANY change to the
 * user's library (a single scrobble bumps `tv_shows.all`) threw all of it away and refetched every
 * show. v2 caches per item: the user's library decides which items are on the calendar, and an
 * item's episode list is refetched only when it is new, or when SIMKL's `/changes` feed (checked at
 * most once a day, as SIMKL's docs ask) says its catalog record moved.
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
    /** [simklCalendarActivitiesStamp] when the library was last read; a different stamp now means the library changed. */
    val activitiesStamp: String? = null,
    /** When every cached item was last known current — a full fetch or a `/changes` check. 0 = never. */
    val changesCheckedAtEpochMs: Long = 0L,
    val items: List<SimklCalendarCachedItem> = emptyList(),
) {
    companion object {
        const val VERSION = 2
        const val UNKNOWN_PROFILE = -1
    }
}

/** One followed show, anime or watchlisted movie, with the calendar entries its catalog record produced. */
@Serializable
internal data class SimklCalendarCachedItem(
    val info: SimklCalendarItemInfo = SimklCalendarItemInfo(),
    /** 0 = the last fetch failed; such an item is always refetched on the next load. */
    val fetchedAtEpochMs: Long = 0L,
    val entries: List<SimklCalendarCachedEntry> = emptyList(),
)

/** What the user's library says about an item; a change in any field means its entries are rebuilt. */
@Serializable
internal data class SimklCalendarItemInfo(
    val kind: String = KIND_TV,
    val simklId: Int = 0,
    val contentId: String = "",
    val title: String = "",
    val posterUrl: String? = null,
    /** Kept so episode coordinates can be projected into [contentId]'s space. */
    val ids: SimklMediaIds = SimklMediaIds(),
) {
    val key: String get() = "$kind:$simklId"

    /** The bucket `/changes` reports this item under. */
    val changesBucket: String
        get() = when (kind) {
            KIND_ANIME -> "anime"
            KIND_MOVIE -> "movies"
            else -> "shows"
        }

    companion object {
        const val KIND_TV = "tv"
        const val KIND_ANIME = "anime"
        const val KIND_MOVIE = "movie"
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
    val airAt: String? = null,
)

/** `/changes` response: Simkl ids per bucket; empty buckets are omitted, and nothing changed is `{}`. */
@Serializable
internal data class SimklChangesResponse(
    val movies: List<Int> = emptyList(),
    val shows: List<Int> = emptyList(),
    val anime: List<Int> = emptyList(),
) {
    fun contains(info: SimklCalendarItemInfo): Boolean = when (info.changesBucket) {
        "anime" -> info.simklId in anime
        "movies" -> info.simklId in movies
        else -> info.simklId in shows
    }
}

internal fun TraktCalendarEntry.toCached(): SimklCalendarCachedEntry = SimklCalendarCachedEntry(
    dateKey = dateKey,
    type = type,
    contentId = contentId,
    title = title,
    posterUrl = posterUrl,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeTitle = episodeTitle,
    airAt = airAt,
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
    airAt = airAt,
)

/**
 * The items whose catalog record has to be fetched, given the current [library] and what is cached.
 *
 * - An item that is not cached, whose last fetch failed, or whose library info changed (title,
 *   poster, ids — anything that shapes its entries) is always fetched.
 * - [refreshAll] fetches everything: there is no cache yet, or the last `/changes` check is older
 *   than SIMKL's 14-day window, so changes could have been missed.
 * - Otherwise [changes] (null when it was not consulted) names the cached items SIMKL changed.
 */
internal fun simklCalendarItemsToFetch(
    library: List<SimklCalendarItemInfo>,
    cached: Map<String, SimklCalendarCachedItem>,
    changes: SimklChangesResponse?,
    refreshAll: Boolean,
): List<SimklCalendarItemInfo> = library.filter { info ->
    val hit = cached[info.key]
    refreshAll ||
        hit == null ||
        hit.fetchedAtEpochMs == 0L ||
        hit.info != info ||
        changes?.contains(info) == true
}

/**
 * The activities stamp that decides whether the user's library has to be re-read.
 *
 * The old check compared `tv_shows.all` alone, which is blind to anime (SIMKL reports it as its own
 * category, the same trap [simklWatchingSeedActivitiesStamp] documents) and to the movie watchlist
 * that supplies the release entries.
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
