package com.nuvio.app.core.ui

internal expect object PosterCardStyleStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}