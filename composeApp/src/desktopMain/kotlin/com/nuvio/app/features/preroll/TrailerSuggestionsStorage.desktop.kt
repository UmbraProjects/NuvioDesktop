package com.nuvio.app.features.preroll

import com.nuvio.app.core.storage.DesktopStorage

internal actual object TrailerSuggestionsStorage {
    private const val KEY = "suggestions"
    private val store = DesktopStorage.store("nuvio_preshow_suggestions")

    actual fun load(): String? = store.getString(KEY)

    actual fun save(json: String?) {
        store.putString(KEY, json)
    }
}
