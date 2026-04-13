package com.nuvio.app.features.trakt

internal expect object TraktLibraryStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}