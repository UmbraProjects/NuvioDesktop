package com.nuvio.app.features.music.library

import kotlin.math.hypot

/**
 * Library views, phase C2: the Constellation's clusters, as pure data (no Compose, so it is unit-tested).
 *
 * Stars are grouped by their main genre: the strongest key of the artist's [TagProfile], by [genreKey]. A mood
 * ("mood:melancholy") never names a group while the artist has a genre to name it by. Ties go to the key that
 * sorts first, so the same library always gives the same groups.
 *
 * A group of one is not a cluster: its star joins the group of the star it is most like (real tag similarity,
 * [cosine], not map distance; a star that shares nothing with any group falls back to the nearest on the map).
 * Only groups that already had two or more stars can take a newcomer, so a lone star never lands on another
 * lone star, and the result does not depend on the order singletons are looked at. If every group is a
 * singleton (a library with no two artists sharing a main genre) nothing merges and each star stays its own cluster.
 *
 * A cluster is named after its genre as the artists spell it ([normalizeGenres] gives the readable name), sits at the
 * centroid of its stars and carries a halo radius that covers them all. The biggest [CLUSTER_CHIPS] are the chips
 * along the top of the view.
 */

/** Chips along the top of the view: the library's biggest clusters. */
internal const val CLUSTER_CHIPS = 8

internal data class StarCluster(
    /** The main genre key the group was formed on (a merged star keeps its own genre; the cluster keeps this). */
    val key: String,
    /** The readable genre name for the label. */
    val label: String,
    /** Artist ids in the cluster, sorted. */
    val memberIds: List<String>,
    /** Label position: the centroid of the members' stars, in unit-box coordinates. */
    val x: Double,
    val y: Double,
    /** Radius of the faint halo, in unit-box units: reaches the far edge of the farthest star. */
    val haloRadius: Double,
) {
    val size: Int get() = memberIds.size
}

internal data class ConstellationClusters(
    /** Every cluster, biggest first; ties by key. */
    val clusters: List<StarCluster>,
    /** Artist id to the key of the cluster the star belongs to. */
    val clusterKeyOf: Map<String, String>,
) {
    /** The genre chips: the biggest [CLUSTER_CHIPS] clusters, in size order. */
    val chips: List<StarCluster> get() = clusters.take(CLUSTER_CHIPS)

    fun clusterOf(artistId: String): StarCluster? = clusterKeyOf[artistId]?.let { key -> clusters.firstOrNull { it.key == key } }
}

/** The key a star is grouped on: its strongest genre key, or (only for an artist with nothing but moods) its strongest mood. */
internal fun mainGenreKey(profile: TagProfile): String? {
    fun strongest(entries: List<Map.Entry<String, Double>>): String? =
        entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).firstOrNull()?.key
    val genres = profile.entries.filter { !it.key.startsWith(MOOD_PREFIX) }
    return strongest(genres) ?: strongest(profile.entries.toList())
}

/** The readable name for [key]: the genre as the first member that has it spells it, else the key itself. */
internal fun clusterLabel(key: String, members: List<LibraryArtist>): String {
    if (key.startsWith(MOOD_PREFIX)) return key.removePrefix(MOOD_PREFIX)
    for (artist in members) {
        val styleParts = artist.style.orEmpty().split(Regex("[/,;]")).map { it.trim() }.filter { it.isNotEmpty() }
        val names = normalizeGenres(artist.genres) + normalizeGenres(artist.tags) + normalizeGenres(styleParts)
        names.firstOrNull { genreKey(it) == key }?.let { return it }
    }
    return canonicalGenre(key) ?: key
}

internal fun buildConstellationClusters(graph: ConstellationGraph, layout: ConstellationLayout): ConstellationClusters {
    val stars = graph.stars // already in id order
    if (stars.isEmpty()) return ConstellationClusters(emptyList(), emptyMap())

    // 1. Group on the main genre.
    val keyOf = HashMap<String, String>()
    val groups = LinkedHashMap<String, MutableList<ConstellationStar>>()
    for (star in stars) {
        val key = mainGenreKey(star.profile) ?: continue
        keyOf[star.id] = key
        groups.getOrPut(key) { mutableListOf() } += star
    }

    // 2. Singletons join the group of their most similar star, among groups that began with two or more.
    val anchors = groups.values.filter { it.size >= 2 }.flatten()
    if (anchors.isNotEmpty()) {
        val points = layout.points.associateBy { it.id }
        for (star in groups.values.filter { it.size == 1 }.map { it.single() }) {
            var best: ConstellationStar? = null
            var bestSimilarity = 0.0
            for (other in anchors) { // id order, strict > keeps the earliest id on a tie
                val similarity = cosine(star.profile, other.profile)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    best = other
                }
            }
            if (best == null) { // shares nothing with any group: the nearest on the map
                val mine = points[star.id]
                best = if (mine == null) anchors.first() else anchors.minByOrNull { other ->
                    points[other.id]?.let { hypot(it.x - mine.x, it.y - mine.y) } ?: Double.MAX_VALUE
                }
            }
            keyOf[star.id] = keyOf.getValue(best!!.id)
        }
    }

    // 3. Build the clusters.
    val points = layout.points.associateBy { it.id }
    val byKey = stars.filter { it.id in keyOf }.groupBy { keyOf.getValue(it.id) }
    val clusters = byKey.map { (key, members) ->
        val ids = members.map { it.id }.sorted()
        val placed = ids.mapNotNull { points[it] }
        val cx = if (placed.isEmpty()) 0.5 else placed.sumOf { it.x } / placed.size
        val cy = if (placed.isEmpty()) 0.5 else placed.sumOf { it.y } / placed.size
        val halo = placed.maxOfOrNull { hypot(it.x - cx, it.y - cy) + it.radius } ?: 0.0
        StarCluster(key, clusterLabel(key, members.map { it.artist }), ids, cx, cy, halo)
    }.sortedWith(compareByDescending<StarCluster> { it.size }.thenBy { it.key })
    return ConstellationClusters(clusters, keyOf.toMap())
}
