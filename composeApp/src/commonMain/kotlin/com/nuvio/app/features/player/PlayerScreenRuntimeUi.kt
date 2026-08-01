package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import com.nuvio.app.core.ui.copyPlainTextToClipboard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioToastPlacement
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.playbackEpisodeNumber
import com.nuvio.app.features.details.playbackSeasonNumber
import com.nuvio.app.features.details.resolveSeriesEpisodePosition
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.p2p.formatP2pMegabytes
import com.nuvio.app.features.p2p.formatP2pSpeed
import com.nuvio.app.features.player.skip.SKIP_SEGMENT_TYPES
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamScore
import com.nuvio.app.features.streams.StreamScoreContext
import com.nuvio.app.features.streams.StreamScoreContexts
import com.nuvio.app.features.streams.StreamScoreProfile
import com.nuvio.app.features.streams.StreamScoreRepository
import com.nuvio.app.features.streams.StreamScorer
import com.nuvio.app.features.streams.isSelectableForPlayback
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.isDesktop
import com.nuvio.app.isIos
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerScreenRuntime.RenderPlayerRuntimeUi() {
    val runtime = this
    val playbackFailedToast = stringResource(Res.string.player_error_playback_failed)
    val unableToPlayStreamMessage = stringResource(Res.string.player_error_unable_to_play_stream)
    val debridRateLimitedToast = stringResource(Res.string.player_error_debrid_rate_limited)
    val failoverTryingNextToast = stringResource(Res.string.player_failover_trying_next)
    val streamBadgeSettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val displayedPositionMs = scrubbingPositionMs ?: playbackSnapshot.positionMs
    val seasonNumber = activeSeasonNumber
    val episodeNumber = activeEpisodeNumber
    val episodeTitle = activeEpisodeTitle
    val isEpisode = seasonNumber != null && episodeNumber != null
    val currentGestureFeedback = liveGestureFeedback ?: gestureFeedback
    val isP2pPlaybackActive = activeTorrentInfoHash != null
    val p2pStats = p2pStreamingState as? P2pStreamingState.Streaming
    val p2pPeerInfo = p2pStats?.let { stats ->
        org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_peer_info,
            stats.seeds,
            stats.peers,
        )
    }
    val p2pDownloadSpeed = p2pStats?.let { formatP2pSpeed(it.downloadSpeed) }
    val p2pInitialLoadingMessage = when {
        !isP2pPlaybackActive || initialLoadCompleted -> null
        p2pStreamingState is P2pStreamingState.Connecting -> {
            org.jetbrains.compose.resources.stringResource(
                nuvio.composeapp.generated.resources.Res.string.player_torrent_connecting_peers,
            )
        }
        p2pStats != null -> {
            if (p2pSettingsUiState.hideTorrentStats) {
                null
            } else {
                org.jetbrains.compose.resources.stringResource(
                    nuvio.composeapp.generated.resources.Res.string.player_torrent_buffered_status,
                    formatP2pMegabytes(p2pStats.preloadedBytes),
                    p2pPeerInfo.orEmpty(),
                    p2pDownloadSpeed.orEmpty(),
                )
            }
        }
        else -> org.jetbrains.compose.resources.stringResource(
            nuvio.composeapp.generated.resources.Res.string.player_torrent_starting_engine,
        )
    }
    val p2pInitialLoadingProgress = when {
        !isP2pPlaybackActive || initialLoadCompleted || p2pStats == null -> null
        else -> (p2pStats.preloadedBytes.toFloat() / P2pInitialPreloadTargetBytes.toFloat()).coerceIn(0f, 1f)
    }
    val showP2pRebufferStats = isP2pPlaybackActive &&
        initialLoadCompleted &&
        playbackSnapshot.isLoading &&
        p2pStats != null &&
        !p2pSettingsUiState.hideTorrentStats
    val p2pRebufferMessage = when {
        !showP2pRebufferStats -> null
        else -> {
            val bufferedSeconds = ((playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs) / 1000L)
                .coerceAtLeast(0L)
            "${bufferedSeconds}s buffered · ${p2pPeerInfo.orEmpty()} · ${p2pDownloadSpeed.orEmpty()}"
        }
    }
    val p2pRebufferProgress = when {
        !showP2pRebufferStats -> null
        else -> {
            val bufferedSeconds = ((playbackSnapshot.bufferedPositionMs - playbackSnapshot.positionMs) / 1000f)
                .coerceAtLeast(0f)
            (bufferedSeconds / 10f).coerceIn(0f, 1f)
        }
    }
    val playerSurfaceSourceUrl = if (isP2pPlaybackActive) p2pResolvedSourceUrl else activeSourceUrl
    val openingOverlayWanted = playerSettingsUiState.showLoadingOverlay &&
        !initialLoadCompleted &&
        errorMessage == null
    val episodeText = if (seasonNumber != null && episodeNumber != null && !episodeTitle.isNullOrBlank()) {
        stringResource(
            Res.string.compose_player_episode_title_format,
            seasonNumber,
            episodeNumber,
            episodeTitle.orEmpty(),
        )
    } else {
        ""
    }
    val allFilterLabel = stringResource(Res.string.collections_tab_all)
    val playingLabel = stringResource(Res.string.compose_player_playing)
    val sourceFilters = buildPlayerControlFilters(
        allLabel = allFilterLabel,
        selectedFilter = null,
    )
    val sourceItems = buildPlayerControlSourceItems()
    val episodeItems = buildPlayerControlEpisodeItems()
    val episodeSeasons = buildPlayerControlSeasonItems(episodeItems)
    val episodeStreamFilters = buildPlayerControlEpisodeStreamFilters(
        allLabel = allFilterLabel,
        selectedFilter = null,
    )
    val episodeStreamItems = buildPlayerControlEpisodeStreamItems()
    val playerControlAddonSubtitles = buildPlayerControlAddonSubtitleItems()
    // Only override the overlay's native built-in list while the preferred-languages filter is on;
    // otherwise leave the list empty so the overlay keeps using its live native track list.
    val builtInSubtitleFilterActive = subtitleStyle.showOnlyPreferredLanguages ||
        playerSettingsUiState.effectiveRejectedSubtitleKeywords().isNotEmpty()
    val playerControlBuiltInSubtitles = if (builtInSubtitleFilterActive) {
        buildPlayerControlBuiltInSubtitleItems()
    } else {
        emptyList()
    }
    val audioTrackFilterActive = playerSettingsUiState.rejectedAudioKeywords.isNotEmpty()
    val playerControlAudioTracks = if (audioTrackFilterActive) {
        buildPlayerControlAudioTrackItems()
    } else {
        emptyList()
    }
    val playerControlAutoSyncCues = buildPlayerControlSubtitleCueItems()
    val themeColors = MaterialTheme.nuvio.colors
    val selectedEpisodeLabel = episodeStreamsPanelState.selectedEpisode?.let { selected ->
        val selectedCode = selected.playerControlsEpisodeCode()
        buildString {
            append(selectedCode)
            if (selected.title.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(selected.title)
            }
        }
    }.orEmpty()
    val nativeSkipInterval = activeSkipInterval.takeIf {
        !isProviderDiagnosticVideoPlayback && initialLoadCompleted && !pausedOverlayVisible
    }
    // A pending manual switch (next-episode button / episode selector) shows the card as loading
    // feedback for the episode being loaded — which may not be the sequential next — so it takes
    // precedence over the end-of-episode card's info.
    val nextEpisodeForControls = (manualEpisodeSwitchInfo ?: nextEpisodeInfo).takeIf {
        !isProviderDiagnosticVideoPlayback && isSeries &&
            (showNextEpisodeCard || manualEpisodeSwitchInfo != null)
    }
    // When the pending switch is a non-sequential episode (episode selector / previous), the card
    // is pure loading feedback: its Play button would fire "playNextEpisode" (the sequential next),
    // which is the wrong target — so suppress it. The sequential next-episode/auto-advance card
    // keeps its Play button (skip countdown / play now).
    val manualSwitchIsNonSequential = manualEpisodeSwitchInfo != null &&
        manualEpisodeSwitchInfo?.videoId != nextEpisodeInfo?.videoId
    val nextEpisodeStatus = when {
        nextEpisodeForControls == null -> ""
        !nextEpisodeForControls.hasAired && !nextEpisodeForControls.unairedMessage.isNullOrBlank() ->
            nextEpisodeForControls.unairedMessage.orEmpty()
        nextEpisodeAutoPlaySearching -> stringResource(Res.string.player_next_episode_finding_source)
        !nextEpisodeAutoPlaySourceName.isNullOrBlank() && nextEpisodeAutoPlayCountdown != null ->
            stringResource(
                Res.string.player_next_episode_playing_via_countdown,
                nextEpisodeAutoPlaySourceName.orEmpty(),
                nextEpisodeAutoPlayCountdown ?: 0,
            )
        else -> ""
    }
    var isAnimeContent by remember(args.parentMetaId) {
        mutableStateOf(
            AnimeContentCache.isAnime(args.parentMetaId) ||
            AnimeContentCache.isAnime(args.videoId) ||
            args.parentMetaType.equals("anime", ignoreCase = true) ||
            args.contentType?.equals("anime", ignoreCase = true) == true ||
            (args.watchProgressSource == "simkl" && args.parentMetaId.startsWith("simkl:", ignoreCase = true))
        )
    }

    LaunchedEffect(args.parentMetaId) {
        // Names the title for the readers that build language targets without a meta id — mpv's
        // alang/slang options and the player's subtitle-list filter.
        OriginalLanguageCache.setCurrent(args.parentMetaId)
        // Continue-watching and auto-advance both reach the player without the detail screen ever
        // having been opened, so an "Original" preference may still need this title's language.
        val needsOriginalLanguage = playerSettingsUiState.usesOriginalLanguagePreference() &&
            OriginalLanguageCache.languageFor(args.parentMetaId) == null
        if (!isAnimeContent || needsOriginalLanguage) {
            val meta = MetaDetailsRepository.fetchLightweightMeta(args.parentMetaType, args.parentMetaId)
            if (meta != null) {
                OriginalLanguageCache.record(args.parentMetaId, meta.language)
                if (!meta.genres.isNullOrEmpty()) {
                    AnimeContentCache.record(args.parentMetaId, meta.genres)
                    if (AnimeContentCache.isAnime(args.parentMetaId)) {
                        isAnimeContent = true
                    }
                }
            }
        }
    }

    val playerControlsState = PlayerControlsState(
        title = title,
        episodeText = episodeText,
        streamTitle = activeStreamTitle,
        providerName = activeProviderName,
        pauseOverlayWatchingLabel = stringResource(Res.string.compose_player_youre_watching),
        pauseOverlayLogo = logo,
        pauseOverlayEpisodeInfo = if (seasonNumber != null && episodeNumber != null) {
            stringResource(Res.string.compose_player_episode_code_full, seasonNumber, episodeNumber)
        } else if (playerSettingsUiState.desktopPauseOverlaySourceEnabled) {
            // Movies borrow this line for the provider name; the source row now says it better, so
            // don't print it twice.
            ""
        } else {
            activeProviderName
        },
        pauseOverlayEpisodeTitle = activeEpisodeTitle.orEmpty(),
        // Only a real synopsis belongs here — the raw stream/release name (activeStreamSubtitle)
        // reads as messy clutter in the pause card, so it is intentionally not used as a fallback.
        pauseOverlayDescription = activePauseDescription.orEmpty(),
        pauseOverlaySourceEnabled = playerSettingsUiState.desktopPauseOverlaySourceEnabled,
        resizeModeLabel = stringResource(resizeMode.labelRes),
        playbackSpeedLabel = formatPlaybackSpeedLabel(playbackSnapshot.playbackSpeed),
        subtitlesLabel = stringResource(Res.string.compose_player_subs),
        audioLabel = stringResource(Res.string.compose_player_audio),
        sourcesLabel = stringResource(Res.string.compose_player_sources),
        episodesLabel = stringResource(Res.string.compose_player_episodes),
        externalPlayerLabel = stringResource(Res.string.streams_open_external_player),
        playLabel = stringResource(Res.string.detail_btn_play),
        pauseLabel = stringResource(Res.string.compose_action_pause),
        closeLabel = stringResource(Res.string.compose_player_close),
        lockLabel = stringResource(Res.string.compose_player_lock_controls),
        unlockLabel = stringResource(Res.string.compose_player_unlock_controls),
        submitIntroLabel = stringResource(Res.string.submit_intro_action),
        videoSettingsLabel = stringResource(Res.string.player_action_video_settings),
        pictureInPictureLabel = stringResource(Res.string.compose_player_picture_in_picture),
        pictureInPictureActive = pictureInPictureActive,
        desktopHdrModeLabel = playerSettingsUiState.desktopHdrMode.label,
        desktopColorProfileLabel = playerSettingsUiState.desktopColorProfile.label,
        // Shows what is actually in effect: a session force wins; otherwise the persisted preset
        // only counts when it would auto-apply (auto-detect on + detected anime), else "Off".
        desktopAnimeModeLabel = playerSettingsUiState.desktopAnimeSessionOverride?.label
            ?: if (playerSettingsUiState.desktopAnimeModeAutoEnabled && isAnimeContent) {
                playerSettingsUiState.desktopAnimeMode.label
            } else {
                DesktopAnimeMode.Off.label
            },
        desktopAnimeSvpEnabled = playerSettingsUiState.desktopAnimeSvpEnabled,
        playbackInfoPanelEnabled = playerSettingsUiState.desktopPlaybackInfoPanelEnabled,
        activeSubtitleLabel = activePlaybackSubtitleLabel(),
        seekThumbnailsEnabled = playerSettingsUiState.desktopBufferPreset != DesktopBufferPreset.Metered,
        tapToUnlockLabel = stringResource(Res.string.compose_player_tap_to_unlock),
        playbackErrorTitle = stringResource(Res.string.compose_player_playback_error),
        playbackErrorMessage = errorMessage.orEmpty(),
        playbackErrorActionLabel = stringResource(Res.string.compose_player_go_back),
        sourcesPanelTitle = stringResource(Res.string.compose_player_panel_sources),
        episodesPanelTitle = stringResource(Res.string.compose_player_panel_episodes),
        streamsPanelTitle = stringResource(Res.string.compose_player_panel_streams),
        allFilterLabel = allFilterLabel,
        reloadLabel = stringResource(Res.string.compose_action_reload),
        backLabel = stringResource(Res.string.action_back),
        panelCloseLabel = stringResource(Res.string.action_close),
        cancelLabel = stringResource(Res.string.action_cancel),
        playingLabel = playingLabel,
        noStreamsLabel = stringResource(Res.string.compose_player_no_streams_found),
        noEpisodesLabel = stringResource(Res.string.compose_player_no_episodes_available),
        submitIntroPanelTitle = stringResource(Res.string.submit_intro_title),
        submitIntroSegmentTypeLabel = stringResource(Res.string.submit_intro_segment_type_label),
        submitIntroSegmentIntroLabel = stringResource(Res.string.submit_intro_segment_intro),
        submitIntroSegmentRecapLabel = stringResource(Res.string.submit_intro_segment_recap),
        submitIntroSegmentOutroLabel = stringResource(Res.string.submit_intro_segment_outro),
        submitIntroSegmentPreviewLabel = stringResource(Res.string.submit_intro_segment_preview),
        submitIntroStartTimeLabel = stringResource(Res.string.submit_intro_start_time_label),
        submitIntroEndTimeLabel = stringResource(Res.string.submit_intro_end_time_label),
        submitIntroCaptureLabel = stringResource(Res.string.submit_intro_capture_button),
        submitIntroSubmitLabel = stringResource(Res.string.submit_intro_button_submit),
        p2pConsentTitle = stringResource(Res.string.p2p_consent_title),
        p2pConsentBody = stringResource(Res.string.p2p_consent_body),
        p2pConsentEnableLabel = stringResource(Res.string.p2p_consent_enable),
        p2pConsentCancelLabel = stringResource(Res.string.p2p_consent_cancel),
        subtitlesPanelTitle = stringResource(Res.string.compose_player_subtitles),
        subtitleBuiltInTabLabel = stringResource(Res.string.compose_player_built_in),
        subtitleAddonsTabLabel = stringResource(Res.string.addon_title),
        subtitleStyleTabLabel = stringResource(Res.string.compose_player_style),
        noneLabel = stringResource(Res.string.compose_player_none),
        fetchSubtitlesLabel = stringResource(Res.string.compose_player_fetch_subtitles),
        subtitleDelayLabel = stringResource(Res.string.compose_player_subtitle_delay),
        resetLabel = stringResource(Res.string.compose_player_reset),
        autoSyncLabel = stringResource(Res.string.compose_player_auto_sync),
        reloadSmallLabel = stringResource(Res.string.compose_player_reload),
        captureLineLabel = stringResource(Res.string.compose_player_capture_line),
        selectAddonSubtitleFirstLabel = stringResource(Res.string.compose_player_select_addon_subtitle_first),
        loadingSubtitleLinesLabel = stringResource(Res.string.compose_player_loading_lines),
        fontSizeLabel = stringResource(Res.string.compose_player_font_size),
        outlineLabel = stringResource(Res.string.compose_player_outline),
        outlineWidthLabel = stringResource(Res.string.compose_player_outline_width),
        shadowLabel = stringResource(Res.string.compose_player_shadow),
        shadowOffsetLabel = stringResource(Res.string.compose_player_shadow_offset),
        shadowColorLabel = stringResource(Res.string.compose_player_shadow_color),
        shadowIntensityLabel = stringResource(Res.string.compose_player_shadow_intensity),
        blurLabel = stringResource(Res.string.compose_player_blur),
        boldLabel = stringResource(Res.string.compose_player_bold),
        italicLabel = stringResource(Res.string.compose_player_italic),
        bottomOffsetLabel = stringResource(Res.string.compose_player_bottom_offset),
        colorLabel = stringResource(Res.string.compose_player_color),
        textOpacityLabel = stringResource(Res.string.compose_player_text_opacity),
        outlineColorLabel = stringResource(Res.string.compose_player_outline_color),
        backgroundColorLabel = stringResource(Res.string.compose_player_background_color),
        resetDefaultsLabel = stringResource(Res.string.compose_player_reset_defaults),
        onLabel = stringResource(Res.string.compose_action_on),
        offLabel = stringResource(Res.string.compose_action_off),
        themeAccentColor = themeColors.accent.toCssColorString(),
        themeAccentStrongColor = themeColors.accentStrong.toCssColorString(),
        themeOnAccentColor = themeColors.onAccent.toCssColorString(),
        themeFocusColor = themeColors.focusRing.toCssColorString(),
        themeSelectedSurfaceColor = themeColors.accent.copy(alpha = 0.24f).toCssColorString(),
        themeSelectedSurfaceHoverColor = themeColors.accent.copy(alpha = 0.34f).toCssColorString(),
        themeSelectedRingColor = themeColors.accent.copy(alpha = 0.35f).toCssColorString(),
        themeTimelineFillColor = themeColors.playerTimelineFill.toCssColorString(),
        themeTimelineTrackColor = themeColors.playerTimelineTrack.toCssColorString(),
        themeBufferingColor = themeColors.playerBuffering.toCssColorString(),
        themeBufferingTrackColor = themeColors.playerBuffering.copy(alpha = 0.28f).toCssColorString(),
        themeControlForegroundColor = themeColors.playerControlsForeground.toCssColorString(),
        isPlaying = playbackSnapshot.isPlaying,
        isLoading = playbackSnapshot.isLoading,
        isLocked = playerControlsLocked,
        lockedOverlayVisible = lockedOverlayVisible,
        controlsVisible = controlsVisible && !playerControlsLocked,
        mouseMoveRevealsControlsEnabled = playerSettingsUiState.mouseMoveRevealsControlsEnabled,
        streamFailoverEnabled = playerSettingsUiState.streamFailoverEnabled,
        legacyHudEnabled = playerSettingsUiState.desktopLegacyHudEnabled,
        alwaysShowClock = playerSettingsUiState.desktopAlwaysShowClockEnabled,
        playbackSpeedFineIncrementsEnabled = playerSettingsUiState.desktopPlaybackSpeedFineIncrementsEnabled,
        uiScalePercent = playerSettingsUiState.desktopUiScalePercent,
        sourceNotchPosition = playerSettingsUiState.desktopSourceNotchPosition.name.lowercase(),
        parentalWarnings = parentalWarnings,
        showParentalGuide = showParentalGuide,
        // Films are submittable too: SkipDB takes them with no season or episode, and holds opening
        // title sequences and end credits for them.
        showSubmitIntro = playerSettingsUiState.introSubmitEnabled &&
            (
                playerSettingsUiState.skipDbApiKey.isNotBlank() ||
                    playerSettingsUiState.introDbApiKey.isNotBlank()
                ) &&
            !activeSubmitIntroImdbId().isNullOrBlank(),
        showVideoSettings = isIos,
        showSources = activeVideoId != null,
        showEpisodes = isSeries,
        showExternalPlayer = args.onOpenInExternalPlayer != null,
        durationMs = playbackSnapshot.durationMs,
        positionMs = displayedPositionMs,
        chapters = playerChapters,
        sourceIsLoading = sourceStreamsState.isAnyLoading,
        sourceBadgePlacement = streamBadgeSettings.badgePlacement.name.lowercase(),
        sourceFilters = sourceFilters,
        sourceItems = sourceItems,
        episodeItems = episodeItems,
        episodeSeasons = episodeSeasons,
        episodeStreamsVisible = episodeStreamsPanelState.showStreams,
        episodeStreamsIsLoading = episodeStreamsRepoState.isAnyLoading,
        selectedEpisodeLabel = selectedEpisodeLabel,
        episodeStreamFilters = episodeStreamFilters,
        episodeStreamItems = episodeStreamItems,
        submitIntroSegmentType = submitIntroSegmentType,
        submitIntroStartTime = submitIntroStartTimeStr,
        submitIntroEndTime = submitIntroEndTimeStr,
        isSubmitIntroSubmitting = isSubmitIntroSubmitting,
        submitIntroStatusMessage = submitIntroStatusMessage.orEmpty(),
        showP2pConsent = playerControlsPendingP2pSwitch != null,
        subtitleActiveTab = activeSubtitleTab.name,
        addonSubtitleItems = playerControlAddonSubtitles,
        builtInSubtitleFilterActive = builtInSubtitleFilterActive,
        builtInSubtitleItems = playerControlBuiltInSubtitles,
        audioTrackFilterActive = audioTrackFilterActive,
        audioTrackItems = playerControlAudioTracks,
        isLoadingAddonSubtitles = isLoadingAddonSubtitles,
        selectedAddonSubtitleId = selectedAddonSubtitleId.orEmpty(),
        useCustomSubtitles = useCustomSubtitles,
        subtitleStyle = subtitleStyle,
        subtitleFontFamilies = availableSubtitleFontFamilies(),
        subtitleDelayMs = subtitleDelayMs,
        hasSelectedAddonSubtitle = selectedAddonSubtitle != null,
        subtitleAutoSyncCapturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: -1L,
        subtitleAutoSyncCues = playerControlAutoSyncCues,
        subtitleAutoSyncIsLoading = subtitleAutoSyncState.isLoading,
        subtitleAutoSyncErrorMessage = subtitleAutoSyncState.errorMessage.orEmpty(),
        closeModalsToken = playerControlsCloseModalsToken,
        showOpeningOverlay = openingOverlayWanted,
        openingArtwork = background ?: poster,
        openingLogo = logo,
        openingTitle = title,
        openingMessage = p2pInitialLoadingMessage,
        openingProgress = p2pInitialLoadingProgress,
        skipPromptVisible = nativeSkipInterval != null && !playerControlsLocked,
        skipPromptLabel = skipPromptLabel(nativeSkipInterval?.type),
        skipPromptStartMs = ((nativeSkipInterval?.startTime ?: 0.0) * 1000).toLong().coerceAtLeast(0L),
        skipPromptEndMs = ((nativeSkipInterval?.endTime ?: 0.0) * 1000).toLong().coerceAtLeast(0L),
        skipPromptDismissed = skipIntervalDismissed,
        nextEpisodeVisible = nextEpisodeForControls != null && !playerControlsLocked,
        nextEpisodeHeaderLabel = stringResource(Res.string.player_next_episode),
        nextEpisodeTitle = nextEpisodeForControls?.let {
            stringResource(
                Res.string.compose_player_episode_title_format,
                it.season,
                it.episode,
                it.title,
            )
        }.orEmpty(),
        nextEpisodeThumbnail = nextEpisodeForControls?.thumbnail.orEmpty(),
        nextEpisodeStatus = nextEpisodeStatus,
        nextEpisodeActionLabel = if (nextEpisodeForControls?.hasAired == true) {
            stringResource(Res.string.detail_btn_play)
        } else {
            stringResource(Res.string.player_next_episode_unaired)
        },
        nextEpisodePlayable = nextEpisodeForControls?.hasAired == true && !manualSwitchIsNonSequential,
    )

    val gestureCallbacks = rememberSurfaceGestureCallbacks()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { layoutSize = it }
            .then(
                if (isDesktop && playerSettingsUiState.mouseMoveRevealsControlsEnabled) {
                    Modifier.onPointerEvent(PointerEventType.Move) {
                        mouseActivitySignal++
                        if (!playerControlsLocked) {
                            controlsVisible = true
                        }
                    }
                } else {
                    Modifier
                },
            )
            .playerSurfaceTapGestures(
                layoutSize = layoutSize,
                playerControlsLockedState = gestureCallbacks.playerControlsLocked,
                onSurfaceTap = gestureCallbacks.onSurfaceTap,
                onSurfaceDoubleTap = gestureCallbacks.onSurfaceDoubleTap,
                revealLockedOverlayState = gestureCallbacks.revealLockedOverlay,
            ),
    ) {
        if (playerSurfaceSourceUrl != null) {
            val surfaceAttemptId = playbackAttemptId
            val surfaceSourceUrl = playerSurfaceSourceUrl
            PlatformPlayerSurface(
                sourceUrl = surfaceSourceUrl,
                sourceAudioUrl = activeSourceAudioUrl,
                sourceHeaders = activeSourceHeaders,
                sourceResponseHeaders = activeSourceResponseHeaders,
                streamType = activeStreamType,
                isAnimeContent = isAnimeContent,
                modifier = Modifier.fillMaxSize(),
                playWhenReady = shouldPlay,
                resizeMode = resizeMode,
                initialPositionMs = activeInitialPositionMs.takeIf {
                    isDesktop && !isProviderDiagnosticVideoPlayback
                } ?: 0L,
                initialProgressFraction = activeInitialProgressFraction.takeIf {
                    isDesktop && activeInitialPositionMs <= 0L && !isProviderDiagnosticVideoPlayback
                },
                initialPlaybackSpeed = sessionPlaybackSpeed,
                playbackAttemptId = surfaceAttemptId,
                playerControlsState = playerControlsState,
                onPlayerControlsAction = { action ->
                    if (isCurrentPlaybackAttempt(surfaceAttemptId, playbackAttemptId)) {
                        handlePlayerControlsAction(action)
                    } else {
                        true
                    }
                },
                onPlayerControlsEvent = { type, value ->
                    if (isCurrentPlaybackAttempt(surfaceAttemptId, playbackAttemptId)) {
                        handlePlayerControlsEvent(type, value)
                    } else {
                        true
                    }
                },
                onPlayerControlsScrubChange = { positionMs ->
                    if (!isCurrentPlaybackAttempt(surfaceAttemptId, playbackAttemptId)) {
                        return@PlatformPlayerSurface true
                    }
                    handlePlayerControlsScrubChange(positionMs)
                    true
                },
                onPlayerControlsScrubFinished = { positionMs ->
                    if (!isCurrentPlaybackAttempt(surfaceAttemptId, playbackAttemptId)) {
                        return@PlatformPlayerSurface true
                    }
                    handlePlayerControlsScrubFinished(positionMs)
                    true
                },
                onControllerReady = { controller ->
                    if (!isCurrentPlaybackAttempt(surfaceAttemptId, playbackAttemptId)) {
                        return@PlatformPlayerSurface
                    }
                    playerController = controller
                    playerControllerSourceUrl = surfaceSourceUrl
                },
                onPlayerAttached = {
                    if (!isCurrentPlaybackAttempt(surfaceAttemptId, playbackAttemptId)) {
                        return@PlatformPlayerSurface
                    }
                    playerAttachedSourceUrl = surfaceSourceUrl
                    playerAttachedAttemptId = surfaceAttemptId
                },
                onSnapshot = { snapshot ->
                    if (!isCurrentPlaybackAttempt(surfaceAttemptId, playbackAttemptId)) {
                        return@PlatformPlayerSurface
                    }
                    val providerWaitVideoDetected = isLikelyProviderWaitVideo(
                        durationMs = snapshot.durationMs,
                        requestedResumePositionMs = activeInitialPositionMs,
                        isSeries = isSeries,
                    )
                    if (providerWaitVideoDetected && providerDiagnosticVideoSourceUrl != activeSourceUrl) {
                        // This is provider status media, never episode progress. Mark it before
                        // publishing the snapshot so completion/scrobble/next-episode effects cannot
                        // observe its 01:59 position as the end of the requested episode.
                        providerDiagnosticVideoSourceUrl = activeSourceUrl
                        providerDiagnosticProbePendingSourceUrl = null
                        lastTrustedPlaybackPositionMs = 0L
                        lastMeaningfulPlaybackSnapshot = null
                        hasRequestedScrobbleStartForCurrentItem = false
                        scrobbleStartRequestGeneration += 1L
                        pendingScrobbleStartAfterSeek = false
                        currentTrackingScrobbleMedia = null
                        val failoverTaken = tryFailoverToNextSource(
                            message = unableToPlayStreamMessage,
                            playbackFailedToast = playbackFailedToast,
                            tryingNextToast = failoverTryingNextToast,
                            trigger = StreamFailoverTrigger.ProviderDiagnosticVideo,
                        )
                        if (!failoverTaken) {
                            activeInitialPositionMs = 0L
                            activeInitialProgressFraction = null
                        }
                    }
                    if (
                        !isProviderDiagnosticVideoPlayback &&
                        snapshot.isPlaying &&
                        !snapshot.isLoading &&
                        !snapshot.isEnded &&
                        snapshot.durationMs > 0L &&
                        snapshot.positionMs in 1 until snapshot.durationMs
                    ) {
                        lastTrustedPlaybackPositionMs = snapshot.positionMs
                    }
                    if (
                        !isProviderDiagnosticVideoPlayback &&
                        !snapshot.isLoading &&
                        snapshot.durationMs > 0L &&
                        snapshot.positionMs >= 1_000L
                    ) {
                        lastMeaningfulPlaybackSnapshot = snapshot
                    }
                    playbackSnapshot = snapshot
                    if (!snapshot.isLoading) initialLoadCompleted = true
                    if (!snapshot.isLoading && !defaultPlaybackSpeedApplied && !isProviderDiagnosticVideoPlayback) {
                        defaultPlaybackSpeedApplied = true
                        if (abs(sessionPlaybackSpeed - snapshot.playbackSpeed) > 0.01f) {
                            playerController?.setPlaybackSpeed(sessionPlaybackSpeed)
                        }
                    }
                    if (snapshot.isEnded) {
                        shouldPlay = false
                        controlsVisible = !playerControlsLocked
                    }
                },
                onError = { message ->
                    if (!isCurrentPlaybackAttempt(surfaceAttemptId, playbackAttemptId)) {
                        return@PlatformPlayerSurface
                    }
                    if (message == null) {
                        errorMessage = null
                        return@PlatformPlayerSurface
                    }
                    // Stop completion persistence/scrobbling before mpv's failed seek can expose
                    // its synthetic last-frame position to the EOF/autoplay effects.
                    playbackSourceFailureActive = true

                    val isRateLimited =
                        playbackErrorFailure(message) == PlaybackSourceFailure.DebridRateLimited
                    val failureToast = if (isRateLimited) debridRateLimitedToast else playbackFailedToast
                    val failedUrl = activeSourceUrl
                    // A rate limit (HTTP 429) is provider-wide and transient: probing the URL for a
                    // diagnostic placeholder just fires another request at the throttled provider, so
                    // skip straight to surfacing it rather than earning another 429.
                    val canAttemptDiagnosticRecovery =
                        !isRateLimited &&
                        !shouldSkipProviderDiagnosticVideo(playerSettingsUiState.streamFailoverEnabled) &&
                        (failedUrl.startsWith("http://", ignoreCase = true) ||
                            failedUrl.startsWith("https://", ignoreCase = true)) &&
                            providerDiagnosticRecoveryAttemptedSourceUrl != failedUrl
                    if (!canAttemptDiagnosticRecovery) {
                        presentUnrecoverablePlaybackError(message, failureToast, failoverTryingNextToast)
                        return@PlatformPlayerSurface
                    }

                    providerDiagnosticRecoveryAttemptedSourceUrl = failedUrl
                    controlsVisible = !playerControlsLocked
                    scope.launch {
                        val diagnostic = resolveProviderDiagnosticVideo(
                            sourceUrl = failedUrl,
                            sourceHeaders = activeSourceHeaders,
                        )
                        if (activeSourceUrl != failedUrl) {
                            diagnostic?.let { releaseProviderDiagnosticVideo(it.sourceUrl) }
                            return@launch
                        }
                        if (diagnostic == null) {
                            presentUnrecoverablePlaybackError(message, failureToast, failoverTryingNextToast)
                            return@launch
                        }

                        activateProviderDiagnosticVideo(diagnostic)
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = pausedOverlayVisible &&
                !controlsVisible &&
                !playerControlsLocked &&
                !isProviderDiagnosticVideoPlayback,
            enter = fadeIn(animationSpec = tween(durationMillis = 220)),
            exit = fadeOut(animationSpec = tween(durationMillis = 180)),
        ) {
            PauseMetadataOverlay(
                title = title,
                logo = logo,
                isEpisode = isEpisode,
                seasonNumber = activeSeasonNumber,
                episodeNumber = activeEpisodeNumber,
                episodeTitle = activeEpisodeTitle,
                pauseDescription = activePauseDescription,
                metrics = metrics,
                horizontalSafePadding = horizontalSafePadding,
                modifier = Modifier.fillMaxSize(),
            )
        }

        RenderPlayerControls(displayedPositionMs = displayedPositionMs, isEpisode = isEpisode)
        RenderPlaybackOverlays(
            runtime = runtime,
            displayedPositionMs = displayedPositionMs,
            currentGestureFeedback = currentGestureFeedback,
            p2pInitialLoadingMessage = p2pInitialLoadingMessage,
            p2pInitialLoadingProgress = p2pInitialLoadingProgress,
            showP2pRebufferStats = showP2pRebufferStats,
            p2pRebufferMessage = p2pRebufferMessage,
            p2pRebufferProgress = p2pRebufferProgress,
            suppressOpeningOverlay = isDesktop && playerSurfaceSourceUrl != null,
        )
        RenderPlayerModals(displayedPositionMs = displayedPositionMs)
    }
}

private fun PlayerScreenRuntime.presentUnrecoverablePlaybackError(
    message: String,
    playbackFailedToast: String,
    failoverTryingNextToast: String,
) {
    val isRateLimited = playbackErrorFailure(message) == PlaybackSourceFailure.DebridRateLimited
    // A provider-side rate limit is not an expired credential. Refreshing a signed URL here can
    // silently retry the same throttled provider and leave the user on the failed player longer.
    if (!isRateLimited && tryRefreshCredentialedSourceAfterError(message)) return
    // Failover carries the same hazard on a rate limit: any hop onto the SAME throttled provider
    // just earns another 429 and deepens the throttle (rapid episode-switching walked the whole
    // list this way). Passing rateLimited scopes the walk to the failed source's provider — it
    // skips same-provider candidates but still tries a different provider, even within one addon.
    if (tryFailoverToNextSource(
            message,
            playbackFailedToast,
            failoverTryingNextToast,
            rateLimited = isRateLimited,
        )
    ) {
        return
    }
    exitAfterPlaybackFailure(message, playbackFailedToast)
}

internal fun PlayerScreenRuntime.exitAfterPlaybackFailure(
    message: String,
    playbackFailedToast: String,
) {
    if (playbackFailureExitRequested) return
    playbackFailureExitRequested = true
    playbackSourceFailureActive = true
    flushWatchProgress()
    // A rate-limited link isn't a bad link, just throttled — keep it cached so the next attempt
    // (once the throttle clears) can reuse it instead of forcing a fresh resolve that may 429 again.
    if (playbackErrorFailure(message) != PlaybackSourceFailure.DebridRateLimited) {
        removeFailedStreamFromCache()
    }
    args.onBack()
    NuvioToastController.show(
        message = message,
        durationMillis = 4500L,
        title = playbackFailedToast,
        placement = NuvioToastPlacement.TopEnd,
    )
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerControls(displayedPositionMs: Long, isEpisode: Boolean) {
    AnimatedVisibility(
        visible = (controlsVisible || showParentalGuide) && !playerControlsLocked,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        PlayerControlsShell(
            title = title,
            streamTitle = activeStreamTitle,
            providerName = activeProviderName,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
            playbackSnapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            metrics = metrics,
            resizeMode = resizeMode,
            isLocked = playerControlsLocked,
            showPlaybackControls = controlsVisible,
            onLockToggle = {
                if (playerControlsLocked) unlockPlayerControls() else lockPlayerControls()
            },
            onBack = {
                flushWatchProgress()
                args.onBack()
            },
            onTogglePlayback = { togglePlayback() },
            onSeekBack = { seekBy(-10_000L) },
            onSeekForward = { seekBy(10_000L) },
            onResizeModeClick = { cycleResizeMode() },
            onSpeedClick = { cyclePlaybackSpeed() },
            onSubtitleClick = {
                refreshTracks()
                showSubtitleModal = true
            },
            onAudioClick = {
                refreshTracks()
                showAudioModal = true
            },
            onVideoSettingsClick = if (isIos) {
                {
                    showVideoSettingsModal = true
                    controlsVisible = true
                }
            } else {
                null
            },
            onSourcesClick = if (activeVideoId != null) { { openSourcesPanel() } } else null,
            onEpisodesClick = if (isSeries) { { openEpisodesPanel() } } else null,
            onOpenInExternalPlayer = args.onOpenInExternalPlayer?.let { openExternal ->
                {
                    val loadedSubtitles = addonSubtitles
                        .takeIf { it.isNotEmpty() }
                        ?.map { sub ->
                            SubtitleInput(
                                url = sub.url,
                                name = buildString {
                                    if (!sub.addonName.isNullOrBlank()) append("[${sub.addonName}] ")
                                    append(sub.display)
                                },
                                lang = sub.language,
                            )
                        }
                    openExternal(
                        ExternalPlayerPlaybackRequest(
                            sourceUrl = activeSourceUrl,
                            title = title,
                            streamTitle = activeStreamTitle,
                            sourceHeaders = activeSourceHeaders,
                            resumePositionMs = playbackSnapshot.positionMs,
                            subtitles = loadedSubtitles,
                            season = activeSeasonNumber,
                            episode = activeEpisodeNumber,
                            episodeTitle = activeEpisodeTitle,
                        ),
                    )
                }
            },
            onSubmitIntroClick = if (
                isSeries &&
                playerSettingsUiState.introSubmitEnabled &&
                playerSettingsUiState.introDbApiKey.isNotBlank()
            ) {
                { showSubmitIntroModal = true }
            } else {
                null
            },
            parentalWarnings = parentalWarnings,
            showParentalGuide = showParentalGuide,
            onParentalGuideAnimationComplete = { showParentalGuide = false },
            onScrubChange = { positionMs ->
                isScrubbingTimeline = true
                scrubbingPositionMs = positionMs
            },
            onScrubFinished = { positionMs ->
                isScrubbingTimeline = false
                scrubbingPositionMs = null
                playerController?.seekTo(positionMs)
                scheduleProgressSyncAfterSeek()
            },
            horizontalSafePadding = horizontalSafePadding,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun PlayerScreenRuntime.handlePlayerControlsAction(action: PlayerControlsAction): Boolean {
    when (action) {
        PlayerControlsAction.ToggleChrome -> {
            if (playerControlsLocked) {
                revealLockedOverlay()
            } else {
                controlsVisible = !controlsVisible
            }
        }
        PlayerControlsAction.RevealLockedOverlay -> revealLockedOverlay()
        PlayerControlsAction.Back -> {
            flushWatchProgress()
            args.onBack()
        }
        PlayerControlsAction.TogglePlayback -> {
            prepareTogglePlaybackForNativeFallback()
            return false
        }
        PlayerControlsAction.KeyboardTogglePlayback -> {
            prepareTogglePlaybackForNativeFallback(revealControls = false)
            return false
        }
        PlayerControlsAction.SeekBack -> {
            prepareSeekByForNativeFallback(-10_000L)
            return false
        }
        PlayerControlsAction.KeyboardSeekBack -> {
            prepareSeekByForNativeFallback(-10_000L, revealControls = false)
            return false
        }
        PlayerControlsAction.SeekForward -> {
            prepareSeekByForNativeFallback(10_000L)
            return false
        }
        PlayerControlsAction.KeyboardSeekForward -> {
            prepareSeekByForNativeFallback(10_000L, revealControls = false)
            return false
        }
        PlayerControlsAction.ResizeMode -> cycleResizeMode()
        PlayerControlsAction.Speed -> cyclePlaybackSpeed()
        PlayerControlsAction.Subtitles -> {
            refreshTracks()
            showSubtitleModal = true
        }
        PlayerControlsAction.Audio -> {
            refreshTracks()
            showAudioModal = true
        }
        PlayerControlsAction.Sources -> {
            prepareSourcesForPlayerControls()
        }
        PlayerControlsAction.Episodes -> {
            prepareEpisodesForPlayerControls()
        }
        PlayerControlsAction.OpenExternalPlayer -> openInExternalPlayer()
        PlayerControlsAction.SubmitIntro -> {
            submitIntroStatusMessage = null
        }
        PlayerControlsAction.LockToggle -> {
            if (playerControlsLocked) unlockPlayerControls() else lockPlayerControls()
        }
        PlayerControlsAction.VideoSettings -> {
            if (isIos) {
                showVideoSettingsModal = true
                controlsVisible = true
            }
        }
        PlayerControlsAction.PictureInPicture -> {
            pictureInPictureActive = !pictureInPictureActive
            controlsVisible = false
        }
        PlayerControlsAction.HeroTrailerMute -> Unit
        PlayerControlsAction.DoubleTapSeekBack -> {
            prepareDoubleTapSeekForNativeFallback(PlayerSeekDirection.Backward)
            return false
        }
        PlayerControlsAction.DoubleTapSeekForward -> {
            prepareDoubleTapSeekForNativeFallback(PlayerSeekDirection.Forward)
            return false
        }
    }
    return true
}

private fun PlayerScreenRuntime.handlePlayerControlsEvent(type: String, value: Double): Boolean {
    when (type) {
        "revealChrome" -> {
            if (!playerControlsLocked) {
                controlsVisible = true
                mouseActivitySignal++
            }
        }
        "chromeInteraction" -> {
            nativeChromeInteractionActive = value > 0.5
            if (nativeChromeInteractionActive && !playerControlsLocked) {
                controlsVisible = true
            }
            // Entering or leaving chrome invalidates the current delay. While interaction remains
            // active the visibility effect is suspended; leaving starts a complete new timeout.
            mouseActivitySignal++
        }
        "hideChrome" -> {
            controlsVisible = false
        }
        "playbackRestart" -> {
            // Native emits this once the current file has produced its first rendered frame.
            // Keep it source-scoped so stale callbacks cannot satisfy a replacement watchdog.
            playerStartedSourceUrl = activeSourceUrl
            playerStartedAttemptId = playbackAttemptId
        }
        "reloadSources" -> {
            prepareSourcesForPlayerControls(forceRefresh = true)
        }
        "selectSource" -> {
            val streams = sourceStreamsState.groups.flatMap { it.streams }
            val stream = streams.getOrNull(value.toInt()) ?: return true
            resetFailoverBudget()
            if (requestP2pConsentForPlayerControls(stream = stream, episode = null)) return true
            switchToSource(stream)
            playerControlsCloseModalsToken += 1
        }
        "selectEpisode" -> {
            val episode = playerMetaVideos.getOrNull(value.toInt()) ?: return true
            if (selectDownloadedEpisodeForPlayback(
                    parentMetaId = parentMetaId,
                    episode = episode,
                    onDownloadedEpisodeSelected = { item, video -> switchToDownloadedEpisode(item, video) },
                )
            ) {
                playerControlsCloseModalsToken += 1
            } else {
                // Autoplay-first: pick a stream like binge auto-advance does; the per-episode
                // source list is only the fallback when nothing could be auto-selected.
                autoPlaySelectedEpisode(episode)
                playerControlsCloseModalsToken += 1
            }
        }
        "selectEpisodeStream" -> {
            val episode = episodeStreamsPanelState.selectedEpisode ?: return true
            val stream = episodeStreamsRepoState.groups.flatMap { it.streams }.getOrNull(value.toInt()) ?: return true
            if (requestP2pConsentForPlayerControls(stream = stream, episode = episode)) return true
            switchToEpisodeStream(stream, episode)
            playerControlsCloseModalsToken += 1
        }
        "backToEpisodes" -> {
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
        }
        "reloadEpisodeStreams" -> {
            episodeStreamsPanelState.selectedEpisode?.let { requestEpisodeStreamsForPlayerControls(it, forceRefresh = true) }
        }
        "submitIntroSegment" -> {
            submitIntroSegmentType = SKIP_SEGMENT_TYPES.getOrElse(value.toInt()) { "intro" }
            submitIntroStatusMessage = null
        }
        "submitIntroStart" -> {
            val seconds = value.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            submitIntroStartTimeSec = seconds
            submitIntroStartTimeStr = formatPlayerControlsSeconds(seconds)
            submitIntroStatusMessage = null
        }
        "submitIntroEnd" -> {
            val seconds = value.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            submitIntroEndTimeSec = seconds
            submitIntroEndTimeStr = formatPlayerControlsSeconds(seconds)
            submitIntroStatusMessage = null
        }
        "submitIntroCommit" -> submitIntroFromPlayerControls()
        "skipInterval" -> {
            val interval = activeSkipInterval ?: return true
            playerController?.seekTo((interval.endTime * 1000).toLong())
            scheduleProgressSyncAfterSeek()
            skipIntervalDismissed = true
        }
        "playNextEpisode" -> {
            if (nextEpisodeInfo?.hasAired == true) {
                nextEpisodeAutoPlayJob?.cancel()
                playNextEpisode()
            }
        }
        "nextEpisode" -> {
            if (nextEpisodeInfo?.hasAired == true) playNextEpisode()
        }
        "previousEpisode" -> {
            val previous = PlayerNextEpisodeRules.resolvePreviousEpisode(
                videos = playerMetaVideos,
                currentSeason = activeSeasonNumber,
                currentEpisode = activeEpisodeNumber,
                currentVideoId = activeVideoId,
                parentMetaId = parentMetaId,
            ) ?: return true
            autoPlaySelectedEpisode(previous)
        }
        "setPlaybackSpeed" -> {
            val speed = value.toFloat().coerceIn(0.5f, 4f)
            playerController?.setPlaybackSpeed(speed)
            sessionPlaybackSpeed = speed
            playbackSnapshot = playbackSnapshot.copy(playbackSpeed = speed)
        }
        "enableP2pForPlayerControls" -> enableP2pForPlayerControls()
        "cancelP2pForPlayerControls" -> {
            playerControlsPendingP2pSwitch = null
        }
        "subtitleTab" -> {
            activeSubtitleTab = when (value.toInt()) {
                1 -> SubtitleTab.Addons
                2 -> SubtitleTab.Style
                else -> SubtitleTab.BuiltIn
            }
        }
        "selectBuiltInSubtitleTrack" -> {
            val index = value.toInt()
            val wasCustom = useCustomSubtitles
            selectedSubtitleIndex = index
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            persistInternalSubtitlePreference(subtitleTracks.firstOrNull { it.index == index })
            if (wasCustom) {
                playerController?.clearExternalSubtitleAndSelect(index)
            } else {
                playerController?.selectSubtitleTrack(index)
            }
            secondarySubtitleSelectionApplied = false
            applySecondarySubtitleSelectionIfNeeded()
        }
        "selectAudioTrack" -> {
            val index = value.toInt()
            selectedAudioIndex = index
            persistAudioPreference(audioTracks.firstOrNull { it.index == index })
            playerController?.selectAudioTrack(index)
        }
        "fetchAddonSubtitles" -> fetchAddonSubtitlesForActiveItem()
        "selectAddonSubtitle" -> {
            val addon = visibleAddonSubtitles.getOrNull(value.toInt()) ?: return true
            selectedAddonSubtitleId = addon.id
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            persistAddonSubtitlePreference(addon)
            playerController?.setSubtitleUri(addon.url)
            secondarySubtitleSelectionApplied = false
            applySecondarySubtitleSelectionIfNeeded()
        }
        "subtitleDelayDelta" -> setSubtitleDelay((subtitleDelayMs + value.toInt()).coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS))
        "subtitleDelayReset" -> setSubtitleDelay(0)
        "subtitleAutoSyncCapture" -> captureSubtitleAutoSyncTime()
        "subtitleAutoSyncReload" -> loadSubtitleAutoSyncCues(force = true)
        "subtitleAutoSyncCue" -> {
            val cue = playerControlsNearestSubtitleCues().getOrNull(value.toInt()) ?: return true
            applySubtitleAutoSyncCue(cue)
        }
        "subtitleFontSizeDelta" -> {
            PlayerSettingsRepository.setSubtitleStyle(
                subtitleStyle.copy(fontSizeSp = (subtitleStyle.fontSizeSp + value.toInt()).coerceIn(6, 40)),
            )
        }
        "subtitleFontDelta" -> {
            PlayerSettingsRepository.setSubtitleStyle(
                subtitleStyle.copy(fontFamily = cycleSubtitleFontFamily(subtitleStyle.fontFamily, value.toInt())),
            )
        }
        "subtitleFontIndex" -> {
            availableSubtitleFontFamilies().getOrNull(value.toInt())?.let { family ->
                PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(fontFamily = family))
            }
        }
        "subtitleOutlineToggle" -> {
            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(outlineEnabled = !subtitleStyle.outlineEnabled))
        }
        "subtitleShadowToggle" -> {
            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(shadowEnabled = !subtitleStyle.shadowEnabled))
        }
        "subtitleShadowOffsetDelta" -> {
            PlayerSettingsRepository.setSubtitleStyle(
                subtitleStyle.copy(
                    shadowOffset = (subtitleStyle.shadowOffset + value.toInt())
                        .coerceIn(SUBTITLE_SHADOW_OFFSET_MIN, SUBTITLE_SHADOW_OFFSET_MAX),
                ),
            )
        }
        "subtitleShadowOpacity" -> {
            val alpha = (value.toFloat() / 100f).coerceIn(0f, 1f)
            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(shadowColor = subtitleStyle.shadowColor.copy(alpha = alpha)))
        }
        "subtitleOutlineWidthDelta" -> {
            PlayerSettingsRepository.setSubtitleStyle(
                subtitleStyle.copy(
                    outlineWidth = (subtitleStyle.outlineWidth + value.toInt())
                        .coerceIn(SUBTITLE_OUTLINE_WIDTH_MIN, SUBTITLE_OUTLINE_WIDTH_MAX),
                ),
            )
        }
        "subtitleBlurDelta" -> {
            PlayerSettingsRepository.setSubtitleStyle(
                subtitleStyle.copy(
                    blur = (subtitleStyle.blur + value.toInt()).coerceIn(SUBTITLE_BLUR_MIN, SUBTITLE_BLUR_MAX),
                ),
            )
        }
        "subtitleBoldToggle" -> {
            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(bold = !subtitleStyle.bold))
        }
        "subtitleItalicToggle" -> {
            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(italic = !subtitleStyle.italic))
        }
        "subtitleBottomOffsetDelta" -> {
            PlayerSettingsRepository.setSubtitleStyle(
                subtitleStyle.copy(bottomOffset = (subtitleStyle.bottomOffset + value.toInt()).coerceIn(0, 200)),
            )
        }
        "subtitleTextColor" -> {
            SubtitleColorSwatches.getOrNull(value.toInt())?.let { color ->
                PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(textColor = color.copy(alpha = subtitleStyle.textColor.alpha)))
            }
        }
        "subtitleOutlineColor" -> {
            SubtitleColorSwatches.getOrNull(value.toInt())?.let { color ->
                PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(outlineColor = color.copy(alpha = subtitleStyle.outlineColor.alpha)))
            }
        }
        "subtitleBackgroundColor" -> {
            SubtitleBackgroundColorSwatches.getOrNull(value.toInt())?.let { color ->
                PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(backgroundColor = color))
            }
        }
        "subtitleShadowColor" -> {
            SubtitleShadowColorSwatches.getOrNull(value.toInt())?.let { color ->
                PlayerSettingsRepository.setSubtitleStyle(
                    subtitleStyle.copy(shadowColor = color.copy(alpha = subtitleStyle.shadowColor.alpha)),
                )
            }
        }
        "subtitleTextOpacity" -> {
            val alpha = (value.toFloat() / 100f).coerceIn(0f, 1f)
            PlayerSettingsRepository.setSubtitleStyle(subtitleStyle.copy(textColor = subtitleStyle.textColor.copy(alpha = alpha)))
        }
        "subtitleStyleReset" -> PlayerSettingsRepository.setSubtitleStyle(SubtitleStyleState.DEFAULT)
        "parentalGuideComplete" -> {
            showParentalGuide = false
        }
        "volumeUp" -> adjustVolume(PlayerVolumeStepFraction)
        "volumeDown" -> adjustVolume(-PlayerVolumeStepFraction)
        "volumeDelta" -> adjustVolume(value.toFloat())
        "volumeSet" -> playerController?.setVolume((value.toFloat() / 100f).coerceIn(0f, playerController?.maxVolumeFraction ?: 1f))
        "keyboardSpeedStep" -> adjustPlaybackSpeedStep(if (value < 0.0) -1 else 1, showFeedback = false)
        "keyboardNextSubtitle" -> cycleSubtitleTrackFromKeyboard()
        "keyboardNextAudio" -> cycleAudioTrackFromKeyboard()
        "selectResizeMode" -> setPlayerResizeMode(
            when (value.toInt()) {
                1 -> PlayerResizeMode.Fill
                2 -> PlayerResizeMode.Zoom
                else -> PlayerResizeMode.Fit
            },
        )
        "copyStreamUrl" -> activeSourceUrl.takeIf { it.isNotBlank() }?.let(::copyPlainTextToClipboard)
        "toggleMpvDiagnostics" -> playerController?.setDiagnosticsOverlayEnabled(value != 0.0)
        "toggleStreamFailover" -> {
            val enabled = value != 0.0
            PlayerSettingsRepository.setStreamFailoverEnabled(enabled)
            if (!enabled) resetFailoverBudget()
        }
        else -> return false
    }
    return true
}

