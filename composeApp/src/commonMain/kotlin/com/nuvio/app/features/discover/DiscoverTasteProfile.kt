package com.nuvio.app.features.discover

import kotlin.math.ln

/**
 * One genre as the user sees it, and the TMDB genre it means in each namespace.
 *
 * TMDB keeps two genre vocabularies that disagree on both numbering *and* wording: films have
 * "Action" and "Adventure" where television has one "Action & Adventure"; films have "Science
 * Fiction" and "Fantasy" where television has "Sci-Fi & Fantasy"; "War" is "War & Politics" there.
 * This list used to be the raw union of the two, so the user was offered "Action" and
 * "Action & Adventure" as separate choices and had to tick both to mean one thing.
 *
 * Now there is one entry per idea, and the mapping to each namespace is internal. A genre that
 * exists in only one namespace has `null` for the other — [movie] `null` on "Kids", [tv] `null` on
 * "Horror" — which is a fact about TMDB, not an omission: television has no horror genre, and a
 * query for one would be a query for nothing.
 *
 * Hardcoded rather than fetched because the settings page has to render the choices before any
 * network call has happened, and because a list the user has ticked boxes in must not reorder or
 * lose entries when TMDB is unreachable. The same reasoning is why `RandomPlayGenres` is a constant.
 */
data class DiscoverGenre(
    val name: String,
    val movie: String?,
    val tv: String?,
) {
    fun tmdbName(mediaType: String): String? = if (mediaType == "tv") tv else movie
}

val DiscoverGenres: List<DiscoverGenre> = listOf(
    DiscoverGenre("Action", movie = "Action", tv = "Action & Adventure"),
    DiscoverGenre("Adventure", movie = "Adventure", tv = "Action & Adventure"),
    DiscoverGenre("Animation", movie = "Animation", tv = "Animation"),
    DiscoverGenre("Comedy", movie = "Comedy", tv = "Comedy"),
    DiscoverGenre("Crime", movie = "Crime", tv = "Crime"),
    DiscoverGenre("Documentary", movie = "Documentary", tv = "Documentary"),
    DiscoverGenre("Drama", movie = "Drama", tv = "Drama"),
    DiscoverGenre("Family", movie = "Family", tv = "Family"),
    DiscoverGenre("Fantasy", movie = "Fantasy", tv = "Sci-Fi & Fantasy"),
    DiscoverGenre("History", movie = "History", tv = null),
    DiscoverGenre("Horror", movie = "Horror", tv = null),
    DiscoverGenre("Kids", movie = null, tv = "Kids"),
    DiscoverGenre("Music", movie = "Music", tv = null),
    DiscoverGenre("Mystery", movie = "Mystery", tv = "Mystery"),
    DiscoverGenre("News", movie = null, tv = "News"),
    DiscoverGenre("Reality", movie = null, tv = "Reality"),
    DiscoverGenre("Romance", movie = "Romance", tv = null),
    DiscoverGenre("Science Fiction", movie = "Science Fiction", tv = "Sci-Fi & Fantasy"),
    DiscoverGenre("Soap", movie = null, tv = "Soap"),
    DiscoverGenre("Talk", movie = null, tv = "Talk"),
    DiscoverGenre("Thriller", movie = "Thriller", tv = null),
    DiscoverGenre("TV Movie", movie = "TV Movie", tv = null),
    DiscoverGenre("War", movie = "War", tv = "War & Politics"),
    DiscoverGenre("Western", movie = "Western", tv = "Western"),
)

val DiscoverGenreNames: List<String> = DiscoverGenres.map { it.name }

/**
 * Which of ours a *combined* television genre reads as, coming back the other way.
 *
 * Each of these is queried by two of our genres — "Action & Adventure" by both Action and Adventure
 * — but a television title tagged with one has to rank as something, and it can only be one thing.
 * Stated explicitly because the alternative is letting the order of [DiscoverGenres] decide it
 * silently: alphabetically "Fantasy" precedes "Science Fiction", so sorting the display list was
 * quietly filing every science-fiction show under Fantasy. Either answer is defensible; having the
 * answer depend on an unrelated list's sort order is not.
 *
 * This is also the migration target for settings saved under the old raw-TMDB vocabulary.
 */
