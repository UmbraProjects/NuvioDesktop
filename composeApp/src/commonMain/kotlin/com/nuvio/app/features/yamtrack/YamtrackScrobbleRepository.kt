package com.nuvio.app.features.yamtrack

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.metadata.ResolvedMediaIds
import com.nuvio.app.features.tracking.TrackingCoordinateFamily
import com.nuvio.app.features.tracking.TrackingScrobbleResult
import com.nuvio.app.features.tracking.hasFranchiseScrobbleId
import com.nuvio.app.features.tracking.projectScrobbleCoordinates
import com.nuvio.app.features.tracking.trackingScrobbleResponseConfirmsWatched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** Scrobble progress at or above this counts as watched, matching the player's own threshold. */
internal const val YAMTRACK_COMPLETION_PERCENT = 80.0

internal sealed interface YamtrackScrobbleItem {
    val itemKey: String
    val ids: YamtrackScrobbleRepository.YamtrackIds
    val title: String?

    data class Movie(
        override val ids: YamtrackScrobbleRepository.YamtrackIds,
        override val title: String?,
    ) : YamtrackScrobbleItem {
        override val itemKey: String = "movie:${ids.stableKey}"
    }

    data class Episode(
        override val ids: YamtrackScrobbleRepository.YamtrackIds,
        val seriesTitle: String?,
        override val title: String?,
        val season: Int,
        val episode: Int,
    ) : YamtrackScrobbleItem {
        override val itemKey: String = "episode:${ids.stableKey}:$season:$episode"
    }
}

/**
 * The Yamtrack fork's generic scrobble endpoint (`POST {base}/api/v1/scrobble/`).
 *
 * A franchise-family provider: it accepts only tmdb/imdb/tvdb ids and resolves everything through
 * TMDB, so anime is addressed by TVDB/TMDB season and episode exactly as Trakt and MDBList address
 * it. See the anime note on [toYamtrackIds] for the `anidb` field.
 *
 * Only `stop` writes durable history — `start` and `pause` update the instance's live "Now Playing"
 * card and nothing else.
 */
internal object YamtrackScrobbleRepository {
    private data class ScrobbleStamp(
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long,
    )

    private val log = Logger.withTag("YamtrackScrobble")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private var lastStamp: ScrobbleStamp? = null
    private const val MIN_SEND_INTERVAL_MS = 8_000L
    private const val PROGRESS_WINDOW = 1.5f
    private const val MAX_STOP_RETRIES = 2
    private const val RETRY_DELAY_MS = 1_500L

    suspend fun scrobble(
        action: String,
        item: YamtrackScrobbleItem,
        progressPercent: Float,
        positionSeconds: Long?,
        durationSeconds: Long?,
    ): TrackingScrobbleResult = send(action, item, progressPercent, positionSeconds, durationSeconds)

    suspend fun buildItem(
        contentType: String,
        parentMetaId: String,
        videoId: String?,
        title: String?,
        episodeTitle: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        isAnime: Boolean,
    ): YamtrackScrobbleItem? {
        val normalizedType = contentType.trim().lowercase()
        val isEpisodeType = normalizedType in listOf("series", "tv", "show", "tvshow", "anime")
        val resolvedIds = MediaIdResolver.resolve(
            contentType = contentType,
            parentMetaId = parentMetaId,
            videoId = videoId,
            title = title,
            sourceSeasonNumber = seasonNumber,
            sourceEpisodeNumber = episodeNumber,
            isAnimeHint = isAnime || normalizedType == "anime",
        )

        // Yamtrack requires at least one of tmdb/imdb/tvdb; anime with only native ids is skipped
        // rather than guessed.
        if (!resolvedIds.hasFranchiseScrobbleId) return null

        val coordinates = resolvedIds.projectScrobbleCoordinates(
            family = TrackingCoordinateFamily.FRANCHISE,
            sourceSeason = seasonNumber,
            sourceEpisode = episodeNumber,
            isAnime = isAnime || resolvedIds.isAnime,
        )
        val ids = resolvedIds.toYamtrackIds()

        return if (isEpisodeType && coordinates.season != null && coordinates.episode != null) {
            YamtrackScrobbleItem.Episode(
                ids = ids,
                seriesTitle = title,
                title = episodeTitle ?: title,
                season = coordinates.season,
                episode = coordinates.episode,
            )
        } else {
            YamtrackScrobbleItem.Movie(ids = ids, title = title)
        }
    }

    private suspend fun send(
        action: String,
        item: YamtrackScrobbleItem,
        progressPercent: Float,
        positionSeconds: Long?,
        durationSeconds: Long?,
    ): TrackingScrobbleResult {
        val (baseUrl, token) = YamtrackSettingsRepository.activeCredentials() ?: return TrackingScrobbleResult.Declined
        val progress = progressPercent.coerceIn(0f, 100f)
        val itemKey = item.itemKey
        if (shouldSkip(action, itemKey, progress)) return TrackingScrobbleResult.Declined

        val url = "$baseUrl/api/v1/scrobble/"
        val body = buildBodyJson(action, item, progress, positionSeconds, durationSeconds)
        val headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Authorization" to "Bearer $token",
        )

        log.d { "Yamtrack scrobble $action: $itemKey @ ${"%.1f".format(progress)}%" }

        // Only "stop" is durable, so it is the only action worth retrying.
        val attempts = if (action == "stop") MAX_STOP_RETRIES + 1 else 1
        for (attempt in 1..attempts) {
            val response = runCatching {
                httpRequestRaw(method = "POST", url = url, headers = headers, body = body)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "Yamtrack scrobble $action transport failure (attempt $attempt/$attempts)" }
            }.getOrNull()