private fun PlayerScreenRuntime.requestP2pConsentForPlayerControls(
    stream: StreamItem,
    episode: MetaVideo?,
): Boolean {
    if (!isP2pStream(stream)) return false
    if (!P2pSettingsRepository.isVisible) return false
    if (P2pSettingsRepository.uiState.value.p2pEnabled) return false
    playerControlsPendingP2pSwitch = PendingPlayerP2pSwitch(
        stream = stream,
        episode = episode,
        isAutoPlay = false,
    )
    return true
}

private fun PlayerScreenRuntime.enableP2pForPlayerControls() {
    val pending = playerControlsPendingP2pSwitch ?: return
    playerControlsPendingP2pSwitch = null
    P2pSettingsRepository.setP2pEnabled(true)
    val episode = pending.episode
    if (episode != null) {
        switchToP2pEpisodeStream(pending.stream, episode, pending.isAutoPlay)
    } else {
        switchToP2pSourceStream(pending.stream)
    }
    playerControlsCloseModalsToken += 1
}

private fun PlayerScreenRuntime.prepareSourcesForPlayerControls(forceRefresh: Boolean = false) {
    val vid = activeVideoId
    if (vid == null) {
        return
    }
    val requestType = contentType ?: parentMetaType
    PlayerStreamsRepository.loadSources(
        type = requestType,
        videoId = vid,
        parentMetaId = parentMetaId,
        title = title,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
        forceRefresh = forceRefresh,
    )
}

