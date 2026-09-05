package com.nuvio.app.features.discover

import com.nuvio.app.core.storage.DesktopStorage

internal actual object DiscoverRowCacheStorage {
    private const val ROWS_KEY = "rows"
    private val store = DesktopStorage.store("nuvio_discover_rows_cache")

    actual fun load(): String? = store.getString(ROWS_KEY)

    actual fun save(json: String?) {
        store.putString(ROWS_KEY, json)
    }
}
