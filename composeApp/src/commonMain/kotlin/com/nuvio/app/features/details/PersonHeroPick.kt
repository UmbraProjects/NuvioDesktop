package com.nuvio.app.features.details

/**
 * TMDB has no wide images of people (`person/{id}/images` only returns portrait headshots), so the
 * person page's hero borrows the backdrop of the title they are best known for. This is the rule
 * that chooses it, kept apart from the fetch so it can be tested.
 */
data class PersonHeroCandidate(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val backdropPath: String?,
    val voteCount: Int,
    /** Cast billing order (0 = top billed); null for crew credits or when TMDB gives none. */
    val billing: Int?,
    val episodeCount: Int?,
    val genreIds: List<Int>,
    /** True for a crew credit in the person's own line of work (a director's "Director" credits). */
    val ownWork: Boolean = false,
)

/** Talk, news and reality: guest spots there are not roles, and they swamp an actor's rows. */
internal val PERSON_GUEST_SPOT_GENRES = setOf(10767, 10763, 10764)

internal fun isGuestSpotGenre(genreIds: List<Int>): Boolean = genreIds.any { it in PERSON_GUEST_SPOT_GENRES }

/**
 * Best-known title with a backdrop. Ranked by vote count (a lifetime measure, unlike TMDB's
 * popularity, which follows whatever is trending this week), keeping only real roles: top-three
 * billing in a film, a series they were in for at least a few episodes, or their own crew work.
 * Falls back to any credit with a backdrop when nothing passes.
 */
fun pickPersonHeroCandidate(candidates: List<PersonHeroCandidate>): PersonHeroCandidate? {
    val withBackdrop = candidates.filter { !it.backdropPath.isNullOrBlank() && !isGuestSpotGenre(it.genreIds) }
    val leading = withBackdrop.filter { candidate ->
        when {
            candidate.ownWork -> true
            candidate.mediaType == "movie" -> (candidate.billing ?: Int.MAX_VALUE) <= 2
            candidate.mediaType == "tv" -> (candidate.episodeCount ?: 0) >= 3
            else -> false
        }
    }
    return (leading.ifEmpty { withBackdrop }).maxByOrNull { it.voteCount }
}
