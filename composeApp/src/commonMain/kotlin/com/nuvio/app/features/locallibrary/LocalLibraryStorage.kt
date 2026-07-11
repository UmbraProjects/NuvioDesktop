package com.nuvio.app.features.locallibrary

/**
 * Per-profile persistence for the local library.
 *
 * - config: the user's configured folders (source of truth).
 * - overrides: manual/auto id corrections, kept separate so a rescan never loses them.
 * - cache: the last scan result, so the library renders instantly on startup.
 */
internal expect object LocalLibraryStorage {
    fun loadConfig(profileId: Int): String?
    fun saveConfig(profileId: Int, payload: String)

    fun loadOverrides(profileId: Int): String?
    fun saveOverrides(profileId: Int, payload: String)

    fun loadCache(profileId: Int): String?
    fun saveCache(profileId: Int, payload: String)
}
