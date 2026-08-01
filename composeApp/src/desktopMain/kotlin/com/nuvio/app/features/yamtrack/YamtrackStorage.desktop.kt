package com.nuvio.app.features.yamtrack

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object YamtrackSettingsStorage {
    private val store = DesktopStorage.store("nuvio_yamtrack_settings")
    private const val payloadKey = "yamtrack_settings"

    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey), payload)
    }
}
