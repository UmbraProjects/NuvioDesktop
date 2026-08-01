package com.nuvio.app.features.settings

internal expect object SettingsFavoritesStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun shouldSeedDefaults(): Boolean
}
