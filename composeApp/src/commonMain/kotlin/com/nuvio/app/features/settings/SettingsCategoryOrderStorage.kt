package com.nuvio.app.features.settings

internal expect object SettingsCategoryOrderStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
