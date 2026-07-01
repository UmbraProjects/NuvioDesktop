package com.nuvio.app.features.metadata

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklMediaIds
import com.nuvio.app.features.simkl.SimklScrobbleRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.trakt.TraktExternalIds
import com.nuvio.app.features.trakt.parseTraktContentIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val SIMKL_BASE_URL = "https://api.simkl.com"

internal data class ResolvedMediaIds(
    val sourceId: String,
    val contentType: String,
    val sourceTitle: String? = null,
    val sourceSeasonNumber: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null,
    val trakt: Int? = null,
    val simkl: Int? = null,
    val mal: Int? = null,
    val kitsu: Int? = null,
    val anilist: Int? = null,
    val anidb: Int? = null,
    val isAnime: Boolean = false,
    val tmdbSeason: Int? = null,
    val tvdbSeason: Int? = null,
    val tmdbEpisodeOffset: Int? = null,
    val tvdbEpisodeOffset: Int? = null,
    val preferredTmdbMediaType: String? = null,
) {
    fun merge(other: ResolvedMediaIds?): ResolvedMediaIds {
        if (other == null) return this
        return copy(
            sourceTitle = sourceTitle ?: other.sourceTitle,
            sourceSeasonNumber = sourceSeasonNumber ?: other.sourceSeasonNumber,
            imdb = imdb ?: other.imdb,
            tmdb = tmdb ?: other.tmdb,
            tvdb = tvdb ?: other.tvdb,
            trakt = trakt ?: other.trakt,
            simkl = simkl ?: other.simkl,
            mal = mal ?: other.mal,
            kitsu = kitsu ?: other.kitsu,
            anilist = anilist ?: other.anilist,
            anidb = anidb ?: other.anidb,
            isAnime = isAnime || other.isAnime,
            tmdbSeason = tmdbSeason ?: other.tmdbSeason,
            tvdbSeason = tvdbSeason ?: other.tvdbSeason,
            tmdbEpisodeOffset = tmdbEpisodeOffset ?: other.tmdbEpisodeOffset,
            tvdbEpisodeOffset = tvdbEpisodeOffset ?: other.tvdbEpisodeOffset,
            preferredTmdbMediaType = preferredTmdbMediaType ?: other.preferredTmdbMediaType,
        )
    }
}

internal object MediaIdResolver {
    private val log = Logger.withTag("MediaIdResolver")
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = linkedMapOf<String, ResolvedMediaIds>()
    private val cacheMutex = Mutex()
    private val inFlightRequests = mutableMapOf<String, CompletableDeferred<ResolvedMediaIds>>()

