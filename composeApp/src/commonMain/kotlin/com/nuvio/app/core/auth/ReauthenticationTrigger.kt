package com.nuvio.app.core.auth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lets a screen buried under the profile-gated app shell (e.g. Settings → Account) ask the
 * root app composable to drop cached-profile access and send the user back to [AuthScreen][
 * com.nuvio.app.features.auth.AuthScreen]. Needed because the app deliberately keeps letting a
 * signed-out-but-cached user browse (offline resilience / backend-session gaps) instead of
 * yanking them to the auth gate the moment [AuthState] flips to [AuthState.Unauthenticated] —
 * so there has to be an explicit way to ask for it.
 */
object ReauthenticationTrigger {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun trigger() {
        _events.tryEmit(Unit)
    }
}
