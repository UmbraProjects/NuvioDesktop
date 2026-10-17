package com.nuvio.app.features.calendar

import com.nuvio.app.core.storage.DesktopBlobFile

internal actual object NuvioCalendarCacheStorage {
    // A blob file, not a store: a store would hold the whole JSON in memory all session.
    private val blob = DesktopBlobFile(
        name = "nuvio_local_calendar_cache",
        legacyStoreName = "nuvio_local_calendar_cache",
        legacyKey = "calendar",
    )

    actual fun load(): String? = blob.read()

    actual fun save(json: String?) {
        blob.write(json)
    }
}
