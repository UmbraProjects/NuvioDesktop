package com.nuvio.app.features.watchprogress

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonsUiState
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SIMKL_CW_DAYS_CAP_ALL
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.simklContinueWatchingCutoffMs
import com.nuvio.app.features.simkl.SimklCalendarRepository
import com.nuvio.app.features.mdblist.MdbListCalendarRepository
import com.nuvio.app.features.mdblist.MdbListProgressRepository
import com.nuvio.app.features.tracking.ContinueWatchingSource
import com.nuvio.app.features.tracking.ContinueWatchingSourceRepository
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.resolveContinueWatchingSource
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.mdblist.WatchProgressSourceMdbList
import com.nuvio.app.features.simkl.SimklProgressRepository
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.simkl.WatchProgressSourceSimkl
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktCalendarRepository
import com.nuvio.app.features.trakt.TraktProgressRepository
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.isTraktCompatibleId
import com.nuvio.app.features.trakt.resolveEffectiveContentId
import com.nuvio.app.features.trakt.shouldUseTraktProgress as shouldUseTraktProgressSource
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.sync.ProgressDeltaEvent
import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import com.nuvio.app.features.watching.sync.ProgressSyncAdapter
import com.nuvio.app.features.watching.sync.SupabaseProgressSyncAdapter
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.yamtrack.YamtrackSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

private const val WATCH_PROGRESS_METADATA_RESOLUTION_CONCURRENCY = 4
private const val WATCH_PROGRESS_METADATA_RESOLUTION_LIMIT = 64
private const val WATCH_PROGRESS_STARTUP_METADATA_GRACE_MS = 1_200L
private const val WATCH_PROGRESS_DELTA_PAGE_SIZE = 900
private const val WATCH_PROGRESS_DELTA_OPERATION_UPSERT = "upsert"
private const val WATCH_PROGRESS_DELTA_OPERATION_DELETE = "delete"
private const val WATCH_PROGRESS_REMOTE_STOP_REFRESH_DELAY_MS = 2_500L

private data class RemoteMetadataResolutionResult(
    val key: Pair<String, String>,
    val entries: List<WatchProgressEntry>,
    val meta: MetaDetails?,
)

/**
 * SIMKL anime progress can use entry-local coordinates while addon metadata exposes the whole
 * franchise. Prefer SIMKL's episode title when it uniquely identifies a video; coordinates remain
 * the fallback for providers that omit or localize titles.
 */
internal fun resolveRemoteProgressEpisode(
    videos: List<MetaVideo>,
    entry: WatchProgressEntry,
): MetaVideo? {
    val normalizedTitle = entry.episodeTitle?.normalizeProgressEpisodeTitle()
    if (!normalizedTitle.isNullOrBlank()) {
        val titleMatches = videos.filter { video ->
            video.title.normalizeProgressEpisodeTitle() == normalizedTitle
        }
        if (titleMatches.size == 1) return titleMatches.first()
    }
    val season = entry.seasonNumber ?: return null
    val episode = entry.episodeNumber ?: return null
    return videos.find { video -> video.season == season && video.episode == episode }
}

private fun String.normalizeProgressEpisodeTitle(): String =
    lowercase().filter(Char::isLetterOrDigit)

private data class MetadataProviderReadiness(
    val providers: List<AddonManifest>,
    val isRefreshing: Boolean,
) {
    val fingerprint: String
        get() = providers.map(AddonManifest::transportUrl).sorted().joinToString(separator = "|")

    val isReady: Boolean
        get() = providers.isNotEmpty() && !isRefreshing

    val isSettledWithoutProviders: Boolean
        get() = !isRefreshing && providers.isEmpty()
}

private data class WatchProgressDeltaApplyResult(
    val appliedUpserts: Int,
    val appliedDeletes: Int,
    val preservedLocalItems: Boolean,
    val changed: Boolean,
)

internal enum class ContinueWatchingRemovalTarget {
    LOCAL,
    TRAKT,
    SIMKL,
    MDBLIST,
}

internal fun continueWatchingRemovalTarget(source: String): ContinueWatchingRemovalTarget? =
    when (source) {
        WatchProgressSourceLocal -> ContinueWatchingRemovalTarget.LOCAL
        WatchProgressSourceTraktPlayback,
        WatchProgressSourceTraktHistory,
        WatchProgressSourceTraktShowProgress,
        -> ContinueWatchingRemovalTarget.TRAKT
        WatchProgressSourceSimkl -> ContinueWatchingRemovalTarget.SIMKL
        WatchProgressSourceMdbList -> ContinueWatchingRemovalTarget.MDBLIST
        else -> null
    }

object WatchProgressRepository {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("WatchProgressRepository")

