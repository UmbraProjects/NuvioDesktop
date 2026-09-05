package com.nuvio.app.features.games

internal expect object GameLibrarySettingsStorage {
    fun loadIgdbClientId(): String?
    fun saveIgdbClientId(value: String)
    fun loadIgdbClientSecret(): String?
    fun saveIgdbClientSecret(value: String)
    fun loadSteamGridDbApiKey(): String?
    fun saveSteamGridDbApiKey(value: String)
    fun loadBackdropStyle(): String?
    fun saveBackdropStyle(value: String)
}
