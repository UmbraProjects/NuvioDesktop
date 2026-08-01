package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.playbackEpisodeNumber
import com.nuvio.app.features.details.playbackSeasonNumber
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamScorer
import com.nuvio.app.features.streams.StreamScoreRepository
import com.nuvio.app.features.streams.StreamScoreProfile
import com.nuvio.app.features.streams.StreamScoreContext
import com.nuvio.app.features.streams.StreamScoreContexts
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun PlayerScreenRuntime.resolveDebridForPlayer(
    stream: StreamItem,
    season: Int?,
    episode: Int?,
    onResolved: (StreamItem) -> Unit,
    onStale: () -> Unit,
): Boolean {
    if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) return false
    scope.launch {
        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = stream,
            season = season,
            episode = episode,
        )
        when (resolved) {
            is DirectDebridPlayableResult.Success -> onResolved(resolved.stream)
            else -> {
                resolved.toastMessage()?.let { NuvioToastController.show(it) }
                if (resolved == DirectDebridPlayableResult.Stale) {
                    onStale()
                }
            }
        }
    }
    return true
}

internal fun PlayerScreenRuntime.p2pSentinelUrl(infoHash: String, fileIdx: Int?): String =
    "torrent://$infoHash${fileIdx?.let { "?index=$it" }.orEmpty()}"

internal fun PlayerScreenRuntime.isP2pStream(stream: StreamItem): Boolean =
    stream.needsLocalDebridResolve && stream.p2pInfoHash != null

/**
 * A stable identity for "the same source", used to mark the playing row and to remember which
 * sources failover has already tried.
 *
 * The ordering below goes from most to least durable, and deliberately avoids two things that look
 * identifying but are not:
 *
 *  - **Display strings.** `streamLabel`/`streamSubtitle` are formatter output. They carry seeder
 *    counts, release age in days and cache indicators, all of which drift between the moment
 *    playback starts and the moment the sources panel is rebuilt. Keying on them meant the playing
 *    row silently stopped being recognised.
 *  - **The playback URL.** Proxying addons (AIOStreams among them) hand out per-request signed URLs,
 *    so the same file gets a different URL on every fetch.
 *
 * Filename plus size is what actually identifies a release across re-fetches, so it sits near the
 * top. Two distinct sources sharing both are treated as one — acceptable, since they are the same
 * file and would fail the same way.
 */
internal fun StreamItem.playerSourceIdentityKey(): String? {
    p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "torrent:$hash:${p2pFileIdx ?: -1}"
    }

    val raw = clientResolve?.stream?.raw
    val contentName = listOfNotNull(
        behaviorHints.filename,
        clientResolve?.filename,
        raw?.filename,
        raw?.torrentName,
        clientResolve?.torrentName,
        debridCacheStatus?.cachedName,
    ).firstOrNull { it.isNotBlank() }
    if (contentName != null) {
        val size = behaviorHints.videoSize ?: raw?.size ?: debridCacheStatus?.cachedSize
        return "file:$addonId:${contentName.trim().lowercase()}:${size ?: ""}"
    }

    clientResolve?.let { resolve ->
        val keyParts = listOf(
            addonId,
            resolve.service,
            resolve.serviceIndex?.toString(),
            resolve.infoHash?.trim()?.lowercase(),
            resolve.fileIdx?.toString(),
            resolve.magnetUri,
            raw?.size?.toString(),
            behaviorHints.videoSize?.toString(),
        ).map { it.orEmpty().trim() }
        if (keyParts.any { it.isNotBlank() }) {
            return "resolve:${keyParts.joinToString("|")}"
        }
    }

    behaviorHints.videoHash?.trim()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "hash:$addonId:$hash:${behaviorHints.videoSize ?: ""}:${behaviorHints.filename.orEmpty()}"
    }

    playableDirectUrl?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
        return "url:$url"
    }

    val fallbackParts = listOf(
        addonId,
        addonName,
        streamLabel,
        streamSubtitle.orEmpty(),
        behaviorHints.filename.orEmpty(),
        behaviorHints.videoSize?.toString().orEmpty(),
        sourceName.orEmpty(),
        sources.joinToString(","),
    ).map { it.trim() }
    return fallbackParts
        .takeIf { parts -> parts.any { it.isNotBlank() } }
        ?.joinToString(separator = "|", prefix = "meta:")
}

internal fun PlayerScreenRuntime.stopActiveP2pStream() {
    if (activeTorrentInfoHash != null || p2pResolvedSourceUrl != null) {
        P2pStreamingEngine.stopStream()
    }
    activeTorrentInfoHash = null
    activeTorrentFileIdx = null
    activeTorrentFilename = null
    activeTorrentTrackers = emptyList()
    p2pResolvedSourceUrl = null
}