    suspend fun resolve(
        contentType: String,
        parentMetaId: String,
        videoId: String?,
        title: String? = null,
        sourceSeasonNumber: Int? = null,
        isAnimeHint: Boolean = false,
    ): ResolvedMediaIds {
        val key = listOf(contentType, parentMetaId, videoId.orEmpty(), title.orEmpty(), sourceSeasonNumber, isAnimeHint).joinToString("|")

        // Trakt and Simkl scrobble builds now both resolve the same content concurrently on
        // every dual-scrobble tick — dedupe concurrent callers onto one in-flight resolution
        // instead of each independently repeating the anime-mapping/Simkl/TMDB lookup chain.
        var ownsRequest = false
        val pending = cacheMutex.withLock {
            cache[key]?.let { return it }
            inFlightRequests[key] ?: CompletableDeferred<ResolvedMediaIds>().also {
                inFlightRequests[key] = it
                ownsRequest = true
            }
        }
        if (!ownsRequest) return pending.await()

        try {
            val normalizedType = contentType.trim().lowercase().ifBlank { "movie" }
            val initial = parseIds(parentMetaId, normalizedType)
                .merge(parseIds(videoId, normalizedType))
                .copy(
                    sourceTitle = title?.takeIf { it.isNotBlank() },
                    sourceSeasonNumber = sourceSeasonNumber,
                    isAnime = isAnimeHint || normalizedType.equals("anime", ignoreCase = true),
                )

            var resolved = initial
            AnimeIdMappingRepository.lookup(resolved)?.let { mapping ->
                resolved = resolved.merge(mapping.toResolvedIds(normalizedType, parentMetaId))
            }

            if (resolved.simkl != null && shouldFetchSimklDetails(resolved)) {
                resolved = resolved.merge(fetchSimklDetailsIds(resolved.simkl, normalizedType, resolved.isAnime))
            }

            AnimeIdMappingRepository.lookup(resolved)?.let { mapping ->
                resolved = resolved.merge(mapping.toResolvedIds(normalizedType, parentMetaId))
            }

            if (resolved.tmdb != null && resolved.imdb == null) {
                val imdb = runCatching {
                    TmdbService.tmdbToImdb(
                        tmdbId = resolved.tmdb,
                        mediaType = resolved.tmdbLookupType(normalizedType),
                    )
                }.getOrNull()
                if (!imdb.isNullOrBlank()) resolved = resolved.copy(imdb = imdb)
            }

            if (resolved.tmdb == null) {
                val tmdbCandidate = listOfNotNull(resolved.imdb, resolved.tvdb?.let { "tvdb:$it" })
                    .firstOrNull()
                if (tmdbCandidate != null) {
                    val tmdb = runCatching {
                        TmdbService.ensureTmdbId(
                            videoId = tmdbCandidate,
                            mediaType = resolved.tmdbLookupType(normalizedType),
                        )?.toIntOrNull()
                    }.getOrNull()
                    if (tmdb != null) resolved = resolved.copy(tmdb = tmdb)
                }
            }

            cacheMutex.withLock {
                if (cache.size > 400) cache.remove(cache.keys.first())
                cache[key] = resolved
                inFlightRequests.remove(key)?.complete(resolved)
            }
            return resolved
        } catch (error: Throwable) {
            cacheMutex.withLock { inFlightRequests.remove(key)?.completeExceptionally(error) }
            throw error
        }
    }

    fun resolveLocalEpisodeIdentity(
        contentType: String,
        parentMetaId: String,
        videoId: String,
        title: String?,
        season: Int?,
        episode: Int?,
        isAnimeHint: Boolean = false,
    ): ResolvedEpisodeIdentity {
        val normalizedType = contentType.trim().lowercase().ifBlank { "movie" }
        var ids = parseIds(parentMetaId, normalizedType)
            .merge(parseIds(videoId, normalizedType))
            .copy(
                sourceTitle = title?.takeIf { it.isNotBlank() },
                sourceSeasonNumber = season,
                isAnime = isAnimeHint || normalizedType.equals("anime", ignoreCase = true),
            )
        AnimeIdMappingRepository.lookup(ids)?.let { mapping ->
            ids = ids.merge(mapping.toResolvedIds(normalizedType, parentMetaId))
        }
        val mappedSeason = ids.canonicalSeasonNumber(season)
        val mappedEpisode = ids.canonicalEpisodeNumber(episode)
        val streamEpisodeParts = ids.streamEpisodeParts(videoId, season, episode, mappedSeason, mappedEpisode)
        val streamVideoId = ids.streamLookupVideoId(videoId, streamEpisodeParts)
        return ResolvedEpisodeIdentity(
            ids = ids,
            season = mappedSeason,
            episode = mappedEpisode,
            streamSeason = streamEpisodeParts.first,
            streamEpisode = streamEpisodeParts.second,
            videoId = streamVideoId,
            canonicalVideoId = ids.rewriteEpisodeVideoId(videoId, season, episode, mappedSeason, mappedEpisode),
        )
    }

    private fun shouldFetchSimklDetails(ids: ResolvedMediaIds): Boolean =
        ids.imdb == null || ids.tmdb == null || ids.tvdb == null ||
            (ids.isAnime && (ids.mal == null || ids.kitsu == null || ids.anilist == null || ids.anidb == null))

