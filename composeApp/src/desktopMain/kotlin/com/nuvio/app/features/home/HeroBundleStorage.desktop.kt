package com.nuvio.app.features.home

import com.nuvio.app.core.storage.DesktopStorage

internal actual object HeroBundleStorage {
    private val store = DesktopStorage.store("nuvio_hero_bundle")

    actual fun load(): String? = store.getString(KEY)

    actual fun save(payload: String) {
        store.putString(KEY, payload)
    }

    private const val KEY = "bundle_v1"
}
