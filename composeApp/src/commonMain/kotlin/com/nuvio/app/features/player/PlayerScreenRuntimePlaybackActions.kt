package com.nuvio.app.features.player

import com.nuvio.app.features.simkl.SimklScrobbleRepository
import com.nuvio.app.features.simkl.WatchProgressSourceSimkl
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.trakt.TraktScrobbleRepository
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val PlayerScreenRuntime.activePlaybackIdentity: String
    get() = activeTorrentInfoHash
        ?.let { hash -> "torrent:$hash:${activeTorrentFileIdx ?: -1}" }
        ?: activeSourceUrl

internal val PlayerScreenRuntime.playbackSession: WatchProgressPlaybackSession
    get() = WatchProgressPlaybackSession(
        contentType = contentType ?: parentMetaType,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: buildPlaybackVideoId(
            parentMetaId = parentMetaId,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            fallbackVideoId = activeVideoId,
        ),
        title = title,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
        providerName = activeProviderName,
        providerAddonId = activeProviderAddonId,
        lastStreamTitle = activeStreamTitle,
        lastStreamSubtitle = activeStreamSubtitle,
        pauseDescription = pauseDescription,
        lastSourceUrl = activeSourceUrl,
    )

internal fun PlayerScreenRuntime.resetIdentityStateIfNeeded() {
    val identity = activePlaybackIdentity
    if (lastResetPlaybackIdentity != identity) {
        lastResetPlaybackIdentity = identity
        shouldPlay = true
        initialLoadCompleted = false
        defaultPlaybackSpeedApplied = false
        speedBoostRestoreSpeed = null
        isHoldToSpeedGestureActive = false
        initialSeekApplied = activeInitialPositionMs <= 0L &&
            (activeInitialProgressFraction == null || activeInitialProgressFraction!! <= 0f)
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        pendingScrobbleStartAfterSeek = false
        autoFetchedAddonSubtitlesForKey = null
        trackPreferenceRestoreApplied = false
        preferredAudioSelectionApplied = false
        preferredSubtitleSelectionApplied = false
    }

    val videoIdentity = "$identity:$activeVideoId:$activeSeasonNumber:$activeEpisodeNumber"
    if (lastResetVideoIdentity != videoIdentity) {
        lastResetVideoIdentity = videoIdentity
        hasRequestedScrobbleStartForCurrentItem = false
        scrobbleStartRequestGeneration = 0L
        pendingScrobbleStartAfterSeek = false
        hasSentCompletionScrobbleForCurrentItem = false
        currentTraktScrobbleItem = null
    }
}

internal fun PlayerScreenRuntime.currentPlaybackProgressPercent(
    snapshot: PlayerPlaybackSnapshot = playbackSnapshot,
): Float {
    val duration = snapshot.durationMs.takeIf { it > 0L } ?: return 0f
    return ((snapshot.positionMs.toFloat() / duration.toFloat()) * 100f)
        .coerceIn(0f, 100f)
}

/**
 * Progress percent adjusted for playback speed, for use when reporting to scrobble services.
 *
 * Faster playback (>1x) scales progress up: at 2x speed you reach the 80% completion threshold
 * after watching only 40% of the video's runtime, matching the real-time investment of an 80% 1x
 * watch. Slower playback (≤1x) is not penalised — raw position is used as-is so a 0.75x viewer
 * at 90% position still reports 90%, not a lesser value.
 */
internal fun PlayerScreenRuntime.currentScrobbleProgressPercent(
    snapshot: PlayerPlaybackSnapshot = playbackSnapshot,
): Float {
    val raw = currentPlaybackProgressPercent(snapshot)
    val speed = snapshot.playbackSpeed.coerceAtLeast(1f)
    return (raw * speed).coerceIn(0f, 100f)
}

