package com.nuvio.app.features.details

import com.nuvio.app.core.storage.DesktopBlobFile

internal actual object LightweightMetaCacheStorage {
    // A blob file, not a store: a store would hold the whole JSON in memory all session.
    private val blob = DesktopBlobFile(
        name = "nuvio_lightweight_meta_cache",
        legacyStoreName = "nuvio_lightweight_meta_cache",
        legacyKey = "entries",
    )

    actual fun load(): String? = blob.read()
    actual fun save(payload: String) = blob.write(payload)
    actual fun clear() = blob.delete()
}
