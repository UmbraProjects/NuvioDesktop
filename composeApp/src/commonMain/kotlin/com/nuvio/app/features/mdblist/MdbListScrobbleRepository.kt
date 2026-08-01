package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.metadata.ResolvedMediaIds
import com.nuvio.app.features.tracking.TrackingCoordinateFamily
import com.nuvio.app.features.tracking.hasFranchiseScrobbleId
import com.nuvio.app.features.tracking.projectScrobbleCoordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.round

internal const val MDBLIST_BASE_URL = "https://api.mdblist.com"

internal sealed interface MdbListScrobbleItem {
    val itemKey: String

    data class Movie(val ids: MdbListScrobbleRepository.MdbListIds) : MdbListScrobbleItem {
        override val itemKey: String = "movie:${ids.stableKey}"
    }

    data class Episode(
        val ids: MdbListScrobbleRepository.MdbListIds,
        val season: Int,
        val episode: Int,
    ) : MdbListScrobbleItem {
        override val itemKey: String = "episode:${ids.stableKey}:$season:$episode"
    }
}

/**
 * MDBList's scrobble API — a close relative of Trakt's, and a franchise-family provider.
 *
 * Accepts `imdb`, `tmdb`, `trakt`, `tvdb` and its own `mdblist` id; notably **not** mal/anilist/
 * kitsu/anidb, so anime is addressed by TVDB/TMDB season and episode exactly as Trakt addresses it.
 */
internal object MdbListScrobbleRepository {
    private data class ScrobbleStamp(
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long,
    )

    private val log = Logger.withTag("MdbListScrobble")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private var lastStamp: ScrobbleStamp? = null
    private const val MIN_SEND_INTERVAL_MS = 8_000L
    private const val PROGRESS_WINDOW = 1.5f
    private const val MAX_STOP_RETRIES = 2
    private const val RETRY_DELAY_MS = 1_500L

    suspend fun scrobbleStart(item: MdbListScrobbleItem, progressPercent: Float): Boolean =
        send("start", item, progressPercent)

    suspend fun scrobblePause(item: MdbListScrobbleItem, progressPercent: Float): Boolean =
        send("pause", item, progressPercent)

    suspend fun scrobbleStop(item: MdbListScrobbleItem, progressPercent: Float): Boolean =
        send("stop", item, progressPercent)

    /**
     * Drops a paused session. MDBList has no `DELETE /sync/playback/{id}`, so removal is posting
     * the same item to `/scrobble/clear`. Sent at 0% because the position is being discarded.
     */
    suspend fun scrobbleClear(item: MdbListScrobbleItem): Boolean =
        send("clear", item, progressPercent = 0f)

    suspend fun buildItem(
        contentType: String,
        parentMetaId: String,
        videoId: String?,
        title: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        isAnime: Boolean,
    ): MdbListScrobbleItem? {
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

        // Anime that resolved to native ids only cannot be addressed here — MDBList accepts none of
        // them. Skipping is the only safe answer: a guessed season/episode is accepted with a 200
        // and marks the wrong episode watched.
        if (!resolvedIds.hasFranchiseScrobbleId) return null

        val coordinates = resolvedIds.projectScrobbleCoordinates(
            family = TrackingCoordinateFamily.FRANCHISE,
            sourceSeason = seasonNumber,
            sourceEpisode = episodeNumber,
            isAnime = isAnime || resolvedIds.isAnime,
        )
        val ids = resolvedIds.toMdbListIds()

        return if (isEpisodeType && coordinates.season != null && coordinates.episode != null) {
            MdbListScrobbleItem.Episode(
                ids = ids,
                season = coordinates.season,
                episode = coordinates.episode,
            )
        } else {
            MdbListScrobbleItem.Movie(ids = ids)
        }
    }

    private suspend fun send(
        action: String,
        item: MdbListScrobbleItem,
        progressPercent: Float,
    ): Boolean {
        val apiKey = MdbListSettingsRepository.trackingApiKey() ?: return false
        val progress = progressPercent.coerceIn(0f, 100f)
        val itemKey = item.itemKey
        if (shouldSkip(action, itemKey, progress)) return false

        val url = "$MDBLIST_BASE_URL/scrobble/$action?apikey=$apiKey"
        val body = buildBodyJson(item, progress)
        val headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )

        log.d { "MDBList scrobble $action: $itemKey @ ${"%.1f".format(progress)}%" }

