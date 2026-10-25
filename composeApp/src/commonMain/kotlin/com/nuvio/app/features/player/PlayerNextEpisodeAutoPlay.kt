package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.playbackEpisodeNumber
import com.nuvio.app.features.details.playbackSeasonNumber
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.streams.BingeReleaseHint
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlayPolicy
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamScoreRepository
import com.nuvio.app.features.streams.StreamScoreContext
import com.nuvio.app.features.streams.StreamScoreContexts
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun eligibleAutoPlayStreams(
    streams: List<StreamItem>,
    sourceAffinity: PlayerSourceAffinity,
): List<StreamItem> = when (sourceAffinity) {
    PlayerSourceAffinity.Local -> streams
    PlayerSourceAffinity.Stream -> streams.filterNot { it.streamType.equals("local", ignoreCase = true) }
}

/**
 * Whether the next episode should first try the same binge group as the stream currently playing.
 *
 * This is a same-session preference keyed on the live in-memory binge group, so it stands on the
 * "Prefer Binge Group" toggle alone. The separate "Reuse Binge Group" toggle governs only the
 * cross-session persisted cache read in `StreamsRepository`, and must not gate this — previously it
 * did, so turning Reuse off silently disabled binge preference for next-episode autoplay even with
 * Prefer still on, which contradicted both toggles' descriptions.
 */
internal fun shouldPreferNextEpisodeBingeGroup(
    sourceAffinity: PlayerSourceAffinity,
    preferenceEnabled: Boolean,
    scoreOverridesBingeGroup: Boolean = false,
): Boolean = sourceAffinity == PlayerSourceAffinity.Stream &&
    preferenceEnabled &&
    !scoreOverridesBingeGroup

/**
 * Whether a next-episode transition should hand the choice back to the user instead of picking a
 * stream for them ("Apply To Next Episode", under Stream Auto-Play).
 *
 * This path has always substituted [StreamAutoPlayMode.FIRST_STREAM] over an unfiltered list when
 * the mode is MANUAL, so a transition can never stall waiting on a picker. That is a sensible
 * default and a surprising one: the setting whose whole meaning is "always let me choose" stopped
 * applying the moment binge or the next-episode card advanced, and nothing on screen said so. The
 * toggle makes the substitution a choice.
 *
 * Local affinity keeps the substitution unconditionally: it exists to take the on-disk file, which
 * is not a source choice to offer.
 */
internal fun shouldOpenManualNextEpisodeSelection(
    mode: StreamAutoPlayMode,
    manualNextEpisodeEnabled: Boolean,
    sourceAffinity: PlayerSourceAffinity,
): Boolean = mode == StreamAutoPlayMode.MANUAL &&
    manualNextEpisodeEnabled &&
    sourceAffinity != PlayerSourceAffinity.Local

