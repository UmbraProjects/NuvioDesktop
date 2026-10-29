package com.nuvio.app.features.music.nebula

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * What the music addon had for each album when last asked, kept on disk so reopening an album costs no
 * search. Pure: the codec, freshness and pruning have no storage or clock of their own, so they are
 * unit tested. NebulaMusicSource reads and writes it. Stream links are never kept: they are fetched
 * fresh for each track.
 */

/** How long a match is trusted. The addon's ids are stable, but a record can be corrected, so not forever. */
internal const val NEBULA_FOUND_TTL_MS = 14L * 24 * 60 * 60 * 1000

/** How long "not found" is trusted. Short, because the addon's catalogue grows. */
internal const val NEBULA_NONE_TTL_MS = 24L * 60 * 60 * 1000

private const val MAX_ENTRIES = 500

/** The addon album matched to one of ours. [albumId] null means the search ran and found none. */
@Serializable
internal data class StoredLookup(
    val savedAt: Long,
    val albumId: String? = null,
    val albumTitle: String = "",
    /** Per track of our album, in order: the addon's track id, or null when it has none. */
    val trackIds: List<String?> = emptyList(),
)

@Serializable
internal data class NebulaLookupBook(val lookups: Map<String, StoredLookup> = emptyMap())

private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodeNebulaLookups(raw: String?): NebulaLookupBook =
    raw?.takeIf { it.isNotBlank() }
        ?.let { runCatching { codec.decodeFromString(NebulaLookupBook.serializer(), it) }.getOrNull() }
        ?: NebulaLookupBook()

internal fun encodeNebulaLookups(book: NebulaLookupBook): String =
    codec.encodeToString(NebulaLookupBook.serializer(), book)

/** True while this entry can still be believed at [nowMs]. */
internal fun StoredLookup.isFresh(nowMs: Long): Boolean {
    val age = nowMs - savedAt
    if (age < 0) return false // a clock that went backwards: ask again rather than trust it
    return age < if (albumId == null) NEBULA_NONE_TTL_MS else NEBULA_FOUND_TTL_MS
}

internal fun NebulaLookupBook.withLookup(key: String, entry: StoredLookup): NebulaLookupBook {
    val next = lookups + (key to entry)
    if (next.size <= MAX_ENTRIES) return NebulaLookupBook(next)
    val keep = next.entries.sortedByDescending { it.value.savedAt }.take(MAX_ENTRIES).associate { it.key to it.value }
    return NebulaLookupBook(keep)
}

internal fun NebulaLookupBook.without(key: String): NebulaLookupBook = NebulaLookupBook(lookups - key)
