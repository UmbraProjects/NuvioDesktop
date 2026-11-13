package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.core.storage.CoalescingCachePersister
import com.nuvio.app.core.ui.ImageCacheSettingsRepository
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The hero bundle on disk: one JSON map, per account. */
internal expect object HeroBundleStorage {
    fun load(): String?
    fun save(payload: String)
}

/**
 * What the Home hero shows about a title that is not already kept elsewhere between launches: its discovery badges,
 * its production credits (director, writer, studio) and IMDb's sub-genres. Cast and MDBList ratings have their own disk
 * caches. Without this every launch fetched a hero summary (addon meta and TMDB) for every hero again just to rebuild
 * these (optimization audit B1: 1,000 to 1,800 requests in the first 25 s of every launch).
 *
 * Lifetime is the Metadata Cache setting in Advanced (Off reads and writes nothing), at most a day for a series (an
 * airing show's badges change) and six hours for an empty answer, which may have been a failed lookup.
 */
internal object HeroBundleCache {
    private val log = Logger.withTag("HeroBundle")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null

    @Serializable
    private data class Entry(val payload: String, val savedAtMs: Long, val lifetimeMs: Long)

    private val mapSerializer = MapSerializer(String.serializer(), Entry.serializer())

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val SERIES_MAX_MS = 24L * HOUR_MS
    private const val EMPTY_MAX_MS = 6L * HOUR_MS
    private const val MAX_ENTRIES = 1_500
    private const val WRITE_DELAY_MS = 30_000L

    private val persister = CoalescingCachePersister(tag = "heroBundle", writeDelayMs = WRITE_DELAY_MS) {
        val snapshot = mutex.withLock { entries?.toMap() } ?: return@CoalescingCachePersister
        runCatching { HeroBundleStorage.save(json.encodeToString(mapSerializer, snapshot)) }
            .onFailure { log.w { "Could not save the hero bundle: ${it.message}" } }
    }

    private fun lifetimeSettingMs(): Long {
        ImageCacheSettingsRepository.ensureLoaded()
        return ImageCacheSettingsRepository.settings.value.metadataCacheHours * HOUR_MS
    }

    private suspend fun loaded(): MutableMap<String, Entry> {
        entries?.let { return it }
        // Parsed off the caller's thread: the hero asks from Home's composition.
        val fromDisk = withContext(Dispatchers.Default) {
            runCatching { HeroBundleStorage.load()?.let { json.decodeFromString(mapSerializer, it) } }.getOrNull().orEmpty()
        }
        return mutex.withLock {
            entries ?: run {
                val now = WatchProgressClock.nowEpochMs()
                val live = fromDisk.filterValues { now - it.savedAtMs in 0..it.lifetimeMs }.toMutableMap()
                log.i { "hero bundle loaded: ${live.size} entries (${fromDisk.size - live.size} expired)" }
                entries = live
                live
            }
        }
    }

    /** [kind] names the field ("badges", "credits", "interests") and goes into the counters the perf log prints. */
    suspend fun <T> get(kind: String, key: String, serializer: KSerializer<T>): T? {
        if (lifetimeSettingMs() <= 0L) return null
        val map = loaded()
        val entry = mutex.withLock { map["$kind|$key"] }
        val now = WatchProgressClock.nowEpochMs()
        val value = entry?.takeIf { now - it.savedAtMs in 0..it.lifetimeMs }
            ?.let { runCatching { json.decodeFromString(serializer, it.payload) }.getOrNull() }
        PerfTelemetry.count(if (value != null) "HeroBundle.hit.$kind" else "HeroBundle.miss.$kind")
        return value
    }

    suspend fun <T> put(kind: String, key: String, value: T, serializer: KSerializer<T>, isEmpty: Boolean, isSeries: Boolean) {
        val setting = lifetimeSettingMs()
        if (setting <= 0L) return
        var lifetime = setting
        if (isSeries) lifetime = minOf(lifetime, SERIES_MAX_MS)
        if (isEmpty) lifetime = minOf(lifetime, EMPTY_MAX_MS)
        val map = loaded()
        val payload = json.encodeToString(serializer, value)
        mutex.withLock {
            map["$kind|$key"] = Entry(payload, WatchProgressClock.nowEpochMs(), lifetime)
            while (map.size > MAX_ENTRIES) {
                val oldest = map.minByOrNull { it.value.savedAtMs }?.key ?: break
                map.remove(oldest)
            }
        }
        persister.schedule()
    }

    /** Writes any pending changes now. For the exit path. */
    suspend fun flushPendingWrites() {
        persister.flush()
    }
}
