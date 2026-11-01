package com.nuvio.app.features.details

/**
 * Whether the cast row shows how many episodes each person is in. On a competition show those counts
 * give away who lasts to the final, so they can be hidden there alone.
 */
enum class CastEpisodeCountVisibility {
    SHOW,
    HIDE_REALITY,
    HIDE;

    fun showCounts(genres: List<String>): Boolean = when (this) {
        SHOW -> true
        HIDE_REALITY -> !isRealityShow(genres)
        HIDE -> false
    }

    companion object {
        fun parse(value: String): CastEpisodeCountVisibility = entries.firstOrNull { it.name == value } ?: SHOW
    }
}

/** TMDB's "Reality" and IMDb's "Reality-TV" / "Game-Show", however the addon spells them. */
internal fun isRealityShow(genres: List<String>): Boolean = genres.any { genre ->
    val normalized = genre.lowercase().replace('-', ' ').trim()
    "reality" in normalized || normalized == "game show"
}
