package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Watched-history reads from MDBList.
 *
 * The daily request budget is shared with the ratings lookups that already use this key, so every
 * read is gated behind `/sync/last_activities`: an unchanged bucket costs one request instead of a
 * full history page. Do not add an unconditional refresh on screen entry.
 */
internal object MdbListSyncRepository {
    private val log = Logger.withTag("MdbListSync")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()

    private var cachedItems: List<WatchedItem> = emptyList()
    private var cachedActivitySignature: String? = null

    private const val PAGE_LIMIT = 1000
    private const val MAX_PAGES = 10

    fun clearLocalState() {
        cachedItems = emptyList()
        cachedActivitySignature = null
    }

    suspend fun watchedItems(forceRefresh: Boolean = false): List<WatchedItem> = mutex.withLock {
        val apiKey = MdbListSettingsRepository.trackingApiKey() ?: return emptyList()
        val signature = fetchActivitySignature(apiKey)

        // A null signature means the activities probe itself failed; serve what we have rather than
        // spending the budget on a full history fetch that may fail the same way.
        if (!forceRefresh && signature != null && signature == cachedActivitySignature) {
            return cachedItems
        }
        if (!forceRefresh && signature == null && cachedActivitySignature != null) {
            return cachedItems
        }

        // A failed fetch throws rather than degrading to the last good snapshot: the consumer
        // merges this additively into the local watched store, so a stale snapshot re-adds ticks
        // the user has removed. Only a complete fetch is cached — stamping a truncated one with
        // the current signature would serve the truncation until the next remote change.
        val items = fetchAllWatched(apiKey) ?: error("MDBList watched-history fetch failed")
        cachedItems = items
        cachedActivitySignature = signature
        return items
    }

    /**
     * The watched-related timestamps from `/sync/last_activities`, joined into one value.
     *
     * Returns null when the probe fails, which callers treat as "unknown" rather than "unchanged".
     */
    private suspend fun fetchActivitySignature(apiKey: String): String? {
        val response = get("$MDBLIST_BASE_URL/sync/last_activities?apikey=$apiKey") ?: return null
        if (response.status !in 200..299) return null
        val activities = runCatching {
            json.decodeFromString(MdbListLastActivities.serializer(), response.body)
        }.getOrNull() ?: return null
        return listOf(
            activities.watchedAt,
            activities.seasonWatchedAt,
            activities.episodeWatchedAt,
            activities.journalAt,
        ).joinToString("|") { it.orEmpty() }
    }

    private suspend fun fetchAllWatched(apiKey: String): List<WatchedItem>? {
        val collected = mutableListOf<WatchedItem>()
        var cursor: String? = null
        var offset = 0
        var page = 0
        while (page < MAX_PAGES) {
            page += 1
            val url = buildString {
                append("$MDBLIST_BASE_URL/sync/watched?apikey=")
                append(apiKey)
                append("&limit=")
                append(PAGE_LIMIT)
                // The cursor is only returned once a cursor request has been made; the first page
                // is necessarily a legacy offset request, so keep an offset in step as a fallback.
                if (cursor != null) {
                    append("&cursor=")
                    append(cursor)
                } else if (offset > 0) {
                    append("&offset=")
                    append(offset)
                }
            }
            val response = get(url) ?: return null
            if (response.status !in 200..299) {
                log.w { "MDBList watched fetch failed: HTTP ${response.status}" }
                return null
            }
            val payload = runCatching {
                json.decodeFromString(MdbListWatchedResponse.serializer(), response.body)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "MDBList watched payload could not be parsed" }
            }.getOrNull() ?: return null

            collected += payload.toWatchedItems()

            // `has_more` is authoritative — `next_cursor` is absent on legacy offset responses, and
            // treating that absence as "done" silently truncated the history at the first page.
            val nextCursor = payload.pagination?.nextCursor?.takeIf { it.isNotBlank() }
                ?: response.nextCursorHeader
            val hasMore = payload.pagination?.hasMore
                ?: response.hasMoreHeader
                ?: (nextCursor != null)
            if (!hasMore) break
            cursor = nextCursor
            offset += PAGE_LIMIT
        }
        return collected
    }

    private suspend fun get(url: String): MdbListHttpResult? = runCatching {
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf("Accept" to "application/json"),
            body = "",
        )
        // The key's daily budget is shared with the ratings lookups, so surface how much is left
        // rather than discovering exhaustion as an opaque 429.
        response.header("x-ratelimit-remaining")?.toIntOrNull()?.let { remaining ->
            if (remaining < 100) {
                log.w { "MDBList rate limit low: $remaining requests remaining today" }
            }
        }
        MdbListHttpResult(
            status = response.status,
            body = response.body,
            nextCursorHeader = response.header("x-next-cursor")?.takeIf { it.isNotBlank() },
            hasMoreHeader = response.header("x-has-more")?.toBooleanStrictOrNull(),
        )
    }.onFailure { error ->
        if (error is CancellationException) throw error
        log.d { "MDBList request failed: ${error.message}" }
    }.getOrNull()
}

