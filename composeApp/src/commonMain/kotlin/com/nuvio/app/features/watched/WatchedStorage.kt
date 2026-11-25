package com.nuvio.app.features.watched

import java.io.InputStream

expect object WatchedStorage {
    fun loadPayload(profileId: Int): String?
    fun <T> loadPayloadStream(profileId: Int, decode: (InputStream) -> T): T?
    fun savePayload(profileId: Int, payload: String)
}

