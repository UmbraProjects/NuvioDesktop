package com.nuvio.app.features.player.skip

import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

internal actual object SkipDbDumpStorage {
    private const val ETAG_KEY = "dump_etag"
    private const val LAST_SYNC_KEY = "dump_last_sync_epoch_millis"

    private val store = DesktopStorage.store("nuvio_skipdb")
    private val dumpFile: Path get() = DesktopStorage.rootDir.resolve("skipdb-dump.json")

    actual fun loadDump(): String? {
        val file = dumpFile
        if (!file.exists()) return null
        return runCatching { Files.readString(file) }.getOrNull()
    }

    actual fun saveDump(json: String, etag: String?) {
        val file = dumpFile
        val written = runCatching {
            Files.createDirectories(file.parent)
            // Staged through a sibling temp file and swapped in, so an interrupted write can only
            // ever leave a stale .tmp behind. Truncating the real file in place would leave a
            // window where a crash mid-write strands an unparseable export that then has to be
            // re-downloaded in full.
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            try {
                Files.writeString(tmp, json)
                runCatching { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE) }
                    .recoverCatching { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
                    .getOrThrow()
            } catch (error: Exception) {
                runCatching { Files.deleteIfExists(tmp) }
                throw error
            }
        }.isSuccess

        // Recording an etag for an export that never landed would revalidate a file that is not
        // there and answer 304, leaving the lookup permanently empty.
        if (written) store.putString(ETAG_KEY, etag)
    }

    actual fun loadEtag(): String? = store.getString(ETAG_KEY)

    actual fun loadLastSyncEpochMillis(): Long? = store.getString(LAST_SYNC_KEY)?.toLongOrNull()

    actual fun saveLastSyncEpochMillis(epochMillis: Long) {
        store.putString(LAST_SYNC_KEY, epochMillis.toString())
    }

    actual fun nowEpochMillis(): Long = System.currentTimeMillis()

    actual fun clear() {
        runCatching { Files.deleteIfExists(dumpFile) }
        store.remove(ETAG_KEY)
        store.remove(LAST_SYNC_KEY)
    }
}
