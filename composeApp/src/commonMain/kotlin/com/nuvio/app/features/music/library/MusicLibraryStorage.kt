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

    /** The artist page's "Where next?" ladders, kept for a week, one JSON payload per profile. */
    fun loadWhereNext(profileId: Int): String?
    fun saveWhereNext(profileId: Int, payload: String)

    /** What TorBox had for each album when last asked, one JSON payload per profile (see TorboxLookupCache). */
    fun loadTorboxLookups(profileId: Int): String?
    fun saveTorboxLookups(profileId: Int, payload: String)
}
