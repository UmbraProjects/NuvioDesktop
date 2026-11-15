package com.nuvio.app.features.livescores

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

// fork:live-scores - favourites and followed competitions are the person's, so the store is profile-scoped.
internal actual object LiveScoreStorage {
    private val store = DesktopStorage.store("nuvio_live_scores")

    actual fun getString(key: String): String? = store.getString(ProfileScopedKey.of(key))

    actual fun putString(key: String, value: String) {
        store.putString(ProfileScopedKey.of(key), value)
    }
}
