package com.nuvio.app.features.details

/**
 * Where the Same Cast cards say who from the cast is in them ("With Hugh Laurie").
 *
 * Poster-service art usually has its own badges in the corners, which a label on the art competes
 * with; plain TMDB backdrops have room for it.
 */
enum class SameCastLabelPlacement {
    /** Below the card on poster-service art, on the card otherwise. */
    AUTO,
    ON_POSTER,
    BELOW_POSTER;

    fun isBelow(artFromPosterService: Boolean): Boolean = when (this) {
        AUTO -> artFromPosterService
        ON_POSTER -> false
        BELOW_POSTER -> true
    }

    companion object {
        fun parse(value: String): SameCastLabelPlacement = entries.firstOrNull { it.name == value } ?: AUTO
    }
}
