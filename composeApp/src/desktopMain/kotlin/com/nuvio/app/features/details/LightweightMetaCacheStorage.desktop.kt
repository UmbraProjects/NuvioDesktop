package com.nuvio.app.features.details

import com.nuvio.app.core.storage.DesktopStorage

internal actual object LightweightMetaCacheStorage {
    private val store = DesktopStorage.store("nuvio_lightweight_meta_cache")

    actual fun load(): String? = store.getString("entries")
    actual fun save(payload: String) = store.putString("entries", payload)
    actual fun clear() = store.deleteAll()
}
