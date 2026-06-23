package com.nuvio.app.features.simkl

internal expect object SimklAuthStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun clearPayload()
}
