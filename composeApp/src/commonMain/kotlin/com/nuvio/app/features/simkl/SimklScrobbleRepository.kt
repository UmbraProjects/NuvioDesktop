package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.trakt.TraktExternalIds
import com.nuvio.app.features.trakt.TraktScrobbleItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private const val BASE_URL = "https://api.simkl.com"

internal object SimklScrobbleRepository {
    private data class ScrobbleStamp(
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long,
    )

    private val log = Logger.withTag("SimklScrobble")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private var lastStamp: ScrobbleStamp? = null
    private val minIntervalMs = 8_000L
    private val progressWindow = 1.5f
    private val maxStopRetries = 2
    private val retryDelayMs = 1_500L
    // SIMKL enforces a 20-second per-user lock on scrobble endpoints (429 = overlapping call).
    private val overlapRetryDelayMs = 21_000L

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) =
        send("start", item, progressPercent)

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) =
        send("stop", item, progressPercent)

    private suspend fun send(action: String, item: TraktScrobbleItem, progressPercent: Float) {
        if (!SimklAuthRepository.isAuthenticated.value) return
        val headers = SimklAuthRepository.authorizedHeaders() ?: return
        val progress = progressPercent.coerceIn(0f, 100f)
        val itemKey = item.itemKey
        if (shouldSkip(action, itemKey, progress)) return

        val body = buildBodyJson(item, progress)
        val url = SimklAuthRepository.appendParams("$BASE_URL/scrobble/$action")

        log.d { "SIMKL scrobble $action: $itemKey @ ${"%.1f".format(progress)}%" }

        val attempts = if (action == "stop") maxStopRetries + 1 else 1
        for (attempt in 1..attempts) {
            val response = runCatching {
                httpRequestRaw(method = "POST", url = url, headers = headers, body = body)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) { "SIMKL scrobble $action transport failure (attempt $attempt/$attempts)" }
            }.getOrNull()

            if (response == null) {
                if (attempt < attempts) { delay(retryDelayMs * attempt); continue }
                return
            }

            log.d { "SIMKL scrobble $action response: ${response.status}" }

            when (response.status) {
                in 200..299 -> {
                    lastStamp = ScrobbleStamp(action, itemKey, progress, System.currentTimeMillis())
                    return
                }
                // 429 = 20-second per-user lock; back off and retry once.
                429 -> {
                    if (attempt < attempts) { delay(overlapRetryDelayMs); continue }
                    log.w { "SIMKL scrobble $action: 429 overlap lock, giving up" }
                    return
                }
                // 409 = already scrobbled (duplicate) — not an error.
                409 -> {
                    log.d { "SIMKL scrobble $action: 409 duplicate, ignoring" }
                    return
                }
                in 500..504 -> {
                    if (attempt < attempts) { delay(retryDelayMs * 3 * attempt); continue }
                    log.w { "SIMKL scrobble $action: server error ${response.status}" }
                    return
                }
                else -> {
                    log.w { "SIMKL scrobble $action: unexpected ${response.status} ${response.body.take(200)}" }
                    return
                }
            }
        }
    }

    private fun shouldSkip(action: String, itemKey: String, progress: Float): Boolean {
        val stamp = lastStamp ?: return false
        val age = System.currentTimeMillis() - stamp.timestampMs
        if (age > minIntervalMs) return false
        if (stamp.itemKey != itemKey) return false
        if (stamp.action != action) return false
        return abs(stamp.progress - progress) <= progressWindow
    }

    // ── Request body DTOs ──────────────────────────────────────────────────────

    @Serializable
    private data class SimklIds(
        val imdb: String? = null,
        val tmdb: Int? = null,
    )

    @Serializable
    private data class SimklMovieBody(val title: String?, val ids: SimklIds)

    @Serializable
    private data class SimklShowBody(val title: String?, val ids: SimklIds)

    @Serializable
    private data class SimklEpisodeBody(val season: Int, val number: Int)

    @Serializable
    private data class SimklMovieRequest(val progress: Float, val movie: SimklMovieBody)

    @Serializable
    private data class SimklEpisodeRequest(
        val progress: Float,
        val show: SimklShowBody,
        val episode: SimklEpisodeBody,
    )

    private fun buildBodyJson(item: TraktScrobbleItem, progress: Float): String = when (item) {
        is TraktScrobbleItem.Movie -> json.encodeToString(
            SimklMovieRequest(
                progress = progress,
                movie = SimklMovieBody(title = item.title, ids = item.ids.toSimklIds()),
            )
        )
        is TraktScrobbleItem.Episode -> json.encodeToString(
            SimklEpisodeRequest(
                progress = progress,
                show = SimklShowBody(title = item.showTitle, ids = item.showIds.toSimklIds()),
                episode = SimklEpisodeBody(season = item.season, number = item.number),
            )
        )
    }

    private fun TraktExternalIds.toSimklIds() = SimklIds(imdb = imdb, tmdb = tmdb)
}
