package com.nuvio.app.features.librarypvr

import co.touchlab.kermit.Logger
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.metadata.migrateLegacyAnimeContentId
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class MonitoredPayload(val items: List<MonitoredItem> = emptyList())

@Serializable
private data class GrabsPayload(val grabs: List<GrabRecord> = emptyList())

/**
 * Owns library auto-download state: the settings block, the monitored-items list, and the grab
 * history/dedup ledger. Per-profile JSON persistence mirrors [com.nuvio.app.features.locallibrary.LocalLibraryRepository];
 * the scheduler ([LibraryPvrScheduler]) reads/writes through this object so all mutation goes
 * through one lock and one persisted source of truth.
 */
object LibraryPvrRepository {
    private val log = Logger.withTag("LibraryPvrRepo")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private const val MAX_GRAB_HISTORY = 500

    private val _uiState = MutableStateFlow(LibraryPvrUiState())
    val uiState: StateFlow<LibraryPvrUiState> = _uiState.asStateFlow()

    private var profileId: Int = 1
    private var hasLoaded = false

    private var settings: LibraryPvrSettings = LibraryPvrSettings()
    private var monitoredById: Map<String, MonitoredItem> = emptyMap()
    private var grabsById: Map<String, GrabRecord> = emptyMap()

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk(ProfileRepository.activeProfileId)
    }

    fun onProfileChanged() = onProfileChanged(ProfileRepository.activeProfileId)

    fun onProfileChanged(newProfileId: Int) {
        if (newProfileId == profileId && hasLoaded) return
        loadFromDisk(newProfileId)
    }

    private fun loadFromDisk(profileId: Int) = synchronized(lock) {
        this.profileId = profileId
        hasLoaded = true

        settings = LibraryPvrStorage.loadSettings(profileId)
            ?.let { runCatching { json.decodeFromString<LibraryPvrSettings>(it) }.getOrNull() }
            ?: LibraryPvrSettings()
        DownloadsRepository.updateAutomaticBandwidthLimit(settings.bandwidthLimitMbps)
        val storedMonitored = LibraryPvrStorage.loadMonitored(profileId)
            ?.let { runCatching { json.decodeFromString<MonitoredPayload>(it).items }.getOrNull() }
            .orEmpty()
        val migratedMonitored = storedMonitored.map { it.withMigratedContentId() }
        monitoredById = migratedMonitored.associateBy { it.id }
        grabsById = LibraryPvrStorage.loadGrabs(profileId)
            ?.let { runCatching { json.decodeFromString<GrabsPayload>(it).grabs }.getOrNull() }
            .orEmpty()
            .associateBy { it.id }

        if (migratedMonitored != storedMonitored) {
            log.i { "Migrated ${migratedMonitored.count { it !in storedMonitored }} monitored item(s) to franchise content ids" }
            persistMonitored()
        }

        publish()
    }

    /**
     * Rewrites a content id written under the old kitsu-first anime policy.
     *
     * The scheduler matches this against ids the app computes now — `LocalMediaItem.contentId` for
     * the destination folder, `DownloadItem.parentMetaId` for finished grabs — and drives the
     * episode list off `MetaDetailsRepository.fetch(contentType, contentId)`. A stale `kitsu:` id
     * matches neither and resolves to no metadata on an addon set that does not advertise a kitsu
     * prefix, so the monitor silently downloads nothing. Left alone when the anime-list has no
     * franchise id for the entry, which is when the native id is still the right address.
     */
    private fun MonitoredItem.withMigratedContentId(): MonitoredItem =
        migrateLegacyAnimeContentId(contentId, contentType)
            ?.takeIf { it != contentId }
            ?.let { copy(contentId = it) }
            ?: this

    // --- Settings ---

    fun updateSettings(transform: (LibraryPvrSettings) -> LibraryPvrSettings) = synchronized(lock) {
        ensureLoadedLocked()
        settings = transform(settings)
        DownloadsRepository.updateAutomaticBandwidthLimit(settings.bandwidthLimitMbps)
        persistSettings()
        publish()
    }

    fun currentSettings(): LibraryPvrSettings = synchronized(lock) {
        ensureLoadedLocked()
        settings
    }

    // --- Monitored items ---

    /** Adds or replaces a monitored item for [contentId]. Returns the stored item. */
    fun upsertMonitoredItem(item: MonitoredItem): MonitoredItem = synchronized(lock) {
        ensureLoadedLocked()
        // Collapse duplicates on contentId so re-adding a title updates the existing monitor.
        val existing = monitoredById.values.firstOrNull { it.contentId == item.contentId }
        val stored = if (existing != null) item.copy(id = existing.id, addedAtEpochMs = existing.addedAtEpochMs) else item
        monitoredById = monitoredById - (existing?.id ?: "") + (stored.id to stored)
        persistMonitored()
        publish()
        stored
    }

    fun newMonitoredItem(
        contentId: String,
        contentType: String,
        title: String,
        targetFolderId: String,
        mode: MonitorMode,
        monitoredSeasons: Set<Int> = emptySet(),
        tmdbId: Int? = null,
        imdbId: String? = null,
        kitsuId: Int? = null,
        malId: Int? = null,
        isAnime: Boolean = false,
        year: Int? = null,
        poster: String? = null,
        background: String? = null,
    ): MonitoredItem {
        val now = TraktPlatformClock.nowEpochMs()
        val item = MonitoredItem(
            id = "monitor-$now-${Random.nextInt(0, 1_000_000)}",
            contentId = contentId,
            contentType = contentType,
            tmdbId = tmdbId,
            imdbId = imdbId,
            kitsuId = kitsuId,
            malId = malId,
            isAnime = isAnime,
            title = title,
            year = year,
            poster = poster,
            background = background,
            targetFolderId = targetFolderId,
            mode = mode,
            monitoredSeasons = monitoredSeasons.filter { it > 0 }.toSet(),
            addedAtEpochMs = now,
        )
        return upsertMonitoredItem(item)
    }

    fun removeMonitoredItem(id: String) = synchronized(lock) {
        ensureLoadedLocked()
        if (!monitoredById.containsKey(id)) return@synchronized
        monitoredById = monitoredById - id
        persistMonitored()
        publish()
    }

    fun removeMonitoredForContent(contentId: String) = synchronized(lock) {
        ensureLoadedLocked()
        val ids = monitoredById.values.filter { it.contentId == contentId }.map { it.id }
        if (ids.isEmpty()) return@synchronized
        monitoredById = monitoredById - ids.toSet()
        persistMonitored()
        publish()
    }

    fun setPaused(id: String, paused: Boolean) = mutateMonitored(id) { it.copy(paused = paused) }

    fun mutateMonitored(id: String, transform: (MonitoredItem) -> MonitoredItem) = synchronized(lock) {
        ensureLoadedLocked()
        val current = monitoredById[id] ?: return@synchronized
        monitoredById = monitoredById + (id to transform(current))
        persistMonitored()
        publish()
    }

    fun monitoredItems(): List<MonitoredItem> = synchronized(lock) {
        ensureLoadedLocked()
        monitoredById.values.toList()
    }

    // --- Grab records (history + dedup) ---

    /** True when a non-failed grab already exists for this episode/movie coordinate. */
    fun hasActiveGrab(monitoredItemId: String, season: Int?, episode: Int?): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val key = "$monitoredItemId|${season ?: -1}|${episode ?: -1}"
        grabsById.values.any { it.logicalKey == key && it.status != GrabStatus.FAILED && it.status != GrabStatus.SKIPPED }
    }

    fun grabsForItem(monitoredItemId: String): List<GrabRecord> = synchronized(lock) {
        ensureLoadedLocked()
        grabsById.values.filter { it.monitoredItemId == monitoredItemId }
    }

    fun upsertGrab(record: GrabRecord): GrabRecord = synchronized(lock) {
        ensureLoadedLocked()
        // A retry is one logical activity, not another history row. Collapse terminal duplicates
        // left by previous versions before inserting the queued retry record.
        val withoutTerminalDuplicates = if (record.status == GrabStatus.QUEUED) {
            grabsById.filterValues { existing ->
                existing.id == record.id ||
                    existing.logicalKey != record.logicalKey
            }
        } else {
            grabsById
        }
        grabsById = (withoutTerminalDuplicates + (record.id to record)).let(::prunedGrabs)
        persistGrabs()
        publish()
        record
    }

    fun updateGrab(id: String, transform: (GrabRecord) -> GrabRecord) = synchronized(lock) {
        ensureLoadedLocked()
        val current = grabsById[id] ?: return@synchronized
        grabsById = grabsById + (id to transform(current).copy(updatedAtEpochMs = TraktPlatformClock.nowEpochMs()))
        persistGrabs()
        publish()
    }

    /** Updates the grab (if any) whose downloadId matches — used by the completion watcher. */
    fun updateGrabByDownloadId(downloadId: String, transform: (GrabRecord) -> GrabRecord) = synchronized(lock) {
        ensureLoadedLocked()
        val current = grabsById.values.firstOrNull { it.downloadId == downloadId } ?: return@synchronized
        grabsById = grabsById + (current.id to transform(current).copy(updatedAtEpochMs = TraktPlatformClock.nowEpochMs()))
        persistGrabs()
        publish()
    }

    fun newGrabId(): String = "grab-${TraktPlatformClock.nowEpochMs()}-${Random.nextInt(0, 1_000_000)}"

    fun clearGrabHistory() {
        // A persisted DOWNLOADING grab is not necessarily live. DownloadsRepository deliberately
        // restores interrupted transfers as PAUSED after restart, so retaining every in-flight
        // grab here made those orphaned rows impossible to clear and permanently consumed scheduler
        // slots. Preserve only grabs backed by a transfer that is actually downloading now.
        DownloadsRepository.ensureLoaded()
        val activeDownloads = DownloadsRepository.uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
        // Failed automatic transfers are shown in the same activity list but live in
        // DownloadsRepository, not here. Purge them too, otherwise clearing the grab records just
        // unhides them as orphaned "direct download" rows and the list never empties.
        DownloadsRepository.clearFailedAutomaticDownloads()

        synchronized(lock) {
            ensureLoadedLocked()
            val checkingNow = _uiState.value.isChecking
            grabsById = grabsById.filterValues { grab ->
                when (grab.status) {
                    GrabStatus.QUEUED -> checkingNow
                    GrabStatus.DOWNLOADING -> {
                        val monitored = monitoredById[grab.monitoredItemId]
                        monitored != null && activeDownloads.any { download ->
                            download.parentMetaId == monitored.contentId &&
                                download.seasonNumber == grab.season &&
                                download.episodeNumber == grab.episode
                        }
                    }
                    else -> false
                }
            }
            persistGrabs()
            publish()
        }
    }

    // --- Scheduler bookkeeping ---

    fun setChecking(checking: Boolean) = synchronized(lock) {
        if (checking) {
            _uiState.value = _uiState.value.copy(isChecking = true)
        } else {
            val completedAt = TraktPlatformClock.nowEpochMs()
            settings = settings.copy(lastCompletedCheckEpochMs = completedAt)
            persistSettings()
            _uiState.value = _uiState.value.copy(
                settings = settings,
                isChecking = false,
                lastCheckAtEpochMs = completedAt,
            )
        }
    }

    // --- internals ---

    private fun ensureLoadedLocked() {
        if (!hasLoaded) loadFromDisk(ProfileRepository.activeProfileId)
    }

    private fun prunedGrabs(all: Map<String, GrabRecord>): Map<String, GrabRecord> {
        if (all.size <= MAX_GRAB_HISTORY) return all
        // Keep in-flight grabs plus the most recent terminal ones, dropping the oldest.
        val inFlight = all.values.filter { it.status == GrabStatus.QUEUED || it.status == GrabStatus.DOWNLOADING }
        val terminal = all.values
            .filter { it.status != GrabStatus.QUEUED && it.status != GrabStatus.DOWNLOADING }
            .sortedByDescending { it.updatedAtEpochMs }
            .take((MAX_GRAB_HISTORY - inFlight.size).coerceAtLeast(0))
        return (inFlight + terminal).associateBy { it.id }
    }

    private fun persistSettings() =
        LibraryPvrStorage.saveSettings(profileId, json.encodeToString(settings))

    private fun persistMonitored() =
        LibraryPvrStorage.saveMonitored(profileId, json.encodeToString(MonitoredPayload(monitoredById.values.toList())))

    private fun persistGrabs() =
        LibraryPvrStorage.saveGrabs(profileId, json.encodeToString(GrabsPayload(grabsById.values.toList())))

    private fun publish() {
        _uiState.value = _uiState.value.copy(
            settings = settings,
            monitoredItems = monitoredById.values.sortedByDescending { it.addedAtEpochMs },
            grabHistory = grabsById.values.sortedByDescending { it.updatedAtEpochMs },
            isLoaded = hasLoaded,
            lastCheckAtEpochMs = settings.lastCompletedCheckEpochMs,
        )
    }
}