private fun PlayerScreenRuntime.currentScrobbleStartProgressPercent(
    snapshot: PlayerPlaybackSnapshot = playbackSnapshot,
): Float {
    val current = currentPlaybackProgressPercent(snapshot)
    val resumeFraction = activeInitialProgressFraction
        ?.takeIf { it > 0f }
        ?.coerceIn(0f, 1f)
    val resumePercent = when {
        activeInitialPositionMs > 0L && snapshot.durationMs > 0L ->
            ((activeInitialPositionMs.toFloat() / snapshot.durationMs.toFloat()) * 100f)
                .coerceIn(0f, 100f)
        resumeFraction != null -> (resumeFraction * 100f).coerceIn(0f, 100f)
        else -> null
    }

    val isBeforeRequestedResume = if (activeInitialPositionMs > 0L) {
        snapshot.positionMs < (activeInitialPositionMs - 1_000L).coerceAtLeast(0L)
    } else {
        resumeFraction != null
    }

    return if (resumePercent != null && resumePercent > current && isBeforeRequestedResume) {
        resumePercent
    } else {
        current
    }
}

internal data class TraktScrobbleItemInputs(
    val contentType: String,
    val parentMetaId: String,
    val videoId: String?,
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val watchProgressSource: String?,
)

internal data class SimklScrobbleItemInputs(
    val contentType: String,
    val parentMetaId: String,
    val videoId: String?,
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val isAnime: Boolean,
)

internal fun PlayerScreenRuntime.snapshotTraktScrobbleItemInputs() = TraktScrobbleItemInputs(
    contentType = contentType ?: parentMetaType,
    parentMetaId = parentMetaId,
    videoId = activeVideoId,
    title = title,
    seasonNumber = activeSeasonNumber,
    episodeNumber = activeEpisodeNumber,
    episodeTitle = activeEpisodeTitle,
    watchProgressSource = activeWatchProgressSource,
)

internal fun PlayerScreenRuntime.snapshotSimklScrobbleItemInputs() = SimklScrobbleItemInputs(
    contentType = contentType ?: parentMetaType,
    parentMetaId = parentMetaId,
    videoId = activeVideoId,
    title = title,
    seasonNumber = activeSeasonNumber,
    episodeNumber = activeEpisodeNumber,
    isAnime = AnimeContentCache.isAnime(parentMetaId) ||
        (activeWatchProgressSource == WatchProgressSourceSimkl && parentMetaId.startsWith("simkl:", ignoreCase = true)),
)

private suspend fun TraktScrobbleItemInputs.buildItem() =
    if (watchProgressSource == WatchProgressSourceSimkl) {
        null
    } else {
    TraktScrobbleRepository.buildItem(
        contentType = contentType,
        parentMetaId = parentMetaId,
        videoId = videoId,
        title = title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
    )
    }

private suspend fun SimklScrobbleItemInputs.buildItem() =
    SimklScrobbleRepository.buildItem(
        contentType = contentType,
        parentMetaId = parentMetaId,
        videoId = videoId,
        title = title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        isAnime = isAnime,
    )

internal suspend fun PlayerScreenRuntime.currentTraktScrobbleItem() =
    snapshotTraktScrobbleItemInputs().buildItem()

internal fun PlayerScreenRuntime.emitTraktScrobbleStart() {
    if (disableProgressTracking) return
    if (hasRequestedScrobbleStartForCurrentItem) return
    hasRequestedScrobbleStartForCurrentItem = true
    val requestGeneration = scrobbleStartRequestGeneration + 1L
    scrobbleStartRequestGeneration = requestGeneration

    scope.launch {
        val item = currentTraktScrobbleItem()
        val simklItem = snapshotSimklScrobbleItemInputs().buildItem()
        if (item == null && simklItem == null) {
            hasRequestedScrobbleStartForCurrentItem = false
            return@launch
        }
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) {
            // Reset so a subsequent emitTraktScrobbleStart call is not permanently blocked.
            hasRequestedScrobbleStartForCurrentItem = false
            return@launch
        }
        currentTraktScrobbleItem = item
        // Report actual video position, not speed-adjusted — services use this for resume.
        val progress = currentScrobbleStartProgressPercent()
        if (item != null) {
            TraktScrobbleRepository.scrobbleStart(item = item, progressPercent = progress)
        }
        if (simklItem != null) {
            SimklScrobbleRepository.scrobbleStart(item = simklItem, progressPercent = progress)
        }
    }
}

