package com.nuvio.app.features.details.filler

import com.nuvio.app.core.storage.DesktopBlobFile

internal actual object AnimeFillerCacheStorage {
    // A blob file, not a store: One Piece's page alone is ~1,200 rows.
    private val blob = DesktopBlobFile(name = "nuvio_anime_filler_cache")

    actual fun load(): String? = blob.read()
    actual fun save(payload: String) = blob.write(payload)
}
