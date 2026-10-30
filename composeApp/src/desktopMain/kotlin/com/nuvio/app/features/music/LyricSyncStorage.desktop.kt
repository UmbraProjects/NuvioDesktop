package com.nuvio.app.features.music

import com.nuvio.app.core.storage.DesktopStorage

internal actual object LyricSyncStorage {
    private val store = DesktopStorage.store("nuvio_music_lyric_sync")

    actual fun load(): String? = store.getString("offsets")

    actual fun save(payload: String) {
        store.putString("offsets", payload)
    }
}
