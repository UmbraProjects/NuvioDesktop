package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.locallibrary.FilenameParser
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.playbackEpisodeNumber
import com.nuvio.app.features.details.playbackSeasonNumber
import com.nuvio.app.features.details.resolveSeriesEpisodePosition
import com.nuvio.app.features.discord.DiscordEpisodeArtwork
import com.nuvio.app.features.discord.DiscordPresenceSettingsRepository
import com.nuvio.app.features.discord.DiscordRichPresenceActivity
import com.nuvio.app.features.discord.DiscordRichPresenceController
import com.nuvio.app.features.discord.DiscordRichPresenceActivityType
import com.nuvio.app.features.discord.DiscordRichPresenceImageFit
import com.nuvio.app.features.discord.isExternallyFetchableArtworkUrl
import com.nuvio.app.features.metadata.AnimeArtworkService
import com.nuvio.app.features.metadata.hasAnimeNamespacePrefix
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.ChapterSkipDetector
import com.nuvio.app.features.player.skip.mergeCommunityAndChapterSkipIntervals
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.SkipLookupTarget
import com.nuvio.app.features.player.skip.resolveSkipLookupTarget
import com.nuvio.app.features.player.skip.identityKey
import com.nuvio.app.features.streams.StreamPrefetchService
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/**
 * How long a community skip lookup waits for the player to report a runtime before giving up and
 * asking without one. Sized for a slow debrid or P2P start rather than a local file.
 */
