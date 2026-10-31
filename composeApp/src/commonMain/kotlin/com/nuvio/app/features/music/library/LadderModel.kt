package com.nuvio.app.features.music.library

import kotlin.math.sqrt

/**
 * Ladder phase 1: scoring, as pure data (no Compose, no network, so it is unit-tested).
 *
 * Everything is a [TagProfile]: genre key to weight, built from the tags an artist carries. Two numbers
 * come out of it for a candidate artist and a target genre:
 *
 *  - [purity]: how central the candidate is to the target genre (its share of the candidate's tag votes).
 *  - [proximity]: how close the candidate is to the listener's [footprint], with the target genre taken
 *    out of both sides, so it measures what they share *beyond* the target. Without that, every artist
 *    in the genre would look equally close to a listener who owns one of them.
 *
 * Phase 2 picks the five rungs from these. Genres are compared by [genreKey] and named by [canonicalGenre]
 * (phase 0), so spelling variants and junk tags never reach the maths.
 */

/** Genre key (or [MOOD_PREFIX] key) to weight. Weights are positive; a ready profile has length 1. */
internal typealias TagProfile = Map<String, Double>

/** Moods share the vector with genres but never collide with one: "mood:melancholy" is not a genre. */
internal const val MOOD_PREFIX = "mood:"

private const val GENRE_WEIGHT = 1.0
private const val TAG_WEIGHT = 0.5
private const val TAG_LIMIT = 8
private const val DESCRIPTOR_WEIGHT = 0.6
private const val DESCRIPTOR_LIMIT = 3

/** A starred artist counts this many times in the footprint. */
internal const val FAVOURITE_FACTOR = 2.0

private val descriptorSplit = Regex("[/,;]")

private fun MutableMap<String, Double>.addWeight(key: String, weight: Double) {
    if (key.isNotEmpty() && weight > 0.0) merge(key, weight, Double::plus)
}

/** [profile] scaled to length 1, or empty when it has no weight. Cosine of two such profiles is their dot product. */
internal fun unitProfile(profile: Map<String, Double>): TagProfile {
    val norm = sqrt(profile.values.sumOf { it * it })
    if (norm <= 0.0) return emptyMap()
    return profile.mapValues { it.value / norm }
}

/** Mood and style are free text ("Alternative/Indie", "Melancholy, Dreamy"): split them and clean each part. */
private fun descriptorParts(text: String?): List<String> =
    text.orEmpty().split(descriptorSplit).map { it.trim() }.filter { it.isNotEmpty() }.take(DESCRIPTOR_LIMIT)

private fun MutableMap<String, Double>.addDescriptors(mood: String?, style: String?) {
    for (part in normalizeGenres(descriptorParts(style))) addWeight(genreKey(part), DESCRIPTOR_WEIGHT)
    for (part in descriptorParts(mood)) {
        val key = genreKey(part)
        if (key.length >= 2) addWeight(MOOD_PREFIX + key, DESCRIPTOR_WEIGHT)
    }
}

/**
 * A profile from lists with no counts, like [LibraryArtist]: [genres] (MusicBrainz's curated list, best
 * first) weigh most, [tags] less, and each position further down the list weighs less again. [style] and
 * [mood] add a little. A source that has none of it gives an empty profile.
 */
internal fun tagProfile(
    genres: List<String>,
    tags: List<String> = emptyList(),
    mood: String? = null,
    style: String? = null,
): TagProfile {
    val raw = HashMap<String, Double>()
    normalizeGenres(genres).forEachIndexed { i, g -> raw.addWeight(genreKey(g), GENRE_WEIGHT / (1 + i)) }
    normalizeGenres(tags).take(TAG_LIMIT).forEachIndexed { i, g -> raw.addWeight(genreKey(g), TAG_WEIGHT / (1 + i)) }
    raw.addDescriptors(mood, style)
    return unitProfile(raw)
}

