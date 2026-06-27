package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private const val BASE_URL = "https://api.trakt.tv"

internal sealed interface TraktScrobbleItem {
    val itemKey: String

    data class Movie(
        val title: String?,
        val year: Int?,
        val ids: TraktExternalIds,
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "movie:${ids.imdb ?: ids.tmdb ?: ids.trakt ?: title.orEmpty()}:${year ?: 0}"
    }

    data class Episode(
        val showTitle: String?,
        val showYear: Int?,
        val showIds: TraktExternalIds,
        val season: Int,
        val number: Int,
        val episodeTitle: String?,
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "episode:${showIds.imdb ?: showIds.tmdb ?: showIds.trakt ?: showTitle.orEmpty()}:$season:$number"
    }
}

internal object TraktScrobbleRepository {
    private data class ScrobbleStamp(
        val profileId: Int,
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long,
    )

    private val log = Logger.withTag("TraktScrobble")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private var lastScrobbleStamp: ScrobbleStamp? = null
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f
    private val maxStopRetries = 2
    private val retryDelayMs = 1_500L
    private val serverOverloadedRetryDelayMs = 5_000L

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "start", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "stop", item = item, progressPercent = progressPercent)
    }

    suspend fun buildItem(
        contentType: String,
        parentMetaId: String,
        videoId: String?,
        title: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        releaseInfo: String? = null,
    ): TraktScrobbleItem? {
        val normalizedType = contentType.trim().lowercase()
        var ids = parseTraktContentIds(parentMetaId)
        val isEpisodeType = normalizedType in listOf("series", "tv", "show", "tvshow", "anime")

        // Fallback: if parentMetaId doesn't resolve to valid Trakt IDs, try videoId.
        // Some addons use non-standard contentId (e.g. "tun_tt7821582") but set a
        // valid IMDB/TMDB videoId (e.g. "tt7821582:3:7").
        if (!ids.hasAnyId() && !videoId.isNullOrBlank() && videoId != parentMetaId) {
            ids = parseTraktContentIds(videoId)
        }

        // Ensure we have a Trakt-supported ID (IMDB, TMDB, Trakt, Slug). If we only have
        // TVDB, Kitsu, MAL, etc., Trakt's scrobble endpoint may reject it. Nuvio's
        // TmdbService can resolve these to a canonical TMDB ID.
        if (
            ids.imdb == null &&
            ids.tmdb == null &&
            ids.trakt == null &&
            ids.tvdb == null &&
            ids.slug == null &&
            !isAnimeExternalOnlyId(parentMetaId)
        ) {
            val fallbackId = parentMetaId.takeIf { it.isNotBlank() } ?: videoId
            if (fallbackId != null) {
                val tmdbId = com.nuvio.app.features.tmdb.TmdbService.ensureTmdbId(
                    videoId = fallbackId,
                    mediaType = if (normalizedType in listOf("series", "tv", "show", "tvshow", "anime")) "series" else "movie"
                )
                if (tmdbId != null) {
                    ids = ids.copy(tmdb = tmdbId.toIntOrNull())
                }
            }
        }

        // Don't send scrobble if we still have no valid Trakt IDs — would cause
        // title-based fuzzy match on Trakt API resulting in wrong show matched.
        if (!ids.hasScrobbleRequestId()) return null

        val parsedYear = extractTraktYear(releaseInfo)

        return if (
            isEpisodeType &&
            seasonNumber != null &&
            episodeNumber != null
        ) {
            TraktScrobbleItem.Episode(
                showTitle = title,
                showYear = parsedYear,
                showIds = ids,
                season = seasonNumber,
                number = episodeNumber,
                episodeTitle = episodeTitle,
            )
        } else {
            TraktScrobbleItem.Movie(
                title = title,
                year = parsedYear,
                ids = ids,
            )
        }
    }

    private suspend fun sendScrobble(
        action: String,
        item: TraktScrobbleItem,
        progressPercent: Float,
    ) {
        val headers = TraktAuthRepository.authorizedHeaders() ?: return
        val activeProfileId = ProfileRepository.activeProfileId
        val clampedProgress = progressPercent.coerceIn(0f, 100f)
        if (action == "stop" && clampedProgress < 1f) {
            log.d { "Skipping Trakt scrobble stop below 1%: ${"%.2f".format(clampedProgress)}%" }
            return
        }
        if (shouldSkip(activeProfileId, action, item.itemKey, clampedProgress)) return

        val url = "$BASE_URL/scrobble/$action"
        val requestBody = json.encodeToString(buildRequestBody(item, clampedProgress))
        val requestHeaders = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ) + headers

        log.d {
            buildString {
                append("Trakt scrobble ")
                append(action)
                append(" request")
                append('\n')
                append("url=")
                append(url)
                append('\n')
                append("headers=")
                append(requestHeaders.redactedForLogs().formatForLog())
                append('\n')
                append("body=")
                append(requestBody.ifBlank { "<empty>" })
            }
        }

        val attempts = if (action == "stop") maxStopRetries + 1 else 1
        var wasSent = false
        for (attempt in 1..attempts) {
            val response = runCatching {
                httpRequestRaw(
                    method = "POST",
                    url = url,
                    body = requestBody,
                    headers = requestHeaders,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w(error) {
                    "Trakt scrobble $action transport failure on attempt $attempt/$attempts"
                }
            }.getOrNull()

            if (response == null) {
                if (attempt < attempts) {
                    delay(retryDelayMs * attempt)
                    continue
                }
                return
            }

            log.d {
                buildString {
                    append("Trakt scrobble ")
                    append(action)
                    append(" response")
                    append('\n')
                    append("status=")
                    append(response.status)
                    append(' ')
                    append(response.statusText.ifBlank { "<no-status-text>" })
                    append('\n')
                    append("url=")
                    append(response.url)
                    append('\n')
                    append("headers=")
                    append(response.headers.formatForLog())
                    append('\n')
                    append("body=")
                    append(response.body.ifBlank { "<empty>" })
                }
            }

            if (response.status in 200..299 || response.status == 409) {
                wasSent = true
                break
            }

            if (response.status in 500..599 && attempt < attempts) {
                val delayMs = if (response.status in 502..504) serverOverloadedRetryDelayMs else retryDelayMs * attempt
                delay(delayMs)
                continue
            }

            log.w {
                "Failed Trakt scrobble $action: HTTP ${response.status} ${response.statusText.ifBlank { "<no-status-text>" }}"
            }
            return
        }

        if (!wasSent) return

        lastScrobbleStamp = ScrobbleStamp(
            profileId = activeProfileId,
            action = action,
            itemKey = item.itemKey,
            progress = clampedProgress,
            timestampMs = TraktPlatformClock.nowEpochMs(),
        )

        if (action == "stop") {
            runCatching { TraktProgressRepository.refreshNow() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w { "Failed to refresh Trakt progress after stop: ${error.message}" }
                }
        }
    }

    private fun buildRequestBody(
        item: TraktScrobbleItem,
        clampedProgress: Float,
    ): TraktScrobbleRequest {
        return when (item) {
            is TraktScrobbleItem.Movie -> TraktScrobbleRequest(
                movie = TraktMovieBody(
                    title = item.title,
                    year = item.year,
                    ids = item.ids.toRequestBodyOrNull(),
                ),
                progress = clampedProgress,
                appVersion = AppVersionPolicy.displayVersionName,
            )

            is TraktScrobbleItem.Episode -> TraktScrobbleRequest(
                show = TraktShowBody(
                    title = item.showTitle,
                    year = item.showYear,
                    ids = item.showIds.toRequestBodyOrNull(),
                ),
                episode = TraktEpisodeBody(
                    title = item.episodeTitle,
                    season = item.season,
                    number = item.number,
                ),
                progress = clampedProgress,
                appVersion = AppVersionPolicy.displayVersionName,
            )
        }
    }

    private fun shouldSkip(profileId: Int, action: String, itemKey: String, progress: Float): Boolean {
        val last = lastScrobbleStamp ?: return false
        val now = TraktPlatformClock.nowEpochMs()
        val isSameWindow = now - last.timestampMs < minSendIntervalMs
        val isSameProfile = last.profileId == profileId
        val isSameAction = last.action == action
        val isSameItem = last.itemKey == itemKey
        val isNearProgress = abs(last.progress - progress) <= progressWindow
        if (action == "stop" && last.action == "start" && isSameItem && isSameProfile) {
            return false
        }
        return isSameWindow && isSameProfile && isSameAction && isSameItem && isNearProgress
    }

    private fun Map<String, String>.redactedForLogs(): Map<String, String> =
        entries.associate { (key, value) ->
            key to when {
                key.equals("authorization", ignoreCase = true) -> redactBearerValue(value)
                key.equals("trakt-api-key", ignoreCase = true) -> "<redacted>"
                else -> value
            }
        }

    private fun Map<String, String>.formatForLog(): String =
        entries
            .sortedBy { it.key.lowercase() }
            .joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" }

    private fun redactBearerValue(value: String): String {
        val tokenPrefix = "Bearer "
        if (!value.startsWith(tokenPrefix, ignoreCase = true)) return "<redacted>"
        val token = value.removePrefix(tokenPrefix).trim()
        if (token.isBlank()) return "Bearer <redacted>"
        val prefix = token.take(6)
        val suffix = token.takeLast(4)
        return "Bearer ${prefix}...${suffix}"
    }

    private fun TraktExternalIds.toRequestBodyOrNull(): TraktIdsBody? {
        if (trakt == null && imdb.isNullOrBlank() && tmdb == null && tvdb == null) return null
        return TraktIdsBody(
            trakt = trakt,
            imdb = imdb,
            tmdb = tmdb,
            tvdb = tvdb,
        )
    }

    private fun TraktExternalIds.hasScrobbleRequestId(): Boolean =
        trakt != null || !imdb.isNullOrBlank() || tmdb != null || tvdb != null

    private fun isAnimeExternalOnlyId(contentId: String): Boolean {
        val raw = contentId.trim()
        return raw.startsWith("kitsu:", ignoreCase = true) ||
            raw.startsWith("mal:", ignoreCase = true) ||
            raw.startsWith("al:", ignoreCase = true) ||
            raw.startsWith("anilist:", ignoreCase = true) ||
            raw.startsWith("simkl:", ignoreCase = true)
    }

    private fun TraktExternalIds.withFallbacks(fallback: TraktExternalIds): TraktExternalIds = copy(
        trakt = trakt ?: fallback.trakt,
        imdb = imdb ?: fallback.imdb,
        tmdb = tmdb ?: fallback.tmdb,
        tvdb = tvdb ?: fallback.tvdb,
        mal = mal ?: fallback.mal,
        kitsu = kitsu ?: fallback.kitsu,
        anilist = anilist ?: fallback.anilist,
        slug = slug ?: fallback.slug,
    )
}

@Serializable
private data class TraktScrobbleRequest(
    @SerialName("movie") val movie: TraktMovieBody? = null,
    @SerialName("show") val show: TraktShowBody? = null,
    @SerialName("episode") val episode: TraktEpisodeBody? = null,
    @SerialName("progress") val progress: Float,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
private data class TraktMovieBody(
    @SerialName("title") val title: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("ids") val ids: TraktIdsBody? = null,
)

@Serializable
private data class TraktShowBody(
    @SerialName("title") val title: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("ids") val ids: TraktIdsBody? = null,
)

@Serializable
private data class TraktEpisodeBody(
    @SerialName("title") val title: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("number") val number: Int? = null,
)

@Serializable
private data class TraktIdsBody(
    @SerialName("trakt") val trakt: Int? = null,
    @SerialName("imdb") val imdb: String? = null,
    @SerialName("tmdb") val tmdb: Int? = null,
    @SerialName("tvdb") val tvdb: Int? = null,
)
