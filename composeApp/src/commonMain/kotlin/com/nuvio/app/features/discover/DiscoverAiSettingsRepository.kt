package com.nuvio.app.features.discover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Provider settings for Discover's AI rows — plan §5. Per profile, like every other service key. */
object DiscoverAiSettingsRepository {
    private val _uiState = MutableStateFlow(DiscoverAiSettings())
    val uiState: StateFlow<DiscoverAiSettings> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun snapshot(): DiscoverAiSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setProvider(value: DiscoverAiProvider) = update {
        DiscoverAiSettingsStorage.saveProvider(value.name)
        it.copy(provider = value)
    }

    fun setApiKey(value: String) = update {
        val trimmed = value.trim()
        DiscoverAiSettingsStorage.saveApiKey(trimmed)
        // Clearing the key is as good as switching the feature off, and leaving `enabled` true would
        // leave rows that can never build sitting in the row list looking broken.
        if (trimmed.isBlank()) {
            DiscoverAiSettingsStorage.saveEnabled(false)
            it.copy(apiKey = "", enabled = false)
        } else {
            it.copy(apiKey = trimmed)
        }
    }

    fun setBaseUrl(value: String) = update {
        val trimmed = value.trim()
        DiscoverAiSettingsStorage.saveBaseUrl(trimmed)
        it.copy(baseUrl = trimmed)
    }

    fun setModel(value: String) = update {
        val trimmed = value.trim()
        DiscoverAiSettingsStorage.saveModel(trimmed)
        it.copy(model = trimmed)
    }

    /**
     * Records that the privacy dialog was accepted. Never called with `false` from the UI: consent
     * is withdrawn by turning the feature off, and re-enabling should not re-ask someone who has
     * already read it once.
     */
    fun setConsentGiven(value: Boolean) = update {
        DiscoverAiSettingsStorage.saveConsentGiven(value)
        it.copy(consentGiven = value)
    }

    fun setEnabled(value: Boolean) = update {
        // Enabling without a key or without consent would produce rows that cannot build. The
        // caller is expected to collect both first; this is the backstop.
        if (value && (it.apiKey.isBlank() || !it.consentGiven)) return@update it
        DiscoverAiSettingsStorage.saveEnabled(value)
        it.copy(enabled = value)
    }

    fun setDailyRefresh(value: Boolean) = update {
        DiscoverAiSettingsStorage.saveDailyRefresh(value)
        it.copy(dailyRefresh = value)
    }

    private inline fun update(transform: (DiscoverAiSettings) -> DiscoverAiSettings) {
        ensureLoaded()
        val next = transform(_uiState.value)
        if (next != _uiState.value) _uiState.value = next
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val provider = DiscoverAiSettingsStorage.loadProvider()
            ?.let { stored -> DiscoverAiProvider.entries.firstOrNull { it.name == stored } }
            ?: DiscoverAiProvider.OpenAiCompat
        _uiState.value = DiscoverAiSettings(
            provider = provider,
            apiKey = DiscoverAiSettingsStorage.loadApiKey().orEmpty(),
            baseUrl = DiscoverAiSettingsStorage.loadBaseUrl()
                ?: DISCOVER_AI_DEFAULT_OPENAI_BASE_URL,
            model = DiscoverAiSettingsStorage.loadModel().orEmpty(),
            consentGiven = DiscoverAiSettingsStorage.loadConsentGiven() ?: false,
            enabled = DiscoverAiSettingsStorage.loadEnabled() ?: false,
            dailyRefresh = DiscoverAiSettingsStorage.loadDailyRefresh() ?: false,
        )
    }

    internal fun exportToSyncPayload() = DiscoverAiSettingsStorage.exportToSyncPayload()

    internal fun replaceFromSyncPayload(payload: kotlinx.serialization.json.JsonObject) {
        DiscoverAiSettingsStorage.replaceFromSyncPayload(payload)
        loadFromDisk()
    }
}
