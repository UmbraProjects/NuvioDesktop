package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var initialized = false
    private var sessionStatusJob: Job? = null

    fun initialize() {
        if (initialized) return
        initialized = true

        val savedAnonId = AuthStorage.loadAnonymousUserId()
        if (savedAnonId != null) {
            publishAuthenticated(
                AuthState.Authenticated(
                    userId = savedAnonId,
                    email = null,
                    isAnonymous = true,
                ),
            )
        }

        sessionStatusJob = scope.launch {
            SupabaseProvider.client.auth.sessionStatus.collect { status ->
                if (AuthStorage.loadAnonymousUserId() != null) return@collect
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        publishAuthenticated(
                            AuthState.Authenticated(
                                userId = user?.id ?: "",
                                email = user?.email,
                                isAnonymous = false,
                            ),
                        )
                    }
                    is SessionStatus.NotAuthenticated -> {
                        _state.value = AuthState.Unauthenticated
                    }
                    is SessionStatus.Initializing -> {
                        if (savedAnonId == null) _state.value = AuthState.Loading
                    }
                    is SessionStatus.RefreshFailure -> {
                        _state.value = AuthState.Unauthenticated
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        _error.value = null
        val userId = Uuid.random().toString()
        AuthStorage.saveAnonymousUserId(userId)
        publishAuthenticated(
            AuthState.Authenticated(
                userId = userId,
                email = null,
                isAnonymous = true,
            ),
        )
    }

    /**
     * Every sign-in goes through here. When the account already has its own settings on this PC
     * (or must not use the folder in use), the app restarts into them and this session never
     * reports the sign-in, so nothing starts syncing into the wrong account's folder.
     */
    private fun publishAuthenticated(authenticated: AuthState.Authenticated) {
        val current = _state.value
        if (current is AuthState.Authenticated && current.userId == authenticated.userId) {
            _state.value = authenticated
            return
        }
        if (PlatformAccountScope.onAuthenticated(authenticated.userId)) {
            log.i { "Restarting into the signed-in account's own settings" }
            return
        }
        _state.value = authenticated
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        Unit
    }.onFailure { e ->
        log.e(e) { "Email sign-up failed" }
        _error.value = e.safeAuthErrorDescription()
            ?: getString(Res.string.auth_sign_up_failed)
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }.onFailure { e ->
        log.e(e) { "Email sign-in failed" }
        _error.value = e.safeAuthErrorDescription()
            ?: getString(Res.string.auth_sign_in_failed)
    }

    /**
     * Ends the session. Desktop keeps this account's settings in its own folder for next time,
     * unless [erase], which removes them — sign-ins and keys included — from the PC.
     */
    suspend fun signOut(erase: Boolean = false): Result<Unit> = runCatching {
        _error.value = null
        val wasAnonymous = AuthStorage.loadAnonymousUserId() != null
        AuthStorage.clearAnonymousUserId()
        if (!wasAnonymous) {
            SupabaseProvider.client.auth.signOut()
        }
        _state.value = AuthState.Unauthenticated
        if (!PlatformAccountScope.onSignedOut(erase)) LocalAccountDataCleaner.wipe()
    }.onFailure { e ->
        log.e(e) { "Sign-out failed" }
        _error.value = e.message ?: getString(Res.string.auth_sign_out_failed)
    }

    /**
     * Drops the local session without the full sign-out wipe, so the account can be re-created
     * against a different backend.
     *
     * [signOut] leaves this account's settings folder (or erases it); a server switch must NOT, because
     * the user is moving their client between backends rather than leaving an account, and the local
     * library/progress is what they expect to push to the new server.
     */
    suspend fun prepareForServerSwitch(): Result<Unit> = runCatching {
        _error.value = null
        // Detach from the outgoing client first: SupabaseProvider.reset() closes it moments from
        // now, and a collector still attached to a closed client keeps publishing the previous
        // backend's session state.
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        AuthStorage.clearAnonymousUserId()
        SupabaseProvider.client.auth.clearSession()
        PlatformAccountScope.onServerSwitch()
        _state.value = AuthState.Unauthenticated
        log.i { "Local session cleared, ready to switch servers" }
    }.onFailure { e ->
        log.e(e) { "Failed to clear the local session before switching servers" }
    }

    /**
     * Re-runs [initialize] against whatever Supabase client is current.
     *
     * The session collector is bound to one client instance, so it has to be cancelled before
     * [SupabaseProvider.reset] hands out a new one - otherwise the old collector keeps publishing
     * the previous backend's session state.
     */
    fun reinitialize() {
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        _state.value = AuthState.Loading
        log.i { "Re-initializing auth against the active server" }
        initialize()
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.functions.invoke("delete-account")
        SupabaseProvider.client.auth.signOut()
        if (!PlatformAccountScope.onSignedOut(erase = true)) LocalAccountDataCleaner.wipe()
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
    }

    private fun Throwable.safeAuthErrorDescription(): String? {
        val description = findCause<AuthRestException>()?.errorDescription
            ?: findCause<RestException>()?.description
            ?: return null
        val normalized = description.trim().replace(Regex("\\s+"), " ")
        return normalized.takeIf {
            it.isNotBlank() &&
                it.length <= 300 &&
                !it.contains("<html", ignoreCase = true)
        }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            val next = current.cause
            if (next === current) break
            current = next
        }
        return null
    }
}
