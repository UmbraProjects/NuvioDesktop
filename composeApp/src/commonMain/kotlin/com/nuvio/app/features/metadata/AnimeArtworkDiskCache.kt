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
 * per launch (optimization audit, 2026-11-13). Pictures that were found are kept for 30 days.
 *
 * "Neither provider has one" is kept too, for [NONE_TTL_MS] (stored as an empty url): the service now tells that
 * answer apart from a failed request, which is never stored. Without it every launch asked again about the same
 * titles with no art, ~28 Kitsu lookups and as many AniList calls, 16 of them refused with 429 (2026-11-25).
 */
internal object AnimeArtworkDiskCache {
    private val log = Logger.withTag("AnimeArtworkDisk")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var entries: MutableMap<String, Entry>? = null

    @Serializable
    private data class Entry(val url: String, val savedAtMs: Long)

    /** What the disk knows about a key. */
    sealed interface Lookup {
        data class Found(val url: String) : Lookup
        data object KnownNone : Lookup
        data object Unknown : Lookup
    }

    private val mapSerializer = MapSerializer(String.serializer(), Entry.serializer())
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** Shorter: a new season's banner is often added on Kitsu/AniList a few days after it starts airing. */
    private const val NONE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_ENTRIES = 3_000

    private fun Entry.isLive(now: Long): Boolean =
        now - savedAtMs in 0..(if (url.isEmpty()) NONE_TTL_MS else TTL_MS)

    // 5 s, not 30: the file is ~40 KB, and launches that ended within 30 s by a path that skips main()'s exit flush
    // (a restart, exitProcess) never wrote what they had learned, so the next launch asked again (Nov 25).
    private val persister = CoalescingCachePersister(tag = "animeArtwork", writeDelayMs = 5_000L) {
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
                val live = fromDisk.filterValues { it.isLive(now) }.toMutableMap()
                log.i { "anime artwork loaded: ${live.size} pictures (${fromDisk.size - live.size} expired)" }
                entries = live
                live
            }
        }
    }

    suspend fun lookup(key: String): Lookup {
        val map = loaded()
        val now = WatchProgressClock.nowEpochMs()
        val entry = mutex.withLock { map[key] }?.takeIf { it.isLive(now) }
        val result = when {
            entry == null -> Lookup.Unknown
            entry.url.isEmpty() -> Lookup.KnownNone
            else -> Lookup.Found(entry.url)
        }
        PerfTelemetry.count(
            when (result) {
                is Lookup.Found -> "AnimeArtworkDisk.hit"
                Lookup.KnownNone -> "AnimeArtworkDisk.hitNone"
                Lookup.Unknown -> "AnimeArtworkDisk.miss"
            },
        )
        return result
    }

    /** Both providers answered and neither has this picture. */
    suspend fun putNone(key: String) = put(key, "")

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