private const val SKIP_LOOKUP_DURATION_TIMEOUT_MS = 30_000L

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
        // Core playback state is invalidated synchronously by beginPlaybackAttempt before this
        // effect can observe the new URL. Resetting it here would race the replacement's early
        // attach/first-frame callbacks and could erase their attempt markers.
        isScrubbingTimeline = false
        scrubbingPositionMs = null
        liveGestureFeedback = null
        renderedGestureFeedback = null
        lockedOverlayVisible = false
        credentialRefreshJob?.cancel()
        credentialRefreshJob = null
        credentialRefreshAttemptedSourceUrl = null
        providerDiagnosticRecoveryAttemptedSourceUrl = null
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
        playbackAttemptId,
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
        val requestedAttemptId = playbackAttemptId
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
            if (
                playbackAttemptId == requestedAttemptId &&
                activeTorrentInfoHash == infoHash &&
                activeTorrentFileIdx == requestedFileIdx
            ) {
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
        val persistedAddonSelection = PlayerTrackPreferenceStorage.load(parentMetaId)
            ?.subtitleType == PersistedSubtitleSelectionType.ADDON
        if (playerSettingsUiState.addonSubtitleStartupMode == AddonSubtitleStartupMode.FAST_STARTUP &&
            !persistedAddonSelection
        ) {
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
        restorePersistedTrackPreferenceIfNeeded()
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
        // Speculative stream searching must not compete with the playback it exists to make feel
        // instant, so it stays parked for as long as the player owns the screen.
        StreamPrefetchService.setPlaybackActive(true)
        onDispose {
            StreamPrefetchService.setPlaybackActive(false)
            P2pStreamingEngine.stopStream()
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

    // The poster playback was launched with is whichever one the launching surface happened to
    // hold, and that is not always one Discord can fetch: a Library or Continue Watching row runs
    // its poster through the user's poster service (`resolveLibraryPosterUrl`), which is often
    // self-hosted, and some records simply carry no poster at all. Discord fetches artwork from its
    // own servers, so either case used to fall straight past the poster to the episode still.
    //
    // The metadata record has one, and it is **the same response the episode still came from** —
    // Cinemeta / Kitsu / the TMDB addon, a public CDN URL by construction. So ask it for the poster
    // rather than settling for the still. `MetaDetailsRepository.fetch` is cached and
    // single-flighted, and the details screen that launched playback has almost always warmed it,
    // so this is usually free and never more than one request.
    LaunchedEffect(parentMetaId, parentMetaType, poster, discordSettings.showPlaybackPresence) {
        discordMetaPosterUrl = null
        if (!discordSettings.showPlaybackPresence) return@LaunchedEffect
        // Only when the poster in hand cannot serve. A reachable one is the user's own choice of
        // art and must not be second-guessed — including a working poster-service URL.
        if (isExternallyFetchableArtworkUrl(poster)) return@LaunchedEffect
        val metaId = parentMetaId.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val metaType = parentMetaType.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        // Peek before fetch. Playback launched from the details screen leaves that meta in the
        // repository's own state, so the common path costs nothing at all; the fetch is for the
        // routes that skip the screen — Continue Watching resume, Random Play, a binge advance.
        val peeked = MetaDetailsRepository.peek(type = metaType, id = metaId)?.poster
        val resolved = peeked ?: runCatching {
            // Default enrichment on purpose. `fetch` keys its cache on `enrich=<flag>`, so asking
            // for an unenriched copy would open a third meta namespace for one field — which is
            // precisely the redundant-fetch shape the Discover work spent §22-§25 removing.
            MetaDetailsRepository.fetch(type = metaType, id = metaId)?.poster
        }.getOrNull()
        discordMetaPosterUrl = resolved?.takeIf { isExternallyFetchableArtworkUrl(it) }
    }

    // A poster the Discord image proxy is certain to be able to fetch, for native anime ids.
    //
    // Unlike `discordMetaPosterUrl` this runs even when the poster in hand looks perfectly fine,
    // because it exists to be the *fallback*: the proxy that squares a portrait poster is handed a
    // `default=` URL and serves it whenever the primary cannot be fetched, silently and with no
    // error anywhere. With the poster preference selected that default was the episode still, so
    // one unfetchable anime poster produced exactly the thing the user had switched off — and
    // anime is where unfetchable posters concentrate, addon payloads carrying dead
    // `media.kitsu.io` URLs being the common case.
    //
    // Kitsu/AniList are asked directly, which is how the app already resolves per-season anime
    // banners; both are public CDNs, service-cached, and never more than one request per title.
    LaunchedEffect(parentMetaId, activeVideoId, discordSettings.showPlaybackPresence) {
        discordAnimePosterUrl = null
        if (!discordSettings.showPlaybackPresence) return@LaunchedEffect
        val animeId = listOfNotNull(parentMetaId, activeVideoId)
            .firstOrNull { it.isNotBlank() && it.hasAnimeNamespacePrefix() }
            ?: return@LaunchedEffect
        val resolved = runCatching { AnimeArtworkService.seasonPoster(animeId) }.getOrNull()
        discordAnimePosterUrl = resolved?.takeIf { isExternallyFetchableArtworkUrl(it) }
    }

    // Metadata-less direct playback (a pasted stream URL / dropped file) carries no poster, so its
    // Discord presence would fall back to the Nuvio logo. Best-effort resolve real art from the
    // parsed title via the user's search addons so it matches how library playback presents.
    LaunchedEffect(parentMetaId, title, parentMetaType, activeEpisodeNumber) {
        adHocArtworkImageUrl = null
        val hasArgArtwork = listOf(poster, background).any {
            it?.trim()?.let { url -> url.startsWith("https://") || url.startsWith("http://") } == true
        }
        if (parentMetaId.isNotBlank() || hasArgArtwork) return@LaunchedEffect
        val lookupTitle = title.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val isSeries = parentMetaType.equals("series", ignoreCase = true) || activeEpisodeNumber != null
        val resolved = PlaybackArtworkResolver.resolvePosterUrl(
            title = lookupTitle,
            year = releaseYear,
            isSeries = isSeries,
        )
        if (resolved != null) adHocArtworkImageUrl = resolved
    }

    LaunchedEffect(
        discordSettings.showPlaybackPresence,
        discordSettings.episodeArtwork,
        title,
        activeVideoId,
        activeSeasonNumber,
        activeEpisodeNumber,
        activeEpisodeTitle,
        presenceReleaseYear,
        presenceReconcileTick,
        pausedAnchorTick,
        poster,
        discordMetaPosterUrl,
        discordAnimePosterUrl,
        activeEpisodeThumbnail,
        background,
        adHocArtworkImageUrl,
        playbackSnapshot.isLoading,
        playbackSnapshot.isPlaying,
        playbackSnapshot.isEnded,
        playbackSnapshot.durationMs,
        playbackSnapshot.playbackSpeed,
        positionBucket,
        errorMessage,
        isProviderDiagnosticVideoPlayback,
    ) {
        val presenceTitle = discordPresenceTitle()
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
                    imageUrl = discordPresenceImageUrl(discordSettings.episodeArtwork),
                    fallbackImageUrl = discordPresenceFallbackImageUrl(discordSettings.episodeArtwork),
                    imageFit = discordPresenceImageFit(discordSettings.episodeArtwork),
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
                imageUrl = discordPresenceImageUrl(discordSettings.episodeArtwork),
                fallbackImageUrl = discordPresenceFallbackImageUrl(discordSettings.episodeArtwork),
                imageFit = discordPresenceImageFit(discordSettings.episodeArtwork),
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
        playbackAttemptId,
        activeSourceUrl,
        playerSettingsUiState.streamFailoverEnabled,
        playerSettingsUiState.streamFailoverTimeoutSeconds,
        isProviderDiagnosticVideoPlayback,
    ) {
        if (!playerSettingsUiState.streamFailoverEnabled) return@LaunchedEffect
        if (activeSourceUrl.isBlank() || isProviderDiagnosticVideoPlayback) return@LaunchedEffect
        val watchedUrl = activeSourceUrl
        val watchedAttemptId = playbackAttemptId
        val timeoutMs = playerSettingsUiState.streamFailoverTimeoutSeconds.coerceAtLeast(1) * 1000L
        // The desktop controller is created before its native Canvas has received a full-size
        // paint. Do not count that UI lifecycle wait against the source: no loadfile request exists
        // until the platform surface reports that this exact URL is being attached.
        while (
            playerAttachedSourceUrl != watchedUrl ||
            playerAttachedAttemptId != watchedAttemptId
        ) {
            if (
                playbackAttemptId != watchedAttemptId ||
                activeSourceUrl != watchedUrl ||
                errorMessage != null ||
                failoverInProgress
            ) {
                return@LaunchedEffect
            }
            delay(STREAM_FAILOVER_WATCHDOG_STEP_MS)
        }
        var elapsed = 0L
        var started = false
        while (elapsed < timeoutMs) {
            // A debrid URL can open and publish duration/position while waiting for the actual
            // torrent. Only mpv's first rendered-frame event proves this source really started.
            if (
                playerStartedSourceUrl == watchedUrl &&
                playerStartedAttemptId == watchedAttemptId
            ) {
                started = true
                break
            }
            // The error path or a source change already took over.
            if (
                playbackAttemptId != watchedAttemptId ||
                activeSourceUrl != watchedUrl ||
                errorMessage != null ||
                failoverInProgress
            ) return@LaunchedEffect
            delay(STREAM_FAILOVER_WATCHDOG_STEP_MS)
            elapsed += STREAM_FAILOVER_WATCHDOG_STEP_MS
        }
        if (!started &&
            playbackAttemptId == watchedAttemptId &&
            activeSourceUrl == watchedUrl &&
            playerStartedAttemptId != watchedAttemptId &&
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
            playbackAttemptId == watchedAttemptId &&
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
                    put("attemptId", watchedAttemptId)
                    put("continuousPlaybackMs", sustainedPlaybackMs)
                    put("clearedTriedSourceCount", failoverTriedIdentityKeys.size)
                })
                failoverTriedIdentityKeys.clear()
                return@LaunchedEffect
            }
        }
    }
}

