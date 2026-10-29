package com.nuvio.app.features.preroll

import com.nuvio.app.core.storage.DesktopStorage

internal actual object PreshowSettingsStorage {
    private val store = DesktopStorage.store("nuvio_preshow_settings")

    actual fun load(key: String): String? = store.getString(key)

    actual fun save(key: String, value: String?) {
        store.putString(key, value)
    }
}
