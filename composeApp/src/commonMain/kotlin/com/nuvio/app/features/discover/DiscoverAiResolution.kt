package com.nuvio.app.features.discover

import com.nuvio.app.features.tmdb.TmdbSearchResult

/**
 * Turning a suggested title into a real one — plan §5.
 *
 * The matching decision is pure and separate from the lookup because it is where the interesting
 * failure lives: **TMDB search returns something for almost any query.** A model told never to
 * invent a title will occasionally invent one anyway, and a search for a film that does not exist
 * comes back with a real, unrelated film — which would then appear in the row wearing the invented
 * title's reason. Requiring the result to actually look like what was asked for is what stops a
 * hallucination becoming a recommendation.
 */

/**
 * Case, punctuation and article-insensitive form used for comparison only.
 *
 * Articles go because "The Thing" and "Thing" are the same film to a person and different strings
 * to TMDB depending on which language's title won.
 */
fun normalizeTitleForMatch(title: String): String {
    val stripped = title.lowercase()
        .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() }
    val withoutLeadingArticle = stripped.drop(1).takeIf {
        stripped.size > 1 && stripped.first() in setOf("the", "a", "an")
    } ?: stripped
    return withoutLeadingArticle.joinToString(" ")
}

/** How close a non-exact match has to be, as a fraction of the longer title's length. */
private const val MIN_CONTAINMENT_RATIO = 0.6

/** How far a result's year may sit from the suggested one and still count as the same title. */
private const val YEAR_TOLERANCE = 1

/**
 * The best match for [title]/[year] among [results], or null when none of them is plausibly it.
 *
 * Ranking, in order: an exact normalised title beats a partial one, a year within
 * [YEAR_TOLERANCE] beats one outside it, and popularity breaks the remaining ties. Returning null
 * is a normal outcome — the caller drops the suggestion.
 */
fun pickBestTmdbMatch(
    title: String,
    year: Int?,
    results: List<TmdbSearchResult>,
): TmdbSearchResult? {
    val wanted = normalizeTitleForMatch(title)
    if (wanted.isBlank()) return null

    return results.asSequence()
        .mapNotNull { result ->
            val candidate = normalizeTitleForMatch(result.displayTitle)
            val titleScore = when {
                candidate.isBlank() -> return@mapNotNull null
                candidate == wanted -> 2
                containsCloseEnough(wanted, candidate) -> 1
                else -> return@mapNotNull null
            }
            val yearScore = when {
                year == null || result.year == null -> 0
                kotlin.math.abs(result.year!! - year) <= YEAR_TOLERANCE -> 1
                // A confident year that disagrees is the strongest signal available that this is a
                // different title with a similar name, so it ranks below having no year at all.
                else -> -1
            }
            Triple(result, titleScore, yearScore)
        }
        .sortedWith(
            compareByDescending<Triple<TmdbSearchResult, Int, Int>> { it.second }
                .thenByDescending { it.third }
                .thenByDescending { it.first.popularity },
        )
        .firstOrNull()
        ?.first
}

/**
 * True when one normalised title contains the other and they are close enough in length.
 *
 * The ratio is what stops "Alien" matching "Alien vs Predator vs The Terminator" — containment on
 * its own makes every short title match every long one that happens to include the word.
 */
private fun containsCloseEnough(wanted: String, candidate: String): Boolean {
    val longer = maxOf(wanted.length, candidate.length)
    if (longer == 0) return false
    val shorter = minOf(wanted.length, candidate.length)
    if (shorter.toDouble() / longer < MIN_CONTAINMENT_RATIO) return false
    return candidate.contains(wanted) || wanted.contains(candidate)
}
