package com.nuvio.app.features.catalog

import com.nuvio.app.core.storage.DesktopStorage

internal actual object FilenameLookupStorage {
    private val store = DesktopStorage.store("nuvio_filename_lookups")

    actual fun load(): String? = store.getString(KEY)

    actual fun save(payload: String) {
        store.putString(KEY, payload)
    }

    private const val KEY = "lookups_v1"
}