private fun Color.toCssColorString(): String {
    val redInt = (red * 255f).roundToInt().coerceIn(0, 255)
    val greenInt = (green * 255f).roundToInt().coerceIn(0, 255)
    val blueInt = (blue * 255f).roundToInt().coerceIn(0, 255)
    val alphaValue = alpha.coerceIn(0f, 1f)
    return "rgba($redInt, $greenInt, $blueInt, ${alphaValue.toCssAlphaString()})"
}

private fun Float.toCssAlphaString(): String {
    val rounded = (this * 1000f).roundToInt() / 1000f
    return rounded.toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

private fun PlayerScreenRuntime.prepareEpisodesForPlayerControls() {
    if (!isSeries) return
    if (playerMetaVideos.isEmpty()) {
        scope.launch {
            playerMetaVideos = MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.videos ?: emptyList()
        }
    }
}

private fun PlayerScreenRuntime.requestEpisodeStreamsForPlayerControls(
    episode: MetaVideo,
    forceRefresh: Boolean = false,
) {
    PlayerStreamsRepository.loadEpisodeStreams(
        type = contentType ?: parentMetaType,
        videoId = episode.id,
        parentMetaId = parentMetaId,
        title = title,
        season = episode.playbackSeasonNumber(),
        episode = episode.playbackEpisodeNumber(),
        sourceAffinity = sourceAffinity,
        forceRefresh = forceRefresh,
    )
    episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = episode)
}

