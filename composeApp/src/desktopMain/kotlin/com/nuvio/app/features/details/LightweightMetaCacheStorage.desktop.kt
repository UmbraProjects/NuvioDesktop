package com.nuvio.app.features.details

import com.nuvio.app.core.storage.DesktopBlobFile
import java.io.InputStream
import java.io.OutputStream

internal actual object LightweightMetaCacheStorage {
    // A blob file, not a store: a store would hold the whole JSON in memory all session.
    private val blob = DesktopBlobFile(
        name = "nuvio_lightweight_meta_cache",
        legacyStoreName = "nuvio_lightweight_meta_cache",
        legacyKey = "entries",
    )

    actual fun <T> read(decode: (InputStream) -> T): T? = blob.readStream(decode)
    actual fun write(encode: (OutputStream) -> Unit) = blob.writeStream(encode)
    actual fun clear() = blob.delete()
}