internal fun PlayerScreenRuntime.saveP2pStreamForReuse(
    stream: StreamItem,
    videoId: String?,
    season: Int?,
    episode: Int?,
) {
    if (!playerSettingsUiState.streamReuseLastLinkEnabled || videoId == null) return
    val infoHash = stream.p2pInfoHash ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
    )
    StreamLinkCacheRepository.save(
        contentKey = cacheKey,
        url = "",
        streamName = stream.streamLabel,
        addonName = stream.addonName,
        addonId = stream.addonId,
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        filename = stream.behaviorHints.filename,
        videoSize = stream.behaviorHints.videoSize,
        infoHash = infoHash,
        fileIdx = stream.p2pFileIdx,
        sources = stream.sources,
        bingeGroup = stream.behaviorHints.bingeGroup,
    )
}

internal fun PlayerScreenRuntime.switchToP2pSourceStream(stream: StreamItem) {
    val infoHash = stream.p2pInfoHash ?: return
    if (!P2pSettingsRepository.isVisible) return
    if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
        pendingP2pSwitch = PendingPlayerP2pSwitch(stream = stream, episode = null, isAutoPlay = false)
        return
    }
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    flushWatchProgress()
    stopActiveP2pStream()
    saveP2pStreamForReuse(
        stream = stream,
        videoId = activeVideoId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeTorrentInfoHash = infoHash
    activeTorrentFileIdx = stream.p2pFileIdx
    activeTorrentFilename = stream.behaviorHints.filename
    activeTorrentTrackers = stream.p2pTrackers
    activeSourceIdentityKey = stream.playerSourceIdentityKey()
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeInitialPositionMs = currentPositionMs
    activeInitialProgressFraction = null
    showSourcesPanel = false
    controlsVisible = true
    beginPlaybackAttempt()
    activeSourceUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
}

internal fun PlayerScreenRuntime.switchToP2pEpisodeStream(
    stream: StreamItem,
    episode: MetaVideo,
    isAutoPlay: Boolean = false,
) {
    val infoHash = stream.p2pInfoHash ?: return
    if (!P2pSettingsRepository.isVisible) return
    if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
        pendingP2pSwitch = PendingPlayerP2pSwitch(stream = stream, episode = episode, isAutoPlay = isAutoPlay)
        return
    }
    resetEpisodePanelAndNextEpisodeState()
    flushWatchProgress()
    stopActiveP2pStream()
    val epVideoId = episode.id
    val seasonNumber = episode.playbackSeasonNumber()
    val episodeNumber = episode.playbackEpisodeNumber()
    val resume = resolveEpisodeResume(epVideoId, episode)
    saveP2pStreamForReuse(
        stream = stream,
        videoId = epVideoId,
        season = seasonNumber,
        episode = episodeNumber,
    )
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeTorrentInfoHash = infoHash
    activeTorrentFileIdx = stream.p2pFileIdx
    activeTorrentFilename = stream.behaviorHints.filename
    activeTorrentTrackers = stream.p2pTrackers
    applyEpisodeStreamMetadata(stream, episode, resume)
    beginPlaybackAttempt()
    activeSourceUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
}

