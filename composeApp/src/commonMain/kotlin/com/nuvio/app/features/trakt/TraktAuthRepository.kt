package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.profiles.ProfileRepository
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.runBlocking

object TraktAuthRepository {
    private const val BASE_URL = "https://api.trakt.tv"
    private const val AUTHORIZE_URL = "https://trakt.tv/oauth/authorize"
    private const val API_VERSION = "2"

    private val log = Logger.withTag("TraktAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TraktAuthUiState())
    val uiState: StateFlow<TraktAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private var hasLoaded = false
    private var currentProfileId = ProfileRepository.activeProfileId
    private var profileGeneration = 0L
    private var authState = TraktAuthState()

    fun ensureLoaded(profileId: Int = ProfileRepository.activeProfileId) {
        if (profileId != ProfileRepository.activeProfileId) return
        if (hasLoaded && currentProfileId == profileId) return
        loadFromDisk(profileId)
    }

    fun onProfileChanged(profileId: Int = ProfileRepository.activeProfileId) {
        if (profileId != ProfileRepository.activeProfileId) return
        TraktAuthCallbackServer.stop()
        loadFromDisk(profileId)
    }

    fun clearLocalState() {
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

    fun onConnectRequested(profileId: Int = ProfileRepository.activeProfileId): String? {
        if (profileId != ProfileRepository.activeProfileId) return null
        ensureLoaded(profileId)
        if (!hasRequiredCredentials()) {
            publish(errorMessage = localizedString(Res.string.trakt_missing_credentials))
            return null
        }

        val oauthState = generateOauthState()
        authState = authState.copy(
            pendingAuthorizationState = oauthState,
            pendingAuthorizationStartedAtMillis = TraktPlatformClock.nowEpochMs(),
        )
        persist(profileId)
        publish(
            statusMessage = localizedString(Res.string.trakt_complete_sign_in_browser),
            errorMessage = null,
        )

        TraktAuthCallbackServer.ensureStarted()
        return buildAuthorizationUrl(oauthState)
    }

    fun pendingAuthorizationUrl(profileId: Int = ProfileRepository.activeProfileId): String? {
        if (profileId != ProfileRepository.activeProfileId) return null
        ensureLoaded(profileId)
        val oauthState = authState.pendingAuthorizationState ?: return null
        TraktAuthCallbackServer.ensureStarted()
        return buildAuthorizationUrl(oauthState)
    }

    fun onCancelAuthorization(profileId: Int = ProfileRepository.activeProfileId) {
        if (profileId != ProfileRepository.activeProfileId) return
        ensureLoaded(profileId)
        TraktAuthCallbackServer.stop()
        clearPendingAuthorization()
        persist(profileId)
        publish(statusMessage = null, errorMessage = null)
    }

    fun onCancelDeviceFlow(profileId: Int = ProfileRepository.activeProfileId) {
        onCancelAuthorization(profileId)
    }

    fun onAuthLaunchFailed(
        reason: String,
        profileId: Int = ProfileRepository.activeProfileId,
    ) {
        if (currentProfileId != profileId) return
        publish(errorMessage = reason)
    }

    fun onAuthCallbackReceived(callbackUrl: String) {
        val profileId = ProfileRepository.activeProfileId
        ensureLoaded(profileId)
        val generation = profileGeneration
        val redirectUri = TraktSettingsRepository.effectiveCredentials().redirectUri
        if (!callbackUrl.startsWith("$redirectUri?", ignoreCase = true) &&
            !callbackUrl.equals(redirectUri, ignoreCase = true)
        ) {
            return
        }

        scope.launch {
            completeAuthorizationFromCallback(callbackUrl, profileId, generation)
        }
    }

    suspend fun authorizedHeaders(profileId: Int = ProfileRepository.activeProfileId): Map<String, String>? {
        if (profileId != ProfileRepository.activeProfileId) return null
        ensureLoaded(profileId)
        val generation = profileGeneration
        if (!authState.isAuthenticated) return null

        val hasValidToken = refreshTokenIfNeeded(
            force = false,
            profileId = profileId,
            generation = generation,
        )
        if (!hasValidToken) return null
        if (!isCurrentProfile(profileId, generation)) return null

        val accessToken = authState.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) return null
        val credentials = TraktSettingsRepository.effectiveCredentials()
        if (!credentials.hasClientId) return null

        return mapOf(
            "trakt-api-version" to API_VERSION,
            "trakt-api-key" to credentials.clientId,
            "Authorization" to "Bearer $accessToken",
        )
    }

    suspend fun refreshUserSettings(profileId: Int = ProfileRepository.activeProfileId): String? {
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
                url = "$BASE_URL/users/settings",
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
        val generation = profileGeneration
        scope.launch {
            disconnect(profileId, generation)
        }
    }

    private suspend fun completeAuthorizationFromCallback(
        callbackUrl: String,
        profileId: Int,
        generation: Long,
    ) {
        if (!isCurrentProfile(profileId, generation)) return
        TraktAuthCallbackServer.stop()
        publish(isLoading = true, errorMessage = null)

        val parsedUrl = runCatching { Url(callbackUrl) }
            .onFailure {
                log.w { "Invalid Trakt callback URL: ${it.message}" }
            }
            .getOrNull()

        if (parsedUrl == null) {
            if (!isCurrentProfile(profileId, generation)) return
            clearPendingAuthorization()
            persist(profileId)
            publish(
                isLoading = false,
                errorMessage = localizedString(Res.string.trakt_invalid_callback),
            )
            return
        }

        val errorCode = parsedUrl.parameters["error"]
        if (!errorCode.isNullOrBlank()) {
            val errorDescription = parsedUrl.parameters["error_description"]
                ?: localizedString(Res.string.trakt_authorization_denied)
            if (!isCurrentProfile(profileId, generation)) return
            clearPendingAuthorization()
            persist(profileId)
            publish(
                isLoading = false,
                errorMessage = errorDescription,
            )
            return
        }

        val code = parsedUrl.parameters["code"].orEmpty().trim()
        if (code.isBlank()) {
            if (!isCurrentProfile(profileId, generation)) return
            clearPendingAuthorization()
            persist(profileId)
            publish(
                isLoading = false,
                errorMessage = localizedString(Res.string.trakt_missing_auth_code),
            )
            return
        }

        val expectedState = authState.pendingAuthorizationState
        val callbackState = parsedUrl.parameters["state"].orEmpty().trim()
        if (!expectedState.isNullOrBlank() && callbackState != expectedState) {
            if (!isCurrentProfile(profileId, generation)) return
            clearPendingAuthorization()
            persist(profileId)
            publish(
                isLoading = false,
                errorMessage = localizedString(Res.string.trakt_invalid_callback_state),
            )
            return
        }

        exchangeAuthorizationCode(code, profileId, generation)
    }

    private suspend fun exchangeAuthorizationCode(
        code: String,
        profileId: Int,
        generation: Long,
    ) {
        if (!isCurrentProfile(profileId, generation)) return
        val credentials = TraktSettingsRepository.effectiveCredentials()
        val body = json.encodeToString(
            TraktAuthorizationCodeRequest(
                code = code,
                clientId = credentials.clientId,
                clientSecret = credentials.clientSecret,
                redirectUri = credentials.redirectUri,
            ),
        )

        val response = runCatching {
            httpPostJsonWithHeaders(
                url = "$BASE_URL/oauth/token",
                body = body,
                headers = emptyMap(),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to exchange Trakt auth code: ${error.message}" }
        }.getOrNull()
        if (!isCurrentProfile(profileId, generation)) return

        if (response == null) {
            clearPendingAuthorization()
            persist(profileId)
            publish(isLoading = false, errorMessage = localizedString(Res.string.trakt_sign_in_complete_failed))
            return
        }

        val parsed = runCatching {
            json.decodeFromString<TraktTokenResponse>(response)
        }.getOrNull()

        if (parsed == null) {
            clearPendingAuthorization()
            persist(profileId)
            publish(isLoading = false, errorMessage = localizedString(Res.string.trakt_invalid_token_response))
            return
        }

        authState = authState.copy(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            tokenType = parsed.tokenType,
            createdAt = parsed.createdAt,
            expiresIn = parsed.expiresIn,
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtMillis = null,
        )
        persist(profileId)
        refreshUserSettingsForGeneration(profileId, generation)
        if (!isCurrentProfile(profileId, generation)) return
        publish(
            isLoading = false,
            statusMessage = localizedString(Res.string.trakt_connected_status),
            errorMessage = null,
        )
    }

    private suspend fun disconnect(profileId: Int, generation: Long) {
        if (!isCurrentProfile(profileId, generation)) return
        publish(isLoading = true, errorMessage = null)

        val token = authState.accessToken?.takeIf { it.isNotBlank() }
        val credentials = TraktSettingsRepository.effectiveCredentials()
        if (!token.isNullOrBlank() && credentials.hasAuthCredentials) {
            val body = json.encodeToString(
                TraktRevokeRequest(
                    token = token,
                    clientId = credentials.clientId,
                    clientSecret = credentials.clientSecret,
                ),
            )
            runCatching {
                httpPostJsonWithHeaders(
                    url = "$BASE_URL/oauth/revoke",
                    body = body,
                    headers = emptyMap(),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Failed to revoke Trakt token: ${error.message}" }
            }
        }
        if (!isCurrentProfile(profileId, generation)) return

        authState = TraktAuthState()
        persist(profileId)
        publish(
            isLoading = false,
            statusMessage = localizedString(Res.string.trakt_disconnected_status),
            errorMessage = null,
        )
    }

    private suspend fun refreshTokenIfNeeded(
        force: Boolean,
        profileId: Int,
        generation: Long,
    ): Boolean {
        if (!isCurrentProfile(profileId, generation)) return false
        val credentials = TraktSettingsRepository.effectiveCredentials()
        if (!credentials.hasAuthCredentials) return false
        val refreshToken = authState.refreshToken?.takeIf { it.isNotBlank() } ?: return false

        if (!force && !isTokenExpiredOrExpiring(authState)) {
            return true
        }

        val body = json.encodeToString(
            TraktRefreshTokenRequest(
                refreshToken = refreshToken,
                clientId = credentials.clientId,
                clientSecret = credentials.clientSecret,
                redirectUri = credentials.redirectUri,
            ),
        )

        val response = runCatching {
            httpPostJsonWithHeaders(
                url = "$BASE_URL/oauth/token",
                body = body,
                headers = emptyMap(),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Trakt token refresh failed: ${error.message}" }
        }.getOrNull() ?: return false
        if (!isCurrentProfile(profileId, generation)) return false

        val parsed = runCatching {
            json.decodeFromString<TraktTokenResponse>(response)
        }.getOrNull() ?: return false
        if (!isCurrentProfile(profileId, generation)) return false

        authState = authState.copy(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            tokenType = parsed.tokenType,
            createdAt = parsed.createdAt,
            expiresIn = parsed.expiresIn,
        )
        persist(profileId)
        publish()
        return true
    }

    private fun loadFromDisk(profileId: Int) {
        profileGeneration += 1L
        currentProfileId = profileId
        hasLoaded = true
        val payload = TraktAuthStorage.loadPayload(profileId).orEmpty().trim()
        authState = if (payload.isBlank()) {
            TraktAuthState()
        } else {
            runCatching { json.decodeFromString<TraktAuthState>(payload) }
                .getOrElse {
                    log.w { "Failed to parse Trakt auth payload: ${it.message}" }
                    TraktAuthState()
                }
        }
        publish(statusMessage = null, errorMessage = null)
    }

    private fun clearPendingAuthorization() {
        authState = authState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtMillis = null,
        )
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

        val mode = when {
            authState.isAuthenticated -> TraktConnectionMode.CONNECTED
            !authState.pendingAuthorizationState.isNullOrBlank() -> TraktConnectionMode.AWAITING_APPROVAL
            else -> TraktConnectionMode.DISCONNECTED
        }

        _isAuthenticated.value = authState.isAuthenticated
        _uiState.value = TraktAuthUiState(
            mode = mode,
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = authState.username,
            tokenExpiresAtMillis = tokenExpiresAtMillis,
            pendingAuthorizationStartedAtMillis = authState.pendingAuthorizationStartedAtMillis,
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

    private fun buildAuthorizationUrl(state: String): String {
        val credentials = TraktSettingsRepository.effectiveCredentials()
        val responseType = "code"
        val encodedClientId = credentials.clientId.encodeURLParameter()
        val encodedRedirectUri = credentials.redirectUri.encodeURLParameter()
        val encodedState = state.encodeURLParameter()
        return "$AUTHORIZE_URL?response_type=$responseType&client_id=$encodedClientId&redirect_uri=$encodedRedirectUri&state=$encodedState"
    }

    private fun generateOauthState(): String {
        val nowPart = TraktPlatformClock.nowEpochMs().toString(16)
        val randomPart = Random.nextLong().toULong().toString(16)
        return "$nowPart$randomPart"
    }

    private fun isTokenExpiredOrExpiring(state: TraktAuthState): Boolean {
        val createdAt = state.createdAt ?: return true
        val expiresIn = state.expiresIn ?: return true
        val expiresAtSeconds = createdAt + expiresIn
        val nowSeconds = TraktPlatformClock.nowEpochMs() / 1_000L
        return nowSeconds >= (expiresAtSeconds - 60)
    }
}

@Serializable
private data class TraktAuthorizationCodeRequest(
    @SerialName("code") val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("grant_type") val grantType: String = "authorization_code",
)

@Serializable
private data class TraktRefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
private data class TraktRevokeRequest(
    @SerialName("token") val token: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
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
