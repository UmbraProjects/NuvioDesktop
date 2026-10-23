package com.nuvio.app.features.iptv

import java.io.InputStream
import java.io.OutputStream

/** The IPTV settings payload (playlists, favourites, recents, preferences) for the active profile. */
internal expect object IptvStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}

/**
 * Files for the parsed channel lists and guides, so the Live TV tab opens at once and refreshes
 * behind. Keyed by playlist id (random UUIDs, so profiles never collide). The desktop actual keeps
 * them in the account folder, beside the store that holds the credentials they were fetched with.
 */
internal expect object IptvCacheStorage {
    fun openRead(name: String): InputStream?

    /** Writes through a temp file and moves it into place, so a crash never leaves half a file. */
    fun write(name: String, block: (OutputStream) -> Unit)

    fun delete(name: String)

    fun clearAll()
}
