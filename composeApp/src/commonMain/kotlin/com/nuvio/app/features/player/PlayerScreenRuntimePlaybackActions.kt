package com.nuvio.app.features.player

import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.WatchProgressSourceSimkl
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleDispatch
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressCompletionPercentThreshold
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_watched_provider_success
import org.jetbrains.compose.resources.getString

internal val PlayerScreenRuntime.activePlaybackIdentity: String
    get() = "$playbackAttemptId:" + (activeTorrentInfoHash
        ?.let { hash -> "torrent:$hash:${activeTorrentFileIdx ?: -1}" }
        ?: activeSourceUrl)

/**
 * Invalidates every callback owned by the outgoing player before a new source can be observed.
 *
 * Callers must flush the outgoing item's progress first, prepare the incoming resume metadata,
 * invoke this function, and only then publish the new source URL. Keeping the reset synchronous
 * closes the Compose-effect window in which a teardown snapshot could be attributed to the new
 * episode.
 */
internal fun PlayerScreenRuntime.beginPlaybackAttempt() {
    playbackAttemptId += 1L
    playerController = null
    playerControllerSourceUrl = null
    playerAttachedSourceUrl = null
    playerAttachedAttemptId = null
    playerStartedSourceUrl = null
    playerStartedAttemptId = null
    playbackSnapshot = PlayerPlaybackSnapshot()
    lastTrustedPlaybackPositionMs = 0L
    lastMeaningfulPlaybackSnapshot = null
    initialLoadCompleted = false
    defaultPlaybackSpeedApplied = false
    initialSeekApplied = activeInitialPositionMs <= 0L &&
        (activeInitialProgressFraction == null || activeInitialProgressFraction!! <= 0f)
    lastProgressPersistEpochMs = 0L
    previousIsPlaying = false
    pendingScrobbleStartAfterSeek = false
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration += 1L
    hasSentCompletionScrobbleForCurrentItem = false
    currentTrackingScrobbleMedia = null
    playbackEndExitRequested = false
    playbackFailureExitRequested = false
    playbackSourceFailureActive = false
    shouldPlay = true
}

internal fun isCurrentPlaybackAttempt(
    callbackAttemptId: Long,
    activeAttemptId: Long,
): Boolean = callbackAttemptId == activeAttemptId

internal fun shouldReleaseNextEpisodeAdvanceLatch(
    advanceInProgress: Boolean,
    targetVideoId: String?,
    activeVideoId: String?,
    activeAttemptId: Long,
    startedAttemptId: Long?,
    isEnded: Boolean,
    positionMs: Long,
    minimumPositionMs: Long,
): Boolean =
    advanceInProgress &&
        !targetVideoId.isNullOrBlank() &&
        activeVideoId == targetVideoId &&
        startedAttemptId == activeAttemptId &&
        !isEnded &&
        positionMs >= minimumPositionMs

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
        pauseDescription = activePauseDescription,
        lastSourceUrl = activeSourceUrl,
    )

