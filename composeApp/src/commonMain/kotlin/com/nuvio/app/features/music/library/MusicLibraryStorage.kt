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

    /** Recent music searches, apart from the film and show search history, one per profile. */
    fun loadSearchHistory(profileId: Int): String?
    fun saveSearchHistory(profileId: Int, payload: String)

    /** The music player's volume (0 to 100) the last session ended on; null before the first change. */
    fun loadPlayerVolume(): Int?
    fun savePlayerVolume(volume: Int)
    /** What the music addon had for each album when last asked, one JSON payload per profile (see NebulaLookupCache). */
    fun loadNebulaLookups(profileId: Int): String?
    fun saveNebulaLookups(profileId: Int, payload: String)

    /** fork:music-playlists - the profile's music playlists, one JSON payload per profile. */
    fun loadPlaylists(profileId: Int): String?
    fun savePlaylists(profileId: Int, payload: String)
}
