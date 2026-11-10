package com.nuvio.app.features.tonight

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

// fork:tonight
internal actual object TonightStorage {
    private val store = DesktopStorage.store("nuvio_tonight")

    actual fun getString(key: String): String? = store.getString(ProfileScopedKey.of(key))

    actual fun putString(key: String, value: String) {
        store.putString(ProfileScopedKey.of(key), value)
    }
}