internal fun PlayerScreenRuntime.switchToSource(
    stream: StreamItem,
    sourceIdentityKey: String? = stream.playerSourceIdentityKey(),
    resumePositionOverrideMs: Long? = null,
) {
    if (
        resolveDebridForPlayer(
            stream = stream,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
            // The playable result usually has a short-lived resolved URL and no longer has the
            // identity fields of the card the user selected. Keep the original card identity so
            // the Sources UI can mark the source MPV is actually using.
            onResolved = { switchToSource(it, sourceIdentityKey, resumePositionOverrideMs) },
            onStale = {
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
        )
    ) return
    if (isP2pStream(stream)) {
        switchToP2pSourceStream(stream)
        return
    }
    val url = stream.playableDirectUrl ?: return
    if (url == activeSourceUrl) {
        activeSourceIdentityKey = sourceIdentityKey ?: activeSourceIdentityKey
        return
    }
    val currentPositionMs = resumePositionOverrideMs
        ?.coerceAtLeast(0L)
        ?: playbackSnapshot.positionMs.coerceAtLeast(0L)
    flushWatchProgress()
    stopActiveP2pStream()
    val currentVideoId = activeVideoId
    if (playerSettingsUiState.streamReuseLastLinkEnabled && currentVideoId != null) {
        saveDirectStreamForReuse(stream, url, currentVideoId, activeSeasonNumber, activeEpisodeNumber)
    }
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    activeSourceIdentityKey = sourceIdentityKey
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeInitialPositionMs = currentPositionMs
    activeInitialProgressFraction = null
    showSourcesPanel = false
    controlsVisible = true
    beginPlaybackAttempt()
    activeSourceUrl = url
}

/** Selects a source explicitly chosen by the user and starts a fresh automatic-failover budget. */
internal fun PlayerScreenRuntime.selectSourceManually(stream: StreamItem) {
    resetFailoverBudget()
    switchToSource(stream)
}

internal fun PlayerScreenRuntime.resetFailoverBudget() {
    failoverJob?.cancel()
    failoverJob = null
    failoverInProgress = false
    failoverTriedIdentityKeys.clear()
}

internal fun PlayerScreenRuntime.switchToEpisodeStream(
    stream: StreamItem,
    episode: MetaVideo,
    sourceIdentityKey: String? = stream.playerSourceIdentityKey(),
) {
    val seasonNumber = episode.playbackSeasonNumber()
    val episodeNumber = episode.playbackEpisodeNumber()
    if (
        resolveDebridForPlayer(
            stream = stream,
            season = seasonNumber,
            episode = episodeNumber,
            onResolved = { resolvedStream ->
                switchToEpisodeStream(resolvedStream, episode, sourceIdentityKey)
            },
            onStale = {
                PlayerStreamsRepository.loadEpisodeStreams(
                    type = contentType ?: parentMetaType,
                    videoId = episode.id,
                    parentMetaId = parentMetaId,
                    title = title,
                    season = seasonNumber,
                    episode = episodeNumber,
                    sourceAffinity = sourceAffinity,
                    forceRefresh = true,
                )
            },
        )
    ) return
    if (isP2pStream(stream)) {
        switchToP2pEpisodeStream(stream, episode)
        return
    }
    val url = stream.playableDirectUrl ?: run {
        BingeAdvanceLog.i { "switchToEpisodeStream aborted: stream has no playableDirectUrl (addon=${stream.addonName})" }
        return
    }
    BingeAdvanceLog.i { "switchToEpisodeStream setting activeSourceUrl for S${seasonNumber}E${episodeNumber} — desktop attach should follow" }
    resetEpisodePanelAndNextEpisodeState()
    flushWatchProgress()
    stopActiveP2pStream()
    val epVideoId = episode.id
    val resume = resolveEpisodeResume(epVideoId, episode)
    if (playerSettingsUiState.streamReuseLastLinkEnabled) {
        saveDirectStreamForReuse(stream, url, epVideoId, seasonNumber, episodeNumber)
    }
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    applyEpisodeStreamMetadata(stream, episode, resume, sourceIdentityKey)
    beginPlaybackAttempt()
    activeSourceUrl = url
}

internal fun PlayerScreenRuntime.switchToDownloadedEpisode(downloadItem: DownloadItem, episode: MetaVideo) {
    val localFileUri = DownloadsRepository.playableLocalFileUri(downloadItem) ?: return
    resetEpisodePanelAndNextEpisodeState()
    flushWatchProgress()
    stopActiveP2pStream()
    val seasonNumber = episode.playbackSeasonNumber()
    val episodeNumber = episode.playbackEpisodeNumber()

    val fallbackVideoId = buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        fallbackVideoId = episode.id,
    )
    val resolvedVideoId = episode.id.takeIf { it.isNotBlank() } ?: fallbackVideoId
    val epEntry = if (disableProgressTracking) null else WatchProgressRepository.progressForVideo(resolvedVideoId)
        ?.takeIf { !it.isCompleted }
    val epResumeFraction = epEntry?.progressPercent
        ?.takeIf { it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val epResumePositionMs = epEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L

    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeSourceIdentityKey = null
    activeStreamTitle = downloadItem.streamTitle.ifBlank {
        episode.title.ifBlank { title }
    }
    activeStreamSubtitle = downloadItem.streamSubtitle
    activeProviderName = downloadItem.providerName.ifBlank { downloadedLabel }
    activeProviderAddonId = downloadItem.providerAddonId
    currentStreamBingeGroup = null
    activeSeasonNumber = seasonNumber
    activeEpisodeNumber = episodeNumber
    activeEpisodeTitle = episode.title
    activeEpisodeThumbnail = episode.thumbnail
    activePauseDescription = episode.overview?.trim()?.takeIf { it.isNotBlank() }
    activeVideoId = resolvedVideoId
    activeInitialPositionMs = epResumePositionMs
    activeInitialProgressFraction = epResumeFraction
    resetFailoverBudget()
    controlsVisible = true
    beginPlaybackAttempt()
    activeSourceUrl = localFileUri
}

private const val STREAM_FAILOVER_POLL_COUNT = 40
private const val STREAM_FAILOVER_POLL_INTERVAL_MS = 250L

/**
 * Failover: swap to the next untried source after a failure instead of exiting. Marks the current
 * source tried, ensures the source list is loaded (polling briefly, mirroring the credential-refresh
 * recovery), then switches in place — [switchToSource] resumes at the current position. Returns true
 * when failover is enabled and has taken over: the async job either swaps to the next stream or, once
 * the list is exhausted, exits via [exitAfterPlaybackFailure]. Returns false when failover is disabled
 * so the caller exits normally.
 */
internal fun PlayerScreenRuntime.tryFailoverToNextSource(
    message: String,
    playbackFailedToast: String,
    tryingNextToast: String,
    trigger: StreamFailoverTrigger = StreamFailoverTrigger.PlaybackError,
    rateLimited: Boolean = false,
): Boolean {
    if (!playerSettingsUiState.streamFailoverEnabled) return false
    if (failoverInProgress) {
        StreamFailoverLog.event("trigger_ignored", buildJsonObject {
            put("trigger", trigger.wireName)
            put("reason", "already_in_progress")
        })
        return true
    }
    val currentVideoId = activeVideoId ?: return false
    val failedAttemptId = playbackAttemptId
    val failedSourceUrl = activeSourceUrl
    val failedIdentityKey = activeSourceIdentityKey
    // Candidates are inserted into the tried set before switchToSource. Membership here therefore
    // distinguishes an automatically selected replacement from the user's original source.
    val failedSourceWasSelectedByFailover =
        isAutomaticFailoverReplacement(failedIdentityKey, failoverTriedIdentityKeys)
    failedIdentityKey?.let { failoverTriedIdentityKeys.add(it) }
    // Scope a rate limit to the throttled provider: skip candidates that resolve to the same
    // provider (they'd 429 again) but still try other providers, even ones served by the same addon.
    val rateLimitScopeKey = if (rateLimited) {
        val loaded = PlayerStreamsRepository.sourceState.value.groups.flatMap { it.streams }
        val failedStream = failedIdentityKey
            ?.let { key -> loaded.firstOrNull { it.playerSourceIdentityKey() == key } }
        failedStream?.rateLimitScopeKey()
            ?: playbackProviderDomain(activeSourceUrl)?.let { "domain:$it" }
    } else {
        null
    }
    failoverInProgress = true
    // Desktop playback is hosted in a native child window, so a Compose toast can sit behind it.
    // Mirror the notification into the native controls overlay, which remains visible above mpv
    // without interrupting or replacing the active player.
    playerController?.showTransientMessage(tryingNextToast, "")
    // A rate-limited link isn't a bad link, just throttled — keep it cached so a later retry (once
    // the throttle clears) can reuse it instead of forcing a fresh resolve that may 429 again.
    if (!rateLimited && trigger != StreamFailoverTrigger.StartupTimeout) {
        removeFailedStreamFromCache()
    }
    StreamFailoverLog.event("attempt_started", buildJsonObject {
        put("attemptId", failedAttemptId)
        put("trigger", trigger.wireName)
        put("contentType", contentType ?: parentMetaType)
        put("videoId", currentVideoId)
        put("triedSourceCount", failoverTriedIdentityKeys.size)
    })

    val type = contentType ?: parentMetaType
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    // A failed network seek can make mpv publish the file duration as its current position. Capture
    // a known-good resume point before the asynchronous source search so failover cannot inherit
    // that synthetic EOF. During very early startup, fall back to the requested initial resume.
    val failoverResumePositionMs = selectFailoverResumePositionMs(
        lastTrustedPositionMs = lastTrustedPlaybackPositionMs,
        initialPositionMs = activeInitialPositionMs,
        snapshotPositionMs = playbackSnapshot.positionMs,
    )

    failoverJob = scope.launch {
        try {
            fun failedAttemptStillCurrent(): Boolean =
                playbackAttemptId == failedAttemptId &&
                    activeVideoId == currentVideoId &&
                    activeSourceUrl == failedSourceUrl

            fun cancelSupersededFailover(): Boolean {
                if (failedAttemptStillCurrent()) return false
                StreamFailoverLog.event("attempt_cancelled", buildJsonObject {
                    put("attemptId", failedAttemptId)
                    put("trigger", trigger.wireName)
                    put("reason", "superseded_playback_attempt")
                    put("activeAttemptId", playbackAttemptId)
                    put("triedSourceCount", failoverTriedIdentityKeys.size)
                })
                return true
            }

            fun originalStartupRecovered(): Boolean =
                trigger == StreamFailoverTrigger.StartupTimeout &&
                    failedAttemptStillCurrent() &&
                    playerStartedSourceUrl == failedSourceUrl &&
                    playerStartedAttemptId == failedAttemptId

            fun cancelRecoveredStartupFailover(): Boolean {
                if (!originalStartupRecovered()) return false
                failedIdentityKey?.let(failoverTriedIdentityKeys::remove)
                StreamFailoverLog.event("attempt_cancelled", buildJsonObject {
                    put("attemptId", failedAttemptId)
                    put("trigger", trigger.wireName)
                    put("reason", "original_source_started")
                    put("triedSourceCount", failoverTriedIdentityKeys.size)
                })
                return true
            }

            // Calling this unconditionally is important: the repository request key makes the call
            // a no-op for the active item, but replaces a non-empty source list left over from a
            // previously played episode.
            PlayerStreamsRepository.loadSources(
                type = type,
                videoId = currentVideoId,
                parentMetaId = parentMetaId,
                title = title,
                season = season,
                episode = episode,
                forceRefresh = false,
            )
            var next: StreamItem? = null
            var poll = 0
            while (poll < STREAM_FAILOVER_POLL_COUNT && next == null) {
                if (cancelSupersededFailover()) return@launch
                val state = PlayerStreamsRepository.sourceState.value
                val streams = state.groups.flatMap { it.streams }
                val activeKey = activeSourceIdentityKey
                val activeSourceLoaded = activeKey == null ||
                    streams.any { it.playerSourceIdentityKey() == activeKey }
                // An early provider result can appear before the group containing the current
                // source. Wait for that match (or load completion) so "next" is based on the
                // Sources panel order instead of accidentally jumping back to its first item.
                next = if (activeSourceLoaded || !state.isAnyLoading) {
                    nextFailoverStream(
                        streams = streams,
                        activeIdentityKey = activeKey,
                        triedIdentityKeys = failoverTriedIdentityKeys,
                        excludeScopeKey = rateLimitScopeKey,
                        preferredAudioLanguages = resolvePreferredAudioLanguageTargets(
                            preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
                            secondaryPreferredAudioLanguage =
                                playerSettingsUiState.secondaryPreferredAudioLanguage,
                            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
                            originalLanguage = OriginalLanguageCache.languageFor(parentMetaId),
                        ),
                        scoreProfile = StreamScoreRepository.profile,
                        scoreContext = StreamScoreContexts.forPlayback(
                            isEpisode = activeEpisodeNumber != null,
                            contentId = parentMetaId,
                            contentType = contentType ?: parentMetaType,
                        ),
                    )
                } else {
                    null
                }
                if (next != null || state.emptyStateReason != null || (!state.isAnyLoading && state.groups.isNotEmpty())) {
                    break
                }
                delay(STREAM_FAILOVER_POLL_INTERVAL_MS)
                poll++
            }
            if (cancelSupersededFailover()) return@launch
            // Source discovery is asynchronous. A slow stream can finish opening while providers
            // are still returning fallback rows; keep it instead of replacing healthy playback
            // with a candidate selected from a timeout that is no longer true.
            if (cancelRecoveredStartupFailover()) return@launch
            val chosen = next
            if (chosen == null) {
                if (cancelRecoveredStartupFailover()) return@launch
                if (
                    trigger == StreamFailoverTrigger.StartupTimeout &&
                    !failedSourceWasSelectedByFailover
                ) {
                    // A timeout is advisory while the original player is still alive. If every
                    // remaining row is uncached or unplayable, there is no safer recovery to make:
                    // keep waiting instead of closing a source that may still finish opening.
                    failedIdentityKey?.let(failoverTriedIdentityKeys::remove)
                    StreamFailoverLog.event("attempt_cancelled", buildJsonObject {
                        put("trigger", trigger.wireName)
                        put("reason", "no_safe_fallback")
                        put("triedSourceCount", failoverTriedIdentityKeys.size)
                    })
                    return@launch
                }
                // Nothing left to try — fall back to the normal unrecoverable-failure exit.
                StreamFailoverLog.event("sources_exhausted", buildJsonObject {
                    put("trigger", trigger.wireName)
                    put("failedSourceWasSelectedByFailover", failedSourceWasSelectedByFailover)
                    put("triedSourceCount", failoverTriedIdentityKeys.size)
                    put("loadedSourceCount", PlayerStreamsRepository.sourceState.value.groups.sumOf { it.streams.size })
                })
                exitAfterPlaybackFailure(message, playbackFailedToast)
                return@launch
            }
            val orderedStreams = PlayerStreamsRepository.sourceState.value.groups.flatMap { it.streams }
            val chosenIndex = orderedStreams.indexOfFirst {
                it.playerSourceIdentityKey() == chosen.playerSourceIdentityKey()
            }
            val sourceFields = StreamFailoverLog.sourceFields(chosen, chosenIndex)
            if (cancelRecoveredStartupFailover()) return@launch
            chosen.playerSourceIdentityKey()?.let { failoverTriedIdentityKeys.add(it) }
            StreamFailoverLog.event("source_selected", buildJsonObject {
                put("attemptId", failedAttemptId)
                put("trigger", trigger.wireName)
                sourceFields.forEach { (key, value) -> put(key, value) }
                put("triedSourceCount", failoverTriedIdentityKeys.size)
            })
            // The active-source effect clears the failure state after the URL changes. Keep it set
            // until then so the failed player's synthetic EOF cannot trigger completion/autoplay in
            // the narrow interval before the replacement player is attached.
            NuvioToastController.show(tryingNextToast)
            // The switch below tears down the native surface (and its overlay) almost
            // immediately, so a pill shown now would vanish before it can be read. Queue it
            // for the replacement player's overlay instead.
            playerController?.showTransientMessageAfterNextAttach(
                title = tryingNextToast,
                value = chosen.addonName.ifBlank { chosen.streamLabel },
            )
            if (cancelRecoveredStartupFailover()) {
                chosen.playerSourceIdentityKey()?.let(failoverTriedIdentityKeys::remove)
                return@launch
            }
            if (cancelSupersededFailover()) {
                chosen.playerSourceIdentityKey()?.let(failoverTriedIdentityKeys::remove)
                return@launch
            }
            if (!rateLimited && trigger == StreamFailoverTrigger.StartupTimeout) {
                removeFailedStreamFromCache()
            }
            switchToSource(chosen, resumePositionOverrideMs = failoverResumePositionMs)
        } finally {
            failoverInProgress = false
            failoverJob = null
        }
    }
    return true
}

internal fun nextFailoverStream(
    streams: List<StreamItem>,
    activeIdentityKey: String?,
    triedIdentityKeys: Set<String>,
    excludeScopeKey: String? = null,
    preferredAudioLanguages: List<String> = emptyList(),
    scoreProfile: StreamScoreProfile = StreamScoreProfile(),
    scoreContext: StreamScoreContext = StreamScoreContext.MOVIE,
): StreamItem? {
    // The repository order is the quality/ranking order. Always restart at its top and exclude the
    // failed/tried identities, rather than starting after the failed stream's old index. Autoplay or
    // source affinity can select a stream far down the list; the old walk then skipped every better
    // fallback above it.
    val playableCandidates = streams.filter { stream ->
        val key = stream.playerSourceIdentityKey()
        if (key == null || key == activeIdentityKey || key in triedIdentityKeys) return@filter false
        val isPlayable = !stream.playableDirectUrl.isNullOrBlank() ||
            (stream.needsLocalDebridResolve && stream.p2pInfoHash != null)
        if (!isPlayable) return@filter false
        if (!stream.isSafeAutomaticFailoverSource()) return@filter false
        // Rate-limit scoping: skip a candidate that resolves to the SAME provider as the throttled
        // source — it would just 429 again. Only excludes on a confident match; a candidate whose
        // provider can't be determined is let through rather than assumed to share the throttle.
        if (excludeScopeKey != null && stream.rateLimitScopeKey() == excludeScopeKey) {
            return@filter false
        }
        true
    }
    // Scoring replaces the repository order as the within-tier tiebreak, and drops anything below
    // the profile's minimum so failover cannot land on a release the user has effectively banned.
    val ordered = if (scoreProfile.appliesToFailover()) {
        StreamScorer.rank(playableCandidates, scoreProfile, scoreContext)
    } else {
        playableCandidates
    }
    // Language tier still wins over score: a source which explicitly advertises the
    // primary/secondary audio language is safer than an unknown source, and an unknown source is
    // safer than one explicitly advertising a different language. minByOrNull keeps the first
    // minimum, so within a tier the order above decides.
    return ordered.minByOrNull { stream ->
        stream.preferredAudioLanguageRank(preferredAudioLanguages)
    }
}

internal fun isAutomaticFailoverReplacement(
    sourceIdentityKey: String?,
    triedIdentityKeys: Set<String>,
): Boolean = sourceIdentityKey != null && sourceIdentityKey in triedIdentityKeys

/**
 * Automatic failover must never knowingly start a debrid download. Uncached AIOStreams/TorBox rows can
 * return a short "file is being downloaded" status video, which looks playable to mpv and can
 * trigger completion, resume and video-filter logic for the wrong media. Manual selection remains
 * available; this restriction applies only to unattended recovery. A cached flag is still only a
 * hint because addon caches can be stale, so the per-source first-frame watchdog remains the final
 * runtime authority for every candidate accepted here.
 */
internal fun StreamItem.isSafeAutomaticFailoverSource(): Boolean {
    if (streamType.equals("usenet", ignoreCase = true) ||
        streamData?.type.equals("usenet", ignoreCase = true)
    ) {
        return true
    }
    when (debridCacheStatus?.state) {
        StreamDebridCacheState.NOT_CACHED,
        StreamDebridCacheState.CHECKING,
        StreamDebridCacheState.UNKNOWN,
        -> return false
        StreamDebridCacheState.CACHED -> return true
        null -> Unit
    }
    if (needsLocalDebridResolve) {
        return debridCacheStatus?.state == StreamDebridCacheState.CACHED
    }
    if (clientResolve?.type.equals("debrid", ignoreCase = true)) {
        return clientResolve?.isCached == true
    }
    if (streamData?.type.equals("debrid", ignoreCase = true)) {
        return streamData?.serviceCached == true
    }
    return true
}

internal fun StreamItem.preferredAudioLanguageRank(preferredAudioLanguages: List<String>): Int {
    val targets = preferredAudioLanguages.mapNotNull(::normalizeLanguageCode).distinct()
    if (targets.isEmpty()) return 0

    val advertised = advertisedAudioLanguages()
    if (advertised.isEmpty()) return targets.size
    if ("multi" in advertised || "mul" in advertised) return 0

    val preferredRank = targets.indices.firstOrNull { index ->
        advertised.any { language -> languageMatchesPreference(language, targets[index]) }
    }
    return preferredRank ?: (targets.size + 1)
}

internal fun StreamItem.advertisedAudioLanguages(): Set<String> {
    val structured = audioLanguages +
        clientResolve?.stream?.raw?.parsed?.languages.orEmpty()
    val supportedCodes = AvailableLanguageOptions
        .mapNotNull { option -> normalizeLanguageCode(option.code) }
        .map { it.substringBefore('-') }
        .toSet() + setOf("multi", "mul")

    val normalizedStructured = structured
        .mapNotNull(::normalizeLanguageCode)
        .filter { it.substringBefore('-') in supportedCodes }

    // Some addons only put language information in their display text. Language names are safe to
    // recognize there; short lowercase tokens are deliberately ignored to avoid treating ordinary
    // words such as "it" as Italian.
    val displayFields = listOfNotNull(
        name,
        title,
        description,
        behaviorHints.filename,
        clientResolve?.filename,
        clientResolve?.torrentName,
        clientResolve?.stream?.raw?.filename,
        clientResolve?.stream?.raw?.torrentName,
    )
    val normalizedDisplayNames = displayFields
        .mapNotNull(::normalizeLanguageCode)
        .filter { normalized ->
            normalized.substringBefore('-') in supportedCodes &&
                normalized.length <= 5
        }
    val normalizedUppercaseCodes = displayFields
        .flatMap { field -> languageTokenRegex.findAll(field).map { it.value }.toList() }
        .filter { token -> token.length in 2..3 && token == token.uppercase() }
        .mapNotNull(::normalizeLanguageCode)
        .filter { it.substringBefore('-') in supportedCodes }

    return (normalizedStructured + normalizedDisplayNames + normalizedUppercaseCodes).toSet()
}

private val languageTokenRegex = Regex("""[\p{L}]{2,20}""")

internal fun selectFailoverResumePositionMs(
    lastTrustedPositionMs: Long,
    initialPositionMs: Long?,
    snapshotPositionMs: Long,
): Long = lastTrustedPositionMs
    .takeIf { it > 0L }
    ?: initialPositionMs?.coerceAtLeast(0L)
    ?: snapshotPositionMs.coerceAtLeast(0L)

internal fun PlayerScreenRuntime.playNextEpisode() {
    // Mirror launchPlayerNextEpisodeAutoPlay's own early-exit checks: when there's clearly no
    // episode to advance to, bail out before engaging the latch at all. Engaging it here and
    // relying on that function's early `return null` to release it doesn't work — those returns
    // never launch a job, so nothing would ever clear the latch and auto-advance would stay
    // permanently disabled for the rest of the session.
    val nextVideoId = nextEpisodeInfo?.videoId
    val nextVideo = nextVideoId?.let { id -> playerMetaVideos.firstOrNull { video -> video.id == id } }
    BingeAdvanceLog.i {
        "playNextEpisode nextVideoId=$nextVideoId resolved=${nextVideo != null} hasAired=${nextEpisodeInfo?.hasAired}"
    }
    if (nextVideo == null || nextEpisodeInfo?.hasAired != true) {
        BingeAdvanceLog.i { "playNextEpisode early return (no next video or not aired) — latch NOT engaged" }
        return
    }

    // Engage the advance latch for every path (auto and manual) so a stale end-of-file can't
    // trigger a second advance and skip an episode. Cleared once the new episode is playing.
    nextEpisodeAdvanceInProgress = true
    nextEpisodeAdvanceTargetVideoId = nextVideo.id
    // Surface the next-episode card as loading feedback while streams resolve. Harmless on the
    // auto-advance path (same episode, card already shown); the win is the manual next-episode
    // button mid-episode, where nothing was shown before. Cleared when the switch resolves.
    manualEpisodeSwitchInfo = nextEpisodeInfo
    scope.launchPlayerNextEpisodeAutoPlay(
        previousJob = nextEpisodeAutoPlayJob,
        nextEpisodeInfo = nextEpisodeInfo,
        allEpisodes = playerMetaVideos,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        contentType = contentType,
        settings = playerSettingsUiState,
        sourceAffinity = sourceAffinity,
        currentStreamBingeGroup = currentStreamBingeGroup,
        onDownloadedEpisodeSelected = { item, episode -> switchToDownloadedEpisode(item, episode) },
        onEpisodeStreamSelected = { stream, episode -> switchToEpisodeStream(stream, episode) },
        onManualSelectionRequired = { nextVideo ->
            // Auto-select failed: the manual stream list is now the feedback, so drop the card.
            manualEpisodeSwitchInfo = null
            episodeStreamsPanelState = EpisodeStreamsPanelState(
                showStreams = true,
                selectedEpisode = nextVideo,
            )
            showEpisodesPanel = true
        },
        onSearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onSourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        onCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeCardVisibleChanged = { showNextEpisodeCard = it },
        skipSourceCountdown = playerSettingsUiState.streamAutoPlayNextEpisodeEnabled,
    )?.let { job ->
        nextEpisodeAutoPlayJob = job
    } ?: run {
        // No background search launched (e.g. resolved straight to a downloaded file) — don't
        // strand the loading card.
        manualEpisodeSwitchInfo = null
    }
}

/**
 * Plays an episode chosen in the player's episodes panel through the same auto-select pipeline
 * as binge auto-advance. In-player episode switching is autoplay-first by design — the panel's
 * per-episode stream list only appears as the fallback when nothing could be auto-selected
 * (which is exactly what [launchPlayerNextEpisodeAutoPlay]'s manual-selection callback does).
 */
internal fun PlayerScreenRuntime.autoPlaySelectedEpisode(episode: MetaVideo) {
    nextEpisodeAutoPlayJob?.cancel()
    showEpisodesPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    val target = NextEpisodeInfo(
        videoId = episode.id,
        season = episode.playbackSeasonNumber() ?: 1,
        episode = episode.playbackEpisodeNumber() ?: 0,
        title = episode.title,
        thumbnail = episode.thumbnail,
        overview = episode.overview,
        released = episode.released,
        hasAired = true,
        unairedMessage = null,
    )
    // Show the chosen episode in the next-episode card as loading feedback while its streams
    // resolve — the episode selector otherwise closes and swaps silently. Uses the picked episode
    // (not the sequential next). Cleared when the switch resolves.
    manualEpisodeSwitchInfo = target
    scope.launchPlayerNextEpisodeAutoPlay(
        previousJob = nextEpisodeAutoPlayJob,
        nextEpisodeInfo = target,
        allEpisodes = playerMetaVideos,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        contentType = contentType,
        settings = playerSettingsUiState,
        sourceAffinity = sourceAffinity,
        currentStreamBingeGroup = currentStreamBingeGroup,
        onDownloadedEpisodeSelected = { item, video -> switchToDownloadedEpisode(item, video) },
        onEpisodeStreamSelected = { stream, video -> switchToEpisodeStream(stream, video) },
        onManualSelectionRequired = { video ->
            // Auto-select failed: the manual stream list is now the feedback, so drop the card.
            manualEpisodeSwitchInfo = null
            episodeStreamsPanelState = EpisodeStreamsPanelState(
                showStreams = true,
                selectedEpisode = video,
            )
            showEpisodesPanel = true
        },
        onSearchingChanged = { nextEpisodeAutoPlaySearching = it },
        onSourceNameChanged = { nextEpisodeAutoPlaySourceName = it },
        onCountdownChanged = { nextEpisodeAutoPlayCountdown = it },
        onNextEpisodeCardVisibleChanged = { showNextEpisodeCard = it },
        skipSourceCountdown = true,
    )?.let { job ->
        nextEpisodeAutoPlayJob = job
    } ?: run {
        // No background search launched (e.g. resolved straight to a downloaded file) — don't
        // strand the loading card.
        manualEpisodeSwitchInfo = null
    }
}

internal fun PlayerScreenRuntime.openSourcesPanel() {
    val vid = activeVideoId ?: return
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = vid,
        parentMetaId = parentMetaId,
        title = title,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    showSourcesPanel = true
    showEpisodesPanel = false
    controlsVisible = false
}

internal fun PlayerScreenRuntime.openEpisodesPanel() {
    if (playerMetaVideos.isEmpty()) {
        scope.launch {
            playerMetaVideos = MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.videos ?: emptyList()
        }
    }
    showEpisodesPanel = true
    showSourcesPanel = false
    controlsVisible = false
}

private data class EpisodeResume(val positionMs: Long, val fraction: Float?)

private fun PlayerScreenRuntime.resetEpisodePanelAndNextEpisodeState() {
    showNextEpisodeCard = false
    manualEpisodeSwitchInfo = null
    showSourcesPanel = false
    showEpisodesPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlaySearching = false
    nextEpisodeAutoPlaySourceName = null
    nextEpisodeAutoPlayCountdown = null
    PlayerStreamsRepository.clearEpisodeStreams()
}

private fun PlayerScreenRuntime.resolveEpisodeResume(epVideoId: String, episode: MetaVideo): EpisodeResume {
    if (disableProgressTracking) return EpisodeResume(positionMs = 0L, fraction = null)
    val epResumeVideoId = buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = episode.playbackSeasonNumber(),
        episodeNumber = episode.playbackEpisodeNumber(),
        fallbackVideoId = epVideoId,
    )
    val epEntry = WatchProgressRepository.progressForVideo(
        epVideoId.takeIf { it.isNotBlank() } ?: epResumeVideoId,
    )?.takeIf { !it.isCompleted }
    val epResumeFraction = epEntry?.progressPercent
        ?.takeIf { it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val epResumePositionMs = epEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L
    return EpisodeResume(positionMs = epResumePositionMs, fraction = epResumeFraction)
}

private fun PlayerScreenRuntime.applyEpisodeStreamMetadata(
    stream: StreamItem,
    episode: MetaVideo,
    resume: EpisodeResume,
    sourceIdentityKey: String? = stream.playerSourceIdentityKey(),
) {
    // New episode: fresh failover budget (previous episode's tried streams are unrelated).
    resetFailoverBudget()
    activeSourceIdentityKey = sourceIdentityKey
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeSeasonNumber = episode.playbackSeasonNumber()
    activeEpisodeNumber = episode.playbackEpisodeNumber()
    activeEpisodeTitle = episode.title
    activeEpisodeThumbnail = episode.thumbnail
    activePauseDescription = episode.overview?.trim()?.takeIf { it.isNotBlank() }
    activeVideoId = episode.id
    activeInitialPositionMs = resume.positionMs
    activeInitialProgressFraction = resume.fraction
    controlsVisible = true
}

private fun PlayerScreenRuntime.saveDirectStreamForReuse(
    stream: StreamItem,
    url: String,
    videoId: String,
    season: Int?,
    episode: Int?,
) {
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
    )
    StreamLinkCacheRepository.save(
        contentKey = cacheKey,
        url = url,
        streamName = stream.streamLabel,
        addonName = stream.addonName,
        addonId = stream.addonId,
        requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
        responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
        filename = stream.behaviorHints.filename,
        videoSize = stream.behaviorHints.videoSize,
        bingeGroup = stream.behaviorHints.bingeGroup,
        streamType = stream.streamType,
    )
}
