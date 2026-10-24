package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Trakt connection state and token lifecycle.
 *
 * Sign-in is Trakt's device-code flow: the app shows a short code, the user approves it at
 * auth.trakt.tv/activate, and the app polls for the token. Since the 2026-10 auth migration it
 * needs only the Client ID — no client secret and no redirect URI. (Trakt's PKCE guide allows
 * https:// redirects on a domain the app owns and rules out localhost, so the browser-callback
 * flow this replaced has no place in a desktop app.)
 *
 * Refresh tokens are single-use: every refresh returns a new pair and kills the old one, so two
 * concurrent refreshes would sign the user out. [refreshMutex] makes this object the only
 * refresher, and it re-reads storage first so a token another instance already rotated is adopted.
 */
object TraktAuthRepository {
    private const val API_URL = "https://api.trakt.tv"
    // Trakt: "Use the https://auth.trakt.tv hostname for all OAuth requests … not the API hostname."
    private const val AUTH_URL = "https://auth.trakt.tv"
    private const val DEFAULT_VERIFICATION_URL = "https://auth.trakt.tv/activate"
    private const val API_VERSION = "2"

    /** Trakt access tokens live 7 days; refresh a day early so a short outage costs nothing. */
    private const val REFRESH_AHEAD_SECONDS = 24L * 60L * 60L

    /** Added to the poll interval on Trakt's 429 "Slow Down". */
    private const val SLOW_DOWN_STEP_MS = 1_000L

    private val log = Logger.withTag("TraktAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()

    private val _uiState = MutableStateFlow(TraktAuthUiState())
    val uiState: StateFlow<TraktAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private var hasLoaded = false
    private var currentProfileId = ProfileRepository.activeProfileId
    private var profileGeneration = 0L
    private var authState = TraktAuthState()
    private var devicePollJob: Job? = null
    private var pendingDevice: PendingDeviceCode? = null
    private var lastProactiveRefreshAttemptMs = 0L

    private data class PendingDeviceCode(
        val userCode: String,
        val verificationUrl: String,
        val expiresAtMillis: Long,
    )

    private val userAgent: String get() = "NuvioDesktop/${AppVersionPolicy.displayVersionName}"

    fun ensureLoaded(profileId: Int = ProfileRepository.activeProfileId) {
        if (profileId != ProfileRepository.activeProfileId) return
        if (hasLoaded && currentProfileId == profileId) return
        loadFromDisk(profileId)
    }

    fun onProfileChanged(profileId: Int = ProfileRepository.activeProfileId) {
        if (profileId != ProfileRepository.activeProfileId) return
        cancelDeviceFlow()
        loadFromDisk(profileId)
    }

    fun clearLocalState() {
        cancelDeviceFlow()
        hasLoaded = false
        currentProfileId = ProfileRepository.activeProfileId
        profileGeneration += 1L
        authState = TraktAuthState()
        publish()
    }

    fun snapshot(profileId: Int = ProfileRepository.activeProfileId): TraktAuthUiState {
        ensureLoaded(profileId)
        return _uiState.value
    }

    fun hasRequiredCredentials(): Boolean =
        TraktSettingsRepository.effectiveCredentials().hasAuthCredentials

    fun onCredentialsChanged() {
        ensureLoaded()
        publish(statusMessage = null, errorMessage = null)
    }

    /**
     * Starts a device-code sign-in. An existing connection (including one that needs reconnecting)
     * keeps its cached data until the new grant arrives, so cancelling costs nothing.
     */
    fun onConnectRequested(profileId: Int = ProfileRepository.activeProfileId) {
        if (profileId != ProfileRepository.activeProfileId) return
        ensureLoaded(profileId)
        val clientId = TraktSettingsRepository.effectiveCredentials().clientId
        if (clientId.isBlank()) {
            publish(errorMessage = localizedString(Res.string.trakt_missing_credentials))
            return
        }
        val generation = profileGeneration
        cancelDeviceFlow()
        publish(isLoading = true, statusMessage = null, errorMessage = null)
        devicePollJob = scope.launch {
            val device = requestDeviceCode(clientId, profileId, generation) ?: return@launch
            if (!isCurrentProfile(profileId, generation)) return@launch
            val expiresAtMillis = TraktPlatformClock.nowEpochMs() + device.expiresIn * 1_000L
            pendingDevice = PendingDeviceCode(
                userCode = device.userCode,
                verificationUrl = device.verificationUrl.ifBlank { DEFAULT_VERIFICATION_URL },
                expiresAtMillis = expiresAtMillis,
            )
            publish(isLoading = false)
            pollForToken(clientId, device, expiresAtMillis, profileId, generation)
        }
    }

    /** Abandons a sign-in in progress. Unlike [onDisconnectRequested], a connection stays. */
    fun onCancelDeviceFlow(profileId: Int = ProfileRepository.activeProfileId) {
        if (profileId != ProfileRepository.activeProfileId) return
        cancelDeviceFlow()
        publish(isLoading = false, statusMessage = null, errorMessage = null)
    }

    fun onAuthLaunchFailed(
        reason: String,
        profileId: Int = ProfileRepository.activeProfileId,
    ) {
        if (currentProfileId != profileId) return
        publish(errorMessage = reason)
    }

    suspend fun authorizedHeaders(profileId: Int = ProfileRepository.activeProfileId): Map<String, String>? {
        if (profileId != ProfileRepository.activeProfileId) return null
        ensureLoaded(profileId)
        val generation = profileGeneration
        if (!authState.isAuthenticated || authState.needsReconnect) return null

        if (isTokenExpiring(authState)) {
            // Within a day of expiry the token still works, so a failed refresh (Trakt outage)
            // waits a minute instead of repeating on every request. A token past expiry always tries.
            val now = TraktPlatformClock.nowEpochMs()
            if (isTokenExpired(authState) || now - lastProactiveRefreshAttemptMs > 60_000L) {
                lastProactiveRefreshAttemptMs = now
                refreshAccessToken(authState.accessToken, profileId, generation)
            }
        }
        if (!isCurrentProfile(profileId, generation)) return null
        if (authState.needsReconnect || isTokenExpired(authState)) return null

        val accessToken = authState.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) return null
        val clientId = authState.clientId?.takeIf { it.isNotBlank() } ?: return null

        return mapOf(
            "trakt-api-version" to API_VERSION,
            "trakt-api-key" to clientId,
            "Authorization" to "Bearer $accessToken",
        )
    }

