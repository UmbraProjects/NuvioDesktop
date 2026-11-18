// Ported from NuvioTV PR 4679 (built-in Usenet, haaihond/NuvioTV usenet @ e0255815).
package com.nuvio.app.features.usenet

import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.tmdb.TmdbSeasonEpisodeCount

/** The content types the built-in Usenet search covers. */
internal val BUILT_IN_USENET_TYPES = setOf("movie", "series", "tv", "show", "anime")

/**
 * Season and episode from an episode video id (tt123:1:2, tmdb:10:1:2); nulls when it has none.
 * An anime entry's id (kitsu:123:5) numbers the episode within the entry and has no season;
 * a four-part one (kitsu:123:3:5) gives the show's season and episode.
 */
internal fun episodeNumbers(videoId: String): Pair<Int?, Int?> {
    val parts = videoId.split(':')
    if (videoId.isAnimeNativeId()) return when (parts.size) {
        3 -> null to parts[2].toIntOrNull()
        4 -> parts[2].toIntOrNull() to parts[3].toIntOrNull()
        else -> null to null
    }
    if (parts.size < if (videoId.startsWith("tmdb:") || videoId.startsWith("tvdb:")) 4 else 3) return null to null
    return parts[parts.lastIndex - 1].toIntOrNull() to parts.last().toIntOrNull()
}

/**
 * Whether the built-in search can look up [videoId]: a movie, or an episode that names its season
 * and episode. A series id alone is not searched, so it must not count as playable.
 */
internal fun builtInUsenetCanSearch(type: String, videoId: String): Boolean {
    val kind = type.lowercase()
    if (kind !in BUILT_IN_USENET_TYPES) return false
    if (kind == "movie") return true
    val (season, episode) = episodeNumbers(videoId)
    if (videoId.isAnimeNativeId()) return episode != null && episode >= 1
    return season != null && season >= 0 && episode != null && episode >= 1
}

/**
 * Whether an anime entry is the show's first: anime-list places it in season 1 with no offset,
 * or in no season at all (one entry for the whole show, as One Piece is).
 */
internal fun animeEntryStartsShow(tvdbSeason: Int?, tmdbSeason: Int?, tvdbOffset: Int?, tmdbOffset: Int?): Boolean {
    if (tvdbSeason == null && tmdbSeason == null) return true
    val (season, offset) = if (tvdbSeason != null) tvdbSeason to tvdbOffset else tmdbSeason to tmdbOffset
    return season == 1 && (offset ?: 0) == 0
}

/**
 * The episode counted from the show's start: the entry's own number where the entry starts the
 * show, else the [coordinates] placed in TMDB's season [counts].
 */
internal fun animeAbsoluteEpisode(
    native: Boolean,
    startsShow: Boolean,
    entryEpisode: Int?,
    coordinates: Pair<Int, Int>?,
    counts: List<TmdbSeasonEpisodeCount>?,
): Int? {
    if (native && startsShow && entryEpisode != null) return entryEpisode
    coordinates?.let { (season, episode) -> absoluteOf(season, episode, counts.orEmpty())?.let { return it } }
    return entryEpisode?.takeIf { startsShow }
}

/** [episode] of [season] counted from the show's start. TMDB numbers some shows on within a season (One Piece). */
internal fun absoluteOf(season: Int, episode: Int, counts: List<TmdbSeasonEpisodeCount>): Int? {
    if (season == 1) return episode
    val own = counts.firstOrNull { it.season == season } ?: return null
    if (episode > own.episodeCount) return episode
    return counts.filter { it.season < season }.sumOf { it.episodeCount } + episode
}

/** The season and episode of the show's [absolute] episode in TMDB's season [counts]. */
internal fun seasonOf(absolute: Int, counts: List<TmdbSeasonEpisodeCount>): Pair<Int, Int>? {
    var before = 0
    for (count in counts) {
        if (absolute <= before + count.episodeCount) return count.season to absolute - before
        before += count.episodeCount
    }
    return null
}
