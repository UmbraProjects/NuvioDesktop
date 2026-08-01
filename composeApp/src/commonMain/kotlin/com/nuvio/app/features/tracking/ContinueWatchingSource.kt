package com.nuvio.app.features.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where Continue Watching gets its rows from.
 *
 * Exactly one source at a time, by construction. This replaces three independent booleans living in
 * three provider settings stores — Trakt's `watchProgressSource`, SIMKL's `asCwSource` and
 * MDBList's `asContinueWatchingSource` — which could all be on at once and were only kept
 * mutually exclusive by a hand-maintained priority chain in `WatchProgressRepository`. Adding a
 * fourth provider to that arrangement would have meant another boolean and another chain entry.
 *
 * Floppy exposes completed history but not a resumable playback feed. Its source therefore
 * contributes completed episode seeds for Next Up, never partially watched resume cards.
 */
enum class ContinueWatchingSource(val storageId: String) {
    /**
     * Nuvio's own progress store — local on disk, and synced through the Nuvio account to
     * mobile, TV and web when signed in. Not device-local.
     */
    LOCAL("local"),
    TRAKT("trakt"),
    SIMKL("simkl"),
    MDBLIST("mdblist"),
    YAMTRACK("yamtrack");

    val providerId: TrackingProviderId?
        get() = when (this) {
            LOCAL -> null
            TRAKT -> TrackingProviderId.TRAKT
            SIMKL -> TrackingProviderId.SIMKL
            MDBLIST -> TrackingProviderId.MDBLIST
            YAMTRACK -> TrackingProviderId.YAMTRACK
        }

    companion object {
        fun fromStorage(value: String?): ContinueWatchingSource? =
            entries.firstOrNull { it.storageId.equals(value?.trim(), ignoreCase = true) }
    }
}

internal expect object ContinueWatchingSourceStorage {
    fun loadSelection(): String?
    fun saveSelection(value: String)
    fun loadLibrarySelection(): String?
    fun saveLibrarySelection(value: String)
    fun loadCalendarSelection(): String?
    fun saveCalendarSelection(value: String)
    fun loadRatingPromptEnabled(): String?
    fun saveRatingPromptEnabled(value: String)
}

/**
 * Resolves the stored selection against what is actually connected.
 *
 * Kept pure so the migration and the fallback are testable without storage or network.
 */
internal fun resolveContinueWatchingSource(
    selected: ContinueWatchingSource,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean,
): ContinueWatchingSource {
    val providerId = selected.providerId ?: return ContinueWatchingSource.LOCAL
    return if (isProviderAuthenticated(providerId)) selected else ContinueWatchingSource.LOCAL
}

/**
 * One-time migration from the three legacy per-provider flags.
 *
 * Uses the same precedence the old chain in `WatchProgressRepository` applied, so whatever a user
 * was actually seeing before the upgrade is what they keep.
 */
internal fun migratedContinueWatchingSource(
    mdbListWasCwSource: Boolean,
    simklWasCwSource: Boolean,
    traktWasCwSource: Boolean,
): ContinueWatchingSource = when {
    mdbListWasCwSource -> ContinueWatchingSource.MDBLIST
    simklWasCwSource -> ContinueWatchingSource.SIMKL
    traktWasCwSource -> ContinueWatchingSource.TRAKT
    else -> ContinueWatchingSource.LOCAL
}

object ContinueWatchingSourceRepository {
    private val _uiState = MutableStateFlow(ContinueWatchingSource.LOCAL)
    val uiState: StateFlow<ContinueWatchingSource> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var selected = ContinueWatchingSource.LOCAL

    /** Supplies the legacy flags for migration; set once at startup to avoid a package cycle. */
    internal var legacyMigrationProbe: (() -> ContinueWatchingSource)? = null

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun selectedSource(): ContinueWatchingSource {
        ensureLoaded()
        return selected
    }

    fun setSource(source: ContinueWatchingSource) {
        ensureLoaded()
        if (selected == source) return
        selected = source
        _uiState.value = source
        ContinueWatchingSourceStorage.saveSelection(source.storageId)
    }

    fun clearLocalState() {
        selected = ContinueWatchingSource.LOCAL
        _uiState.value = ContinueWatchingSource.LOCAL
        ContinueWatchingSourceStorage.saveSelection(ContinueWatchingSource.LOCAL.storageId)
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = ContinueWatchingSource.fromStorage(ContinueWatchingSourceStorage.loadSelection())
        selected = stored ?: (legacyMigrationProbe?.invoke() ?: ContinueWatchingSource.LOCAL).also {
            // Persist immediately so the legacy flags are read exactly once.
            ContinueWatchingSourceStorage.saveSelection(it.storageId)
        }
        _uiState.value = selected
    }
}