/**
 * What Discord shows as the title, from the best source that actually has one.
 *
 * A blank title is the single condition that suppresses the presence entirely, so it must be a
 * genuine "we know nothing about this" rather than one unlucky lookup. The launch title is empty
 * whenever whatever started playback had no resolved metadata to name it with — the case anime
 * hits most, because a season addressed by a per-entry id (or one whose season is not TMDB's
 * season 1) is exactly what a meta addon fails to answer for. The loaded meta is the same answer
 * arriving later, and the stream's own release name is the last resort the direct-play path
 * already relies on: a parsed filename beats no presence at all.
 */
private fun PlayerScreenRuntime.discordPresenceTitle(): String? =
    resolveDiscordPresenceTitle(
        argsTitle = title,
        metaName = discordPresenceMetadata()?.name,
        streamReleaseName = streamTitle,
        isEpisode = activeEpisodeNumber != null,
    )

internal fun resolveDiscordPresenceTitle(
    argsTitle: String?,
    metaName: String?,
    streamReleaseName: String?,
    isEpisode: Boolean,
): String? {
    argsTitle?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    metaName?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

    val releaseName = streamReleaseName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parsed = if (isEpisode) {
        FilenameParser.parseEpisode(releaseName).showTitle
    } else {
        FilenameParser.parseTitle(releaseName).title
    }
    return parsed?.trim()?.takeIf { it.isNotBlank() }
        ?: FilenameParser.cleanTitle(releaseName).trim().takeIf { it.isNotBlank() }
}

