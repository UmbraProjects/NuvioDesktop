package com.nuvio.app.features.watched

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.effectiveEpisodeNumber
import com.nuvio.app.features.details.effectiveSeasonNumber
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.WatchProgressSource
import com.nuvio.app.features.trakt.shouldUseTraktProgress
import com.nuvio.app.features.watching.sync.SupabaseWatchedSyncAdapter
import com.nuvio.app.features.watching.sync.TraktWatchedSyncAdapter
import com.nuvio.app.features.watching.sync.WatchedDeltaEvent
import com.nuvio.app.features.tracking.ContinueWatchingSource
import com.nuvio.app.features.tracking.ContinueWatchingSourceRepository
import com.nuvio.app.features.tracking.LibrarySourceRepository
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.tracking.resolveContinueWatchingSource
import com.nuvio.app.features.tracking.resolveLibrarySource
import com.nuvio.app.features.tracking.trackingProvider
import com.nuvio.app.features.watching.sync.WatchedSyncAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watched_episode_provider_success
import org.jetbrains.compose.resources.getString

@Serializable
private data class StoredWatchedPayload(
    val items: List<WatchedItem> = emptyList(),
    val lastSuccessfulPushEpochMs: Long = 0L,
    val deltaCursorEventId: Long = 0L,
    val deltaInitialized: Boolean = false,
)

internal enum class WatchedRemoteSync {
    Manual,
    Skip,
}

internal fun shouldWriteSelectedLibraryHistory(sync: WatchedRemoteSync): Boolean =
    sync == WatchedRemoteSync.Manual

