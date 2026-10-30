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

    actual fun loadSearchHistory(profileId: Int): String? = store.getString("search_history_$profileId")

    actual fun saveSearchHistory(profileId: Int, payload: String) {
        store.putString("search_history_$profileId", payload)
    }

    actual fun loadPlayerVolume(): Int? = store.getString("player_volume")?.toIntOrNull()?.coerceIn(0, 100)

    actual fun savePlayerVolume(volume: Int) {
        store.putString("player_volume", volume.coerceIn(0, 100).toString())
    }

    actual fun loadNebulaLookups(profileId: Int): String? = store.getString("nebula_lookups_$profileId")

    actual fun saveNebulaLookups(profileId: Int, payload: String) {
        store.putString("nebula_lookups_$profileId", payload)
    }

    actual fun loadNebulaAvailability(profileId: Int): String? = store.getString("nebula_availability_$profileId")

    actual fun saveNebulaAvailability(profileId: Int, payload: String) {
        store.putString("nebula_availability_$profileId", payload)
    }

    actual fun loadPlaylists(profileId: Int): String? = store.getString("playlists_$profileId")

    actual fun savePlaylists(profileId: Int, payload: String) {
        store.putString("playlists_$profileId", payload)
    }
}
