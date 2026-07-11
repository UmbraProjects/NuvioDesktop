package com.nuvio.app.features.locallibrary

import com.nuvio.app.features.tmdb.TmdbSearchResult
import com.nuvio.app.features.tmdb.TmdbService

/**
 * Resolves local items to TMDB/IMDb ids. Auto-matching is deliberately conservative — a folder
 * that doesn't confidently match a TMDB title is left UNMATCHED (so it never scrobbles the wrong
 * show) and surfaced to the user to fix manually.
 */
internal object LocalMatcher {

    /** Attempts to auto-resolve ids for an unmatched item. Manual matches are never touched. */
    suspend fun autoMatch(item: LocalMediaItem): LocalMediaItem {
        if (item.matchState == LocalMatchState.MANUAL || item.isMatched) return item
        val mediaType = item.tmdbMediaType()
        // Deliberately no year filter: TMDB's primary_release_year is a hard filter, but a local
        // library's year often differs from TMDB's by a year (festival vs wide release — e.g.
        // "Obsession" is 2025 or 2026 depending on the source). We instead let the year nudge
        // scoring in pickBest, where a ±1 gap is treated as a match.
        val results = runCatching { TmdbService.searchTitles(item.title, mediaType) }
            .getOrDefault(emptyList())
        val best = pickBest(item, results) ?: return item
        return item.applyTmdbMatch(tmdbId = best.id, posterPath = best.posterPath, state = LocalMatchState.AUTO)
    }

    /** Text search for the Fix-match dialog. */
    suspend fun search(query: String, type: LocalFolderType): List<LocalMatchCandidate> {
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
        item.applyTmdbMatch(tmdbId = tmdbId, posterPath = null, state = state)

    /**
     * Parses a user-pasted id into an override. Accepts `tt1234567`, `tmdb:1234`, a bare TMDB
     * number, or a themoviedb.org URL. Returns null when nothing recognisable is present.
     */
    suspend fun applyPastedId(item: LocalMediaItem, raw: String): LocalMediaItem? {
        val text = raw.trim()
        if (text.isBlank()) return null

        imdbIdRegex.find(text)?.value?.let { imdb ->
            val mediaType = item.tmdbMediaType()
            val tmdb = runCatching { TmdbService.ensureTmdbId(imdb, mediaType)?.toIntOrNull() }.getOrNull()
            return item.copy(
                imdbId = imdb,
                tmdbId = tmdb ?: item.tmdbId,
                poster = tmdb?.let { posterFromTmdb(it, mediaType) } ?: item.poster,
                matchState = LocalMatchState.MANUAL,
            )
        }

        val tmdbId = tmdbFromText(text) ?: return null
        return item.applyTmdbMatch(tmdbId = tmdbId, posterPath = null, state = LocalMatchState.MANUAL)
    }

    private suspend fun LocalMediaItem.applyTmdbMatch(
        tmdbId: Int,
        posterPath: String?,
        state: LocalMatchState,
    ): LocalMediaItem {
        val mediaType = tmdbMediaType()
        val imdb = runCatching { TmdbService.tmdbToImdb(tmdbId, mediaType) }.getOrNull()
        val poster = TmdbService.tmdbImageUrl(posterPath) ?: posterFromTmdb(tmdbId, mediaType) ?: poster
        return copy(
            tmdbId = tmdbId,
            imdbId = imdb ?: imdbId,
            poster = poster,
            matchState = state,
        )
    }

    private suspend fun posterFromTmdb(tmdbId: Int, mediaType: String): String? =
        runCatching { TmdbService.fetchPosterUrl(tmdbId, mediaType) }.getOrNull()

    private fun LocalMediaItem.tmdbMediaType(): String =
        if (type == LocalFolderType.SERIES) "tv" else "movie"

    private fun pickBest(item: LocalMediaItem, results: List<TmdbSearchResult>): TmdbSearchResult? {
        if (results.isEmpty()) return null
        val target = normalize(item.title)
        val scored = results.mapNotNull { result ->
            val candidate = normalize(result.displayTitle)
            if (candidate.isBlank()) return@mapNotNull null
            val similarity = titleSimilarity(target, candidate)
            val yearPenalty = yearMismatchPenalty(item.year, result.year)
            (result to (similarity - yearPenalty))
        }
        val best = scored.maxByOrNull { it.second } ?: return null
        // Require a strong title match to auto-accept; borderline cases stay unmatched for the user.
        return best.first.takeIf { best.second >= 0.72 }
    }

    private fun yearMismatchPenalty(a: Int?, b: Int?): Double {
        if (a == null || b == null) return 0.0
        return when (kotlin.math.abs(a - b)) {
            // A ±1 gap is a match — a local library and TMDB routinely disagree by a year
            // (festival vs wide release). Only larger gaps count against the candidate.
            0, 1 -> 0.0
            2 -> 0.2
            else -> 0.45
        }
    }

    private fun titleSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val tokensA = a.split(' ').filter { it.isNotBlank() }.toSet()
        val tokensB = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble()
        val jaccard = intersection / union
        val containment = if (b.startsWith(a) || a.startsWith(b)) 0.15 else 0.0
        return (jaccard + containment).coerceAtMost(1.0)
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")

    private val imdbIdRegex = Regex("""tt\d{6,9}""", RegexOption.IGNORE_CASE)
    private val tmdbUrlRegex = Regex("""themoviedb\.org/(?:movie|tv)/(\d+)""", RegexOption.IGNORE_CASE)
    private val tmdbPrefixRegex = Regex("""(?i)tmdb[:/](\d+)""")
    private val bareNumberRegex = Regex("""^\d{1,9}$""")

    private fun tmdbFromText(text: String): Int? =
        tmdbUrlRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: tmdbPrefixRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: bareNumberRegex.find(text.trim())?.value?.toIntOrNull()
}