internal fun CoroutineScope.launchPlayerNextEpisodeAutoPlay(
    previousJob: Job?,
    nextEpisodeInfo: NextEpisodeInfo?,
    allEpisodes: List<MetaVideo>,
    parentMetaId: String,
    parentMetaType: String,
    contentType: String?,
    settings: PlayerSettingsUiState,
    sourceAffinity: PlayerSourceAffinity,
    currentStreamBingeGroup: String?,
    /** The stream playing now, whose release (filename, infohash) is picked again when it is offered. */
    currentStream: StreamItem? = null,
    onDownloadedEpisodeSelected: (DownloadItem, MetaVideo) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onManualSelectionRequired: (MetaVideo) -> Unit,
    onSearchingChanged: (Boolean) -> Unit,
    onSourceNameChanged: (String?) -> Unit,
    onCountdownChanged: (Int?) -> Unit,
    onNextEpisodeCardVisibleChanged: (Boolean) -> Unit,
    skipSourceCountdown: Boolean = false,
    emptyResultRetriesRemaining: Int = 1,
    /** False skips a completed download of the next episode (a playlist with local files off). */
    allowDownloaded: Boolean = true,
    /** Use what Preload Next Episode Sources found; false on the empty-result retry, which must fetch. */
    usePreloadedSources: Boolean = true,
): Job? {
    val nextVideoId = nextEpisodeInfo?.videoId ?: return null
    val nextVideo = allEpisodes.firstOrNull { video -> video.id == nextVideoId } ?: return null
    if (nextEpisodeInfo.hasAired != true) return null
    val nextSeasonNumber = nextVideo.playbackSeasonNumber()
    val nextEpisodeNumber = nextVideo.playbackEpisodeNumber()

    val downloadedNextEpisode = if (!allowDownloaded) null else DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = nextSeasonNumber,
        episodeNumber = nextEpisodeNumber,
        videoId = nextVideo.id,
    )
    if (downloadedNextEpisode != null) {
        onDownloadedEpisodeSelected(downloadedNextEpisode, nextVideo)
        return null
    }

    // Opted in to choosing the source for every episode: open the picker straight away rather than
    // running a 3-20s search whose result would be thrown away. Returns no job, which the callers
    // already handle (they clear the loading card when nothing was launched).
    if (
        shouldOpenManualNextEpisodeSelection(
            mode = settings.streamAutoPlayMode,
            manualNextEpisodeEnabled = settings.streamAutoPlayManualNextEpisode,
            sourceAffinity = sourceAffinity,
        )
    ) {
        BingeAdvanceLog.i {
            "manual next-episode selection for S${nextSeasonNumber}E${nextEpisodeNumber}; " +
                "opening source list without searching"
        }
        previousJob?.cancel()
        onSearchingChanged(false)
        onSourceNameChanged(null)
        onCountdownChanged(null)
        // Deliberately leaves the next-episode card up, unlike every other exit from this
        // function: the panel is a choice the user can back out of, and the card is how they get
        // back to it. It clears on its own once the picked episode starts playing.
        onManualSelectionRequired(nextVideo)
        return null
    }

    previousJob?.cancel()
    onSearchingChanged(true)
    onSourceNameChanged(null)
    onCountdownChanged(null)

    val type = contentType ?: parentMetaType
    val shouldUseFirstAvailable = sourceAffinity == PlayerSourceAffinity.Local ||
        settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL

    val scoreProfile = StreamScoreRepository.profile
    val configuredMode = if (shouldUseFirstAvailable) {
        StreamAutoPlayMode.FIRST_STREAM
    } else {
        settings.streamAutoPlayMode
    }
    // The first-play path runs the configured mode through this policy (StreamsRepository); this one
    // never did, so "Apply scoring to -> choosing the first stream" governed the episode you started
    // by hand and then silently stopped applying to every episode binge advanced into.
    //
    // Local affinity is excluded: it forces FIRST_STREAM over an unfiltered list precisely to take
    // the on-disk file, and ranking that list could float a remote stream above it.
    val effectiveMode = if (sourceAffinity == PlayerSourceAffinity.Local) {
        configuredMode
    } else {
        StreamAutoPlayPolicy.effectiveMode(configuredMode, scoreProfile)
    }
    val effectiveSource = if (shouldUseFirstAvailable) {
        StreamAutoPlaySource.ALL_SOURCES
    } else {
        settings.streamAutoPlaySource
    }
    val effectiveSelectedAddons = if (shouldUseFirstAvailable) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedAddons
    }
    val effectiveSelectedPlugins = if (shouldUseFirstAvailable) {
        emptySet()
    } else {
        settings.streamAutoPlaySelectedPlugins
    }
    val effectiveRegex = if (shouldUseFirstAvailable) {
        ""
    } else {
        settings.streamAutoPlayRegex
    }
    val shouldPreferBingeGroup = shouldPreferNextEpisodeBingeGroup(
        sourceAffinity = sourceAffinity,
        preferenceEnabled = settings.streamAutoPlayPreferBingeGroup,
        scoreOverridesBingeGroup = StreamAutoPlayPolicy.scoreOverridesBingeGroup(effectiveMode, scoreProfile),
    )
    val preferredBingeGroup = if (shouldPreferBingeGroup) {
        currentStreamBingeGroup
    } else {
        null
    }
    val preferredRelease = if (preferredBingeGroup != null && currentStream?.behaviorHints?.bingeGroup == preferredBingeGroup) {
        BingeReleaseHint.forEpisode(
            lastFilename = currentStream.behaviorHints.filename,
            lastInfoHash = currentStream.p2pInfoHash,
            season = nextSeasonNumber,
            episode = nextEpisodeNumber,
        )
    } else {
        null
    }

    return launch {
        // forceRefresh is essential here. Without it, loadEpisodeStreams dedups against the last
        // request key + current state: if this episode was already loaded into a terminal EMPTY
        // state earlier (e.g. a transient no-streams result from opening it in the episodes panel,
        // or a prior binge attempt), the guard skips the fetch entirely and the collector below
        // immediately sees "no streams, not loading" → falls back to manual selection and binge
        // silently stalls. Forcing a fresh fetch guarantees the search actually runs. (Diagnosed
        // from BingeAdvance logs: "autoplay search finished ... selected=false" with no stream
        // fetch logged at all.)
        PlayerStreamsRepository.loadEpisodeStreams(
            type = type,
            videoId = nextVideo.id,
            parentMetaId = parentMetaId,
            season = nextSeasonNumber,
            episode = nextEpisodeNumber,
            sourceAffinity = sourceAffinity,
            forceRefresh = true,
            readPrefetchOnForceRefresh = usePreloadedSources && settings.preloadNextEpisodeSources,
        )
        // The episodes panel loads into the same state. A search running while the viewer browses
        // another episode's sources must not read those streams as this episode's — ignore any
        // state that belongs to a later load (worst case: no selection, and the retry / source
        // list takes over, instead of playing the wrong episode).
        val ownRequestKey = PlayerStreamsRepository.episodeStreamsRequestKey()
        fun ownState(): StreamsUiState =
            if (PlayerStreamsRepository.episodeStreamsRequestKey() == ownRequestKey) {
                PlayerStreamsRepository.episodeStreamsState.value
            } else {
                StreamsUiState()
            }

        val installedAddonNames = AddonRepository.uiState.value.addons
            .enabledAddons()
            .map { it.displayTitle }
            .toSet()
        val debridSettings = DebridSettingsRepository.snapshot()

        val timeoutSeconds = settings.streamAutoPlayTimeoutSeconds
        var autoSelectTriggered = false
        var timeoutElapsed = false
        var selectedStream: StreamItem? = null
        val autoSelectSettled = CompletableDeferred<Unit>()

        fun settleAutoSelect() {
            if (!autoSelectSettled.isCompleted) {
                autoSelectSettled.complete(Unit)
            }
        }

        fun selectStream(stream: StreamItem) {
            autoSelectTriggered = true
            selectedStream = stream
            settleAutoSelect()
        }

        fun finishWithoutSelection() {
            autoSelectTriggered = true
            settleAutoSelect()
        }

        fun trySelectStream(streams: List<StreamItem>): StreamItem? {
            val configuredSelection = StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
                preferredBingeGroup = preferredBingeGroup,
                preferredRelease = preferredRelease,
                preferBingeGroupInSelection = shouldPreferBingeGroup,
                bingeGroupOnly = false,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
                scoreProfile = scoreProfile,
                scoreContext = StreamScoreContexts.forPlayback(
                    isEpisode = true,
                    contentId = parentMetaId,
                    contentType = contentType ?: parentMetaType,
                ),
            )
            if (configuredSelection != null) return configuredSelection

            return StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = StreamAutoPlayMode.FIRST_STREAM,
                regexPattern = "",
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
                preferredBingeGroup = preferredBingeGroup,
                preferredRelease = preferredRelease,
                preferBingeGroupInSelection = shouldPreferBingeGroup,
                bingeGroupOnly = false,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
            )
        }

        fun tryBingeGroupOnly(streams: List<StreamItem>): StreamItem? {
            if (preferredBingeGroup == null || !shouldPreferBingeGroup) return null
            return StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
                preferredBingeGroup = preferredBingeGroup,
                preferredRelease = preferredRelease,
                preferBingeGroupInSelection = true,
                bingeGroupOnly = true,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
            )
        }

        val innerJob = launch {
            PlayerStreamsRepository.episodeStreamsState.collectLatest { state ->
                if (PlayerStreamsRepository.episodeStreamsRequestKey() != ownRequestKey) return@collectLatest
                if (state.groups.isEmpty() && state.isAnyLoading) return@collectLatest

                val allStreams = eligibleAutoPlayStreams(
                    streams = state.groups.flatMap { it.streams },
                    sourceAffinity = sourceAffinity,
                )

                if (autoSelectTriggered) {
                    // Already resolved.
                } else if (timeoutElapsed) {
                    if (allStreams.isNotEmpty()) {
                        val candidate = trySelectStream(allStreams)
                        if (candidate != null) {
                            selectStream(candidate)
                        }
                    }
                } else if (allStreams.isNotEmpty()) {
                    val earlyMatch = tryBingeGroupOnly(allStreams)
                    if (earlyMatch != null) {
                        selectStream(earlyMatch)
                    }
                }

                if (!autoSelectTriggered && !state.isAnyLoading) {
                    if (allStreams.isNotEmpty()) {
                        val candidate = trySelectStream(allStreams)
                        if (candidate != null) {
                            selectStream(candidate)
                        }
                    }
                    if (!autoSelectTriggered) {
                        finishWithoutSelection()
                    }
                    return@collectLatest
                }

                if (autoSelectTriggered) return@collectLatest
            }
        }

        val timeoutMs = timeoutSeconds * 1_000L
        val isBoundedTimeout = timeoutSeconds in 1..30

        if (isBoundedTimeout) {
            // A local-affinity load publishes its terminal local group synchronously. Await the
            // collector first so that result starts immediately; the old unconditional delay made
            // even an already-found on-disk episode wait for the full online-stream timeout.
            val settledBeforeTimeout = withTimeoutOrNull(timeoutMs) {
                autoSelectSettled.await()
            } != null
            if (!settledBeforeTimeout) {
                timeoutElapsed = true
                if (!autoSelectTriggered) {
                    val allStreams = eligibleAutoPlayStreams(
                        ownState().groups.flatMap { it.streams },
                        sourceAffinity,
                    )
                    // Deliberately no give-up branch here: streams having arrived tells us nothing
                    // about whether a *selectable* one is still in flight. When auto-play is scoped
                    // to specific addons, a hundred streams from unscoped providers are all
                    // unselectable, so bailing out on "streams exist but none matched" abandoned the
                    // search while the only eligible provider was still loading. Fall through to the
                    // grace period and let it decide. (Diagnosed from BingeAdvance logs: S9E9
                    // finished at the 3s timeout with streams=34 selected=false, and the scoped
                    // addon's single eligible stream landed 127ms later.)
                    if (allStreams.isNotEmpty()) {
                        trySelectStream(allStreams)?.let(::selectStream)
                    }
                }
                if (!autoSelectTriggered) {
                    // Once the configured selection delay has elapsed, keep listening. The
                    // collector is now in timeoutElapsed mode, so the first eligible addon
                    // response selects immediately rather than making every search pay the full
                    // window. The window is bounded by what can still change the answer rather
                    // than by a flat wall clock: a provider auto-play is *scoped to* being in
                    // flight is the only thing that can still produce a selectable stream, so
                    // give up early when none are, and keep waiting when one is. (S9E2 in
                    // nuvio(12).log: 304 unselectable streams in hand at the 3s deadline, while
                    // the single scoped addon took 13161ms — past the old flat 7s window.)
                    awaitNextEpisodeGraceWindow(
                        autoSelectSettled = autoSelectSettled,
                        hasScopedProviderInFlight = {
                            hasScopedAutoPlayProviderInFlight(
                                groups = ownState().groups,
                                installedAddonNames = installedAddonNames,
                                source = effectiveSource,
                                selectedAddons = effectiveSelectedAddons,
                                selectedPlugins = effectiveSelectedPlugins,
                            )
                        },
                    )
                    if (!autoSelectTriggered) {
                        val allStreams = eligibleAutoPlayStreams(
                            ownState().groups.flatMap { it.streams },
                            sourceAffinity,
                        )
                        if (allStreams.isNotEmpty()) {
                            selectedStream = trySelectStream(allStreams)
                        }
                        finishWithoutSelection()
                    }
                }
            }
            innerJob.cancel()
        } else {
            timeoutElapsed = true
            if (!autoSelectTriggered) {
                val allStreams = eligibleAutoPlayStreams(
                    ownState().groups.flatMap { it.streams },
                    sourceAffinity,
                )
                if (allStreams.isNotEmpty()) {
                    trySelectStream(allStreams)?.let(::selectStream)
                }
            }
            val completed = withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { autoSelectSettled.await() }
            innerJob.cancel()
            if (completed == null && !autoSelectTriggered) {
                val allStreams = eligibleAutoPlayStreams(
                    ownState().groups.flatMap { it.streams },
                    sourceAffinity,
                )
                if (allStreams.isNotEmpty()) {
                    selectedStream = trySelectStream(allStreams)
                }
                finishWithoutSelection()
            }
        }

        val selected = selectedStream
        val finalState = ownState()
        val providerCount = finalState.groups.size
        val loadingProviderCount = finalState.groups.count { it.isLoading }
        val errorProviderCount = finalState.groups.count { !it.error.isNullOrBlank() }
        val returnedStreamCount = finalState.groups.sumOf { it.streams.size }
        BingeAdvanceLog.i {
            "autoplay search finished for S${nextSeasonNumber}E${nextEpisodeNumber} " +
                "selected=${selected != null} source=${selected?.addonName ?: "<none>"} " +
                "providers=$providerCount loading=$loadingProviderCount errors=$errorProviderCount " +
                "streams=$returnedStreamCount retriesRemaining=$emptyResultRetriesRemaining"
        }
        if (shouldRetryEmptyNextEpisodeSearch(selected, emptyResultRetriesRemaining)) {
            BingeAdvanceLog.i {
                "autoplay search returned no selectable stream for S${nextSeasonNumber}E${nextEpisodeNumber}; " +
                    "retrying once with a fresh provider fetch"
            }
            delay(NEXT_EPISODE_EMPTY_RESULT_RETRY_DELAY_MS)
            launchPlayerNextEpisodeAutoPlay(
                previousJob = null,
                nextEpisodeInfo = nextEpisodeInfo,
                allEpisodes = allEpisodes,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                contentType = contentType,
                settings = settings,
                sourceAffinity = sourceAffinity,
                currentStreamBingeGroup = currentStreamBingeGroup,
                currentStream = currentStream,
                onDownloadedEpisodeSelected = onDownloadedEpisodeSelected,
                onEpisodeStreamSelected = onEpisodeStreamSelected,
                onManualSelectionRequired = onManualSelectionRequired,
                onSearchingChanged = onSearchingChanged,
                onSourceNameChanged = onSourceNameChanged,
                onCountdownChanged = onCountdownChanged,
                onNextEpisodeCardVisibleChanged = onNextEpisodeCardVisibleChanged,
                skipSourceCountdown = skipSourceCountdown,
                emptyResultRetriesRemaining = emptyResultRetriesRemaining - 1,
                allowDownloaded = allowDownloaded,
                usePreloadedSources = false,
            )
            return@launch
        }

        onSearchingChanged(false)
        if (selected != null) {
            onSourceNameChanged(selected.addonName)
            if (skipSourceCountdown || sourceAffinity == PlayerSourceAffinity.Local) {
                onCountdownChanged(null)
            } else {
                for (i in 3 downTo 1) {
                    onCountdownChanged(i)
                    delay(1000)
                }
            }
            onEpisodeStreamSelected(selected, nextVideo)
            onNextEpisodeCardVisibleChanged(false)
            onCountdownChanged(null)
            onSourceNameChanged(null)
        } else {
            BingeAdvanceLog.i {
                "autoplay search exhausted retry for S${nextSeasonNumber}E${nextEpisodeNumber}; " +
                    "opening manual source selection"
            }
            onManualSelectionRequired(nextVideo)
            onNextEpisodeCardVisibleChanged(false)
        }
    }
}

