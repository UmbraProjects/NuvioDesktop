package com.nuvio.app.features.watching.application

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.effectiveEpisodeNumber
import com.nuvio.app.features.details.effectiveSeasonNumber
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
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watching.domain.isReleasedBy
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.simkl.SimklRewatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watched_bulk_marked
import nuvio.composeapp.generated.resources.watched_bulk_unmarked
import org.jetbrains.compose.resources.getString

object WatchingActions {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("WatchingActions")

    /**
     * Whole-title watched toggle. For a series this marks or unmarks every released main-season
     * episode at once, which is why every call is logged with the UI surface it came from: a stray
     * click here is otherwise indistinguishable, in a user's log, from episodes appearing on its own.
     */
    suspend fun togglePosterWatched(preview: MetaPreview, origin: String = "unspecified") {
        if (!preview.type.isSeriesLikeType()) {
            log.i { "Toggle watched ($origin): ${preview.type}:${preview.id} (${preview.name})" }
            WatchedRepository.toggleWatched(preview.toWatchedItem(markedAtEpochMs = 0L))
            return
        }

        val isCurrentlyWatched = WatchedRepository.isWatched(
            id = preview.id,
            type = preview.type,
        )
        val meta = MetaDetailsRepository.fetch(type = preview.type, id = preview.id)
        if (meta == null) {
            log.i {
                "Toggle series watched ($origin): ${preview.type}:${preview.id} (${preview.name}) " +
                    "has no metadata; wasWatched=$isCurrentlyWatched"
            }
            if (isCurrentlyWatched) {
                WatchedRepository.unmarkWatched(preview.toWatchedItem(markedAtEpochMs = 0L))
            }
            return
        }

        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val releasedMainEpisodes = meta.releasedMainSeasonEpisodes(todayIsoDate)
        if (releasedMainEpisodes.isEmpty()) {
            log.i {
                "Toggle series watched ($origin): ${meta.type}:${meta.id} (${meta.name}) " +
                    "has no released episodes; wasWatched=$isCurrentlyWatched"
            }
            if (isCurrentlyWatched) {
                WatchedRepository.unmarkWatched(meta.toSeriesWatchedItem())
            }
            return
        }
        val seriesItems = buildList {
            add(meta.toSeriesWatchedItem())
            addAll(releasedMainEpisodes.map(meta::toEpisodeWatchedItem))
        }

        logEpisodeAction("whole series ($origin)", meta, releasedMainEpisodes, unmark = isCurrentlyWatched)
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
        showBulkToast(meta, releasedMainEpisodes.size, unmark = isCurrentlyWatched)
    }