private data class MdbListHttpResult(
    val status: Int,
    val body: String,
    val nextCursorHeader: String?,
    val hasMoreHeader: Boolean?,
)

private fun com.nuvio.app.features.addons.RawHttpResponse.header(name: String): String? =
    headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

// ── Response DTOs ──────────────────────────────────────────────────────────────

@Serializable
private data class MdbListLastActivities(
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("season_watched_at") val seasonWatchedAt: String? = null,
    @SerialName("episode_watched_at") val episodeWatchedAt: String? = null,
    @SerialName("journal_at") val journalAt: String? = null,
)

@Serializable
private data class MdbListMediaIds(
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null,
    val trakt: Int? = null,
    val mdblist: String? = null,
)

@Serializable
private data class MdbListMedia(
    val title: String? = null,
    val ids: MdbListMediaIds = MdbListMediaIds(),
)

/**
 * One watched episode row, as `/sync/watched` actually returns it.
 *
 * ```json
 * { "last_watched_at": "…",
 *   "episode": { "season": 1, "number": 3, "name": "…", "ids": {…},
 *                "show": { "title": "FROM", "ids": { "imdb": "tt9813792", … } } } }
 * ```
 *
 * The schema documents `episodes` only as an untyped array, and this differs from an earlier guess
 * based on `/sync/playback` in the two ways that matter: the timestamp is `last_watched_at`, not
 * `watched_at`, and the show is nested *inside* the episode rather than being its sibling. Both
 * would have silently yielded no episodes at all.
 */
@Serializable
private data class MdbListWatchedEpisodeRow(
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val episode: MdbListWatchedEpisode? = null,
)

@Serializable
private data class MdbListWatchedEpisode(
    val season: Int? = null,
    val number: Int? = null,
    val name: String? = null,
    val still: String? = null,
    val show: MdbListMedia? = null,
)

@Serializable
private data class MdbListWatchedMovie(
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val movie: MdbListMedia? = null,
)

@Serializable
private data class MdbListWatchedShow(
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val show: MdbListMedia? = null,
)

@Serializable
private data class MdbListPagination(
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
)

@Serializable
private data class MdbListWatchedResponse(
    val movies: List<MdbListWatchedMovie> = emptyList(),
    val shows: List<MdbListWatchedShow> = emptyList(),
    val episodes: List<MdbListWatchedEpisodeRow> = emptyList(),
    val pagination: MdbListPagination? = null,
) {
    fun toWatchedItems(): List<WatchedItem> {
        val result = mutableListOf<WatchedItem>()

        movies.forEach { row ->
            val movie = row.movie ?: return@forEach
            val id = movie.ids.normalizedContentId() ?: return@forEach
            result += WatchedItem(
                id = id,
                type = "movie",
                name = movie.title ?: id,
                markedAtEpochMs = row.lastWatchedAt.toEpochMs(),
            )
        }

        // `shows` carries a show-level "everything aired is watched" marker with no episode
        // coordinates, which the local watched model has no room for — the per-episode rows below
        // are the authoritative source and cover the same ground.
        episodes.forEach { row ->
            val episode = row.episode ?: return@forEach
            val show = episode.show ?: return@forEach
            val id = show.ids.normalizedContentId() ?: return@forEach
            val season = episode.season ?: return@forEach
            val number = episode.number ?: return@forEach
            result += WatchedItem(
                id = id,
                type = "series",
                name = show.title ?: id,
                season = season,
                episode = number,
                markedAtEpochMs = row.lastWatchedAt.toEpochMs(),
            )
        }

        return result
    }
}

/** Matches the id preference the rest of the app uses for content ids. */
private fun MdbListMediaIds.normalizedContentId(): String? = when {
    !imdb.isNullOrBlank() -> imdb
    tmdb != null -> "tmdb:$tmdb"
    tvdb != null -> "tvdb:$tvdb"
    trakt != null -> "trakt:$trakt"
    else -> null
}

private fun String?.toEpochMs(): Long =
    this?.let(::parseTraktIsoDateTimeToEpochMs) ?: 0L

/** Exposes the projection so it can be asserted against a captured real response. */
internal fun parseMdbListWatchedForTest(payload: String): List<WatchedItem> =
    Json { ignoreUnknownKeys = true; isLenient = true }
        .decodeFromString(MdbListWatchedResponse.serializer(), payload)
        .toWatchedItems()
