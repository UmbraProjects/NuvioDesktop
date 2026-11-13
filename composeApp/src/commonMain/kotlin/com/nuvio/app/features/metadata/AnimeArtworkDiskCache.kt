package com.nuvio.app.features.metadata

import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.core.storage.CoalescingCachePersister
import com.nuvio.app.features.watchprogress.WatchProgressClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The anime artwork lookups on disk: one JSON map, per account. */
internal expect object AnimeArtworkStorage {
    fun load(): String?
    fun save(payload: String)
}

/**
 * Season backdrops and posters found on Kitsu or AniList, kept between launches. In memory only, every launch asked
 * Kitsu (and AniList for what Kitsu lacks) about every anime on Home again: 92 to 126 Kitsu and ~35 AniList requests
 * per launch (optimization audit, 2026-11-13). Only pictures that were found are kept, for 30 days: a miss cannot be
 * told apart from a failed request here, so misses are asked again next launch.
 */
internal object AnimeArtworkDiskCache {
    private val log = Logger.withTag("AnimeArtworkDisk")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null

    @Serializable
    private data class Entry(val url: String, val savedAtMs: Long)

    private val mapSerializer = MapSerializer(String.serializer(), Entry.serializer())
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 3_000

    private val persister = CoalescingCachePersister(tag = "animeArtwork", writeDelayMs = 30_000L) {
        val snapshot = mutex.withLock { entries?.toMap() } ?: return@CoalescingCachePersister
        runCatching { AnimeArtworkStorage.save(json.encodeToString(mapSerializer, snapshot)) }
            .onFailure { log.w { "Could not save anime artwork lookups: ${it.message}" } }
    }

    private suspend fun loaded(): MutableMap<String, Entry> {
        entries?.let { return it }
        val fromDisk = withContext(Dispatchers.Default) {
            runCatching { AnimeArtworkStorage.load()?.let { json.decodeFromString(mapSerializer, it) } }.getOrNull().orEmpty()
        }
        return mutex.withLock {
            entries ?: run {
                val now = WatchProgressClock.nowEpochMs()
                val live = fromDisk.filterValues { now - it.savedAtMs in 0..TTL_MS }.toMutableMap()
                log.i { "anime artwork loaded: ${live.size} pictures (${fromDisk.size - live.size} expired)" }
                entries = live
                live
            }
        }
    }

    suspend fun get(key: String): String? {
        val map = loaded()
        val url = mutex.withLock { map[key] }?.url
        PerfTelemetry.count(if (url != null) "AnimeArtworkDisk.hit" else "AnimeArtworkDisk.miss")
        return url
    }

    suspend fun put(key: String, url: String) {
        val map = loaded()
        mutex.withLock {
            map[key] = Entry(url, WatchProgressClock.nowEpochMs())
            while (map.size > MAX_ENTRIES) {
                val oldest = map.minByOrNull { it.value.savedAtMs }?.key ?: break
                map.remove(oldest)
            }
        }
        persister.schedule()
    }

    suspend fun flushPendingWrites() {
        persister.flush()
    }
}