    private val _uiState = MutableStateFlow(WatchProgressUiState())
    val uiState: StateFlow<WatchProgressUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var currentProfileId: Int = 1
    private var entriesByVideoId: MutableMap<String, WatchProgressEntry> = mutableMapOf()
    private var metadataResolutionJob: Job? = null
    private var metadataResolutionStartupGraceUsed = false
    private var isPullingNuvioSyncFromServer = false
    private var lastSuccessfulPushEpochMs = 0L
    private var deltaCursorEventId = 0L
    private var deltaInitialized = false
    private var lastAddonMetadataReadyFingerprint: String? = null
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
            SimklAuthRepository.isAuthenticated.collectLatest { authenticated ->
                if (authenticated && shouldUseSimklProgress()) {
                    runCatching { SimklProgressRepository.refreshNow() }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            log.w { "Failed to refresh SIMKL progress after auth: ${e.message}" }
                        }
                }
                publish()
            }
        }

        syncScope.launch {
            SimklProgressRepository.uiState.collectLatest { state ->
                if (shouldUseSimklProgress()) {
                    publish()
                    // When SIMKL entries load with missing images, reset the addon fingerprint
                    // so resolveRemoteMetadata() runs again once addons are ready.
                    if (state.hasLoaded && state.entries.any { it.poster.isNullOrBlank() || it.background.isNullOrBlank() }) {
                        lastAddonMetadataReadyFingerprint = null
                        resolveRemoteMetadata(useStartupGrace = true)
                        retryMetadataResolutionWhenAddonMetaProvidersReady(AddonRepository.uiState.value)
                    }
                }
            }
        }

        syncScope.launch {
            MdbListProgressRepository.uiState.collectLatest { state ->
                if (shouldUseMdbListProgress()) {
                    publish()
                    if (state.hasLoaded && state.entries.any { it.poster.isNullOrBlank() || it.background.isNullOrBlank() }) {
                        lastAddonMetadataReadyFingerprint = null
                        resolveRemoteMetadata(useStartupGrace = true)
                        retryMetadataResolutionWhenAddonMetaProvidersReady(AddonRepository.uiState.value)
                    }
                }
            }
        }

        syncScope.launch {
            WatchedRepository.uiState.collectLatest {
                if (shouldUseYamtrackProgress()) publish()
            }
        }

        syncScope.launch {
            ContinueWatchingSourceRepository.uiState.collectLatest {
                // Switching source has to pull the new one immediately, or Continue Watching shows
                // an empty list until something else happens to trigger a refresh.
                when (activeContinueWatchingSource()) {
                    ContinueWatchingSource.MDBLIST ->
                        runCatching { MdbListProgressRepository.refreshNow() }
                    ContinueWatchingSource.SIMKL ->
                        runCatching { SimklProgressRepository.refreshNow() }
                    ContinueWatchingSource.TRAKT ->
                        runCatching { TraktProgressRepository.refreshNow() }
                    ContinueWatchingSource.YAMTRACK ->
                        runCatching { WatchedRepository.pullFromServer(ProfileRepository.activeProfileId) }
                    ContinueWatchingSource.LOCAL -> Unit
                }
                publish()
            }
        }

        syncScope.launch {
            MdbListSettingsRepository.uiState.collectLatest {
                if (shouldUseMdbListProgress()) {
                    runCatching { MdbListProgressRepository.refreshNow() }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            log.w { "Failed to refresh MDBList progress after source change: ${e.message}" }
                        }
                }
                publish()
            }
        }

        syncScope.launch {
            SimklSettingsRepository.uiState.collectLatest {
                val useSimkl = shouldUseSimklProgress()
                if (useSimkl) {
                    runCatching { SimklProgressRepository.refreshNow() }
                        .onFailure { e ->
                            if (e is CancellationException) throw e
                            log.w { "Failed to refresh SIMKL progress after source change: ${e.message}" }
                        }
                }
                publish()
            }
        }

        syncScope.launch {
            AddonRepository.uiState.collectLatest { state ->
                retryMetadataResolutionWhenAddonMetaProvidersReady(state)
            }
        }

    }

    fun ensureLoaded() {
        TraktAuthRepository.ensureLoaded()
        TraktSettingsRepository.ensureLoaded()
        TraktProgressRepository.ensureLoaded()
        SimklSettingsRepository.ensureLoaded()
        SimklAuthRepository.ensureLoaded()
        MdbListProgressRepository.ensureLoaded()
        SimklProgressRepository.ensureLoaded()
        if (hasLoaded) return
        loadFromDisk(ProfileRepository.activeProfileId)
        when {
            shouldUseMdbListProgress() -> MdbListProgressRepository.refreshAsync()
            shouldUseSimklProgress() -> SimklProgressRepository.refreshAsync()
            shouldUseTraktProgress() -> TraktProgressRepository.refreshAsync()
        }
    }

    /**
     * Pulls the active Continue Watching source after startup initialization has settled. The
     * ordinary ensureLoaded path can run before profile-scoped integration settings finish loading,
     * leaving cross-device progress stale until a profile switch happens to trigger another pull.
     */
    suspend fun forceContinueWatchingSync(profileId: Int) {
        ensureLoaded()
        when {
            shouldUseMdbListProgress() -> {
                log.d { "Force refreshing MDBList Continue Watching for profile $profileId" }
                MdbListProgressRepository.refreshNow()
            }
            shouldUseSimklProgress() -> {
                log.d { "Force refreshing SIMKL Continue Watching for profile $profileId" }
                SimklProgressRepository.refreshNow()
            }
            shouldUseTraktProgress() -> {
                log.d { "Force refreshing Trakt Continue Watching for profile $profileId" }
                TraktProgressRepository.refreshNow()
            }
            else -> {
                log.d { "Force refreshing Nuvio Continue Watching for profile $profileId" }
                pullFromServer(profileId)
            }
        }
        publish()
    }

    fun onProfileChanged(profileId: Int) {
        if (profileId == currentProfileId && hasLoaded) return
        TraktSettingsRepository.onProfileChanged()
        loadFromDisk(profileId)
        TraktProgressRepository.onProfileChanged()
        TraktCalendarRepository.onProfileChanged()
        SimklCalendarRepository.onProfileChanged()
        MdbListCalendarRepository.onProfileChanged()
        MdbListProgressRepository.onProfileChanged()
        SimklProgressRepository.onProfileChanged()
        when {
            shouldUseMdbListProgress() -> MdbListProgressRepository.refreshAsync()
            shouldUseSimklProgress() -> SimklProgressRepository.refreshAsync()
            shouldUseTraktProgress() -> TraktProgressRepository.refreshAsync()
        }
    }

    fun clearLocalState() {
        metadataResolutionJob?.cancel()
        hasLoaded = false
        currentProfileId = 1
        lastAddonMetadataReadyFingerprint = null
        entriesByVideoId.clear()
        lastSuccessfulPushEpochMs = 0L
        deltaCursorEventId = 0L
        deltaInitialized = false
        MdbListProgressRepository.clearLocalState()
        MdbListCalendarRepository.clearLocalState()
        TraktProgressRepository.clearLocalState()
        TraktCalendarRepository.clearLocalState()
        TraktSettingsRepository.clearLocalState()
        _uiState.value = WatchProgressUiState()
    }

    private fun loadFromDisk(profileId: Int) {
        currentProfileId = profileId
        hasLoaded = true
        lastAddonMetadataReadyFingerprint = null
        entriesByVideoId.clear()

        val payload = WatchProgressStorage.loadPayload(profileId).orEmpty().trim()
        if (payload.isNotEmpty()) {
            val storedPayload = WatchProgressCodec.decodePayload(payload)
            lastSuccessfulPushEpochMs = storedPayload.lastSuccessfulPushEpochMs
            deltaCursorEventId = storedPayload.deltaCursorEventId
            deltaInitialized = storedPayload.deltaInitialized
            entriesByVideoId = storedPayload.entries
                .associateBy { it.videoId }
                .toMutableMap()
        } else {
            lastSuccessfulPushEpochMs = 0L
            deltaCursorEventId = 0L
            deltaInitialized = false
        }
        log.d {
            "Loaded watch progress for profile $profileId: entries=${entriesByVideoId.size} " +
                "deltaInitialized=$deltaInitialized cursor=$deltaCursorEventId lastPush=$lastSuccessfulPushEpochMs"
        }
        publish()
        resolveRemoteMetadata(useStartupGrace = true)
    }

    suspend fun pullFromServer(profileId: Int) {
        TraktAuthRepository.ensureLoaded(profileId)
        TraktSettingsRepository.ensureLoaded()
        TraktProgressRepository.ensureLoaded()
        currentProfileId = profileId

        val useTraktProgress = shouldUseTraktProgress()

        if (!useTraktProgress && isPullingNuvioSyncFromServer) {
            log.d { "Skipping watch progress pull for profile $profileId because a Nuvio sync pull is already running" }
            return
        }
        if (!useTraktProgress) {
            isPullingNuvioSyncFromServer = true
        }

        try {
            if (useTraktProgress) {
                log.d { "Pulling Trakt watch progress for profile $profileId" }
                runCatching { TraktProgressRepository.refreshNow() }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        log.e(e) { "Failed to pull Trakt progress" }
                    }
                publish()
                return
            }

            runCatching {
                log.d { "Pulling Nuvio watch progress for profile $profileId" }
                pullSupabaseDeltaFromServer(
                    profileId = profileId,
                    pullStartedEpochMs = WatchProgressClock.nowEpochMs(),
                )
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

    suspend fun forceSnapshotRefreshFromServer(profileId: Int) {
        ensureLoaded()
        if (currentProfileId != profileId) {
            loadFromDisk(profileId)
        }

        if (shouldUseTraktProgress()) {
            log.d { "Force refreshing Trakt watch progress for profile $profileId" }
            runCatching { TraktProgressRepository.refreshNow() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.e(error) { "Failed to force refresh Trakt progress" }
                }
            publish()
            return
        }

        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) {
            log.d { "Skipping force watch progress refresh because Nuvio Sync is not authenticated" }
            return
        }

        deltaCursorEventId = 0L
        deltaInitialized = false
        persist()
        pullFromServer(profileId)
    }

    private suspend fun pullSupabaseDeltaFromServer(
        profileId: Int,
        pullStartedEpochMs: Long,
    ) {
        log.d {
            "Watch progress delta sync start: profile=$profileId entries=${entriesByVideoId.size} " +
                "deltaInitialized=$deltaInitialized cursor=$deltaCursorEventId lastPush=$lastSuccessfulPushEpochMs"
        }
        if (!deltaInitialized) {
            log.d { "Watch progress delta not initialized for profile $profileId; requesting cursor before snapshot" }
            val cursorBeforeSnapshot = try {
                syncAdapter.getDeltaCursor(profileId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Watch progress delta cursor unavailable, falling back to full pull: ${error.message}" }
                null
            }
            if (cursorBeforeSnapshot == null) {
                log.d { "Watch progress delta cursor unavailable for profile $profileId; using snapshot fallback" }
                pullFullFromAdapter(
                    profileId = profileId,
                    pullStartedEpochMs = pullStartedEpochMs,
                    resetDeltaState = true,
                )
                return
            }

            log.d { "Watch progress delta cursor before snapshot for profile $profileId is $cursorBeforeSnapshot" }
            pullFullFromAdapter(
                profileId = profileId,
                pullStartedEpochMs = pullStartedEpochMs,
                resetDeltaState = false,
            )
            deltaCursorEventId = cursorBeforeSnapshot
            deltaInitialized = true
            persist()
            log.d {
                "Watch progress delta initialized for profile $profileId: cursor=$deltaCursorEventId " +
                    "entries=${entriesByVideoId.size}"
            }
            return
        }

        var cursor = deltaCursorEventId
        var changed = false
        var totalUpserts = 0
        var totalDeletes = 0
        var preservedLocalItems = false
        var page = 1

        while (true) {
            log.d { "Pulling watch progress delta page $page for profile $profileId from cursor $cursor" }
            val events = try {
                syncAdapter.pullDelta(
                    profileId = profileId,
                    sinceEventId = cursor,
                    limit = WATCH_PROGRESS_DELTA_PAGE_SIZE,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Watch progress delta pull unavailable, falling back to full pull: ${error.message}" }
                pullFullFromAdapter(
                    profileId = profileId,
                    pullStartedEpochMs = pullStartedEpochMs,
                    resetDeltaState = true,
                )
                return
            }
            if (events.isEmpty()) {
                log.d { "Watch progress delta page $page returned no events for profile $profileId at cursor $cursor" }
                break
            }

            val firstEvent = events.firstOrNull()?.eventId
            val lastEvent = events.lastOrNull()?.eventId
            val eventUpserts = events.count { it.operation.equals(WATCH_PROGRESS_DELTA_OPERATION_UPSERT, ignoreCase = true) }
            val eventDeletes = events.count { it.operation.equals(WATCH_PROGRESS_DELTA_OPERATION_DELETE, ignoreCase = true) }
            log.d {
                "Watch progress delta page $page fetched ${events.size} events for profile $profileId " +
                    "first=$firstEvent last=$lastEvent upserts=$eventUpserts deletes=$eventDeletes"
            }

            val pageResult = applyWatchProgressDeltaEvents(
                events = events,
                pullStartedEpochMs = pullStartedEpochMs,
            )
            changed = pageResult.changed || changed
            totalUpserts += pageResult.appliedUpserts
            totalDeletes += pageResult.appliedDeletes
            preservedLocalItems = preservedLocalItems || pageResult.preservedLocalItems
            cursor = maxOf(cursor, events.maxOf { it.eventId })
            deltaCursorEventId = cursor
            deltaInitialized = true
            log.d {
                "Watch progress delta page $page applied for profile $profileId: " +
                    "appliedUpserts=${pageResult.appliedUpserts} appliedDeletes=${pageResult.appliedDeletes} " +
                    "preservedLocal=${pageResult.preservedLocalItems} newCursor=$cursor"
            }

            if (events.size < WATCH_PROGRESS_DELTA_PAGE_SIZE) break
            page += 1
        }

        hasLoaded = true
        if (changed) {
            publish()
            persist()
            resolveRemoteMetadata()
        }
        log.d {
            "Watch progress delta sync finished for profile $profileId: changed=$changed " +
                "appliedUpserts=$totalUpserts appliedDeletes=$totalDeletes preservedLocal=$preservedLocalItems " +
                "cursor=$deltaCursorEventId entries=${entriesByVideoId.size}"
        }
    }

    private suspend fun pullFullFromAdapter(
        profileId: Int,
        pullStartedEpochMs: Long,
        resetDeltaState: Boolean,
    ) {
        val serverEntries = syncAdapter.pull(profileId = profileId)
        log.d {
            "Watch progress snapshot fetched ${serverEntries.size} entries for profile $profileId " +
                "resetDeltaState=$resetDeltaState"
        }
        entriesByVideoId = mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = serverEntries,
            localEntries = entriesByVideoId.values,
            lastSuccessfulPushEpochMs = lastSuccessfulPushEpochMs,
            pullStartedEpochMs = pullStartedEpochMs,
        ).toMutableMap()
        if (resetDeltaState) {
            deltaCursorEventId = 0L
            deltaInitialized = false
        }
        hasLoaded = true
        publish()
        persist()
        resolveRemoteMetadata()
        log.d {
            "Watch progress snapshot applied for profile $profileId: entries=${entriesByVideoId.size} " +
                "deltaInitialized=$deltaInitialized cursor=$deltaCursorEventId"
        }
    }

    private fun applyWatchProgressDeltaEvents(
        events: Collection<ProgressDeltaEvent>,
        pullStartedEpochMs: Long,
    ): WatchProgressDeltaApplyResult {
        var changed = false
        var appliedUpserts = 0
        var appliedDeletes = 0
        var preservedLocalItems = false
        events.forEach { event ->
            if (event.videoId.isBlank()) return@forEach
            when (event.operation.lowercase()) {
                WATCH_PROGRESS_DELTA_OPERATION_UPSERT -> {
                    val current = entriesByVideoId[event.videoId]
                    val updated = event.toProgressSyncRecord().toWatchProgressEntry(cached = current)
                    if (current != updated) {
                        entriesByVideoId[event.videoId] = updated
                        changed = true
                        appliedUpserts += 1
                    }
                }
                WATCH_PROGRESS_DELTA_OPERATION_DELETE -> {
                    val localEntry = entriesByVideoId[event.videoId]
                    if (
                        localEntry != null &&
                        shouldPreserveLocalWatchProgressEntry(
                            localEntry = localEntry,
                            lastSuccessfulPushEpochMs = lastSuccessfulPushEpochMs,
                            pullStartedEpochMs = pullStartedEpochMs,
                        )
                    ) {
                        preservedLocalItems = true
                        return@forEach
                    }
                    if (entriesByVideoId.remove(event.videoId) != null) {
                        changed = true
                        appliedDeletes += 1
                    }
                }
            }
        }
        return WatchProgressDeltaApplyResult(
            appliedUpserts = appliedUpserts,
            appliedDeletes = appliedDeletes,
            preservedLocalItems = preservedLocalItems,
            changed = changed,
        )
    }

    private fun ProgressSyncRecord.toWatchProgressEntry(cached: WatchProgressEntry?): WatchProgressEntry =
        WatchProgressEntry(
            contentType = contentType,
            parentMetaId = contentId,
            parentMetaType = cached?.parentMetaType ?: contentType,
            videoId = videoId,
            title = cached?.title?.takeIf { it.isNotBlank() } ?: contentId,
            logo = cached?.logo,
            poster = cached?.poster,
            background = cached?.background,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = cached?.episodeTitle,
            episodeThumbnail = cached?.episodeThumbnail,
            lastPositionMs = position,
            durationMs = duration,
            lastUpdatedEpochMs = lastWatched,
            providerName = cached?.providerName,
            providerAddonId = cached?.providerAddonId,
            lastStreamTitle = cached?.lastStreamTitle,
            lastStreamSubtitle = cached?.lastStreamSubtitle,
            pauseDescription = cached?.pauseDescription,
            lastSourceUrl = cached?.lastSourceUrl,
            lastSourceWasLocalFile = cached?.lastSourceWasLocalFile ?: false,
            isCompleted = isWatchProgressComplete(position, duration, false),
        )

    private fun ProgressDeltaEvent.toProgressSyncRecord(): ProgressSyncRecord =
        ProgressSyncRecord(
            contentId = contentId,
            contentType = contentType,
            videoId = videoId,
            season = season,
            episode = episode,
            position = position,
            duration = duration,
            lastWatched = lastWatched,
        )

    private fun mergeWatchProgressEntriesPreservingUnsynced(
        serverEntries: Collection<ProgressSyncRecord>,
        localEntries: Collection<WatchProgressEntry>,
        lastSuccessfulPushEpochMs: Long,
        pullStartedEpochMs: Long,
    ): Map<String, WatchProgressEntry> {
        val localByVideoId = localEntries.associateBy { entry -> entry.videoId }
        val merged = serverEntries.associate { record ->
            record.videoId to record.toWatchProgressEntry(cached = localByVideoId[record.videoId])
        }.toMutableMap()

        localByVideoId.forEach { (videoId, localEntry) ->
            val remoteEntry = merged[videoId]
            val shouldPreserve = shouldPreserveLocalWatchProgressEntry(
                localEntry = localEntry,
                lastSuccessfulPushEpochMs = lastSuccessfulPushEpochMs,
                pullStartedEpochMs = pullStartedEpochMs,
            )
            if (!shouldPreserve) return@forEach
            if (remoteEntry == null || localEntry.lastUpdatedEpochMs > remoteEntry.lastUpdatedEpochMs) {
                merged[videoId] = localEntry
            }
        }

        return merged
    }

    private fun shouldPreserveLocalWatchProgressEntry(
        localEntry: WatchProgressEntry,
        lastSuccessfulPushEpochMs: Long,
        pullStartedEpochMs: Long,
    ): Boolean {
        val updatedAt = localEntry.lastUpdatedEpochMs
        val wasUpdatedAfterLastPush = lastSuccessfulPushEpochMs > 0L && updatedAt > lastSuccessfulPushEpochMs
        val wasUpdatedDuringPull = pullStartedEpochMs > 0L && updatedAt >= pullStartedEpochMs
        return wasUpdatedAfterLastPush || wasUpdatedDuringPull
    }

    private fun retryMetadataResolutionWhenAddonMetaProvidersReady(state: AddonsUiState) {
        // Skip when Trakt is active (it supplies its own images) but run for local and SIMKL sources.
        if (!hasLoaded || shouldUseTraktProgress()) return

        val readiness = state.metadataProviderReadiness()
        if (!readiness.isReady) return

        val fingerprint = readiness.fingerprint
        if (fingerprint == lastAddonMetadataReadyFingerprint) return
        lastAddonMetadataReadyFingerprint = fingerprint

        if (metadataResolutionJob?.isActive == true) return
        resolveRemoteMetadata(useStartupGrace = true)
    }

    private fun resolveRemoteMetadata(useStartupGrace: Boolean = false) {
        val localMissing = entriesByVideoId.values
            .filter { it.poster.isNullOrBlank() || it.background.isNullOrBlank() }
        val mdbListMissing = if (shouldUseMdbListProgress()) {
            MdbListProgressRepository.uiState.value.entries
                .filter {
                    it.poster.isNullOrBlank() ||
                        it.background.isNullOrBlank() ||
                        (it.contentType == "series" && it.episodeTitle.isNullOrBlank())
                }
        } else emptyList()
        val simklMissing = if (shouldUseSimklProgress()) {
            SimklProgressRepository.uiState.value.entries
                .filter {
                    it.poster.isNullOrBlank() ||
                        it.background.isNullOrBlank() ||
                        (it.contentType == "series" && !it.episodeTitle.isNullOrBlank())
                }
        } else emptyList()
        val missingMetadataEntries = localMissing + mdbListMissing + simklMissing
        val entriesToResolve = missingMetadataEntries.continueWatchingEntries(
            limit = WATCH_PROGRESS_METADATA_RESOLUTION_LIMIT,
        )
        val needsResolution = entriesToResolve
            .groupBy { it.parentMetaId to it.contentType }

        if (needsResolution.isEmpty()) return

        metadataResolutionJob?.cancel()
        val shouldDelayForStartup = useStartupGrace && !metadataResolutionStartupGraceUsed
        if (shouldDelayForStartup) {
            metadataResolutionStartupGraceUsed = true
        }
        metadataResolutionJob = syncScope.launch {
            if (shouldDelayForStartup) {
                kotlinx.coroutines.delay(WATCH_PROGRESS_STARTUP_METADATA_GRACE_MS)
            }
            val providerReadiness = awaitReadyMetadataProviders() ?: return@launch
            lastAddonMetadataReadyFingerprint = providerReadiness.fingerprint

            val supportedNeedsResolution = needsResolution.filter { (key, _) ->
                val (metaId, metaType) = key
                providerReadiness.providers.any { provider ->
                    provider.supportsMetaRequest(type = metaType, id = metaId)
                }
            }
            if (supportedNeedsResolution.isEmpty()) return@launch

            var resolvedEntries = 0
            val semaphore = Semaphore(WATCH_PROGRESS_METADATA_RESOLUTION_CONCURRENCY)
            val resolutionResults = coroutineScope {
                supportedNeedsResolution.map { (key, entries) ->
                    async {
                        semaphore.withPermit {
                            fetchRemoteMetadataGroup(key = key, entries = entries)
                        }
                    }
                }.awaitAll()
            }

            for (result in resolutionResults) {
                ensureActive()
                val meta = result.meta
                if (meta == null) {
                    continue
                }

                var appliedEntries = 0
                for (entry in result.entries) {
                    val episodeVideo = resolveRemoteProgressEpisode(meta.videos, entry)

                    if (entry.source == WatchProgressSourceMdbList) {
                        MdbListProgressRepository.enrichEntry(
                            videoId = entry.videoId,
                            poster = meta.poster,
                            background = meta.background,
                            episodeTitle = episodeVideo?.title ?: entry.episodeTitle,
                            episodeThumbnail = episodeVideo?.thumbnail ?: entry.episodeThumbnail,
                        )
                        appliedEntries += 1
                        continue
                    }

                    if (entry.source == WatchProgressSourceSimkl) {
                        SimklProgressRepository.enrichEntry(
                            videoId = entry.videoId,
                            poster = meta.poster,
                            background = meta.background,
                            episodeTitle = episodeVideo?.title ?: entry.episodeTitle,
                            episodeThumbnail = episodeVideo?.thumbnail ?: entry.episodeThumbnail,
                            seasonNumber = episodeVideo?.season,
                            episodeNumber = episodeVideo?.episode,
                        )
                        appliedEntries += 1
                        continue
                    }

                    val current = entriesByVideoId[entry.videoId] ?: continue
                    entriesByVideoId[current.videoId] = current.copy(
                        title = meta.name,
                        poster = meta.poster,
                        background = meta.background,
                        logo = meta.logo,
                        episodeTitle = episodeVideo?.title ?: current.episodeTitle,
                        episodeThumbnail = episodeVideo?.thumbnail ?: current.episodeThumbnail,
                        pauseDescription = episodeVideo?.overview
                            ?: meta.description
                            ?: current.pauseDescription,
                    )
                    appliedEntries += 1
                }
                if (appliedEntries == 0) {
                    continue
                }

                resolvedEntries += appliedEntries
            }
            if (resolvedEntries > 0) {
                publish()
                persist()
            }
        }
    }

    private suspend fun fetchRemoteMetadataGroup(
        key: Pair<String, String>,
        entries: List<WatchProgressEntry>,
    ): RemoteMetadataResolutionResult {
        val (metaId, metaType) = key
        val meta = try {
            MetaDetailsRepository.fetch(metaType, metaId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        return RemoteMetadataResolutionResult(
            key = key,
            entries = entries,
            meta = meta,
        )
    }

    private suspend fun awaitReadyMetadataProviders(): MetadataProviderReadiness? {
        val current = AddonRepository.uiState.value.metadataProviderReadiness()
        if (current.isReady) return current
        if (current.isSettledWithoutProviders) return null

        val settled = withTimeoutOrNull(30_000L) {
            AddonRepository.uiState.first { state ->
                val readiness = state.metadataProviderReadiness()
                readiness.isReady || readiness.isSettledWithoutProviders
            }.metadataProviderReadiness()
        }
        return settled?.takeIf { it.isReady }
    }

    fun upsertPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        syncRemote: Boolean = true,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true, syncRemote = syncRemote)
    }

    fun flushPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        syncRemote: Boolean = true,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true, syncRemote = syncRemote)
        refreshContinueWatchingAfterPlaybackStops()
    }

    /**
     * The local entry is published synchronously by [upsert]. Remote Continue Watching sources
     * need a short grace period for their stop scrobble to be processed before their canonical
     * list is read again.
     */
    fun refreshContinueWatchingAfterPlaybackStops() {
        ensureLoaded()
        when {
            shouldUseMdbListProgress() -> syncScope.launch {
                delay(WATCH_PROGRESS_REMOTE_STOP_REFRESH_DELAY_MS)
                MdbListProgressRepository.refreshAsync()
            }

            shouldUseSimklProgress() -> syncScope.launch {
                delay(WATCH_PROGRESS_REMOTE_STOP_REFRESH_DELAY_MS)
                SimklProgressRepository.refreshAsync()
            }

            shouldUseTraktProgress() -> syncScope.launch {
                delay(WATCH_PROGRESS_REMOTE_STOP_REFRESH_DELAY_MS)
                runCatching { TraktProgressRepository.refreshNow() }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        log.w { "Failed to refresh Trakt Continue Watching after playback stopped: ${error.message}" }
                    }
            }
        }
    }

    fun clearProgress(videoId: String) {
        clearProgress(listOf(videoId))
    }

    fun clearProgress(videoIds: Collection<String>) {
        ensureLoaded()
        if (videoIds.isEmpty()) return

        val entriesToRemove = currentEntries().filter { entry -> entry.videoId in videoIds }
        when (activeContinueWatchingSource()) {
            ContinueWatchingSource.MDBLIST -> {
                removeMdbListProgress(entriesToRemove)
                return
            }
            ContinueWatchingSource.SIMKL -> {
                removeSimklProgress(entriesToRemove)
                return
            }
            ContinueWatchingSource.TRAKT -> {
                removeTraktProgress(entriesToRemove)
                return
            }
            // Floppy's rows are projected from the watched store, which the unmark that triggered
            // this has already cleared — so there is nothing provider-side to remove, and falling
            // through is what clears the local progress row. Returning here left it behind, to
            // reappear intact the moment the user switched back to the local source.
            ContinueWatchingSource.YAMTRACK -> Unit
            ContinueWatchingSource.LOCAL -> Unit
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

        when (activeContinueWatchingSource()) {
            ContinueWatchingSource.MDBLIST -> {
                removeMdbListProgress(entriesToRemove)
                return
            }
            ContinueWatchingSource.SIMKL -> {
                removeSimklProgress(entriesToRemove)
                return
            }
            ContinueWatchingSource.TRAKT -> {
                removeTraktProgress(entriesToRemove)
                return
            }
            // See clearProgress: nothing to remove provider-side, and the local row still needs it.
            ContinueWatchingSource.YAMTRACK -> Unit
            ContinueWatchingSource.LOCAL -> Unit
        }

        entriesToRemove.forEach { entry ->
            entriesByVideoId.remove(entry.videoId)
        }
        publish()
        persist()
        pushDeleteToServer(entriesToRemove)
    }

    /** Removes exactly the selected Continue Watching card from the service that supplied it. */
    fun removeContinueWatchingItem(item: ContinueWatchingItem) {
        ensureLoaded()
        val entry = currentEntries().firstOrNull { candidate ->
            candidate.videoId == item.videoId && candidate.source == item.source
        } ?: run {
            log.w { "Continue Watching removal target is stale: source=${item.source} videoId=${item.videoId}" }
            return
        }

        when (continueWatchingRemovalTarget(item.source)) {
            ContinueWatchingRemovalTarget.MDBLIST -> removeMdbListProgress(listOf(entry))
            ContinueWatchingRemovalTarget.SIMKL -> removeSimklProgress(listOf(entry))
            ContinueWatchingRemovalTarget.TRAKT -> removeTraktProgress(listOf(entry))
            ContinueWatchingRemovalTarget.LOCAL -> removeLocalProgress(listOf(entry))
            null -> log.w { "Unknown Continue Watching source '${item.source}'; refusing cross-provider removal" }
        }
    }

    private fun removeMdbListProgress(entries: List<WatchProgressEntry>) {
        if (entries.isEmpty()) return
        entries.forEach { MdbListProgressRepository.applyOptimisticRemoval(it.videoId) }
        publish()
        syncScope.launch {
            entries.forEach { entry ->
                runCatching { MdbListProgressRepository.deleteSession(entry.videoId) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        MdbListProgressRepository.applyOptimisticProgress(entry)
                        publish()
                        log.e(error) { "Failed to clear MDBList playback session for ${entry.videoId}" }
                    }
            }
        }
    }

    private fun removeSimklProgress(entries: List<WatchProgressEntry>) {
        if (entries.isEmpty()) return
        entries.forEach { SimklProgressRepository.applyOptimisticRemoval(it.videoId) }
        publish()
        syncScope.launch {
            entries.forEach { entry ->
                // Only a real playback session can be deleted upstream. The rest of SIMKL's rows
                // are watched-history seeds derived from the show's last_watched marker: there is
                // no session behind them, and the removal that matters for those is the history
                // removal WatchedRepository issues. Attempting the delete anyway threw, and the
                // rollback below then restored the row — which is what made those episodes
                // impossible to unmark.
                if (!SimklProgressRepository.hasPlaybackSession(entry.videoId)) {
                    SimklProgressRepository.suppressWatchedSeed(
                        videoId = entry.videoId,
                        watchedAtEpochMs = entry.lastUpdatedEpochMs,
                    )
                    return@forEach
                }
                runCatching { SimklProgressRepository.deleteSession(entry.videoId) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        SimklProgressRepository.applyOptimisticProgress(entry)
                        publish()
                        log.e(error) { "Failed to delete SIMKL playback session for ${entry.videoId}" }
                    }
            }
        }
    }

    private fun removeTraktProgress(entries: List<WatchProgressEntry>) {
        if (entries.isEmpty()) return
        entries.forEach { TraktProgressRepository.applyOptimisticRemoval(it.videoId) }
        publish()
        syncScope.launch {
            entries.forEach { entry ->
                runCatching {
                    TraktProgressRepository.removeProgress(
                        contentId = entry.parentMetaId,
                        seasonNumber = entry.seasonNumber,
                        episodeNumber = entry.episodeNumber,
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    TraktProgressRepository.applyOptimisticProgress(entry)
                    publish()
                    log.e(error) { "Failed to clear Trakt playback progress for ${entry.videoId}" }
                }
            }
        }
    }

    private fun removeLocalProgress(entries: List<WatchProgressEntry>) {
        val removedEntries = entries.mapNotNull { entry -> entriesByVideoId.remove(entry.videoId) }
        if (removedEntries.isEmpty()) return
        publish()
        persist()
        pushDeleteToServer(removedEntries)
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

    fun refreshEpisodeProgress(contentId: String, forceRefresh: Boolean = false) {
        ensureLoaded()
        if (!shouldUseTraktProgress()) return
        syncScope.launch {
            runCatching {
                TraktProgressRepository.refreshEpisodeProgress(
                    contentId = contentId,
                    forceRefresh = forceRefresh,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Failed to refresh Trakt episode progress for $contentId: ${error.message}" }
            }
        }
    }

    private fun upsert(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        persist: Boolean,
        syncRemote: Boolean,
    ) {
        val positionMs = snapshot.positionMs.coerceAtLeast(0L)
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val isCompleted = isWatchProgressComplete(
            positionMs = positionMs,
            durationMs = durationMs,
            isEnded = snapshot.isEnded,
        )
        if (!isCompleted && !shouldStoreWatchProgress(positionMs = positionMs, durationMs = durationMs)) {
            return
        }

        val useMdbListProgress = shouldUseMdbListProgress()
        val useSimklProgress = shouldUseSimklProgress()
        val useTraktProgress = shouldUseTraktProgress()

        // If Trakt is the active CW source and parentMetaId is not Trakt-resolvable
        // but videoId contains a valid IMDB/TMDB, use the resolved ID to avoid
        // duplicate CW entries (one local with garbage ID, one from Trakt with real ID).
        val effectiveParentMetaId = if (useTraktProgress) {
            resolveEffectiveContentId(session.parentMetaId, session.videoId)
        } else {
            session.parentMetaId
        }

        // Record native-anime episodes in the entry-relative coordinate space the details page
        // checks against; the session may carry franchise numbering depending on entry point.
        val (resolvedSeasonNumber, resolvedEpisodeNumber) = resolveNativeAnimeEpisodeCoordinates(
            parentMetaId = session.parentMetaId,
            videoId = session.videoId,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber,
        )

        val entry = WatchProgressEntry(
            contentType = session.contentType,
            parentMetaId = effectiveParentMetaId,
            parentMetaType = session.parentMetaType,
            videoId = session.videoId,
            title = session.title,
            logo = session.logo,
            poster = session.poster,
            background = session.background,
            seasonNumber = resolvedSeasonNumber,
            episodeNumber = resolvedEpisodeNumber,
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
            lastSourceWasLocalFile = isLocalFileSourceUrl(session.lastSourceUrl),
            isCompleted = isCompleted,
        ).normalizedCompletion()

        if (entry.parentMetaType.equals("series", ignoreCase = true)) {
            ContinueWatchingPreferencesRepository.removeDismissedNextUpKeysForContent(entry.parentMetaId)
        }

        entriesByVideoId[session.videoId] = entry
        when {
            useMdbListProgress -> MdbListProgressRepository.applyOptimisticProgress(entry)
            useSimklProgress -> SimklProgressRepository.applyOptimisticProgress(entry)
            useTraktProgress -> TraktProgressRepository.applyOptimisticProgress(entry)
        }
        publish()
        if (persist) persist()
        if (entry.poster.isNullOrBlank() || entry.background.isNullOrBlank()) {
            resolveRemoteMetadata()
        }
        if (syncRemote) {
            pushScrobbleToServer(entry)
        }
        if (shouldCascadeCompletedProgressToWatchedHistory(entry, useTraktProgress)) {
            WatchingActions.onProgressEntryUpdated(entry, syncRemote = syncRemote)
        }
    }

    // Nuvio Sync RPCs require a real (non-anonymous) Supabase session server-side; pushing
    // without one just earns an "Unauthorized: valid session required for sync" rejection.
    // Mirrors the gate the pull path (forceSnapshotRefreshFromServer) and LibraryRepository
    // already apply.
    private fun isNuvioSyncAuthenticated(): Boolean {
        val authState = AuthRepository.state.value
        return authState is AuthState.Authenticated && !authState.isAnonymous
    }

    private fun pushScrobbleToServer(entry: WatchProgressEntry) {
        if (!isNuvioSyncAuthenticated()) {
            log.d { "Skipping watch progress scrobble push: Nuvio Sync is not authenticated" }
            return
        }
        syncScope.launch {
            runCatching {
                val profileId = ProfileRepository.activeProfileId
                withSyncRetry("Watch progress scrobble push") {
                    syncAdapter.push(profileId = profileId, entries = listOf(entry))
                }
                recordSuccessfulPush(profileId = profileId, entries = listOf(entry))
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress scrobble" }
            }
        }
    }

    // The sync_push_* RPCs intermittently hit the client request timeout; a completion scrobble
    // dropped here never reaches other devices, so retry briefly before giving up.
    private suspend fun <T> withSyncRetry(description: String, block: suspend () -> T): T {
        val retryDelaysMs = longArrayOf(2_000L, 5_000L)
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt >= retryDelaysMs.size) throw error
                log.w { "$description failed (attempt ${attempt + 1}/${retryDelaysMs.size + 1}), retrying: ${error.message}" }
                delay(retryDelaysMs[attempt])
                attempt += 1
            }
        }
    }

    private fun pushDeleteToServer(entries: Collection<WatchProgressEntry>) {
        if (shouldUseTraktProgress()) return
        if (!isNuvioSyncAuthenticated()) {
            log.d { "Skipping watch progress delete push: Nuvio Sync is not authenticated" }
            return
        }
        syncScope.launch {
            runCatching {
                if (entries.isEmpty()) return@runCatching
                val profileId = ProfileRepository.activeProfileId
                syncAdapter.delete(profileId = profileId, entries = entries)
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress delete" }
            }
        }
    }

    private fun publish() {
        val entries = currentEntries()
        val sortedEntries = entries.sortedByDescending { it.lastUpdatedEpochMs }
        val hasLoadedRemoteProgress = if (shouldUseTraktProgress()) {
            TraktProgressRepository.uiState.value.hasLoadedRemoteProgress
        } else {
            hasLoaded
        }
        _uiState.value = WatchProgressUiState(
            entries = sortedEntries,
            hasLoadedRemoteProgress = hasLoadedRemoteProgress,
        )
    }

    private fun persist() {
        WatchProgressStorage.savePayload(
            currentProfileId,
            WatchProgressCodec.encodePayload(
                entries = entriesByVideoId.values,
                lastSuccessfulPushEpochMs = lastSuccessfulPushEpochMs,
                deltaCursorEventId = deltaCursorEventId,
                deltaInitialized = deltaInitialized,
            ),
        )
    }

    private fun recordSuccessfulPush(profileId: Int, entries: Collection<WatchProgressEntry>) {
        if (profileId != currentProfileId) return
        val latestPushed = entries
            .asSequence()
            .map { entry -> entry.lastUpdatedEpochMs }
            .maxOrNull()
            ?: return
        if (latestPushed <= lastSuccessfulPushEpochMs) return
        lastSuccessfulPushEpochMs = latestPushed
        persist()
    }

    /**
     * The single selected Continue Watching source, after checking the provider is connected.
     *
     * Mutual exclusion is now structural rather than a chain of `!shouldUseOther()` guards: there is
     * one stored choice, and a disconnected provider falls back to local rather than to whichever
     * other provider happened to be next in the old precedence order.
     */
    private fun activeContinueWatchingSource(): ContinueWatchingSource =
        resolveContinueWatchingSource(
            selected = ContinueWatchingSourceRepository.selectedSource(),
        ) { providerId ->
            when (providerId) {
                TrackingProviderId.MDBLIST -> MdbListSettingsRepository.trackingApiKey() != null
                TrackingProviderId.SIMKL -> SimklAuthRepository.isAuthenticated.value
                TrackingProviderId.TRAKT -> TraktAuthRepository.isAuthenticated.value
                TrackingProviderId.YAMTRACK -> YamtrackSettingsRepository.activeCredentials() != null
            }
        }

    private fun shouldUseMdbListProgress(): Boolean =
        activeContinueWatchingSource() == ContinueWatchingSource.MDBLIST

    private fun shouldUseSimklProgress(): Boolean =
        activeContinueWatchingSource() == ContinueWatchingSource.SIMKL

    private fun shouldUseTraktProgress(): Boolean =
        activeContinueWatchingSource() == ContinueWatchingSource.TRAKT

    private fun shouldUseYamtrackProgress(): Boolean =
        activeContinueWatchingSource() == ContinueWatchingSource.YAMTRACK

    private fun currentEntries(): List<WatchProgressEntry> {
        // A selected remote source shows that source's rows and nothing else.
        //
        // Each branch used to merge in local entries the provider "could not address" — anything
        // without an id in that provider's namespace. The intent was to avoid losing progress for
        // content the remote cannot hold, but the effect was that selecting a source with three
        // rows produced a screen of unrelated local history: 96 local entries, 14 of which no
        // provider could address, drowning the source the user actually picked. Leaking between
        // services is exactly what choosing a single source is meant to prevent.
        when (activeContinueWatchingSource()) {
            ContinueWatchingSource.MDBLIST ->
                return MdbListProgressRepository.uiState.value.entries
                    .sortedByDescending { it.lastUpdatedEpochMs }
                    .withNuvioSyncEntries()

            ContinueWatchingSource.SIMKL -> {
                // Apply the user's day cap and sort most-recently-watched first.
                val cutoffMs = simklContinueWatchingCutoffMs(
                    daysCap = SimklSettingsRepository.simklContinueWatchingDaysCap(),
                    nowEpochMs = WatchProgressClock.nowEpochMs(),
                )
                return SimklProgressRepository.uiState.value.entries
                    .filter { cutoffMs == 0L || it.lastUpdatedEpochMs >= cutoffMs }
                    .sortedByDescending { it.lastUpdatedEpochMs }
                    .withNuvioSyncEntries(cutoffMs)
            }

            ContinueWatchingSource.TRAKT ->
                return TraktProgressRepository.uiState.value.entries.withNuvioSyncEntries()

            ContinueWatchingSource.YAMTRACK ->
                return WatchedRepository.uiState.value.items
                    .asSequence()
                    .filter { it.season != null && it.episode != null }
                    .map { item ->
                        WatchProgressEntry(
                            contentType = "series",
                            parentMetaId = item.id,
                            parentMetaType = "series",
                            videoId = "${item.id}:${item.season}:${item.episode}",
                            title = item.name,
                            poster = item.poster,
                            seasonNumber = item.season,
                            episodeNumber = item.episode,
                            lastPositionMs = 0L,
                            durationMs = 0L,
                            lastUpdatedEpochMs = item.markedAtEpochMs,
                            isCompleted = true,
                            progressPercent = 100f,
                            source = WatchProgressSourceYamtrackHistory,
                        )
                    }
                    .sortedByDescending(WatchProgressEntry::lastUpdatedEpochMs)
                    .toList()
                    .withNuvioSyncEntries()

            ContinueWatchingSource.LOCAL -> Unit
        }

        return entriesByVideoId.values.toList()
    }

    /**
     * Adds the Nuvio Sync rows the selected provider cannot hold — native anime namespaces and
     * addon-specific ids — when the user has asked for it.
     *
     * Governed by the same preference as Up Next seeding, because they are the same question:
     * whether Nuvio Sync contributes alongside the chosen service. Unconditional merging is what
     * made a three-session source render seventeen rows.
     */
    private fun List<WatchProgressEntry>.withNuvioSyncEntries(
        cutoffMs: Long = 0L,
    ): List<WatchProgressEntry> {
        if (!ContinueWatchingPreferencesRepository.uiState.value.seedNextUpFromNuvioSync) return this
        val remoteKeys = mapTo(mutableSetOf()) { it.videoId }
        val extra = entriesByVideoId.values.filter { entry ->
            entry.videoId !in remoteKeys &&
                !isTraktCompatibleId(entry.parentMetaId) &&
                // Appended after the source's own day-cap filter, so without this they ignored the
                // Continue Watching window entirely — turning on Nuvio Sync seeding brought local
                // history of any age back alongside a 30-day remote list.
                (cutoffMs == 0L || entry.lastUpdatedEpochMs >= cutoffMs)
        }
        return if (extra.isEmpty()) this else this + extra
    }

    fun isDroppedShow(contentId: String): Boolean {
        return shouldUseTraktProgress() && TraktProgressRepository.isShowHiddenFromProgress(contentId)
    }

    private fun AddonsUiState.metadataProviderReadiness(): MetadataProviderReadiness {
        val enabled = addons.enabledAddons()
        val providers = enabled
            .mapNotNull { addon -> addon.manifest }
            .filter { manifest -> manifest.hasMetaResource() }
        return MetadataProviderReadiness(
            providers = providers,
            isRefreshing = enabled.any { addon -> addon.isRefreshing },
        )
    }

    private fun AddonManifest.hasMetaResource(): Boolean =
        resources.any { resource -> resource.name == "meta" }

    private fun AddonManifest.supportsMetaRequest(type: String, id: String): Boolean =
        resources.any { resource ->
            resource.name == "meta" &&
                resource.types.contains(type) &&
                (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { prefix -> id.startsWith(prefix) })
        }
}
