package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.StateFlow

actual fun ensurePhoneRemoteLoaded() = PhoneRemote.ensureLoaded()

actual fun phoneRemoteState(): StateFlow<PhoneRemoteState> = PhoneRemote.state

actual fun setPhoneRemoteEnabled(enabled: Boolean) = PhoneRemote.setEnabled(enabled)

actual fun resetPhoneRemotePairing() = PhoneRemote.resetPairing()
