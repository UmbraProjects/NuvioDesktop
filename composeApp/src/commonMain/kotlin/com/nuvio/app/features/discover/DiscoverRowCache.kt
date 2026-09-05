package com.nuvio.app.features.discover

import com.nuvio.app.features.home.MetaPreview
import kotlinx.serialization.Serializable

/**
 * On-disk form of a built Discover tab.
 *
 * The tab's hour-long cache used to live only in memory, so every launch paid a full cold build —
 * ~4.4s and 25-30 TMDB requests — to rebuild rows that had not changed. This persists the result so
 * the second launch inside the hour is free.
 *
 * [VERSION] is checked on load and a mismatch is a miss, not a merge. It is the escape hatch for
 * every shape change this entry or [DiscoverCachedItem] can undergo: bump it and the stale file is
 * ignored.
 *
 * **Every field added here needs a default**, or the version check never gets to run: a required
 * field missing from an older entry fails deserialisation first, and the file is then discarded as
 * unreadable rather than as superseded. The outcome is the same — rebuild — but "unreadable" reads
 * as corruption in a log where the truth is an expected upgrade. Adding `profileId` without a
 * default is exactly how that was found.
 */
@Serializable
internal data class DiscoverRowCacheEntry(
    val version: Int = VERSION,
    val builtAtEpochMs: Long,
    /**
     * The two signatures the in-memory cache already keys on, stored verbatim. Hydration restores
     * them alongside the rows so [DiscoverRecommendationsRepository.refresh]'s existing freshness
     * logic decides what to do with them — no second, parallel set of invalidation rules.
     */
    val settingsSignature: String,
    val seedSignature: String?,
    /**
     * The profile these rows were built for. Discover is derived from watch history, which is
     * per-profile, so another profile's rows are not stale — they are somebody else's.
     *
     * Recorded here rather than enforced by clearing the file on every switch, because the check
     * is what makes correctness independent of *when* the switch is noticed. Startup enters a
     * profile through the same code path as a real switch, and a teardown that cannot tell the two
     * apart deletes the cache on every launch.
     */
    val profileId: Int = UNKNOWN_PROFILE,
    val rows: List<DiscoverCachedRow> = emptyList(),
) {
    companion object {
        /** Bump on any change to this entry's shape or to what [DiscoverCachedItem] carries. */
        /**
         * 3: Discover backdrops moved from w780 to w1280. The shape did not change, but every
         * cached row holds the old low-resolution URLs and would keep serving them for the rest of
         * the TTL — a fix nobody would see for an hour is indistinguishable from no fix.
         *
         * 4: [DiscoverCachedItem.imdbRating] and [DiscoverCachedItem.recommendationReason]. Both
         * default to null, so a version-3 file would deserialise cleanly and hydrate rows with no
         * rating badge and no AI reasons for the rest of the hour — readable, and wrong in exactly
         * the way nobody would report.
         */
        const val VERSION = 4

        /** Stand-in for an entry written before [profileId] existed; never matches a real profile. */
        const val UNKNOWN_PROFILE = -1
    }
}

@Serializable
internal data class DiscoverCachedRow(
    val key: String,
    val title: String,
    val entryId: String,
    val items: List<DiscoverCachedItem>,
)

/**
 * A row item, narrowed to the fields the generated rows actually populate.
 *
 * **This is deliberately not a serialised [MetaPreview].** That model is large, shared, and owned
 * by the UI; making it `@Serializable` would tie the on-disk format to a class that changes for
 * reasons which have nothing to do with this cache, and every field added to it would silently
 * become part of the format.
 *
 * The subset is not arbitrary and is not a guess: it is exactly what
 * `TmdbSearchResult.toMetaPreview`, `WatchProgressEntry.toMetaPreview`, `withCustomLibraryPoster`
 * and the `toRecommendationRow` mappings for imported and AI rows between them set — the only
 * things that ever build a Discover row item. That makes the
 * round trip **lossless for these rows** rather than merely adequate, which matters because a
 * restored row must be indistinguishable from a freshly built one; a Discover row can seed the
 * hero, and a missing `description` there is a blank synopsis, not a missing poster.
 *
 * If a generator starts setting a field that is not here, add it and bump
 * [DiscoverRowCacheEntry.VERSION] — `DiscoverRowCacheTest` fails loudly in that case rather than
 * letting the loss go unnoticed.
 */
@Serializable
internal data class DiscoverCachedItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterFallback: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val popularity: Double? = null,
    val imdbRating: String? = null,
    val recommendationReason: String? = null,
)

internal fun MetaPreview.toCachedItem(): DiscoverCachedItem = DiscoverCachedItem(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterFallback = posterFallback,
    banner = banner,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    popularity = popularity,
    imdbRating = imdbRating,
    recommendationReason = recommendationReason,
)

internal fun DiscoverCachedItem.toMetaPreview(): MetaPreview = MetaPreview(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterFallback = posterFallback,
    banner = banner,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    popularity = popularity,
    imdbRating = imdbRating,
    recommendationReason = recommendationReason,
)

internal fun DiscoverRecommendationRow.toCachedRow(): DiscoverCachedRow = DiscoverCachedRow(
    key = key,
    title = title,
    entryId = entryId,
    items = items.map { it.toCachedItem() },
)

internal fun DiscoverCachedRow.toRow(): DiscoverRecommendationRow = DiscoverRecommendationRow(
    key = key,
    title = title,
    entryId = entryId,
    items = items.map { it.toMetaPreview() },
)

/** Persists the built Discover rows across launches. */
internal expect object DiscoverRowCacheStorage {
    fun load(): String?
    fun save(json: String?)
}
