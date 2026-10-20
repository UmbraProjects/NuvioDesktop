package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopBlobFile
import com.nuvio.app.core.storage.DesktopStorage
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

internal actual object MdbListRatingsCacheStorage {
    // Machine-wide like the store they replace (MACHINE_STORE_NAMES): ratings and cast describe
    // titles, not the signed-in person.
    private val ratings = DesktopBlobFile(name = "nuvio_mdblist_ratings", directory = { DesktopStorage.rootDir })
    private val cast = DesktopBlobFile(name = "nuvio_hero_cast", directory = { DesktopStorage.rootDir })

    // The old shared store. Kept in MACHINE_STORE_NAMES so storeFile() still finds it to migrate.
    private const val LEGACY_STORE = "nuvio_mdblist_ratings_cache"
    private const val LEGACY_RATINGS_KEY = "ratings_cache"
    private const val LEGACY_CAST_KEY = "cast_cache"

    actual fun <T> readRatings(decode: (InputStream) -> T): T? {
        migrateLegacyStore()
        return ratings.readStream(decode)
    }

    actual fun writeRatings(encode: (OutputStream) -> Unit) {
        migrateLegacyStore()
        ratings.writeStream(encode)
    }

    actual fun clearRatings() {
        migrateLegacyStore()
        ratings.delete()
    }

    actual fun <T> readCast(decode: (InputStream) -> T): T? {
        migrateLegacyStore()
        return cast.readStream(decode)
    }

    actual fun writeCast(encode: (OutputStream) -> Unit) {
        migrateLegacyStore()
        cast.writeStream(encode)
    }

    /**
     * Moves both values out of the old store at once, then deletes it. Not DesktopBlobFile's own
     * legacy move: that deletes the store after taking one key, which would lose the other cache.
     * A blob that already exists is newer than the store and is not overwritten.
     * Checked on every read and write (both rare); after the move the store file is gone.
     */
    @Synchronized
    private fun migrateLegacyStore() {
        // Only open the old store when its file exists; opening one creates its in-memory copy.
        if (!Files.exists(DesktopStorage.storeFile(LEGACY_STORE))) return
        runCatching {
            val store = DesktopStorage.store(LEGACY_STORE)
            listOf(LEGACY_RATINGS_KEY to ratings, LEGACY_CAST_KEY to cast).forEach { (key, blob) ->
                val value = store.getString(key) ?: return@forEach
                if (blob.readStream { } == null) blob.write(value)
            }
            // Reached only when both moves succeeded; a failure keeps the store for the next start.
            store.deleteAll()
        }.onFailure { error -> Logger.withTag("MdbListRatingsCache").w(error) { "Could not move $LEGACY_STORE into its own files" } }
    }
}