/**
 * The artwork this playback can offer Discord, best first.
 *
 * Poster first by default, then the other posters, then backdrop, and the episode still last — but
 * every candidate is checked for
 * whether Discord can reach it rather than merely for looking like a URL. A custom poster service
 * (PostersPlus / RPDB) is often self-hosted, and such a URL passed the old scheme-only test and then
 * permanently shadowed the episode still and backdrop behind it, both of which are public CDN URLs
 * that would have worked. That is why Rich Presence lost its image the moment a poster source was
 * set.
 *
 * **[discordMetaPosterUrl] sits directly behind the poster, ahead of the still**, and is what stops
 * an unreachable or missing poster from demoting the whole presence to an episode still. It is the
 * poster off the title's own metadata record — the same response the still came from — so it is a
 * public CDN URL by construction. Resolved lazily and only when needed; see the effect that fills
 * it.
 *
 * **[DiscordEpisodeArtwork] moves the still to the front, and only for an episode.** It is a
 * preference over an ordering, never a filter: whichever the user asks for, the others stay behind
 * it, because an unreachable or absent first choice has to degrade to something rather than to the
 * Nuvio logo.
 *
 * **With the poster preference the still goes last, behind every other artwork.** The tail of this
 * list is not decoration: the second entry becomes the image proxy's `default=`, which it serves
 * silently whenever the first cannot be fetched, and the RPC transport steps down to it when
 * Discord rejects the first outright. Ordering the still second meant a single unfetchable poster
 * put an episode thumbnail on screen for a user who had explicitly asked not to see one, with
 * nothing anywhere reporting that a substitution had happened. A backdrop is at least the title's
 * own art; the still is the one thing that preference rules out, so it degrades to that only when
 * there is nothing else at all.
 */
private fun PlayerScreenRuntime.discordPresenceArtworkCandidates(
    episodeArtwork: DiscordEpisodeArtwork,
): List<String> {
    val preferEpisodeStill = episodeArtwork == DiscordEpisodeArtwork.EpisodeThumbnail &&
        activeEpisodeNumber != null
    val ordered = if (preferEpisodeStill) {
        listOf(
            activeEpisodeThumbnail,
            poster,
            discordMetaPosterUrl,
            discordAnimePosterUrl,
            background,
            adHocArtworkImageUrl,
        )
    } else {
        listOf(
            poster,
            discordMetaPosterUrl,
            discordAnimePosterUrl,
            adHocArtworkImageUrl,
            background,
            activeEpisodeThumbnail,
        )
    }
    return ordered
        .mapNotNull { it?.trim() }
        .filter { isExternallyFetchableArtworkUrl(it) }
        .distinct()
}

private fun PlayerScreenRuntime.discordPresenceImageUrl(episodeArtwork: DiscordEpisodeArtwork): String? =
    discordPresenceArtworkCandidates(episodeArtwork).firstOrNull()

/**
 * The artwork to fall back to when the chosen one is reachable but has nothing to serve — a poster
 * service answering 404 for a title it has no art for, which no check here can predict.
 */
private fun PlayerScreenRuntime.discordPresenceFallbackImageUrl(
    episodeArtwork: DiscordEpisodeArtwork,
): String? = discordPresenceArtworkCandidates(episodeArtwork).drop(1).firstOrNull()

