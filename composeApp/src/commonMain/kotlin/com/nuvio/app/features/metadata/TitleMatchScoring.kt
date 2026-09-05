package com.nuvio.app.features.metadata

import com.nuvio.app.features.locallibrary.normalizeLocalMatchTitle
import com.nuvio.app.features.tmdb.TmdbSearchResult
import kotlin.math.abs

/**
 * Title/year scoring shared by the lookups that start from a release name rather than an id — the
 * local-library auto-matcher and the filename-only catalog resolver both feed a parsed title into
 * TMDB search and need the same tolerances and the same "don't guess" threshold.
 */

/** Auto-accept threshold: below this the best candidate is a guess, so callers leave the item as-is. */
internal const val TITLE_MATCH_ACCEPT_SCORE = 0.72

/**
 * A ±1 year gap is free — a release name and TMDB routinely disagree by a year (festival vs wide
 * release, a late-December air date). ±2 is still reachable but has to be paid for with a better
 * title match; anything wider is almost certainly a different title.
 */
internal fun yearMismatchPenalty(a: Int?, b: Int?): Double {
    if (a == null || b == null) return 0.0
    return when (abs(a - b)) {
        0, 1 -> 0.0
        2 -> 0.2
        else -> 0.45
    }
}

/**
 * Ranks candidates that tie on score: an exactly matching year wins over a merely tolerated one.
 *
 * [yearMismatchPenalty] treats a ±1 gap as free, which is right for a single title dated
 * differently by two sources but leaves consecutive-year entries of the *same* title tied — anime
 * sequels routinely differ only by punctuation that normalisation erases ("Kaguya-sama: Love is War"
 * 2019 vs "Kaguya-sama: Love is War?" 2020). Without this the winner is whichever the provider
 * happened to list first. It is a tie-break rather than a bonus on purpose: nothing may cross the
 * accept threshold on the strength of its year alone.
 */
internal fun exactYearRank(local: Int?, candidate: Int?): Int =
    if (local != null && local == candidate) 1 else 0

/**
 * Token-overlap score, with two allowances for how release names spell titles.
 *
 * [allowCharacterSimilarity] adds a character-level floor for callers that would rather show a
 * slightly-wrong title than no title. It is off for the local library, where a wrong match would
 * scrobble the wrong show.
 */
internal fun titleSimilarity(a: String, b: String, allowCharacterSimilarity: Boolean = false): Double {
    if (a == b) return 1.0
    // Normalisation turns punctuation into spaces, so "Pans Labyrinth" and "Pan's Labyrinth" end up
    // as {pans, labyrinth} vs {pan, s, labyrinth} and token overlap collapses to 0.25. Compared
    // without separators at all they are identical, which is what a release name meant to say.
    val compactA = a.filter { it.isLetterOrDigit() }
    val compactB = b.filter { it.isLetterOrDigit() }
    if (compactA.isNotEmpty() && compactA == compactB) return 1.0

    val tokensA = a.split(' ').filter { it.isNotBlank() }.toSet()
    val tokensB = b.split(' ').filter { it.isNotBlank() }.toSet()
    if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
    val intersection = tokensA.intersect(tokensB).size.toDouble()
    val union = tokensA.union(tokensB).size.toDouble()
    val jaccard = intersection / union
    val containment = if (b.startsWith(a) || a.startsWith(b)) 0.15 else 0.0
    val tokenScore = (jaccard + containment).coerceAtMost(1.0)
    if (!allowCharacterSimilarity) return tokenScore
    return maxOf(tokenScore, bigramDice(compactA, compactB))
}

/**
 * Sørensen–Dice over character bigrams. Rescues a title carrying one leftover token the cleaner
 * didn't know about ("friends 1994" vs "friends"), which token overlap alone scores just under the
 * accept threshold, without matching genuinely different titles ("Dune" vs "Dune Part Two" = 0.46).
 */
private fun bigramDice(a: String, b: String): Double {
    if (a.length < 2 || b.length < 2) return 0.0
    val remaining = HashMap<String, Int>()
    a.windowed(2).forEach { bigram -> remaining[bigram] = (remaining[bigram] ?: 0) + 1 }
    var shared = 0
    b.windowed(2).forEach { bigram ->
        val count = remaining[bigram] ?: 0
        if (count > 0) {
            remaining[bigram] = count - 1
            shared++
        }
    }
    return 2.0 * shared / (a.length - 1 + b.length - 1)
}

/** Best TMDB search hit for a parsed title/year, or null when nothing clears [minScore]. */
internal fun pickBestTmdbMatch(
    title: String,
    year: Int?,
    results: List<TmdbSearchResult>,
    minScore: Double = TITLE_MATCH_ACCEPT_SCORE,
    fuzzy: Boolean = false,
): TmdbSearchResult? {
    if (results.isEmpty()) return null
    val target = normalizeLocalMatchTitle(title)
    if (target.isBlank()) return null
    val scored = results.mapNotNull { result ->
        val candidate = normalizeLocalMatchTitle(result.displayTitle)
        if (candidate.isBlank()) return@mapNotNull null
        val similarity = titleSimilarity(target, candidate, allowCharacterSimilarity = fuzzy)
        result to (similarity - yearMismatchPenalty(year, result.year))
    }
    val best = scored.maxWithOrNull(
        compareBy({ it.second }, { exactYearRank(year, it.first.year) }),
    ) ?: return null
    return best.first.takeIf { best.second >= minScore }
}
