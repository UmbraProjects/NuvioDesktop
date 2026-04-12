package com.nuvio.app.core.network

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class NetworkCondition {
    Unknown,
    Checking,
    Online,
    NoInternet,
    ServersUnreachable,
}

data class NetworkStatusUiState(
    val condition: NetworkCondition = NetworkCondition.Unknown,
) {
    val isOnline: Boolean
        get() = condition == NetworkCondition.Online

    val isOfflineLike: Boolean
        get() = condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable
}

fun NetworkCondition.titleForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> "Cannot reach servers"
        NetworkCondition.NoInternet -> "No internet connection"
        else -> "Connection issue"
    }

fun NetworkCondition.messageForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> "Your device is online, but Nuvio could not reach required servers."
        NetworkCondition.NoInternet -> "Check your Wi-Fi or mobile data connection and try again."
        else -> "Please check your connection and try again."
    }

object NetworkStatusRepository {
    private const val REQUEST_TIMEOUT_MS = 4_500L
    private const val PUBLIC_PROBE_PRIMARY = "https://www.gstatic.com/generate_204"
    private const val PUBLIC_PROBE_FALLBACK = "https://cloudflare.com/cdn-cgi/trace"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(NetworkStatusUiState())
    val uiState: StateFlow<NetworkStatusUiState> = _uiState.asStateFlow()

    private var started = false
    private var probeInFlight = false
    private var pendingProbeAfterCurrent = false
    private var addonProbeTargets: List<String> = emptyList()

    fun ensureStarted() {
        if (started) return
        started = true
        requestRefresh(force = true)
    }

    fun updateAddonProbeTargets(urls: List<String>) {
        val normalized = urls
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalized == addonProbeTargets) return
        addonProbeTargets = normalized
        requestRefresh(force = true)
    }

    fun requestRefresh(force: Boolean = false) {
        ensureStarted()
        if (probeInFlight) {
            if (force) pendingProbeAfterCurrent = true
            return
        }

        scope.launch {
            do {
                pendingProbeAfterCurrent = false
                probeInFlight = true
                runProbe()
                probeInFlight = false
            } while (pendingProbeAfterCurrent)
        }
    }

    private suspend fun runProbe() {
        if (_uiState.value.condition == NetworkCondition.Unknown) {
            _uiState.value = NetworkStatusUiState(condition = NetworkCondition.Checking)
        }

        val internetReachable = probePublicInternet()
        if (!internetReachable) {
            _uiState.value = NetworkStatusUiState(condition = NetworkCondition.NoInternet)
            return
        }

        val supabaseReachable = probeReachable(
            url = "${SupabaseConfig.URL.trimEnd('/')}/rest/v1/",
            headers = mapOf("apikey" to SupabaseConfig.ANON_KEY),
        )
        if (!supabaseReachable) {
            _uiState.value = NetworkStatusUiState(condition = NetworkCondition.ServersUnreachable)
            return
        }

        val addonTarget = addonProbeTargets.firstOrNull()
        if (addonTarget != null) {
            val addonReachable = probeReachable(url = addonTarget)
            if (!addonReachable) {
                _uiState.value = NetworkStatusUiState(condition = NetworkCondition.ServersUnreachable)
                return
            }
        }

        _uiState.value = NetworkStatusUiState(condition = NetworkCondition.Online)
    }

    private suspend fun probePublicInternet(): Boolean =
        probeReachable(PUBLIC_PROBE_PRIMARY) || probeReachable(PUBLIC_PROBE_FALLBACK)

    private suspend fun probeReachable(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Boolean {
        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = headers,
                    body = "",
                )
            }.getOrNull()
        } ?: return false

        return response.status in 100..599
    }
}