private fun PlayerScreenRuntime.submitIntroFromPlayerControls() {
    if (isSubmitIntroSubmitting) return
    val imdbId = activeSubmitIntroImdbId()
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    val start = submitIntroStartTimeSec
    val end = submitIntroEndTimeSec
    // A null season/episode is a film, which SkipDB accepts — only the timings have to be valid.
    if (imdbId.isNullOrBlank() || start == null || end == null || end <= start) {
        submitIntroStatusMessage = "Check the start and end times."
        return
    }
    isSubmitIntroSubmitting = true
    submitIntroStatusMessage = null
    // The runtime is what lets SkipDB match these timings to the right cut later, so it is sent
    // with the submission exactly as it is sent with a lookup.
    val durationSeconds = playbackSnapshot.durationMs.takeIf { it > 0L }?.let { it / 1000L }
    scope.launch {
        val outcome = SkipIntroRepository.submitSegment(
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = start,
            endSec = end,
            segmentType = submitIntroSegmentType,
            durationSeconds = durationSeconds,
        )
        isSubmitIntroSubmitting = false
        if (outcome.accepted) {
            submitIntroStartTimeSec = 0.0
            submitIntroEndTimeSec = 0.0
            submitIntroStartTimeStr = "00:00"
            submitIntroEndTimeStr = "00:00"
            submitIntroSegmentType = "intro"
            submitIntroStatusMessage = null
            playerControlsCloseModalsToken += 1
        } else {
            // SkipDB explains itself — an overlap, a failed validation, a rate limit — and that is
            // far more actionable than a generic failure line.
            submitIntroStatusMessage = outcome.message
        }
    }
}

