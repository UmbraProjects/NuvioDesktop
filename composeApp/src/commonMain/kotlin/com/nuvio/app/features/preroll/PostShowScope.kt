package com.nuvio.app.features.preroll

import com.nuvio.app.features.details.isEndedSeriesStatus

/**
 * What the post-show is looking back at. It decides the prompt, the cache entry and the spoiler
 * boundary: a season finale's cards may use nothing from later seasons.
 */
sealed interface PostShowScope {
    /** Stable text for the cache key. */
    val cacheKey: String

    /** A film. */
    data object Film : PostShowScope {
        override val cacheKey: String get() = "film"
    }

    /** The last episode of [season]. The viewer has seen nothing beyond it. */
    data class SeasonFinale(val season: Int, val episode: Int, val episodeTitle: String?) : PostShowScope {
        override val cacheKey: String get() = "s$season"
    }

    /** The last episode of the last season of a show that has ended: the whole story may be discussed. */
    data class SeriesFinale(val season: Int, val episode: Int, val episodeTitle: String?) : PostShowScope {
        override val cacheKey: String get() = "series-s$season"
    }
}

/** One regular episode of a show, with whether it is marked watched. */
data class PostShowEpisode(val season: Int, val episode: Int, val watched: Boolean)

/**
 * Whether the episode now playing earns a post-show, and of what kind. Null means no cards.
 *
 * - **Finale:** the episode must be the highest-numbered one of its season in [episodes]. It is a
 *   series finale when that is also the last season listed and [status] says the show has ended
 *   or been canceled; otherwise a season finale. Season 0 (specials) never qualifies and never
 *   counts toward anything.
 * - **Spoiler gate, per season:** every earlier episode of the *same season* must be marked
 *   watched. A season finale is only shown to someone who has watched their way to it; episodes
 *   of earlier seasons are not required (people skip seasons, and imported history has gaps), and
 *   the prompt keeps the cards inside the season in any case.
 */
fun postShowScopeFor(
    status: String?,
    episodes: List<PostShowEpisode>,
    currentSeason: Int,
    currentEpisode: Int,
    episodeTitle: String?,
): PostShowScope? {
    if (currentSeason < 1) return null
    val regular = episodes.filter { it.season >= 1 }
    val thisSeason = regular.filter { it.season == currentSeason }
    if (thisSeason.none { it.episode == currentEpisode }) return null
    if (thisSeason.maxOf { it.episode } != currentEpisode) return null
    if (thisSeason.any { it.episode < currentEpisode && !it.watched }) return null
    val title = episodeTitle?.trim()?.takeIf { it.isNotEmpty() }
    val isLastSeason = regular.maxOf { it.season } == currentSeason
    return if (isLastSeason && isEndedSeriesStatus(status)) {
        PostShowScope.SeriesFinale(currentSeason, currentEpisode, title)
    } else {
        PostShowScope.SeasonFinale(currentSeason, currentEpisode, title)
    }
}
