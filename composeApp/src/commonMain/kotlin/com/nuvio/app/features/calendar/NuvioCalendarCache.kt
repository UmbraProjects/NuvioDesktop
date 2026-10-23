package com.nuvio.app.features.calendar

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.trakt.TraktCalendarEntry
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.serialization.Serializable

/** How long a running show's episode list is trusted before the meta addon is asked again. */
internal const val NUVIO_CALENDAR_ACTIVE_TTL_MS = 12 * 60 * 60 * 1000L

/** A show that has finished, or a movie released long ago, is re-read this rarely. */
internal const val NUVIO_CALENDAR_FINISHED_TTL_MS = 30 * 24 * 60 * 60 * 1000L

/** A title whose lookup failed is retried after this long, not on every load. */
internal const val NUVIO_CALENDAR_FAILED_RETRY_MS = 6 * 60 * 60 * 1000L

/** Anything dated inside this many days of today, or later, keeps a title on the short TTL. */
private const val RECENT_DAYS = 60L

/** A show with no ended status still counts as finished once its last episode is this old. */
private const val DORMANT_DAYS = 365L

/**
 * On-disk form of the local (Nuvio) calendar.
 *
 * The calendar is one meta-addon read per followed show and one TMDB read per library movie, a few
 * hundred for a long watch history, so it is cached per title like the SIMKL calendar and each
 * title is re-read on its own schedule ([nuvioCalendarRefreshDue]).
 *
 * Every field needs a default so an older file fails the [VERSION] check rather than
 * deserialisation (see `DiscoverRowCacheEntry`).
 */
@Serializable
internal data class NuvioCalendarCacheEntry(
    val version: Int = VERSION,
    val builtAtEpochMs: Long = 0L,
    /** The calendar is built from the profile's own library and history; another profile's file is somebody else's. */
    val profileId: Int = UNKNOWN_PROFILE,
    val items: List<NuvioCalendarCachedItem> = emptyList(),
) {
    companion object {
        const val VERSION = 1
        const val UNKNOWN_PROFILE = -1
    }
}

/** A title on the calendar: a show from the library or watch history, or a movie in the library. */
@Serializable
internal data class NuvioCalendarFollow(
    val kind: String = KIND_SHOW,
    /** The content type the user's own data uses for it, so detail routes and watched keys match. */
    val type: String = "series",
    val contentId: String = "",
    val title: String = "",
    val posterUrl: String? = null,
    val tmdbId: Int? = null,
) {
    val key: String get() = "$kind:$contentId"

    companion object {
        const val KIND_SHOW = "show"
        const val KIND_MOVIE = "movie"
    }
}

@Serializable
internal data class NuvioCalendarCachedItem(
    val follow: NuvioCalendarFollow = NuvioCalendarFollow(),
    val fetchedAtEpochMs: Long = 0L,
    /** The last lookup failed; previous [entries] are kept and the title is retried sooner. */
    val failed: Boolean = false,
    /** Nothing new is expected: an ended show, or a movie released long ago. */
    val finished: Boolean = false,
    /**
     * The title as the meta addon identifies it, so the same show followed under two ids (a `tt`
     * library row and a `tmdb:` history row) is listed once.
     */
    val scheduleKey: String? = null,
    val entries: List<NuvioCalendarCachedEntry> = emptyList(),
)

/** Deliberately not a serialised [TraktCalendarEntry]: that class is shared UI state, not a file format. */
@Serializable
internal data class NuvioCalendarCachedEntry(
    val dateKey: String = "",
    val type: String = "series",
    val contentId: String = "",
    val title: String = "",
    val posterUrl: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val airAt: String? = null,
)