    private suspend fun fetchSimklDetailsIds(
        simklId: Int,
        contentType: String,
        isAnime: Boolean,
    ): ResolvedMediaIds? {
        val endpoints = simklDetailEndpoints(simklId, contentType, isAnime)
        for (endpoint in endpoints) {
            val url = SimklAuthRepository.appendParams("$SIMKL_BASE_URL/$endpoint")
            val response = runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = emptyMap(),
                    body = "",
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.d { "SIMKL ID lookup failed for $endpoint: ${error.message}" }
            }.getOrNull() ?: continue

            if (response.status !in 200..299) continue

            val details = runCatching {
                json.decodeFromString<SimklDetailsResponse>(response.body)
            }.getOrNull() ?: continue
            return details.ids.toResolvedIds(contentType, "simkl:$simklId", isAnime || endpoint.startsWith("anime/"))
                .copy(simkl = simklId)
        }
        return null
    }

    private fun simklDetailEndpoints(simklId: Int, contentType: String, isAnime: Boolean): List<String> {
        val normalized = contentType.trim().lowercase()
        val preferred = when {
            isAnime || normalized == "anime" -> "anime/$simklId"
            normalized in setOf("movie", "movies") -> "movies/$simklId"
            else -> "tv/$simklId"
        }
        return listOf(preferred, "anime/$simklId", "tv/$simklId", "movies/$simklId").distinct()
    }

    private fun parseIds(value: String?, contentType: String): ResolvedMediaIds {
        val raw = value?.trim().orEmpty()
        val traktIds = parseTraktContentIds(raw)
        return ResolvedMediaIds(
            sourceId = raw,
            contentType = contentType,
            imdb = traktIds.imdb,
            tmdb = traktIds.tmdb,
            tvdb = traktIds.tvdb,
            trakt = traktIds.trakt,
            simkl = extractPrefixedInt(raw, "simkl"),
            mal = traktIds.mal ?: extractPrefixedInt(raw, "myanimelist"),
            kitsu = traktIds.kitsu,
            anilist = traktIds.anilist,
            anidb = extractPrefixedInt(raw, "anidb"),
            isAnime = hasAnimeNativePrefix(raw),
        )
    }

    private fun hasAnimeNativePrefix(value: String): Boolean =
        value.startsWith("kitsu:", ignoreCase = true) ||
            value.startsWith("mal:", ignoreCase = true) ||
            value.startsWith("myanimelist:", ignoreCase = true) ||
            value.startsWith("al:", ignoreCase = true) ||
            value.startsWith("anilist:", ignoreCase = true) ||
            value.startsWith("anidb:", ignoreCase = true) ||
            value.startsWith("simkl:", ignoreCase = true)

    private fun extractPrefixedInt(value: String, prefix: String): Int? {
        val marker = "$prefix:"
        if (!value.startsWith(marker, ignoreCase = true)) return null
        return value.substringAfter(':').substringBefore(':').substringBefore('/').toIntOrNull()
    }

    private fun ResolvedMediaIds.tmdbLookupType(fallbackContentType: String): String =
        preferredTmdbMediaType ?: when (fallbackContentType.trim().lowercase()) {
            "anime", "series", "tv", "show", "tvshow" -> "series"
            else -> fallbackContentType
        }
}

internal fun ResolvedMediaIds.toTraktExternalIds(): TraktExternalIds =
    TraktExternalIds(
        trakt = trakt,
        imdb = imdb,
        tmdb = tmdb,
        tvdb = tvdb,
        mal = mal,
        kitsu = kitsu,
        anilist = anilist,
    )

internal fun ResolvedMediaIds.toSimklIds(): SimklScrobbleRepository.SimklIds =
    SimklScrobbleRepository.SimklIds(
        simkl = simkl,
        imdb = imdb,
        tmdb = tmdb,
        tvdb = tvdb,
        mal = mal,
        kitsu = kitsu,
        anilist = anilist,
        anidb = anidb,
    )

internal data class ResolvedEpisodeIdentity(
    val ids: ResolvedMediaIds,
    val season: Int?,
    val episode: Int?,
    val streamSeason: Int?,
    val streamEpisode: Int?,
    val videoId: String,
    val canonicalVideoId: String,
)

