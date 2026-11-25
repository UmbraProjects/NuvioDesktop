package com.nuvio.app

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal sealed interface StartupBootstrapState {
    /** Early bootstrap was never started (e.g. smoke harness or tests). */
    data object Idle : StartupBootstrapState

    /** Auth / profile warm-up is running in the background while the window comes up. */
    data object Running : StartupBootstrapState

    /**
     * Auth resolved to [userId], [profileIndex] was activated, and the Home-critical repositories
     * (including `HomeRepository.restoreFromDiskInBackground()`) have been warmed. `App()` can
     * enter `Main` directly without hopping through `Loading` -> `ProfileSwitching`.
     */
    data class Ready(val userId: String, val profileIndex: Int) : StartupBootstrapState

    /**
     * Startup cannot bypass the gate (signed out, PIN-protected profile, multi-profile picker,
     * error, or invalidated after sign-out). `App()`'s normal gate logic handles navigation.
     */
    data object NeedsGate : StartupBootstrapState
}

/**
 * Holds heavy `MainAppContent` composition back while the desktop window is still cloaked and
 * settling its initial borderless-fullscreen bounds. Defaults to `true` so tests, non-Windows
 * targets, and smoke launches never wait.
 */
internal object StartupWindowRevealGate {
    private val _ready = MutableStateFlow(true)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun arm() {
        _ready.value = false
    }

    fun release() {
        _ready.value = true
    }
}

/**
 * Overlaps auth session load, cached profile load, and Home-critical repository warming with
 * desktop `Window` creation in `main()` (~430 ms) and the first-frame fullscreen settle (~200 ms).
 *
 * Without this, `AuthRepository.initialize()` only started after `Window` finished opening, and
 * `ProfileRepository.switchToProfile()` + `HomeRepository.restoreFromDiskInBackground()` waited
 * through two Compose UI-thread recompositions after the first frame finished (~900-1166 ms).
 */
internal object StartupBootstrap {
    private val log = Logger.withTag("AppStartup")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow<StartupBootstrapState>(StartupBootstrapState.Idle)
    val state: StateFlow<StartupBootstrapState> = _state.asStateFlow()

    private var job: Job? = null
    private var pulledProfilesForUserId: String? = null

    fun start() {
        synchronized(lock) {
            if (_state.value != StartupBootstrapState.Idle) return
            _state.value = StartupBootstrapState.Running
            job = scope.launch {
                runBootstrap()
            }
        }
    }

    /**
     * Marks any previous early-bootstrap result obsolete (e.g. on sign-out or explicit
     * re-authentication) so a subsequent sign-in goes through the normal blocking sync gate.
     */
    fun invalidate() {
        val toCancel = synchronized(lock) {
            pulledProfilesForUserId = null
            if (_state.value != StartupBootstrapState.Idle) {
                _state.value = StartupBootstrapState.NeedsGate
            }
            job.also { job = null }
        }
        toCancel?.cancel()
    }

    /**
     * True when early bootstrap already warmed [profileState]'s active profile for [authState].
     * Checked on `App()`'s first composition so `gateScreen` can start at `Main` directly.
     */
    fun isReadyFor(authState: AuthState, profileState: ProfileState): Boolean {
        val ready = _state.value as? StartupBootstrapState.Ready ?: return false
        return authState is AuthState.Authenticated &&
            authState.userId == ready.userId &&
            profileState.activeProfile?.profileIndex == ready.profileIndex
    }

    /**
     * Returns true (once) if early bootstrap already kicked off `ProfileRepository.pullProfiles()`
     * for [userId], so `App()`'s `LaunchedEffect` does not issue a duplicate RPC at startup.
     */
    fun consumePulledProfiles(userId: String): Boolean = synchronized(lock) {
        if (pulledProfilesForUserId == userId) {
            pulledProfilesForUserId = null
            true
        } else {
            false
        }
    }

    private suspend fun runBootstrap() {
        val startedAt = System.currentTimeMillis()
        log.i { "early startup bootstrap started" }
        try {
            val outcome = executeBootstrap(
                loadCachedProfiles = { ProfileRepository.loadCachedProfiles() },
                initializeAuth = { AuthRepository.initialize() },
                awaitSettledAuth = { AuthRepository.state.first { it !is AuthState.Loading } },
                currentAuth = { AuthRepository.state.value },
                ensureProfilesLoaded = { userId -> ProfileRepository.ensureLoaded(userId) },
                launchProfilePull = { userId ->
                    synchronized(lock) { pulledProfilesForUserId = userId }
                    scope.launch { ProfileRepository.pullProfiles() }
                },
                resolveAutoProfile = { ProfileRepository.resolveAutoStartupProfile() },
                activateAndWarmProfile = { profile ->
                    ProfileRepository.switchToProfile(profile.profileIndex)
                    warmProfileStartupRepositories()
                },
            )
            synchronized(lock) {
                if (_state.value == StartupBootstrapState.Running) {
                    _state.value = outcome
                }
            }
            val elapsedMs = System.currentTimeMillis() - startedAt
            when (outcome) {
                is StartupBootstrapState.Ready ->
                    log.i { "early startup bootstrap ready (profile=${outcome.profileIndex}) in ${elapsedMs}ms" }
                else ->
                    log.i { "early startup bootstrap handed off to gate in ${elapsedMs}ms" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.w(e) { "early startup bootstrap failed; falling back to UI gate" }
            synchronized(lock) {
                if (_state.value == StartupBootstrapState.Running) {
                    _state.value = StartupBootstrapState.NeedsGate
                }
            }
        }
    }
}

/**
 * Pure orchestration of the early startup bootstrap so its gate decisions can be verified in
 * isolation without booting Supabase or AWT.
 */
internal suspend fun executeBootstrap(
    loadCachedProfiles: () -> Boolean,
    initializeAuth: () -> Unit,
    awaitSettledAuth: suspend () -> AuthState,
    currentAuth: () -> AuthState,
    ensureProfilesLoaded: (userId: String) -> Unit,
    launchProfilePull: (userId: String) -> Unit,
    resolveAutoProfile: () -> NuvioProfile?,
    activateAndWarmProfile: suspend (NuvioProfile) -> Unit,
): StartupBootstrapState {
    loadCachedProfiles()
    initializeAuth()
    val settledAuth = awaitSettledAuth()
    if (settledAuth !is AuthState.Authenticated) {
        return StartupBootstrapState.NeedsGate
    }
    ensureProfilesLoaded(settledAuth.userId)
    launchProfilePull(settledAuth.userId)
    val autoProfile = resolveAutoProfile() ?: return StartupBootstrapState.NeedsGate
    activateAndWarmProfile(autoProfile)
    val latestAuth = currentAuth()
    if (latestAuth !is AuthState.Authenticated || latestAuth.userId != settledAuth.userId) {
        return StartupBootstrapState.NeedsGate
    }
    return StartupBootstrapState.Ready(
        userId = settledAuth.userId,
        profileIndex = autoProfile.profileIndex,
    )
}