private fun PlayerScreenRuntime.activeSubmitIntroImdbId(): String? =
    activeVideoId?.split(":")?.firstOrNull()?.takeIf { it.startsWith("tt") }
        ?: parentMetaId.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.id?.takeIf { it.startsWith("tt") }

@Composable
private fun skipPromptLabel(type: String?): String =
    when (type?.lowercase()) {
        "intro", "op", "mixed-op" -> stringResource(Res.string.player_skip_intro)
        "outro", "ed", "mixed-ed", "credits" -> stringResource(Res.string.player_skip_outro)
        "recap" -> stringResource(Res.string.player_skip_recap)
        else -> stringResource(Res.string.player_skip)
    }

private fun formatPlayerControlsSeconds(seconds: Double): String {
    val totalSeconds = seconds
        .takeIf { it.isFinite() && it >= 0.0 }
        ?.toLong()
        ?: 0L
    val minutes = totalSeconds / 60L
    val remainder = totalSeconds % 60L
    return "${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
}

private fun PlayerScreenRuntime.handlePlayerControlsScrubChange(positionMs: Long) {
    isScrubbingTimeline = true
    scrubbingPositionMs = positionMs
}

private fun PlayerScreenRuntime.handlePlayerControlsScrubFinished(positionMs: Long) {
    isScrubbingTimeline = false
    scrubbingPositionMs = null
    playerController?.seekTo(positionMs)
    scheduleProgressSyncAfterSeek()
}

