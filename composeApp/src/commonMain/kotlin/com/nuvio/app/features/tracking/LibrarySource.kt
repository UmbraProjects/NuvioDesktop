package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the Library tab gets its contents from — the same single-selection treatment as
 * [ContinueWatchingSource].
 *
 * The previous arrangement was subtler than the Continue Watching one and worse for it:
 * `LibrarySourceMode` already had a `SIMKL` case, but nothing could ever select it. Choosing SIMKL
 * actually set a separate `asLibrarySource` boolean in SIMKL's own settings, which
 * `LibraryRepository` checked *before* the enum. So one control wrote a value that was dead, and
 * another wrote the value that won.
 */
internal val LibrarySourceMode.trackingProvider: TrackingProviderId?
    get() = when (this) {
        LibrarySourceMode.LOCAL -> null
        LibrarySourceMode.TRAKT -> TrackingProviderId.TRAKT
        LibrarySourceMode.SIMKL -> TrackingProviderId.SIMKL
        LibrarySourceMode.MDBLIST -> TrackingProviderId.MDBLIST
        LibrarySourceMode.YAMTRACK -> TrackingProviderId.YAMTRACK
    }

internal fun librarySourceStorageId(mode: LibrarySourceMode): String = mode.name.lowercase()

internal fun librarySourceFromStorage(value: String?): LibrarySourceMode? =
    LibrarySourceMode.entries.firstOrNull {
        librarySourceStorageId(it).equals(value?.trim(), ignoreCase = true)
    }

/** Falls back to the local library when the selected provider is not connected. */
internal fun resolveLibrarySource(
    selected: LibrarySourceMode,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean,
): LibrarySourceMode {
    val providerId = selected.trackingProvider ?: return LibrarySourceMode.LOCAL
    return if (isProviderAuthenticated(providerId)) selected else LibrarySourceMode.LOCAL
}

/**
 * One-time migration from the split arrangement, using the precedence `LibraryRepository` applied:
 * the SIMKL boolean was checked first and won outright when set.
 */
internal fun migratedLibrarySource(
    simklWasLibrarySource: Boolean,
    storedMode: LibrarySourceMode,
): LibrarySourceMode = when {
    simklWasLibrarySource -> LibrarySourceMode.SIMKL
    // A stored SIMKL could never have been in effect, so it is not honoured on the way across.
    storedMode == LibrarySourceMode.SIMKL -> LibrarySourceMode.LOCAL
    else -> storedMode
}

object LibrarySourceRepository {
    private val _uiState = MutableStateFlow(LibrarySourceMode.LOCAL)
    val uiState: StateFlow<LibrarySourceMode> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var selected = LibrarySourceMode.LOCAL

    /** Supplies the legacy values for migration; set once at startup to avoid a package cycle. */
    internal var legacyMigrationProbe: (() -> LibrarySourceMode)? = null

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun selectedSource(): LibrarySourceMode {
        ensureLoaded()
        return selected
    }

    fun setSource(source: LibrarySourceMode) {
        ensureLoaded()
        if (selected == source) return
        selected = source
        _uiState.value = source
        ContinueWatchingSourceStorage.saveLibrarySelection(librarySourceStorageId(source))
    }

    fun clearLocalState() {
        selected = LibrarySourceMode.LOCAL
        _uiState.value = LibrarySourceMode.LOCAL
        ContinueWatchingSourceStorage.saveLibrarySelection(
            librarySourceStorageId(LibrarySourceMode.LOCAL),
        )
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = librarySourceFromStorage(ContinueWatchingSourceStorage.loadLibrarySelection())
        selected = stored ?: (legacyMigrationProbe?.invoke() ?: LibrarySourceMode.LOCAL).also {
            ContinueWatchingSourceStorage.saveLibrarySelection(librarySourceStorageId(it))
        }
        _uiState.value = selected
    }
}