internal fun PlayerScreenRuntime.emitTraktScrobbleStop(progressPercent: Float? = null) {
    if (disableProgressTracking) return
    val provided = progressPercent
    if (!hasRequestedScrobbleStartForCurrentItem && (provided ?: 0f) < 80f) return

    // Report raw position for accurate resume; speed only affects the completion threshold.
    val percent = provided ?: currentPlaybackProgressPercent()
    val itemSnapshot = currentTraktScrobbleItem
    val inputsSnapshot = snapshotTraktScrobbleItemInputs()
    val simklInputsSnapshot = snapshotSimklScrobbleItemInputs()
    scope.launch(NonCancellable) {
        val item = itemSnapshot ?: inputsSnapshot.buildItem()
        if (item != null) {
            TraktScrobbleRepository.scrobbleStop(item = item, progressPercent = percent)
        }
        simklInputsSnapshot.buildItem()?.let { simklItem ->
            SimklScrobbleRepository.scrobbleStop(item = simklItem, progressPercent = percent)
        }
    }
    currentTraktScrobbleItem = null
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration += 1L
}

internal fun PlayerScreenRuntime.emitStopScrobbleForCurrentProgress() {
    // Speed-adjusted percent: used only to decide whether the 80% completion threshold is met.
    // Raw percent: what gets sent to scrobble services so resume starts at the right position.
    val effectivePercent = currentScrobbleProgressPercent()
    val rawPercent = currentPlaybackProgressPercent()

    if (effectivePercent >= 0.1f && effectivePercent < 80f && hasRequestedScrobbleStartForCurrentItem) {
        emitTraktScrobbleStop(rawPercent)
        return
    }

    if (effectivePercent >= 80f) {
        val isAtEnd = effectivePercent >= 99f
        if (!hasSentCompletionScrobbleForCurrentItem || isAtEnd) {
            hasSentCompletionScrobbleForCurrentItem = true
            emitTraktScrobbleStop(rawPercent)
        }
    }
}

internal fun PlayerScreenRuntime.tryShowParentalGuide() {
    if (!parentalGuideHasShown && parentalWarnings.isNotEmpty() && !playbackStartedForParentalGuide) {
        playbackStartedForParentalGuide = true
        controlsVisible = true
        showParentalGuide = true
        parentalGuideHasShown = true
    }
}

internal suspend fun PlayerScreenRuntime.resolveParentalGuideImdbId(): String? {
    val candidates = listOf(parentMetaId, activeVideoId)
    candidates.firstNotNullOfOrNull(::extractParentalGuideImdbId)?.let { return it }
    val tmdbId = candidates.firstNotNullOfOrNull(::extractParentalGuideTmdbId) ?: return null
    return TmdbService.tmdbToImdb(
        tmdbId = tmdbId,
        mediaType = contentType ?: parentMetaType,
    )
}

internal fun PlayerScreenRuntime.flushWatchProgress() {
    if (disableProgressTracking) return
    emitStopScrobbleForCurrentProgress()
    WatchProgressRepository.flushPlaybackProgress(
        session = playbackSession,
        snapshot = playbackSnapshot,
    )
}

internal fun PlayerScreenRuntime.scheduleProgressSyncAfterSeek() {
    if (disableProgressTracking) return
    val shouldRestartScrobbleAfterSeek = shouldPlay || playbackSnapshot.isPlaying
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(PlayerSeekProgressSyncDebounceMs)
        WatchProgressRepository.upsertPlaybackProgress(
            session = playbackSession,
            snapshot = playbackSnapshot,
        )

        val progressPercent = currentPlaybackProgressPercent()
        if (progressPercent >= 1f && progressPercent < 80f) {
            emitTraktScrobbleStop(progressPercent)
            val shouldRestartScrobbleNow = shouldRestartScrobbleAfterSeek && shouldPlay
            if (shouldRestartScrobbleNow && playbackSnapshot.isPlaying) {
                pendingScrobbleStartAfterSeek = false
                emitTraktScrobbleStart()
            } else if (shouldRestartScrobbleNow) {
                pendingScrobbleStartAfterSeek = true
            }
        }
    }
}

internal fun PlayerScreenRuntime.persistPlaybackProgressTick() {
    if (disableProgressTracking) return
    val now = WatchProgressClock.nowEpochMs()
    if (now - lastProgressPersistEpochMs < PlaybackProgressPersistIntervalMs) return
    lastProgressPersistEpochMs = now
    WatchProgressRepository.upsertPlaybackProgress(
        session = playbackSession,
        snapshot = playbackSnapshot,
        syncRemote = false,
    )
}
