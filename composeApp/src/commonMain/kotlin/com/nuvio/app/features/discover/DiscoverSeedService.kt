package com.nuvio.app.features.discover

import co.touchlab.kermit.Logger
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * A seed that has been reconciled to a TMDB id and is ready to recommend against.
 *
 * [tmdbId] is namespaced by [tmdbMediaType]: TMDB movie 1234 and tv 1234 are unrelated titles, so
 * the pair — never the number alone — identifies a seed.
 */
data class DiscoverSeed(
    val tmdbId: Int,
    val tmdbMediaType: String,
    val title: String,
    val lastWatchedAtEpochMs: Long,
    val episodesWatched: Int,
    /** Parent ids that collapsed into this seed — several when an anime has a id per season. */
    val sourceIds: List<String>,
)

/**
 * Turns watch history into a small, de-duplicated set of TMDB-addressable seeds.
 *
 * Two collapses happen, in this order, and both matter:
 *  1. **Episodes → title**, purely on the stored ids ([collapseWatchedToSeedCandidates]).
 *  2. **Ids → show**, after resolution. Anime is the reason: a franchise carries a separate
 *     kitsu/mal id per season, so "Attack on Titan" seasons 1-4 arrive as four unrelated ids that
 *     all map to one TMDB show. Left uncollapsed they would quadruple that franchise's share of
 *     the recommendation rows.
 *
 * Resolution goes through [MediaIdResolver] rather than TMDB's id conversion directly, because it
 * already owns the anime mapping and the movie/tv namespace rules. Re-deriving those here is how
 * seeds end up recommending against the wrong title.
 */
object DiscoverSeedService {
    private val log = Logger.withTag("DiscoverSeeds")

    /**
     * @param maxSeeds how many resolved seeds to return. Each one costs a TMDB request per row
     *   generator downstream, so this is a rate-limit knob as much as a relevance one.
     */
    suspend fun buildSeeds(
        history: List<WatchedItem>,
        now: Long,
        maxSeeds: Int = MAX_SEEDS,
        maxCandidatesToResolve: Int = MAX_CANDIDATES_TO_RESOLVE,
    ): List<DiscoverSeed> {
        val candidates = collapseWatchedToSeedCandidates(history, now = now)
            .take(maxCandidatesToResolve)
        if (candidates.isEmpty()) return emptyList()

        // Keyed by "mediaType:tmdbId" so the two TMDB namespaces never merge.
        val merged = LinkedHashMap<String, DiscoverSeed>()
        // Resolved in parallel chunks rather than one candidate at a time. Each resolution is at
        // least one TMDB round trip, and resolving 24 of them in sequence measured ~20s on a cold
        // Discover build — the whole tab waits behind this, so it is the feature's floor.
        //
        // Chunked rather than one flat fan-out, for two reasons that pull the same way as the
        // prune pass's [PRUNE_CONCURRENCY]: firing two dozen TMDB requests at once is the shape
        // that draws a 429, and the early exit below is worth keeping. Candidate order is
        // preserved across and within chunks, so the merge — which is order-sensitive — behaves
        // exactly as it did when this was a sequential loop.
        for (chunk in candidates.chunked(SEED_RESOLVE_CONCURRENCY)) {
            if (merged.size >= maxSeeds) break
            val resolved = coroutineScope {
                chunk.map { candidate -> async { resolveCandidate(candidate) } }.awaitAll()
            }
            for (seed in resolved.filterNotNull()) {
                val key = "${seed.tmdbMediaType}:${seed.tmdbId}"
                val existing = merged[key]
                merged[key] = if (existing == null) {
                    seed
                } else {
                    // Same show reached by two ids (typically two anime seasons). Keep the newest
                    // watch date and the title that goes with it; sum the episode counts, since
                    // they describe different parts of the same show.
                    val newest = if (seed.lastWatchedAtEpochMs > existing.lastWatchedAtEpochMs) seed else existing
                    newest.copy(
                        episodesWatched = existing.episodesWatched + seed.episodesWatched,
                        sourceIds = (existing.sourceIds + seed.sourceIds).distinct(),
                    )
                }
            }
        }

        return merged.values
            .sortedByDescending { it.lastWatchedAtEpochMs }
            .take(maxSeeds)
    }

    private suspend fun resolveCandidate(candidate: DiscoverSeedCandidate): DiscoverSeed? {
        val mediaType = tmdbMediaTypeFor(candidate.type)
        val resolved = runCatching {
            MediaIdResolver.resolve(
                contentType = candidate.type,
                parentMetaId = candidate.parentId,
                videoId = null,
                title = candidate.title,
                isAnimeHint = candidate.type.equals("anime", ignoreCase = true),
            )
        }.getOrElse { error ->
            log.w { "Seed resolution failed for ${candidate.parentId}: ${error.message}" }
            return null
        }

        val tmdbId = resolved.tmdb?.takeIf { it > 0 } ?: run {
            log.d { "Seed ${candidate.parentId} (${candidate.title}) has no TMDB id; skipped" }
            return null
        }

        // A seed with no title would render a headless "Because you watched" row. The id is known,
        // so ask TMDB what it is called rather than dropping an otherwise good seed.
        val title = candidate.title.ifBlank {
            runCatching { TmdbService.fetchTitle(tmdbId, mediaType) }.getOrNull().orEmpty()
        }
        if (title.isBlank()) {
            log.d { "Seed ${candidate.parentId} has no resolvable title; skipped" }
            return null
        }

        return DiscoverSeed(
            tmdbId = tmdbId,
            tmdbMediaType = mediaType,
            title = title,
            lastWatchedAtEpochMs = candidate.lastWatchedAtEpochMs,
            episodesWatched = candidate.episodesWatched,
            sourceIds = listOf(candidate.parentId),
        )
    }

