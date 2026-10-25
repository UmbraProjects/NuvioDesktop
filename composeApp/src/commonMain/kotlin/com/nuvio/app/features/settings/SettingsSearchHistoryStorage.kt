package com.nuvio.app.features.settings

internal expect object SettingsSearchHistoryStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
