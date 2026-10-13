package com.nuvio.app.features.calendar

import com.nuvio.app.core.storage.DesktopStorage

internal actual object NuvioCalendarCacheStorage {
    private const val ENTRY_KEY = "calendar"
    private val store = DesktopStorage.store("nuvio_local_calendar_cache")

    actual fun load(): String? = store.getString(ENTRY_KEY)

    actual fun save(json: String?) {
        store.putString(ENTRY_KEY, json)
    }
}
