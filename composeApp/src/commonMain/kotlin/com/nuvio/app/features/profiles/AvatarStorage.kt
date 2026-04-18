package com.nuvio.app.features.profiles

internal expect object AvatarStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}