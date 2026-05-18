package com.nuvio.app.features.watchprogress

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktProgressRepository
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.shouldUseTraktProgress as shouldUseTraktProgressSource
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.sync.ProgressSyncAdapter
import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import com.nuvio.app.features.watching.sync.SupabaseProgressSyncAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val NUVIO_SYNC_PERIODIC_INTERVAL_MS = 5L * 60L * 1000L

object WatchProgressRepository {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("WatchProgressRepository")

    private val _uiState = MutableStateFlow(WatchProgressUiState())
    val uiState: StateFlow<WatchProgressUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var currentProfileId: Int = 1
    private var entriesByVideoId: MutableMap<String, WatchProgressEntry> = mutableMapOf()
    private var metadataResolutionJob: Job? = null
    private var isPullingNuvioSyncFromServer = false
    private var hasCompletedInitialNuvioSyncPull = false
    internal var syncAdapter: ProgressSyncAdapter = SupabaseProgressSyncAdapter

    init {
        syncScope.launch {
            TraktAuthRepository.isAuthenticated.collectLatest { authenticated ->
                if (shouldUseTraktProgressSource(
                        isAuthenticated = authenticated,
                        source = TraktSettingsRepository.uiState.value.watchProgressSource,
                    )
                ) {
                    runCatching { TraktProgressRepository.refreshNow() }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            log.w { "Failed to refresh Trakt progress after auth: ${error.message}" }
                        }
                }
                publish()
            }
        }