    fun toggleEpisodeWatched(
        meta: MetaDetails,
        episode: MetaVideo,
        isCurrentlyWatched: Boolean,
    ) {
        val watchedItem = meta.toEpisodeWatchedItem(episode)
        logEpisodeAction("episode", meta, listOf(episode), unmark = isCurrentlyWatched)
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
            action = "previous episodes",
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
            action = "season",
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

    /** Continue Watching's "Mark as watched": the card's episode or film, as the details screen marks it. Ported from upstream. */
    fun markContinueWatchingWatched(item: ContinueWatchingItem) {
        if (!item.canMarkWatched()) return
        WatchedRepository.markWatched(item.toWatchedItem())
        WatchProgressRepository.clearProgress(item.videoId)
        if (!item.parentMetaType.isSeriesLikeType()) return
        actionScope.launch {
            MetaDetailsRepository.fetch(type = item.parentMetaType, id = item.parentMetaId)
                ?.let { meta -> reconcileSeriesWatchedState(meta) }
        }
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
        action: String,
        meta: MetaDetails,
        episodes: Collection<MetaVideo>,
        areCurrentlyWatched: Boolean,
    ) {
        if (episodes.isEmpty()) return
        logEpisodeAction(action, meta, episodes, unmark = areCurrentlyWatched)
        val watchedItems = episodes.map(meta::toEpisodeWatchedItem)
        if (areCurrentlyWatched) {
            WatchedRepository.unmarkWatched(watchedItems)
            WatchProgressRepository.clearProgress(episodes.flatMap(meta::episodePlaybackIds))
        } else {
            WatchedRepository.markWatched(watchedItems)
            WatchProgressRepository.clearProgress(episodes.flatMap(meta::episodePlaybackIds))
        }
        reconcileSeriesWatchedState(meta)
        actionScope.launch { showBulkToast(meta, episodes.size, unmark = areCurrentlyWatched) }
    }

    /**
     * One line per manual watched action, naming the title and every episode it touched. These
     * actions push straight to the connected tracker's history, so without this a user's log cannot
     * tell "I clicked Mark Season" apart from episodes the app marked by itself.
     */
    private fun logEpisodeAction(
        action: String,
        meta: MetaDetails,
        episodes: Collection<MetaVideo>,
        unmark: Boolean,
    ) {
        log.i {
            val coordinates = formatEpisodeCoordinates(
                episodes.map { it.effectiveSeasonNumber() to it.effectiveEpisodeNumber() },
            )
            "${if (unmark) "Unmark" else "Mark"} watched [$action]: ${meta.type}:${meta.id} (${meta.name}) " +
                "${episodes.size} episode(s) $coordinates"
        }
    }

    /**
     * Bulk marks change many rows on the connected tracker at once and are otherwise silent — only
     * a single-episode mark shows a toast, after the provider confirms it. Say how many, and of
     * what, so an accidental whole-season or whole-show click is noticed when it happens.
     */
    private suspend fun showBulkToast(meta: MetaDetails, episodeCount: Int, unmark: Boolean) {
        if (episodeCount <= 1) return
        val message = getString(
            if (unmark) Res.string.watched_bulk_unmarked else Res.string.watched_bulk_marked,
            episodeCount,
            meta.name,
        )
        NuvioToastController.show(message = message, durationMillis = 4_000L)
    }
}

/** `S1:E1-8,10 S2:E1-3`, with unknown coordinates shown as `?`. */
internal fun formatEpisodeCoordinates(coordinates: Collection<Pair<Int?, Int?>>): String =
    coordinates
        .groupBy({ (season, _) -> season }, { (_, episode) -> episode })
        .toList()
        .sortedBy { (season, _) -> season ?: Int.MIN_VALUE }
        .joinToString(" ") { (season, episodes) ->
            val known = episodes.filterNotNull().distinct().sorted()
            val ranges = mutableListOf<String>()
            var index = 0
            while (index < known.size) {
                var last = index
                while (last + 1 < known.size && known[last + 1] == known[last] + 1) last++
                ranges += if (last == index) "${known[index]}" else "${known[index]}-${known[last]}"
                index = last + 1
            }
            repeat(episodes.count { it == null }) { ranges += "?" }
            "S${season ?: "?"}:E${ranges.joinToString(",")}"
        }

/** Cloud files have no catalog entry to track, and unreleased episodes cannot be watched yet. */
internal fun ContinueWatchingItem.canMarkWatched(
    todayIsoDate: String = CurrentDateProvider.todayIsoDate(),
): Boolean =
    !parentMetaType.equals(CloudLibraryContentType, ignoreCase = true) &&
        (!isNextUp || isReleasedBy(todayIsoDate, released))

/** Same shape as the details screen builds, so tracker sync takes the same path. */
internal fun ContinueWatchingItem.toWatchedItem(markedAtEpochMs: Long = 0L): WatchedItem =
    WatchedItem(
        id = parentMetaId,
        type = parentMetaType,
        name = episodeTitle?.takeIf { it.isNotBlank() } ?: title,
        poster = episodeThumbnail ?: background ?: poster,
        season = seasonNumber,
        episode = episodeNumber,
        markedAtEpochMs = markedAtEpochMs,
    )

private fun String.isSeriesLikeType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow")
