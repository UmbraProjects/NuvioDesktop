package com.nuvio.app.features.metadata

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object AnimeIdPreferenceStorage {
    private val store = DesktopStorage.store("nuvio_anime_identity")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("anime_id_preference"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("anime_id_preference"), payload)
    }
}
