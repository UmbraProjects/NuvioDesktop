package com.nuvio.app.features.preroll

import com.nuvio.app.core.storage.DesktopStorage

internal actual object PostShowCacheStorage {
    private const val KEY = "postshow"
    private val store = DesktopStorage.store("nuvio_postshow_cache")

    actual fun load(): String? = store.getString(KEY)

    actual fun save(json: String?) {
        store.putString(KEY, json)
    }
}