private fun PlayerScreenRuntime.openInExternalPlayer() {
    val openExternal = args.onOpenInExternalPlayer ?: return
    val loadedSubtitles = addonSubtitles
        .takeIf { it.isNotEmpty() }
        ?.map { sub ->
            SubtitleInput(
                url = sub.url,
                name = buildString {
                    if (!sub.addonName.isNullOrBlank()) append("[${sub.addonName}] ")
                    append(sub.display)
                },
                lang = sub.language,
            )
        }
    openExternal(
        ExternalPlayerPlaybackRequest(
            sourceUrl = activeSourceUrl,
            title = title,
            streamTitle = activeStreamTitle,
            sourceHeaders = activeSourceHeaders,
            resumePositionMs = playbackSnapshot.positionMs,
            subtitles = loadedSubtitles,
        ),
    )
}

private fun PlayerScreenRuntime.buildPlayerControlFilters(
    groups: List<AddonStreamGroup> = sourceStreamsState.groups,
    allLabel: String,
    selectedFilter: String?,
): List<PlayerControlFilterItem> {
    if (groups.size <= 1) return emptyList()
    return buildList {
        add(PlayerControlFilterItem(id = "", label = allLabel, isSelected = selectedFilter == null))
        groups.distinctBy { it.addonId }.forEach { group ->
            add(
                PlayerControlFilterItem(
                    id = group.addonId,
                    label = group.addonName,
                    isSelected = selectedFilter == group.addonId,
                    isLoading = group.isLoading,
                    hasError = group.error != null,
                ),
            )
        }
    }
}

private fun PlayerScreenRuntime.buildPlayerControlEpisodeStreamFilters(
    allLabel: String,
    selectedFilter: String?,
): List<PlayerControlFilterItem> =
    buildPlayerControlFilters(
        groups = episodeStreamsRepoState.groups,
        allLabel = allLabel,
        selectedFilter = selectedFilter,
    )

/**
 * Scores every stream once, or nothing at all when no consumer needs a number.
 *
 * Scoring runs the full trait detector — dozens of regex passes over each release name — so the
 * sort and the badge share one pass here rather than each running their own.
 */
private fun scorePlayerControlStreams(
    streams: List<IndexedValue<Pair<String, StreamItem>>>,
    profile: StreamScoreProfile,
    context: StreamScoreContext,
): Map<Int, StreamScore> {
    if (!profile.enabled || !(profile.sortStreamList || profile.showScoreOnStreams)) return emptyMap()
    return streams.associate { (index, item) ->
        index to StreamScorer.score(item.second, profile, context)
    }
}

private fun playerControlSourceItem(
    originalIndex: Int,
    filterId: String,
    stream: StreamItem,
    isCurrent: Boolean,
    canResolveDebrid: Boolean,
    score: StreamScore?,
): PlayerControlSourceItem = PlayerControlSourceItem(
    // Selection events index the repository's original flattened list. Keep that action
    // index even though the currently playing item is presented first.
    index = originalIndex,
    filterId = filterId,
    label = stream.streamLabel,
    subtitle = stream.streamSubtitle.orEmpty(),
    addonName = stream.addonName,
    badges = stream.badges.map { badge ->
        PlayerControlStreamBadge(
            name = badge.name,
            imageUrl = badge.imageURL,
            backgroundColor = badge.tagColor,
            textColor = badge.textColor,
            borderColor = badge.borderColor,
        )
    },
    isCurrent = isCurrent,
    isEnabled = stream.isSelectableForPlayback(canResolveDebrid),
    score = score?.total,
    scoreRejected = score?.rejected == true,
)

/**
 * The HUD's source rows.
 *
 * Held in [remember] because building the list scores every stream, and this HUD recomposes on
 * every position tick — several times a second, for the whole session. A few hundred sources
 * re-scored at that rate saturates the UI thread, which is why the player only ever went
 * unresponsive *after* the sources panel had been opened once (nothing loads the list before that)
 * and then stayed that way until playback ended.
 */
