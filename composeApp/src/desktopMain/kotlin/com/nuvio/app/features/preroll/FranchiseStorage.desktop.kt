package com.nuvio.app.features.preroll

import com.nuvio.app.core.storage.DesktopStorage

internal actual object FranchiseStorage {
    private const val KEY = "franchise"
    private val store = DesktopStorage.store("nuvio_preshow_franchise")

    actual fun load(): String? = store.getString(KEY)

    actual fun save(json: String?) {
        store.putString(KEY, json)
    }
}
