package com.nuvio.app.features.yamtrack

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class YamtrackSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiToken: String = "",
    val connectionState: YamtrackConnectionState = YamtrackConnectionState.Unknown,
) {
    val hasCredentials: Boolean
        get() = baseUrl.isNotBlank() && apiToken.isNotBlank()

    val isActive: Boolean
        get() = enabled && hasCredentials
}

sealed interface YamtrackConnectionState {
    data object Unknown : YamtrackConnectionState
    data object Testing : YamtrackConnectionState

    /** Reached the instance and the token was accepted. */
    data class Connected(val version: String?) : YamtrackConnectionState

    /** Reached the instance but the token was rejected — the URL is right, the token is not. */
    data object Unauthorized : YamtrackConnectionState

    /** Could not reach a Yamtrack instance at that URL at all. */
    data class Unreachable(val detail: String?) : YamtrackConnectionState
}

internal expect object YamtrackSettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

/**
 * Yamtrack is self-hosted, so unlike the other providers there is no fixed host: the user supplies
 * a base URL and an API token from their own instance (Settings → Integrations → API Token).
 */
object YamtrackSettingsRepository {
    private val _uiState = MutableStateFlow(YamtrackSettings())
    val uiState: StateFlow<YamtrackSettings> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var enabled = false
    private var baseUrl = ""
    private var apiToken = ""
    private var connectionState: YamtrackConnectionState = YamtrackConnectionState.Unknown

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun snapshot(): YamtrackSettings {
        ensureLoaded()
        return _uiState.value
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (value && !(baseUrl.isNotBlank() && apiToken.isNotBlank())) return
        if (enabled == value) return
        enabled = value
        persistAndPublish()
    }

    fun setBaseUrl(value: String) {
        ensureLoaded()
        val normalized = normalizeBaseUrl(value)
        if (baseUrl == normalized) return
        baseUrl = normalized
        connectionState = YamtrackConnectionState.Unknown
        if (baseUrl.isBlank()) enabled = false
        persistAndPublish()
    }

    fun setApiToken(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (apiToken == normalized) return
        apiToken = normalized
        connectionState = YamtrackConnectionState.Unknown
        if (apiToken.isBlank()) enabled = false
        persistAndPublish()
    }

    internal fun setConnectionState(state: YamtrackConnectionState) {
        ensureLoaded()
        if (connectionState == state) return
        connectionState = state
        publish()
    }

    /** Base URL and token, or null when Yamtrack is off or not fully configured. */
    internal fun activeCredentials(): Pair<String, String>? {
        ensureLoaded()
        if (!enabled || baseUrl.isBlank() || apiToken.isBlank()) return null
        return baseUrl to apiToken
    }

    fun clearLocalState() {
        enabled = false
        baseUrl = ""
        apiToken = ""
        connectionState = YamtrackConnectionState.Unknown
        persistAndPublish()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = YamtrackSettingsStorage.loadPayload().orEmpty()
        val parsed = decodeYamtrackSettingsPayload(stored)
        baseUrl = parsed.baseUrl
        apiToken = parsed.apiToken
        enabled = parsed.enabled && baseUrl.isNotBlank() && apiToken.isNotBlank()
        connectionState = YamtrackConnectionState.Unknown
        publish()
    }

    private fun persistAndPublish() {
        YamtrackSettingsStorage.savePayload(
            encodeYamtrackSettingsPayload(enabled = enabled, baseUrl = baseUrl, apiToken = apiToken),
        )
        publish()
    }

    private fun publish() {
        _uiState.value = YamtrackSettings(
            enabled = enabled,
            baseUrl = baseUrl,
            apiToken = apiToken,
            connectionState = connectionState,
        )
        YamtrackTrackingAuthProvider.refreshAuthenticationState()
    }
}

/**
 * Trims a pasted URL into an origin.
 *
 * Users paste whatever is in the address bar, which is usually a page rather than the API root, so
 * a trailing path or slash is removed. A scheme is assumed only when absent — self-hosted
 * instances on a LAN are legitimately plain HTTP, so http:// is never rewritten to https://.
 */
internal fun normalizeBaseUrl(value: String): String {
    var raw = value.trim().removeSuffix("/")
    if (raw.isBlank()) return ""
    if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
        raw = "https://$raw"
    }
    // Drop anything after the host (and port), e.g. a pasted /settings or /api/v1 path.
    val schemeEnd = raw.indexOf("://") + 3
    val pathStart = raw.indexOf('/', schemeEnd)
    if (pathStart != -1) raw = raw.substring(0, pathStart)
    return raw.removeSuffix("/")
}

/** True when the URL is plain HTTP to something other than a loopback address. */
internal fun isInsecureRemoteBaseUrl(value: String): Boolean {
    if (!value.startsWith("http://", ignoreCase = true)) return false
    val host = value.removePrefix("http://").substringBefore(':').substringBefore('/').lowercase()
    return host != "localhost" && host != "127.0.0.1" && host != "[::1]" && !host.endsWith(".local")
}
