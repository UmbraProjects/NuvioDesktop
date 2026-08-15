package com.nuvio.app.features.simkl

internal expect object SimklRewatchStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun clearPayload()
}
