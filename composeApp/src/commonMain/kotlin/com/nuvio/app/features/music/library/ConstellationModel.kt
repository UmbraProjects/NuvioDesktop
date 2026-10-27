package com.nuvio.app.features.music.library

import kotlin.math.min

/**
 * Library views, phase C3 (pure part): everything the Constellation drawing needs that is not Compose, so it is
 * unit-tested. [ConstellationView] only draws what is here.
 *
 *  - [buildConstellationModel] runs C0 to C2 once (graph, layout, clusters) and stamps the result with a [ConstellationKeyRow]
 *    per artist. The key holds only what changes the map (id, genres, tags, style, mood, release-card count), so a
 *    palette arriving, a favourite toggled or a refresh that changes nothing does not rebuild it.
 *  - [mapFrame] maps the unit box to the canvas. The box is stretched along the longer side by at most
 *    [MAP_MAX_ASPECT] (a wide window is used, but the map is never flattened into a strip) and centred. Star radii use
 *    the shorter side, so a star stays round and, because distances only grow along the stretched axis, relaxed stars
 *    still never overlap.
 *  - [clusterLead] picks the star whose colour tints a cluster's halo; [drawsClusterLabel] says which clusters get a label.
 */

/** Longest the drawn map may be stretched relative to its shorter side. */
internal const val MAP_MAX_ASPECT = 1.6f

/** What about an artist changes the map. Everything else (palette, favourite, name) is read live when drawing. */
internal data class ConstellationKeyRow(
    val id: String,
    val genres: List<String>,
    val tags: List<String>,
    val style: String?,
    val mood: String?,
    val releaseCards: Int,
)

internal fun constellationKey(artists: List<LibraryArtist>): List<ConstellationKeyRow> =
    artists.sortedBy { it.id }.map {
        ConstellationKeyRow(it.id, it.genres, it.tags, it.style, it.mood, countReleaseCards(it.releases))
    }

internal class ConstellationModel(
    val key: List<ConstellationKeyRow>,
    val graph: ConstellationGraph,
    val layout: ConstellationLayout,
    val clusters: ConstellationClusters,
)

internal fun buildConstellationModel(artists: List<LibraryArtist>, key: List<ConstellationKeyRow> = constellationKey(artists)): ConstellationModel {
    val graph = buildConstellationGraph(artists)
    val layout = layoutConstellation(graph)
    return ConstellationModel(key, graph, layout, buildConstellationClusters(graph, layout))
}

/** The unit box placed on a canvas of [width] by [height] pixels. */
internal class MapFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    /** Pixels per unit for radii: the shorter side, so circles stay circles. */
    val unit: Float get() = min(width, height)

    fun x(u: Double): Float = left + (u * width).toFloat()
    fun y(u: Double): Float = top + (u * height).toFloat()
    fun radius(u: Double): Float = (u * unit).toFloat()
}

internal fun mapFrame(width: Float, height: Float): MapFrame {
    val w = width.coerceAtLeast(0f)
    val h = height.coerceAtLeast(0f)
    val usedW = min(w, h * MAP_MAX_ASPECT)
    val usedH = min(h, w * MAP_MAX_ASPECT)
    return MapFrame((w - usedW) / 2f, (h - usedH) / 2f, usedW, usedH)
}

/** Opacity of a line: faint near the threshold, firmer for close matches, and a little firmer when both stars picked each other. */
internal fun linkAlpha(similarity: Double, mutual: Boolean): Float {
    val span = 1.0 - CONSTELLATION_MIN_SIMILARITY
    val strength = ((similarity - CONSTELLATION_MIN_SIMILARITY) / span).coerceIn(0.0, 1.0)
    return (0.10 + 0.32 * strength + if (mutual) 0.08 else 0.0).toFloat()
}

/** The star that lends a cluster its colour: the biggest on the map, ties to the lowest id. Null for an empty cluster. */
internal fun clusterLead(cluster: StarCluster, layout: ConstellationLayout): String? {
    val points = layout.points.associateBy { it.id }
    return cluster.memberIds
        .mapNotNull { points[it] }
        .sortedWith(compareByDescending<StarPoint> { it.radius }.thenBy { it.id })
        .firstOrNull()?.id
}

/** A halo and label mean a group; a cluster of one star is just a star, so it gets neither. */
internal fun drawsClusterLabel(cluster: StarCluster): Boolean = cluster.size >= 2

/** [mapFrame] for a canvas of [width] by [height] with [pad] pixels kept clear on every side. */
internal fun paddedFrame(width: Float, height: Float, pad: Float): MapFrame {
    val inner = mapFrame(width - 2 * pad, height - 2 * pad)
    return MapFrame(inner.left + pad, inner.top + pad, inner.width, inner.height)
}
