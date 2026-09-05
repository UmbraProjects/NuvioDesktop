package com.nuvio.app.features.settings

internal expect object SettingsCategoryNamesStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