internal fun NuvioCalendarCachedEntry.toEntry(): TraktCalendarEntry = TraktCalendarEntry(
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

private val SERIES_TYPES = setOf("series", "tv", "show", "tvshow", "anime")

/**
 * What the local calendar follows: every show and movie in the Nuvio library, plus every show with
 * an episode in the watch history — the same "my shows" Trakt's calendar lists. Library rows come
 * first (they carry a poster and TMDB id), then history by most recent watch, so the first titles
 * fetched are the ones most likely to be on screen.
 *
 * Rows addressing a file rather than a title (cloud library), and types that are neither a movie
 * nor a series (live channels, sport), are skipped.
 */
internal fun nuvioCalendarFollows(
    library: List<LibraryItem>,
    watched: List<WatchedItem>,
): List<NuvioCalendarFollow> {
    val out = LinkedHashMap<String, NuvioCalendarFollow>()
    fun add(follow: NuvioCalendarFollow) {
        if (follow.contentId.isBlank() || follow.contentId.startsWith("upnext_")) return
        out.putIfAbsent(follow.key, follow)
    }
    library.sortedByDescending { it.savedAtEpochMs }.forEach { item ->
        if (item.metaLookupId != null) return@forEach
        val type = item.type.trim().lowercase()
        val kind = when {
            type == "movie" -> NuvioCalendarFollow.KIND_MOVIE
            type in SERIES_TYPES -> NuvioCalendarFollow.KIND_SHOW
            else -> return@forEach
        }
        add(
            NuvioCalendarFollow(
                kind = kind,
                type = item.type,
                contentId = item.id.trim(),
                title = item.name,
                posterUrl = item.poster?.takeIf { it.isNotBlank() },
                tmdbId = item.tmdbId,
            ),
        )
    }
    watched.sortedByDescending { it.markedAtEpochMs }.forEach { item ->
        if (item.type.trim().lowercase() !in SERIES_TYPES || item.season == null) return@forEach
        add(
            NuvioCalendarFollow(
                kind = NuvioCalendarFollow.KIND_SHOW,
                type = item.type,
                contentId = item.id.trim(),
                title = item.name,
                posterUrl = item.poster?.takeIf { it.isNotBlank() },
            ),
        )
    }
    return out.values.toList()
}

/** Whether a cached title has to be looked up again; null (never fetched) always does. */
internal fun nuvioCalendarRefreshDue(item: NuvioCalendarCachedItem?, nowEpochMs: Long): Boolean {
    if (item == null || item.fetchedAtEpochMs <= 0L) return true
    val age = nowEpochMs - item.fetchedAtEpochMs
    if (age < 0L) return true
    val ttl = when {
        item.failed -> NUVIO_CALENDAR_FAILED_RETRY_MS
        item.finished -> NUVIO_CALENDAR_FINISHED_TTL_MS
        else -> NUVIO_CALENDAR_ACTIVE_TTL_MS
    }
    return age >= ttl
}

/**
 * Whether a title can go on the long TTL: nothing dated within [RECENT_DAYS] of [todayIso] or
 * later, and either the addon says the show ended / was cancelled, or its last date is over a year
 * old. A movie with no date at all stays on the short TTL, since its date may yet be announced.
 */
internal fun nuvioCalendarIsFinished(
    dateKeys: List<String>,
    status: String?,
    todayIso: String,
): Boolean {
    val latest = dateKeys.maxOrNull() ?: return false
    val today = runCatching { java.time.LocalDate.parse(todayIso) }.getOrNull() ?: return false
    val last = runCatching { java.time.LocalDate.parse(latest) }.getOrNull() ?: return false
    if (!last.isBefore(today.minusDays(RECENT_DAYS))) return false
    val normalized = status?.trim()?.lowercase().orEmpty()
    val ended = "ended" in normalized || "cancel" in normalized || normalized == "released"
    return ended || last.isBefore(today.minusDays(DORMANT_DAYS))
}

/** One row of TMDB's `release_dates` (SIMKL relays the same codes): country, type, `yyyy-MM-dd`. */
internal data class MovieReleaseDate(val country: String, val type: Int, val date: String)

// TMDB release types: 1 premiere, 2 limited theatrical, 3 theatrical, 4 digital, 5 physical, 6 TV.
internal const val MOVIE_RELEASE_TYPE_LIMITED = 2
internal const val MOVIE_RELEASE_TYPE_THEATRICAL = 3
internal const val MOVIE_RELEASE_TYPE_DIGITAL = 4

/**
 * The one date a movie goes on the calendar (Nuvio and SIMKL alike): digital in the US, then GB,
 * then the earliest anywhere; failing that, theatrical in the US, then the earliest anywhere.
 *
 * Limited and wide theatrical count as one release, earliest first. A film that opens limited and
 * goes wide a week later (You Can See Everything: US limited 16 Oct, wide 23 Oct) came out on the
 * first date — the one TMDB itself gives as its release date — not on the day it expanded.
 */
internal fun pickMovieCalendarDate(releases: List<MovieReleaseDate>): String? {
    fun earliest(types: Set<Int>, country: String? = null) = releases
        .filter { it.type in types && (country == null || it.country.equals(country, ignoreCase = true)) }
        .minOfOrNull { it.date }
    val digital = setOf(MOVIE_RELEASE_TYPE_DIGITAL)
    val theatrical = setOf(MOVIE_RELEASE_TYPE_LIMITED, MOVIE_RELEASE_TYPE_THEATRICAL)
    return earliest(digital, "US")
        ?: earliest(digital, "GB")
        ?: earliest(digital)
        ?: earliest(theatrical, "US")
        ?: earliest(theatrical)
}

/** Persists the local calendar across launches. */
internal expect object NuvioCalendarCacheStorage {
    fun load(): String?
    fun save(json: String?)
}
