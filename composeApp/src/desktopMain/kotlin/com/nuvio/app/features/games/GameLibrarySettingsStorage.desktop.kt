package com.nuvio.app.features.games

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object GameLibrarySettingsStorage {
    private const val igdbClientIdKey = "games_igdb_client_id"
    private const val igdbClientSecretKey = "games_igdb_client_secret"
    private const val steamGridDbApiKeyKey = "games_steamgriddb_api_key"
    private const val backdropStyleKey = "games_backdrop_style"
    private const val migratedKey = "games_settings_migrated_from_library_file"
    private val store = DesktopStorage.store("nuvio_games_settings")

    actual fun loadIgdbClientId(): String? = load(igdbClientIdKey)
    actual fun saveIgdbClientId(value: String) = save(igdbClientIdKey, value)
    actual fun loadIgdbClientSecret(): String? = load(igdbClientSecretKey)
    actual fun saveIgdbClientSecret(value: String) = save(igdbClientSecretKey, value)
    actual fun loadSteamGridDbApiKey(): String? = load(steamGridDbApiKeyKey)
    actual fun saveSteamGridDbApiKey(value: String) = save(steamGridDbApiKeyKey, value)
    actual fun loadBackdropStyle(): String? = load(backdropStyleKey)
    actual fun saveBackdropStyle(value: String) = save(backdropStyleKey, value)

    private fun load(key: String): String? {
        migrateFromLibraryFileIfNeeded()
        return store.getString(ProfileScopedKey.of(key))
    }

    private fun save(key: String, value: String) {
        // A save can arrive before anything has been read (an empty page the user types into
        // immediately), and the migration must not then overwrite what they just entered.
        migrateFromLibraryFileIfNeeded()
        store.putString(ProfileScopedKey.of(key), value)
    }

    /**
     * Lifts the credentials and presentation choice out of a pre-merge Umbra `library.json`.
     *
     * Runs at most once per profile and only fills keys that are still unset, so it cannot undo a
     * later edit. The library file keeps its `settings` block untouched — only
     * `lastExecutableDirectory` is written back there from now on, and leaving the rest in place
     * costs nothing and keeps the old file readable by the standalone launcher during a changeover.
     */
    private fun migrateFromLibraryFileIfNeeded() {
        if (store.getBoolean(ProfileScopedKey.of(migratedKey)) == true) return
        store.putBoolean(ProfileScopedKey.of(migratedKey), true)
        val legacy = runCatching { GameLibraryRepository().loadBlocking().settings }.getOrNull() ?: return
        migrateValue(igdbClientIdKey, legacy.igdbClientId)
        migrateValue(igdbClientSecretKey, legacy.igdbClientSecret)
        migrateValue(steamGridDbApiKeyKey, legacy.steamGridDbApiKey)
        migrateValue(
            backdropStyleKey,
            when (legacy.backdropShelfStyle) {
                "FULL_BACKDROP" -> GameBackdropStyle.FullBackdrop.name
                "BLACK_SHELF" -> GameBackdropStyle.BlackShelf.name
                else -> ""
            },
        )
    }

    private fun migrateValue(key: String, value: String) {
        if (value.isBlank()) return
        val scoped = ProfileScopedKey.of(key)
        if (store.getString(scoped) != null) return
        store.putString(scoped, value)
    }
}