object WatchedRepository {
    private const val watchedItemsPageSize = 900
    private const val watchedItemsDeltaPageSize = 900
    private const val providerHistoryPullMinIntervalMs = 60_000L
    private const val watchedDeltaOperationUpsert = "upsert"
    private const val watchedDeltaOperationDelete = "delete"

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("WatchedRepository")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(WatchedUiState())
    val uiState: StateFlow<WatchedUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var currentProfileId: Int = 1
    private var itemsByKey: MutableMap<String, WatchedItem> = mutableMapOf()
    private var lastSuccessfulPushEpochMs: Long = 0L
    private var deltaCursorEventId: Long = 0L
    private var deltaInitialized: Boolean = false
    private val providerHistoryPullLock = Mutex()
    private var lastProviderHistoryPullAtMs: Long = 0L
    internal var syncAdapter: WatchedSyncAdapter = SupabaseWatchedSyncAdapter

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk(ProfileRepository.activeProfileId)
    }

    fun onProfileChanged(profileId: Int) {
        if (profileId == currentProfileId && hasLoaded) return
        loadFromDisk(profileId)
    }

    fun clearLocalState() {
        hasLoaded = false
        currentProfileId = 1
        itemsByKey.clear()
        lastSuccessfulPushEpochMs = 0L
        deltaCursorEventId = 0L
        deltaInitialized = false
        _uiState.value = WatchedUiState()
    }

    private fun loadFromDisk(profileId: Int) {
        currentProfileId = profileId
        hasLoaded = true
        itemsByKey.clear()

        val payload = WatchedStorage.loadPayload(profileId).orEmpty().trim()
        if (payload.isNotEmpty()) {
            val storedPayload = runCatching {
                json.decodeFromString<StoredWatchedPayload>(payload)
            }.getOrDefault(StoredWatchedPayload())
            lastSuccessfulPushEpochMs = storedPayload.lastSuccessfulPushEpochMs
            deltaCursorEventId = storedPayload.deltaCursorEventId
            deltaInitialized = storedPayload.deltaInitialized
            itemsByKey = storedPayload.items
                .map(WatchedItem::normalizedMarkedAt)
                .associateBy { watchedItemKey(it.type, it.id, it.season, it.episode) }
                .toMutableMap()
        } else {
            lastSuccessfulPushEpochMs = 0L
            deltaCursorEventId = 0L
            deltaInitialized = false
        }

        publish()
    }

    suspend fun pullFromServer(profileId: Int) {
        TraktAuthRepository.ensureLoaded(profileId)
        TraktSettingsRepository.ensureLoaded()
        currentProfileId = profileId
        val pullStartedEpochMs = WatchedClock.nowEpochMs()
        val localBeforePull = itemsByKey.values
            .map(WatchedItem::normalizedMarkedAt)
            .toList()
        val lastPushEpochMs = lastSuccessfulPushEpochMs
        runCatching {
            val remoteAdapter = activeRemoteWatchedAdapter()
            if (remoteAdapter != null) {
                pullFullFromAdapter(
                    adapter = remoteAdapter,
                    profileId = profileId,
                    localBeforePull = localBeforePull,
                    lastPushEpochMs = lastPushEpochMs,
                    pullStartedEpochMs = pullStartedEpochMs,
                    resetDeltaState = true,
                )
            } else {
                pullSupabaseDeltaFromServer(
                    profileId = profileId,
                    localBeforePull = localBeforePull,
                    lastPushEpochMs = lastPushEpochMs,
                    pullStartedEpochMs = pullStartedEpochMs,
                )
            }
        }.onFailure { e ->
            log.e(e) { "Failed to pull watched items from server" }
        }

        // Provider history is merged additively after the account's primary watched store. A
        // missing row from SIMKL, MDBList or Floppy must never erase a tick written locally or by
        // another service; those APIs are independent histories, not mirrors of one another.
        pullConnectedProviderHistoryAdditively(profileId)
    }

    /**
     * Imports the Continue Watching provider's history without touching the Nuvio Sync store.
     *
     * SIMKL, MDBList and Floppy are the user's own connections and have nothing to do with whether
     * they signed into a Nuvio account. This was previously reachable only through [pullFromServer],
     * whose only caller returns early for anonymous and signed-out users, so "Continue without
     * account" meant provider history was never imported at all.
     *
     * [force] skips the request-budget interval for an explicit source change, where the whole point
     * of the call is that the answer has just changed.
     */
    suspend fun pullConnectedProviderHistory(profileId: Int, force: Boolean = false) {
        ensureLoaded()
        if (profileId != currentProfileId) return
        providerHistoryPullLock.withLock {
            // A fresh sign-in reaches both this and the account sync within a second of each other,
            // and every provider read costs a request against a shared daily budget.
            val now = WatchedClock.nowEpochMs()
            if (!force && now - lastProviderHistoryPullAtMs < providerHistoryPullMinIntervalMs) return
            lastProviderHistoryPullAtMs = now
        }
        pullConnectedProviderHistoryAdditively(profileId)
    }

    /**
     * Imports history from the provider that owns Continue Watching, and withdraws what an earlier
     * import brought in from any other one.
     *
     * This used to run for every connected provider. Watched history is not a private store: Up Next
     * seeds off it, so a connected SIMKL account poured its entire history into Continue Watching
     * while the selected source was Nuvio Sync — the single-source guarantee broken through the back
     * door, by the one provider read that never asked which source was selected. Trakt has always
     * followed the selection (see [activeRemoteWatchedAdapter]); the additive providers now do too.
     */
    private suspend fun pullConnectedProviderHistoryAdditively(profileId: Int) {
        val importProviderId = activeWatchedHistoryImportProviderId()
        var changed = withdrawForeignImportedHistory(importProviderId)
        val provider = TrackingProviderRegistry.connectedWatchedProviders()
            .firstOrNull { candidate -> candidate.providerId == importProviderId }
        if (provider != null) {
            val remoteItems = try {
                provider.pull(profileId = profileId, pageSize = watchedItemsPageSize)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w(error) { "Failed to pull watched history from ${provider.providerId.storageId}" }
                null
            }
            if (profileId != currentProfileId) return
            if (remoteItems != null) {
                val imported = remoteItems.map { item ->
                    item.copy(importedFrom = provider.providerId.storageId)
                }
                val merged = mergeWatchedItemsAdditively(itemsByKey.values, imported)
                if (merged.size != itemsByKey.size || merged != itemsByKey) {
                    itemsByKey = merged.toMutableMap()
                    changed = true
                }
            }
        }
        if (changed && profileId == currentProfileId) {
            hasLoaded = true
            publish()
            persist()
        }
    }

    /**
     * Clears watched rows that came from a connected provider which does not own Continue Watching,
     * and reports how many were removed.
     *
     * The automatic withdrawal above can only act on rows carrying [WatchedItem.importedFrom], which
     * builds before this one never wrote — their imports are indistinguishable from the user's own
     * ticks once stored. This asks each such provider what is in its history and removes those rows
     * on the user's say-so, which is why it is an explicit action behind a confirmation rather than
     * something that runs on its own: an episode the user watched here *and* has on that service
     * goes too.
     *
     * Local only. The provider's own history is untouched — the user is clearing what was imported,
     * not what they watched elsewhere.
     */
    suspend fun withdrawImportedProviderHistory(profileId: Int): Int {
        ensureLoaded()
        if (profileId != currentProfileId) return 0
        val importProviderId = activeWatchedHistoryImportProviderId()
        val foreignKeys = mutableSetOf<String>()
        TrackingProviderRegistry.connectedWatchedProviders()
            .filter { provider -> provider.providerId != importProviderId }
            .forEach { provider ->
                val remoteItems = try {
                    provider.pull(profileId = profileId, pageSize = watchedItemsPageSize)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    log.w(error) { "Failed to read ${provider.providerId.storageId} history to withdraw it" }
                    return@forEach
                }
                remoteItems.mapTo(foreignKeys) { item ->
                    watchedItemKey(item.type, item.id, item.season, item.episode)
                }
            }
        if (profileId != currentProfileId) return 0

        val retained = watchedItemsWithoutForeignImports(itemsByKey.values, importProviderId)
            .filterNot { item ->
                watchedItemKey(item.type, item.id, item.season, item.episode) in foreignKeys
            }
        val removed = itemsByKey.size - retained.size
        if (removed <= 0) return 0
        itemsByKey = retained
            .associateBy { item -> watchedItemKey(item.type, item.id, item.season, item.episode) }
            .toMutableMap()
        publish()
        persist()
        return removed
    }

    /** Drops rows imported from a provider that no longer owns Continue Watching. */
    private fun withdrawForeignImportedHistory(importProviderId: TrackingProviderId?): Boolean {
        val retained = watchedItemsWithoutForeignImports(
            items = itemsByKey.values,
            importProviderId = importProviderId,
        )
        if (retained.size == itemsByKey.size) return false
        itemsByKey = retained
            .associateBy { item -> watchedItemKey(item.type, item.id, item.season, item.episode) }
            .toMutableMap()
        return true
    }

    private fun activeWatchedHistoryImportProviderId(): TrackingProviderId? =
        watchedHistoryImportProviderId(
            selected = ContinueWatchingSourceRepository.selectedSource(),
            isProviderAuthenticated = TrackingProviderRegistry::isAuthenticated,
        )

    private suspend fun pullFullFromAdapter(
        adapter: WatchedSyncAdapter,
        profileId: Int,
        localBeforePull: List<WatchedItem>,
        lastPushEpochMs: Long,
        pullStartedEpochMs: Long,
        resetDeltaState: Boolean,
    ) {
        val serverItems = adapter.pull(
            profileId = profileId,
            pageSize = watchedItemsPageSize,
        )

        itemsByKey = mergeWatchedItemsPreservingUnsynced(
            serverItems = serverItems,
            localItems = localBeforePull,
            lastSuccessfulPushEpochMs = lastPushEpochMs,
            pullStartedEpochMs = pullStartedEpochMs,
        ).toMutableMap()
        if (resetDeltaState) {
            deltaCursorEventId = 0L
            deltaInitialized = false
        }
        hasLoaded = true
        publish()
        persist()
    }

    private suspend fun pullSupabaseDeltaFromServer(
        profileId: Int,
        localBeforePull: List<WatchedItem>,
        lastPushEpochMs: Long,
        pullStartedEpochMs: Long,
    ) {
        if (!deltaInitialized) {
            // Mirror the watch-progress repository: a missing/failing delta cursor must not leave
            // the local state unsynced forever — fall back to a full snapshot pull instead.
            val cursorBeforeSnapshot = try {
                syncAdapter.getDeltaCursor(profileId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Watched delta cursor unavailable, falling back to full pull: ${error.message}" }
                null
            }
            if (cursorBeforeSnapshot == null) {
                pullFullFromAdapter(
                    adapter = syncAdapter,
                    profileId = profileId,
                    localBeforePull = localBeforePull,
                    lastPushEpochMs = lastPushEpochMs,
                    pullStartedEpochMs = pullStartedEpochMs,
                    resetDeltaState = true,
                )
                return
            }
            pullFullFromAdapter(
                adapter = syncAdapter,
                profileId = profileId,
                localBeforePull = localBeforePull,
                lastPushEpochMs = lastPushEpochMs,
                pullStartedEpochMs = pullStartedEpochMs,
                resetDeltaState = false,
            )
            deltaCursorEventId = cursorBeforeSnapshot
            deltaInitialized = true
            persist()
            return
        }

        var cursor = deltaCursorEventId
        var changed = false

        while (true) {
            val events = try {
                syncAdapter.pullDelta(
                    profileId = profileId,
                    sinceEventId = cursor,
                    limit = watchedItemsDeltaPageSize,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Watched delta pull unavailable, falling back to full pull: ${error.message}" }
                pullFullFromAdapter(
                    adapter = syncAdapter,
                    profileId = profileId,
                    localBeforePull = localBeforePull,
                    lastPushEpochMs = lastPushEpochMs,
                    pullStartedEpochMs = pullStartedEpochMs,
                    resetDeltaState = true,
                )
                return
            }
            if (events.isEmpty()) break

            applyWatchedDeltaEvents(
                events = events,
                lastPushEpochMs = lastPushEpochMs,
                pullStartedEpochMs = pullStartedEpochMs,
            )
            cursor = maxOf(cursor, events.maxOf { it.eventId })
            deltaCursorEventId = cursor
            deltaInitialized = true
            changed = true

            if (events.size < watchedItemsDeltaPageSize) break
        }

        hasLoaded = true
        if (changed) {
            publish()
            persist()
        }
    }

    private fun applyWatchedDeltaEvents(
        events: Collection<WatchedDeltaEvent>,
        lastPushEpochMs: Long,
        pullStartedEpochMs: Long,
    ) {
        events.forEach { event ->
            val key = watchedItemKey(event.contentType, event.contentId, event.season, event.episode)
            when (event.operation.lowercase()) {
                watchedDeltaOperationUpsert -> {
                    itemsByKey[key] = WatchedItem(
                        id = event.contentId,
                        type = event.contentType,
                        name = event.title,
                        season = event.season,
                        episode = event.episode,
                        markedAtEpochMs = normalizeWatchedMarkedAtEpochMs(event.watchedAt),
                    )
                }
                watchedDeltaOperationDelete -> {
                    val localItem = itemsByKey[key]
                    if (localItem != null && shouldPreserveLocalWatchedItem(localItem, lastPushEpochMs, pullStartedEpochMs)) {
                        return@forEach
                    }
                    itemsByKey.remove(key)
                }
            }
        }
    }

    fun toggleWatched(item: WatchedItem) {
        ensureLoaded()
        val key = watchedItemKey(item.type, item.id, item.season, item.episode)
        if (itemsByKey.containsKey(key)) {
            unmarkWatched(item)
        } else {
            markWatched(item)
        }
    }

    fun markWatched(item: WatchedItem) {
        markWatched(listOf(item))
    }

    fun markWatched(items: Collection<WatchedItem>) {
        markWatched(items = items, remoteSync = WatchedRemoteSync.Manual)
    }

    internal fun markWatchedFromPlaybackCompletion(item: WatchedItem, syncRemote: Boolean = true) {
        markWatched(items = listOf(item), remoteSync = WatchedRemoteSync.Skip, syncRemote = syncRemote)
    }

    private fun markWatched(
        items: Collection<WatchedItem>,
        remoteSync: WatchedRemoteSync,
        syncRemote: Boolean = true,
    ) {
        ensureLoaded()
        if (items.isEmpty()) return
        val markedAt = WatchedClock.nowEpochMs()
        val timestampedItems = items.map { watchedItem ->
            watchedItem.copy(markedAtEpochMs = markedAt)
        }
        timestampedItems.forEach { watchedItem ->
            val key = watchedItemKey(watchedItem.type, watchedItem.id, watchedItem.season, watchedItem.episode)
            itemsByKey[key] = watchedItem
        }
        publish()
        persist()
        if (syncRemote) {
            pushMarksToServer(timestampedItems, remoteSync)
        }
    }

    fun unmarkWatched(item: WatchedItem) {
        unmarkWatched(listOf(item))
    }

    fun unmarkWatched(
        id: String,
        type: String,
        season: Int? = null,
        episode: Int? = null,
    ) {
        unmarkWatched(
            listOf(
                WatchedItem(
                    id = id,
                    type = type,
                    name = "",
                    season = season,
                    episode = episode,
                    markedAtEpochMs = 0L,
                ),
            ),
        )
    }

    fun unmarkWatched(items: Collection<WatchedItem>) {
        unmarkWatched(items = items, syncRemote = true)
    }

    private fun unmarkWatched(
        items: Collection<WatchedItem>,
        syncRemote: Boolean,
    ) {
        ensureLoaded()
        if (items.isEmpty()) return
        val removedByKey = mutableMapOf<String, WatchedItem>()
        items.forEach { watchedItem ->
            val key = watchedItemKey(watchedItem.type, watchedItem.id, watchedItem.season, watchedItem.episode)
            itemsByKey.remove(key)?.let { removed -> removedByKey[key] = removed }
        }
        if (removedByKey.isNotEmpty()) {
            publish()
            persist()
        }
        if (!syncRemote) return

        pushDeleteToServer(watchedUnmarkDeleteTargets(items, removedByKey))
    }

    fun isWatched(
        id: String,
        type: String,
        season: Int? = null,
        episode: Int? = null,
    ): Boolean {
        ensureLoaded()
        return itemsByKey.containsKey(watchedItemKey(type, id, season, episode))
    }

    fun reconcileSeriesWatchedState(
        meta: MetaDetails,
        todayIsoDate: String,
        isEpisodeCompleted: (com.nuvio.app.features.details.MetaVideo) -> Boolean = { false },
    ) {
        if (!meta.type.isSeriesLikeWatchedType()) return

        ensureLoaded()
        val shouldMarkSeriesWatched = meta.hasWatchedAllMainSeasonEpisodes(todayIsoDate) { episode ->
            isWatched(
                id = meta.id,
                type = meta.type,
                season = episode.effectiveSeasonNumber(),
                episode = episode.effectiveEpisodeNumber(),
            ) || isEpisodeCompleted(episode)
        }
        val seriesWatchedItem = meta.toSeriesWatchedItem()
        val hasSeriesWatchedMarker = isWatched(id = meta.id, type = meta.type)
        if (shouldMarkSeriesWatched) {
            if (!hasSeriesWatchedMarker) {
                markWatched(
                    items = listOf(seriesWatchedItem),
                    remoteSync = WatchedRemoteSync.Skip,
                    syncRemote = false,
                )
            }
        } else if (hasSeriesWatchedMarker) {
            // The parent marker is a local aggregate used by poster UI, not a provider history
            // entry. In Nuvio Sync a delete without episode coordinates means "delete this whole
            // title", and external providers similarly interpret it as a whole-show mutation.
            unmarkWatched(items = listOf(seriesWatchedItem), syncRemote = false)
            rewriteNuvioSeriesHistory(seriesWatchedItem)
        }
    }

    private fun pushMarksToServer(
        items: Collection<WatchedItem>,
        remoteSync: WatchedRemoteSync,
    ) {
        syncScope.launch {
            runCatching {
                if (items.isEmpty()) return@runCatching
                val profileId = ProfileRepository.activeProfileId
                val pushed = pushToActiveTargets(
                    profileId = profileId,
                    items = items,
                    remoteSync = remoteSync,
                )
                if (pushed) {
                    recordSuccessfulPush(profileId = profileId, items = items)
                }
            }.onFailure { e ->
                log.e(e) { "Failed to push watched items" }
            }
        }
    }

    private fun pushDeleteToServer(items: Collection<WatchedItem>) {
        syncScope.launch {
            runCatching {
                if (items.isEmpty()) return@runCatching
                val profileId = ProfileRepository.activeProfileId
                deleteFromActiveTargets(profileId = profileId, items = items)
            }.onFailure { e ->
                log.e(e) { "Failed to push watched item delete" }
            }
        }
    }

    private fun publish() {
        val items = itemsByKey.values
            .map(WatchedItem::normalizedMarkedAt)
            .sortedByDescending { it.markedAtEpochMs }
        val watchedKeys = items.mapTo(linkedSetOf()) {
            watchedItemKey(it.type, it.id, it.season, it.episode)
        }
        // History keyed on a native anime id also answers under the franchise ids the same entry
        // is known by, so switching content ids to franchise-first does not orphan it.
        watchedKeys += animeAlternateWatchedKeys(items)
        _uiState.value = WatchedUiState(
            items = items,
            watchedKeys = watchedKeys,
            isLoaded = true,
        )
    }

    private fun persist() {
        WatchedStorage.savePayload(
            currentProfileId,
            json.encodeToString(
                StoredWatchedPayload(
                    items = itemsByKey.values
                        .map(WatchedItem::normalizedMarkedAt)
                        .sortedByDescending { it.markedAtEpochMs },
                    lastSuccessfulPushEpochMs = lastSuccessfulPushEpochMs,
                    deltaCursorEventId = deltaCursorEventId,
                    deltaInitialized = deltaInitialized,
                ),
            ),
        )
    }

    private fun recordSuccessfulPush(profileId: Int, items: Collection<WatchedItem>) {
        if (profileId != currentProfileId) return
        val latestPushed = items
            .asSequence()
            .map { item -> normalizeWatchedMarkedAtEpochMs(item.markedAtEpochMs) }
            .maxOrNull()
            ?: return
        if (latestPushed <= lastSuccessfulPushEpochMs) return
        lastSuccessfulPushEpochMs = latestPushed
        persist()
    }

    private fun shouldUseTraktWatchedSync(): Boolean =
        activeRemoteWatchedAdapter() === TraktWatchedSyncAdapter

    /**
     * The watched-history adapter for the selected Continue Watching source, or null for Nuvio Sync.
     *
     * Previously this asked "is Trakt the progress source?" while reading a setting that nothing
     * writes any more, so it was stuck on whatever that value happened to be. It now follows the
     * single Continue Watching selection.
     *
     * **Only Trakt is eligible, deliberately.** `pullFullFromAdapter` treats the adapter as the
     * authority and reconciles the local store against it, which is right for Trakt — a full
     * history store this app also writes to. It is not right for the read-only providers: MDBList's
     * history contains only what this app has scrobbled since it was connected, so promoting it to
     * authority would reconcile away watched ticks it has never seen. Feeding those providers into
     * the seed pipeline needs an additive merge path, which does not exist yet.
     */
    private fun activeRemoteWatchedAdapter(): WatchedSyncAdapter? {
        val providerId = ContinueWatchingSourceRepository.selectedSource().providerId ?: return null
        if (providerId != TrackingProviderId.TRAKT) return null
        if (!TrackingProviderRegistry.isAuthenticated(providerId)) return null
        return TraktWatchedSyncAdapter
    }

    private suspend fun pushToActiveTargets(
        profileId: Int,
        items: Collection<WatchedItem>,
        remoteSync: WatchedRemoteSync,
    ): Boolean {
        val writer = selectedLibraryHistoryWriter()
        if (writer == null) {
            withSyncRetry("Watched items push") {
                syncAdapter.push(profileId = profileId, items = items)
            }
            return true
        }

        val remoteItems = items.withoutDerivedSeriesMarkers()
        if (remoteItems.isEmpty()) return false
        // Completed playback is already delivered to external providers through their scrobbler.
        // Only an explicit in-app action should add a second, manual history entry.
        if (!shouldWriteSelectedLibraryHistory(remoteSync)) return false
        val result = writer.addToHistory(
            profileId = profileId,
            items = remoteItems.map { item ->
                TrackingHistoryItem(
                    media = item.toTrackingMediaReference(),
                    watchedAtEpochMs = item.markedAtEpochMs,
                )
            },
        )
        result.warnIfIncomplete(writer.providerId, "mark watched")
        if (result.isComplete && items.size == 1 && items.first().episode != null) {
            val providerName = TrackingProviderRegistry.authProvider(writer.providerId)
                ?.descriptor
                ?.displayName
                ?.takeIf(String::isNotBlank)
                ?: writer.providerId.storageId
            NuvioToastController.show(
                getString(Res.string.watched_episode_provider_success, providerName),
            )
        }
        return true
    }

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

    private suspend fun deleteFromActiveTargets(
        profileId: Int,
        items: Collection<WatchedItem>,
    ) {
        val writer = selectedLibraryHistoryWriter()
        if (writer == null) {
            syncAdapter.delete(profileId = profileId, items = items)
            return
        }
        writer.removeFromHistory(
            profileId = profileId,
            // A parent marker can only arrive here from an explicit whole-show unmark. Derived
            // marker changes are local-only, so retaining it here deliberately clears the entire
            // remote show (including any unreleased rows written by older builds).
            items = items.map(WatchedItem::toTrackingMediaReference),
        ).warnIfIncomplete(writer.providerId, "unmark watched")
    }

    /**
     * Reports items the provider could not address — no id it accepts, or coordinates it cannot
     * map. These are silent successes from the caller's point of view: the local store has already
     * changed, and the next additive pull quietly puts the old state back. Until there is a way to
     * surface this in the UI, at least make it visible in the log rather than dropping the result.
     */
    private fun TrackingMutationResult.warnIfIncomplete(
        providerId: TrackingProviderId,
        action: String,
    ) {
        if (isComplete) return
        log.w {
            "${providerId.storageId} could not $action $notFoundCount of $attemptedCount item(s); " +
                "local state and ${providerId.storageId} are now out of step for those"
        }
    }

    private fun selectedLibraryHistoryWriter() = resolveLibrarySource(
        selected = LibrarySourceRepository.selectedSource(),
        isProviderAuthenticated = TrackingProviderRegistry::isAuthenticated,
    ).trackingProvider?.let(TrackingProviderRegistry::historyWriter)

    /**
     * Nuvio Sync represents a null season/episode delete as "remove the whole title". Replacing
     * the title snapshot is therefore the only safe way to clear its derived series marker after
     * one episode is unmarked: delete the title, then immediately restore every episode that is
     * still watched locally. External providers never receive the derived marker in the first
     * place, so they need no rewrite.
     */
    private fun rewriteNuvioSeriesHistory(seriesItem: WatchedItem) {
        if (selectedLibraryHistoryWriter() != null) return
        val remainingEpisodes = itemsByKey.values.filter { item ->
            item.isEpisode && item.contentIdentity() == seriesItem.contentIdentity()
        }
        val profileId = ProfileRepository.activeProfileId
        syncScope.launch {
            runCatching {
                syncAdapter.delete(profileId = profileId, items = listOf(seriesItem))
                if (remainingEpisodes.isNotEmpty()) {
                    withSyncRetry("Watched series rewrite") {
                        syncAdapter.push(profileId = profileId, items = remainingEpisodes)
                    }
                }
            }.onFailure { error ->
                log.e(error) { "Failed to rewrite Nuvio watched series after episode unmark" }
            }
        }
    }
}

private fun WatchedItem.toTrackingMediaReference() = buildTrackingMediaReference(
    contentType = type,
    parentMetaId = id,
    title = name,
    releaseInfo = releaseInfo,
    seasonNumber = season,
    episodeNumber = episode,
)

/**
 * A series-level marker sent in the same action as concrete episodes is only Nuvio's local
 * aggregate for poster state. Sending it in a history-add request widens the operation to the
 * entire show, which includes unreleased episodes on MDBList. Standalone movies and genuine
 * standalone title actions are retained.
 */
internal fun Collection<WatchedItem>.withoutDerivedSeriesMarkers(): List<WatchedItem> {
    val episodeParents = asSequence()
        .filter(WatchedItem::isEpisode)
        .mapTo(linkedSetOf()) { item -> item.contentIdentity() }
    if (episodeParents.isEmpty()) return toList()
    return filterNot { item -> !item.isEpisode && item.contentIdentity() in episodeParents }
}

private fun WatchedItem.contentIdentity(): String =
    "${type.trim().lowercase()}:${id.trim()}"

internal fun mergeWatchedItemsPreservingUnsynced(
    serverItems: Collection<WatchedItem>,
    localItems: Collection<WatchedItem>,
    lastSuccessfulPushEpochMs: Long,
    pullStartedEpochMs: Long,
): Map<String, WatchedItem> {
    val merged = serverItems
        .map(WatchedItem::normalizedMarkedAt)
        .associateBy { watchedItemKey(it.type, it.id, it.season, it.episode) }
        .toMutableMap()

    localItems
        .map(WatchedItem::normalizedMarkedAt)
        .forEach { localItem ->
            val key = watchedItemKey(localItem.type, localItem.id, localItem.season, localItem.episode)
            if (key in merged) return@forEach
            if (shouldPreserveLocalWatchedItem(localItem, lastSuccessfulPushEpochMs, pullStartedEpochMs)) {
                merged[key] = localItem
            }
        }

    return merged
}

/**
 * What an unmark should remove upstream: everything the user asked to unmark, deduplicated.
 *
 * Deliberately not "whatever was removed from the local store". A tick can be rendered entirely
 * from a provider's own projection — SIMKL's watching-list marker shows an episode as watched with
 * no local row behind it — and gating the delete on a local removal made "unmark" a silent no-op
 * for exactly those episodes, locally *and* upstream. The stored copy wins where there is one,
 * because it carries the title and release info the id-only overload leaves blank.
 */
internal fun watchedUnmarkDeleteTargets(
    requestedItems: Collection<WatchedItem>,
    removedByKey: Map<String, WatchedItem>,
): List<WatchedItem> = requestedItems
    .associateBy { item -> watchedItemKey(item.type, item.id, item.season, item.episode) }
    .map { (key, requested) -> removedByKey[key] ?: requested }

internal fun mergeWatchedItemsAdditively(
    localItems: Collection<WatchedItem>,
    remoteItems: Collection<WatchedItem>,
): Map<String, WatchedItem> {
    val merged = localItems
        .map(WatchedItem::normalizedMarkedAt)
        .associateBy { watchedItemKey(it.type, it.id, it.season, it.episode) }
        .toMutableMap()
    remoteItems.map(WatchedItem::normalizedMarkedAt).forEach { remote ->
        val key = watchedItemKey(remote.type, remote.id, remote.season, remote.episode)
        val local = merged[key]
        if (local == null) {
            merged[key] = remote
        } else if (remote.markedAtEpochMs > local.markedAtEpochMs) {
            // Refreshing a row the store already had must not hand ownership of it to the provider.
            // An episode the user watched here and the provider also knows about is still their own
            // tick, and tagging it as imported would delete it the next time the source changes.
            merged[key] = remote.copy(importedFrom = local.importedFrom)
        }
    }
    return merged
}

/**
 * The provider whose watched history may be imported into the local store, or null for none.
 *
 * One rule for every provider: whoever owns Continue Watching, and only while connected. Watched
 * history feeds Up Next seeding, so importing from an unselected provider puts that service's rows
 * into Continue Watching — which is exactly what choosing a single source is meant to prevent.
 */
internal fun watchedHistoryImportProviderId(
    selected: ContinueWatchingSource,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean,
): TrackingProviderId? = resolveContinueWatchingSource(
    selected = selected,
    isProviderAuthenticated = isProviderAuthenticated,
).providerId

/**
 * The store with every previously imported row from providers other than [importProviderId] removed.
 *
 * Rows the user marked themselves carry no provenance and are always kept, so switching sources
 * withdraws the import without touching local history.
 */
internal fun watchedItemsWithoutForeignImports(
    items: Collection<WatchedItem>,
    importProviderId: TrackingProviderId?,
): List<WatchedItem> = items.filter { item ->
    val importedFrom = item.importedFrom ?: return@filter true
    importedFrom.equals(importProviderId?.storageId, ignoreCase = true)
}

internal fun shouldPreserveLocalWatchedItem(
    localItem: WatchedItem,
    lastSuccessfulPushEpochMs: Long,
    pullStartedEpochMs: Long,
): Boolean {
    val markedAt = localItem.markedAtEpochMs
    val wasMarkedAfterLastPush = lastSuccessfulPushEpochMs > 0L && markedAt > lastSuccessfulPushEpochMs
    val wasMarkedDuringPull = pullStartedEpochMs > 0L && markedAt >= pullStartedEpochMs
    return wasMarkedAfterLastPush || wasMarkedDuringPull
}

internal fun shouldUseTraktWatchedSync(
    isAuthenticated: Boolean,
    source: WatchProgressSource,
): Boolean = shouldUseTraktProgress(
    isAuthenticated = isAuthenticated,
    source = source,
)

private fun String.isSeriesLikeWatchedType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow")
