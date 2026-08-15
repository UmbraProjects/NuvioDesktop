package com.nuvio.app.features.simkl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SIMKL_DEFAULT_CW_DAYS_CAP = 30
const val SIMKL_CW_DAYS_CAP_ALL = 0

/**
 * The oldest `lastUpdatedEpochMs` a Continue Watching row may have, or 0 for "no window".
 *
 * One function because the same arithmetic previously lived in three places — the repository's
 * row filter, Home's Up Next seed filter and the settings label — and drifting copies are how a
 * 30-day window ended up applied to some of those lists and not others.
 */
internal fun simklContinueWatchingCutoffMs(daysCap: Int, nowEpochMs: Long): Long =
    if (daysCap > SIMKL_CW_DAYS_CAP_ALL) {
        nowEpochMs - daysCap.toLong() * 24L * 60L * 60L * 1000L
    } else {
        SIMKL_NO_CW_CUTOFF
    }

/** Sentinel returned by [simklContinueWatchingCutoffMs] when every row qualifies. */
internal const val SIMKL_NO_CW_CUTOFF = 0L

data class SimklSettingsUiState(
    val simklClientId: String = "",
    val simklAsLibrarySource: Boolean = false,
    val simklAsCwSource: Boolean = false,
    val simklAsCalendarSource: Boolean = false,
    val simklContinueWatchingDaysCap: Int = SIMKL_DEFAULT_CW_DAYS_CAP,
    val simklOpenDailyOnStartup: Boolean = false,
    val simklTrackRewatches: Boolean = false,
)

@Serializable
private data class SimklSettingsState(
    val clientId: String? = null,
    val asLibrarySource: Boolean = false,
    val asCwSource: Boolean = false,
    val asCalendarSource: Boolean = false,
    val continueWatchingDaysCap: Int = SIMKL_DEFAULT_CW_DAYS_CAP,
    val openDailyOnStartup: Boolean = false,
    val trackRewatches: Boolean = false,
    // UTC day (see SimklDailyVisit) the site was last opened by the startup reminder.
    val lastDailyVisitEpochDay: Long? = null,
    // Last known /sync/activities timestamps, used to skip full re-fetches when nothing changed.
    val lastLibraryActivitiesAt: String? = null,
    val lastCwActivitiesAt: String? = null,
    val lastCalendarActivitiesAt: String? = null,
)

internal object SimklSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val _uiState = MutableStateFlow(SimklSettingsUiState())
    val uiState: StateFlow<SimklSettingsUiState> = _uiState.asStateFlow()

    private var state = SimklSettingsState()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = SimklSettingsStorage.loadPayload().orEmpty().trim()
        state = if (raw.isBlank()) SimklSettingsState()
        else runCatching { json.decodeFromString<SimklSettingsState>(raw) }.getOrDefault(SimklSettingsState())
        publish()
    }

    fun onProfileChanged() {
        loaded = false
        state = SimklSettingsState()
        ensureLoaded()
    }

    fun snapshot(): SimklSettingsUiState = _uiState.value

    fun clientId(): String = state.clientId.orEmpty().trim()

    fun isSimklLibrarySource(): Boolean = state.asLibrarySource

    fun isSimklCwSource(): Boolean = state.asCwSource

    fun isSimklCalendarSource(): Boolean = state.asCalendarSource

    fun simklContinueWatchingDaysCap(): Int = state.continueWatchingDaysCap

    fun isOpenDailyOnStartup(): Boolean = state.openDailyOnStartup

    fun isRewatchTrackingEnabled(): Boolean = state.trackRewatches


    /** True when the reminder is enabled and simkl.com has not been opened yet this UTC day. */
    fun isDailyVisitDue(nowMillis: Long): Boolean =
        state.openDailyOnStartup && SimklDailyVisit.isDue(state.lastDailyVisitEpochDay, nowMillis)

    /** Records that simkl.com was opened, suppressing the reminder until midnight UTC. */
    fun markDailyVisitOpened(nowMillis: Long) {
        state = state.copy(lastDailyVisitEpochDay = SimklDailyVisit.utcEpochDay(nowMillis))
        persist()
    }

    fun lastLibraryActivitiesAt(): String? = state.lastLibraryActivitiesAt
    fun lastCwActivitiesAt(): String? = state.lastCwActivitiesAt
    fun lastCalendarActivitiesAt(): String? = state.lastCalendarActivitiesAt

    fun setLastLibraryActivitiesAt(ts: String) {
        state = state.copy(lastLibraryActivitiesAt = ts); persist()
    }
    fun setLastCwActivitiesAt(ts: String) {
        state = state.copy(lastCwActivitiesAt = ts); persist()
    }
    fun setLastCalendarActivitiesAt(ts: String) {
        state = state.copy(lastCalendarActivitiesAt = ts); persist()
    }

    fun setClientId(clientId: String) {
        state = state.copy(clientId = clientId.trim())
        persist(); publish()
    }

    fun clearClientId() {
        state = SimklSettingsState()
        persist(); publish()
    }

    fun clearLocalState() {
        state = state.copy(
            lastLibraryActivitiesAt = null,
            lastCwActivitiesAt = null,
            lastCalendarActivitiesAt = null,
            lastDailyVisitEpochDay = null,
        )
        persist(); publish()
    }

    fun setAsLibrarySource(enabled: Boolean) {
        state = state.copy(asLibrarySource = enabled)
        persist(); publish()
    }

    fun setAsCwSource(enabled: Boolean) {
        state = state.copy(asCwSource = enabled)
        persist(); publish()
    }

    fun setAsCalendarSource(enabled: Boolean) {
        state = state.copy(asCalendarSource = enabled)
        persist(); publish()
    }

    fun setOpenDailyOnStartup(enabled: Boolean) {
        // Re-enabling clears the stamp so the reminder fires on the next launch instead of
        // silently waiting out a day that was already consumed before it was turned off.
        state = state.copy(
            openDailyOnStartup = enabled,
            lastDailyVisitEpochDay = if (enabled) null else state.lastDailyVisitEpochDay,
        )
        persist(); publish()
    }

    fun setTrackRewatches(enabled: Boolean) {
        state = state.copy(trackRewatches = enabled)
        persist(); publish()
        if (enabled) SimklRewatchRepository.refreshAsync()
    }

    fun setSimklContinueWatchingDaysCap(days: Int) {
        state = state.copy(continueWatchingDaysCap = days.coerceAtLeast(SIMKL_CW_DAYS_CAP_ALL))
        persist(); publish()
    }

    private fun publish() {
        _uiState.value = SimklSettingsUiState(
            simklClientId = state.clientId.orEmpty(),
            simklAsLibrarySource = state.asLibrarySource,
            simklAsCwSource = state.asCwSource,
            simklAsCalendarSource = state.asCalendarSource,
            simklContinueWatchingDaysCap = state.continueWatchingDaysCap,
            simklOpenDailyOnStartup = state.openDailyOnStartup,
            simklTrackRewatches = state.trackRewatches,
        )
    }

    private fun persist() {
        SimklSettingsStorage.savePayload(json.encodeToString(state))
    }
}
