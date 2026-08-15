package com.nuvio.app.features.locallibrary

import com.nuvio.app.features.kitsu.KitsuSearchResult
import com.nuvio.app.features.kitsu.KitsuService
import com.nuvio.app.features.metadata.AnimeIdMappingRepository
import com.nuvio.app.features.metadata.AnimeIdPreference
import com.nuvio.app.features.metadata.AnimeIdPreferenceRepository
import com.nuvio.app.features.metadata.pickBestTmdbMatch
import com.nuvio.app.features.metadata.titleSimilarity
import com.nuvio.app.features.metadata.yearMismatchPenalty
import com.nuvio.app.features.tmdb.TmdbArtwork
import com.nuvio.app.features.tmdb.TmdbSearchResult
import com.nuvio.app.features.tmdb.TmdbService

/**
 * Resolves local items to external ids. Anime items ([LocalMediaItem.isAnime]) are matched against
 * Kitsu first — TMDB search is unreliable for anime — and fall back to TMDB when Kitsu misses;
 * everything else goes straight to TMDB. Auto-matching is deliberately conservative: a folder that
 * doesn't confidently match is left UNMATCHED (so it never scrobbles the wrong show) and surfaced
 * to the user to fix manually.
 */
internal object LocalMatcher {

    /** Attempts to auto-resolve ids for an unmatched item. Manual matches are never touched. */
    suspend fun autoMatch(item: LocalMediaItem): LocalMediaItem {
        if (item.matchState == LocalMatchState.MANUAL || item.isMatched) return item
        // Anime → Kitsu first; a hit gives a native kitsu id (+ imdb/tmdb back-filled from the
        // anime-list mapping). On a miss we still try TMDB so the title gets *some* id.
        if (item.isAnime) {
            matchAnime(item)?.let { return it }
        }
        val mediaType = item.tmdbMediaType()
        // Deliberately no year filter: TMDB's primary_release_year is a hard filter, but a local
        // library's year often differs from TMDB's by a year (festival vs wide release — e.g.
        // "Obsession" is 2025 or 2026 depending on the source). We instead let the year nudge
        // scoring in pickBest, where a ±1 gap is treated as a match.
        val results = runCatching { TmdbService.searchTitles(item.title, mediaType) }
            .getOrDefault(emptyList())
        val best = pickBest(item, results) ?: return item
        return item.applyTmdbMatch(
            tmdbId = best.id,
            posterPath = best.posterPath,
            backdropPath = best.backdropPath,
            state = LocalMatchState.AUTO,
        )
    }

    private suspend fun matchAnime(item: LocalMediaItem): LocalMediaItem? {
        val results = runCatching { KitsuService.searchTitles(item.title, preferMovie = item.type == LocalFolderType.MOVIES) }
            .getOrDefault(emptyList())
        val best = pickBestKitsu(item, results) ?: return null
        return item.applyKitsuMatch(kitsuId = best.id, poster = best.poster, state = LocalMatchState.AUTO)
    }

    /** Compatibility entry point for callers whose provider still follows the target folder. */
    suspend fun search(query: String, type: LocalFolderType, isAnime: Boolean): List<LocalMatchCandidate> {
        val provider = if (isAnime) LocalMatchProvider.KITSU else LocalMatchProvider.TMDB
        return search(query, type, provider)
    }

