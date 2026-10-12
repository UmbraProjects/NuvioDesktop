package com.nuvio.app.features.discover

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DiscoverBrowseStorage {
    private val store = DesktopStorage.store("nuvio_discover_browse")
    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of("discover_browse"))
    actual fun savePayload(payload: String) = store.putString(ProfileScopedKey.of("discover_browse"), payload)
}
