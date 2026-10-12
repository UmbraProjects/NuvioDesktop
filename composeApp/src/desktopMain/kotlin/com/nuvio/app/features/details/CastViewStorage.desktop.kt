package com.nuvio.app.features.details

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CastViewStorage {
    private val store = DesktopStorage.store("nuvio_cast_view")

    actual fun loadShowCharacters(): Boolean =
        store.getBoolean(ProfileScopedKey.of("cast_show_characters")) ?: false

    actual fun saveShowCharacters(value: Boolean) {
        store.putBoolean(ProfileScopedKey.of("cast_show_characters"), value)
    }
}
