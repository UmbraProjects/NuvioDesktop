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
        // leave rows that can never build sitting in the row list looking broken. The recap switch
        // goes with it for the same reason: left on, it would read as an enabled feature whose row
        // has silently stopped appearing.
        if (trimmed.isBlank()) {
            DiscoverAiSettingsStorage.saveEnabled(false)
            DiscoverAiSettingsStorage.saveRecapEnabled(false)
            // Trivia is left as the user set it: with no key it falls back to Wikipedia, so it is
            // not a feature that can never run without one.
            it.copy(apiKey = "", enabled = false, recapEnabled = false)
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

    /** Same backstop as [setEnabled]: a switch that turns on a feature which cannot run is a bug. */
    fun setRecapEnabled(value: Boolean) = update {
        if (value && (it.apiKey.isBlank() || !it.consentGiven)) return@update it
        DiscoverAiSettingsStorage.saveRecapEnabled(value)
        it.copy(recapEnabled = value)
    }

    /**
     * No key or consent backstop, unlike the switches above: trivia also runs from Wikipedia, which
     * needs neither. Consent only gates sending anything to the AI provider, and
     * [DiscoverAiSettings.isTriviaReady] already requires it before the provider is asked.
     */
    fun setTriviaEnabled(value: Boolean) = update {
        DiscoverAiSettingsStorage.saveTriviaEnabled(value)
        it.copy(triviaEnabled = value)
    }

    fun setRecapUseModelKnowledge(value: Boolean) = update {
        DiscoverAiSettingsStorage.saveRecapUseModelKnowledge(value)
        it.copy(recapUseModelKnowledge = value)
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
            recapEnabled = DiscoverAiSettingsStorage.loadRecapEnabled() ?: false,
            recapUseModelKnowledge =
                DiscoverAiSettingsStorage.loadRecapUseModelKnowledge() ?: false,
            triviaEnabled = DiscoverAiSettingsStorage.loadTriviaEnabled() ?: false,
        )
    }

    internal fun exportToSyncPayload() = DiscoverAiSettingsStorage.exportToSyncPayload()

    internal fun replaceFromSyncPayload(payload: kotlinx.serialization.json.JsonObject) {
        DiscoverAiSettingsStorage.replaceFromSyncPayload(payload)
        loadFromDisk()
    }
}