        syncScope.launch {
            TraktSettingsRepository.uiState.collectLatest { settings ->
                if (shouldUseTraktProgressSource(
                        isAuthenticated = TraktAuthRepository.isAuthenticated.value,
                        source = settings.watchProgressSource,
                    )
                ) {
                    runCatching { TraktProgressRepository.refreshNow() }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            log.w { "Failed to refresh Trakt progress after source change: ${error.message}" }
                        }
                }
                publish()
            }
        }

        syncScope.launch {
            TraktProgressRepository.uiState.collectLatest {
                if (shouldUseTraktProgress()) {
                    publish()
                }
            }
        }

        syncScope.launch {
            while (true) {
                delay(NUVIO_SYNC_PERIODIC_INTERVAL_MS)
                TraktAuthRepository.ensureLoaded()
                TraktSettingsRepository.ensureLoaded()
                if (shouldUseTraktProgress()) continue

                val authState = AuthRepository.state.value
                if (authState !is AuthState.Authenticated || authState.isAnonymous) continue
                if (!hasCompletedInitialNuvioSyncPull || isPullingNuvioSyncFromServer) continue

                log.d {
                    "periodic NuvioSync pull start profileId=${ProfileRepository.activeProfileId} " +
                        "entries=${entriesByVideoId.size}"
                }
                runCatching { pullFromServer(ProfileRepository.activeProfileId) }
                    .onSuccess {
                        log.d {
                            "periodic NuvioSync pull complete profileId=${ProfileRepository.activeProfileId} " +
                                "entries=${entriesByVideoId.size}"
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.w { "Periodic NuvioSync pull failed: ${error.message}" }
                    }
            }
        }
    }

    fun ensureLoaded() {
        TraktAuthRepository.ensureLoaded()
        TraktSettingsRepository.ensureLoaded()
        TraktProgressRepository.ensureLoaded()
        if (hasLoaded) return
        loadFromDisk(ProfileRepository.activeProfileId)
        if (shouldUseTraktProgress()) {
            TraktProgressRepository.refreshAsync()
        }
    }

    fun onProfileChanged(profileId: Int) {
        if (profileId == currentProfileId && hasLoaded) return
        TraktSettingsRepository.onProfileChanged()
        loadFromDisk(profileId)
        TraktProgressRepository.onProfileChanged()
        if (shouldUseTraktProgress()) {
            TraktProgressRepository.refreshAsync()
        }
    }

    fun clearLocalState() {
        metadataResolutionJob?.cancel()
        hasLoaded = false
        currentProfileId = 1
        entriesByVideoId.clear()
        TraktProgressRepository.clearLocalState()
        TraktSettingsRepository.clearLocalState()
        _uiState.value = WatchProgressUiState()
    }

    private fun loadFromDisk(profileId: Int) {
        currentProfileId = profileId
        hasLoaded = true
        entriesByVideoId.clear()

        val payload = WatchProgressStorage.loadPayload(profileId).orEmpty().trim()
        if (payload.isNotEmpty()) {
            entriesByVideoId = WatchProgressCodec.decodeEntries(payload)
                .associateBy { it.videoId }
                .toMutableMap()
        }
        publish()
        resolveRemoteMetadata()
    }

    suspend fun pullFromServer(profileId: Int) {
        TraktAuthRepository.ensureLoaded()
        TraktSettingsRepository.ensureLoaded()
        TraktProgressRepository.ensureLoaded()
        currentProfileId = profileId

        val useTraktProgress = shouldUseTraktProgress()
        log.d {
            "pullFromServer start profileId=$profileId source=${if (useTraktProgress) "trakt" else "nuvio_sync"} " +
                "localEntries=${entriesByVideoId.size}"
        }

        if (!useTraktProgress && isPullingNuvioSyncFromServer) {
            log.d { "pullFromServer NuvioSync skipped: pull already in flight profileId=$profileId" }
            return
        }
        if (!useTraktProgress) {
            isPullingNuvioSyncFromServer = true
        }

        try {
            if (useTraktProgress) {
                runCatching { TraktProgressRepository.refreshNow() }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        log.e(e) { "Failed to pull Trakt progress" }
                    }
                publish()
                log.d {
                    "pullFromServer trakt complete entries=${TraktProgressRepository.uiState.value.entries.size} " +
                        "sources=${TraktProgressRepository.uiState.value.entries.debugSourceCounts()} " +
                        "items=${TraktProgressRepository.uiState.value.entries.debugWatchProgressEntrySummary()}"
                }
                return
            }

            runCatching {
                val serverEntries = syncAdapter.pull(profileId = profileId)
                log.d {
                    "pullFromServer NuvioSync returned ${serverEntries.size} records " +
                        "items=${serverEntries.debugProgressRecordSummary()}"
                }

                val oldLocal = entriesByVideoId.toMap()
                val newMap = mutableMapOf<String, WatchProgressEntry>()

                serverEntries.forEach { entry ->
                    val videoId = entry.videoId
                    val cached = oldLocal[videoId]
                    newMap[videoId] = WatchProgressEntry(
                        contentType = entry.contentType,
                        parentMetaId = entry.contentId,
                        parentMetaType = cached?.parentMetaType ?: entry.contentType,
                        videoId = videoId,
                        title = cached?.title?.takeIf { it.isNotBlank() } ?: entry.contentId,
                        logo = cached?.logo,
                        poster = cached?.poster,
                        background = cached?.background,
                        seasonNumber = entry.season,
                        episodeNumber = entry.episode,
                        episodeTitle = cached?.episodeTitle,
                        episodeThumbnail = cached?.episodeThumbnail,
                        lastPositionMs = entry.position,
                        durationMs = entry.duration,
                        lastUpdatedEpochMs = entry.lastWatched,
                        providerName = cached?.providerName,
                        providerAddonId = cached?.providerAddonId,
                        lastStreamTitle = cached?.lastStreamTitle,
                        lastStreamSubtitle = cached?.lastStreamSubtitle,
                        pauseDescription = cached?.pauseDescription,
                        lastSourceUrl = cached?.lastSourceUrl,
                        isCompleted = isWatchProgressComplete(entry.position, entry.duration, false),
                    )
                }

                entriesByVideoId = newMap
                hasLoaded = true
                hasCompletedInitialNuvioSyncPull = true
                publish()
                persist()
                log.d {
                    "pullFromServer NuvioSync applied entries=${entriesByVideoId.size} " +
                        "items=${entriesByVideoId.values.debugWatchProgressEntrySummary()}"
                }

                resolveRemoteMetadata()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                log.e(e) { "Failed to pull watch progress from server" }
            }
        } finally {
            if (!useTraktProgress) {
                isPullingNuvioSyncFromServer = false
            }
        }
    }

    private fun resolveRemoteMetadata() {
        val needsResolution = entriesByVideoId.values
            .filter { it.poster.isNullOrBlank() || it.background.isNullOrBlank() }
            .groupBy { it.parentMetaId to it.contentType }

        if (needsResolution.isEmpty()) {
            log.d { "resolveRemoteMetadata skipped: all entries have artwork" }
            return
        }
        log.d {
            "resolveRemoteMetadata start groups=${needsResolution.size} " +
                "entries=${needsResolution.values.sumOf { it.size }} " +
                "keys=${needsResolution.keys.joinToString(limit = 12) { (metaId, type) -> "$type:$metaId" }}"
        }

        metadataResolutionJob?.cancel()
        metadataResolutionJob = syncScope.launch {
            withTimeoutOrNull(30_000L) {
                AddonRepository.awaitManifestsLoaded()
            } ?: run {
                log.w { "Timed out waiting for addon manifests" }
                return@launch
            }

            for ((key, entries) in needsResolution) {
                val (metaId, metaType) = key
                val meta = runCatching {
                    MetaDetailsRepository.fetch(metaType, metaId)
                }.getOrNull()
                if (meta == null) {
                    log.d { "resolveRemoteMetadata miss type=$metaType id=$metaId entries=${entries.size}" }
                    continue
                }

                for (entry in entries) {
                    val episodeVideo = if (entry.seasonNumber != null && entry.episodeNumber != null) {
                        meta.videos.find { v ->
                            v.season == entry.seasonNumber && v.episode == entry.episodeNumber
                        }
                    } else null

                    entriesByVideoId[entry.videoId] = entry.copy(
                        title = meta.name,
                        poster = meta.poster,
                        background = meta.background,
                        logo = meta.logo,
                        episodeTitle = episodeVideo?.title ?: entry.episodeTitle,
                        episodeThumbnail = episodeVideo?.thumbnail ?: entry.episodeThumbnail,
                        pauseDescription = episodeVideo?.overview
                            ?: meta.description
                            ?: entry.pauseDescription,
                    )
                }

                publish()
                log.d {
                    "resolveRemoteMetadata applied type=$metaType id=$metaId entries=${entries.size} " +
                        "metaVideos=${meta.videos.size}"
                }
            }
            persist()
            log.d { "resolveRemoteMetadata complete entries=${entriesByVideoId.size}" }
        }
    }

    fun upsertPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true)
    }

    fun flushPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true)
    }

    fun clearProgress(videoId: String) {
        clearProgress(listOf(videoId))
    }

    fun clearProgress(videoIds: Collection<String>) {
        ensureLoaded()
        if (videoIds.isEmpty()) return

        if (shouldUseTraktProgress()) {
            videoIds.forEach(TraktProgressRepository::applyOptimisticRemoval)
            publish()
            return
        }

        val removedEntries = videoIds.mapNotNull { videoId ->
            entriesByVideoId.remove(videoId)
        }
        if (removedEntries.isNotEmpty()) {
            publish()
            persist()
            pushDeleteToServer(removedEntries)
        }
    }

    fun removeProgress(
        contentId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) {
        ensureLoaded()
        val normalizedContentId = contentId.trim()
        if (normalizedContentId.isBlank()) return

        val entriesToRemove = currentEntries().filter { entry ->
            if (entry.parentMetaId != normalizedContentId) {
                false
            } else if (seasonNumber != null && episodeNumber != null) {
                entry.seasonNumber == seasonNumber && entry.episodeNumber == episodeNumber
            } else {
                true
            }
        }
        if (entriesToRemove.isEmpty()) return

        if (shouldUseTraktProgress()) {
            TraktProgressRepository.applyOptimisticRemoval(
                contentId = normalizedContentId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
            publish()
            syncScope.launch {
                runCatching {
                    TraktProgressRepository.removeProgress(
                        contentId = normalizedContentId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                    )
                }.onFailure { error ->
                    log.e(error) { "Failed to remove Trakt watch progress" }
                }
            }
            return
        }

        entriesToRemove.forEach { entry ->
            entriesByVideoId.remove(entry.videoId)
        }
        publish()
        persist()
        pushDeleteToServer(entriesToRemove)
    }

    fun progressForVideo(videoId: String): WatchProgressEntry? {
        ensureLoaded()
        return if (shouldUseTraktProgress()) {
            TraktProgressRepository.uiState.value.entries
        } else {
            entriesByVideoId.values.toList()
        }.firstOrNull { it.videoId == videoId }
    }

    fun resumeEntryForSeries(metaId: String): WatchProgressEntry? {
        ensureLoaded()
        return currentEntries().resumeEntryForSeries(metaId)
    }

    fun continueWatching(): List<WatchProgressEntry> {
        ensureLoaded()
        return currentEntries().continueWatchingEntries()
    }

    private fun upsert(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        persist: Boolean,
    ) {
        val positionMs = snapshot.positionMs.coerceAtLeast(0L)
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val isCompleted = isWatchProgressComplete(
            positionMs = positionMs,
            durationMs = durationMs,
            isEnded = snapshot.isEnded,
        )
        if (!isCompleted && !shouldStoreWatchProgress(positionMs = positionMs, durationMs = durationMs)) {
            log.d {
                "upsert skipped below threshold video=${session.videoId} content=${session.parentMetaId} " +
                    "s=${session.seasonNumber} e=${session.episodeNumber} pos=$positionMs dur=$durationMs ended=${snapshot.isEnded}"
            }
            return
        }

        val entry = WatchProgressEntry(
            contentType = session.contentType,
            parentMetaId = session.parentMetaId,
            parentMetaType = session.parentMetaType,
            videoId = session.videoId,
            title = session.title,
            logo = session.logo,
            poster = session.poster,
            background = session.background,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber,
            episodeTitle = session.episodeTitle,
            episodeThumbnail = session.episodeThumbnail,
            lastPositionMs = if (isCompleted && durationMs > 0L) durationMs else positionMs,
            durationMs = durationMs,
            lastUpdatedEpochMs = WatchProgressClock.nowEpochMs(),
            providerName = session.providerName,
            providerAddonId = session.providerAddonId,
            lastStreamTitle = session.lastStreamTitle,
            lastStreamSubtitle = session.lastStreamSubtitle,
            pauseDescription = session.pauseDescription,
            lastSourceUrl = session.lastSourceUrl,
            isCompleted = isCompleted,
        ).normalizedCompletion()

        if (entry.parentMetaType.equals("series", ignoreCase = true)) {
            ContinueWatchingPreferencesRepository.removeDismissedNextUpKeysForContent(entry.parentMetaId)
        }

        val useTraktProgress = shouldUseTraktProgress()
        log.d {
            "upsert progress source=${if (useTraktProgress) "trakt" else "nuvio_sync"} " +
                "entry=${entry.debugSummary()} snapshotEnded=${snapshot.isEnded}"
        }

        entriesByVideoId[session.videoId] = entry
        if (useTraktProgress) {
            TraktProgressRepository.applyOptimisticProgress(entry)
        }
        publish()
        if (persist) persist()
        if (entry.poster.isNullOrBlank() || entry.background.isNullOrBlank()) {
            resolveRemoteMetadata()
        }
        pushScrobbleToServer(entry)
        if (shouldCascadeCompletedProgressToWatchedHistory(entry, useTraktProgress)) {
            WatchingActions.onProgressEntryUpdated(entry)
        }
    }

    private fun pushScrobbleToServer(entry: WatchProgressEntry) {
        syncScope.launch {
            runCatching {
                val profileId = ProfileRepository.activeProfileId
                log.d { "pushScrobbleToServer profileId=$profileId entry=${entry.debugSummary()}" }
                syncAdapter.push(profileId = profileId, entries = listOf(entry))
                log.d { "pushScrobbleToServer complete profileId=$profileId video=${entry.videoId}" }
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress scrobble" }
            }
        }
    }

    private fun pushDeleteToServer(entries: Collection<WatchProgressEntry>) {
        if (shouldUseTraktProgress()) return
        syncScope.launch {
            runCatching {
                if (entries.isEmpty()) return@runCatching
                val profileId = ProfileRepository.activeProfileId
                log.d {
                    "pushDeleteToServer profileId=$profileId entries=${entries.size} " +
                        "items=${entries.debugWatchProgressEntrySummary()}"
                }
                syncAdapter.delete(profileId = profileId, entries = entries)
                log.d { "pushDeleteToServer complete profileId=$profileId entries=${entries.size}" }
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress delete" }
            }
        }
    }

    private fun publish() {
        val entries = currentEntries()
        val sortedEntries = entries.sortedByDescending { it.lastUpdatedEpochMs }
        log.d {
            "publish source=${if (shouldUseTraktProgress()) "trakt" else "nuvio_sync"} " +
                "entries=${sortedEntries.size} cw=${sortedEntries.continueWatchingEntries().size} " +
                "sources=${sortedEntries.debugSourceCounts()} items=${sortedEntries.debugWatchProgressEntrySummary()}"
        }
        _uiState.value = WatchProgressUiState(
            entries = sortedEntries,
            hasLoadedRemoteProgress = if (shouldUseTraktProgress()) {
                TraktProgressRepository.uiState.value.hasLoadedRemoteProgress
            } else {
                hasLoaded
            },
        )
    }

    private fun persist() {
        WatchProgressStorage.savePayload(
            currentProfileId,
            WatchProgressCodec.encodeEntries(entriesByVideoId.values),
        )
    }

    private fun shouldUseTraktProgress(): Boolean =
        shouldUseTraktProgressSource(
            isAuthenticated = TraktAuthRepository.isAuthenticated.value,
            source = TraktSettingsRepository.uiState.value.watchProgressSource,
        )

    private fun currentEntries(): List<WatchProgressEntry> {
        return if (shouldUseTraktProgress()) {
            TraktProgressRepository.uiState.value.entries
        } else {
            entriesByVideoId.values.toList()
        }
    }

}