internal suspend fun MediaIdResolver.resolveEpisodeIdentity(
    contentType: String,
    parentMetaId: String,
    videoId: String,
    title: String?,
    season: Int?,
    episode: Int?,
    isAnimeHint: Boolean = false,
): ResolvedEpisodeIdentity {
    val ids = resolve(
        contentType = contentType,
        parentMetaId = parentMetaId,
        videoId = videoId,
        title = title,
        sourceSeasonNumber = season,
        isAnimeHint = isAnimeHint,
    )
    val mappedSeason = ids.canonicalSeasonNumber(season)
    val mappedEpisode = ids.canonicalEpisodeNumber(episode)
    val streamEpisodeParts = ids.streamEpisodeParts(videoId, season, episode, mappedSeason, mappedEpisode)
    val streamVideoId = ids.streamLookupVideoId(videoId, streamEpisodeParts)
    return ResolvedEpisodeIdentity(
        ids = ids,
        season = mappedSeason,
        episode = mappedEpisode,
        streamSeason = streamEpisodeParts.first,
        streamEpisode = streamEpisodeParts.second,
        videoId = streamVideoId,
        canonicalVideoId = ids.rewriteEpisodeVideoId(videoId, season, episode, mappedSeason, mappedEpisode),
    )
}

private fun ResolvedMediaIds.streamEpisodeParts(
    videoId: String,
    sourceSeason: Int?,
    sourceEpisode: Int?,
    mappedSeason: Int?,
    mappedEpisode: Int?,
): Pair<Int?, Int?> {
    // Native anime ids (kitsu/mal/anilist/anidb) address episodes by a single absolute
    // episode number scoped to that entry — there is no season segment. SIMKL reports
    // these as season 1 and AIOMetadata's video list numbers them per-franchise-season,
    // but the stream lookup must drop the season entirely. Returning a null season keeps
    // the rest of the pipeline from re-appending one.
    if (isAnime && videoId.hasNativeAnimePrefix()) {
        return null to (videoId.nativeAnimeEpisode() ?: sourceEpisode)
    }
    val suffix = videoId.episodeSuffix()
    return suffix ?: (sourceSeason to sourceEpisode)
}

private fun ResolvedMediaIds.streamLookupVideoId(videoId: String, streamEpisodeParts: Pair<Int?, Int?>): String {
    if (!isAnime || !videoId.hasNativeAnimePrefix()) return videoId
    val preferredBase = when {
        kitsu != null -> "kitsu:$kitsu"
        mal != null -> "mal:$mal"
        anilist != null -> "anilist:$anilist"
        anidb != null -> "anidb:$anidb"
        simkl != null -> "simkl:$simkl"
        else -> videoId.nativeAnimeBase()
    }
    // Anime stream providers (Torrentio, AIOStreams, …) expect `prefix:id:absoluteEpisode`
    // (e.g. `kitsu:8174:1`). The 3-part `prefix:id:season:episode` form makes their
    // scrapers return "500 - Internal Server Error", so we never emit a season here.
    val episode = streamEpisodeParts.second
    return if (episode != null) "$preferredBase:$episode" else preferredBase
}

/**
 * Absolute episode number for a native anime id of the form `prefix:id` or
 * `prefix:id:episode` (and tolerating a stray `prefix:id:season:episode`). The episode is
 * always the trailing numeric segment; `prefix:id` (no episode) yields null.
 */
internal fun String.nativeAnimeEpisode(): Int? {
    val parts = split(':')
    if (parts.size < 3) return null
    return parts.last().toIntOrNull()
}

/** The `prefix:id` base of a native anime id, dropping any episode/season segments. */
internal fun String.nativeAnimeBase(): String {
    val parts = split(':')
    return if (parts.size >= 2) "${parts[0]}:${parts[1]}" else this
}

private fun String.episodeSuffix(): Pair<Int?, Int?>? {
    val parts = split(':')
    if (parts.size < 3) return null
    val suffixSeason = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
    val suffixEpisode = parts.last().toIntOrNull() ?: return null
    return suffixSeason to suffixEpisode
}

