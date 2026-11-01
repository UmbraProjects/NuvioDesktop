package com.nuvio.app.features.music

import com.nuvio.app.core.storage.DesktopStorage

internal actual object LyricCacheStorage {
    private val store = DesktopStorage.store("nuvio_music_lyric_cache")

    actual fun keys(): Set<String> = store.keys()

    actual fun get(key: String): String? = store.getString(key)

    actual fun put(key: String, value: String?) {
        store.putString(key, value)
    }
}