    /** Text search using the provider explicitly selected in the Fix-match dialog. */
    suspend fun search(
        query: String,
        type: LocalFolderType,
        provider: LocalMatchProvider,
    ): List<LocalMatchCandidate> {
        if (provider == LocalMatchProvider.KITSU) {
            return runCatching { KitsuService.searchTitles(query, preferMovie = type == LocalFolderType.MOVIES) }
                .getOrDefault(emptyList())
                .map { result ->
                    LocalMatchCandidate(
                        kitsuId = result.id,
                        type = if (result.isMovie) LocalFolderType.MOVIES else LocalFolderType.SERIES,
                        title = result.title,
                        year = result.year,
                        poster = result.poster,
                        overview = result.synopsis,
                    )
                }
        }
        val mediaType = if (type == LocalFolderType.SERIES) "tv" else "movie"
        return runCatching { TmdbService.searchTitles(query, mediaType) }
            .getOrDefault(emptyList())
            .map { result ->
                LocalMatchCandidate(
                    tmdbId = result.id,
                    type = if (result.isTv) LocalFolderType.SERIES else type,
                    title = result.displayTitle,
                    year = result.year,
                    poster = TmdbService.tmdbImageUrl(result.posterPath),
                    overview = result.overview,
                )
            }
    }

    /** Applies a chosen TMDB id (from a candidate or a rescan) and back-fills the IMDb id + poster. */
    suspend fun applyTmdbId(item: LocalMediaItem, tmdbId: Int, state: LocalMatchState): LocalMediaItem =
        item.applyTmdbMatch(
            tmdbId = tmdbId,
            posterPath = null,
            state = state,
            replaceNativeAnimeIdentity = true,
        )

    /** Applies a chosen Kitsu id (from a candidate) and back-fills imdb/tmdb from the anime mapping. */
    suspend fun applyKitsuId(item: LocalMediaItem, kitsuId: Int, poster: String?, state: LocalMatchState): LocalMediaItem =
        item.applyKitsuMatch(kitsuId = kitsuId, poster = poster, state = state)

    /**
     * Resolves an ID entered into the unified search field. Explicit IDs override the selected
     * text-search provider. A bare number keeps the old ID box's TMDB meaning only while TMDB is
     * selected, allowing numeric anime titles such as "86" to remain searchable through Kitsu.
     */
    suspend fun applySearchInput(
        item: LocalMediaItem,
        raw: String,
        provider: LocalMatchProvider,
    ): LocalMediaItem? = when (val parsed = parseLocalMatchId(raw, provider)) {
        is LocalMatchInputId.Kitsu ->
            item.applyKitsuMatch(parsed.id, poster = null, state = LocalMatchState.MANUAL)
        is LocalMatchInputId.Mal ->
            item.copy(
                kitsuId = null,
                malId = parsed.id,
                isAnime = true,
                matchState = LocalMatchState.MANUAL,
            )
        is LocalMatchInputId.Imdb -> {
            val mediaType = item.tmdbMediaType()
            val tmdb = runCatching {
                TmdbService.ensureTmdbId(parsed.id, mediaType)?.toIntOrNull()
            }.getOrNull()
            val artwork = tmdb?.let { artworkFromTmdb(it, mediaType) }
            item.withFranchiseIdentity(
                imdbId = parsed.id,
                tmdbId = tmdb,
                poster = artwork?.poster ?: item.poster,
                background = artwork?.backdrop ?: item.background,
                matchState = LocalMatchState.MANUAL,
            )
        }
        is LocalMatchInputId.Tmdb ->
            item.applyTmdbMatch(
                tmdbId = parsed.id,
                posterPath = null,
                state = LocalMatchState.MANUAL,
                replaceNativeAnimeIdentity = true,
            )
        null -> null
    }

    /** Retained for non-UI callers; bare numeric input historically meant TMDB here. */
    suspend fun applyPastedId(item: LocalMediaItem, raw: String): LocalMediaItem? =
        applySearchInput(item, raw, LocalMatchProvider.TMDB)

