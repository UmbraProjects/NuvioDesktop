package com.nuvio.app.features.search

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object ConfidentSearchStorage {
    private val store = DesktopStorage.store("nuvio_confident_search")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("confident_search_settings"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("confident_search_settings"), payload)
    }
}
