package com.nuvio.app.features.usenet

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How Nuvio names itself to indexers and NZB servers when the user picks it; follows every update. */
internal val NUVIO_USER_AGENT = "Nuvio/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}"

internal const val USER_AGENT = "User-Agent"

/** An HTTP header value: printable ASCII on one line. */
internal fun isValidUserAgent(value: String) = value.length <= 256 && value.all { it in ' '..'~' }

/** The engine's tuning (NuvioTV's UsenetConfiguration), sent with every session it opens. */
@Serializable
data class UsenetConfiguration(
    val profile: String = "balanced",
    val readAhead: Int = 0,
    val maxConnections: Int = 0,
    val fastMkvStartup: Boolean = true,
    val fastNzbFetch: Boolean = true,
    val cacheNzb: Boolean = true,
    val allowPrivateNetwork: Boolean = false,
    val holeFilling: Boolean = false,
    val maxMissingArticles: Int = 5,
    val maxConsecutiveMissing: Int = 2,
    /**
     * What indexer searches and NZB downloads call themselves. [AGENT_GENERIC] sends no User-Agent
     * of ours, so each HTTP library sends its own unbranded default (Java-http-client for searches,
     * Go-http-client for the engine's NZB fetch): it neither names Nuvio nor pretends to be
     * another program. [AGENT_NUVIO] sends [NUVIO_USER_AGENT]; [AGENT_CUSTOM] sends [customUserAgent].
     */
    val userAgentMode: String = AGENT_GENERIC,
    val customUserAgent: String = "",
) {
    /** The User-Agent to send, or null to leave the library's own default. */
    val userAgent: String?
        get() = when (userAgentMode) {
            AGENT_NUVIO -> NUVIO_USER_AGENT
            AGENT_CUSTOM -> customUserAgent.trim().takeIf { it.isNotEmpty() }
            else -> null
        }

    fun normalized(): UsenetConfiguration {
        val missing = maxMissingArticles.coerceIn(1, 50)
        return copy(
            profile = profile.takeIf { it in PROFILES } ?: "balanced",
            readAhead = readAhead.coerceIn(0, 512),
            maxConnections = maxConnections.coerceIn(0, 4096),
            maxMissingArticles = missing,
            maxConsecutiveMissing = maxConsecutiveMissing.coerceIn(1, minOf(10, missing)),
            userAgentMode = userAgentMode.takeIf { it in AGENT_MODES } ?: AGENT_GENERIC,
            customUserAgent = customUserAgent.takeIf(::isValidUserAgent).orEmpty(),
        )
    }

    companion object {
        val PROFILES = listOf("low-memory", "balanced", "throughput")
        const val AGENT_GENERIC = "generic"
        const val AGENT_NUVIO = "nuvio"
        const val AGENT_CUSTOM = "custom"
        val AGENT_MODES = listOf(AGENT_GENERIC, AGENT_NUVIO, AGENT_CUSTOM)
    }
}

@Serializable
private data class UsenetSettingsPayload(
    val sources: UsenetSourceConfiguration = UsenetSourceConfiguration(),
    val engine: UsenetConfiguration = UsenetConfiguration(),
)

data class UsenetUiState(
    val sources: UsenetSourceConfiguration = UsenetSourceConfiguration(),
    val engine: UsenetConfiguration = UsenetConfiguration(),
)

/**
 * Built-in Usenet settings for the active profile: providers (with their passwords), indexers
 * (with their API keys), the search filters and the engine tuning. Stored on this PC in the
 * account's store, like debrid keys, and never synced.
 */
object UsenetSettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(UsenetUiState())
    val uiState: StateFlow<UsenetUiState> = _uiState.asStateFlow()
    private var loadedProfile: Int? = null

    fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfile == profileId) return
        loadedProfile = profileId
        val payload = UsenetStorage.loadPayload(profileId)
            ?.let { runCatching { json.decodeFromString<UsenetSettingsPayload>(it) }.getOrNull() }
            ?: UsenetSettingsPayload()
        _uiState.value = UsenetUiState(payload.sources.normalized(), payload.engine.normalized())
    }

    fun onProfileChanged() {
        loadedProfile = null
        ensureLoaded()
    }

    val sources: UsenetSourceConfiguration get() = ensureLoaded().let { _uiState.value.sources }
    val engine: UsenetConfiguration get() = ensureLoaded().let { _uiState.value.engine }

    /** Saves [sources] once it validates; throws IllegalArgumentException with a readable reason. */
    fun updateSources(transform: (UsenetSourceConfiguration) -> UsenetSourceConfiguration) {
        ensureLoaded()
        val next = transform(_uiState.value.sources).normalized()
        next.validate()
        _uiState.value = _uiState.value.copy(sources = next)
        persist()
    }

    fun updateEngine(transform: (UsenetConfiguration) -> UsenetConfiguration) {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(engine = transform(_uiState.value.engine).normalized())
        persist()
        UsenetEngine.settingsChanged()
    }

    fun clearLocalState() {
        loadedProfile = null
        _uiState.value = UsenetUiState()
    }

    private fun persist() {
        val state = _uiState.value
        UsenetStorage.savePayload(
            loadedProfile ?: ProfileRepository.activeProfileId,
            json.encodeToString(UsenetSettingsPayload.serializer(), UsenetSettingsPayload(state.sources, state.engine)),
        )
    }
}

internal expect object UsenetStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
}
