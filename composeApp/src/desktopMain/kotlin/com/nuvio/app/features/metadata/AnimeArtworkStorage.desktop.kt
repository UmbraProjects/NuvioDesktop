package com.nuvio.app.features.metadata

import com.nuvio.app.core.storage.DesktopStorage

internal actual object AnimeArtworkStorage {
    private val store = DesktopStorage.store("nuvio_anime_artwork")

    actual fun load(): String? = store.getString(KEY)

    actual fun save(payload: String) {
        store.putString(KEY, payload)
    }

    private const val KEY = "artwork_v1"
}
