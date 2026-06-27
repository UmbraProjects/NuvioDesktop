package com.nuvio.app.features.tvdb

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object TvdbSettingsStorage {
    private const val apiKeyKey = "tvdb_api_key"
    private val store = DesktopStorage.store("nuvio_tvdb_settings")

    actual fun loadApiKey(): String? = store.getString(ProfileScopedKey.of(apiKeyKey))
    actual fun saveApiKey(apiKey: String) = store.putString(ProfileScopedKey.of(apiKeyKey), apiKey)
}
