package com.nuvio.app.features.watched

import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.core.storage.DesktopBlobFile
import com.nuvio.app.core.storage.DesktopStorage
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
                val blob = blobFor(profileId)
                // Do not overwrite a newer blob if one already exists on disk.
                if (blob.readStream { } == null) {
                    blob.write(value)
                }
            }
            store.deleteAll()
            log.i { "Migrated $LEGACY_STORE (${keys.size} profile(s)) to raw JSON blob files" }
        }.onFailure { error ->
            log.w(error) { "Could not migrate $LEGACY_STORE to raw JSON blob files" }
        }
    }
}