/**
 * Minimum grace window. Held even once every scoped provider has settled, because a settled
 * provider's stream can still flip from unselectable to playable when its debrid cache check
 * resolves — that runs on its own pipeline and never shows up as provider loading.
 */
private const val NEXT_EPISODE_ADDON_GRACE_MS = 7_000L

/**
 * Hard ceiling on the grace window, measured from where [NEXT_EPISODE_ADDON_GRACE_MS] starts.
 * Only reached when a scoped provider stays in flight that long; a slow-but-alive addon has been
 * observed at 13s, so the ceiling has to clear that with room to spare.
 */
private const val NEXT_EPISODE_SCOPED_PROVIDER_CEILING_MS = 20_000L

/** How often the extended window re-checks whether a scoped provider is still in flight. */
private const val NEXT_EPISODE_SCOPED_PROVIDER_POLL_MS = 250L

private const val NEXT_EPISODE_EMPTY_RESULT_RETRY_DELAY_MS = 500L

/**
 * Waits for the auto-play search to settle, up to [NEXT_EPISODE_ADDON_GRACE_MS] unconditionally
 * and beyond that only while a provider that could actually satisfy the selection is still
 * loading, capped at [NEXT_EPISODE_SCOPED_PROVIDER_CEILING_MS]. Never waits *less* than the base
 * window, so it cannot shorten any case the flat window already handled.
 */
