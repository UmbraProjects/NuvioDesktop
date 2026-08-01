package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://api.simkl.com"

internal object SimklAuthRepository {
    private val log = Logger.withTag("SimklAuth")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private val _uiState = MutableStateFlow(SimklAuthUiState())
    val uiState: StateFlow<SimklAuthUiState> = _uiState.asStateFlow()

    // Mirrors TraktAuthRepository.isAuthenticated — a StateFlow so consumers can react to changes.
    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private var authState = SimklAuthState()
    private var pinPollJob: Job? = null
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = SimklAuthStorage.loadPayload().orEmpty().trim()
        authState = if (raw.isBlank()) SimklAuthState()
        else runCatching { json.decodeFromString<SimklAuthState>(raw) }.getOrDefault(SimklAuthState())
        publishState()
    }

    fun onProfileChanged() {
        pinPollJob?.cancel()
        pinPollJob = null
        loaded = false
        authState = SimklAuthState()
        ensureLoaded()
    }

    /** Returns the headers needed for authenticated SIMKL API calls, or null if not connected. */
    fun authorizedHeaders(): Map<String, String>? {
        val token = authState.accessToken?.takeIf { it.isNotBlank() } ?: return null
        return mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
            "User-Agent" to "NuvioDesktop/${AppVersionPolicy.displayVersionName}",
        )
    }

    /**
     * Fetches the activities endpoint — must be called before any /sync/all-items request.
     * Returns null if unauthenticated or the request fails.
     */
    suspend fun fetchActivities(): SimklActivities? {
        val headers = authorizedHeaders() ?: return null
        val url = appendParams("$BASE_URL/sync/activities")
        return runCatching {
            val resp = httpRequestRaw(method = "GET", url = url, headers = headers, body = "")
            if (resp.status !in 200..299) return null
            json.decodeFromString<SimklActivities>(resp.body)
        }.onFailure { log.w(it) { "SIMKL /sync/activities failed" } }.getOrNull()
    }

    /** Appends required query parameters to any SIMKL API URL. */
    fun appendParams(url: String): String {
        val connector = if ('?' in url) '&' else '?'
        val clientId = SimklSettingsRepository.clientId()
        val version = AppVersionPolicy.displayVersionName
        return "$url${connector}client_id=$clientId&app-name=NuvioDesktop&app-version=$version"
    }

    fun onConnectRequested() {
        val clientId = SimklSettingsRepository.clientId()
        if (clientId.isBlank()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Enter your SIMKL Client ID and save it before connecting.",
            )
            return
        }
        pinPollJob?.cancel()
        _uiState.value = SimklAuthUiState(isLoading = true)
        pinPollJob = scope.launch {
            try {
                val pin = requestPin() ?: run {
                    _uiState.value = SimklAuthUiState(
                        errorMessage = "Failed to request PIN from SIMKL. Check your connection.",
                    )
                    return@launch
                }
                _uiState.value = SimklAuthUiState(
                    mode = SimklConnectionMode.AWAITING_PIN,
                    pendingPin = pin.userCode,
                )
                pollForToken(pin)
            } catch (_: CancellationException) {
                // Cancelled — leave UI as-is (disconnect was called mid-flow).
            }
        }
    }

    fun onDisconnectRequested() {
        pinPollJob?.cancel()
        pinPollJob = null
        authState = SimklAuthState()
        SimklAuthStorage.clearPayload()
        publishState()
    }

    private suspend fun requestPin(): SimklPinResponse? = runCatching {
        val url = appendParams("$BASE_URL/oauth/pin")
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf("User-Agent" to "NuvioDesktop/${AppVersionPolicy.displayVersionName}"),
            body = "",
        )
        if (response.status !in 200..299) {
            log.w { "PIN request failed: ${response.status} ${response.body}" }
            return@runCatching null
        }
        json.decodeFromString<SimklPinResponse>(response.body)
    }.onFailure { log.w(it) { "PIN request exception" } }.getOrNull()

    private suspend fun pollForToken(pin: SimklPinResponse) {
        val intervalMs = (pin.interval * 1000L).coerceAtLeast(5_000L)
        val expiresAtMs = System.currentTimeMillis() + (pin.expiresIn * 1000L)
        val pollUrl = appendParams("$BASE_URL/oauth/pin/${pin.userCode}")

        while (System.currentTimeMillis() < expiresAtMs) {
            delay(intervalMs)
            val result = runCatching {
                val response = httpRequestRaw(
                    method = "GET",
                    url = pollUrl,
                    headers = mapOf("User-Agent" to "NuvioDesktop/${AppVersionPolicy.displayVersionName}"),
                    body = "",
                )
                if (response.status !in 200..299) return@runCatching null
                json.decodeFromString<SimklPinPollResponse>(response.body)
            }.onFailure { log.w(it) { "PIN poll exception" } }.getOrNull()

            when {
                result == null -> continue
                result.result == "OK" && result.accessToken != null -> {
                    val username = fetchUsername(result.accessToken)
                    authState = SimklAuthState(accessToken = result.accessToken, username = username)
                    SimklAuthStorage.savePayload(json.encodeToString(authState))
                    publishState()
                    return
                }
                // A new device_code in the response means the server reissued a code (expiry) — stop.
                result.result == "KO" && result.deviceCode != null -> {
                    _uiState.value = SimklAuthUiState(
                        errorMessage = "PIN expired. Please try connecting again.",
                    )
                    return
                }
                // Otherwise ("KO", no device_code) still pending — keep polling.
            }
        }

        _uiState.value = SimklAuthUiState(errorMessage = "PIN expired. Please try connecting again.")
    }

    private suspend fun fetchUsername(token: String): String? = runCatching {
        val url = appendParams("$BASE_URL/users/settings")
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "User-Agent" to "NuvioDesktop/${AppVersionPolicy.displayVersionName}",
            ),
            body = "",
        )
        if (response.status !in 200..299) return@runCatching null
        json.decodeFromString<SimklUserSettingsResponse>(response.body).user?.name
    }.onFailure { log.w(it) { "Failed to fetch SIMKL username" } }.getOrNull()

    private fun publishState() {
        val authenticated = authState.isAuthenticated
        _isAuthenticated.value = authenticated
        _uiState.value = SimklAuthUiState(
            mode = if (authenticated) SimklConnectionMode.CONNECTED else SimklConnectionMode.DISCONNECTED,
            username = authState.username,
        )
    }
}

