package com.nuvio.app.features.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The one tracking service whose schedule populates the Calendar screen. */
enum class CalendarSource(val storageId: String) {
    TRAKT("trakt"),
    SIMKL("simkl"),
    MDBLIST("mdblist");

    val providerId: TrackingProviderId
        get() = when (this) {
            TRAKT -> TrackingProviderId.TRAKT
            SIMKL -> TrackingProviderId.SIMKL
            MDBLIST -> TrackingProviderId.MDBLIST
        }

    companion object {
        fun fromStorage(value: String?): CalendarSource? =
            entries.firstOrNull { it.storageId.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Falls back to a connected provider when the selected one is not.
 *
 * Continue Watching and Library both resolve their selection against what is actually connected and
 * fall back to local state; the Calendar had no equivalent, and its default is Trakt — which no
 * longer issues API keys. A profile that has never touched the setting therefore opened on "connect
 * your Trakt account" with SIMKL sitting connected right next to it. Declaration order decides the
 * fallback, matching how the provider registry orders every other fan-out.
 *
 * With nothing connected the selection is returned unchanged, so the screen still names the service
 * the user actually chose in its connect prompt.
 */
internal fun resolveCalendarSource(
    selected: CalendarSource,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean,
): CalendarSource {
    if (isProviderAuthenticated(selected.providerId)) return selected
    return CalendarSource.entries.firstOrNull { candidate ->
        isProviderAuthenticated(candidate.providerId)
    } ?: selected
}

/**
 * One-time migration from the old independent MDBList and SIMKL switches.
 *
 * This deliberately preserves the old Calendar precedence: MDBList won when both switches were
 * enabled, SIMKL won when only its switch was enabled, and Trakt was the implicit default.
 */
internal fun migratedCalendarSource(
    mdbListWasCalendarSource: Boolean,
    simklWasCalendarSource: Boolean,
): CalendarSource = when {
    mdbListWasCalendarSource -> CalendarSource.MDBLIST
    simklWasCalendarSource -> CalendarSource.SIMKL
    else -> CalendarSource.TRAKT
}

object CalendarSourceRepository {
    private val _uiState = MutableStateFlow(CalendarSource.TRAKT)
    val uiState: StateFlow<CalendarSource> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var selected = CalendarSource.TRAKT

    /** Supplies the legacy flags for migration; set once at startup to avoid a package cycle. */
    internal var legacyMigrationProbe: (() -> CalendarSource)? = null

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun selectedSource(): CalendarSource {
        ensureLoaded()
        return selected
    }

    fun setSource(source: CalendarSource) {
        ensureLoaded()
        if (selected == source) return
        selected = source
        _uiState.value = source
        ContinueWatchingSourceStorage.saveCalendarSelection(source.storageId)
    }

    fun clearLocalState() {
        selected = CalendarSource.TRAKT
        _uiState.value = CalendarSource.TRAKT
        ContinueWatchingSourceStorage.saveCalendarSelection(CalendarSource.TRAKT.storageId)
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = CalendarSource.fromStorage(
            ContinueWatchingSourceStorage.loadCalendarSelection(),
        )
        selected = stored ?: (legacyMigrationProbe?.invoke() ?: CalendarSource.TRAKT).also {
            // Persist immediately so legacy provider flags are only consulted once per profile.
            ContinueWatchingSourceStorage.saveCalendarSelection(it.storageId)
        }
        _uiState.value = selected
    }
}
