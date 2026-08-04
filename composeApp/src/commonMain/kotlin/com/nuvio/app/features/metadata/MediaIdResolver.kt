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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val sourceEpisodeNumber: Int? = null,
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
    // True when the caller's season/episode numbers are already franchise (TVDB/TMDB)
    // numbering — e.g. a kitsu-catalog entry whose meta shows the whole franchise's seasons.
    // Entry-local sources (Kitsu addon, SIMKL) always report season 1 and leave this false.
    val franchiseNumbering: Boolean = false,
    // For franchise-numbered native anime requests, whether anime-list has an entry whose
    // coordinates explicitly cover this season/episode. False means the native entry spans or
    // ambiguously overlaps Western seasons, so an entry-local episode number would be unsafe.
    val nativeMappingCoversFranchiseEpisode: Boolean? = null,
) {
    fun merge(other: ResolvedMediaIds?): ResolvedMediaIds {
        if (other == null) return this
        return copy(
            sourceTitle = sourceTitle ?: other.sourceTitle,
            sourceSeasonNumber = sourceSeasonNumber ?: other.sourceSeasonNumber,
            sourceEpisodeNumber = sourceEpisodeNumber ?: other.sourceEpisodeNumber,
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
            franchiseNumbering = franchiseNumbering || other.franchiseNumbering,
            nativeMappingCoversFranchiseEpisode =
                nativeMappingCoversFranchiseEpisode ?: other.nativeMappingCoversFranchiseEpisode,
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
        sourceEpisodeNumber: Int? = null,
        isAnimeHint: Boolean = false,
    ): ResolvedMediaIds {
        val key = listOf(contentType, parentMetaId, videoId.orEmpty(), title.orEmpty(), sourceSeasonNumber, sourceEpisodeNumber, isAnimeHint).joinToString("|")

        // Trakt and Simkl scrobble builds now both resolve the same content concurrently on
        // every dual-scrobble tick — dedupe concurrent callers onto one in-flight resolution
        // instead of each independently repeating the anime-mapping/Simkl/TMDB lookup chain.
        while (true) {
            var ownsRequest = false
            val pending = cacheMutex.withLock {
                cache[key]?.let { return it }
                inFlightRequests[key] ?: CompletableDeferred<ResolvedMediaIds>().also {
                    inFlightRequests[key] = it
                    ownsRequest = true
                }
            }
            if (ownsRequest) break
            try {
                return pending.await()
            } catch (error: CancellationException) {
                // The owning caller was cancelled, not us — its CancellationException must not
                // kill an unrelated waiter (e.g. the Trakt build dying because the Simkl build
                // was cancelled). Retry with a fresh resolution unless we are cancelled too.
                currentCoroutineContext().ensureActive()
            }
        }

        try {
            val normalizedType = contentType.trim().lowercase().ifBlank { "movie" }
            val franchiseCoords = franchiseEpisodeCoords(parentMetaId, videoId, sourceSeasonNumber, sourceEpisodeNumber)
            val parsed = parseIds(parentMetaId, normalizedType)
                .merge(parseIds(videoId, normalizedType))
            val initial = parsed.copy(
                sourceTitle = title?.takeIf { it.isNotBlank() },
                sourceSeasonNumber = sourceSeasonNumber,
                sourceEpisodeNumber = sourceEpisodeNumber,
                // Preserve the anime flag derived from native id prefixes (kitsu:/mal:/…) so
                // titles missing from anime-list-mini.json still get anime stream-id handling.
                isAnime = parsed.isAnime || isAnimeHint || normalizedType.equals("anime", ignoreCase = true),
                franchiseNumbering = franchiseCoords != null,
            )

            var resolved = initial
            resolved = applyAnimeMapping(resolved, franchiseCoords, normalizedType, parentMetaId)

            if (resolved.simkl != null && shouldFetchSimklDetails(resolved)) {
                resolved = resolved.merge(fetchSimklDetailsIds(resolved.simkl, normalizedType, resolved.isAnime))
            }

            resolved = applyAnimeMapping(resolved, franchiseCoords, normalizedType, parentMetaId)

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
        val franchiseCoords = franchiseEpisodeCoords(parentMetaId, videoId, season, episode)
        val parsed = parseIds(parentMetaId, normalizedType)
            .merge(parseIds(videoId, normalizedType))
        var ids = parsed.copy(
            sourceTitle = title?.takeIf { it.isNotBlank() },
            sourceSeasonNumber = season,
            sourceEpisodeNumber = episode,
            isAnime = parsed.isAnime || isAnimeHint || normalizedType.equals("anime", ignoreCase = true),
            franchiseNumbering = franchiseCoords != null,
        )
        ids = applyAnimeMapping(ids, franchiseCoords, normalizedType, parentMetaId)
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

    /**
     * Merges the anime-list mapping for [ids], then — when the caller addresses episodes in
     * franchise numbering ([franchiseCoords]) — swaps the per-entry native ids for the sibling
     * entry that actually covers that franchise season/episode. Anime franchises are one
     * TVDB/TMDB show but many kitsu/mal entries (SAO season 3 is its own kitsu id), so a
     * kitsu-catalog result pinned to one season must not answer for the whole franchise.
     */
    private fun applyAnimeMapping(
        ids: ResolvedMediaIds,
        franchiseCoords: Pair<Int, Int>?,
        contentType: String,
        sourceId: String,
    ): ResolvedMediaIds {
        val mapping = AnimeIdMappingRepository.lookup(ids) ?: return ids
        var merged = ids.merge(mapping.toResolvedIds(contentType, sourceId))
        franchiseCoords?.let { (season, episode) ->
            val entry = AnimeIdMappingRepository.franchiseEntryFor(
                base = mapping,
                season = season,
                episode = episode,
                coordinateSystem = animeMappingCoordinateSystemFor(ids.sourceId),
            )
            merged = if (entry != null) {
                merged.withFranchiseEntry(entry).copy(nativeMappingCoversFranchiseEpisode = true)
            } else {
                merged.copy(nativeMappingCoversFranchiseEpisode = false)
            }
        }
        return merged
    }

    /**
     * The franchise-numbered season/episode a caller is addressing, or null when the source
     * uses entry-local numbering.
     *
     * - A 4-part native id (`kitsu:8174:3:5`, AIOMetadata style) is explicit franchise
     *   numbering — the id stays pinned to the entry the user opened while season/episode
     *   walk the whole franchise.
     * - A 2/3-part native id with a season parameter other than 1 can only be franchise
     *   numbering: entry-local sources (Kitsu addon metas, SIMKL) always report season 1.
     * - Season 1 with a 2/3-part id is franchise numbering only when the id addresses a
     *   different entry than the parent in the same namespace (`kitsu:6589:1` under parent
     *   `kitsu:8174`) — that shape is produced exclusively by an earlier franchise remap.
     *   Otherwise it stays entry-local, the long-standing behaviour for those sources.
     * - IMDb/TMDB/TVDB and ordinary Stremio series ids are franchise metadata by definition.
     *   Treating those as entry-local used to add a split-cour offset a second time.
     */
    private fun franchiseEpisodeCoords(
        parentMetaId: String?,
        videoId: String?,
        season: Int?,
        episode: Int?,
    ): Pair<Int, Int>? {
        videoId?.explicitFranchiseCoords()?.let { return it }
        if (season == null || episode == null) return null
        if (videoId == null || !videoId.hasAnimeNamespacePrefix()) return season to episode
        if (season != 1) return season to episode
        if (videoId.addressesDifferentEntryThan(parentMetaId)) return season to episode
        return null
    }

    /**
     * True when this native id and [parentMetaId] use the same anime namespace but address
     * different entry ids. Entry-local metas always address the parent's own entry, so a
     * mismatch proves the id came from a franchise remap.
     */
    private fun String.addressesDifferentEntryThan(parentMetaId: String?): Boolean {
        if (parentMetaId == null || !parentMetaId.hasAnimeNamespacePrefix()) return false
        val parts = split(':')
        val parentParts = parentMetaId.split(':')
        if (parts.size < 2 || parentParts.size < 2) return false
        if (!parts[0].equals(parentParts[0], ignoreCase = true)) return false
        return parts[1] != parentParts[1]
    }

    /**
     * Re-points the per-entry id namespaces (kitsu/mal/anilist/anidb/simkl) and the
     * season/offset fields at the franchise sibling [entry], keeping the franchise-level ids
     * (imdb/tmdb/tvdb/trakt) already resolved. Replaces wholesale — a stale kitsu id from the
     * opened entry would address the wrong season's streams.
     */
    private fun ResolvedMediaIds.withFranchiseEntry(entry: AnimeIdMapping): ResolvedMediaIds =
        copy(
            simkl = entry.simklId,
            mal = entry.malId,
            kitsu = entry.kitsuId,
            anilist = entry.anilistId,
            anidb = entry.anidbId,
            tmdbSeason = entry.tmdbSeason,
            tvdbSeason = entry.tvdbSeason,
            tmdbEpisodeOffset = entry.tmdbEpisodeOffset,
            tvdbEpisodeOffset = entry.tvdbEpisodeOffset,
            isAnime = true,
        )

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

    // Deliberately excludes "simkl:" — SIMKL ids cover regular TV/movies too, so a simkl:
    // prefix alone must not flag content as anime (the anime-list/Simkl-details lookups set
    // isAnime for genuine simkl anime instead).
    private fun hasAnimeNativePrefix(value: String): Boolean = value.hasAnimeNamespacePrefix()

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
        // Some anime entries span several TMDB/TVDB seasons while anime-list records only the
        // first (Pokémon Advanced Generation is marked season 6 but also covers seasons 7–9).
        // Without an explicit coordinate match we cannot turn S7E41 into a trustworthy
        // entry-local absolute number. Preserve franchise coordinates and let streamLookupVideoId
        // use a franchise namespace instead of silently querying Kitsu episode 41 or episode 1.
        if (franchiseNumbering && nativeMappingCoversFranchiseEpisode == false) {
            return sourceSeason to sourceEpisode
        }
        // Franchise-numbered callers: once the franchise sibling entry has been applied
        // (its mapped season matches the addressed season), convert the franchise episode
        // to that entry's local absolute episode via its episode offset.
        val franchiseEpisode: Int? = if (!franchiseNumbering) {
            null
        } else {
            videoId.explicitFranchiseCoords()
                ?.takeIf { (season, _) -> tvdbSeason == season || tmdbSeason == season }
                ?.second
                ?: sourceEpisode.takeIf {
                    videoId.nativeAnimeEpisode() == null &&
                        sourceSeason != null &&
                        (tvdbSeason == sourceSeason || tmdbSeason == sourceSeason)
                }
        }
        if (franchiseEpisode != null) {
            val offset = preferredAnimeEpisodeOffset(sourceSeason) ?: 0
            return null to (franchiseEpisode - offset).coerceAtLeast(1)
        }
        return null to (videoId.nativeAnimeEpisode() ?: sourceEpisode)
    }
    val suffix = videoId.episodeSuffix()
    return suffix ?: (sourceSeason to sourceEpisode)
}

private fun ResolvedMediaIds.streamLookupVideoId(videoId: String, streamEpisodeParts: Pair<Int?, Int?>): String {
    if (!isAnime || !videoId.hasNativeAnimePrefix()) return videoId
    if (franchiseNumbering && nativeMappingCoversFranchiseEpisode == false) {
        val franchiseBase = when (animeMappingCoordinateSystemFor(sourceId)) {
            AnimeMappingCoordinateSystem.TVDB -> tvdb?.let { "tvdb:$it" }
                ?: tmdb?.let { "tmdb:$it" }
                ?: imdb
            AnimeMappingCoordinateSystem.TMDB -> tmdb?.let { "tmdb:$it" }
                ?: tvdb?.let { "tvdb:$it" }
                ?: imdb
            // Native anime catalogs do not tell us whether a stream addon interprets a Kitsu
            // episode as entry-absolute or franchise-season numbering. IMDb's ordinary Stremio
            // series id preserves the unambiguous S/E coordinates and has the broadest support.
            AnimeMappingCoordinateSystem.AUTO -> imdb
                ?: tmdb?.let { "tmdb:$it" }
                ?: tvdb?.let { "tvdb:$it" }
        }
        val (season, episode) = streamEpisodeParts
        if (franchiseBase != null && season != null && episode != null) {
            return "$franchiseBase:$season:$episode"
        }
    }
    // An entry-addressed (2/3-part) kitsu id is already in the preferred stream namespace and
    // is more specific than the parent-derived ids — it may be a franchise sibling produced by
    // an earlier resolution pass (StreamsRepository re-resolves its own output for request
    // tokens). Re-basing it onto the parent entry would undo that remap, so keep its base.
    val isEntryAddressedKitsu = videoId.startsWith("kitsu:", ignoreCase = true) &&
        videoId.explicitFranchiseCoords() == null
    val preferredBase = when {
        isEntryAddressedKitsu -> videoId.nativeAnimeBase()
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
    hasAnimeNamespacePrefix() || startsWith("simkl:", ignoreCase = true)

/** Anime-only id namespaces (unlike simkl:, these prefixes imply anime content). */
internal fun String.hasAnimeNamespacePrefix(): Boolean =
    startsWith("kitsu:", ignoreCase = true) ||
        startsWith("mal:", ignoreCase = true) ||
        startsWith("myanimelist:", ignoreCase = true) ||
        startsWith("al:", ignoreCase = true) ||
        startsWith("anilist:", ignoreCase = true) ||
        startsWith("anidb:", ignoreCase = true)

/**
 * The mapped TMDB **movie** id for a native anime (or `simkl:`) id, as a `tmdb:` content id.
 *
 * SIMKL hands out per-entry anime ids for anime movies too — the shape the anime pipeline needs,
 * but one only a Kitsu-capable meta addon can answer. A user whose anime metadata comes from TMDB
 * has nothing that resolves it, so the item never gets artwork or text at all. A movie is a single
 * anime-list entry with no season/episode numbering to reconcile, so its mapped TMDB movie id
 * addresses exactly the same title and is a safe *metadata-only* substitute.
 *
 * Metadata only: the native id stays the content id, because that is what stream scrapers and the
 * tracking providers are keyed on. Series are deliberately excluded — a franchise TMDB tv id covers
 * many entries, so the same substitution there would return another season's art and episode list.
 */
internal fun String.animeMovieTmdbFallbackId(): String? {
    val base = nativeAnimeBase()
    if (!base.hasNativeAnimePrefix()) return null
    val parts = base.split(':')
    val entryId = parts.getOrNull(1)?.toIntOrNull() ?: return null
    val entry = when (parts[0].lowercase()) {
        "kitsu" -> AnimeIdMappingRepository.entryForNativeIds(kitsu = entryId)
        "mal", "myanimelist" -> AnimeIdMappingRepository.entryForNativeIds(mal = entryId)
        "al", "anilist" -> AnimeIdMappingRepository.entryForNativeIds(anilist = entryId)
        "anidb" -> AnimeIdMappingRepository.entryForNativeIds(anidb = entryId)
        "simkl" -> AnimeIdMappingRepository.entryForNativeIds(simkl = entryId)
        else -> null
    } ?: return null
    return entry.tmdbMovieIds.firstOrNull()?.let { "tmdb:$it" }
}

/**
 * Season/episode from an explicit 4-part franchise-numbered native id
 * (`prefix:entryId:season:episode`, AIOMetadata style), or null for any other shape.
 */
internal fun String.explicitFranchiseCoords(): Pair<Int, Int>? {
    if (!hasAnimeNamespacePrefix()) return null
    val parts = split(':')
    if (parts.size != 4) return null
    if (parts[1].toIntOrNull() == null) return null
    val season = parts[2].toIntOrNull() ?: return null
    val episode = parts[3].toIntOrNull() ?: return null
    return season to episode
}

internal fun ResolvedMediaIds.canonicalSeasonNumber(sourceSeasonNumber: Int?): Int? =
    when {
        sourceSeasonNumber == null -> null
        // Already franchise numbering — the entry-local season-1 remap must not apply.
        franchiseNumbering -> sourceSeasonNumber
        preferredAnimeSeason() != null && sourceSeasonNumber == 1 -> preferredAnimeSeason()
        else -> sourceSeasonNumber
    }

internal fun ResolvedMediaIds.canonicalEpisodeNumber(sourceEpisodeNumber: Int?): Int? {
    if (sourceEpisodeNumber == null) return null
    if (franchiseNumbering) return sourceEpisodeNumber
    // A caller already passing the entry's mapped (franchise) season is franchise-numbered —
    // re-adding the entry's episode offset would double-map. Entry-local sources (SIMKL,
    // Kitsu addon metas) always report season 1, so they never take this branch.
    val mappedSeason = preferredAnimeSeason()
    if (sourceSeasonNumber != null && sourceSeasonNumber != 1 && sourceSeasonNumber == mappedSeason) {
        return sourceEpisodeNumber
    }
    val offset = preferredAnimeEpisodeOffset(sourceSeasonNumber) ?: 0
    return sourceEpisodeNumber + offset
}

private fun ResolvedMediaIds.preferredAnimeSeason(): Int? = when (animeMappingCoordinateSystemFor(sourceId)) {
    AnimeMappingCoordinateSystem.TMDB -> tmdbSeason
    AnimeMappingCoordinateSystem.TVDB -> tvdbSeason
    AnimeMappingCoordinateSystem.AUTO -> tmdbSeason ?: tvdbSeason
}

private fun ResolvedMediaIds.preferredAnimeEpisodeOffset(sourceSeasonNumber: Int?): Int? =
    when (animeMappingCoordinateSystemFor(sourceId)) {
        AnimeMappingCoordinateSystem.TMDB -> tmdbEpisodeOffset
        AnimeMappingCoordinateSystem.TVDB -> tvdbEpisodeOffset
        AnimeMappingCoordinateSystem.AUTO -> when {
            sourceSeasonNumber != null && tmdbSeason == sourceSeasonNumber && tvdbSeason != sourceSeasonNumber ->
                tmdbEpisodeOffset
            sourceSeasonNumber != null && tvdbSeason == sourceSeasonNumber && tmdbSeason != sourceSeasonNumber ->
                tvdbEpisodeOffset
            else -> tmdbEpisodeOffset ?: tvdbEpisodeOffset
        }
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
    // Native anime ids are `prefix:id:absoluteEpisode` — already canonical in their own
    // namespace. Splicing TVDB-style season/episode numbers into them would replace the
    // entry id itself (`kitsu:123:5` → `kitsu:3:17`), so leave them untouched.
    if (videoId.hasNativeAnimePrefix()) return videoId
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
        // Match the TMDB namespace to the content type; only fall back across namespaces
        // when the mapping has no id of the matching kind.
        tmdb = when (contentType.trim().lowercase()) {
            "series", "tv", "show", "tvshow", "anime" -> tmdbTvId ?: tmdbMovieIds.firstOrNull()
            else -> tmdbMovieIds.firstOrNull() ?: tmdbTvId
        },
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
