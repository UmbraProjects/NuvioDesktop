package com.nuvio.app.features.tracking

/** Why the rating prompt is being offered — also decides the wording the user sees. */
enum class RatingPromptReason {
    MOVIE,
    SEASON_FINALE,
    SERIES_FINALE,
}

/**
 * Series whose status means no further episodes are coming.
 *
 * Addons pass TMDB's vocabulary through, with the usual spelling drift on "cancelled". Anything
 * unrecognised — including a missing status — is treated as still running, so an ambiguous show
 * gets the season-finale wording rather than being wrongly declared over.
 */
private val ENDED_SERIES_STATUSES = setOf("ended", "canceled", "cancelled")

/**
 * Whether finishing this item should offer a rating, and on what grounds.
 *
 * Deliberately decided from the *full* episode list rather than the released one. "Last episode
 * released so far" is just the newest episode of an airing season, and prompting there would ask
 * the user to rate a season every single week; the finale is the highest episode number the
 * metadata knows about, which for an airing season is the real finale with a future air date.
 *
 * Specials (season 0) never prompt: they are not part of a season's arc, and metadata routinely
 * appends them after the finale.
 *
 * [seasonEpisodeNumbers] maps a main season number to every episode number it contains.
 */
fun ratingPromptReasonFor(
    contentType: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    seasonEpisodeNumbers: Map<Int, Set<Int>> = emptyMap(),
    seriesStatus: String? = null,
): RatingPromptReason? {
    if (contentType.trim().lowercase() in setOf("movie", "film")) return RatingPromptReason.MOVIE
    if (seasonNumber == null || episodeNumber == null) return null
    if (seasonNumber <= 0) return null

    val episodesInSeason = seasonEpisodeNumbers[seasonNumber].orEmpty()
    val lastOfSeason = episodesInSeason.maxOrNull() ?: return null
    if (episodeNumber != lastOfSeason) return null

    val lastSeason = seasonEpisodeNumbers.keys.filter { it > 0 }.maxOrNull()
    val isFinalSeason = lastSeason != null && seasonNumber >= lastSeason
    val hasEnded = seriesStatus?.trim()?.lowercase() in ENDED_SERIES_STATUSES

    return if (isFinalSeason && hasEnded) {
        RatingPromptReason.SERIES_FINALE
    } else {
        RatingPromptReason.SEASON_FINALE
    }
}
