package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.metadata.toSimklIds
import com.nuvio.app.features.tracking.TrackingCoordinateFamily
import com.nuvio.app.features.tracking.projectScrobbleCoordinates
import com.nuvio.app.features.trakt.TraktExternalIds
import com.nuvio.app.features.trakt.parseTraktContentIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private const val BASE_URL = "https://api.simkl.com"

internal sealed interface SimklScrobbleItem {
    val itemKey: String

    data class Movie(
        val title: String?,
        val ids: SimklScrobbleRepository.SimklIds,
    ) : SimklScrobbleItem {
        override val itemKey: String =
            "movie:${ids.simkl ?: ids.imdb ?: ids.tmdb ?: ids.tvdb ?: title.orEmpty()}"
    }

    data class Episode(
        val showTitle: String?,
        val ids: SimklScrobbleRepository.SimklIds,
        val season: Int,
        val number: Int,
        val isAnime: Boolean,
    ) : SimklScrobbleItem {
        override val itemKey: String =
            "episode:${ids.simkl ?: ids.imdb ?: ids.tmdb ?: ids.tvdb ?: showTitle.orEmpty()}:$season:$number"
    }
}

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

    suspend fun scrobbleStart(item: SimklScrobbleItem, progressPercent: Float) =
        send("start", item, progressPercent)

    suspend fun scrobbleStop(item: SimklScrobbleItem, progressPercent: Float) =
        send("stop", item, progressPercent)

    suspend fun buildItem(
        contentType: String,
        parentMetaId: String,
        videoId: String?,
        title: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        isAnime: Boolean,
    ): SimklScrobbleItem? {
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
        val resolvedIsAnime = isAnime || resolvedIds.isAnime
        val coordinates = resolvedIds.projectScrobbleCoordinates(
            family = TrackingCoordinateFamily.ENTRY_LOCAL,
            sourceSeason = seasonNumber,
            sourceEpisode = episodeNumber,
            isAnime = resolvedIsAnime,
        )
        val enrichedIds = resolvedIds.toSimklIds()
            .let { if (resolvedIsAnime) enrichAnimeIdsForSimkl(it) else it }
        val ids = enrichedIds.retainingNativeAnimeIds(coordinates.retainsNativeAnimeIds)

        // Addressability is decided here rather than in the projection: the usable id set is only
        // known after the anime enrichment round-trip above.
        if (!ids.hasAny()) return null

        return if (
            isEpisodeType &&
            coordinates.season != null &&
            coordinates.episode != null
        ) {
            SimklScrobbleItem.Episode(
                showTitle = title,
                ids = ids,
                season = coordinates.season,
                number = coordinates.episode,
                isAnime = resolvedIsAnime,
            )
        } else {
            SimklScrobbleItem.Movie(title = title, ids = ids)
        }
    }

    private suspend fun send(action: String, item: SimklScrobbleItem, progressPercent: Float) {
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
                429 -> {
                    if (attempt < attempts) { delay(overlapRetryDelayMs); continue }
                    log.w { "SIMKL scrobble $action: 429 overlap lock, giving up" }
                    return
                }
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
    data class SimklIds(
        val simkl: Int? = null,
        val imdb: String? = null,
        val tmdb: Int? = null,
        val tvdb: Int? = null,
        val mal: Int? = null,
        val kitsu: Int? = null,
        @SerialName("al") val anilist: Int? = null,
        val anidb: Int? = null,
    )

    /**
     * Applies the projection's per-entry id decision to a SIMKL id set.
     *
     * When the coordinates could not be pinned to one unambiguous anime-list entry, the per-entry
     * ids must go: pairing them with a franchise season produces an episode that cannot exist.
     * What survives is the franchise ids, which SIMKL's own seasonal-anime mapping can translate.
     * The decision itself lives in `TrackingIdProjection`; this only carries it out.
     */
    internal fun SimklIds.retainingNativeAnimeIds(retains: Boolean): SimklIds = if (retains) {
        this
    } else {
        copy(
            simkl = null,
            mal = null,
            kitsu = null,
            anilist = null,
            anidb = null,
        )
    }

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

    @Serializable
    private data class SimklAnimeEpisodeRequest(
        val progress: Float,
        val anime: SimklShowBody,
        val episode: SimklEpisodeBody,
    )

    private fun buildBodyJson(item: SimklScrobbleItem, progress: Float): String = when (item) {
        is SimklScrobbleItem.Movie -> json.encodeToString(
            SimklMovieRequest(
                progress = progress,
                movie = SimklMovieBody(title = item.title, ids = item.ids),
            )
        )
        is SimklScrobbleItem.Episode -> {
            val media = SimklShowBody(title = item.showTitle, ids = item.ids)
            val episode = SimklEpisodeBody(season = item.season, number = item.number)
            if (item.isAnime) json.encodeToString(
                SimklAnimeEpisodeRequest(
                    progress = progress,
                    anime = media,
                    episode = episode,
                )
            ) else json.encodeToString(
                SimklEpisodeRequest(
                    progress = progress,
                    show = media,
                    episode = episode,
                )
            )
        }
    }

    private fun TraktExternalIds.toSimklIds() = SimklIds(
        imdb = imdb,
        tmdb = tmdb,
        tvdb = tvdb,
        mal = mal,
        kitsu = kitsu,
        anilist = anilist,
    )

    private fun SimklMediaIds.toSimklIds() = SimklIds(
        simkl = simkl,
        imdb = imdb,
        tmdb = tmdb?.toIntOrNull(),
        tvdb = tvdb,
        mal = mal?.toIntOrNull(),
        kitsu = kitsu?.toIntOrNull(),
        anilist = anilist?.toIntOrNull(),
        anidb = anidb?.toIntOrNull(),
    )

    private fun buildSimklIds(parentMetaId: String, videoId: String?): SimklIds {
        val parentIds = parseTraktContentIds(parentMetaId).toSimklIds()
            .copy(simkl = extractSimklId(parentMetaId))
        val videoIds = parseTraktContentIds(videoId).toSimklIds()
            .copy(simkl = extractSimklId(videoId))
        return parentIds.withFallbacks(videoIds)
    }

    // Memoized per input ids: scrobble start and stop both rebuild the item, and without
    // this each rebuild repeats the SIMKL redirect + details round-trips.
    private val animeIdEnrichmentMutex = Mutex()
    private val animeIdEnrichmentCache = mutableMapOf<SimklIds, SimklIds>()

    private suspend fun enrichAnimeIdsForSimkl(ids: SimklIds): SimklIds {
        if (ids.simkl != null && (ids.tvdb != null || ids.kitsu != null || ids.mal != null)) return ids
        animeIdEnrichmentMutex.withLock { animeIdEnrichmentCache[ids] }?.let { return it }
        val resolvedSimklId = ids.simkl ?: resolveAnimeSimklId(ids) ?: return ids
        val detailsIds = fetchAnimeIds(resolvedSimklId)
        val enriched = ids.copy(simkl = resolvedSimklId).withFallbacks(detailsIds)
        animeIdEnrichmentMutex.withLock { animeIdEnrichmentCache[ids] = enriched }
        return enriched
    }

    private suspend fun resolveAnimeSimklId(ids: SimklIds): Int? {
        val query = when {
            ids.tvdb != null -> "tvdb=${ids.tvdb}"
            ids.kitsu != null -> "kitsu=${ids.kitsu}"
            ids.mal != null -> "mal=${ids.mal}"
            ids.anilist != null -> "anilist=${ids.anilist}"
            ids.anidb != null -> "anidb=${ids.anidb}"
            !ids.imdb.isNullOrBlank() -> "imdb=${ids.imdb}"
            ids.tmdb != null -> "tmdb=${ids.tmdb}"
            else -> return null
        }
        val url = SimklAuthRepository.appendParams("$BASE_URL/redirect?$query")
        return runCatching {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = emptyMap(),
                body = "",
                followRedirects = false,
            )
        }.getOrNull()
            ?.headers
            ?.entries
            ?.firstOrNull { (key, _) -> key.equals("location", ignoreCase = true) }
            ?.value
            ?.substringAfter("/anime/", missingDelimiterValue = "")
            ?.substringBefore('/')
            ?.toIntOrNull()
    }

    private suspend fun fetchAnimeIds(simklId: Int): SimklIds? {
        val url = SimklAuthRepository.appendParams("$BASE_URL/anime/$simklId")
        return runCatching {
            val response = httpRequestRaw(
                method = "GET",
                url = url,
                headers = emptyMap(),
                body = "",
            )
            if (response.status !in 200..299) return@runCatching null
            json.decodeFromString(SimklAnimeDetails.serializer(), response.body).ids.toSimklIds()
        }.getOrNull()
    }

    private fun extractSimklId(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (!raw.startsWith("simkl:", ignoreCase = true)) return null
        return raw.substringAfter(':').substringBefore(':').toIntOrNull()
    }

    private fun SimklIds?.hasAny(): Boolean = this != null && (
        simkl != null ||
            !imdb.isNullOrBlank() ||
            tmdb != null ||
            tvdb != null ||
            mal != null ||
            kitsu != null ||
            anilist != null ||
            anidb != null
        )

    private fun SimklIds.withFallbacks(fallback: SimklIds?): SimklIds {
        if (fallback == null) return this
        return copy(
            simkl = simkl ?: fallback.simkl,
            imdb = imdb ?: fallback.imdb,
            tmdb = tmdb ?: fallback.tmdb,
            tvdb = tvdb ?: fallback.tvdb,
            mal = mal ?: fallback.mal,
            kitsu = kitsu ?: fallback.kitsu,
            anilist = anilist ?: fallback.anilist,
            anidb = anidb ?: fallback.anidb,
        )
    }

    @Serializable
    private data class SimklAnimeDetails(
        val ids: SimklMediaIds = SimklMediaIds(),
    )
}