private fun String.hasNativeAnimePrefix(): Boolean =
    startsWith("kitsu:", ignoreCase = true) ||
        startsWith("mal:", ignoreCase = true) ||
        startsWith("myanimelist:", ignoreCase = true) ||
        startsWith("al:", ignoreCase = true) ||
        startsWith("anilist:", ignoreCase = true) ||
        startsWith("anidb:", ignoreCase = true) ||
        startsWith("simkl:", ignoreCase = true)

internal fun ResolvedMediaIds.canonicalSeasonNumber(sourceSeasonNumber: Int?): Int? =
    when {
        sourceSeasonNumber == null -> null
        tmdbSeason != null && sourceSeasonNumber == 1 -> tmdbSeason
        tvdbSeason != null && sourceSeasonNumber == 1 -> tvdbSeason
        else -> sourceSeasonNumber
    }

internal fun ResolvedMediaIds.canonicalEpisodeNumber(sourceEpisodeNumber: Int?): Int? {
    if (sourceEpisodeNumber == null) return null
    val offset = tmdbEpisodeOffset ?: tvdbEpisodeOffset ?: 0
    return sourceEpisodeNumber + offset
}

private fun ResolvedMediaIds.rewriteEpisodeVideoId(
    videoId: String,
    sourceSeason: Int?,
    sourceEpisode: Int?,
    mappedSeason: Int?,
    mappedEpisode: Int?,
): String {
    if (sourceSeason == null || sourceEpisode == null || mappedSeason == null || mappedEpisode == null) return videoId
    if (sourceSeason == mappedSeason && sourceEpisode == mappedEpisode) return videoId
    val parts = videoId.split(':')
    if (parts.size >= 3 && parts[parts.lastIndex - 1].toIntOrNull() != null && parts.last().toIntOrNull() != null) {
        return (parts.dropLast(2) + listOf(mappedSeason.toString(), mappedEpisode.toString())).joinToString(":")
    }
    return "$videoId:$mappedSeason:$mappedEpisode"
}

private fun AnimeIdMapping.toResolvedIds(contentType: String, sourceId: String): ResolvedMediaIds =
    ResolvedMediaIds(
        sourceId = sourceId,
        contentType = contentType,
        sourceTitle = null,
        sourceSeasonNumber = null,
        imdb = imdbIds.firstOrNull(),
        tmdb = tmdbMovieIds.firstOrNull() ?: tmdbTvId,
        tvdb = tvdbId,
        simkl = simklId,
        mal = malId,
        kitsu = kitsuId,
        anilist = anilistId,
        anidb = anidbId,
        isAnime = true,
        tmdbSeason = tmdbSeason,
        tvdbSeason = tvdbSeason,
        tmdbEpisodeOffset = tmdbEpisodeOffset,
        tvdbEpisodeOffset = tvdbEpisodeOffset,
        preferredTmdbMediaType = tmdbMediaTypeHint(),
    )

private fun AnimeIdMapping.tmdbMediaTypeHint(): String? =
    when {
        tmdbMovieIds.isNotEmpty() -> "movie"
        tmdbTvId != null -> "series"
        else -> when (type?.trim()?.lowercase()) {
            "movie", "film", "special" -> "movie"
            "tv", "series", "show", "tvshow", "ova", "ona" -> "series"
            else -> null
        }
    }

private fun SimklMediaIds.toResolvedIds(contentType: String, sourceId: String, isAnime: Boolean): ResolvedMediaIds =
    ResolvedMediaIds(
        sourceId = sourceId,
        contentType = contentType,
        imdb = imdb?.takeIf { it.isNotBlank() },
        tmdb = tmdb?.toIntOrNull(),
        tvdb = tvdb,
        simkl = simkl,
        mal = mal?.toIntOrNull(),
        kitsu = kitsu?.toIntOrNull(),
        anilist = anilist?.toIntOrNull(),
        anidb = anidb?.toIntOrNull(),
        isAnime = isAnime || mal != null || kitsu != null || anilist != null || anidb != null,
    )

@Serializable
private data class SimklDetailsResponse(
    val ids: SimklMediaIds = SimklMediaIds(),
)
