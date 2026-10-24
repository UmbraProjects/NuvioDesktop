package com.nuvio.app.features.music.library

import com.nuvio.app.core.storage.DesktopStorage

internal actual object MusicLibraryStorage {
    private val store = DesktopStorage.store("nuvio_music_library")

    actual fun loadLibrary(profileId: Int): String? = store.getString("library_$profileId")

    actual fun saveLibrary(profileId: Int, payload: String) {
        store.putString("library_$profileId", payload)
    }

    actual fun loadSettings(): String? = store.getString("settings")

    actual fun saveSettings(payload: String) {
        store.putString("settings", payload)
    }
}
