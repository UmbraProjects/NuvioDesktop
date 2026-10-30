package com.nuvio.app.features.preroll

import com.nuvio.app.core.storage.DesktopStorage

internal actual object TriviaCacheStorage {
    private const val TRIVIA_KEY = "trivia"
    private val store = DesktopStorage.store("nuvio_trivia_cache")

    actual fun load(): String? = store.getString(TRIVIA_KEY)

    actual fun save(json: String?) {
        store.putString(TRIVIA_KEY, json)
    }
}
