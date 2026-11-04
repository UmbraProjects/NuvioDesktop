package com.nuvio.app.features.music.booklet

import com.nuvio.app.core.storage.DesktopStorage

internal actual object PinStorage {
    private val store = DesktopStorage.store("nuvio_music_pins")

    actual fun get(key: String): String? = store.getString(key)

    actual fun put(key: String, value: String?) {
        store.putString(key, value)
    }

    actual fun keys(): Set<String> = store.keys()
}
