package com.nuvio.app.features.yamtrack

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.addons.isTruncated
import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Watched history from `GET {base}/api/v1/history/`.
 *
 * The response is grouped by day rather than being a flat list — `results` are dates, each holding
 * the `entries` played that day — so it is flattened here. Identity comes from `item.source` plus
 * `item.media_id`, which for the scrobbles this app sends is always TMDB.
 *
 * A failed fetch throws rather than degrading to the last good snapshot: the consumer merges this
 * additively into the local watched store, so a stale snapshot re-adds ticks the user has removed.
 */
internal object YamtrackHistoryRepository {
    private val log = Logger.withTag("YamtrackHistory")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()

    // Days per page, not entries — `results` are dates, each holding everything played that day.
    // Each day carries full item metadata (synopsis, genres, artwork), so 500 of them ran past the
    // 1 MB response cap in `httpRequestRaw` and came back as truncated, unparseable JSON: Floppy
    // history never imported at all. 50 keeps a page comfortably inside the cap.
    private const val PAGE_LIMIT = 50
    private const val MAX_PAGES = 60

    suspend fun watchedItems(): List<WatchedItem> = mutex.withLock {
        val (baseUrl, token) = YamtrackSettingsRepository.activeCredentials() ?: return emptyList()
        val collected = mutableListOf<WatchedItem>()
        var offset = 0
        var page = 0

        while (page < MAX_PAGES) {
            page += 1
            val url = "$baseUrl/api/v1/history/?limit=$PAGE_LIMIT&offset=$offset"
            val response = runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Authorization" to "Bearer $token",
                    ),
                    body = "",
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
            }.getOrThrow()

            if (response.status !in 200..299) {
                error("Yamtrack history fetch failed: HTTP ${response.status}")
            }
            // Named explicitly, because the alternative is a JSON parse error halfway through a
            // page that reads like a schema mismatch rather than a size limit.
            if (response.isTruncated) {
                error(
                    "Yamtrack history page exceeded the response size cap at limit=$PAGE_LIMIT; " +
                        "lower PAGE_LIMIT",
                )
            }

            val payload = runCatching {
                json.decodeFromString(YamtrackHistoryResponse.serializer(), response.body)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "Yamtrack history payload could not be parsed" }
            }.getOrThrow()

            collected += payload.toWatchedItems()
            if (payload.pagination?.next.isNullOrBlank()) break
            offset += PAGE_LIMIT
        }

        log.d { "Yamtrack history: ${collected.size} watched entries" }
        return collected
    }
}

// ── /api/v1/history/ DTOs ──────────────────────────────────────────────────────

@Serializable
private data class YamtrackHistoryPagination(
    val total: Int? = null,
    val next: String? = null,
)

@Serializable
private data class YamtrackHistoryItem(
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("media_id") val mediaId: String? = null,
    val source: String? = null,
    val title: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
)

@Serializable
private data class YamtrackHistoryEntry(
    @SerialName("media_type") val mediaType: String? = null,
    val item: YamtrackHistoryItem? = null,
    val title: String? = null,
    @SerialName("played_at_local") val playedAtLocal: String? = null,
)

@Serializable
private data class YamtrackHistoryDay(
    val date: String? = null,
    val entries: List<YamtrackHistoryEntry> = emptyList(),
)

@Serializable
private data class YamtrackHistoryResponse(
    val pagination: YamtrackHistoryPagination? = null,
    val results: List<YamtrackHistoryDay> = emptyList(),
) {
    fun toWatchedItems(): List<WatchedItem> = results
        .asSequence()
        .flatMap { day -> day.entries.asSequence() }
        .mapNotNull { entry -> entry.toWatchedItem() }
        .toList()

    private fun YamtrackHistoryEntry.toWatchedItem(): WatchedItem? {
        val item = item ?: return null
        val contentId = item.contentId() ?: return null
        val watchedAtMs = playedAtLocal?.let(::parseTraktIsoDateTimeToEpochMs) ?: 0L

        return when (item.mediaType ?: mediaType) {
            "episode" -> {
                val season = item.seasonNumber ?: return null
                val episode = item.episodeNumber ?: return null
                WatchedItem(
                    id = contentId,
                    type = "series",
                    // Entries carry the *episode* title, not the show's; the local watched model
                    // wants a series name and has no other source for it here.
                    name = item.title ?: contentId,
                    season = season,
                    episode = episode,
                    markedAtEpochMs = watchedAtMs,
                )
            }
            "movie" -> WatchedItem(
                id = contentId,
                type = "movie",
                name = item.title ?: contentId,
                markedAtEpochMs = watchedAtMs,
            )
            else -> null
        }
    }

    /**
     * Yamtrack identifies media as `source` + `media_id`. Everything this app scrobbles resolves
     * through TMDB, so anything else is a manual entry the local model cannot address.
     */
    private fun YamtrackHistoryItem.contentId(): String? {
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return null
        return when (source?.lowercase()) {
            "tmdb" -> "tmdb:$id"
            "imdb" -> id
            "tvdb" -> "tvdb:$id"
            else -> null
        }
    }
}