private fun PlayerScreenRuntime.discordPresenceImageFit(
    episodeArtwork: DiscordEpisodeArtwork,
): DiscordRichPresenceImageFit {
    // Posters (args or resolved for ad-hoc playback) are portrait and must not be centre-cropped;
    // episode thumbnails and backdrops are landscape and fill the square cleanly. Derived from
    // what was actually chosen, so it follows the preference without being told about it.
    val chosen = discordPresenceImageUrl(episodeArtwork)
    val isPortraitPoster = chosen != null &&
        (
            chosen == poster?.trim() ||
                chosen == discordMetaPosterUrl?.trim() ||
                chosen == discordAnimePosterUrl?.trim() ||
                chosen == adHocArtworkImageUrl?.trim()
            )
    return if (isPortraitPoster) DiscordRichPresenceImageFit.Contain else DiscordRichPresenceImageFit.Cover
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
    return extractDiscordReleaseYear(discordPresenceMetadata()?.releaseInfo)
}

/**
 * Metadata for the active presence. Anime providers may answer a MAL request with a Kitsu/IMDb
 * identity, so exact response-ID matching alone can discard the valid title. The repository cache
 * retains that response under the original request key and is safe to consult without performing
 * another fetch or changing the metadata route.
 */
private fun PlayerScreenRuntime.discordPresenceMetadata() =
    metaUiState.meta?.takeIf { meta ->
        meta.id == parentMetaId && meta.type.equals(parentMetaType, ignoreCase = true)
    } ?: MetaDetailsRepository.peek(parentMetaType, parentMetaId)

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
        // Paused hides sooner: the chrome is only in the way of the metadata overlay that follows
        // it, so the whole sequence lands faster. Playback keeps the longer grace period (and the
        // web HUD's own auto-hide timer is on the same 5.5s).
        delay(if (playbackSnapshot.isPlaying) 5500 else 3500)
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
                // Playing to not-playing without ending is a pause, not a stop. Providers with a
                // distinct pause action keep the session resumable instead of closing it.
                flushWatchProgress(paused = true)
            }
        }

        if (playbackSnapshot.isPlaying && pendingScrobbleStartAfterSeek) {
            pendingScrobbleStartAfterSeek = false
            emitTrackingScrobbleStart()
        } else if (!previousIsPlaying && playbackSnapshot.isPlaying) {
            emitTrackingScrobbleStart()
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

    LaunchedEffect(activeVideoId, activeSeasonNumber, activeEpisodeNumber, parentMetaId, parentMetaType) {
        skipIntervals = emptyList()
        playerChapters = emptyList()
        communitySkipIntervals = emptyList()
        chapterSkipIntervals = emptyList()
        activeSkipInterval = null
        skipIntervalDismissed = false
        autoAcceptedSkipIntervals.clear()
        showNextEpisodeCard = false
        nextEpisodeThresholdStableSamples = 0
        nextEpisodeAutoPlayJob?.cancel()
        nextEpisodeAutoPlaySearching = false

        val season = activeSeasonNumber
        val episode = activeEpisodeNumber
        val vid = activeVideoId ?: return@LaunchedEffect

        launch {
            // Which provider chain this playback belongs to is decided by its id, before anything
            // is fetched — an id that resolves to nothing stops here rather than waiting out the
            // duration timeout only to look nothing up. Ids outside the namespaces the providers
            // know (`tmdb:`, `tvdb:`, an addon's own scheme) are resolved rather than dropped, so
            // the feature does not depend on which metadata addon the user happens to run.
            val target = resolveSkipLookupTarget(
                videoId = vid,
                parentMetaId = parentMetaId,
                contentType = contentType ?: parentMetaType,
                season = season,
                episode = episode,
            ) ?: return@launch
            // SkipDB matches its timings against the runtime of the exact cut being played, so wait
            // for the player to report one instead of asking straight away. Without a runtime every
            // answer is duration-agnostic and a re-cut release is indistinguishable from the one the
            // timings came from. Waiting cannot cost a prompt: playback only reaches a segment well
            // after the duration is known, and a source that never reports one still falls through.
            val durationSeconds = withTimeoutOrNull(SKIP_LOOKUP_DURATION_TIMEOUT_MS) {
                snapshotFlow { playbackSnapshot.durationMs }.first { it > 0L }
            }?.let { durationMs -> durationMs / 1000L }
            val intervals = when (target) {
                is SkipLookupTarget.Episode -> SkipIntroRepository.getSkipIntervals(
                    imdbId = target.imdbId,
                    season = target.season,
                    episode = target.episode,
                    durationSeconds = durationSeconds,
                )
                // A film has no season or episode to look up by, and only SkipDB carries anything
                // for one, so it takes a separate path rather than the episode chain.
                is SkipLookupTarget.Movie -> SkipIntroRepository.getMovieSkipIntervals(
                    imdbId = target.imdbId,
                    durationSeconds = durationSeconds,
                )
                is SkipLookupTarget.Anime -> SkipIntroRepository.getSkipIntervalsForAnime(
                    namespace = target.namespace,
                    id = target.id,
                    episode = target.episode,
                    durationSeconds = durationSeconds,
                )
                is SkipLookupTarget.AnimeMovie -> SkipIntroRepository.getMovieSkipIntervalsForAnime(
                    namespace = target.namespace,
                    id = target.id,
                    durationSeconds = durationSeconds,
                )
            }
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

    // Auto-accept. Deliberately keyed on the segment rather than on position: the prompt is
    // accepted once, when playback first enters a segment this mode trusts, and rewinding back
    // into an already-accepted one leaves the viewer where they aimed.
    LaunchedEffect(
        activeSkipInterval,
        playerSettingsUiState.skipAutoAcceptMode,
        initialLoadCompleted,
        playbackSnapshot.isPlaying,
        pausedOverlayVisible,
    ) {
        val interval = activeSkipInterval ?: return@LaunchedEffect
        if (isProviderDiagnosticVideoPlayback) return@LaunchedEffect
        // Mirrors the gates the prompt itself is shown behind — nothing is skipped silently in a
        // state where the viewer would never have been offered the button.
        if (!initialLoadCompleted || !playbackSnapshot.isPlaying || pausedOverlayVisible) {
            return@LaunchedEffect
        }
        if (!playerSettingsUiState.skipAutoAcceptMode.accepts(interval)) return@LaunchedEffect
        if (!autoAcceptedSkipIntervals.add(interval.identityKey())) return@LaunchedEffect
        acceptSkipInterval(interval)
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
        playbackAttemptId,
        playerStartedAttemptId,
        activeVideoId,
        nextEpisodeAdvanceTargetVideoId,
        playbackSnapshot.isEnded,
        playbackSnapshot.positionMs >= NEXT_EPISODE_ADVANCE_RESET_POSITION_MS,
    ) {
        if (
            shouldReleaseNextEpisodeAdvanceLatch(
                advanceInProgress = nextEpisodeAdvanceInProgress,
                targetVideoId = nextEpisodeAdvanceTargetVideoId,
                activeVideoId = activeVideoId,
                activeAttemptId = playbackAttemptId,
                startedAttemptId = playerStartedAttemptId,
                isEnded = playbackSnapshot.isEnded,
                positionMs = playbackSnapshot.positionMs,
                minimumPositionMs = NEXT_EPISODE_ADVANCE_RESET_POSITION_MS,
            )
        ) {
            BingeAdvanceLog.i {
                "advance latch released (next episode playing) " +
                    "attemptId=$playbackAttemptId videoId=$activeVideoId pos=${playbackSnapshot.positionMs}"
            }
            nextEpisodeAdvanceInProgress = false
            nextEpisodeAdvanceTargetVideoId = null
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
        nextEpisodeAdvanceTargetVideoId = null
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
    val failedAttemptId = playbackAttemptId
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
            if (
                playbackAttemptId != failedAttemptId ||
                activeVideoId != currentVideoId ||
                activeSourceUrl != failedUrl
            ) return@launch
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
        if (
            playbackAttemptId != failedAttemptId ||
            activeVideoId != currentVideoId ||
            activeSourceUrl != failedUrl
        ) return@launch
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
        beginPlaybackAttempt()
        activeSourceUrl = refreshedUrl
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
