package com.nuvio.app.features.streams

internal expect object StreamScoreStorage {
    fun loadProfile(): String?
    fun saveProfile(profile: String)
}
