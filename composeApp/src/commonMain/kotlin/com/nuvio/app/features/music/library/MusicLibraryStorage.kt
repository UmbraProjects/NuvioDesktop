package com.nuvio.app.features.music.library

/** Where the music library and its settings live on disk. One payload (JSON) per profile for the library. */
internal expect object MusicLibraryStorage {
    fun loadLibrary(profileId: Int): String?
    fun saveLibrary(profileId: Int, payload: String)
    fun loadSettings(): String?
    fun saveSettings(payload: String)

    /** Phase 7: the ladders the person has climbed, one JSON payload per profile. */
    fun loadLadders(profileId: Int): String?
    fun saveLadders(profileId: Int, payload: String)
}