internal fun PlayerScreenRuntime.resetIdentityStateIfNeeded() {
    val identity = activePlaybackIdentity
    if (lastResetPlaybackIdentity != identity) {
        lastResetPlaybackIdentity = identity
        shouldPlay = true
        initialLoadCompleted = false
        defaultPlaybackSpeedApplied = false
        initialSeekApplied = activeInitialPositionMs <= 0L &&
            (activeInitialProgressFraction == null || activeInitialProgressFraction!! <= 0f)
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        pendingScrobbleStartAfterSeek = false
    }

    val videoIdentity = "$identity:$activeVideoId:$activeSeasonNumber:$activeEpisodeNumber"
    if (lastResetVideoIdentity != videoIdentity) {
        lastResetVideoIdentity = videoIdentity
        // The fallback flush snapshot belongs to the previous video; carrying it across an episode
        // or source switch would flush the old position onto the new item.
        lastMeaningfulPlaybackSnapshot = null
        hasRequestedScrobbleStartForCurrentItem = false
        scrobbleStartRequestGeneration = 0L
        pendingScrobbleStartAfterSeek = false
        hasSentCompletionScrobbleForCurrentItem = false
        currentTrackingScrobbleMedia = null
        // Track ids and selected flags are file-local. Reset them for every new video as well as
        // every source replacement (the source identity is part of videoIdentity), otherwise an
        // episode switch that reuses the same player can display/apply the previous file's index.
        autoFetchedAddonSubtitlesForKey = null
        completedAutoAddonSubtitleFetchForKey = null
        trackPreferenceRestoreApplied = false
        preferredAudioSelectionApplied = false
        preferredSubtitleSelectionApplied = false
        pendingSubtitleSelectionIndex = null
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        selectedAudioIndex = -1
        selectedSubtitleIndex = -1
        selectedAddonSubtitleId = null
        useCustomSubtitles = false
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

/**
 * True once the live snapshot describes the item currently being scrobbled.
 *
 * Between a next-episode advance and the new player's first sample, `playbackSnapshot` still holds
 * the *outgoing* episode's position while the season/episode fields have already moved on. Anything
 * reading the snapshot in that window attributes the old episode's progress to the new one.
 */
private val PlayerScreenRuntime.snapshotBelongsToCurrentAttempt: Boolean
    get() = playerStartedAttemptId == playbackAttemptId

private fun PlayerScreenRuntime.currentScrobbleStartProgressPercent(
    snapshot: PlayerPlaybackSnapshot = playbackSnapshot,
): Float = scrobbleStartProgressPercent(
    currentPercent = currentPlaybackProgressPercent(snapshot),
    positionMs = snapshot.positionMs,
    durationMs = snapshot.durationMs,
    initialPositionMs = activeInitialPositionMs,
    initialProgressFraction = activeInitialProgressFraction,
    initialSeekApplied = initialSeekApplied,
    snapshotBelongsToCurrentAttempt = snapshotBelongsToCurrentAttempt,
)

/**
 * What a scrobble `start` should report as its position.
 *
 * Three cases, in order:
 *
 * 1. **The snapshot is not this item's.** Between a next-episode advance and the new player's first
 *    sample the snapshot still holds the outgoing episode's position while the episode fields have
 *    already advanced. Reporting it announced the *incoming* episode as nearly finished, which
 *    providers that read a high start as completion turned into "watched".
 * 2. **The resume seek has landed.** The live position is then the truth, including after a
 *    deliberate seek back to before the resume point.
 * 3. **The resume seek has not landed yet.** The player may still read 0 while a resume is pending,
 *    so report where playback is about to begin rather than the start of the file.
 */
internal fun scrobbleStartProgressPercent(
    currentPercent: Float,
    positionMs: Long,
    durationMs: Long,
    initialPositionMs: Long,
    initialProgressFraction: Float?,
    initialSeekApplied: Boolean,
    snapshotBelongsToCurrentAttempt: Boolean,
): Float {
    val resumeFraction = initialProgressFraction?.takeIf { it > 0f }?.coerceIn(0f, 1f)
    val resumePercent = when {
        initialPositionMs > 0L && durationMs > 0L ->
            ((initialPositionMs.toFloat() / durationMs.toFloat()) * 100f).coerceIn(0f, 100f)
        resumeFraction != null -> (resumeFraction * 100f).coerceIn(0f, 100f)
        else -> null
    }

    if (!snapshotBelongsToCurrentAttempt) return resumePercent ?: 0f
    if (initialSeekApplied) return currentPercent

    val isBeforeRequestedResume = if (initialPositionMs > 0L) {
        positionMs < (initialPositionMs - 1_000L).coerceAtLeast(0L)
    } else {
        resumeFraction != null
    }

    return if (resumePercent != null && resumePercent > currentPercent && isBeforeRequestedResume) {
        resumePercent
    } else {
        currentPercent
    }
}

/**
 * The media the player is currently scrobbling, in provider-neutral form.
 *
 * Each provider re-derives its own ids and episode numbering from this reference, so the anime
 * coordinate system stays a provider concern rather than something the player has to pick.
 */
internal fun PlayerScreenRuntime.snapshotTrackingScrobbleMedia(): TrackingMediaReference {
    val media = buildTrackingMediaReference(
        contentType = contentType ?: parentMetaType,
        parentMetaId = parentMetaId,
        videoId = activeVideoId,
        title = title,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
    )
    // The id-derived classification in buildTrackingMediaReference misses content that is only
    // known to be anime from runtime state, which is what SIMKL's anime scrobble body keys on.
    val isAnime = AnimeContentCache.isAnime(parentMetaId) ||
        (activeWatchProgressSource == WatchProgressSourceSimkl && parentMetaId.startsWith("simkl:", ignoreCase = true))
    return if (isAnime && media.kind != TrackingMediaKind.MOVIE) {
        media.copy(kind = TrackingMediaKind.ANIME)
    } else {
        media
    }
}

internal fun PlayerScreenRuntime.emitTrackingScrobbleStart(viaSeek: Boolean = false) {
    val request = prepareTrackingScrobbleStart(viaSeek) ?: return
    scope.launch { request() }
}

/**
 * Prepares a start, returning the suspending half so a caller already inside a coroutine can await
 * it instead of racing it.
 *
 * A seek sends a stop and then a start. Launched independently they arrive in whatever order the
 * network settles, and a provider whose `stop` ends the session then sees the stop land *after* the
 * restart and drops the session entirely. Returning the work rather than launching it lets the seek
 * path order the pair.
 */
private fun PlayerScreenRuntime.prepareTrackingScrobbleStart(
    viaSeek: Boolean,
): (suspend () -> Unit)? {
    if (progressTrackingDisabled) return null
    if (hasRequestedScrobbleStartForCurrentItem) return null
    hasRequestedScrobbleStartForCurrentItem = true
    val requestGeneration = scrobbleStartRequestGeneration + 1L
    scrobbleStartRequestGeneration = requestGeneration
    val media = snapshotTrackingScrobbleMedia()
    val profileId = ProfileRepository.activeProfileId

    return prepared@{
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) {
            // Reset so a subsequent start call is not permanently blocked.
            hasRequestedScrobbleStartForCurrentItem = false
            return@prepared
        }
        currentTrackingScrobbleMedia = media
        // A new session must be re-anchored on its own schedule, not the previous item's.
        TrackingScrobbleCoordinator.onScrobbleItemChanged()
        // Report actual video position, not speed-adjusted — services use this for resume.
        val dispatch = dispatchTrackingScrobble(
            profileId = profileId,
            action = TrackingScrobbleAction.START,
            media = media,
            progressPercent = currentScrobbleStartProgressPercent(),
            viaSeek = viaSeek,
            positionSeconds = playbackSnapshot.positionSecondsOrNull(),
            durationSeconds = playbackSnapshot.durationSecondsOrNull(),
        )
        if (dispatch.handledNothing) {
            // No connected provider could address this item. Release the latch so the next start
            // trigger for the same video retries — id enrichment can succeed on a later attempt.
            currentTrackingScrobbleMedia = null
            hasRequestedScrobbleStartForCurrentItem = false
        }
    }
}

internal fun PlayerScreenRuntime.emitTrackingScrobbleStop(
    progressPercent: Float? = null,
    viaSeek: Boolean = false,
    paused: Boolean = false,
) {
    val request = prepareTrackingScrobbleStop(progressPercent, viaSeek, paused) ?: return
    scope.launch(NonCancellable) { request() }
}

/** The stop counterpart of [prepareTrackingScrobbleStart]; see there for why this is split. */
private fun PlayerScreenRuntime.prepareTrackingScrobbleStop(
    progressPercent: Float?,
    viaSeek: Boolean,
    paused: Boolean = false,
): (suspend () -> Unit)? {
    if (progressTrackingDisabled) return null
    val provided = progressPercent
    if (!hasRequestedScrobbleStartForCurrentItem && (provided ?: 0f) < 80f) return null

    // Report raw position for accurate resume; speed only affects the completion threshold.
    val percent = provided ?: currentPlaybackProgressPercent()
    val media = currentTrackingScrobbleMedia ?: snapshotTrackingScrobbleMedia()
    val profileId = ProfileRepository.activeProfileId
    val positionSeconds = playbackSnapshot.positionSecondsOrNull()
    val durationSeconds = playbackSnapshot.durationSecondsOrNull()
    val request: suspend () -> Unit = {
        val dispatch = dispatchTrackingScrobble(
            profileId = profileId,
            action = TrackingScrobbleAction.STOP,
            media = media,
            progressPercent = percent,
            viaSeek = viaSeek,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            paused = paused,
        )
        showWatchedProviderToast(
            dispatch = dispatch,
            profileId = profileId,
            media = media,
        )
    }
    currentTrackingScrobbleMedia = null
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration += 1L
    return request
}


private suspend fun PlayerScreenRuntime.showWatchedProviderToast(
    dispatch: TrackingScrobbleDispatch,
    profileId: Int,
    media: TrackingMediaReference,
) {
    if (dispatch.watchedProviderIds.isEmpty()) return
    val controller = playerController ?: return
    val mediaKey = buildString {
        append(profileId)
        append(':')
        append(media.stableKey)
        append(':')
        append(media.catalog?.videoId.orEmpty())
        append(':')
        append(media.episode?.season ?: -1)
        append(':')
        append(media.episode?.number ?: -1)
    }
    val providerIds = dispatch.watchedProviderIds.distinct().filter { providerId ->
        synchronized(shownWatchedProviderToastKeys) {
            shownWatchedProviderToastKeys.add("$mediaKey:${providerId.storageId}")
        }
    }
    if (providerIds.isEmpty()) return

    val providerNames = providerIds.joinToString { providerId ->
        TrackingProviderRegistry.authProvider(providerId)?.descriptor?.displayName
            ?: providerId.storageId
    }
    controller.showTransientMessage(
        title = getString(Res.string.player_watched_provider_success),
        value = providerNames,
    )
}

private suspend fun dispatchTrackingScrobble(
    profileId: Int,
    action: TrackingScrobbleAction,
    media: TrackingMediaReference,
    progressPercent: Float,
    viaSeek: Boolean,
    positionSeconds: Long?,
    durationSeconds: Long?,
    paused: Boolean = false,
): TrackingScrobbleDispatch {
    val event = TrackingScrobbleEvent(
        media = media,
        progressPercent = progressPercent.toDouble(),
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        isPauseRatherThanStop = paused,
    )
    return if (viaSeek) {
        TrackingScrobbleCoordinator.scrobbleSeek(profileId = profileId, action = action, event = event)
    } else {
        TrackingScrobbleCoordinator.scrobble(profileId = profileId, action = action, event = event)
    }
}

internal fun PlayerScreenRuntime.emitStopScrobbleForCurrentProgress(
    snapshot: PlayerPlaybackSnapshot = playbackSnapshot,
    paused: Boolean = false,
) {
    // Speed-adjusted percent: used only to decide whether the 80% completion threshold is met.
    // Raw percent: what gets sent to scrobble services so resume starts at the right position.
    val effectivePercent = currentScrobbleProgressPercent(snapshot)
    val rawPercent = currentPlaybackProgressPercent(snapshot)

    if (effectivePercent >= 0.1f && effectivePercent < 80f && hasRequestedScrobbleStartForCurrentItem) {
        emitTrackingScrobbleStop(rawPercent, paused = paused)
        return
    }

    if (effectivePercent >= 80f) {
        val isAtEnd = effectivePercent >= 99f
        if (!hasSentCompletionScrobbleForCurrentItem || isAtEnd) {
            hasSentCompletionScrobbleForCurrentItem = true
            // A completion is a completion even if the player happens to be paused at the time.
            emitTrackingScrobbleStop(rawPercent)
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

internal fun PlayerScreenRuntime.flushWatchProgress(paused: Boolean = false) {
    if (progressTrackingDisabled) return
    // Teardown can zero the live snapshot before the flush runs; fall back to the last meaningful
    // sample of the current video so the final position (and its completion) is not dropped.
    val snapshot = playbackSnapshot.progressSnapshotForFlush(
        initialPositionMs = activeInitialPositionMs,
        initialProgressFraction = activeInitialProgressFraction,
    ) ?: lastMeaningfulPlaybackSnapshot?.progressSnapshotForFlush(
        initialPositionMs = activeInitialPositionMs,
        initialProgressFraction = activeInitialProgressFraction,
    ) ?: run {
        // A controller can be disposed before its first meaningful sample. Do not let the
        // zero-valued placeholder replace an existing resume point or start a 0% scrobble.
        hasRequestedScrobbleStartForCurrentItem = false
        scrobbleStartRequestGeneration += 1L
        currentTrackingScrobbleMedia = null
        return
    }
    emitStopScrobbleForCurrentProgress(snapshot, paused = paused)
    WatchProgressRepository.flushPlaybackProgress(
        session = playbackSession,
        snapshot = snapshot,
    )
}

internal fun PlayerPlaybackSnapshot.progressSnapshotForFlush(
    initialPositionMs: Long,
    initialProgressFraction: Float?,
): PlayerPlaybackSnapshot? {
    val duration = durationMs.coerceAtLeast(0L)
    val requestedPosition = when {
        initialPositionMs > 0L -> initialPositionMs
        duration > 0L && initialProgressFraction != null && initialProgressFraction > 0f ->
            (duration.toDouble() * initialProgressFraction.coerceIn(0f, 1f).toDouble()).toLong()
        else -> 0L
    }
    // Some engines briefly expose a placeholder duration before media metadata settles. Treat a
    // resume point beyond that duration as unknown rather than clamping it to 100% completion.
    if (requestedPosition > 0L && requestedPosition > duration) return null
    val trustworthyPosition = maxOf(positionMs.coerceAtLeast(0L), requestedPosition)
    if (duration <= 0L || trustworthyPosition < 1_000L) return null
    return copy(positionMs = trustworthyPosition.coerceAtMost(duration))
}

/**
 * Jumps past a skip segment. The single implementation behind the prompt (tap, keyboard shortcut,
 * and the auto-accept setting), so all three leave the player in the same state.
 */
internal fun PlayerScreenRuntime.acceptSkipInterval(interval: SkipInterval) {
    playerController?.seekTo((interval.endTime * 1000).toLong())
    scheduleProgressSyncAfterSeek()
    skipIntervalDismissed = true
}

internal fun PlayerScreenRuntime.scheduleProgressSyncAfterSeek() {
    if (progressTrackingDisabled) return
    val shouldRestartScrobbleAfterSeek = shouldPlay || playbackSnapshot.isPlaying
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(PlayerSeekProgressSyncDebounceMs)
        WatchProgressRepository.upsertPlaybackProgress(
            session = playbackSession,
            snapshot = playbackSnapshot,
        )

        val progressPercent = currentPlaybackProgressPercent()
        // No upper bound. This used to stop at 80% so that seeking near the end could not mark an
        // item watched, which left every provider stale for the rest of the file — the reported
        // "stopped tracking" after a seek. Seeking to the end is a deliberate act, so it is
        // reported like any other position and providers may draw their own conclusion.
        if (progressPercent >= 1f) {
            // Seek-driven stop/restart: only providers whose scrobble session has to be torn down
            // and recreated after a jump take part. A provider that treats `start` as "update the
            // existing session" opts out via its seekScrobblePolicy instead.
            //
            // The two halves are awaited in order rather than launched. Racing them let the stop
            // arrive after the restart, and a provider whose stop ends the session then dropped it
            // entirely — the progress simply vanished mid-playback.
            val stopRequest = prepareTrackingScrobbleStop(progressPercent, viaSeek = true)
            val shouldRestartScrobbleNow = shouldRestartScrobbleAfterSeek && shouldPlay
            val startRequest = if (shouldRestartScrobbleNow && playbackSnapshot.isPlaying) {
                pendingScrobbleStartAfterSeek = false
                prepareTrackingScrobbleStart(viaSeek = true)
            } else {
                null
            }
            withContext(NonCancellable) {
                stopRequest?.invoke()
                startRequest?.invoke()
            }
            if (startRequest == null && shouldRestartScrobbleNow) {
                pendingScrobbleStartAfterSeek = true
            }
        }
    }
}

internal fun PlayerScreenRuntime.persistPlaybackProgressTick() {
    if (progressTrackingDisabled) return
    // Ahead of the throttle below, deliberately. The persist interval is a minute, and the whole
    // point of this check is that the tracker learns the title is finished *when it is finished*.
    emitCompletionScrobbleAtThreshold()
    val now = WatchProgressClock.nowEpochMs()
    if (now - lastProgressPersistEpochMs < PlaybackProgressPersistIntervalMs) return
    lastProgressPersistEpochMs = now
    WatchProgressRepository.upsertPlaybackProgress(
        session = playbackSession,
        snapshot = playbackSnapshot,
        syncRemote = false,
    )
    emitTrackingProgressRefresh()
}

/**
 * Sends the completion scrobble the moment playback passes the completion threshold, instead of
 * waiting for something to flush.
 *
 * **The mark used to arrive at the end of the file, and only by accident of when a flush happened.**
 * A stop scrobble is the only thing that records a watched item on a provider, and the only callers
 * of one are pause, exit, source change and end of playback — nothing runs on a timer. So playing a
 * film straight through meant the app marked it watched locally at
 * [WatchProgressCompletionPercentThreshold] (Continue Watching updated, the poster got its tick)
 * while Trakt/Simkl heard nothing until the credits. Worse, a crash or a force-quit inside that last
 * stretch lost the tracker write entirely, on a title the app already considered watched.
 *
 * **The cost, accepted knowingly:** a stop closes the provider's session, so "watching now" goes
 * quiet for the remainder. That is a few minutes of presence traded for a mark that is on time and
 * survives the app dying. The end-of-playback stop still fires (see
 * [emitStopScrobbleForCurrentProgress]'s `isAtEnd` branch) and providers treat the repeat as a
 * duplicate; the toast does not repeat either, because it is deduped per media and provider.
 *
 * The local threshold is used rather than the scrobble path's own 80%, so this fires when the app
 * itself decides the title is finished — one moment, not two.
 */
private fun PlayerScreenRuntime.emitCompletionScrobbleAtThreshold() {
    if (hasSentCompletionScrobbleForCurrentItem) return
    // Only from a live session on the current attempt: a leftover snapshot from the previous
    // source would bank a completion for the wrong item, which is the same hazard the start and
    // the refresh both guard against.
    if (!hasRequestedScrobbleStartForCurrentItem) return
    if (!playbackSnapshot.isPlaying) return
    if (!snapshotBelongsToCurrentAttempt) return
    // Speed-adjusted to decide completion, raw to report position — the same split the flush path
    // uses, and for the same reason.
    if (currentScrobbleProgressPercent() < WatchProgressCompletionPercentThreshold) return
    hasSentCompletionScrobbleForCurrentItem = true
    emitTrackingScrobbleStop(currentPlaybackProgressPercent())
}

/**
 * Re-anchors any provider that asked to be kept in step with a long-running session.
 *
 * Providers extrapolate position from the last scrobble at ordinary speed, so anything that breaks
 * that — a speed above 1x above all — drifts until the stop corrects it. The coordinator applies
 * each provider's own interval, so calling this on the progress cadence costs nothing for the
 * providers that did not opt in.
 */
private fun PlayerScreenRuntime.emitTrackingProgressRefresh() {
    // Only while a session is actually running: a refresh with no preceding start would create one
    // mid-file, and a paused player has nothing new to report.
    if (!hasRequestedScrobbleStartForCurrentItem) return
    if (!playbackSnapshot.isPlaying) return
    // Same hazard as the start: a snapshot left over from the previous attempt would re-anchor the
    // new item at the old one's position.
    if (!snapshotBelongsToCurrentAttempt) return
    val media = currentTrackingScrobbleMedia ?: return
    val progressPercent = currentPlaybackProgressPercent()
    if (progressPercent < 1f) return
    val profileId = ProfileRepository.activeProfileId

    scope.launch {
        TrackingScrobbleCoordinator.scrobbleProgressRefresh(
            profileId = profileId,
            event = TrackingScrobbleEvent(
                media = media,
                progressPercent = progressPercent.toDouble(),
                positionSeconds = playbackSnapshot.positionSecondsOrNull(),
                durationSeconds = playbackSnapshot.durationSecondsOrNull(),
            ),
        )
    }
}

private fun PlayerPlaybackSnapshot.positionSecondsOrNull(): Long? =
    positionMs.takeIf { it > 0L }?.div(1000L)

private fun PlayerPlaybackSnapshot.durationSecondsOrNull(): Long? =
    durationMs.takeIf { it > 0L }?.div(1000L)
