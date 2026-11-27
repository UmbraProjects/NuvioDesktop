package com.nuvio.app.features.watched

import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.core.storage.DesktopBlobFile
import com.nuvio.app.core.storage.DesktopStorage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Per-profile raw JSON blob storage for watched history (`nuvio_watched_<profileId>.json`).
 *
 * Replaces `nuvio_watched.properties` (2.6+ MB of backslash-escaped JSON): `Properties.load()`
 * alone took 230-260 ms at startup and held the entire escaped string permanently in RAM. Raw
 * UTF-8 reads take a few milliseconds and leave nothing retained after decode.
 *
 * Writes are coalesced on a background thread (1.5 s delay) and registered with
 * [DesktopStorage.registerFlushListener] so exit and settings backup always flush them. A legacy
 * `nuvio_watched.properties` file (from an earlier version or a restored backup) is migrated once
 * on first access and deleted.
 */
actual object WatchedStorage {
    private const val LEGACY_STORE = "nuvio_watched"
    private const val LEGACY_KEY_PREFIX = "watched_"
    private const val BLOB_PREFIX = "nuvio_watched_"
    private const val FLUSH_DELAY_MS = 1_500L

    private val log = Logger.withTag("WatchedStorage")
    private val blobs = ConcurrentHashMap<Int, DesktopBlobFile>()
    private val pendingLock = Any()
    private val pendingPayloads = LinkedHashMap<Int, String>()
    private var flushScheduled = false

    private val flushExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nuvio-watched-flush").apply { isDaemon = true }
        }.also {
            DesktopStorage.registerFlushListener(::flushPendingWrites)
        }
    }

    private fun blobFor(profileId: Int): DesktopBlobFile =
        blobs.computeIfAbsent(profileId) { id -> DesktopBlobFile(name = "$BLOB_PREFIX$id") }

    actual fun loadPayload(profileId: Int): String? {
        migrateLegacyStoreIfPresent()
        synchronized(pendingLock) {
            pendingPayloads[profileId]?.let { return it }
        }
        return blobFor(profileId).read()
    }

    actual fun <T> loadPayloadStream(profileId: Int, decode: (InputStream) -> T): T? {
        migrateLegacyStoreIfPresent()
        synchronized(pendingLock) {
            pendingPayloads[profileId]?.let { pending ->
                return ByteArrayInputStream(pending.toByteArray(Charsets.UTF_8)).use(decode)
            }
        }
        return blobFor(profileId).readStream(decode)
    }

    actual fun savePayload(profileId: Int, payload: String) {
        migrateLegacyStoreIfPresent()
        val shouldSchedule = synchronized(pendingLock) {
            val previous = pendingPayloads.put(profileId, payload)
            PerfTelemetry.recordStoreWrite(LEGACY_STORE, "$LEGACY_KEY_PREFIX$profileId", changed = previous != payload)
            if (!flushScheduled) {
                flushScheduled = true
                true
            } else {
                false
            }
        }
        if (shouldSchedule) {
            runCatching {
                flushExecutor.schedule(::flushPendingWrites, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
            }.onFailure {
                // Executor shut down; write synchronously as a fallback.
                flushPendingWrites()
            }
        }
    }

    internal fun flushPendingWrites() {
        val snapshot = synchronized(pendingLock) {
            flushScheduled = false
            if (pendingPayloads.isEmpty()) return
            LinkedHashMap(pendingPayloads)
        }
        snapshot.forEach { (profileId, payload) ->
            val startedAt = System.nanoTime()
            runCatching {
                blobFor(profileId).write(payload)
                synchronized(pendingLock) {
                    if (pendingPayloads[profileId] === payload) {
                        pendingPayloads.remove(profileId)
                    }
                }
                if (PerfTelemetry.enabled) {
                    PerfTelemetry.recordStoreFlush(
                        "$BLOB_PREFIX$profileId",
                        bytes = payload.toByteArray(Charsets.UTF_8).size.toLong(),
                        durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                    )
                }
            }.onFailure { error ->
                log.w(error) { "Could not persist $BLOB_PREFIX$profileId.json" }
            }
        }
    }

    internal fun clearAll() {
        synchronized(pendingLock) {
            pendingPayloads.clear()
            flushScheduled = false
        }
        runCatching {
            val dir = DesktopStorage.accountDir
            if (Files.isDirectory(dir)) {
                Files.list(dir).use { entries ->
                    entries.filter { path ->
                        val name = path.fileName.toString()
                        name.startsWith(BLOB_PREFIX) && (name.endsWith(".json") || name.endsWith(".json.tmp"))
                    }.forEach { path -> runCatching { Files.deleteIfExists(path) } }
                }
            }
        }
    }

    /**
     * Every profile's watched history in this account, keyed `watched_<profileId>` like the legacy
     * `nuvio_watched.properties` store. The settings backup writes it under that store's name, so a
     * restore (on this build or an older one) brings the history back through the legacy migration.
     */
    internal fun payloadsForBackup(): Map<String, String> {
        migrateLegacyStoreIfPresent()
        val out = sortedMapOf<String, String>()
        runCatching {
            val dir = DesktopStorage.accountDir
            if (Files.isDirectory(dir)) {
                Files.list(dir).use { entries ->
                    entries.forEach { path ->
                        val name = path.fileName.toString()
                        if (!name.startsWith(BLOB_PREFIX) || !name.endsWith(".json")) return@forEach
                        val profileId = name.removePrefix(BLOB_PREFIX).removeSuffix(".json").toIntOrNull()
                            ?: return@forEach
                        blobFor(profileId).read()?.let { out["$LEGACY_KEY_PREFIX$profileId"] = it }
                    }
                }
            }
        }.onFailure { error -> log.w(error) { "Could not list watched history for backup" } }
        // Not yet flushed: newer than the file.
        synchronized(pendingLock) {
            pendingPayloads.forEach { (profileId, payload) -> out["$LEGACY_KEY_PREFIX$profileId"] = payload }
        }
        return out
    }

    @Synchronized
    private fun migrateLegacyStoreIfPresent() {
        val legacyFile = DesktopStorage.storeFile(LEGACY_STORE)
        if (!Files.exists(legacyFile)) return
        runCatching {
            val store = DesktopStorage.store(LEGACY_STORE)
            val keys = store.keys().filter { it.startsWith(LEGACY_KEY_PREFIX) }
            keys.forEach { key ->
                val profileId = key.removePrefix(LEGACY_KEY_PREFIX).toIntOrNull() ?: return@forEach
                val value = store.getString(key) ?: return@forEach
                // The legacy store wins over a blob already on disk. Once migrated it is deleted, so
                // it only reappears when a settings backup is restored (backups carry watched history
                // as `nuvio_watched.properties`, see [payloadsForBackup]) or an older build wrote it:
                // either way it is the newer copy, and skipping it threw the restored history away.
                synchronized(pendingLock) { pendingPayloads.remove(profileId) }
                blobFor(profileId).write(value)
            }
            store.deleteAll()
            log.i { "Migrated $LEGACY_STORE (${keys.size} profile(s)) to raw JSON blob files" }
        }.onFailure { error ->
            log.w(error) { "Could not migrate $LEGACY_STORE to raw JSON blob files" }
        }
    }
}
