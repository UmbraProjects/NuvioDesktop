package com.nuvio.app.features.preroll

import com.nuvio.app.core.storage.DesktopStorage

internal actual object PrerollSettingsStorage {
    private const val TRAILER_COUNT_KEY = "trailer_count"
    private val store = DesktopStorage.store("nuvio_preroll_settings")

    actual fun loadTrailerCount(): Int? = store.getInt(TRAILER_COUNT_KEY)

    actual fun saveTrailerCount(count: Int) {
        store.putInt(TRAILER_COUNT_KEY, count)
    }
}
