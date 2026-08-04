package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Device-local permissions for writing compatible desktop settings to the shared Nuvio profile.
 * These permissions are intentionally not profile scoped and are never included in sync payloads.
 * New desktop installs default to no settings sync so opening the fork cannot silently rewrite the
 * settings used by an official mobile application.
 */
@Serializable
data class SynchronizationPreferencesUiState(
    val appearanceEnabled: Boolean = false,
    val homeCatalogsEnabled: Boolean = false,
    val streamDisplayEnabled: Boolean = false,
    val debridEnabled: Boolean = false,
    val metadataEnabled: Boolean = false,
    val contentPreferencesEnabled: Boolean = false,
    val traktEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
)

internal expect object SynchronizationPreferencesStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

object SynchronizationPreferencesRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val _uiState = MutableStateFlow(SynchronizationPreferencesUiState())
    val uiState: StateFlow<SynchronizationPreferencesUiState> = _uiState.asStateFlow()
    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _uiState.value = SynchronizationPreferencesStorage.loadPayload()
            ?.takeIf { it.isNotBlank() }
            ?.let { payload ->
                runCatching {
                    json.decodeFromString<SynchronizationPreferencesUiState>(payload)
                }.getOrNull()
            }
            ?: SynchronizationPreferencesUiState()
    }

    fun setAppearanceEnabled(enabled: Boolean) = update { copy(appearanceEnabled = enabled) }

    fun setHomeCatalogsEnabled(enabled: Boolean) = update { copy(homeCatalogsEnabled = enabled) }

    fun setStreamDisplayEnabled(enabled: Boolean) = update { copy(streamDisplayEnabled = enabled) }

    fun setDebridEnabled(enabled: Boolean) = update { copy(debridEnabled = enabled) }

    fun setMetadataEnabled(enabled: Boolean) = update { copy(metadataEnabled = enabled) }

    fun setContentPreferencesEnabled(enabled: Boolean) =
        update { copy(contentPreferencesEnabled = enabled) }

    fun setTraktEnabled(enabled: Boolean) = update { copy(traktEnabled = enabled) }

    fun setNotificationsEnabled(enabled: Boolean) = update { copy(notificationsEnabled = enabled) }

    private inline fun update(transform: SynchronizationPreferencesUiState.() -> SynchronizationPreferencesUiState) {
        ensureLoaded()
        val next = _uiState.value.transform()
        if (next == _uiState.value) return
        _uiState.value = next
        SynchronizationPreferencesStorage.savePayload(json.encodeToString(next))
    }
}
