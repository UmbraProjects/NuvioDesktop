package com.nuvio.app.features.simkl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SIMKL_DEFAULT_CW_DAYS_CAP = 30
const val SIMKL_CW_DAYS_CAP_ALL = 0

data class SimklSettingsUiState(
    val simklClientId: String = "",
    val simklAsLibrarySource: Boolean = false,
    val simklAsCwSource: Boolean = false,
    val simklAsCalendarSource: Boolean = false,
    val simklContinueWatchingDaysCap: Int = SIMKL_DEFAULT_CW_DAYS_CAP,
)

@Serializable
private data class SimklSettingsState(
    val clientId: String? = null,
    val asLibrarySource: Boolean = false,
    val asCwSource: Boolean = false,
    val asCalendarSource: Boolean = false,
    val continueWatchingDaysCap: Int = SIMKL_DEFAULT_CW_DAYS_CAP,
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

    fun snapshot(): SimklSettingsUiState = _uiState.value

    fun clientId(): String = state.clientId.orEmpty().trim()

    fun isSimklLibrarySource(): Boolean = state.asLibrarySource

    fun isSimklCwSource(): Boolean = state.asCwSource

    fun isSimklCalendarSource(): Boolean = state.asCalendarSource

    fun simklContinueWatchingDaysCap(): Int = state.continueWatchingDaysCap

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
        )
    }

    private fun persist() {
        SimklSettingsStorage.savePayload(json.encodeToString(state))
    }
}