    /**
     * Resolves a Kitsu id to a full local match: sets the native kitsu id and back-fills the
     * franchise-level imdb/tmdb ids (and a poster) from the offline anime-list mapping so the
     * details page still opens through the usual meta addons.
     */
    private suspend fun LocalMediaItem.applyKitsuMatch(
        kitsuId: Int,
        poster: String?,
        state: LocalMatchState,
    ): LocalMediaItem {
        val mapping = AnimeIdMappingRepository.entryForNativeIds(kitsu = kitsuId)
        val mediaType = tmdbMediaType()
        val tmdb = if (type == LocalFolderType.SERIES) mapping?.tmdbTvId else mapping?.tmdbMovieIds?.firstOrNull()
        val imdb = mapping?.imdbIds?.firstOrNull()
        // A search hit supplies Kitsu's own poster; an id typed into the Fix-match dialog supplies
        // none, and would otherwise fall through to the franchise's TMDB art — the same surprise as
        // refreshing a poster used to give. Fetch the entry's own art when it is what the identity
        // preference asked for.
        val nativePoster = poster
            ?: kitsuId
                .takeIf { AnimeIdPreferenceRepository.current() != AnimeIdPreference.IMDB }
                ?.let { id -> runCatching { KitsuService.fetchPosterUrl(id) }.getOrNull() }
        // Kitsu gives a poster but no backdrop; the mapped TMDB id supplies the landscape art.
        val tmdbArtwork = tmdb?.takeIf { nativePoster == null || background == null }
            ?.let { artworkFromTmdb(it, mediaType) }
        val resolvedPoster = nativePoster ?: tmdbArtwork?.poster ?: this.poster
        return copy(
            kitsuId = kitsuId,
            malId = mapping?.malId ?: malId,
            tmdbId = tmdb ?: tmdbId,
            imdbId = imdb ?: imdbId,
            isAnime = true,
            poster = resolvedPoster,
            background = background ?: tmdbArtwork?.backdrop,
            matchState = state,
        )
    }

    private suspend fun LocalMediaItem.applyTmdbMatch(
        tmdbId: Int,
        posterPath: String?,
        state: LocalMatchState,
        backdropPath: String? = null,
        replaceNativeAnimeIdentity: Boolean = false,
    ): LocalMediaItem {
        val mediaType = tmdbMediaType()
        val imdb = runCatching { TmdbService.tmdbToImdb(tmdbId, mediaType) }.getOrNull()
        // A search hit already carries both paths; an id typed into the Fix-match dialog carries
        // neither, so fall back to a single detail lookup that returns both.
        val searchPoster = TmdbService.tmdbImageUrl(posterPath)
        val searchBackdrop = TmdbService.tmdbImageUrl(backdropPath, size = LOCAL_BACKDROP_SIZE)
        val fetched = if (searchPoster == null || searchBackdrop == null) {
            artworkFromTmdb(tmdbId, mediaType)
        } else {
            null
        }
        val poster = searchPoster ?: fetched?.poster ?: poster
        // The local library renders as landscape cards and drives the TV-mode hero; without a
        // backdrop both fall back to stretching the portrait poster.
        val background = searchBackdrop ?: fetched?.backdrop ?: background
        return if (replaceNativeAnimeIdentity) {
            withFranchiseIdentity(
                imdbId = imdb,
                tmdbId = tmdbId,
                poster = poster,
                background = background,
                matchState = state,
            )
        } else {
            copy(
                tmdbId = tmdbId,
                imdbId = imdb ?: imdbId,
                poster = poster,
                background = background,
                matchState = state,
            )
        }
    }

    private suspend fun artworkFromTmdb(tmdbId: Int, mediaType: String): TmdbArtwork? =
        runCatching {
            TmdbService.fetchArtwork(tmdbId, mediaType, backdropSize = LOCAL_BACKDROP_SIZE)
        }.getOrNull()

    private suspend fun posterFromTmdb(tmdbId: Int, mediaType: String): String? =
        artworkFromTmdb(tmdbId, mediaType)?.poster

    private fun LocalMediaItem.tmdbMediaType(): String =
        if (type == LocalFolderType.SERIES) "tv" else "movie"

