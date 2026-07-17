package com.nuvio.app.features.details

import com.nuvio.app.features.watchprogress.buildPlaybackVideoId

/**
 * Coordinates used while an episode is playing.
 *
 * Several anime metadata providers omit `season` for their regular, absolute-numbered episode
 * list. In that representation a missing season means the entry's first season, not specials.
 * Keeping that rule here prevents the details screen, player UI, stream lookup and autoplay from
 * each inventing a slightly different interpretation.
 */
internal fun MetaVideo.playbackSeasonNumber(): Int? =
    effectiveSeasonNumber() ?: effectiveEpisodeNumber()?.let { 1 }

internal fun MetaVideo.playbackEpisodeNumber(): Int? = effectiveEpisodeNumber()

internal data class ResolvedSeriesEpisodePosition(
    val video: MetaVideo,
    val seasonNumber: Int,
    val episodeNumber: Int,
)

/**
 * Resolve a playback identity back to the metadata row that represents it.
 *
 * Resolution deliberately goes from strongest to weakest evidence:
 *  1. the metadata video's own id (or the canonical parent/season/episode id),
 *  2. normalized season/episode coordinates,
 *  3. absolute anime numbering across a multi-season metadata list,
 *  4. an episode-only match, but only when it is unambiguous.
 */
internal fun List<MetaVideo>.resolveSeriesEpisodePosition(
    parentMetaId: String? = null,
    videoId: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): ResolvedSeriesEpisodePosition? {
    val playable = mapNotNull { video ->
        val episode = video.playbackEpisodeNumber() ?: return@mapNotNull null
        val season = video.playbackSeasonNumber() ?: return@mapNotNull null
        ResolvedSeriesEpisodePosition(video, season, episode)
    }
    if (playable.isEmpty()) return null

    val normalizedVideoId = videoId?.trim()?.takeIf { it.isNotEmpty() }
    if (normalizedVideoId != null) {
        playable.firstOrNull { position ->
            position.video.id.equals(normalizedVideoId, ignoreCase = true)
        }?.let { return it }

        val normalizedParentId = parentMetaId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedParentId != null) {
            playable.firstOrNull { position ->
                buildPlaybackVideoId(
                    parentMetaId = normalizedParentId,
                    seasonNumber = position.seasonNumber,
                    episodeNumber = position.episodeNumber,
                    fallbackVideoId = position.video.id,
                ).equals(normalizedVideoId, ignoreCase = true)
            }?.let { return it }
        }
    }

    if (seasonNumber != null && episodeNumber != null) {
        playable.firstOrNull { position ->
            position.seasonNumber == seasonNumber && position.episodeNumber == episodeNumber
        }?.let { return it }
    }

    // Kitsu/MAL/SIMKL commonly persist an entry as S1 + absolute episode, while TMDB/TVDB
    // metadata splits the same run into multiple seasons. Map the absolute number by global
    // episode order, explicitly excluding specials.
    if ((seasonNumber == null || seasonNumber == 1) && episodeNumber != null && episodeNumber > 0) {
        val mainEpisodes = playable
            .filter { position -> position.seasonNumber > SPECIALS_SEASON_NUMBER }
            .sortedWith(seriesEpisodePositionComparator)
        val distinctSeasons = mainEpisodes.mapTo(mutableSetOf()) { it.seasonNumber }
        if (distinctSeasons.size > 1) {
            mainEpisodes.getOrNull(episodeNumber - 1)?.let { return it }
        }
    }

    if (episodeNumber != null) {
        playable.filter { position -> position.episodeNumber == episodeNumber }
            .singleOrNull()
            ?.let { return it }
    }
    return null
}

internal fun List<MetaVideo>.sortedPlaybackEpisodePositions(): List<ResolvedSeriesEpisodePosition> =
    mapNotNull { video ->
        val episode = video.playbackEpisodeNumber() ?: return@mapNotNull null
        val season = video.playbackSeasonNumber() ?: return@mapNotNull null
        ResolvedSeriesEpisodePosition(video, season, episode)
    }.sortedWith(seriesEpisodePositionComparator)

private val seriesEpisodePositionComparator =
    compareBy<ResolvedSeriesEpisodePosition>(
        { seasonSortKey(it.seasonNumber) },
        { it.episodeNumber },
        { it.video.released.orEmpty() },
        { it.video.title },
    )