private val discoverCombinedGenrePrimary: Map<String, String> = mapOf(
    "action & adventure" to "Action",
    "sci-fi & fantasy" to "Science Fiction",
    "war & politics" to "War",
)

/**
 * Every TMDB genre name that resolves back to one of ours, lowercased.
 *
 * The combined names are claimed first, by [discoverCombinedGenrePrimary]; everything else is
 * unambiguous and claims itself. A name with no entry here ranks under its own spelling and cannot
 * be excluded from the settings page — see [canonicalDiscoverGenreName].
 */
private val discoverGenreByAlias: Map<String, String> = buildMap {
    putAll(discoverCombinedGenrePrimary)
    DiscoverGenres.forEach { genre ->
        listOfNotNull(genre.name, genre.movie, genre.tv).forEach { alias ->
            putIfAbsent(alias.trim().lowercase(), genre.name)
        }
    }
}

/**
 * A TMDB genre name, or a name stored by an older build, as one of [DiscoverGenreNames].
 *
 * Doubles as the migration for saved settings: a set holding "Sci-Fi & Fantasy" from before the two
 * vocabularies were merged resolves to "Science Fiction" instead of being dropped as unknown.
 * Returns null for a genre TMDB has added since — the caller decides whether to keep the raw name
 * (ranking does) or discard it (a settings list must, having nowhere to show it).
 */
fun canonicalDiscoverGenreName(name: String): String? = discoverGenreByAlias[name.trim().lowercase()]

/** The TMDB genre name [canonical] means for [mediaType], or null if that namespace has none. */
fun discoverGenreTmdbName(canonical: String, mediaType: String): String? =
    DiscoverGenres.firstOrNull { it.name.equals(canonical, ignoreCase = true) }?.tmdbName(mediaType)

/**
 * The genres of one resolved seed, as TMDB states them for that seed's media type.
 *
 * Kept separate from [DiscoverSeed] because the genres cost a request each to fetch, while the seed
 * itself is already in hand — and because this is the shape the pure weighting below needs.
 */
data class SeedGenres(
    /** `movie` or `tv` — the namespace [genres] ids belong to. */
    val mediaType: String,
    val lastWatchedAtEpochMs: Long,
    /** `(id, name)` pairs from TMDB's genre list for [mediaType]. */
    val genres: List<Pair<Int, String>>,
    /** How much of this title was watched. Damped, never linear — see [volumeWeight]. */
    val episodesWatched: Int = 1,
)

/**
 * A genre the user's history leans on.
 *
 * Identified by **name**, not id, because TMDB namespaces genre ids by media type and the two
 * namespaces disagree on both numbering and vocabulary — movies have "Action" (28) and "Adventure"
 * (12) where tv has the single "Action & Adventure" (10759). A row titled "Trending in Action"
 * therefore has to know which id means that for each type it queries, which is what
 * [idsByMediaType] carries. Merging on the id alone would silently mix two unrelated genres.
 */
data class GenreAffinity(
    val name: String,
    val idsByMediaType: Map<String, Int>,
    val weight: Double,
) {
    fun idFor(mediaType: String): Int? = idsByMediaType[mediaType]
}

