package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.addons.AddonsUiState
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaScreenSettingsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.p2p.P2pSettingsUiState
import com.nuvio.app.features.p2p.P2pStreamingState
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.watched.WatchedUiState
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal class PlayerScreenRuntime(
    args: PlayerScreenArgs,
) {
    var args by mutableStateOf(args)

    val title: String get() = args.title
    val sourceUrl: String get() = args.sourceUrl
    val sourceAudioUrl: String? get() = args.sourceAudioUrl
    val sourceHeaders: Map<String, String> get() = args.sourceHeaders
    val sourceResponseHeaders: Map<String, String> get() = args.sourceResponseHeaders
    val streamType: String? get() = args.streamType
    val sourceAffinity: PlayerSourceAffinity get() = args.sourceAffinity
    val providerName: String get() = args.providerName
    val streamTitle: String get() = args.streamTitle
    val streamSubtitle: String? get() = args.streamSubtitle
    val initialBingeGroup: String? get() = args.initialBingeGroup
    val pauseDescription: String? get() = args.pauseDescription
    val logo: String? get() = args.logo
    val poster: String? get() = args.poster
    val background: String? get() = args.background
    val seasonNumber: Int? get() = args.seasonNumber
    val episodeNumber: Int? get() = args.episodeNumber
    val episodeTitle: String? get() = args.episodeTitle
    val episodeThumbnail: String? get() = args.episodeThumbnail
    val releaseYear: Int? get() = args.releaseYear
    val contentType: String? get() = args.contentType
    val videoId: String? get() = args.videoId
    val parentMetaId: String get() = args.parentMetaId
    val parentMetaType: String get() = args.parentMetaType
    val watchProgressSource: String? get() = args.watchProgressSource
    val providerAddonId: String? get() = args.providerAddonId
    val torrentInfoHash: String? get() = args.torrentInfoHash
    val torrentFileIdx: Int? get() = args.torrentFileIdx
    val torrentFilename: String? get() = args.torrentFilename
    val torrentTrackers: List<String> get() = args.torrentTrackers
    val initialPositionMs: Long get() = args.initialPositionMs
    val initialProgressFraction: Float? get() = args.initialProgressFraction
    val disableProgressTracking: Boolean get() = args.disableProgressTracking
    val isProviderDiagnosticVideoPlayback: Boolean
        get() = activeSourceUrl == providerDiagnosticVideoSourceUrl
    val isProviderDiagnosticProbePending: Boolean
        get() = activeSourceUrl == providerDiagnosticProbePendingSourceUrl
    val progressTrackingDisabled: Boolean
        get() = disableProgressTracking ||
            playbackSourceFailureActive ||
            isProviderDiagnosticVideoPlayback ||
            isProviderDiagnosticProbePending
    val autoPlayMode: PlayerAutoPlayMode get() = args.autoPlayMode
    val isSeries: Boolean get() = parentMetaType == "series"

    lateinit var scope: CoroutineScope

    var playerSettingsUiState: PlayerSettingsUiState = PlayerSettingsUiState()
    var p2pSettingsUiState: P2pSettingsUiState = P2pSettingsUiState()
    var p2pStreamingState: P2pStreamingState = P2pStreamingState.Idle
    var metaScreenSettingsUiState: MetaScreenSettingsUiState = MetaScreenSettingsUiState()
    var watchedUiState: WatchedUiState = WatchedUiState()
    var watchProgressUiState: WatchProgressUiState = WatchProgressUiState()
    var sourceStreamsState by mutableStateOf(StreamsUiState())
    var episodeStreamsRepoState by mutableStateOf(StreamsUiState())
    var metaUiState: MetaDetailsUiState = MetaDetailsUiState()
    var addonsUiState: AddonsUiState = AddonsUiState()
    var addonSubtitles: List<AddonSubtitle> = emptyList()
    var isLoadingAddonSubtitles: Boolean = false
    var downloadingAddonSubtitleId by mutableStateOf<String?>(null)

    var horizontalSafePadding: Dp = 0.dp
    var metrics: PlayerLayoutMetrics = PlayerLayoutMetrics.fromWidth(0.dp)
    var sliderEdgePadding: Dp = 0.dp
    var overlayBottomPadding: Dp = 0.dp
    var resizeModeFitLabel: String = ""
    var resizeModeFillLabel: String = ""
    var resizeModeZoomLabel: String = ""
    var downloadedLabel: String = ""
    var subtitleSavedLabel: String = "Subtitle saved"
    var subtitleSaveFailedLabel: String = "Could not save subtitle"
    var airsPrefix: String = ""
    var tbaLabel: String = ""
    var genericUnknownLabel: String = ""
    var parentalGuideLabels: ParentalGuideLabels = ParentalGuideLabels("", "", "", "", "", "", "", "")

    var controlsVisible by mutableStateOf(true)
    var mouseActivitySignal by mutableStateOf(0)
    var nativeChromeInteractionActive by mutableStateOf(false)
    var playerControlsLocked by mutableStateOf(false)
    var activeSourceUrl by mutableStateOf(sourceUrl)
    var providerDiagnosticVideoSourceUrl by mutableStateOf(
        sourceUrl.takeIf(::isExplicitProviderDiagnosticVideoUrl),
    )
    var providerDiagnosticProbePendingSourceUrl by mutableStateOf(
        sourceUrl.takeIf(::isProviderPlaybackEndpoint),
    )
    var providerDiagnosticRecoveryAttemptedSourceUrl by mutableStateOf<String?>(null)
    var activeSourceAudioUrl by mutableStateOf(sourceAudioUrl)
    var activeSourceHeaders by mutableStateOf(sanitizePlaybackHeaders(sourceHeaders))
    var activeSourceResponseHeaders by mutableStateOf(sanitizePlaybackResponseHeaders(sourceResponseHeaders))
    var activeStreamType by mutableStateOf(streamType)
    var activeTorrentInfoHash by mutableStateOf(torrentInfoHash)
    var activeTorrentFileIdx by mutableStateOf(torrentFileIdx)
    var activeTorrentFilename by mutableStateOf(torrentFilename)
    var activeTorrentTrackers by mutableStateOf(torrentTrackers)
    var p2pResolvedSourceUrl by mutableStateOf<String?>(null)
    var activeSourceIdentityKey by mutableStateOf(
        args.sourceIdentityKey ?: torrentInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { hash ->
            "torrent:$hash:${torrentFileIdx ?: -1}"
        } ?: sourceUrl.trim().takeIf { it.isNotBlank() }?.let { url -> "url:$url" },
    )
    var activeStreamTitle by mutableStateOf(streamTitle)
    var activeStreamSubtitle by mutableStateOf(streamSubtitle)
    var activeProviderName by mutableStateOf(providerName)
    var activeProviderAddonId by mutableStateOf(providerAddonId)
    var activeWatchProgressSource by mutableStateOf(watchProgressSource)
    var currentStreamBingeGroup by mutableStateOf(initialBingeGroup)
    var activeSeasonNumber by mutableStateOf(seasonNumber)
    var activeEpisodeNumber by mutableStateOf(episodeNumber)
    var activeEpisodeTitle by mutableStateOf(episodeTitle)
    var activeEpisodeThumbnail by mutableStateOf(episodeThumbnail)
    // Artwork resolved after launch for metadata-less direct playback (pasted URL / dropped file),
    // where args carry no poster. Feeds the Discord presence image so it matches library playback.
    var adHocArtworkImageUrl by mutableStateOf<String?>(null)
    var activePauseDescription by mutableStateOf(pauseDescription)
    var activeVideoId by mutableStateOf(videoId)
    var activeInitialPositionMs by mutableStateOf(initialPositionMs)
    var activeInitialProgressFraction by mutableStateOf(initialProgressFraction)
    var shouldPlay by mutableStateOf(true)
    var playbackEndExitRequested by mutableStateOf(false)
    var resizeMode by mutableStateOf(playerSettingsUiState.resizeMode)
    var pictureInPictureActive by mutableStateOf(false)
    var layoutSize by mutableStateOf(IntSize.Zero)
    var playbackSnapshot by mutableStateOf(PlayerPlaybackSnapshot())
    // Monotonic ownership token for every player attach. Source URLs are not unique attempt
    // identities: a retry may reuse one, and the outgoing controller can still publish a final
    // snapshot while the replacement is being composed.
    var playbackAttemptId by mutableStateOf(1L)
    // Player-instance scoped: a user speed change survives episode/source replacement but a new
    // player session starts from the persisted default.
    var sessionPlaybackSpeed by mutableStateOf(1f)
    var lastTrustedPlaybackPositionMs by mutableStateOf(0L)
    // Last snapshot with a real duration and position for the CURRENT video. Teardown can hand
    // flushWatchProgress a zeroed placeholder; this is the fallback so an exit near the end still
    // records the final position (and its completion cascade) instead of being dropped.
    var lastMeaningfulPlaybackSnapshot by mutableStateOf<PlayerPlaybackSnapshot?>(null)
    var playerController by mutableStateOf<PlayerEngineController?>(null)
    var playerControllerSourceUrl by mutableStateOf<String?>(null)
    // Set only when the platform surface actually begins attaching the source. On desktop the
    // controller exists while its native Canvas is still waiting for a full-size paint, so using
    // controller readiness would incorrectly spend the startup-failover timeout before mpv starts.
    var playerAttachedSourceUrl by mutableStateOf<String?>(null)
    var playerAttachedAttemptId by mutableStateOf<Long?>(null)
    // FILE_LOADED and a non-loading snapshot are not sufficient proof that a provider delivered
    // playable media: stale debrid cache metadata can yield a response which opens but never
    // renders. Desktop sets this only from mpv's first-frame PLAYBACK_RESTART event.
    var playerStartedSourceUrl by mutableStateOf<String?>(null)
    var playerStartedAttemptId by mutableStateOf<Long?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var playbackFailureExitRequested by mutableStateOf(false)
    var playbackSourceFailureActive by mutableStateOf(false)
    var isScrubbingTimeline by mutableStateOf(false)
    var scrubbingPositionMs by mutableStateOf<Long?>(null)
    var pausedOverlayVisible by mutableStateOf(false)
    var pausedOverlayInteractionSignal by mutableStateOf(0)
    var gestureFeedback by mutableStateOf<GestureFeedbackState?>(null)
    var liveGestureFeedback by mutableStateOf<GestureFeedbackState?>(null)
    var renderedGestureFeedback by mutableStateOf<GestureFeedbackState?>(null)
    var lockedOverlayVisible by mutableStateOf(false)
    var gestureMessageJob by mutableStateOf<Job?>(null)
    var accumulatedSeekResetJob by mutableStateOf<Job?>(null)
    var seekProgressSyncJob by mutableStateOf<Job?>(null)
    var accumulatedSeekState by mutableStateOf<PlayerAccumulatedSeekState?>(null)
    var initialLoadCompleted by mutableStateOf(false)
    var defaultPlaybackSpeedApplied by mutableStateOf(false)
    var initialSeekApplied by mutableStateOf(
        initialPositionMs <= 0L && ((initialProgressFraction ?: 0f) <= 0f),
    )
    var lastProgressPersistEpochMs by mutableStateOf(0L)
    var previousIsPlaying by mutableStateOf(false)
    var hasRequestedScrobbleStartForCurrentItem by mutableStateOf(false)
    var scrobbleStartRequestGeneration by mutableStateOf(0L)
    var pendingScrobbleStartAfterSeek by mutableStateOf(false)
    var hasSentCompletionScrobbleForCurrentItem by mutableStateOf(false)
    var currentTrackingScrobbleMedia by mutableStateOf<TrackingMediaReference?>(null)

    var showSourcesPanel by mutableStateOf(false)
    var showEpisodesPanel by mutableStateOf(false)
    var showSubmitIntroModal by mutableStateOf(false)
    var submitIntroSegmentType by mutableStateOf("intro")
    var submitIntroStartTimeStr by mutableStateOf("00:00")
    var submitIntroEndTimeStr by mutableStateOf("00:00")
    var submitIntroStartTimeSec by mutableStateOf<Double?>(0.0)
    var submitIntroEndTimeSec by mutableStateOf<Double?>(0.0)
    var isSubmitIntroSubmitting by mutableStateOf(false)
    var submitIntroStatusMessage by mutableStateOf<String?>(null)
    var playerControlsPendingP2pSwitch by mutableStateOf<PendingPlayerP2pSwitch?>(null)
    var playerControlsCloseModalsToken by mutableStateOf(0L)
    var episodeStreamsPanelState by mutableStateOf(EpisodeStreamsPanelState())
    var playerMetaVideos by mutableStateOf<List<MetaVideo>>(emptyList())
    var playerChapters by mutableStateOf<List<PlayerChapter>>(emptyList())
    var skipIntervals by mutableStateOf<List<SkipInterval>>(emptyList())
    var communitySkipIntervals by mutableStateOf<List<SkipInterval>>(emptyList())
    var chapterSkipIntervals by mutableStateOf<List<SkipInterval>>(emptyList())
    var activeSkipInterval by mutableStateOf<SkipInterval?>(null)
    var skipIntervalDismissed by mutableStateOf(false)
    var parentalWarnings by mutableStateOf<List<ParentalWarning>>(emptyList())
    var showParentalGuide by mutableStateOf(false)
    var parentalGuideHasShown by mutableStateOf(false)
    var playbackStartedForParentalGuide by mutableStateOf(false)
    var nextEpisodeInfo by mutableStateOf<NextEpisodeInfo?>(null)
    var showNextEpisodeCard by mutableStateOf(false)
    // Set while a user-initiated episode switch (next-episode button or episode selector) is
    // loading streams in the background, so the next-episode card can double as "we heard you,
    // loading…" feedback. Holds the episode being switched to — which is NOT necessarily the
    // sequential next episode — and is cleared once the switch resolves (plays, falls back to the
    // manual stream list, or is dismissed). Distinct from showNextEpisodeCard, which the
    // end-of-episode threshold/EOF logic owns.
    var manualEpisodeSwitchInfo by mutableStateOf<NextEpisodeInfo?>(null)
    var nextEpisodeAutoPlaySearching by mutableStateOf(false)
    var nextEpisodeAutoPlaySourceName by mutableStateOf<String?>(null)
    var nextEpisodeAutoPlayCountdown by mutableStateOf<Int?>(null)
    var nextEpisodeAutoPlayJob by mutableStateOf<Job?>(null)
    // Latch that allows the next-episode auto-advance to fire at most once per episode. Set when
    // an advance is initiated; cleared only once the new episode is genuinely playing. Prevents a
    // stale end-of-file (which lingers while the next stream loads — common with MPV + slow addons)
    // from triggering a SECOND advance and skipping an episode.
    var nextEpisodeAdvanceInProgress by mutableStateOf(false)
    // The latch belongs to one concrete destination episode. A first frame from any other
    // playback attempt must not release it.
    var nextEpisodeAdvanceTargetVideoId by mutableStateOf<String?>(null)
    var nextEpisodeThresholdStableSamples by mutableStateOf(0)
    var pendingP2pSwitch by mutableStateOf<PendingPlayerP2pSwitch?>(null)
    var credentialRefreshJob by mutableStateOf<Job?>(null)
    var credentialRefreshAttemptedSourceUrl by mutableStateOf<String?>(null)

    var showAudioModal by mutableStateOf(false)
    var showSubtitleModal by mutableStateOf(false)
    var showVideoSettingsModal by mutableStateOf(false)
    var audioTracks by mutableStateOf<List<AudioTrack>>(emptyList())
    var subtitleTracks by mutableStateOf<List<SubtitleTrack>>(emptyList())
    var selectedAudioIndex by mutableStateOf(-1)
    var selectedSubtitleIndex by mutableStateOf(-1)
    var selectedAddonSubtitleId by mutableStateOf<String?>(null)
    var useCustomSubtitles by mutableStateOf(false)
    var preferredAudioSelectionApplied by mutableStateOf(false)
    var preferredSubtitleSelectionApplied by mutableStateOf(false)
    // A native subtitle selection is only reflected in the UI after the refreshed mpv track
    // list reports it selected. This prevents a command issued during file startup from leaving
    // a stale checkmark when mpv has not accepted/applied the track yet.
    var pendingSubtitleSelectionIndex by mutableStateOf<Int?>(null)
    var secondarySubtitleSelectionApplied by mutableStateOf(false)
    var activeSubtitleTab by mutableStateOf(SubtitleTab.BuiltIn)
    var autoFetchedAddonSubtitlesForKey by mutableStateOf<String?>(null)
    var completedAutoAddonSubtitleFetchForKey by mutableStateOf<String?>(null)
    var trackPreferenceRestoreApplied by mutableStateOf(false)
    var subtitleDelayMs by mutableStateOf(0)
    var subtitleAutoSyncState by mutableStateOf(SubtitleAutoSyncUiState())

    var lastSyncedSettingsResizeMode: PlayerResizeMode? = null
    var lastResetPlaybackIdentity: String? = null
    var lastResetVideoIdentity: String? = null

    // Stream failover: identity keys of sources already tried (and failed) for the current item, so
    // failover walks down the list without re-trying the same dead stream. Cleared on manual source
    // pick, episode change, and sustained successful playback. [failoverInProgress] guards against a
    // second failover firing (e.g. error + watchdog together) and tells switchToSource not to clear
    // the tried-set when the swap is failover-initiated rather than user-initiated.
    val failoverTriedIdentityKeys: MutableSet<String> = mutableSetOf()
    var failoverInProgress by mutableStateOf(false)
    var failoverJob: Job? = null
}
