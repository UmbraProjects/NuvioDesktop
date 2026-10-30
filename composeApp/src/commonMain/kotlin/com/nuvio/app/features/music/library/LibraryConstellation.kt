package com.nuvio.app.features.music.library

/**
 * Library views, phase C0: the Constellation's similarity graph, as pure data (no Compose, so it is unit-tested).
 *
 * Each saved artist is a star. Stars are compared by the ladder's own maths: the artist's [TagProfile]
 * ([tagProfile], genres, tags, mood and style) and [cosine]. Unlike the ladder, no target genre is taken out,
 * because here a shared genre is exactly what should pull two stars together.
 *
 * Every star is linked to its nearest [CONSTELLATION_NEIGHBOURS] neighbours whose similarity is at least
 * [CONSTELLATION_MIN_SIMILARITY], so the lines form constellations rather than a hairball. A link is drawn
 * when either star picks the other; it is [ConstellationLink.mutual] when both do. The similarity on a link is the real
 * tag similarity, never a map distance, so a line is never a lie about the sound.
 *
 * Artists with no usable tags have no profile: they are not placed, and wait in [ConstellationGraph.untagged].
 */

/** Links per star. */
internal const val CONSTELLATION_NEIGHBOURS = 3

/** Least cosine similarity worth a line. */
internal const val CONSTELLATION_MIN_SIMILARITY = 0.35

/** Fewer placeable artists than this is not a map. */
internal const val CONSTELLATION_MIN_ARTISTS = 3

internal data class ConstellationStar(val artist: LibraryArtist, val profile: TagProfile) {
    val id: String get() = artist.id
}

/** A line between two stars. [a] sorts before [b] by artist id, so a pair has one spelling. */
internal data class ConstellationLink(val a: String, val b: String, val similarity: Double, val mutual: Boolean)

internal data class ConstellationGraph(
    /** The placeable artists, ordered by artist id so the same library always gives the same graph. */
    val stars: List<ConstellationStar>,
    val links: List<ConstellationLink>,
    /** Artists with no usable genres or tags yet, in library order of id. They join once the enricher fills their tags. */
    val untagged: List<LibraryArtist>,
) {
    val placedCount: Int get() = stars.size

    /** False when there are too few placeable artists ([CONSTELLATION_MIN_ARTISTS]): the view says so instead of drawing. */
    val canDraw: Boolean get() = stars.size >= CONSTELLATION_MIN_ARTISTS

    /** Ids and similarities of the stars linked to [id], best first. */
    fun neighbours(id: String): List<Pair<String, Double>> =
        links.mapNotNull { l ->
            when (id) {
                l.a -> l.b to l.similarity
                l.b -> l.a to l.similarity
                else -> null
            }
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
}

internal fun buildConstellationGraph(artists: List<LibraryArtist>): ConstellationGraph {
    val byId = artists.sortedBy { it.id }
    val (placed, untagged) = byId
        .map { ConstellationStar(it, it.tagProfile()) to it }
        .partition { (star, _) -> star.profile.isNotEmpty() }
    val stars = placed.map { it.first }

    // Each star's picks: the nearest few above the threshold, best first, ties by id (stars are already in id order).
    val picks = stars.map { me ->
        stars.asSequence()
            .filter { it.id != me.id }
            .map { other -> other.id to cosine(me.profile, other.profile) }
            .filter { it.second >= CONSTELLATION_MIN_SIMILARITY }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(CONSTELLATION_NEIGHBOURS)
            .map { it.first to it.second }
            .toList()
    }

    val picked = HashSet<Pair<String, String>>() // (picker, picked)
    stars.forEachIndexed { i, star -> picks[i].forEach { (other, _) -> picked += star.id to other } }
    val links = LinkedHashMap<Pair<String, String>, ConstellationLink>()
    stars.forEachIndexed { i, star ->
        for ((other, sim) in picks[i]) {
            val key = if (star.id < other) star.id to other else other to star.id
            if (key in links) continue
            links[key] = ConstellationLink(
                a = key.first,
                b = key.second,
                similarity = sim,
                mutual = (star.id to other) in picked && (other to star.id) in picked,
            )
        }
    }
    return ConstellationGraph(
        stars = stars,
        links = links.values.sortedWith(compareBy({ it.a }, { it.b })),
        untagged = untagged.map { it.second },
    )
}