@Composable
private fun PlayerScreenRuntime.buildPlayerControlSourceItems(): List<PlayerControlSourceItem> {
    val canResolveDebrid = DebridSettingsRepository.uiState.value.canResolvePlayableLinks
    val scoreProfile = StreamScoreRepository.profile
    val groups = sourceStreamsState.groups
    val identityKey = activeSourceIdentityKey
    val sourceUrl = activeSourceUrl
    val torrentInfoHash = activeTorrentInfoHash
    val isEpisode = activeEpisodeNumber != null
    val metaId = parentMetaId
    val metaType = contentType ?: parentMetaType
    return remember(
        groups,
        scoreProfile,
        canResolveDebrid,
        identityKey,
        sourceUrl,
        torrentInfoHash,
        isEpisode,
        metaId,
        metaType,
    ) {
        val scoreContext = StreamScoreContexts.forPlayback(
            isEpisode = isEpisode,
            contentId = metaId,
            contentType = metaType,
        )
        val indexedStreams = groups.flatMap { group ->
            group.streams.map { stream -> group.addonId to stream }
        }.mapIndexed { index, item -> IndexedValue(index, item) }
        val scores = scorePlayerControlStreams(indexedStreams, scoreProfile, scoreContext)
        // The desktop sources panel is the HTML overlay, so it cannot reuse the Compose picker's
        // sorting — apply the same score ordering here. The index stays the repository's original
        // one because selection events are dispatched by it.
        val ordered = if (!scoreProfile.enabled || !scoreProfile.sortStreamList) {
            indexedStreams
        } else {
            indexedStreams.sortedByDescending { (index, _) -> scores[index]?.total ?: 0 }
        }
        val current = findCurrentPlayerControlStream(
            entries = ordered,
            identityKey = identityKey,
            sourceUrl = sourceUrl,
            torrentInfoHash = torrentInfoHash,
        ) { it.value.second }
        prioritizeCurrentItem(ordered) { it === current }.map { entry ->
            val (originalIndex, item) = entry
            val (filterId, stream) = item
            playerControlSourceItem(
                originalIndex = originalIndex,
                filterId = filterId,
                stream = stream,
                // Identity alone cannot decide this: one file offered by two providers — or listed
                // twice by one — gives both rows the same key, and asking each row on its own
                // painted "Playing" on every copy.
                isCurrent = entry === current,
                canResolveDebrid = canResolveDebrid,
                score = scores[originalIndex]?.takeIf { scoreProfile.showScoreOnStreams },
            )
        }
    }
}

/** The episode panel's per-episode source rows. Memoized for the same reason as the source list. */
@Composable
private fun PlayerScreenRuntime.buildPlayerControlEpisodeStreamItems(): List<PlayerControlSourceItem> {
    val canResolveDebrid = DebridSettingsRepository.uiState.value.canResolvePlayableLinks
    val scoreProfile = StreamScoreRepository.profile
    val groups = episodeStreamsRepoState.groups
    val metaId = parentMetaId
    val metaType = contentType ?: parentMetaType
    return remember(groups, scoreProfile, canResolveDebrid, metaId, metaType) {
        val scoreContext = StreamScoreContexts.forPlayback(
            isEpisode = true,
            contentId = metaId,
            contentType = metaType,
        )
        val indexedStreams = groups.flatMap { group ->
            group.streams.map { stream -> group.addonId to stream }
        }.mapIndexed { index, item -> IndexedValue(index, item) }
        val scores = scorePlayerControlStreams(indexedStreams, scoreProfile, scoreContext)
        indexedStreams.map { (index, item) ->
            val (filterId, stream) = item
            playerControlSourceItem(
                originalIndex = index,
                filterId = filterId,
                stream = stream,
                isCurrent = false,
                canResolveDebrid = canResolveDebrid,
                score = scores[index]?.takeIf { scoreProfile.showScoreOnStreams },
            )
        }
    }
}

/**
 * The one entry that is playing, or null when none of them is.
 *
 * Resolved once for a whole list instead of asked row by row, so exactly one row can ever be marked
 * — see the note at the call site about duplicate files sharing an identity key.
 *
 * The tiers are tried in order and the first that matches anything wins. Identity stays
 * authoritative; the weaker signals are consulted only when it matches *nothing*, which is the case
 * that used to leave the list with no "Playing" row at all — proxying addons re-sign their URLs on
 * every fetch, so a key minted at playback start can no longer be found in a list fetched later.
 * Falling through can no longer mark a second row, which is what made it unsafe before.
 */
private fun <T> findCurrentPlayerControlStream(
    entries: List<T>,
    identityKey: String?,
    sourceUrl: String?,
    torrentInfoHash: String?,
    stream: (T) -> StreamItem,
): T? {
    if (identityKey != null) {
        entries.firstOrNull { stream(it).playerSourceIdentityKey() == identityKey }?.let { return it }
    }
    if (!sourceUrl.isNullOrBlank()) {
        entries.firstOrNull { stream(it).playableDirectUrl == sourceUrl }?.let { return it }
    }
    if (!torrentInfoHash.isNullOrBlank()) {
        entries.firstOrNull { stream(it).p2pInfoHash == torrentInfoHash }?.let { return it }
    }
    return null
}

/**
 * Display name of the subtitle track actually on screen, or empty when there is none. Reads the same
 * two selections the subtitle panel marks — an addon subtitle wins whenever [useCustomSubtitles] is
 * set, because that is the flag mpv's own track selection is turned off by.
 */
@Composable
private fun PlayerScreenRuntime.activePlaybackSubtitleLabel(): String = if (useCustomSubtitles) {
    visibleAddonSubtitles.firstOrNull { subtitle ->
        subtitle.id == selectedAddonSubtitleId || subtitle.url == selectedAddonSubtitleId
    }?.display.orEmpty()
} else {
    visibleSubtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }
        ?.let { localizedTrackDisplayName(it.label, it.language, it.index) }
        .orEmpty()
}

@Composable
private fun PlayerScreenRuntime.buildPlayerControlBuiltInSubtitleItems(): List<PlayerControlBuiltInSubtitleItem> =
    visibleSubtitleTracks.map { track ->
        PlayerControlBuiltInSubtitleItem(
            index = track.index,
            label = localizedTrackDisplayName(track.label, track.language, track.index),
            isSelected = !useCustomSubtitles && track.index == selectedSubtitleIndex,
        )
    }

@Composable
private fun PlayerScreenRuntime.buildPlayerControlAudioTrackItems(): List<PlayerControlAudioTrackItem> =
    visibleAudioTracks.map { track ->
        PlayerControlAudioTrackItem(
            index = track.index,
            label = localizedTrackDisplayName(track.label, track.language, track.index),
            isSelected = track.index == selectedAudioIndex,
        )
    }

@Composable
private fun PlayerScreenRuntime.buildPlayerControlAddonSubtitleItems(): List<PlayerControlAddonSubtitleItem> =
    visibleAddonSubtitles.mapIndexed { index, subtitle ->
        PlayerControlAddonSubtitleItem(
            index = index,
            id = subtitle.id,
            display = subtitle.display,
            languageLabel = languageLabelForCode(subtitle.language),
            addonName = subtitle.addonName.orEmpty(),
            isSelected = subtitle.id == selectedAddonSubtitleId || subtitle.url == selectedAddonSubtitleId,
        )
    }

private fun PlayerScreenRuntime.buildPlayerControlSubtitleCueItems(): List<PlayerControlSubtitleCueItem> =
    playerControlsNearestSubtitleCues().mapIndexed { index, cue ->
        PlayerControlSubtitleCueItem(
            index = index,
            timeMs = cue.startTimeMs,
            timeLabel = formatPlayerControlsCueTimestamp(cue.startTimeMs),
            text = cue.text,
        )
    }

// Cues delivered to the scrollable HUD list: enough to recover from heavy desync (±30 lines
// around the capture point is minutes of dialogue) while keeping the controls JSON small.
private const val PLAYER_CONTROLS_SUBTITLE_CUE_WINDOW = 60

private fun PlayerScreenRuntime.playerControlsNearestSubtitleCues(): List<SubtitleSyncCue> {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return emptyList()
    // Nearest-N picks the window, but the list itself is chronological so scrolling up moves
    // earlier and scrolling down moves later — the HUD centers the view on the nearest line.
    return subtitleAutoSyncState.cues
        .sortedBy { abs(it.startTimeMs - capturedPositionMs) }
        .take(PLAYER_CONTROLS_SUBTITLE_CUE_WINDOW)
        .sortedBy { it.startTimeMs }
}