/**
 * Ranks genres by how much of the user's recent watching they account for.
 *
 * Pure and synchronous so the ranking is testable without a network: everything upstream of it
 * (resolving seeds, fetching their genres) is IO, and everything downstream (which row to build)
 * is a policy decision. Ordering is weight-descending, name-ascending — the tiebreak matters
 * because equal-weight genres would otherwise reorder between builds and shuffle the user's rows.
 *
 * Two things scale a seed's contribution:
 *  - **Recency**, decaying rather than dropping off a cliff, so "what I watch now" outranks "what I
 *    watched ten months ago" without erasing it.
 *  - **Volume**, heavily damped ([volumeWeight]). A title counted once regardless of how much of it
 *    was watched puts 237 episodes of a sitcom on equal footing with one film watched once, and the
 *    resulting profile does not describe the user at all: it describes what they most recently
 *    *started*. Note this is the opposite call to the one seeding makes — there, one title is one
 *    seed no matter what, because the rule protects the *row budget* from a long-running show. A
 *    histogram has no budget to protect and every reason to weigh evidence.
 *
 * Multi-genre titles are deliberately *not* normalised by their genre count: a film tagged with four
 * genres genuinely is evidence for four genres. [excludedGenres] are canonical names
 * ([DiscoverGenreNames]) and are dropped before ranking, case-insensitively — excluding "Action"
 * therefore also drops television's "Action & Adventure", which is the point of the merge.
 */
fun buildGenreAffinities(
    seeds: List<SeedGenres>,
    now: Long,
    maxAgeMs: Long = SEED_MAX_AGE_MS,
    excludedGenres: Set<String> = emptySet(),
): List<GenreAffinity> {
    if (seeds.isEmpty()) return emptyList()

    val excluded = excludedGenres.mapTo(mutableSetOf()) { it.trim().lowercase() }
    val weights = mutableMapOf<String, Double>()
    val idsByName = mutableMapOf<String, MutableMap<String, Int>>()

    for (seed in seeds) {
        val weight = recencyWeight(
            lastWatchedAtEpochMs = seed.lastWatchedAtEpochMs,
            now = now,
            maxAgeMs = maxAgeMs,
        ) * volumeWeight(seed.episodesWatched)
        for ((id, rawName) in seed.genres) {
            val trimmed = rawName.trim()
            if (id <= 0 || trimmed.isEmpty()) continue
            // Ranked under the canonical name, so a film tagged "Action" and a show tagged
            // "Action & Adventure" are evidence for the same taste rather than two half-weight
            // genres that each fail to rank. An unrecognised name — a genre TMDB added since — keeps
            // its own spelling and still ranks; it just cannot be excluded from the settings page.
            val name = canonicalDiscoverGenreName(trimmed) ?: trimmed
            if (name.lowercase() in excluded) continue
            weights[name] = (weights[name] ?: 0.0) + weight
            // Ids stay per media type, which is the whole reason this is keyed by name: the merged
            // affinity carries movie 28 *and* tv 10759, and each query uses the one it needs.
            idsByName.getOrPut(name) { mutableMapOf() }[seed.mediaType] = id
        }
    }

    return weights.map { (name, weight) ->
        GenreAffinity(
            name = name,
            idsByMediaType = idsByName[name].orEmpty().toMap(),
            weight = weight,
        )
    }.sortedWith(compareByDescending<GenreAffinity> { it.weight }.thenBy { it.name })
}

/**
 * Linear decay from 1.0 (watched now) to [RECENCY_FLOOR] (watched [maxAgeMs] ago or longer).
 *
 * The floor is not zero on purpose — a seed old enough to have decayed to nothing would be better
 * excluded entirely (which [collapseWatchedToSeedCandidates] already does at [SEED_MAX_AGE_MS]),
 * and a genre that only ever appears on older seeds should still be able to rank.
 */
private fun recencyWeight(lastWatchedAtEpochMs: Long, now: Long, maxAgeMs: Long): Double {
    if (maxAgeMs <= 0L) return 1.0
    val age = (now - lastWatchedAtEpochMs).coerceAtLeast(0L)
    val ageFraction = (age.toDouble() / maxAgeMs.toDouble()).coerceIn(0.0, 1.0)
    return RECENCY_FLOOR + (1.0 - RECENCY_FLOOR) * (1.0 - ageFraction)
}

/**
 * Logarithmic, so watching more of something counts for more without letting one long-running show
 * own the profile: 240 episodes is worth roughly four times one film, not two hundred and forty.
 */
private fun volumeWeight(episodesWatched: Int): Double =
    1.0 + ln(1.0 + episodesWatched.coerceAtLeast(1).toDouble())

private const val RECENCY_FLOOR = 0.25
