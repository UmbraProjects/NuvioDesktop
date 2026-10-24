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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** See [SimklAuthRepository.fetchActivities]. */
private const val ACTIVITIES_BURST_WINDOW_MS = 15_000L

private const val BASE_URL = "https://api.simkl.com"

/** SIMKL wants a short lowercase identifier here; the User-Agent carries the display name. */
private const val SIMKL_APP_NAME = "nuvio-htpc"

/** Refresh this far ahead of expiry ("a day or so" per SIMKL's token guide). */
private const val REFRESH_AHEAD_MS = 24L * 60L * 60L * 1000L

/** RFC 8628 `slow_down`: add this to the poll interval, and actually wait it out. */
private const val SLOW_DOWN_STEP_MS = 5_000L

private fun formEncode(params: Map<String, String>): String =
    params.entries.joinToString("&") { (key, value) ->
        "${simklUrlEncode(key)}=${simklUrlEncode(value)}"
    }

/** application/x-www-form-urlencoded, which [java.net.URLEncoder]-free common code has to do itself. */
internal fun simklUrlEncode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val c = byte.toInt().toChar()
        when {
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~' ->
                append(c)
            c == ' ' -> append('+')
            else -> {
                append('%')
                append(((byte.toInt() shr 4) and 0xF).toString(16).uppercase())
                append((byte.toInt() and 0xF).toString(16).uppercase())
            }
        }
    }
}