            if (response == null) {
                if (attempt < attempts) {
                    delay(RETRY_DELAY_MS * attempt)
                    continue
                }
                return TrackingScrobbleResult.Declined
            }

            log.d { "Yamtrack scrobble $action response: ${response.status}" }

            when (response.status) {
                in 200..299 -> {
                    lastStamp = ScrobbleStamp(action, itemKey, progress, System.currentTimeMillis())
                    return TrackingScrobbleResult(
                        handled = true,
                        confirmsWatched = trackingScrobbleResponseConfirmsWatched(response.body),
                    )
                }
                401, 403 -> {
                    log.w { "Yamtrack scrobble $action: token rejected (${response.status})" }
                    YamtrackSettingsRepository.setConnectionState(YamtrackConnectionState.Unauthorized)
                    return TrackingScrobbleResult.Handled
                }
                // 404 means the instance could not resolve the media through TMDB — a real outcome
                // for obscure titles, not a transport problem, so there is nothing to retry.
                404 -> {
                    log.w { "Yamtrack scrobble $action: media not resolved ${response.body.take(200)}" }
                    return TrackingScrobbleResult.Handled
                }
                in 500..599 -> {
                    if (attempt < attempts) {
                        delay(RETRY_DELAY_MS * 3 * attempt)
                        continue
                    }
                    log.w { "Yamtrack scrobble $action: server error ${response.status}" }
                    return TrackingScrobbleResult.Handled
                }
                else -> {
                    log.w { "Yamtrack scrobble $action: unexpected ${response.status} ${response.body.take(200)}" }
                    return TrackingScrobbleResult.Handled
                }
            }
        }
        return TrackingScrobbleResult.Declined
    }

    private fun shouldSkip(action: String, itemKey: String, progress: Float): Boolean {
        val stamp = lastStamp ?: return false
        if (System.currentTimeMillis() - stamp.timestampMs > MIN_SEND_INTERVAL_MS) return false
        if (stamp.itemKey != itemKey || stamp.action != action) return false
        return abs(stamp.progress - progress) <= PROGRESS_WINDOW
    }

    // ── Request body ───────────────────────────────────────────────────────────

    /**
     * Ids are strings on the wire — the endpoint's schema types tmdb/imdb/tvdb as nullable strings,
     * not integers.
     */
    @Serializable
    data class YamtrackIds(
        val tmdb: String? = null,
        val imdb: String? = null,
        val tvdb: String? = null,
        /**
         * Not in the endpoint's accepted set, and dropped by the server today.
         *
         * Sent anyway because Yamtrack's shared webhook processor already contains a complete
         * AniDB→MAL path that tracks against its MAL-backed `anime` media type, gated on an
         * `anidb_id` that the generic scrobble processor never populates. If that is ever wired up,
         * anime starts tracking correctly here with no client change.
         */
        val anidb: String? = null,
    ) {
        internal val stableKey: String
            get() = imdb ?: tmdb?.let { "tmdb:$it" } ?: tvdb?.let { "tvdb:$it" } ?: "unknown"

        internal val hasAny: Boolean
            get() = !tmdb.isNullOrBlank() || !imdb.isNullOrBlank() || !tvdb.isNullOrBlank()
    }

    @Serializable
    private data class YamtrackScrobbleRequest(
        val action: String,
        @kotlinx.serialization.SerialName("media_type") val mediaType: String,
        val ids: YamtrackIds,
        val title: String? = null,
        @kotlinx.serialization.SerialName("series_title") val seriesTitle: String? = null,
        @kotlinx.serialization.SerialName("season_number") val seasonNumber: Int? = null,
        @kotlinx.serialization.SerialName("episode_number") val episodeNumber: Int? = null,
        @kotlinx.serialization.SerialName("position_seconds") val positionSeconds: Long? = null,
        @kotlinx.serialization.SerialName("duration_seconds") val durationSeconds: Long? = null,
        val completed: Boolean? = null,
    )

    internal fun buildBodyJson(
        action: String,
        item: YamtrackScrobbleItem,
        progressPercent: Float,
        positionSeconds: Long?,
        durationSeconds: Long?,
    ): String {
        // `completed` is only read on stop, and stating it explicitly keeps the decision on our
        // side rather than deferring to the instance's position/duration buffer heuristic.
        val completed = if (action == "stop") progressPercent >= YAMTRACK_COMPLETION_PERCENT else null
        val request = when (item) {
            is YamtrackScrobbleItem.Movie -> YamtrackScrobbleRequest(
                action = action,
                mediaType = "movie",
                ids = item.ids,
                title = item.title,
                positionSeconds = positionSeconds,
                durationSeconds = durationSeconds,
                completed = completed,
            )
            is YamtrackScrobbleItem.Episode -> YamtrackScrobbleRequest(
                action = action,
                mediaType = "episode",
                ids = item.ids,
                title = item.title,
                seriesTitle = item.seriesTitle,
                seasonNumber = item.season,
                episodeNumber = item.episode,
                positionSeconds = positionSeconds,
                durationSeconds = durationSeconds,
                completed = completed,
            )
        }
        return json.encodeToString(request)
    }

    private fun ResolvedMediaIds.toYamtrackIds(): YamtrackIds = YamtrackIds(
        tmdb = tmdb?.toString(),
        imdb = imdb?.takeIf { it.isNotBlank() },
        tvdb = tvdb?.toString(),
        anidb = anidb?.toString(),
    )
}
