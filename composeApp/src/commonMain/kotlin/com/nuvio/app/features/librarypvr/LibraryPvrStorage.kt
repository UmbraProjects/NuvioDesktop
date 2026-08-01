package com.nuvio.app.features.librarypvr

/**
 * Per-profile persistence for library auto-download, mirroring
 * [com.nuvio.app.features.locallibrary.LocalLibraryStorage]. The desktop backing store writes
 * atomically (temp file + swap), so a crash mid-write can't corrupt the monitored-items list.
 */
internal expect object LibraryPvrStorage {
    fun loadSettings(profileId: Int): String?
    fun saveSettings(profileId: Int, payload: String)

    fun loadMonitored(profileId: Int): String?
    fun saveMonitored(profileId: Int, payload: String)

    fun loadGrabs(profileId: Int): String?
    fun saveGrabs(profileId: Int, payload: String)
}
