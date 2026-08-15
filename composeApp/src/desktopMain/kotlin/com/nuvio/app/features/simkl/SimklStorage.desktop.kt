package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SimklSettingsStorage {
    private val store = DesktopStorage.store("nuvio_simkl_settings")

    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of("simkl_settings"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("simkl_settings"), payload)
    }
}

internal actual object SimklAuthStorage {
    private val store = DesktopStorage.store("nuvio_simkl_auth")
    private const val payloadKey = "simkl_auth_payload"

    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey), payload)
    }

    actual fun clearPayload() {
        store.remove(ProfileScopedKey.of(payloadKey))
    }
}

internal actual object SimklRewatchStorage {
    private val store = DesktopStorage.store("nuvio_simkl_rewatches")
    private const val payloadKey = "simkl_rewatch_payload"

    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey), payload)
    }

    actual fun clearPayload() {
        store.remove(ProfileScopedKey.of(payloadKey))
    }
}
