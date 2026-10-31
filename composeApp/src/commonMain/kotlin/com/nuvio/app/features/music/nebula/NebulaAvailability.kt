package com.nuvio.app.features.music.nebula

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Whether the music addon has a release that YouTube Music does not, kept on disk so an artist page asks the addon
 * once, not on every visit. The addon has no artist or catalogue call, only a search per release, so the saving is all
 * in remembering: a "found" for two weeks, a "not found" for longer each time it repeats (the addon's catalogue grows,
 * but a release it lacked yesterday is unlikely to appear tomorrow). A failed ask is never kept, so it is asked again.
 *
 * Apart from [NebulaLookupBook], which is keyed by YouTube Music album id and holds full track matches: a release with
 * no YouTube Music id has no such key. Pure (codec, freshness, backoff, pruning), so it is unit tested; NebulaMusicSource
 * reads and writes it. Stream links are never kept.
 */

/** How long a match is trusted. */
internal const val ADDON_FOUND_TTL_MS = 14L * 24 * 60 * 60 * 1000

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Days a "not found" is trusted after the first, second and third-or-later miss in a row. */
internal val ADDON_MISS_BACKOFF_DAYS = listOf(3L, 7L, 14L)

/** Many more than [NebulaLookupBook]: one entry per release, and an artist can have dozens. */
internal const val ADDON_MAX_ENTRIES = 1000

/** What the addon had for one release when last asked. [albumId] null means the search ran and found none. */
@Serializable
internal data class AddonAvailability(
    val savedAt: Long,
    val albumId: String? = null,
    val albumTitle: String = "",
    /** The addon's own track count for the match, when it said. */
    val trackCount: Int? = null,
    /** Searches in a row that found nothing; sets how long the miss is trusted. */
    val misses: Int = 0,
) {
    val has: Boolean get() = albumId != null
}

@Serializable
internal data class AddonAvailabilityBook(val entries: Map<String, AddonAvailability> = emptyMap())

/** One release to look for: what the search and the strict match need, and the key its answer is kept under. */
internal data class AddonReleaseQuery(
    val cacheKey: String,
    val title: String,
    val artist: String,
    val year: String?,
    val trackCount: Int?,
)

/**
 * The key an answer is kept under. A release's own key is only stable inside one artist (a title-and-kind key repeats
 * across artists), so the artist's id is part of it.
 */
internal fun addonCacheKey(artistId: String, releaseKey: String): String = "$artistId|$releaseKey"

private val availabilityCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun decodeAddonAvailability(raw: String?): AddonAvailabilityBook =
    raw?.takeIf { it.isNotBlank() }
        ?.let { runCatching { availabilityCodec.decodeFromString(AddonAvailabilityBook.serializer(), it) }.getOrNull() }
        ?: AddonAvailabilityBook()

internal fun encodeAddonAvailability(book: AddonAvailabilityBook): String =
    availabilityCodec.encodeToString(AddonAvailabilityBook.serializer(), book)

/** How long this entry is believed: a match for [ADDON_FOUND_TTL_MS], a miss for the backoff its miss count has reached. */
internal fun AddonAvailability.ttlMs(): Long =
    if (albumId != null) ADDON_FOUND_TTL_MS
    else ADDON_MISS_BACKOFF_DAYS[(misses - 1).coerceIn(0, ADDON_MISS_BACKOFF_DAYS.lastIndex)] * DAY_MS

/** True while this entry can still be believed at [nowMs]. */
internal fun AddonAvailability.isFresh(nowMs: Long): Boolean {
    val age = nowMs - savedAt
    if (age < 0) return false // a clock that went backwards: ask again rather than trust it
    return age < ttlMs()
}

/** The entry for a search that found [album]. */
internal fun addonFound(nowMs: Long, album: NebulaAlbumSummary): AddonAvailability =
    AddonAvailability(savedAt = nowMs, albumId = album.id, albumTitle = album.title, trackCount = album.trackCount)

/** The entry for a search that found nothing: one more miss than [previous] had, unless [previous] was a match. */
internal fun addonMissed(nowMs: Long, previous: AddonAvailability?): AddonAvailability =
    AddonAvailability(savedAt = nowMs, misses = (previous?.takeIf { it.albumId == null }?.misses ?: 0) + 1)

internal fun AddonAvailabilityBook.withEntry(key: String, entry: AddonAvailability): AddonAvailabilityBook {
    val next = entries + (key to entry)
    if (next.size <= ADDON_MAX_ENTRIES) return AddonAvailabilityBook(next)
    val keep = next.entries.sortedByDescending { it.value.savedAt }.take(ADDON_MAX_ENTRIES).associate { it.key to it.value }
    return AddonAvailabilityBook(keep)
}

internal fun AddonAvailabilityBook.without(key: String): AddonAvailabilityBook = AddonAvailabilityBook(entries - key)