private suspend fun awaitNextEpisodeGraceWindow(
    autoSelectSettled: CompletableDeferred<Unit>,
    hasScopedProviderInFlight: () -> Boolean,
) {
    withTimeoutOrNull(NEXT_EPISODE_ADDON_GRACE_MS) { autoSelectSettled.await() }
    if (autoSelectSettled.isCompleted) return

    var extendedWaitMs = 0L
    while (
        !autoSelectSettled.isCompleted &&
        extendedWaitMs < NEXT_EPISODE_SCOPED_PROVIDER_CEILING_MS - NEXT_EPISODE_ADDON_GRACE_MS &&
        hasScopedProviderInFlight()
    ) {
        withTimeoutOrNull(NEXT_EPISODE_SCOPED_PROVIDER_POLL_MS) { autoSelectSettled.await() }
        extendedWaitMs += NEXT_EPISODE_SCOPED_PROVIDER_POLL_MS
    }
}

/**
 * True when a provider whose streams auto-play is allowed to select is still loading. Mirrors the
 * source/addon/plugin scoping in `StreamAutoPlaySelector.evaluateAutoPlayStream`, at provider
 * granularity: an unscoped provider can return a thousand streams without any of them being
 * selectable, so its being in flight is no reason to keep waiting.
 */
internal fun hasScopedAutoPlayProviderInFlight(
    groups: List<AddonStreamGroup>,
    installedAddonNames: Set<String>,
    source: StreamAutoPlaySource,
    selectedAddons: Set<String>,
    selectedPlugins: Set<String>,
): Boolean = groups.any { group ->
    group.isLoading && isScopedAutoPlayProvider(
        group = group,
        installedAddonNames = installedAddonNames,
        source = source,
        selectedAddons = selectedAddons,
        selectedPlugins = selectedPlugins,
    )
}

internal fun isScopedAutoPlayProvider(
    group: AddonStreamGroup,
    installedAddonNames: Set<String>,
    source: StreamAutoPlaySource,
    selectedAddons: Set<String>,
    selectedPlugins: Set<String>,
): Boolean {
    val isAddonProvider = group.addonName in installedAddonNames
    val allowedBySource = when (source) {
        StreamAutoPlaySource.ALL_SOURCES -> true
        StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> isAddonProvider
        StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> !isAddonProvider
    }
    if (!allowedBySource) return false
    return if (isAddonProvider) {
        selectedAddons.isEmpty() || group.addonName in selectedAddons
    } else {
        selectedPlugins.isEmpty() || group.addonName in selectedPlugins
    }
}

internal fun shouldRetryEmptyNextEpisodeSearch(
    selectedStream: StreamItem?,
    retriesRemaining: Int,
): Boolean = selectedStream == null && retriesRemaining > 0
