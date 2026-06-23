package com.nuvio.app.features.simkl

internal expect object SimklSettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