private fun ProgressSyncRecord.debugSummary(): String =
    buildString {
        append(contentType)
        append(":")
        append(contentId)
        if (season != null || episode != null) {
            append(" s=")
            append(season)
            append(" e=")
            append(episode)
        }
        append(" video=")
        append(videoId)
        append(" pos=")
        append(position)
        append(" dur=")
        append(duration)
        append(" last=")
        append(lastWatched)
    }

private fun Collection<ProgressSyncRecord>.debugProgressRecordSummary(limit: Int = 10): String =
    take(limit).joinToString(separator = " | ") { it.debugSummary() }.ifBlank { "none" }

private fun WatchProgressEntry.debugSummary(): String =
    buildString {
        append(parentMetaType)
        append(":")
        append(parentMetaId)
        if (seasonNumber != null || episodeNumber != null) {
            append(" s=")
            append(seasonNumber)
            append(" e=")
            append(episodeNumber)
        }
        append(" video=")
        append(videoId)
        append(" pos=")
        append(lastPositionMs)
        append(" dur=")
        append(durationMs)
        append(" pct=")
        append(progressPercent)
        append(" completed=")
        append(isCompleted)
        append(" effectiveCompleted=")
        append(isEffectivelyCompleted)
        append(" src=")
        append(source)
        append(" last=")
        append(lastUpdatedEpochMs)
    }

private fun Collection<WatchProgressEntry>.debugWatchProgressEntrySummary(limit: Int = 10): String =
    take(limit).joinToString(separator = " | ") { it.debugSummary() }.ifBlank { "none" }

private fun Collection<WatchProgressEntry>.debugSourceCounts(): String =
    groupingBy { it.source }
        .eachCount()
        .entries
        .sortedBy { it.key }
        .joinToString(separator = ",") { "${it.key}=${it.value}" }
        .ifBlank { "none" }
