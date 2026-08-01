package com.nuvio.app.features.library

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object LibraryDisplaySettingsStorage {
    private val store = DesktopStorage.store("nuvio_library_display_settings")
    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of("library_display_settings"))
    actual fun savePayload(payload: String) = store.putString(ProfileScopedKey.of("library_display_settings"), payload)
}
