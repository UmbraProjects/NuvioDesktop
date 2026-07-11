package com.nuvio.app.features.locallibrary

import com.nuvio.app.core.storage.DesktopStorage

internal actual object LocalLibraryStorage {
    private val store = DesktopStorage.store("nuvio_local_library")

    actual fun loadConfig(profileId: Int): String? = store.getString("config_$profileId")
    actual fun saveConfig(profileId: Int, payload: String) = store.putString("config_$profileId", payload)

    actual fun loadOverrides(profileId: Int): String? = store.getString("overrides_$profileId")
    actual fun saveOverrides(profileId: Int, payload: String) = store.putString("overrides_$profileId", payload)

    actual fun loadCache(profileId: Int): String? = store.getString("cache_$profileId")
    actual fun saveCache(profileId: Int, payload: String) = store.putString("cache_$profileId", payload)
}
