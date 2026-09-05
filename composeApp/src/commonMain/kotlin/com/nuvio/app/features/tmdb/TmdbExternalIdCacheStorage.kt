package com.nuvio.app.features.tmdb

/**
 * Disk-backed TMDB ↔ IMDb id map — plan §20.2.
 *
 * The hide-watched prune pass resolves an IMDb id per generated title, which measured **495
 * `external_ids` calls in a single Discover build** and starved the row fetches of TMDB permits for
 * 25 seconds. Those calls ask for a mapping that never changes: a title's IMDb id is a fact about
 * the world, not a piece of state. Caching it in memory only meant paying for it again on every
 * launch.
 *
 * **Not profile-scoped.** The mapping is identical for every profile on the machine, and scoping it
 * would make each new profile re-fetch the same immutable answers.
 *
 * **Positive mappings only.** "This TMDB record has no IMDb id" is a different kind of claim — it
 * can stop being true when the record is edited upstream — so negatives stay in memory for the
 * session and are re-checked on the next launch.
 */
internal expect object TmdbExternalIdCacheStorage {
    /** The stored map, keyed `"<tmdbId>:<movie|tv>"` with an IMDb id as the value. */
    fun load(): Map<String, String>

    fun save(entries: Map<String, String>)
}

/**
 * Ceiling on stored entries. Comfortably past a large library — 20k mappings is roughly 700KB — and
 * the oldest are dropped first, so a long-lived install trims rather than growing without bound.
 */
internal const val TMDB_EXTERNAL_ID_CACHE_LIMIT = 20_000

/**
 * Applies [TMDB_EXTERNAL_ID_CACHE_LIMIT], dropping the oldest entries first.
 *
 * Relies on the map being insertion-ordered, which it is — the caller snapshots a `LinkedHashMap`.
 * The most recently resolved ids are the most likely to be wanted again, so they are the ones kept.
 */
internal fun capExternalIdEntries(entries: Map<String, String>): Map<String, String> =
    if (entries.size <= TMDB_EXTERNAL_ID_CACHE_LIMIT) {
        entries
    } else {
        entries.entries
            .drop(entries.size - TMDB_EXTERNAL_ID_CACHE_LIMIT)
            .associate { it.key to it.value }
    }