/**
 * SIMKL connection state and the AUTH V2 (OAuth 2.0) token lifecycle.
 *
 * New connections always use the RFC 8628 device flow against the `/oauth2` endpoints. A connection made
 * before AUTH V2 support carries a V1 token (64 hex chars, no prefix) and keeps working with the
 * V1 client_id that minted it until the user reconnects — SIMKL's "run both client IDs" migration
 * path. V1 retires around April 2027; nothing mints a V1 token any more.
 *
 * V2 access tokens live 7 days. This object is the single refresh owner for the process: a
 * refresh replaces the grant's access token immediately, so two independent refreshers cut each
 * other off. Before refreshing it re-reads storage, so a second app instance that already
 * refreshed is adopted rather than fought.
 */
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
    private val activitiesMutex = Mutex()
    private val refreshMutex = Mutex()
    private var cachedActivities: SimklActivities? = null
    private var cachedActivitiesAtMs = 0L
    private var pinPollJob: Job? = null
    private var loaded = false
    private var lastProactiveRefreshAttemptMs = 0L

    val userAgent: String get() = "NuvioDesktop/${AppVersionPolicy.displayVersionName}"

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        authState = readStoredState()
        if (authState.isAuthenticated && authState.clientId.isNullOrBlank()) {
            // Saved before tokens remembered their registration. Pin the V1 token to the Client
            // ID it was minted with now, so entering a new V2 Client ID cannot strand it.
            val settingsClientId = SimklSettingsRepository.clientId()
            if (settingsClientId.isNotBlank()) {
                authState = authState.copy(clientId = settingsClientId)
                persist()
            }
        }
        publishState()
        if (authState.isAuthenticated && authState.accountType.isNullOrBlank()) {
            scope.launch {
                if (refreshUserSettings() && SimklSettingsRepository.isRewatchTrackingEnabled()) {
                    SimklRewatchRepository.refreshAsync()
                }
            }
        }
    }

    fun onProfileChanged() {
        pinPollJob?.cancel()
        pinPollJob = null
        loaded = false
        authState = SimklAuthState()
        clearActivitiesCache()
        ensureLoaded()
    }

    /** True when a token is held and usable — the cheap, non-suspending connection check. */
    fun hasUsableToken(): Boolean = authState.isAuthenticated && !authState.needsReconnect

    /**
     * Headers for an authenticated SIMKL API call, or null if not connected. A V2 token within a
     * day of expiry is refreshed first.
     *
     * Never send these to the Cloudflare-cached catalog endpoints (`/movies/{id}`, `/tv/{id}`,
     * `/anime/{id}`, episode lists, `/redirect`, data.simkl.in) — use [publicHeaders] there.
     */
    suspend fun authorizedHeaders(): Map<String, String>? {
        if (!hasUsableToken()) return null
        // Ahead-of-expiry refresh is opportunistic: the token still has up to a day left, so a
        // failed attempt (SIMKL outage) waits a minute rather than repeating on every request.
        val now = System.currentTimeMillis()
        if (authState.isV2 && isNearExpiry(authState) && now - lastProactiveRefreshAttemptMs > 60_000L) {
            lastProactiveRefreshAttemptMs = now
            refreshAccessToken(authState.accessToken)
        }
        val token = authState.accessToken?.takeIf { it.isNotBlank() && !authState.needsReconnect } ?: return null
        return mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
            "User-Agent" to userAgent,
        )
    }

    /** Headers for SIMKL's public, edge-cached catalog endpoints: no Authorization on purpose. */
    fun publicHeaders(): Map<String, String> = mapOf("User-Agent" to userAgent)

    /**
     * Called by [simklRequest] on a 401 that carried [tokenUsed]. Returns true when a different,
     * usable token is now held and the request is worth one retry.
     */
    suspend fun recoverFromUnauthorized(tokenUsed: String?): Boolean {
        if (tokenUsed.isNullOrBlank() || !authState.isV2) return false
        return refreshAccessToken(tokenUsed)
    }

    /**
     * Fetches the activities endpoint — must be called before any /sync/all-items request.
     * Returns null if unauthenticated or the request fails.
     *
     * Successful answers are held for [ACTIVITIES_BURST_WINDOW_MS]. This is a burst collapser, not
     * a cache: one Continue Watching pull asks three times over — the watching-seed gate, the
     * watched-history gate and the library each check the stamp within a second of each other — and
     * the window is far shorter than any interval those gates are polled on, so nothing that could
     * have observed a change is hidden from it. The mutex makes a concurrent trio share one request
     * rather than race to issue three.
     */
    suspend fun fetchActivities(): SimklActivities? {
        if (!hasUsableToken()) return null
        return activitiesMutex.withLock {
            val now = System.currentTimeMillis()
            cachedActivities?.takeIf { now - cachedActivitiesAtMs < ACTIVITIES_BURST_WINDOW_MS }
                ?.let { return@withLock it }
            val url = appendParams("$BASE_URL/sync/activities")
            runCatching {
                val resp = simklRequest(method = "GET", url = url)
                if (resp.status !in 200..299) return@withLock null
                json.decodeFromString<SimklActivities>(resp.body)
            }.onFailure {
                if (it is CancellationException) throw it
                log.w(it) { "SIMKL /sync/activities failed" }
            }.getOrNull()
                ?.also { activities ->
                    cachedActivities = activities
                    cachedActivitiesAtMs = now
                    val settingsStamp = activities.settings?.all
                    if (!settingsStamp.isNullOrBlank() && settingsStamp != authState.settingsActivitiesAt) {
                        refreshUserSettings(settingsStamp)
                    }
                }
        }
    }

    private fun clearActivitiesCache() {
        cachedActivities = null
        cachedActivitiesAtMs = 0L
    }

    /**
     * Appends SIMKL's required `client_id`, `app-name` and `app-version` to any API URL.
     *
     * While connected the client_id is the one that minted the token — a token only works with its
     * own registration — so a user can paste a new V2 Client ID into settings without breaking the
     * V1 connection that is still running.
     */
    fun appendParams(url: String): String {
        val connector = if ('?' in url) '&' else '?'
        val clientId = activeClientId()
        return "$url${connector}client_id=${simklUrlEncode(clientId)}&${appIdentificationParams()}"
    }

    private fun appIdentificationParams(): String =
        "app-name=$SIMKL_APP_NAME&app-version=${simklUrlEncode(AppVersionPolicy.displayVersionName)}"

    private fun activeClientId(): String =
        authState.clientId?.takeIf { authState.isAuthenticated && it.isNotBlank() }
            ?: SimklSettingsRepository.clientId()

    // ── Device flow (RFC 8628) ─────────────────────────────────────────────────

    /**
     * Starts an AUTH V2 sign-in. A live connection (a legacy V1 token, or a V2 grant SIMKL stopped
     * refreshing) keeps serving requests until the new grant arrives, so cancelling a reconnect
     * costs the user nothing.
     */
    fun onConnectRequested() {
        val clientId = SimklSettingsRepository.clientId()
        if (clientId.isBlank()) {
            _uiState.value = baseUiState().copy(
                errorMessage = "Enter your SIMKL Client ID and save it before connecting.",
            )
            return
        }
        pinPollJob?.cancel()
        _uiState.value = baseUiState().copy(isLoading = true, isReconnecting = authState.isAuthenticated)
        pinPollJob = scope.launch {
            val device = requestDeviceCode(clientId) ?: return@launch
            val expiresAtMs = System.currentTimeMillis() + device.expiresIn * 1000L
            _uiState.value = baseUiState().copy(
                mode = SimklConnectionMode.AWAITING_PIN,
                pendingPin = device.userCode,
                pendingVerificationUrl = device.verificationUriComplete ?: device.verificationUri,
                pendingExpiresAtMs = expiresAtMs,
                isReconnecting = authState.isAuthenticated,
            )
            pollForToken(clientId, device, expiresAtMs)
        }
    }

    /** Abandons a sign-in in progress. Unlike [onDisconnectRequested], an existing connection stays. */
    fun onConnectCancelled() {
        pinPollJob?.cancel()
        pinPollJob = null
        publishState()
    }

    fun onDisconnectRequested() {
        pinPollJob?.cancel()
        pinPollJob = null
        val previous = authState
        authState = SimklAuthState()
        clearActivitiesCache()
        SimklAuthStorage.clearPayload()
        SimklRewatchRepository.clearLocalState()
        SimklWatchedRepository.clearLocalState()
        SimklProgressRepository.clearLocalState()
        SimklLibraryRepository.clearLocalState()
        SimklDeletionCheck.clear()
        publishState()
        if (previous.isV2) scope.launch { revoke(previous) }
    }

    private suspend fun requestDeviceCode(clientId: String): SimklDeviceCodeResponse? {
        val failure = runCatching {
            val response = httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/oauth2/device?${appIdentificationParams()}",
                headers = oauthHeaders(),
                body = formEncode(mapOf("client_id" to clientId, "scope" to SIMKL_REQUESTED_SCOPE)),
            )
            if (response.status in 200..299) {
                return json.decodeFromString<SimklDeviceCodeResponse>(response.body)
            }
            val error = parseTokenError(response.body)
            log.w { "Device code request failed: ${response.status} ${error ?: ""}" }
            if (response.status == 401 || error == "invalid_client" || error == "unauthorized_client") {
                V1_CLIENT_ID_MESSAGE
            } else {
                "SIMKL refused the sign-in request (${response.status}). Try again in a moment."
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            log.w(it) { "Device code request exception" }
            "Couldn't reach SIMKL. Check your connection."
        }
        _uiState.value = baseUiState().copy(errorMessage = failure)
        return null
    }

    private suspend fun pollForToken(clientId: String, device: SimklDeviceCodeResponse, expiresAtMs: Long) {
        var intervalMs = (device.interval * 1000L).coerceAtLeast(5_000L)
        // SIMKL records no "deny" — a user who clicks No just leaves us at authorization_pending —
        // so this deadline is the only thing that ends the loop in that case.
        while (System.currentTimeMillis() + intervalMs < expiresAtMs) {
            delay(intervalMs)
            val response = runCatching {
                httpRequestRaw(
                    method = "POST",
                    url = "$BASE_URL/oauth2/token?${appIdentificationParams()}",
                    headers = oauthHeaders(),
                    body = formEncode(
                        mapOf(
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                            "client_id" to clientId,
                            "device_code" to device.deviceCode,
                        ),
                    ),
                )
            }.onFailure {
                if (it is CancellationException) throw it
                log.w(it) { "Device token poll exception" }
            }.getOrNull() ?: continue

            val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }.getOrNull()
            if (response.status in 200..299 && !token?.accessToken.isNullOrBlank()) {
                completeSignIn(clientId, token!!)
                return
            }
            when (token?.error) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMs += SLOW_DOWN_STEP_MS
                "expired_token" -> break
                "invalid_client", "unauthorized_client" -> {
                    _uiState.value = baseUiState().copy(errorMessage = V1_CLIENT_ID_MESSAGE)
                    return
                }
                else -> if (response.status !in setOf(429, 500, 502, 503)) {
                    log.w { "Device token poll failed: ${response.status} ${token?.error}" }
                    _uiState.value = baseUiState().copy(
                        errorMessage = "SIMKL sign-in failed (${token?.error ?: response.status}). Please try again.",
                    )
                    return
                }
            }
        }
        _uiState.value = baseUiState().copy(errorMessage = "The SIMKL code expired. Please try connecting again.")
    }

    private suspend fun completeSignIn(clientId: String, token: SimklTokenResponse) {
        val accessToken = token.accessToken ?: return
        val issued = SimklAuthState(
            accessToken = accessToken,
            refreshToken = token.refreshToken,
            accessTokenExpiresAtMs = token.expiresIn?.let { System.currentTimeMillis() + it * 1000L },
            scope = token.scope,
            clientId = clientId,
        )
        // An unrecognised or missing scope silently downgrades to read-only; catch it here rather
        // than as a 403 on the first scrobble.
        if (token.scope?.split(' ')?.contains("media:write") != true) {
            log.w { "SIMKL granted scope '${token.scope}', expected media:write" }
            revoke(issued)
            _uiState.value = baseUiState().copy(
                errorMessage = "SIMKL granted read-only access, so scrobbling would fail. Please try connecting again.",
            )
            return
        }
        val previous = authState
        val settings = fetchUserSettings(issued)
        authState = issued.copy(
            username = settings?.user?.name,
            accountType = settings?.account?.type?.lowercase(),
        )
        clearActivitiesCache()
        persist()
        publishState()
        if (previous.isAuthenticated && previous.username != null && authState.username != null &&
            !previous.username.equals(authState.username, ignoreCase = true)
        ) {
            log.w { "SIMKL reconnected as a different account (${previous.username} → ${authState.username})" }
        }
        // A grant SIMKL stopped refreshing is dead weight in the user's Connected Apps list.
        if (previous.isV2 && previous.accessToken != authState.accessToken) revoke(previous)
        if (authState.accountType == "pro" || authState.accountType == "vip") {
            if (SimklSettingsRepository.isRewatchTrackingEnabled()) {
                SimklRewatchRepository.refreshAsync()
            }
        }
    }

    // ── Refresh / revoke ───────────────────────────────────────────────────────

    private fun isNearExpiry(state: SimklAuthState): Boolean {
        val expiresAt = state.accessTokenExpiresAtMs ?: return false
        return System.currentTimeMillis() >= expiresAt - REFRESH_AHEAD_MS
    }

    /**
     * Replaces [staleToken] with a fresh access token. Returns true when the token now held
     * differs from [staleToken] and is usable.
     */
    private suspend fun refreshAccessToken(staleToken: String?): Boolean = refreshMutex.withLock {
        // Someone — a concurrent caller, or another Nuvio instance sharing this grant — may have
        // refreshed already. Adopt that token instead of refreshing again and invalidating it.
        if (authState.accessToken != staleToken) return@withLock hasUsableToken()
        val stored = readStoredState()
        if (stored.isV2 && stored.accessToken != staleToken && !stored.needsReconnect) {
            authState = authState.copy(
                accessToken = stored.accessToken,
                refreshToken = stored.refreshToken ?: authState.refreshToken,
                accessTokenExpiresAtMs = stored.accessTokenExpiresAtMs,
            )
            return@withLock true
        }
        val refreshToken = authState.refreshToken?.takeIf { it.isNotBlank() }
        val clientId = authState.clientId?.takeIf { it.isNotBlank() }
        if (refreshToken == null || clientId == null) {
            markNeedsReconnect("no refresh token")
            return@withLock false
        }
        val response = runCatching {
            httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/oauth2/token?${appIdentificationParams()}",
                headers = oauthHeaders(),
                body = formEncode(
                    mapOf(
                        "grant_type" to "refresh_token",
                        "client_id" to clientId,
                        "refresh_token" to refreshToken,
                    ),
                ),
            )
        }.onFailure {
            if (it is CancellationException) throw it
            log.w(it) { "SIMKL token refresh failed to send" }
        }.getOrNull() ?: return@withLock false

        val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }.getOrNull()
        val accessToken = token?.accessToken
        if (response.status in 200..299 && !accessToken.isNullOrBlank()) {
            authState = authState.copy(
                accessToken = accessToken,
                // Non-rotating: SIMKL echoes the same refresh token and slides its window.
                refreshToken = token.refreshToken ?: refreshToken,
                accessTokenExpiresAtMs = token.expiresIn?.let { System.currentTimeMillis() + it * 1000L },
                scope = token.scope ?: authState.scope,
            )
            persist()
            log.i { "SIMKL access token refreshed" }
            return@withLock true
        }
        // invalid_grant / invalid_client: revoked from Connected Apps, or the 180-day window lapsed.
        // Retrying cannot help. Anything else (5xx, 429) is transient — keep the token we have.
        if (response.status in 400..401 && token?.error in setOf("invalid_grant", "invalid_client", "unauthorized_client")) {
            markNeedsReconnect(token?.error ?: response.status.toString())
        } else {
            log.w { "SIMKL token refresh failed: ${response.status} ${token?.error}" }
        }
        false
    }

    private fun markNeedsReconnect(reason: String) {
        if (authState.needsReconnect) return
        log.w { "SIMKL connection needs reconnecting: $reason" }
        // Keep the account, username and every cached list — losing a token is not losing the
        // account. Requests stop until the user signs in again.
        authState = authState.copy(needsReconnect = true)
        clearActivitiesCache()
        persist()
        publishState()
    }

    /** RFC 7009. Always 200, so there is nothing to check — drop our copy and move on. */
    private suspend fun revoke(state: SimklAuthState) {
        val token = state.refreshToken ?: state.accessToken ?: return
        val clientId = state.clientId?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            httpRequestRaw(
                method = "POST",
                url = "$BASE_URL/oauth2/revoke?${appIdentificationParams()}",
                headers = oauthHeaders(),
                body = formEncode(mapOf("client_id" to clientId, "token" to token)),
            )
        }.onFailure {
            if (it is CancellationException) throw it
            log.w(it) { "SIMKL token revoke failed" }
        }
    }

    private fun oauthHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/x-www-form-urlencoded",
        "Accept" to "application/json",
        "User-Agent" to userAgent,
    )

    private fun parseTokenError(body: String): String? =
        runCatching { json.decodeFromString<SimklTokenResponse>(body).error }.getOrNull()

    // ── Account ────────────────────────────────────────────────────────────────

    suspend fun refreshUserSettings(settingsActivitiesAt: String? = authState.settingsActivitiesAt): Boolean {
        if (!hasUsableToken()) return false
        val settings = fetchUserSettings(authState) ?: return false
        authState = authState.copy(
            username = settings.user?.name ?: authState.username,
            accountType = settings.account?.type?.lowercase() ?: authState.accountType,
            settingsActivitiesAt = settingsActivitiesAt,
        )
        persist()
        publishState()
        return true
    }

    /** `GET /users/settings` — a read, so it stays within `media:read` and carries no body. */
    private suspend fun fetchUserSettings(state: SimklAuthState): SimklUserSettingsResponse? = runCatching {
        val token = state.accessToken?.takeIf(String::isNotBlank) ?: return@runCatching null
        val clientId = state.clientId?.takeIf(String::isNotBlank) ?: SimklSettingsRepository.clientId()
        val url = "$BASE_URL/users/settings?client_id=${simklUrlEncode(clientId)}&${appIdentificationParams()}"
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "User-Agent" to userAgent,
            ),
            body = "",
        )
        if (response.status !in 200..299) return@runCatching null
        json.decodeFromString<SimklUserSettingsResponse>(response.body)
    }.onFailure {
        if (it is CancellationException) throw it
        log.w(it) { "Failed to fetch SIMKL user settings" }
    }.getOrNull()

    // ── State ──────────────────────────────────────────────────────────────────

    private fun readStoredState(): SimklAuthState {
        val raw = SimklAuthStorage.loadPayload().orEmpty().trim()
        return if (raw.isBlank()) SimklAuthState()
        else runCatching { json.decodeFromString<SimklAuthState>(raw) }.getOrDefault(SimklAuthState())
    }

    private fun persist() {
        SimklAuthStorage.savePayload(json.encodeToString(authState))
    }

    private fun baseUiState(): SimklAuthUiState {
        val authenticated = authState.isAuthenticated
        return SimklAuthUiState(
            mode = if (authenticated) SimklConnectionMode.CONNECTED else SimklConnectionMode.DISCONNECTED,
            username = authState.username,
            accountType = authState.accountType,
            isLegacyConnection = authenticated && !authState.isV2,
            needsReconnect = authenticated && authState.needsReconnect,
        )
    }

    private fun publishState() {
        // Stays true while a reconnect is needed: consumers clear their SIMKL data on `false`, and
        // a lapsed token must leave cached lists and history on screen. Requests stop on their own,
        // because [authorizedHeaders] returns null.
        _isAuthenticated.value = authState.isAuthenticated
        _uiState.value = baseUiState()
    }

    private const val V1_CLIENT_ID_MESSAGE =
        "SIMKL rejected this Client ID. It is probably an older (AUTH V1) app — register a new app at " +
            "simkl.com/settings/developer and paste its Client ID here."
}
