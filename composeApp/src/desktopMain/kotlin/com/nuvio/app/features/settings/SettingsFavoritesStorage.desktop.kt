package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SettingsFavoritesStorage {
    private val store = DesktopStorage.store("nuvio_settings_favorites")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("settings_favorites"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("settings_favorites"), payload)
    }

    actual fun shouldSeedDefaults(): Boolean = DesktopStorage.isFreshInstall
}
