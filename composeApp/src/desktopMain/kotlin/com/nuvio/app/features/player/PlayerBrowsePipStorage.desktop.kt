package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage

internal actual object PlayerBrowsePipStorage {
    private val store = DesktopStorage.store("nuvio_player_browse_pip")

    actual fun load(key: String): String? = store.getString(key)

    actual fun save(key: String, value: String?) {
        store.putString(key, value)
    }
}