    private fun pickBestKitsu(item: LocalMediaItem, results: List<KitsuSearchResult>): KitsuSearchResult? {
        if (results.isEmpty()) return null
        val target = normalizeLocalMatchTitle(item.title)
        val wantMovie = item.type == LocalFolderType.MOVIES
        val scored = results.mapNotNull { result ->
            // Score against the best-matching title variant — a folder may use romaji or English.
            val similarity = result.matchTitles
                .map { normalizeLocalMatchTitle(it) }
                .filter { it.isNotBlank() }
                .maxOfOrNull { titleSimilarity(target, it) }
                ?: return@mapNotNull null
            val yearPenalty = yearMismatchPenalty(item.year, result.year)
            // Kitsu's movie/TV subtype disagreeing with the folder is a soft signal, not decisive
            // (OVA/ONA/special all file under a "series" folder).
            val typePenalty = if (result.isMovie == wantMovie) 0.0 else 0.15
            result to (similarity - yearPenalty - typePenalty)
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { best.second >= 0.72 }
    }

    // Requires a strong title match to auto-accept; borderline cases stay unmatched for the user.
    private fun pickBest(item: LocalMediaItem, results: List<TmdbSearchResult>): TmdbSearchResult? =
        pickBestTmdbMatch(title = item.title, year = item.year, results = results)

}

enum class LocalMatchProvider {
    KITSU,
    TMDB,
}

internal sealed interface LocalMatchInputId {
    data class Imdb(val id: String) : LocalMatchInputId
    data class Tmdb(val id: Int) : LocalMatchInputId
    data class Kitsu(val id: Int) : LocalMatchInputId
    data class Mal(val id: Int) : LocalMatchInputId
}

private val nativeAnimeIdRegex = Regex("""(?i)^\s*(kitsu|mal|myanimelist)[:/](\d+)\s*$""")
private val imdbIdRegex = Regex("""(?i)^\s*(tt\d{6,9})\s*$""")
private val tmdbUrlRegex =
    Regex("""(?i)^\s*(?:https?://)?(?:www\.)?themoviedb\.org/(?:movie|tv)/(\d+)(?:[-/?#].*)?\s*$""")
private val tmdbPrefixRegex = Regex("""(?i)^\s*tmdb[:/](\d+)\s*$""")
private val bareNumberRegex = Regex("""^\s*(\d{1,9})\s*$""")

internal fun parseLocalMatchId(
    raw: String,
    provider: LocalMatchProvider,
): LocalMatchInputId? {
    nativeAnimeIdRegex.matchEntire(raw)?.let { match ->
        val id = match.groupValues[2].toIntOrNull() ?: return null
        return if (match.groupValues[1].equals("kitsu", ignoreCase = true)) {
            LocalMatchInputId.Kitsu(id)
        } else {
            LocalMatchInputId.Mal(id)
        }
    }
    imdbIdRegex.matchEntire(raw)?.let { match ->
        return LocalMatchInputId.Imdb(match.groupValues[1].lowercase())
    }
    val tmdb = tmdbUrlRegex.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()
        ?: tmdbPrefixRegex.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()
    if (tmdb != null) return LocalMatchInputId.Tmdb(tmdb)
    if (provider != LocalMatchProvider.TMDB) return null
    val bare = bareNumberRegex.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return LocalMatchInputId.Tmdb(bare)
}

/**
 * An explicitly selected IMDb/TMDB identity means the user wants franchise season coordinates,
 * even when the folder is marked as anime. Native ids must be removed because [LocalMediaItem.contentId]
 * deliberately gives Kitsu/MAL priority for entry-relative numbering.
 */
internal fun LocalMediaItem.withFranchiseIdentity(
    imdbId: String?,
    tmdbId: Int?,
    poster: String?,
    matchState: LocalMatchState,
    background: String? = this.background,
): LocalMediaItem = copy(
    imdbId = imdbId,
    tmdbId = tmdbId,
    kitsuId = null,
    malId = null,
    poster = poster,
    background = background,
    matchState = matchState,
)

/** Landscape cards and the TV-mode hero both read this, so w1280 rather than a thumbnail size. */
private const val LOCAL_BACKDROP_SIZE = "w1280"