private fun formatPlayerControlsCueTimestamp(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun PlayerScreenRuntime.buildPlayerControlEpisodeItems(): List<PlayerControlEpisodeItem> {
    val items = mutableListOf<PlayerControlEpisodeItem>()
    val current = playerMetaVideos.resolveSeriesEpisodePosition(
        parentMetaId = parentMetaId,
        videoId = activeVideoId,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
    )
    for ((index, video) in playerMetaVideos.withIndex()) {
        val season = video.playbackSeasonNumber() ?: continue
        val episode = video.playbackEpisodeNumber() ?: continue
        val episodeVideoId = buildPlaybackVideoId(
            parentMetaId = parentMetaId,
            seasonNumber = season,
            episodeNumber = episode,
            fallbackVideoId = video.id,
        )
        val isWatched = watchProgressUiState.byVideoId[episodeVideoId]?.isEffectivelyCompleted == true ||
            WatchingState.isEpisodeWatched(
                watchedKeys = watchedUiState.watchedKeys,
                metaType = parentMetaType,
                metaId = parentMetaId,
                episode = video,
            )
        items.add(
            PlayerControlEpisodeItem(
                index = index,
                id = video.id,
                title = video.title,
                code = video.playerControlsEpisodeCode(),
                overview = video.overview.orEmpty(),
                thumbnail = video.thumbnail.orEmpty(),
                season = season.coerceAtLeast(0),
                episode = episode,
                isCurrent = current?.video == video,
                isWatched = isWatched,
            ),
        )
    }
    return items
}

@Composable
private fun PlayerScreenRuntime.buildPlayerControlSeasonItems(
    episodes: List<PlayerControlEpisodeItem>,
): List<PlayerControlSeasonItem> {
    val currentSeason = playerMetaVideos.resolveSeriesEpisodePosition(
        parentMetaId = parentMetaId,
        videoId = activeVideoId,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
    )?.seasonNumber ?: activeSeasonNumber
    val availableSeasons = episodes
        .map { it.season }
        .distinct()
        .let { seasons ->
            seasons.filter { it > 0 }.sorted() + seasons.filter { it == 0 }
        }
    val items = mutableListOf<PlayerControlSeasonItem>()
    for (season in availableSeasons) {
        val label = if (season == 0) {
            stringResource(Res.string.episodes_specials)
        } else {
            stringResource(Res.string.episodes_season, season)
        }
        items.add(
            PlayerControlSeasonItem(
                season = season,
                label = label,
                isSelected = currentSeason == season,
            ),
        )
    }
    return items
}

@Composable
private fun MetaVideo.playerControlsEpisodeCode(): String =
    when {
        playbackSeasonNumber() != null && playbackEpisodeNumber() != null -> stringResource(
            Res.string.compose_player_episode_code_full,
            playbackSeasonNumber()!!,
            playbackEpisodeNumber()!!,
        )
        playbackEpisodeNumber() != null -> stringResource(
            Res.string.compose_player_episode_code_episode_only,
            playbackEpisodeNumber()!!,
        )
        else -> ""
    }

@Composable
private fun BoxScope.RenderPlaybackOverlays(
    runtime: PlayerScreenRuntime,
    displayedPositionMs: Long,
    currentGestureFeedback: GestureFeedbackState?,
    p2pInitialLoadingMessage: String?,
    p2pInitialLoadingProgress: Float?,
    showP2pRebufferStats: Boolean,
    p2pRebufferMessage: String?,
    p2pRebufferProgress: Float?,
    suppressOpeningOverlay: Boolean,
) {
    runtime.run {
        PlayerPlaybackOverlays(
            playerControlsLocked = playerControlsLocked,
            lockedOverlayVisible = lockedOverlayVisible,
            playbackSnapshot = playbackSnapshot,
            displayedPositionMs = displayedPositionMs,
            metrics = metrics,
            horizontalSafePadding = horizontalSafePadding,
            onUnlock = { unlockPlayerControls() },
            showOpeningOverlay = playerSettingsUiState.showLoadingOverlay &&
                !initialLoadCompleted &&
                errorMessage == null &&
                !suppressOpeningOverlay,
            backdropArtwork = background ?: poster,
            logo = logo,
            title = title,
            onBackWithProgress = {
                flushWatchProgress()
                args.onBack()
            },
            p2pInitialLoadingMessage = p2pInitialLoadingMessage,
            p2pInitialLoadingProgress = p2pInitialLoadingProgress,
            showP2pRebufferStats = showP2pRebufferStats,
            p2pRebufferMessage = p2pRebufferMessage,
            p2pRebufferProgress = p2pRebufferProgress,
            currentGestureFeedback = currentGestureFeedback,
            renderedGestureFeedback = renderedGestureFeedback,
            initialLoadCompleted = initialLoadCompleted,
            pausedOverlayVisible = pausedOverlayVisible,
            activeSkipInterval = activeSkipInterval.takeUnless {
                isDesktop || isProviderDiagnosticVideoPlayback
            },
            skipIntervalDismissed = skipIntervalDismissed,
            controlsVisible = controlsVisible,
            onSkipInterval = { interval ->
                playerController?.seekTo((interval.endTime * 1000).toLong())
                scheduleProgressSyncAfterSeek()
                skipIntervalDismissed = true
            },
            onDismissSkipInterval = { skipIntervalDismissed = true },
            sliderEdgePadding = sliderEdgePadding,
            overlayBottomPadding = overlayBottomPadding,
            isSeries = isSeries,
            nextEpisodeInfo = nextEpisodeInfo.takeUnless { isProviderDiagnosticVideoPlayback },
            showNextEpisodeCard = showNextEpisodeCard &&
                !isDesktop &&
                !isProviderDiagnosticVideoPlayback,
            nextEpisodeAutoPlaySearching = nextEpisodeAutoPlaySearching,
            nextEpisodeAutoPlaySourceName = nextEpisodeAutoPlaySourceName,
            nextEpisodeAutoPlayCountdown = nextEpisodeAutoPlayCountdown,
            onPlayNextEpisode = {
                nextEpisodeAutoPlayJob?.cancel()
                playNextEpisode()
            },
            onDismissNextEpisode = {
                nextEpisodeAutoPlayJob?.cancel()
                showNextEpisodeCard = false
                manualEpisodeSwitchInfo = null
                nextEpisodeAutoPlaySearching = false
                nextEpisodeAutoPlaySourceName = null
                nextEpisodeAutoPlayCountdown = null
            },
            errorMessage = errorMessage,
            onDismissError = {
                flushWatchProgress()
                args.onBack()
            },
        )
    }
}

@Composable
private fun PlayerScreenRuntime.RenderPlayerModals(displayedPositionMs: Long) {
    LaunchedEffect(showSubtitleModal, playerController) {
        if (!showSubtitleModal || playerController == null) return@LaunchedEffect
        while (true) {
            refreshTracks()
            kotlinx.coroutines.delay(250)
        }
    }

    PlayerScreenModalHosts(
        pendingP2pSwitch = pendingP2pSwitch,
        onPendingP2pSwitchChanged = { pendingP2pSwitch = it },
        onP2pEpisodeStreamSelected = { stream, episode, isAutoPlay ->
            switchToP2pEpisodeStream(stream, episode, isAutoPlay)
        },
        onP2pSourceStreamSelected = { stream -> switchToP2pSourceStream(stream) },
        onNextEpisodeAutoPlaySearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onNextEpisodeAutoPlayCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeAutoPlaySourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        showAudioModal = showAudioModal,
        audioTracks = visibleAudioTracks,
        selectedAudioIndex = selectedAudioIndex,
        onAudioTrackSelected = { index ->
            selectedAudioIndex = index
            persistAudioPreference(audioTracks.firstOrNull { it.index == index })
            playerController?.selectAudioTrack(index)
            scope.launch {
                kotlinx.coroutines.delay(200)
                showAudioModal = false
            }
        },
        onAudioModalDismissed = { showAudioModal = false },
        showSubtitleModal = showSubtitleModal,
        activeSubtitleTab = activeSubtitleTab,
        subtitleTracks = visibleSubtitleTracks,
        selectedSubtitleIndex = visibleSubtitleTracks
            .firstOrNull(SubtitleTrack::isSelected)
            ?.index
            ?: selectedSubtitleIndex,
        addonSubtitles = visibleAddonSubtitles,
        selectedAddonSubtitleId = selectedAddonSubtitleId,
        isLoadingAddonSubtitles = isLoadingAddonSubtitles,
        subtitleStyle = subtitleStyle,
        subtitleDelayMs = subtitleDelayMs,
        selectedAddonSubtitle = selectedAddonSubtitle,
        subtitleAutoSyncState = subtitleAutoSyncState,
        onSubtitleTabSelected = { activeSubtitleTab = it },
        onBuiltInSubtitleTrackSelected = { index ->
            val wasCustom = useCustomSubtitles
            selectedSubtitleIndex = index
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
            persistInternalSubtitlePreference(subtitleTracks.firstOrNull { it.index == index })
            if (wasCustom) {
                playerController?.clearExternalSubtitleAndSelect(index)
            } else {
                playerController?.selectSubtitleTrack(index)
            }
            secondarySubtitleSelectionApplied = false
            applySecondarySubtitleSelectionIfNeeded()
        },
        onAddonSubtitleSelected = { addon ->
            selectedAddonSubtitleId = addon.id
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            persistAddonSubtitlePreference(addon)
            playerController?.setSubtitleUri(addon.url)
            secondarySubtitleSelectionApplied = false
            applySecondarySubtitleSelectionIfNeeded()
        },
        onFetchAddonSubtitles = { fetchAddonSubtitlesForActiveItem() },
        onSubtitleStyleChanged = PlayerSettingsRepository::setSubtitleStyle,
        onSubtitleDelayChanged = { delayMs -> setSubtitleDelay(delayMs) },
        onSubtitleDelayReset = { setSubtitleDelay(0) },
        onAutoSyncCapture = { captureSubtitleAutoSyncTime() },
        onAutoSyncCueSelected = { cue -> applySubtitleAutoSyncCue(cue) },
        onAutoSyncReload = { loadSubtitleAutoSyncCues(force = true) },
        onSubtitleModalDismissed = { showSubtitleModal = false },
        showVideoSettingsModal = showVideoSettingsModal,
        playerSettings = playerSettingsUiState,
        onVideoSettingsChanged = {
            playerController?.configureIosVideoOutput(PlayerSettingsRepository.uiState.value)
        },
        onVideoSettingsModalDismissed = { showVideoSettingsModal = false },
        showSourcesPanel = showSourcesPanel,
        sourceStreamsState = sourceStreamsState,
        activeSourceIdentityKey = activeSourceIdentityKey,
        activeSourceUrl = activeSourceUrl,
        activeStreamTitle = activeStreamTitle,
        onSourceFilterSelected = PlayerStreamsRepository::selectSourceFilter,
        onSourceStreamSelected = { stream -> selectSourceManually(stream) },
        onReloadSources = {
            val vid = activeVideoId
            if (vid != null) {
                PlayerStreamsRepository.loadSources(
                    type = contentType ?: parentMetaType,
                    videoId = vid,
                    parentMetaId = parentMetaId,
                    title = title,
                    season = activeSeasonNumber,
                    episode = activeEpisodeNumber,
                    forceRefresh = true,
                )
            }
        },
        onSourcesPanelDismissed = {
            showSourcesPanel = false
            controlsVisible = true
        },
        isSeries = isSeries,
        showEpisodesPanel = showEpisodesPanel,
        allEpisodes = playerMetaVideos,
        parentMetaType = parentMetaType,
        parentMetaId = parentMetaId,
        activeSeasonNumber = activeSeasonNumber,
        activeEpisodeNumber = activeEpisodeNumber,
        watchProgressByVideoId = watchProgressUiState.byVideoId,
        watchedKeys = watchedUiState.watchedKeys,
        blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
        episodeStreamsPanelState = episodeStreamsPanelState,
        episodeStreamsRepoState = episodeStreamsRepoState,
        onEpisodeSelectedForDownload = { episode ->
            selectDownloadedEpisodeForPlayback(
                parentMetaId = parentMetaId,
                episode = episode,
                onDownloadedEpisodeSelected = { item, video -> switchToDownloadedEpisode(item, video) },
            )
        },
        onEpisodeStreamsRequested = { episode ->
            // Autoplay-first, mirroring the native HUD's selectEpisode handling: the stream
            // list only opens as the fallback when auto-selection comes up empty.
            autoPlaySelectedEpisode(episode)
        },
        onEpisodeStreamFilterSelected = PlayerStreamsRepository::selectEpisodeStreamsFilter,
        onEpisodeStreamSelected = { stream, episode -> switchToEpisodeStream(stream, episode) },
        onBackToEpisodes = {
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
        },
        onReloadEpisodeStreams = {
            val episode = episodeStreamsPanelState.selectedEpisode
            if (episode != null) {
                PlayerStreamsRepository.loadEpisodeStreams(
                    type = contentType ?: parentMetaType,
                    videoId = episode.id,
                    parentMetaId = parentMetaId,
                    title = title,
                    season = episode.playbackSeasonNumber(),
                    episode = episode.playbackEpisodeNumber(),
                    sourceAffinity = sourceAffinity,
                    forceRefresh = true,
                )
            }
        },
        onEpisodesPanelDismissed = {
            showEpisodesPanel = false
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
            controlsVisible = true
        },
        showSubmitIntroModal = showSubmitIntroModal,
        activeVideoId = activeVideoId,
        metaUiState = metaUiState,
        displayedPositionMs = displayedPositionMs,
        submitIntroDurationSeconds = playbackSnapshot.durationMs.takeIf { it > 0L }?.let { it / 1000L },
        submitIntroSegmentType = submitIntroSegmentType,
        onSubmitIntroSegmentTypeChanged = { submitIntroSegmentType = it },
        submitIntroStartTimeStr = submitIntroStartTimeStr,
        onSubmitIntroStartTimeChanged = { submitIntroStartTimeStr = it },
        submitIntroEndTimeStr = submitIntroEndTimeStr,
        onSubmitIntroEndTimeChanged = { submitIntroEndTimeStr = it },
        onSubmitIntroDismissed = { showSubmitIntroModal = false },
        onSubmitIntroSuccess = {
            submitIntroStartTimeSec = 0.0
            submitIntroEndTimeSec = 0.0
            submitIntroStatusMessage = null
            submitIntroStartTimeStr = "00:00"
            submitIntroEndTimeStr = "00:00"
            submitIntroSegmentType = "intro"
            showSubmitIntroModal = false
        },
    )
}
