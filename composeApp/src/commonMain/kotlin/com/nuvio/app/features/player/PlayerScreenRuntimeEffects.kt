package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.playbackEpisodeNumber
import com.nuvio.app.features.details.playbackSeasonNumber
import com.nuvio.app.features.details.resolveSeriesEpisodePosition
import com.nuvio.app.features.discord.DiscordPresenceSettingsRepository
import com.nuvio.app.features.discord.DiscordRichPresenceActivity
import com.nuvio.app.features.discord.DiscordRichPresenceController
import com.nuvio.app.features.discord.DiscordRichPresenceActivityType
import com.nuvio.app.features.discord.DiscordRichPresenceImageFit
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.ChapterSkipDetector
import com.nuvio.app.features.player.skip.mergeCommunityAndChapterSkipIntervals
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.streams.BingeGroupCacheRepository
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.hasLikelyExpiringPlaybackCredentials
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.isDesktop
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Composable
internal fun PlayerScreenRuntime.BindPlayerRuntimeEffects() {
    val currentFeedback = liveGestureFeedback ?: gestureFeedback
    LaunchedEffect(currentFeedback) {
        if (currentFeedback != null) {
            renderedGestureFeedback = currentFeedback
        }
    }

    LaunchedEffect(parentMetaType, parentMetaId) {
        playerMetaVideos = MetaDetailsRepository.peek(parentMetaType, parentMetaId)?.videos ?: emptyList()
        if (playerMetaVideos.isEmpty()) {
            playerMetaVideos = MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.videos ?: emptyList()
        }
    }

    LaunchedEffect(metaUiState.meta, parentMetaType, parentMetaId) {
        val currentMeta = metaUiState.meta ?: return@LaunchedEffect
        if (currentMeta.type == parentMetaType && currentMeta.id == parentMetaId) {
            playerMetaVideos = currentMeta.videos
        }
    }

    LaunchedEffect(currentStreamBingeGroup, parentMetaId) {
        val bg = currentStreamBingeGroup
        if (bg != null && parentMetaId.isNotBlank()) {
            BingeGroupCacheRepository.save(parentMetaId, bg)
        }
    }

    LaunchedEffect(activeSourceUrl, activeSourceAudioUrl, activeSourceHeaders, activeSourceResponseHeaders) {
        errorMessage = null
        playbackFailureExitRequested = false
        playbackSourceFailureActive = false
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        lastTrustedPlaybackPositionMs = 0L
        isScrubbingTimeline = false
        scrubbingPositionMs = null
        liveGestureFeedback = null
        renderedGestureFeedback = null
        lockedOverlayVisible = false
        credentialRefreshJob?.cancel()
        credentialRefreshJob = null
        credentialRefreshAttemptedSourceUrl = null
        providerDiagnosticRecoveryAttemptedSourceUrl = null
        initialLoadCompleted = false
        defaultPlaybackSpeedApplied = false
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        pendingScrobbleStartAfterSeek = false
        seekProgressSyncJob?.cancel()
        seekProgressSyncJob = null
        accumulatedSeekResetJob?.cancel()
        accumulatedSeekResetJob = null
        accumulatedSeekState = null
        preferredAudioSelectionApplied = false
        preferredSubtitleSelectionApplied = false
        secondarySubtitleSelectionApplied = false
        showSourcesPanel = false
        showEpisodesPanel = false
        episodeStreamsPanelState = EpisodeStreamsPanelState()
        PlayerStreamsRepository.clearEpisodeStreams()
        SubtitleRepository.clear()
        WatchProgressRepository.ensureLoaded()

        providerDiagnosticProbePendingSourceUrl = activeSourceUrl.takeIf(::isProviderPlaybackEndpoint)
        if (playbackSourceFailure(activeSourceUrl) == PlaybackSourceFailure.DebridRateLimited) {
            val message = getString(Res.string.player_error_debrid_rate_limited)
            providerDiagnosticVideoSourceUrl = activeSourceUrl
            shouldPlay = true
            controlsVisible = !playerControlsLocked
            NuvioToastController.show(message)
        } else if (isExplicitProviderDiagnosticVideoUrl(activeSourceUrl)) {
            providerDiagnosticVideoSourceUrl = activeSourceUrl
        }
    }

    LaunchedEffect(
        activeSourceUrl,
        activeSourceHeaders,
        playerSettingsUiState.streamFailoverEnabled,
    ) {
        val probedUrl = activeSourceUrl
        if (
            shouldSkipProviderDiagnosticVideo(playerSettingsUiState.streamFailoverEnabled) &&
            isExplicitProviderDiagnosticVideoUrl(probedUrl)
        ) {
            providerDiagnosticVideoSourceUrl = null
            tryFailoverToNextSource(
                message = getString(Res.string.player_error_unable_to_play_stream),
                playbackFailedToast = getString(Res.string.player_error_playback_failed),
                tryingNextToast = getString(Res.string.player_failover_trying_next),
                trigger = StreamFailoverTrigger.ProviderDiagnosticVideo,
            )
            return@LaunchedEffect
        }
        if (!isProviderPlaybackEndpoint(probedUrl)) {
            if (providerDiagnosticProbePendingSourceUrl == probedUrl) {
                providerDiagnosticProbePendingSourceUrl = null
            }
            return@LaunchedEffect
        }

        providerDiagnosticProbePendingSourceUrl = probedUrl
        providerDiagnosticRecoveryAttemptedSourceUrl = probedUrl
        val diagnostic = resolveProviderDiagnosticVideo(
            sourceUrl = probedUrl,
            sourceHeaders = activeSourceHeaders,
        )
        if (activeSourceUrl != probedUrl) {
            diagnostic?.let { releaseProviderDiagnosticVideo(it.sourceUrl) }
            return@LaunchedEffect
        }
        providerDiagnosticProbePendingSourceUrl = null
        if (diagnostic != null) {
            if (shouldSkipProviderDiagnosticVideo(playerSettingsUiState.streamFailoverEnabled)) {
                releaseProviderDiagnosticVideo(diagnostic.sourceUrl)
                tryFailoverToNextSource(
                    message = getString(Res.string.player_error_unable_to_play_stream),
                    playbackFailedToast = getString(Res.string.player_error_playback_failed),
                    tryingNextToast = getString(Res.string.player_failover_trying_next),
                    trigger = StreamFailoverTrigger.ProviderDiagnosticVideo,
                )
            } else {
                activateProviderDiagnosticVideo(diagnostic)
            }
        }
    }

    LaunchedEffect(
        activeTorrentInfoHash,
        activeTorrentFileIdx,
        activeTorrentFilename,
        activeTorrentTrackers,
        p2pSettingsUiState.p2pEnabled,
    ) {
        val infoHash = activeTorrentInfoHash
        if (infoHash == null) {
            p2pResolvedSourceUrl = null
            P2pStreamingEngine.stopStream()
            return@LaunchedEffect
        }
        if (!P2pSettingsRepository.isVisible || !p2pSettingsUiState.p2pEnabled) {
            return@LaunchedEffect
        }

        p2pResolvedSourceUrl = null
        val requestedFileIdx = activeTorrentFileIdx
        val requestedFilename = activeTorrentFilename
        val requestedTrackers = activeTorrentTrackers
        errorMessage = null
        playerController = null
        playerControllerSourceUrl = null
        playbackSnapshot = PlayerPlaybackSnapshot()
        initialLoadCompleted = false

        try {
            val localUrl = P2pStreamingEngine.startStream(
                P2pStreamRequest(
                    infoHash = infoHash,
                    fileIdx = requestedFileIdx,
                    filename = requestedFilename,
                    trackers = requestedTrackers,
                ),
            )
            if (activeTorrentInfoHash == infoHash && activeTorrentFileIdx == requestedFileIdx) {
                activeSourceAudioUrl = null
                activeSourceHeaders = emptyMap()
                activeSourceResponseHeaders = emptyMap()
                p2pResolvedSourceUrl = localUrl
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errorMessage = getString(
                Res.string.player_error_failed_start_torrent,
                error.message ?: genericUnknownLabel,
            )
            controlsVisible = !playerControlsLocked
            initialLoadCompleted = true
        }
    }

    LaunchedEffect(p2pStreamingState, activeTorrentInfoHash) {
        val state = p2pStreamingState
        if (activeTorrentInfoHash != null && state is P2pStreamingState.Error) {
            errorMessage = getString(Res.string.player_error_torrent, state.message)
            controlsVisible = !playerControlsLocked
        }
    }

    LaunchedEffect(playbackSession.videoId) {
        subtitleDelayMs = PlayerTrackPreferenceStorage.loadSubtitleDelayMs(playbackSession.videoId) ?: 0
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleDelayMs) {
        playerController?.setSubtitleDelayMs(subtitleDelayMs)
    }

    LaunchedEffect(selectedAddonSubtitleId, useCustomSubtitles, activeSourceUrl) {
        subtitleAutoSyncState = SubtitleAutoSyncUiState()
    }

    LaunchedEffect(playerController, subtitleStyle) {
        playerController?.applySubtitleStyle(subtitleStyle)
    }

    LaunchedEffect(
        playerController,
        playerSettingsUiState.dualSubtitlesEnabled,
        playerSettingsUiState.secondaryPreferredSubtitleLanguage,
    ) {
        secondarySubtitleSelectionApplied = false
        if (playerController != null && !playbackSnapshot.isLoading) {
            refreshTracks()
        }
    }

    LaunchedEffect(activeSourceUrl, addonSubtitleFetchKey, playerSettingsUiState.addonSubtitleStartupMode) {
        val fetchKey = addonSubtitleFetchKey ?: return@LaunchedEffect
        if (playerSettingsUiState.addonSubtitleStartupMode == AddonSubtitleStartupMode.FAST_STARTUP) {
            return@LaunchedEffect
        }
        if (autoFetchedAddonSubtitlesForKey == fetchKey) return@LaunchedEffect
        autoFetchedAddonSubtitlesForKey = fetchKey
        fetchAddonSubtitlesForActiveItem()?.join()
        if (addonSubtitleFetchKey == fetchKey) {
            completedAutoAddonSubtitleFetchForKey = fetchKey
            applyPreferredAddonSubtitleIfReady()
        }
    }

    LaunchedEffect(
        addonSubtitles,
        isLoadingAddonSubtitles,
        autoFetchedAddonSubtitlesForKey,
        completedAutoAddonSubtitleFetchForKey,
        playerController,
        playbackSnapshot.isLoading,
        trackPreferenceRestoreApplied,
    ) {
        applyPreferredAddonSubtitleIfReady()
    }

    LaunchedEffect(playbackSnapshot.isLoading, playerController) {
        if (!playbackSnapshot.isLoading && playerController != null) {
            refreshTracks()
        }
    }

    LaunchedEffect(
        playerController,
        playbackSnapshot.isLoading,
        preferredAudioSelectionApplied,
        preferredSubtitleSelectionApplied,
        secondarySubtitleSelectionApplied,
    ) {
        if (playerController == null) return@LaunchedEffect
        // During the initial load, poll mpv's native track list and restore persisted tracks as
        // soon as they appear. This deliberately runs while isLoading=true so subtitle selection
        // happens before the first visible frame. Continue suppressing track changes during a
        // later, mid-playback rebuffer.
        if (playbackSnapshot.isLoading && initialLoadCompleted) return@LaunchedEffect
        if (preferredAudioSelectionApplied &&
            preferredSubtitleSelectionApplied &&
            secondarySubtitleSelectionApplied
        ) {
            return@LaunchedEffect
        }

        repeat(INITIAL_TRACK_RESTORE_POLL_ATTEMPTS) {
            refreshTracks()
            if (preferredAudioSelectionApplied &&
                preferredSubtitleSelectionApplied &&
                secondarySubtitleSelectionApplied
            ) {
                return@LaunchedEffect
            }
            delay(INITIAL_TRACK_RESTORE_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(
        playerController,
        playerControllerSourceUrl,
        playbackSnapshot.isLoading,
        playbackSnapshot.durationMs,
        activeInitialPositionMs,
        activeInitialProgressFraction,
        initialSeekApplied,
    ) {
        val controller = playerController ?: return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        if (isProviderDiagnosticVideoPlayback) {
            initialSeekApplied = true
            return@LaunchedEffect
        }
        if (initialSeekApplied || playbackSnapshot.isLoading) return@LaunchedEffect

        val progressFraction = activeInitialProgressFraction
            ?.takeIf { it > 0f }
            ?.coerceIn(0f, 1f)
        val targetPositionMs = when {
            activeInitialPositionMs > 0L -> activeInitialPositionMs
            progressFraction != null && playbackSnapshot.durationMs > 0L -> {
                (playbackSnapshot.durationMs.toDouble() * progressFraction.toDouble()).toLong()
            }
            progressFraction != null -> return@LaunchedEffect
            else -> 0L
        }
        if (targetPositionMs <= 0L) {
            initialSeekApplied = true
            return@LaunchedEffect
        }
        // Desktop mpv applies both absolute and percentage resume positions in its loadfile
        // command, before the first frame. Mobile engines still need this duration-based seek.
        if (isDesktop && (activeInitialPositionMs > 0L || progressFraction != null)) {
            initialSeekApplied = true
            return@LaunchedEffect
        }

        controller.seekTo(targetPositionMs)
        initialSeekApplied = true
    }

    BindPlayerUiVisibilityEffects()
    BindPlayerMetadataAndSkipEffects()
    BindDiscordRichPresenceEffect()
    BindStreamFailoverWatchdogEffect()

    DisposableEffect(playbackSession.videoId, activeSourceUrl, activeSourceAudioUrl) {
        val effectVideoId = playbackSession.videoId
        val effectSourceUrl = activeSourceUrl
        val effectSourceAudioUrl = activeSourceAudioUrl
        onDispose {
            if (
                playbackSession.videoId == effectVideoId &&
                activeSourceUrl == effectSourceUrl &&
                activeSourceAudioUrl == effectSourceAudioUrl
            ) {
                flushWatchProgress()
            }
            releaseProviderDiagnosticVideo(effectSourceUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            P2pStreamingEngine.shutdown()
            PlayerStreamsRepository.clearAll()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindDiscordRichPresenceEffect() {
    DiscordPresenceSettingsRepository.ensureLoaded()
    val discordSettings by DiscordPresenceSettingsRepository.uiState.collectAsState()
    val positionBucket = playbackSnapshot.positionMs.coerceAtLeast(0L) / DISCORD_PROGRESS_UPDATE_BUCKET_MS
    var presenceReleaseYear by remember(parentMetaId) {
        mutableStateOf(discordPresenceReleaseYear())
    }
    val currentReleaseYear = discordPresenceReleaseYear()
    LaunchedEffect(currentReleaseYear) {
        if (currentReleaseYear != null) presenceReleaseYear = currentReleaseYear
    }

    // Reconcile the player-owned presence periodically. This recovers if Discord misses the route
    // handoff from "Choosing a stream" without requiring a user pause/unpause interaction.
    var presenceReconcileTick by remember { mutableStateOf(0L) }
    LaunchedEffect(discordSettings.showPlaybackPresence, activeSourceUrl) {
        if (!discordSettings.showPlaybackPresence) return@LaunchedEffect
        while (true) {
            delay(DISCORD_PLAYBACK_RECONCILE_MS)
            presenceReconcileTick++
        }
    }

    // Discord has no paused timestamp state. Re-anchor its native wall-clock bar periodically so
    // it remains visually near the paused position without flooding the IPC connection.
    var pausedAnchorTick by remember { mutableStateOf(0L) }
    LaunchedEffect(playbackSnapshot.isPlaying, playbackSnapshot.isLoading, playbackSnapshot.isEnded) {
        if (playbackSnapshot.isPlaying || playbackSnapshot.isLoading || playbackSnapshot.isEnded) return@LaunchedEffect
        while (true) {
            delay(DISCORD_PAUSED_ANCHOR_REFRESH_MS)
            pausedAnchorTick++
        }
    }

    LaunchedEffect(
        discordSettings.showPlaybackPresence,
        title,
        activeVideoId,
        activeSeasonNumber,
        activeEpisodeNumber,
        activeEpisodeTitle,
        presenceReleaseYear,
        presenceReconcileTick,
        pausedAnchorTick,
        poster,
        activeEpisodeThumbnail,
        background,
        playbackSnapshot.isLoading,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isEnded,
        playbackSnapshot.durationMs,
        playbackSnapshot.playbackSpeed,
        positionBucket,
        errorMessage,
        isProviderDiagnosticVideoPlayback,
    ) {
        val presenceTitle = title.trim().takeIf { it.isNotBlank() }
        if (
            !discordSettings.showPlaybackPresence ||
            isProviderDiagnosticVideoPlayback ||
            playbackSnapshot.isEnded ||
            errorMessage != null
        ) {
            DiscordRichPresenceController.setPlaybackActivity(null)
            return@LaunchedEffect
        }

        if (playbackSnapshot.isLoading) {
            DiscordRichPresenceController.setPlaybackActivity(
                DiscordRichPresenceActivity(
                    title = "Starting stream",
                    subtitle = presenceTitle,
                    imageUrl = discordPresenceImageUrl(),
                    imageFit = discordPresenceImageFit(),
                    type = DiscordRichPresenceActivityType.Browsing,
                ),
            )
            return@LaunchedEffect
        }

        if (presenceTitle == null) {
            DiscordRichPresenceController.setPlaybackActivity(null)
            return@LaunchedEffect
        }

        DiscordRichPresenceController.setPlaybackActivity(
            DiscordRichPresenceActivity(
                title = presenceTitle,
                subtitle = discordPresenceSubtitle(presenceReleaseYear),
                episodeLabel = discordPresenceEpisodeLabel(),
                episodeTitle = activeEpisodeTitle?.trim()?.takeIf { it.isNotBlank() },
                imageUrl = discordPresenceImageUrl(),
                imageFit = discordPresenceImageFit(),
                type = DiscordRichPresenceActivityType.Playback,
                isPlaying = playbackSnapshot.isPlaying,
                positionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
                durationMs = playbackSnapshot.durationMs.coerceAtLeast(0L),
                refreshNonce = presenceReconcileTick * 1_000_000L + pausedAnchorTick,
            ),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            DiscordRichPresenceController.setPlaybackActivity(null)
        }
    }
}

/**
 * Load-timeout half of stream failover: when a source is active but never starts playing within the
 * configured timeout (a stream that "connects but never buffers", which raises no error), treat it as
 * a failed start and fail over to the next source. Initial-start only — mid-playback rebuffering must
 * not trip it. Re-arms per source (keyed on [activeSourceUrl]); after sustained successful playback
 * it clears the failover tried-set so a later, unrelated mid-playback failure gets a fresh budget.
 */
private const val STREAM_FAILOVER_WATCHDOG_STEP_MS = 500L
private const val STREAM_FAILOVER_SUSTAINED_PLAYBACK_RESET_MS = 30_000L
private const val INITIAL_TRACK_RESTORE_POLL_INTERVAL_MS = 250L
private const val INITIAL_TRACK_RESTORE_POLL_ATTEMPTS = 60

@Composable
private fun PlayerScreenRuntime.BindStreamFailoverWatchdogEffect() {
    LaunchedEffect(
        activeSourceUrl,
        playerSettingsUiState.streamFailoverEnabled,
        playerSettingsUiState.streamFailoverTimeoutSeconds,
        isProviderDiagnosticVideoPlayback,
    ) {
        if (!playerSettingsUiState.streamFailoverEnabled) return@LaunchedEffect
        if (activeSourceUrl.isBlank() || isProviderDiagnosticVideoPlayback) return@LaunchedEffect
        val watchedUrl = activeSourceUrl
        val timeoutMs = playerSettingsUiState.streamFailoverTimeoutSeconds.coerceAtLeast(1) * 1000L
        var elapsed = 0L
        var started = false
        while (elapsed < timeoutMs) {
            if (initialLoadCompleted || playbackSnapshot.positionMs > 0L) {
                started = true
                break
            }
            // The error path or a source change already took over.
            if (activeSourceUrl != watchedUrl || errorMessage != null || failoverInProgress) return@LaunchedEffect
            delay(STREAM_FAILOVER_WATCHDOG_STEP_MS)
            elapsed += STREAM_FAILOVER_WATCHDOG_STEP_MS
        }
        if (!started &&
            activeSourceUrl == watchedUrl &&
            !initialLoadCompleted &&
            playbackSnapshot.positionMs <= 0L &&
            errorMessage == null &&
            !failoverInProgress
        ) {
            tryFailoverToNextSource(
                message = getString(Res.string.player_failover_stream_timed_out),
                playbackFailedToast = getString(Res.string.player_error_playback_failed),
                tryingNextToast = getString(Res.string.player_failover_trying_next),
                trigger = StreamFailoverTrigger.StartupTimeout,
            )
            return@LaunchedEffect
        }

        // Do not immediately forget the sources that just failed: a replacement can render its
        // first frame and still die moments later. Thirty seconds of actual, non-buffering playback
        // is long enough to treat a later failure as a new incident. Pausing stops the clock; a
        // rebuffer resets it because the playback was not continuous.
        var sustainedPlaybackMs = 0L
        while (
            activeSourceUrl == watchedUrl &&
            playerSettingsUiState.streamFailoverEnabled &&
            failoverTriedIdentityKeys.isNotEmpty()
        ) {
            delay(STREAM_FAILOVER_WATCHDOG_STEP_MS)
            val snapshot = playbackSnapshot
            when {
                snapshot.isPlaying && !snapshot.isLoading && !snapshot.isEnded -> {
                    sustainedPlaybackMs += STREAM_FAILOVER_WATCHDOG_STEP_MS
                }
                snapshot.isLoading -> sustainedPlaybackMs = 0L
            }
            if (sustainedPlaybackMs >= STREAM_FAILOVER_SUSTAINED_PLAYBACK_RESET_MS) {
                StreamFailoverLog.event("playback_stabilized", buildJsonObject {
                    put("continuousPlaybackMs", sustainedPlaybackMs)
                    put("clearedTriedSourceCount", failoverTriedIdentityKeys.size)
                })
                failoverTriedIdentityKeys.clear()
                return@LaunchedEffect
            }
        }
    }
}

private fun PlayerScreenRuntime.discordPresenceImageUrl(): String? =
    listOf(poster, activeEpisodeThumbnail, background)
        .firstOrNull { url ->
            url?.trim()?.let { it.startsWith("https://") || it.startsWith("http://") } == true
        }
        ?.trim()

private fun PlayerScreenRuntime.discordPresenceImageFit(): DiscordRichPresenceImageFit =
    if (poster?.trim()?.let { it.startsWith("https://") || it.startsWith("http://") } == true) {
        DiscordRichPresenceImageFit.Contain
    } else {
        DiscordRichPresenceImageFit.Cover
    }

private fun PlayerScreenRuntime.discordPresenceSubtitle(releaseYear: String?): String? {
    val episodeLabel = discordPresenceEpisodeLabel()
    val episodeState = listOfNotNull(
        episodeLabel,
        activeEpisodeTitle?.trim()?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotBlank() }
    // Movies have no episode line; surface the release year beneath the title instead.
    return episodeState ?: releaseYear
}

private fun PlayerScreenRuntime.discordPresenceEpisodeLabel(): String? {
    val episodeNumber = activeEpisodeNumber ?: return null
    return activeSeasonNumber?.let { seasonNumber ->
        "S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"
    } ?: "Episode $episodeNumber"
}

/** Leading four-digit release year from the loaded meta (e.g. "2021" from "2021" or "2019–2023"). */
private fun PlayerScreenRuntime.discordPresenceReleaseYear(): String? {
    val matchingMeta = metaUiState.meta?.takeIf { meta ->
        meta.id == parentMetaId && meta.type.equals(parentMetaType, ignoreCase = true)
    } ?: MetaDetailsRepository.peek(parentMetaType, parentMetaId)
    return extractDiscordReleaseYear(matchingMeta?.releaseInfo)
}

internal fun extractDiscordReleaseYear(releaseInfo: String?): String? =
    releaseInfo
        ?.let { Regex("\\d{4}").find(it)?.value }
        ?.takeIf { it.isNotBlank() }

@Composable
private fun PlayerScreenRuntime.BindPlayerUiVisibilityEffects() {
    LaunchedEffect(
        controlsVisible,
        mouseActivitySignal,
        nativeChromeInteractionActive,
        isScrubbingTimeline,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        showParentalGuide,
        errorMessage,
    ) {
        if (
            !controlsVisible ||
            nativeChromeInteractionActive ||
            isScrubbingTimeline ||
            playbackSnapshot.isLoading ||
            showParentalGuide ||
            errorMessage != null
        ) {
            return@LaunchedEffect
        }
        // Auto-hide runs while paused too (not just during playback): a mouse move re-shows the
        // controls, and letting them time out again is what allows the paused metadata overlay
        // (gated on !controlsVisible) to reappear. Keeping controls pinned while paused was the
        // reason any mouse movement made the overlay effectively unreachable.
        delay(5500)
        controlsVisible = false
    }

    LaunchedEffect(playerControlsLocked, lockedOverlayVisible) {
        if (!playerControlsLocked || !lockedOverlayVisible) return@LaunchedEffect
        delay(PlayerLockedOverlayDurationMs)
        lockedOverlayVisible = false
    }

    LaunchedEffect(
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        playbackSnapshot.durationMs,
        errorMessage,
        pausedOverlayInteractionSignal,
    ) {
        pausedOverlayVisible = false
        if (playbackSnapshot.isPlaying || playbackSnapshot.isLoading || playbackSnapshot.durationMs <= 0L || errorMessage != null) {
            return@LaunchedEffect
        }
        delay(5000)
        pausedOverlayVisible = true
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isLoading,
        playbackSnapshot.isEnded,
        playbackSnapshot.durationMs,
        lastTrustedPlaybackPositionMs,
        progressTrackingDisabled,
    ) {
        if (progressTrackingDisabled) {
            previousIsPlaying = false
            pendingScrobbleStartAfterSeek = false
            return@LaunchedEffect
        }
        if (playbackSnapshot.isEnded) {
            if (
                isTrustworthyPlaybackEnd(
                    isEnded = true,
                    durationMs = playbackSnapshot.durationMs,
                    positionMs = playbackSnapshot.positionMs,
                    lastTrustedPositionMs = lastTrustedPlaybackPositionMs,
                )
            ) {
                flushWatchProgress()
            }
            previousIsPlaying = false
            pendingScrobbleStartAfterSeek = false
            return@LaunchedEffect
        }

        if (previousIsPlaying && !playbackSnapshot.isPlaying && !playbackSnapshot.isLoading) {
            pendingScrobbleStartAfterSeek = false
            if (
                isPlaybackPositionSupportedByRecentProgress(
                    positionMs = playbackSnapshot.positionMs,
                    lastTrustedPositionMs = lastTrustedPlaybackPositionMs,
                )
            ) {
                flushWatchProgress()
            }
        }

        if (playbackSnapshot.isPlaying && pendingScrobbleStartAfterSeek) {
            pendingScrobbleStartAfterSeek = false
            emitTraktScrobbleStart()
        } else if (!previousIsPlaying && playbackSnapshot.isPlaying) {
            emitTraktScrobbleStart()
        }

        if (!playbackSnapshot.isLoading) {
            previousIsPlaying = playbackSnapshot.isPlaying
        }
        if (playbackSnapshot.isPlaying) {
            persistPlaybackProgressTick()
        }
    }
}

@Composable
private fun PlayerScreenRuntime.BindPlayerMetadataAndSkipEffects() {
    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber, parentMetaId, parentMetaType) {
        parentalWarnings = emptyList()
        showParentalGuide = false
        parentalGuideHasShown = false
        playbackStartedForParentalGuide = false

        val imdbId = resolveParentalGuideImdbId() ?: return@LaunchedEffect
        val guide = ParentalGuideRepository.getParentalGuide(imdbId) ?: return@LaunchedEffect
        parentalWarnings = buildParentalWarnings(guide, parentalGuideLabels)

        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(playbackSnapshot.isPlaying, parentalWarnings) {
        if (playbackSnapshot.isPlaying) {
            tryShowParentalGuide()
        }
    }

    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber) {
        skipIntervals = emptyList()
        playerChapters = emptyList()
        communitySkipIntervals = emptyList()
        chapterSkipIntervals = emptyList()
        activeSkipInterval = null
        skipIntervalDismissed = false
        showNextEpisodeCard = false
        nextEpisodeThresholdStableSamples = 0
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlaySearching = false

        val season = activeSeasonNumber
        val episode = activeEpisodeNumber
        val vid = activeVideoId
        if (season == null || episode == null || vid == null) return@LaunchedEffect

        launch {
            val imdbId = vid.split(":").firstOrNull()?.takeIf { it.startsWith("tt") }
            val intervals = SkipIntroRepository.getSkipIntervals(
                imdbId = imdbId,
                season = season,
                episode = episode,
            )
            communitySkipIntervals = intervals
            skipIntervals = mergeCommunityAndChapterSkipIntervals(
                communityIntervals = intervals,
                chapterIntervals = chapterSkipIntervals,
            )
        }
    }

    LaunchedEffect(
        activeVideoId,
        activeSourceIdentityKey,
        playerController,
        playerControllerSourceUrl,
        playbackSnapshot.durationMs,
    ) {
        if (playbackSnapshot.durationMs <= 0L) return@LaunchedEffect
        if (playerControllerSourceUrl != activeSourceUrl) return@LaunchedEffect
        val chapters = playerController?.getChapters().orEmpty()
        playerChapters = chapters
        val intervals = ChapterSkipDetector.findIntervals(
            chapters = chapters,
            durationSeconds = playbackSnapshot.durationMs / 1000.0,
        )
        chapterSkipIntervals = intervals
        skipIntervals = mergeCommunityAndChapterSkipIntervals(
            communityIntervals = communitySkipIntervals,
            chapterIntervals = intervals,
        )
    }

    LaunchedEffect(playbackSnapshot.positionMs, skipIntervals) {
        if (skipIntervals.isEmpty()) {
            activeSkipInterval = null
            return@LaunchedEffect
        }
        val positionSec = playbackSnapshot.positionMs / 1000.0
        val current = skipIntervals.firstOrNull { interval ->
            positionSec >= interval.startTime && positionSec < interval.endTime
        }
        if (current != activeSkipInterval) {
            activeSkipInterval = current
            if (current != null) skipIntervalDismissed = false
        }
    }

    LaunchedEffect(playerMetaVideos, activeVideoId, activeSeasonNumber, activeEpisodeNumber) {
        if (!isSeries || playerMetaVideos.isEmpty()) {
            nextEpisodeInfo = null
            return@LaunchedEffect
        }
        val curSeason = activeSeasonNumber
        val curEpisode = activeEpisodeNumber ?: return@LaunchedEffect
        val nextVideo = resolveAutoPlayEpisode(
            videos = playerMetaVideos,
            currentVideoId = activeVideoId,
            parentMetaId = parentMetaId,
            currentSeason = curSeason,
            currentEpisode = curEpisode,
            mode = autoPlayMode,
        )
        val nextEpisode = nextVideo?.playbackEpisodeNumber()
        nextEpisodeInfo = if (nextVideo != null && nextEpisode != null) {
            NextEpisodeInfo(
                videoId = nextVideo.id,
                season = nextVideo.playbackSeasonNumber() ?: curSeason ?: 1,
                episode = nextEpisode,
                title = nextVideo.title,
                thumbnail = nextVideo.thumbnail,
                overview = nextVideo.overview,
                released = nextVideo.released,
                hasAired = PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released),
                unairedMessage = if (!PlayerNextEpisodeRules.hasEpisodeAired(nextVideo.released)) {
                    "$airsPrefix ${nextVideo.released ?: tbaLabel}"
                } else null,
            )
        } else null
    }

    LaunchedEffect(
        playbackSnapshot.positionMs,
        playbackSnapshot.durationMs,
        playbackSnapshot.isEnded,
        lastTrustedPlaybackPositionMs,
        nextEpisodeInfo,
        isProviderDiagnosticVideoPlayback,
        playbackSourceFailureActive,
        errorMessage,
        skipIntervals,
        playerSettingsUiState.nextEpisodeThresholdMode,
        playerSettingsUiState.nextEpisodeThresholdPercent,
        playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
    ) {
        if (
            isProviderDiagnosticVideoPlayback ||
            playbackSourceFailureActive ||
            errorMessage != null ||
            nextEpisodeInfo == null ||
            playbackSnapshot.isEnded ||
            playbackSnapshot.durationMs <= 0L
        ) {
            showNextEpisodeCard = false
            nextEpisodeThresholdStableSamples = 0
            return@LaunchedEffect
        }
        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = playbackSnapshot.positionMs,
            durationMs = playbackSnapshot.durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = playerSettingsUiState.nextEpisodeThresholdMode,
            thresholdPercent = playerSettingsUiState.nextEpisodeThresholdPercent,
            thresholdMinutesBeforeEnd = playerSettingsUiState.nextEpisodeThresholdMinutesBeforeEnd,
        )
        if (shouldShow) {
            val thresholdWasNew = !showNextEpisodeCard
            showNextEpisodeCard = true
            nextEpisodeThresholdStableSamples++
            val willTrigger = playerSettingsUiState.streamAutoPlayNextEpisodeEnabled &&
                nextEpisodeInfo?.hasAired == true &&
                !nextEpisodeAdvanceInProgress &&
                isPlaybackPositionSupportedByRecentProgress(
                    positionMs = playbackSnapshot.positionMs,
                    lastTrustedPositionMs = lastTrustedPlaybackPositionMs,
                ) &&
                nextEpisodeThresholdStableSamples >= NEXT_EPISODE_THRESHOLD_STABLE_SAMPLES
            if (thresholdWasNew || willTrigger) {
                BingeAdvanceLog.i {
                    "threshold reached pos=${playbackSnapshot.positionMs} dur=${playbackSnapshot.durationMs} " +
                        "samples=$nextEpisodeThresholdStableSamples " +
                        "autoPlayEnabled=${playerSettingsUiState.streamAutoPlayNextEpisodeEnabled} " +
                        "hasAired=${nextEpisodeInfo?.hasAired} advanceInProgress=$nextEpisodeAdvanceInProgress " +
                        "-> triggering=$willTrigger"
                }
            }
            if (willTrigger) {
                // Subtitle/audio track changes make mpv perform a refresh seek. During that seek
                // it can briefly publish a near-end position even though playback remains in the
                // middle of the episode. Consecutive threshold samples filter out that spike.
                if (!playbackSnapshot.isLoading) {
                    nextEpisodeAdvanceInProgress = true
                    playNextEpisode()
                }
            }
        } else if (!shouldShow) {
            showNextEpisodeCard = false
            nextEpisodeThresholdStableSamples = 0
        }
    }

    LaunchedEffect(
        playbackSnapshot.isEnded,
        lastTrustedPlaybackPositionMs,
        nextEpisodeInfo,
        isProviderDiagnosticVideoPlayback,
        playbackSourceFailureActive,
        errorMessage,
    ) {
        if (
            !isProviderDiagnosticVideoPlayback &&
            !playbackSourceFailureActive &&
            errorMessage == null &&
            playbackSnapshot.isEnded
        ) {
            delay(NEXT_EPISODE_EOF_STABILITY_MS)
            val durationMs = playbackSnapshot.durationMs
            val positionMs = playbackSnapshot.positionMs
            val isStableRealEnd = isTrustworthyPlaybackEnd(
                isEnded = playbackSnapshot.isEnded,
                durationMs = durationMs,
                positionMs = positionMs,
                lastTrustedPositionMs = lastTrustedPlaybackPositionMs,
            )
            val nextVideoId = nextEpisodeInfo?.videoId
            val hasPlayableNextEpisode = nextEpisodeInfo?.hasAired == true &&
                nextVideoId != null &&
                playerMetaVideos.any { it.id == nextVideoId }
            if (hasPlayableNextEpisode) showNextEpisodeCard = true
            // The latch (cleared only once the next episode is genuinely playing) stops a stale
            // end-of-file — which lingers while the next stream loads — from advancing twice and
            // skipping an episode. This effect also re-runs when nextEpisodeInfo changes to the
            // following episode, which is exactly the path that produced the skip.
            val willTrigger = isStableRealEnd &&
                hasPlayableNextEpisode &&
                nextEpisodeAutoPlayJob?.isActive != true &&
                !nextEpisodeAdvanceInProgress
            val willExit = isStableRealEnd &&
                !hasPlayableNextEpisode &&
                nextEpisodeAutoPlayJob?.isActive != true &&
                !nextEpisodeAdvanceInProgress &&
                !playbackEndExitRequested
            BingeAdvanceLog.i {
                "end-of-file fallback hasAired=${nextEpisodeInfo?.hasAired} " +
                    "pos=$positionMs dur=$durationMs lastTrusted=$lastTrustedPlaybackPositionMs " +
                    "jobActive=${nextEpisodeAutoPlayJob?.isActive} advanceInProgress=$nextEpisodeAdvanceInProgress " +
                    "-> triggering=$willTrigger exiting=$willExit"
            }
            if (willTrigger) {
                nextEpisodeAdvanceInProgress = true
                playNextEpisode()
            } else if (willExit) {
                playbackEndExitRequested = true
                flushWatchProgress()
                args.onPlaybackCompleted()
            }
        }
    }

    // Release the auto-advance latch once the new episode is actually playing (not just selected,
    // and not in the stale-ended loading gap), so the next end can advance exactly once.
    LaunchedEffect(
        playbackSnapshot.isEnded,
        playbackSnapshot.positionMs >= NEXT_EPISODE_ADVANCE_RESET_POSITION_MS,
    ) {
        if (!playbackSnapshot.isEnded && playbackSnapshot.positionMs >= NEXT_EPISODE_ADVANCE_RESET_POSITION_MS) {
            if (nextEpisodeAdvanceInProgress) {
                BingeAdvanceLog.i { "advance latch released (next episode playing) pos=${playbackSnapshot.positionMs}" }
            }
            nextEpisodeAdvanceInProgress = false
        }
    }

    // Safety net: some internal stream-switch paths (e.g. a debrid link resolving stale, or a
    // null playableDirectUrl deep inside switchToEpisodeStream) can bail out silently after the
    // latch is engaged without ever starting a new episode, which would otherwise leave
    // auto-advance permanently disabled for the rest of the session. Force-release it after a
    // bound comfortably longer than the stream-search hard timeout if nothing has cleared it
    // naturally by then.
    LaunchedEffect(nextEpisodeAdvanceInProgress) {
        if (!nextEpisodeAdvanceInProgress) return@LaunchedEffect
        delay(NEXT_EPISODE_ADVANCE_LATCH_SAFETY_TIMEOUT_MS)
        BingeAdvanceLog.i { "advance latch force-released after safety timeout (advance likely bailed out)" }
        nextEpisodeAdvanceInProgress = false
    }
}

private const val NEXT_EPISODE_ADVANCE_LATCH_SAFETY_TIMEOUT_MS = 150_000L
private const val NEXT_EPISODE_THRESHOLD_STABLE_SAMPLES = 3
private const val NEXT_EPISODE_EOF_STABILITY_MS = 1_000L
private const val NEXT_EPISODE_EOF_POSITION_TOLERANCE_MS = 5_000L
private const val NEXT_EPISODE_TRUSTED_POSITION_TOLERANCE_MS = 30_000L

internal fun isPlaybackPositionSupportedByRecentProgress(
    positionMs: Long,
    lastTrustedPositionMs: Long,
): Boolean =
    lastTrustedPositionMs > 0L &&
        positionMs <= lastTrustedPositionMs + NEXT_EPISODE_TRUSTED_POSITION_TOLERANCE_MS

internal fun isTrustworthyPlaybackEnd(
    isEnded: Boolean,
    durationMs: Long,
    positionMs: Long,
    lastTrustedPositionMs: Long,
): Boolean =
    isEnded &&
        durationMs > 0L &&
        positionMs >= (durationMs - NEXT_EPISODE_EOF_POSITION_TOLERANCE_MS).coerceAtLeast(0L) &&
        lastTrustedPositionMs > 0L &&
        lastTrustedPositionMs >=
            (durationMs - NEXT_EPISODE_TRUSTED_POSITION_TOLERANCE_MS).coerceAtLeast(0L)

// Diagnostic logging for the next-episode / binge auto-advance path. Reports of it silently not
// firing (notably while the window is minimized, which pauses Compose recomposition and can stall
// these snapshot-keyed effects) are hard to reproduce, so this traces every link in the chain —
// threshold detection, the end-of-file fallback, the advance latch, and the actual advance call —
// to pinpoint exactly where a stall happens the next time it's observed. Purely observational.
internal val BingeAdvanceLog = Logger.withTag("BingeAdvance")

private fun resolveAutoPlayEpisode(
    videos: List<MetaVideo>,
    currentVideoId: String?,
    parentMetaId: String?,
    currentSeason: Int?,
    currentEpisode: Int?,
    mode: PlayerAutoPlayMode,
): MetaVideo? {
    if (mode != PlayerAutoPlayMode.RandomEpisode) {
        return PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentVideoId = currentVideoId,
            parentMetaId = parentMetaId,
        )
    }

    val airedEpisodes = videos
        .filter { video ->
            video.playbackEpisodeNumber()?.let { it > 0 } == true &&
                PlayerNextEpisodeRules.hasEpisodeAired(video.released)
        }
    val mainSeasonEpisodes = airedEpisodes.filter { video ->
        video.playbackSeasonNumber()?.let { it > 0 } == true
    }
    val randomPool = mainSeasonEpisodes.ifEmpty { airedEpisodes }
    val currentVideo = videos.resolveSeriesEpisodePosition(
        parentMetaId = parentMetaId,
        videoId = currentVideoId,
        seasonNumber = currentSeason,
        episodeNumber = currentEpisode,
    )?.video
    val candidates = randomPool.filterNot { video -> video == currentVideo }.ifEmpty { randomPool }

    return candidates.randomOrNull(Random.Default)
}

internal fun PlayerScreenRuntime.removeFailedStreamFromCache() {
    val currentVideoId = activeVideoId ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = currentVideoId,
        parentMetaId = parentMetaId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    StreamLinkCacheRepository.remove(cacheKey)
}

internal fun PlayerScreenRuntime.tryRefreshCredentialedSourceAfterError(message: String?): Boolean {
    val failedUrl = activeSourceUrl
    if (!failedUrl.hasLikelyExpiringPlaybackCredentials()) return false
    if (credentialRefreshJob?.isActive == true) return true
    if (credentialRefreshAttemptedSourceUrl == failedUrl) return false

    val currentVideoId = activeVideoId ?: return false
    credentialRefreshAttemptedSourceUrl = failedUrl
    removeFailedStreamFromCache()

    val savedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    val expectedProviderAddonId = activeProviderAddonId
    val expectedProviderName = activeProviderName
    val expectedStreamTitle = activeStreamTitle
    val expectedBingeGroup = currentStreamBingeGroup
    val type = contentType ?: parentMetaType
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber

    errorMessage = null
    controlsVisible = !playerControlsLocked

    credentialRefreshJob = scope.launch {
        PlayerStreamsRepository.loadSources(
            type = type,
            videoId = currentVideoId,
            parentMetaId = parentMetaId,
            title = title,
            season = season,
            episode = episode,
            forceRefresh = true,
        )

        var refreshedStream: StreamItem? = null
        var pollCount = 0
        while (pollCount < CREDENTIAL_REFRESH_POLL_COUNT && refreshedStream == null) {
            val state = PlayerStreamsRepository.sourceState.value
            refreshedStream = findCredentialRefreshCandidate(
                streams = state.groups.flatMap { it.streams },
                failedUrl = failedUrl,
                expectedProviderAddonId = expectedProviderAddonId,
                expectedProviderName = expectedProviderName,
                expectedStreamTitle = expectedStreamTitle,
                expectedBingeGroup = expectedBingeGroup,
            )
            if (
                refreshedStream != null ||
                state.emptyStateReason != null ||
                (!state.isAnyLoading && state.groups.isNotEmpty())
            ) {
                break
            }
            delay(CREDENTIAL_REFRESH_POLL_INTERVAL_MS)
            pollCount++
        }

        val stream = refreshedStream
        if (stream == null) {
            errorMessage = message
            controlsVisible = !playerControlsLocked
            return@launch
        }

        val refreshedUrl = stream.playableDirectUrl
        if (refreshedUrl.isNullOrBlank() || refreshedUrl == failedUrl) {
            errorMessage = message
            controlsVisible = !playerControlsLocked
            return@launch
        }

        flushWatchProgress()
        stopActiveP2pStream()
        activeSourceUrl = refreshedUrl
        activeSourceAudioUrl = null
        activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
        activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
        activeStreamType = stream.streamType
        activeSourceIdentityKey = stream.playerSourceIdentityKey()
        activeStreamTitle = stream.streamLabel
        activeStreamSubtitle = stream.streamSubtitle
        activeProviderName = stream.addonName
        activeProviderAddonId = stream.addonId
        currentStreamBingeGroup = stream.behaviorHints.bingeGroup
        activeInitialPositionMs = savedPositionMs
        activeInitialProgressFraction = null
        showSourcesPanel = false
        controlsVisible = true
    }
    return true
}

private fun findCredentialRefreshCandidate(
    streams: List<StreamItem>,
    failedUrl: String,
    expectedProviderAddonId: String?,
    expectedProviderName: String,
    expectedStreamTitle: String,
    expectedBingeGroup: String?,
): StreamItem? =
    streams
        .asSequence()
        .mapNotNull { stream ->
            val refreshedUrl = stream.playableDirectUrl?.takeIf { it.isNotBlank() && it != failedUrl }
                ?: return@mapNotNull null
            val providerMatches = if (!expectedProviderAddonId.isNullOrBlank()) {
                stream.addonId == expectedProviderAddonId
            } else {
                stream.addonName == expectedProviderName
            }
            if (!providerMatches) return@mapNotNull null

            var score = 100
            if (stream.streamLabel == expectedStreamTitle) score += 40
            if (!expectedBingeGroup.isNullOrBlank() && stream.behaviorHints.bingeGroup == expectedBingeGroup) {
                score += 20
            }
            if (refreshedUrl.hasLikelyExpiringPlaybackCredentials()) score += 5
            score to stream
        }
        .maxByOrNull { (score, _) -> score }
        ?.second

private const val CREDENTIAL_REFRESH_POLL_COUNT = 30
private const val CREDENTIAL_REFRESH_POLL_INTERVAL_MS = 500L
private const val DISCORD_PROGRESS_UPDATE_BUCKET_MS = 15_000L
private const val DISCORD_PAUSED_ANCHOR_REFRESH_MS = 5_000L
private const val DISCORD_PLAYBACK_RECONCILE_MS = 10_000L
// How often, while paused, to re-anchor the Discord progress bar to the frozen position. Small
// enough that the (live) bar never visibly creeps forward before it snaps back.
// How far into the freshly-loaded episode playback must reach before the next-episode auto-advance
// latch is released. Long enough to clear the stale end-of-file loading gap, short enough to re-arm
// well before the new episode itself ends.
private const val NEXT_EPISODE_ADVANCE_RESET_POSITION_MS = 3_000L