/**
 * A profile from tag votes, like a MusicBrainz search hit: [votes] (spelling variants merged, junk dropped)
 * scaled so the top tag is 1.0, so a long tail of one-vote tags stays quiet. [style] and [mood] add a little.
 */
internal fun tagProfileFromVotes(
    votes: Map<String, Int>,
    mood: String? = null,
    style: String? = null,
): TagProfile {
    val merged = normalizeWeightedGenres(votes).filterValues { it > 0 }
    val top = merged.values.maxOrNull()?.toDouble() ?: 0.0
    val raw = HashMap<String, Double>()
    if (top > 0.0) for ((name, count) in merged) raw.addWeight(genreKey(name), count / top)
    raw.addDescriptors(mood, style)
    return unitProfile(raw)
}

/**
 * The profile of one saved artist, from the genres, tags, mood and style the library stores for it. Genres the person
 * chose ([LibraryArtist.manualGenres]) lead, so an artist no source knows still gets a place.
 */
internal fun LibraryArtist.tagProfile(): TagProfile = tagProfile(manualGenres + genres, tags, mood, style)

/**
 * What the listener's saved artists add up to: each artist's profile summed (a favourite counts
 * [FAVOURITE_FACTOR] times) and scaled to length 1. Empty when there are no artists or none has tags;
 * the ladder then falls back to purity alone.
 */
internal fun footprint(artists: List<LibraryArtist>): TagProfile {
    val sum = HashMap<String, Double>()
    for (artist in artists) {
        val factor = if (artist.favourite) FAVOURITE_FACTOR else 1.0
        for ((key, weight) in artist.tagProfile()) sum.addWeight(key, weight * factor)
    }
    return unitProfile(sum)
}

/** The comparison key of a target the person typed or picked ("DnB" gives the key of "drum and bass"), or null for a non-genre. */
internal fun targetGenreKey(raw: String): String? = canonicalGenre(raw)?.let(::genreKey)

/** Cosine similarity of two profiles, 0.0 to 1.0. Either side empty gives 0.0. */
internal fun cosine(a: TagProfile, b: TagProfile): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val (small, large) = if (a.size <= b.size) a to b else b to a
    var dot = 0.0
    for ((key, weight) in small) dot += weight * (large[key] ?: 0.0)
    val na = sqrt(a.values.sumOf { it * it })
    val nb = sqrt(b.values.sumOf { it * it })
    if (na <= 0.0 || nb <= 0.0) return 0.0
    return (dot / (na * nb)).coerceIn(0.0, 1.0)
}

/**
 * How close [candidate] is to the listener's [footprint], 0.0 to 1.0, ignoring the genres in [excluded]
 * (the target). Both sides are re-scaled after the target is removed, so what is left is compared fairly.
 */
internal fun proximity(candidate: TagProfile, footprint: TagProfile, excluded: Set<String>): Double =
    cosine(
        unitProfile(candidate.filterKeys { it !in excluded }),
        unitProfile(footprint.filterKeys { it !in excluded }),
    )

/**
 * How central the artist behind [votes] is to the genre [targetKey], 0.0 to 1.0: the target's share of
 * all its genre votes once variants are merged and junk dropped. An artist with no usable votes gives 0.0.
 */
internal fun purity(votes: Map<String, Int>, targetKey: String): Double = shrunkPurity(votes, targetKey, 0.0)

/**
 * [purity] with the evidence counted: the target's votes over all genre votes plus [priorVotes] imaginary votes for
 * something else. One vote for the genre out of one is 1/(1+k), not 100%; forty out of forty is still close to 1.
 * With [priorVotes] 0.0 this is exactly [purity].
 */
internal fun shrunkPurity(votes: Map<String, Int>, targetKey: String, priorVotes: Double): Double {
    val merged = normalizeWeightedGenres(votes)
    val total = merged.values.sum()
    if (total <= 0) return 0.0
    val target = merged.entries.filter { genreKey(it.key) == targetKey }.sumOf { it.value }
    return target.toDouble() / (total + priorVotes.coerceAtLeast(0.0))
}
