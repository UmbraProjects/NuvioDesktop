package com.nuvio.app.features.watching.application

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.episodePlaybackIds
import com.nuvio.app.features.watched.releasedMainSeasonEpisodes
import com.nuvio.app.features.tracking.RatingPromptReason
import com.nuvio.app.features.tracking.RatingPromptRepository
import com.nuvio.app.features.tracking.RatingPromptRequest
import com.nuvio.app.features.tracking.ratingPromptReasonFor
import com.nuvio.app.features.watched.seasonEpisodeNumbers
import com.nuvio.app.features.watched.toEpisodeWatchedItem
import com.nuvio.app.features.watched.toSeriesWatchedItem
import com.nuvio.app.features.watched.toWatchedItem
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.simkl.SimklRewatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WatchingActions {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun togglePosterWatched(preview: MetaPreview) {
        if (!preview.type.isSeriesLikeType()) {
            WatchedRepository.toggleWatched(preview.toWatchedItem(markedAtEpochMs = 0L))
            return
        }

        val isCurrentlyWatched = WatchedRepository.isWatched(
            id = preview.id,
            type = preview.type,
        )
        val meta = MetaDetailsRepository.fetch(type = preview.type, id = preview.id)
        if (meta == null) {
            if (isCurrentlyWatched) {
                WatchedRepository.unmarkWatched(preview.toWatchedItem(markedAtEpochMs = 0L))
            }
            return
        }

        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val releasedMainEpisodes = meta.releasedMainSeasonEpisodes(todayIsoDate)
        if (releasedMainEpisodes.isEmpty()) {
            if (isCurrentlyWatched) {
                WatchedRepository.unmarkWatched(meta.toSeriesWatchedItem())
            }
            return
        }
        val seriesItems = buildList {
            add(meta.toSeriesWatchedItem())
            addAll(releasedMainEpisodes.map(meta::toEpisodeWatchedItem))
        }

        if (isCurrentlyWatched) {
            WatchedRepository.unmarkWatched(seriesItems)
            WatchProgressRepository.clearProgress(
                releasedMainEpisodes.flatMap(meta::episodePlaybackIds),
            )
        } else {
            WatchedRepository.markWatched(seriesItems)
            WatchProgressRepository.clearProgress(
                releasedMainEpisodes.flatMap(meta::episodePlaybackIds),
            )
        }
    }

    fun toggleEpisodeWatched(
        meta: MetaDetails,
        episode: MetaVideo,
        isCurrentlyWatched: Boolean,
    ) {
        val watchedItem = meta.toEpisodeWatchedItem(episode)
        if (isCurrentlyWatched) {
            WatchedRepository.unmarkWatched(watchedItem)
            WatchProgressRepository.clearProgress(meta.episodePlaybackIds(episode))
        } else {
            WatchedRepository.markWatched(watchedItem)
            WatchProgressRepository.clearProgress(meta.episodePlaybackIds(episode))
        }
        reconcileSeriesWatchedState(meta)
    }

    fun togglePreviousEpisodesWatched(
        meta: MetaDetails,
        episodes: Collection<MetaVideo>,
        areCurrentlyWatched: Boolean,
    ) {
        toggleEpisodesWatched(
            meta = meta,
            episodes = episodes,
            areCurrentlyWatched = areCurrentlyWatched,
        )
    }

    fun toggleSeasonWatched(
        meta: MetaDetails,
        episodes: Collection<MetaVideo>,
        areCurrentlyWatched: Boolean,
    ) {
        toggleEpisodesWatched(
            meta = meta,
            episodes = episodes,
            areCurrentlyWatched = areCurrentlyWatched,
        )
    }

    fun reconcileSeriesWatchedState(
        meta: MetaDetails,
        todayIsoDate: String = CurrentDateProvider.todayIsoDate(),
    ) {
        if (!meta.type.isSeriesLikeType()) return

        WatchedRepository.reconcileSeriesWatchedState(
            meta = meta,
            todayIsoDate = todayIsoDate,
            isEpisodeCompleted = { episode ->
                meta.episodePlaybackIds(episode).any { videoId ->
                    WatchProgressRepository.progressForVideo(videoId)?.isCompleted == true
                }
            },
        )
    }

    fun onProgressEntryUpdated(entry: WatchProgressEntry, syncRemote: Boolean = true) {
        if (!entry.isCompleted) return

        // A playback session that completed before its metadata resolved carries a blank title, and
        // the watched row it writes is never revisited. Fall back to any name already known for the
        // same title — a sibling episode's progress entry, or an existing watched row — so the row
        // is not stored permanently nameless.
        val resolvedName = entry.title.ifBlank {
            WatchProgressRepository.knownTitleForParent(entry.parentMetaId)
                ?: WatchedRepository.knownTitleFor(entry.parentMetaId, entry.parentMetaType)
                ?: ""
        }
        val watchedItem = WatchedItem(
            id = entry.parentMetaId,
            type = entry.parentMetaType,
            name = resolvedName,
            poster = entry.poster,
            season = entry.seasonNumber,
            episode = entry.episodeNumber,
            markedAtEpochMs = entry.lastUpdatedEpochMs,
        )
        WatchedRepository.markWatchedFromPlaybackCompletion(watchedItem, syncRemote = syncRemote)

        // `syncRemote` is what separates playback the user just finished here from progress
        // reconstructed out of a remote snapshot. Only the former should ask for a rating.
        if (!syncRemote) return
        SimklRewatchRepository.onPlaybackCompleted(entry)
        if (!entry.isEpisode) {
            offerRatingPrompt(
                contentId = entry.parentMetaId,
                contentType = entry.parentMetaType,
                title = entry.title,
                reason = RatingPromptReason.MOVIE,
            )
            return
        }
        actionScope.launch {
            val meta = runCatching {
                MetaDetailsRepository.fetch(
                    type = entry.parentMetaType,
                    id = entry.parentMetaId,
                )
            }.getOrNull() ?: return@launch

            reconcileSeriesWatchedState(meta = meta)

            val reason = ratingPromptReasonFor(
                contentType = meta.type,
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,
                seasonEpisodeNumbers = meta.seasonEpisodeNumbers(),
                seriesStatus = meta.status,
            ) ?: return@launch
            offerRatingPrompt(
                contentId = meta.id,
                contentType = meta.type,
                title = meta.name,
                reason = reason,
                seasonNumber = entry.seasonNumber,
                releaseInfo = meta.releaseInfo,
            )
        }
    }

    private fun offerRatingPrompt(
        contentId: String,
        contentType: String,
        title: String,
        reason: RatingPromptReason,
        seasonNumber: Int? = null,
        releaseInfo: String? = null,
    ) {
        if (title.isBlank()) return
        RatingPromptRepository.offer(
            RatingPromptRequest(
                contentId = contentId,
                contentType = contentType,
                title = title,
                reason = reason,
                seasonNumber = seasonNumber,
                releaseInfo = releaseInfo,
            ),
        )
    }

    private fun toggleEpisodesWatched(
        meta: MetaDetails,
        episodes: Collection<MetaVideo>,
        areCurrentlyWatched: Boolean,
    ) {
        if (episodes.isEmpty()) return
        val watchedItems = episodes.map(meta::toEpisodeWatchedItem)
        if (areCurrentlyWatched) {
            WatchedRepository.unmarkWatched(watchedItems)
            WatchProgressRepository.clearProgress(episodes.flatMap(meta::episodePlaybackIds))
        } else {
            WatchedRepository.markWatched(watchedItems)
            WatchProgressRepository.clearProgress(episodes.flatMap(meta::episodePlaybackIds))
        }
        reconcileSeriesWatchedState(meta)
    }
}

private fun String.isSeriesLikeType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow")