        val attempts = if (action == "stop") MAX_STOP_RETRIES + 1 else 1
        for (attempt in 1..attempts) {
            val response = runCatching {
                httpRequestRaw(method = "POST", url = url, headers = headers, body = body)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "MDBList scrobble $action transport failure (attempt $attempt/$attempts)" }
            }.getOrNull()

            if (response == null) {
                if (attempt < attempts) {
                    delay(RETRY_DELAY_MS * attempt)
                    continue
                }
                return false
            }

            log.d { "MDBList scrobble $action response: ${response.status}" }

            when (response.status) {
                in 200..299 -> {
                    lastStamp = ScrobbleStamp(action, itemKey, progress, System.currentTimeMillis())
                    return true
                }
                // The daily request budget is shared with ratings lookups, so back off rather than
                // burning the remainder on retries.
                429 -> {
                    log.w { "MDBList scrobble $action: rate limited, giving up" }
                    return true
                }
                in 500..599 -> {
                    if (attempt < attempts) {
                        delay(RETRY_DELAY_MS * 3 * attempt)
                        continue
                    }
                    log.w { "MDBList scrobble $action: server error ${response.status}" }
                    return true
                }
                else -> {
                    log.w { "MDBList scrobble $action: unexpected ${response.status} ${response.body.take(200)}" }
                    return true
                }
            }
        }
        return false
    }

    /** Suppresses a repeat of the same action for the same item at effectively the same position. */
    private fun shouldSkip(action: String, itemKey: String, progress: Float): Boolean {
        val stamp = lastStamp ?: return false
        if (System.currentTimeMillis() - stamp.timestampMs > MIN_SEND_INTERVAL_MS) return false
        if (stamp.itemKey != itemKey || stamp.action != action) return false
        return abs(stamp.progress - progress) <= PROGRESS_WINDOW
    }

    // ── Request body ───────────────────────────────────────────────────────────

    @Serializable
    data class MdbListIds(
        val imdb: String? = null,
        val tmdb: Int? = null,
        val tvdb: Int? = null,
        val trakt: Int? = null,
        val mdblist: String? = null,
    ) {
        internal val stableKey: String
            get() = imdb ?: tmdb?.let { "tmdb:$it" } ?: tvdb?.let { "tvdb:$it" }
                ?: trakt?.let { "trakt:$it" } ?: mdblist?.let { "mdblist:$it" } ?: "unknown"
    }

    @Serializable
    private data class MdbListMovieBody(val ids: MdbListIds)

    @Serializable
    private data class MdbListShowBody(val ids: MdbListIds, val season: Int, val episode: Int)

    @Serializable
    private data class MdbListMovieRequest(val movie: MdbListMovieBody, val progress: Double)

    @Serializable
    private data class MdbListShowRequest(val show: MdbListShowBody, val progress: Double)

    /**
     * Progress rounded to two decimal places.
     *
     * MDBList stores progress in a decimal field capped at five digits in total, and rejects the
     * request outright otherwise:
     * `{"error":{"progress":["Ensure that there are no more than 5 digits in total."]}}`.
     * A raw `Float` serialises with full precision (`10.443218`), so it always failed. Two decimals
     * is the most that fits alongside a three-digit `100`, and is far finer than the resolution
     * anything downstream uses.
     */
    internal fun wireProgress(progressPercent: Float): Double =
        round(progressPercent.coerceIn(0f, 100f).toDouble() * 100.0) / 100.0

    private fun buildBodyJson(item: MdbListScrobbleItem, progress: Float): String = when (item) {
        is MdbListScrobbleItem.Movie -> json.encodeToString(
            MdbListMovieRequest(
                movie = MdbListMovieBody(ids = item.ids),
                progress = wireProgress(progress),
            ),
        )
        // The flat season/episode form, which the API documents alongside the nested one.
        is MdbListScrobbleItem.Episode -> json.encodeToString(
            MdbListShowRequest(
                show = MdbListShowBody(ids = item.ids, season = item.season, episode = item.episode),
                progress = wireProgress(progress),
            ),
        )
    }

    /** Exposes the encoder so the wire shape can be asserted without a live account. */
    internal fun encodeBodyForTest(item: MdbListScrobbleItem, progress: Float): String =
        buildBodyJson(item, progress)

    private fun ResolvedMediaIds.toMdbListIds(): MdbListIds = MdbListIds(
        imdb = imdb?.takeIf { it.isNotBlank() },
        tmdb = tmdb,
        tvdb = tvdb,
        trakt = trakt,
    )
}
