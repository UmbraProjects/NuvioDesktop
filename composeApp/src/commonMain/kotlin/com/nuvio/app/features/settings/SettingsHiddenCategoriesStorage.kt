package com.nuvio.app.features.settings

internal expect object SettingsHiddenCategoriesStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
