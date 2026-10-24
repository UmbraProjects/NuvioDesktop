package com.nuvio.app.features.discover

import com.nuvio.app.features.watched.WatchedItem

/**
 * One title the user has watched, as a candidate to recommend against.
 *
 * A seed is **a show, not a viewing**. Forty episodes of one series and one film both produce
 * exactly one seed, so a long-running show cannot crowd out everything else simply by having more
 * rows in the watch history. [episodesWatched] is kept as a signal (it says "this was a real
 * commitment, not a sampled first episode") but is deliberately *not* a weight multiplier.
 */
data class DiscoverSeedCandidate(
    /** Parent id exactly as the watch history stores it — may be `tt…`, `tmdb:…`, `kitsu:…`, … */
    val parentId: String,
    /** `movie` or `series`, as stored. */
    val type: String,
    val title: String,
    val lastWatchedAtEpochMs: Long,
    val episodesWatched: Int,
)

/** Content types the recommender understands; everything else (channel, tv, …) is dropped. */
private val SeedableTypes = setOf("movie", "series", "anime", "show", "tv")

/**
 * Collapses raw watch history into one candidate per title.
 *
 * Pure and synchronous: this half of seeding is the part worth testing, and it must not need a
 * network or a repository to exercise. Id reconciliation happens afterwards in
 * [DiscoverSeedService], because collapsing *across* id schemes (a kitsu id per anime season all
 * pointing at one TMDB show) cannot be decided without the mapping tables.
 *
 * Ordering is most-recently-watched first, so callers can simply `take(n)`.
 */
fun collapseWatchedToSeedCandidates(
    items: List<WatchedItem>,
    now: Long,
    maxAgeMs: Long = SEED_MAX_AGE_MS,
): List<DiscoverSeedCandidate> {
    val cutoff = now - maxAgeMs
    return items
        .asSequence()
        .filter { it.type.trim().lowercase() in SeedableTypes }
        .filter { it.markedAtEpochMs >= cutoff }
        .filter { it.id.isNotBlank() }
        // The watch-history key is (type, id, season, episode), so grouping on (type, id) is what
        // turns a season's worth of episode rows back into the one series they came from.
        .groupBy { seedGroupKey(it.type, it.id) }
        .map { (_, rows) ->
            val newest = rows.maxBy { it.markedAtEpochMs }
            DiscoverSeedCandidate(
                parentId = newest.id,
                type = newest.type.trim().lowercase(),
                // The newest row is the right source for *when*, but not always for *what*: some
                // watch-history rows are written with an empty name, so take the title from the
                // most recent row that actually has one. May still be blank when every row for a
                // title lacks a name — DiscoverSeedService recovers those from TMDB.
                title = rows.sortedByDescending { it.markedAtEpochMs }
                    .firstOrNull { it.name.isNotBlank() }
                    ?.name
                    .orEmpty(),
                lastWatchedAtEpochMs = newest.markedAtEpochMs,
                // Distinct episodes, not rows: the same episode can appear twice when a tracking
                // provider import lands on top of a local mark.
                episodesWatched = rows.mapNotNull { row ->
                    row.episode?.let { ep -> (row.season ?: 0) to ep }
                }.distinct().size.coerceAtLeast(if (rows.any { it.episode == null }) 1 else 0),
            )
        }
        .sortedByDescending { it.lastWatchedAtEpochMs }
}

private fun seedGroupKey(type: String, id: String): String =
    "${type.trim().lowercase()}:${id.trim()}"

/**
 * How far back watch history is considered. Recommendations should reflect current taste; a show
 * finished three years ago is not what the user wants suggested against today.
 */
const val SEED_MAX_AGE_MS: Long = 365L * 24 * 60 * 60 * 1000

/** TMDB media type for a stored content type. TMDB's movie and tv id spaces are disjoint. */
fun tmdbMediaTypeFor(storedType: String): String =
    when (storedType.trim().lowercase()) {
        "movie", "movies", "film" -> "movie"
        else -> "tv"
    }
