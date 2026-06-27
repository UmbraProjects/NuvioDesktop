package com.nuvio.app.features.tvdb

internal expect object TvdbSettingsStorage {
    fun loadApiKey(): String?
    fun saveApiKey(apiKey: String)
}
