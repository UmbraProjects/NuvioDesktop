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

    actual fun loadLadders(profileId: Int): String? = store.getString("ladders_$profileId")

    actual fun saveLadders(profileId: Int, payload: String) {
        store.putString("ladders_$profileId", payload)
    }

    actual fun loadWhereNext(profileId: Int): String? = store.getString("wherenext_$profileId")

    actual fun saveWhereNext(profileId: Int, payload: String) {
        store.putString("wherenext_$profileId", payload)
    }

    actual fun loadTorboxLookups(profileId: Int): String? = store.getString("torbox_lookups_$profileId")

    actual fun saveTorboxLookups(profileId: Int, payload: String) {
        store.putString("torbox_lookups_$profileId", payload)
    }
}