    /**
     * Ranks the genres behind [seeds], for the rows that recommend by taste rather than by title.
     *
     * Costs one TMDB request per seed (their genres are not in the watch history), so callers pass
     * the profile seed set — deliberately larger than the row budget, since a histogram built from
     * four titles is mostly noise — and the result is cached by the caller alongside the rows.
     *
     * Genre ids are resolved to names per media type before ranking. That indirection is not
     * incidental: TMDB's movie and tv genre id spaces are different vocabularies, so ids cannot be
     * pooled and a name is the only thing the two halves of a mixed history share.
     */
    suspend fun resolveGenreAffinities(
        seeds: List<DiscoverSeed>,
        now: Long,
        excludedGenres: Set<String> = emptySet(),
    ): List<GenreAffinity> {
        if (seeds.isEmpty()) return emptyList()
        val namesByType = fetchGenreNamesFor(seeds.mapTo(mutableSetOf()) { it.tmdbMediaType })
        if (namesByType.values.all { it.isEmpty() }) {
            log.d { "No TMDB genre list available; skipping genre-backed rows" }
            return emptyList()
        }

        val seedGenres = coroutineScope {
            seeds.map { seed ->
                async {
                    val ids = runCatching {
                        TmdbService.fetchGenreIds(seed.tmdbId, seed.tmdbMediaType)
                    }.getOrDefault(emptyList())
                    val names = namesByType[seed.tmdbMediaType].orEmpty()
                    SeedGenres(
                        mediaType = seed.tmdbMediaType,
                        lastWatchedAtEpochMs = seed.lastWatchedAtEpochMs,
                        genres = ids.mapNotNull { id -> names[id]?.let { id to it } },
                        episodesWatched = seed.episodesWatched,
                    )
                }
            }.awaitAll()
        }.filter { it.genres.isNotEmpty() }

        val affinities = buildGenreAffinities(
            seeds = seedGenres,
            now = now,
            excludedGenres = excludedGenres,
        )
        log.d {
            "Genre affinities: " +
                affinities.take(8).joinToString { "${it.name}=${(it.weight * 100).toInt() / 100.0}" }
        }
        return affinities
    }

    /**
     * TMDB genre ids to drop on sight, per media type — the id form of the user's excluded-genre
     * names. Resolved here rather than at the call site because only this file knows that the two
     * media types number their genres differently.
     */
    suspend fun resolveExcludedGenreIds(excludedGenres: Set<String>): Map<String, Set<Int>> =
        resolveGenreIdsByType(excludedGenres)

    /**
     * Canonical genre names → TMDB ids, per media type.
     *
     * The names are ours ([DiscoverGenreNames]); each is translated to the TMDB name that namespace
     * actually uses before matching, so "Science Fiction" finds `Science Fiction` for films and
     * `Sci-Fi & Fantasy` for television. A genre one namespace does not have — horror on television
     * — contributes nothing there, which is the correct outcome rather than a gap to paper over.
     */
    suspend fun resolveGenreIdsByType(genres: Set<String>): Map<String, Set<Int>> {
        if (genres.isEmpty()) return emptyMap()
        return fetchGenreNamesFor(setOf("movie", "tv")).mapValues { (mediaType, names) ->
            val wanted = genres.mapNotNullTo(mutableSetOf()) { canonical ->
                discoverGenreTmdbName(canonical, mediaType)?.trim()?.lowercase()
            }
            if (wanted.isEmpty()) emptySet() else names.filterValues { it.trim().lowercase() in wanted }.keys
        }.filterValues { it.isNotEmpty() }
    }

    private suspend fun fetchGenreNamesFor(mediaTypes: Set<String>): Map<String, Map<Int, String>> =
        coroutineScope {
            mediaTypes.map { type ->
                async { type to runCatching { TmdbService.fetchGenreNames(type) }.getOrDefault(emptyMap()) }
            }.awaitAll()
        }.toMap()

    /** Seeds actually used for rows. One "Because you watched" row each, so keep it small. */
    const val MAX_SEEDS = 4

    /**
     * Seeds resolved for the *genre* histogram, independent of how many "Because you watched" rows
     * the user asked for. Four titles is too thin a base to call a taste profile, and the rows that
     * read the profile are the ones that survive when the row budget is set to zero.
     */
    const val MAX_PROFILE_SEEDS = 12

    /**
     * Candidates resolved before giving up. Higher than [MAX_SEEDS] because resolution drops
     * anything without a TMDB id — local files, dead ids, anime missing from the mapping — and
     * without headroom a few unresolvable recent watches would leave the rows empty.
     */
    const val MAX_CANDIDATES_TO_RESOLVE = 24

    /**
     * Candidates resolved concurrently in [buildSeeds].
     *
     * Sized to cut the round-trip count without becoming a burst: at 6, the full
     * [MAX_CANDIDATES_TO_RESOLVE] costs four sequential waits instead of twenty-four. Higher is
     * tempting and is what draws TMDB's rate limit — the logs behind this change already showed
     * `external_ids` returning 429 at the *sequential* request rate, and `TmdbService` has no
     * backoff to absorb it.
     */
    private const val SEED_RESOLVE_CONCURRENCY = 6
}
