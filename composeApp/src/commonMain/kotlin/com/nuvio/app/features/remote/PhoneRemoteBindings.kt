package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.StateFlow

/**
 * fork:phone-remote — bridge from the shared settings page to the desktop phone-remote listener,
 * shaped like `GamepadBindings` next door. Only a desktop actual exists.
 */
data class PhoneRemoteState(
    val enabled: Boolean = false,
    /** True once the listener is bound; false while off, or when every port was taken. */
    val listening: Boolean = false,
    val port: Int? = null,
    val pin: String = "",
    /** This PC's home-network addresses, for typing into the phone if it cannot find the PC itself. */
    val addresses: List<String> = emptyList(),
    val pairedDevices: List<String> = emptyList(),
    val connectedDevices: List<String> = emptyList(),
)

expect fun ensurePhoneRemoteLoaded()

expect fun phoneRemoteState(): StateFlow<PhoneRemoteState>

expect fun setPhoneRemoteEnabled(enabled: Boolean)

/** A new pairing code; every phone paired so far has to pair again. */
expect fun resetPhoneRemotePairing()