    suspend fun refreshUserSettings(profileId: Int = ProfileRepository.activeProfileId): String? {
        PerfTelemetry.count("TraktAuthRepository.refreshUserSettings")
        if (profileId != ProfileRepository.activeProfileId) return null
        ensureLoaded(profileId)
        return refreshUserSettingsForGeneration(profileId, profileGeneration)
    }

    private suspend fun refreshUserSettingsForGeneration(
        profileId: Int,
        generation: Long,
    ): String? {
        if (!isCurrentProfile(profileId, generation)) return null
        val headers = authorizedHeaders(profileId) ?: return null
        val response = runCatching {
            httpGetTextWithHeaders(
                url = "$API_URL/users/settings",
                headers = headers,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to fetch Trakt user settings: ${error.message}" }
        }.getOrNull() ?: return null
        if (!isCurrentProfile(profileId, generation)) return null

        val parsed = runCatching {
            json.decodeFromString<TraktUserSettingsResponse>(response)
        }.getOrNull() ?: return null
        if (!isCurrentProfile(profileId, generation)) return null

        authState = authState.copy(
            username = parsed.user?.username,
            userSlug = parsed.user?.ids?.slug,
        )
        persist(profileId)
        publish()
        return authState.username
    }

    fun onDisconnectRequested(profileId: Int = ProfileRepository.activeProfileId) {
        if (profileId != ProfileRepository.activeProfileId) return
        ensureLoaded(profileId)
        cancelDeviceFlow()
        val generation = profileGeneration
        scope.launch {
            disconnect(profileId, generation)
        }
    }

    // ── Device flow ────────────────────────────────────────────────────────────

    private suspend fun requestDeviceCode(
        clientId: String,
        profileId: Int,
        generation: Long,
    ): TraktDeviceCodeResponse? {
        val failure = runCatching {
            val response = oauthPost(
                path = "/oauth/device/code",
                body = json.encodeToString(TraktDeviceCodeRequest(clientId = clientId)),
                clientId = clientId,
            )
            if (response.status in 200..299) {
                return json.decodeFromString<TraktDeviceCodeResponse>(response.body)
            }
            log.w { "Trakt device code request failed: ${response.status}" }
            if (response.status == 401 || response.status == 403) {
                INVALID_CLIENT_ID_MESSAGE
            } else {
                "Trakt refused the sign-in request (${response.status}). Try again in a moment."
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            log.w(it) { "Trakt device code request exception" }
            "Couldn't reach Trakt. Check your connection."
        }
        if (isCurrentProfile(profileId, generation)) {
            publish(isLoading = false, errorMessage = failure)
        }
        return null
    }

    private suspend fun pollForToken(
        clientId: String,
        device: TraktDeviceCodeResponse,
        expiresAtMillis: Long,
        profileId: Int,
        generation: Long,
    ) {
        var intervalMs = (device.interval * 1_000L).coerceAtLeast(1_000L)
        while (TraktPlatformClock.nowEpochMs() + intervalMs < expiresAtMillis) {
            delay(intervalMs)
            if (!isCurrentProfile(profileId, generation)) return
            val response = runCatching {
                oauthPost(
                    path = "/oauth/device/token",
                    body = json.encodeToString(
                        TraktDeviceTokenRequest(code = device.deviceCode, clientId = clientId),
                    ),
                    clientId = clientId,
                )
            }.onFailure {
                if (it is CancellationException) throw it
                log.w(it) { "Trakt device token poll exception" }
            }.getOrNull() ?: continue
            if (!isCurrentProfile(profileId, generation)) return

            // Trakt signals each device-poll outcome by status code alone.
            when (response.status) {
                200 -> {
                    val token = runCatching {
                        json.decodeFromString<TraktTokenResponse>(response.body)
                    }.getOrNull()
                    if (token == null) {
                        finishDeviceFlow(localizedString(Res.string.trakt_invalid_token_response))
                    } else {
                        completeSignIn(clientId, token, profileId, generation)
                    }
                    return
                }
                400 -> Unit // pending: the user has not approved yet
                429 -> intervalMs += SLOW_DOWN_STEP_MS
                404 -> return finishDeviceFlow("Trakt didn't recognise the sign-in code. Please try connecting again.")
                409 -> return finishDeviceFlow("That code was already used. Please try connecting again.")
                410 -> return finishDeviceFlow(CODE_EXPIRED_MESSAGE)
                418 -> return finishDeviceFlow("Sign-in was declined on Trakt.")
                else -> log.w { "Trakt device token poll: ${response.status}" } // transient; keep polling
            }
        }
        if (isCurrentProfile(profileId, generation)) finishDeviceFlow(CODE_EXPIRED_MESSAGE)
    }

    private fun finishDeviceFlow(errorMessage: String) {
        pendingDevice = null
        devicePollJob = null
        publish(isLoading = false, errorMessage = errorMessage)
    }

    private suspend fun completeSignIn(
        clientId: String,
        token: TraktTokenResponse,
        profileId: Int,
        generation: Long,
    ) {
        val previous = authState
        pendingDevice = null
        devicePollJob = null
        authState = TraktAuthState(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            tokenType = token.tokenType,
            createdAt = token.createdAt,
            expiresIn = token.expiresIn,
            clientId = clientId,
            // Kept until /users/settings answers, so a reconnect never flashes "Trakt user".
            username = previous.username,
            userSlug = previous.userSlug,
        )
        persist(profileId)
        publish(isLoading = true, errorMessage = null)
        refreshUserSettingsForGeneration(profileId, generation)
        if (!isCurrentProfile(profileId, generation)) return
        publish(
            isLoading = false,
            statusMessage = localizedString(Res.string.trakt_connected_status),
            errorMessage = null,
        )
        // A dead grant left behind by a reconnect is clutter in the user's Connected Apps.
        if (previous.isAuthenticated && previous.accessToken != authState.accessToken) {
            revoke(previous)
        }
    }

    private fun cancelDeviceFlow() {
        devicePollJob?.cancel()
        devicePollJob = null
        pendingDevice = null
    }

    // ── Refresh / revoke ───────────────────────────────────────────────────────

    /**
     * Replaces [staleToken] with a fresh access token. Returns true when the token now held differs
     * from [staleToken] and is usable.
     */
    private suspend fun refreshAccessToken(
        staleToken: String?,
        profileId: Int,
        generation: Long,
    ): Boolean = refreshMutex.withLock {
        if (!isCurrentProfile(profileId, generation)) return@withLock false
        // A concurrent caller already rotated it: the old refresh token is now dead, so refreshing
        // again would fail with invalid_grant and look like a revoked connection.
        if (authState.accessToken != staleToken) return@withLock !authState.needsReconnect
        val stored = readStoredState(profileId)
        if (stored.isAuthenticated && stored.accessToken != staleToken && !stored.needsReconnect) {
            authState = stored
            publish()
            return@withLock true
        }

        val refreshToken = authState.refreshToken?.takeIf { it.isNotBlank() }
        val clientId = authState.clientId?.takeIf { it.isNotBlank() }
        if (refreshToken == null || clientId == null) {
            markNeedsReconnect(profileId, "no refresh token or client id")
            return@withLock false
        }

        val response = runCatching {
            oauthPost(
                path = "/oauth/token",
                body = json.encodeToString(
                    TraktRefreshTokenRequest(refreshToken = refreshToken, clientId = clientId),
                ),
                clientId = clientId,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Trakt token refresh failed to send: ${error.message}" }
        }.getOrNull() ?: return@withLock false
        if (!isCurrentProfile(profileId, generation)) return@withLock false

        if (response.status in 200..299) {
            val parsed = runCatching {
                json.decodeFromString<TraktTokenResponse>(response.body)
            }.getOrNull() ?: return@withLock false
            authState = authState.copy(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                tokenType = parsed.tokenType,
                createdAt = parsed.createdAt,
                expiresIn = parsed.expiresIn,
            )
            persist(profileId)
            publish()
            log.i { "Trakt access token refreshed" }
            return@withLock true
        }

        // invalid_grant: revoked in Trakt's Connected Apps, or a pre-migration refresh token
        // ("session not found"). Retrying cannot help. 5xx / 429 are transient — keep the token.
        val error = runCatching { json.decodeFromString<TraktOAuthError>(response.body) }.getOrNull()
        if (response.status in 400..401 &&
            error?.error in setOf("invalid_grant", "invalid_client", "unauthorized_client")
        ) {
            markNeedsReconnect(profileId, "${error?.error}: ${error?.errorDescription}")
        } else {
            log.w { "Trakt token refresh failed: ${response.status} ${error?.error}" }
        }
        false
    }

    private fun markNeedsReconnect(profileId: Int, reason: String) {
        if (authState.needsReconnect) return
        log.w { "Trakt connection needs reconnecting: $reason" }
        // Keep the account and every cached list: losing a token is not losing the account.
        authState = authState.copy(needsReconnect = true)
        persist(profileId)
        publish()
    }

    private suspend fun disconnect(profileId: Int, generation: Long) {
        if (!isCurrentProfile(profileId, generation)) return
        publish(isLoading = true, errorMessage = null)
        val previous = authState
        authState = TraktAuthState()
        persist(profileId)
        publish(
            isLoading = false,
            statusMessage = localizedString(Res.string.trakt_disconnected_status),
            errorMessage = null,
        )
        revoke(previous)
    }

    /** Optional per Trakt, but it keeps the user's Connected Apps list honest. */
    private suspend fun revoke(state: TraktAuthState) {
        val token = state.accessToken?.takeIf { it.isNotBlank() } ?: return
        val clientId = state.clientId?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            oauthPost(
                path = "/oauth/revoke",
                body = json.encodeToString(TraktRevokeRequest(token = token, clientId = clientId)),
                clientId = clientId,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to revoke Trakt token: ${error.message}" }
        }
    }

    private suspend fun oauthPost(path: String, body: String, clientId: String): RawHttpResponse =
        httpRequestRaw(
            method = "POST",
            url = "$AUTH_URL$path",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "trakt-api-version" to API_VERSION,
                "trakt-api-key" to clientId,
                "User-Agent" to userAgent,
            ),
            body = body,
        )

    // ── State ──────────────────────────────────────────────────────────────────

    private fun loadFromDisk(profileId: Int) {
        profileGeneration += 1L
        currentProfileId = profileId
        hasLoaded = true
        authState = readStoredState(profileId)
        if (authState.isAuthenticated && authState.clientId.isNullOrBlank() && !authState.needsReconnect) {
            // Minted before Trakt's 2026-10 auth migration (and under an API key Trakt revoked):
            // Trakt no longer exchanges these refresh tokens, so only a fresh sign-in helps.
            authState = authState.copy(needsReconnect = true)
            persist(profileId)
        }
        publish(statusMessage = null, errorMessage = null)
    }

    private fun readStoredState(profileId: Int): TraktAuthState {
        val payload = TraktAuthStorage.loadPayload(profileId).orEmpty().trim()
        if (payload.isBlank()) return TraktAuthState()
        return runCatching { json.decodeFromString<TraktAuthState>(payload) }
            .getOrElse {
                log.w { "Failed to parse Trakt auth payload: ${it.message}" }
                TraktAuthState()
            }
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        statusMessage: String? = _uiState.value.statusMessage,
        errorMessage: String? = _uiState.value.errorMessage,
    ) {
        val tokenExpiresAtMillis = authState.createdAt
            ?.let { createdAtSeconds ->
                authState.expiresIn?.let { expiresInSeconds ->
                    (createdAtSeconds + expiresInSeconds) * 1_000L
                }
            }

        val device = pendingDevice
        // A reconnect stays CONNECTED (with a pending code) so screens keyed on the mode keep their
        // Trakt features while the user approves.
        val mode = when {
            authState.isAuthenticated -> TraktConnectionMode.CONNECTED
            device != null -> TraktConnectionMode.AWAITING_APPROVAL
            else -> TraktConnectionMode.DISCONNECTED
        }

        // Stays true while a reconnect is needed: consumers clear their Trakt data on `false`, and
        // a lapsed token must leave cached lists on screen. Requests stop on their own, because
        // [authorizedHeaders] returns null.
        _isAuthenticated.value = authState.isAuthenticated
        _uiState.value = TraktAuthUiState(
            mode = mode,
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = authState.username,
            tokenExpiresAtMillis = tokenExpiresAtMillis,
            needsReconnect = authState.isAuthenticated && authState.needsReconnect,
            pendingUserCode = device?.userCode,
            pendingVerificationUrl = device?.verificationUrl,
            pendingExpiresAtMillis = device?.expiresAtMillis,
            statusMessage = statusMessage,
            errorMessage = errorMessage,
        )
    }

    private fun persist(profileId: Int = currentProfileId) {
        if (currentProfileId != profileId) return
        TraktAuthStorage.savePayload(profileId, json.encodeToString(authState))
    }

    private fun isCurrentProfile(profileId: Int, generation: Long): Boolean =
        currentProfileId == profileId &&
            profileGeneration == generation &&
            ProfileRepository.activeProfileId == profileId

    private fun tokenExpiresAtSeconds(state: TraktAuthState): Long? {
        val createdAt = state.createdAt ?: return null
        val expiresIn = state.expiresIn ?: return null
        return createdAt + expiresIn
    }

    private fun isTokenExpiring(state: TraktAuthState): Boolean {
        val expiresAt = tokenExpiresAtSeconds(state) ?: return true
        return TraktPlatformClock.nowEpochMs() / 1_000L >= expiresAt - REFRESH_AHEAD_SECONDS
    }

    private fun isTokenExpired(state: TraktAuthState): Boolean {
        val expiresAt = tokenExpiresAtSeconds(state) ?: return true
        return TraktPlatformClock.nowEpochMs() / 1_000L >= expiresAt - 60
    }

    private const val CODE_EXPIRED_MESSAGE = "The Trakt code expired. Please try connecting again."
    private const val INVALID_CLIENT_ID_MESSAGE =
        "Trakt rejected this Client ID. Copy it again from app.trakt.tv/settings/apps and save it here."
}

@Serializable
private data class TraktDeviceCodeRequest(
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class TraktDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String = "",
    @SerialName("expires_in") val expiresIn: Long = 600,
    @SerialName("interval") val interval: Long = 5,
)

@Serializable
private data class TraktDeviceTokenRequest(
    @SerialName("code") val code: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class TraktRefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
private data class TraktRevokeRequest(
    @SerialName("token") val token: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
private data class TraktTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
private data class TraktOAuthError(
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
private data class TraktUserSettingsResponse(
    val user: TraktUserDto? = null,
)

@Serializable
private data class TraktUserDto(
    val username: String? = null,
    val ids: TraktUserIdsDto? = null,
)

@Serializable
private data class TraktUserIdsDto(
    val slug: String? = null,
)

private fun localizedString(resource: StringResource): String = runBlocking { getString(resource) }
