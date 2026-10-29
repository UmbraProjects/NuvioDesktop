package com.nuvio.app.features.music.torbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * What TorBox had for each album when it was last asked, kept on disk so reopening an album next
 * week costs no search. Pure: the codec, the freshness rules and the pruning, with no storage or
 * clock of its own, so they are unit tested. TorboxMusicSource reads and writes it.
 */

/** How long a "found" answer is trusted. Cached torrents do drop off TorBox, so not forever; a stale one is also retried at play time. */
internal const val TORBOX_FOUND_TTL_MS = 7L * 24 * 60 * 60 * 1000

/** How long "not cached" is trusted. Short, because a copy can be cached at any time. */
internal const val TORBOX_NONE_TTL_MS = 24L * 60 * 60 * 1000

private const val MAX_ENTRIES = 500

/** The best cached torrents for an album, best first. Empty means the search ran and found none. */
@Serializable
internal data class StoredLookup(
    val savedAt: Long,
    val hits: List<StoredHit> = emptyList(),
)

@Serializable
internal data class StoredHit(
    val hash: String,
    val title: String,
    val magnet: String? = null,
    val seeders: Int = 0,
    val sizeBytes: Long = 0,
)

@Serializable
internal data class TorboxLookupBook(val lookups: Map<String, StoredLookup> = emptyMap())

private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodeTorboxLookups(raw: String?): TorboxLookupBook =
    raw?.takeIf { it.isNotBlank() }
        ?.let { runCatching { codec.decodeFromString(TorboxLookupBook.serializer(), it) }.getOrNull() }
        ?: TorboxLookupBook()

internal fun encodeTorboxLookups(book: TorboxLookupBook): String =
    codec.encodeToString(TorboxLookupBook.serializer(), book)

/** True while [entry] can still be believed at [nowMs]. */
internal fun StoredLookup.isFresh(nowMs: Long): Boolean {
    val age = nowMs - savedAt
    if (age < 0) return false // a clock that went backwards: ask again rather than trust it
    return age < if (hits.isEmpty()) TORBOX_NONE_TTL_MS else TORBOX_FOUND_TTL_MS
}

/** [book] with [entry] for [key], dropping the oldest entries past the limit. */
internal fun TorboxLookupBook.withLookup(key: String, entry: StoredLookup): TorboxLookupBook {
    val next = lookups + (key to entry)
    if (next.size <= MAX_ENTRIES) return TorboxLookupBook(next)
    val keep = next.entries.sortedByDescending { it.value.savedAt }.take(MAX_ENTRIES).associate { it.key to it.value }
    return TorboxLookupBook(keep)
}

internal fun TorboxLookupBook.without(key: String): TorboxLookupBook = TorboxLookupBook(lookups - key)

internal fun TorboxSearchHit.toStored() = StoredHit(hash, title, magnet, seeders, sizeBytes)

/** A stored hit is only ever one TorBox said was cached, so it comes back cached. */
internal fun StoredHit.toHit() = TorboxSearchHit(hash, title, magnet, seeders, sizeBytes, cached = true)
