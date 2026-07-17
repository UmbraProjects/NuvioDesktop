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
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
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

internal fun shouldReuseNextEpisodeBingeGroup(
    sourceAffinity: PlayerSourceAffinity,
    reuseEnabled: Boolean,
    preferenceEnabled: Boolean,
): Boolean = sourceAffinity == PlayerSourceAffinity.Stream && reuseEnabled && preferenceEnabled

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
    onDownloadedEpisodeSelected: (DownloadItem, MetaVideo) -> Unit,
    onEpisodeStreamSelected: (StreamItem, MetaVideo) -> Unit,
    onManualSelectionRequired: (MetaVideo) -> Unit,
    onSearchingChanged: (Boolean) -> Unit,
    onSourceNameChanged: (String?) -> Unit,
    onCountdownChanged: (Int?) -> Unit,
    onNextEpisodeCardVisibleChanged: (Boolean) -> Unit,
    skipSourceCountdown: Boolean = false,
): Job? {
    val nextVideoId = nextEpisodeInfo?.videoId ?: return null
    val nextVideo = allEpisodes.firstOrNull { video -> video.id == nextVideoId } ?: return null
    if (nextEpisodeInfo.hasAired != true) return null
    val nextSeasonNumber = nextVideo.playbackSeasonNumber()
    val nextEpisodeNumber = nextVideo.playbackEpisodeNumber()

    val downloadedNextEpisode = DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = nextSeasonNumber,
        episodeNumber = nextEpisodeNumber,
        videoId = nextVideo.id,
    )
    if (downloadedNextEpisode != null) {
        onDownloadedEpisodeSelected(downloadedNextEpisode, nextVideo)
        return null
    }

    previousJob?.cancel()
    onSearchingChanged(true)
    onSourceNameChanged(null)
    onCountdownChanged(null)

    val type = contentType ?: parentMetaType
    val shouldUseFirstAvailable = sourceAffinity == PlayerSourceAffinity.Local ||
        settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL

    val effectiveMode = if (shouldUseFirstAvailable) {
        StreamAutoPlayMode.FIRST_STREAM
    } else {
        settings.streamAutoPlayMode
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
    val shouldReuseBingeGroup = shouldReuseNextEpisodeBingeGroup(
        sourceAffinity = sourceAffinity,
        reuseEnabled = settings.streamAutoPlayReuseBingeGroup,
        preferenceEnabled = settings.streamAutoPlayPreferBingeGroup,
    )
    val preferredBingeGroup = if (shouldReuseBingeGroup) {
        currentStreamBingeGroup
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
        )

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
                preferBingeGroupInSelection = shouldReuseBingeGroup,
                bingeGroupOnly = false,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
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
                preferBingeGroupInSelection = shouldReuseBingeGroup,
                bingeGroupOnly = false,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
            )
        }

        fun tryBingeGroupOnly(streams: List<StreamItem>): StreamItem? {
            if (preferredBingeGroup == null || !shouldReuseBingeGroup) return null
            return StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = effectiveMode,
                regexPattern = effectiveRegex,
                source = effectiveSource,
                installedAddonNames = installedAddonNames,
                selectedAddons = effectiveSelectedAddons,
                selectedPlugins = effectiveSelectedPlugins,
                preferredBingeGroup = preferredBingeGroup,
                preferBingeGroupInSelection = true,
                bingeGroupOnly = true,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                activeResolverProviderId = debridSettings.activeResolverProviderId,
            )
        }

        val innerJob = launch {
            PlayerStreamsRepository.episodeStreamsState.collectLatest { state ->
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
                        PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams },
                        sourceAffinity,
                    )
                    if (allStreams.isNotEmpty()) {
                        val candidate = trySelectStream(allStreams)
                        if (candidate != null) {
                            selectStream(candidate)
                        } else {
                            finishWithoutSelection()
                        }
                    }
                }
                if (!autoSelectTriggered) {
                    // Once the configured selection delay has elapsed, keep listening for a
                    // bounded seven-second grace period. The collector is now in timeoutElapsed
                    // mode, so the first eligible addon response selects immediately rather than
                    // making every search pay the full grace period.
                    val completed = withTimeoutOrNull(NEXT_EPISODE_ADDON_GRACE_MS) {
                        autoSelectSettled.await()
                    }
                    if (completed == null && !autoSelectTriggered) {
                        val allStreams = eligibleAutoPlayStreams(
                            PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams },
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
                    PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams },
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
                    PlayerStreamsRepository.episodeStreamsState.value.groups.flatMap { it.streams },
                    sourceAffinity,
                )
                if (allStreams.isNotEmpty()) {
                    selectedStream = trySelectStream(allStreams)
                }
                finishWithoutSelection()
            }
        }

        onSearchingChanged(false)
        val selected = selectedStream
        BingeAdvanceLog.i {
            "autoplay search finished for S${nextSeasonNumber}E${nextEpisodeNumber} " +
                "selected=${selected != null} source=${selected?.addonName ?: "<none, manual selection>"}"
        }
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
            onManualSelectionRequired(nextVideo)
            onNextEpisodeCardVisibleChanged(false)
        }
    }
}

private const val NEXT_EPISODE_ADDON_GRACE_MS = 7_000L
