package com.nuvio.app.features.preroll

/** One episode as the recap sees it: where it sits, what the addon says about it, and when it was watched. */
internal data class PreviouslyOnEpisode(
    val season: Int,
    val episode: Int,
    val title: String?,
    val overview: String?,
    /** Null when the episode is not marked watched. */
    val watchedAtMs: Long?,
)

/** The episode a "previously on" is about, with what the prompt needs to know about it. */
internal data class PreviouslyOnTarget(
    val season: Int,
    val episode: Int,
    val title: String?,
    val overview: String?,
    /** The overview of the episode before the recapped one, for context. */
    val earlierOverview: String?,
    val watchedAtMs: Long?,
    /** The recapped episode is from an earlier season than the one about to start. */
    val previousSeason: Boolean,
    /** Episodes between the recapped one and the one about to start were not watched. */
    val skippedEpisodes: Boolean,
) {
    val label: String get() = "S${season}E$episode"
}

/**
 * The latest watched episode before ([season], [episode]): normally the one just before it, the
 * last of the previous season for a premiere, and an older one when episodes were skipped. Season 0
 * (specials) is ignored on both sides. Null when nothing before it was watched, or for a special.
 */
internal fun previouslyOnTargetFor(episodes: List<PreviouslyOnEpisode>, season: Int, episode: Int): PreviouslyOnTarget? {
    if (season < 1 || episode < 1) return null
    val ordered = episodes
        .filter { it.season >= 1 && it.episode >= 1 }
        .distinctBy { it.season to it.episode }
        .sortedWith(compareBy({ it.season }, { it.episode }))
    val before = ordered.filter { it.season < season || (it.season == season && it.episode < episode) }
    val watched = before.lastOrNull { it.watchedAtMs != null } ?: return null
    val index = before.indexOf(watched)
    return PreviouslyOnTarget(
        season = watched.season,
        episode = watched.episode,
        title = watched.title?.trim()?.takeIf { it.isNotEmpty() },
        overview = watched.overview?.trim()?.takeIf { it.isNotEmpty() },
        earlierOverview = before.getOrNull(index - 1)?.overview?.trim()?.takeIf { it.isNotEmpty() },
        watchedAtMs = watched.watchedAtMs,
        previousSeason = watched.season < season,
        skippedEpisodes = watched !== before.last(),
    )
}

/**
 * Whether enough time has passed. With the break rule off the cards always show; with it on they
 * show once the recapped episode was watched [gapMs] ago. An unknown watch time counts as passed,
 * since not knowing is not a reason to hold the recap back.
 */
internal fun previouslyOnGapPassed(watchedAtMs: Long?, nowMs: Long, breakOnly: Boolean, gapMs: Long): Boolean =
    !breakOnly || watchedAtMs == null || nowMs - watchedAtMs >= gapMs

/** The heading of the first card. */
internal fun PreviouslyOnTarget.lastTimeHeading(): String = when {
    previousSeason -> "LAST SEASON"
    skippedEpisodes -> "LAST WATCHED · $label"
    else -> "LAST TIME"
}
