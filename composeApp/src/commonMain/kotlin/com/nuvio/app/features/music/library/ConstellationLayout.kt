package com.nuvio.app.features.music.library

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Library views, phase C1: where each star sits, as pure data (no Compose, so it is unit-tested).
 *
 * The same library always draws the same map:
 *
 *  1. **Project.** The tag profiles ([ConstellationStar.profile]) form an artist-by-genre matrix. Its two
 *     strongest axes of variation (PCA, by power iteration with a fixed iteration count) give each artist an x and
 *     a y, so artists that share genres land close together. The sign of each axis is fixed (its largest loading is
 *     positive), so a flip never mirrors the map.
 *  2. **Orient.** PCA only fixes the plane, not which way is "x" or which side is "up", and those can flip when one
 *     artist is added. The cloud is turned until its anchor star lies on the positive x axis and mirrored so its
 *     lopsided side is up ([orientScores]), so the usual addition moves each star only a little. When two axes are
 *     nearly as strong as each other the plane itself can still change; that is inherent to PCA.
 *  3. **Fit.** The cloud is scaled uniformly (aspect kept) and centred in the unit box with a margin.
 *  4. **Relax.** Stars closer than their radii are pushed apart. Every pair is judged from the same positions and the
 *     pushes are summed and damped (not applied one pair after another), so the result does not depend on the order
 *     stars are listed in and an added star disturbs only its neighbours. The smaller star of a pair moves further.
 *
 * Closeness is an honest 2D squash of tag similarity. Lines come from the real similarity ([ConstellationGraph.links]),
 * not from distances on this map.
 *
 * Coordinates are in a unit box: x and y in 0..1, radii in the same units. The view maps the box to its pixels.
 */

/** Power-iteration steps per axis. Fixed, so the result never depends on a convergence test. */
internal const val LAYOUT_ITERATIONS = 100

/** Empty border of the unit box that the fitted cloud keeps clear. */
internal const val LAYOUT_MARGIN = 0.06

/** Radius of the largest star; shrinks for crowded libraries (see [starRadii]: about a quarter of the box is covered by stars at most). */
internal const val MAX_STAR_RADIUS = 0.05

/** Most relaxation passes; it stops earlier when nothing overlaps. 300 clears libraries to about 150 artists; beyond ~300 two stars may touch by a pixel. */
internal const val RELAX_PASSES = 300

/** Share of the summed push applied per pass. */
internal const val RELAX_DAMPING = 0.5

/** Clear space kept between two stars after relaxing. */
internal const val STAR_GAP = 0.004

/** The anchor star is the first (by id) whose distance from the centre is at least this share of the farthest star's. */
internal const val ANCHOR_SHARE = 0.5

private const val EPS = 1e-12

/** One star on the map. [x], [y] and [radius] are in unit-box coordinates. */
internal data class StarPoint(val id: String, val x: Double, val y: Double, val radius: Double)

internal data class ConstellationLayout(val points: List<StarPoint>) {
    fun point(id: String): StarPoint? = points.firstOrNull { it.id == id }
}

/** The two principal axes of a set of profiles. [loading1] and [loading2] run over [keys] (sorted genre keys). */
internal class PcaAxes(
    val keys: List<String>,
    val scores: List<Pair<Double, Double>>,
    val loading1: DoubleArray,
    val loading2: DoubleArray,
)

/**
 * Projects [profiles] onto their two strongest axes. A profile is a sparse genre-key-to-weight map; columns are the
 * sorted keys so the result does not depend on hash order. With no variation (one star, or all alike) every score is 0.
 */
internal fun principalAxes(profiles: List<TagProfile>): PcaAxes {
    val n = profiles.size
    val keys = profiles.flatMap { it.keys }.distinct().sorted()
    val d = keys.size
    val column = HashMap<String, Int>().apply { keys.forEachIndexed { j, k -> put(k, j) } }
    val x = Array(n) { DoubleArray(d) }
    profiles.forEachIndexed { i, p -> for ((k, w) in p) x[i][column.getValue(k)] = w }
    for (j in 0 until d) { // centre each column
        var mean = 0.0
        for (i in 0 until n) mean += x[i][j]
        mean /= max(n, 1)
        for (i in 0 until n) x[i][j] -= mean
    }
    val first = principalComponent(x, n, d, seed = 0)
    for (i in 0 until n) for (j in 0 until d) x[i][j] -= first.first[i] * first.second[j] // take the first axis out
    val second = principalComponent(x, n, d, seed = 1)
    return PcaAxes(keys, List(n) { first.first[it] to second.first[it] }, first.second, second.second)
}

/** Scores and loading of the strongest axis of [x] (n rows, d columns): power iteration on xᵀx, sign fixed. */
private fun principalComponent(x: Array<DoubleArray>, n: Int, d: Int, seed: Int): Pair<DoubleArray, DoubleArray> {
    var v = DoubleArray(d) { j -> 1.0 + ((j * 7 + seed * 3) % 5) } // varied, so it is never square to an axis
    v = unit(v) ?: return DoubleArray(n) to DoubleArray(d)
    repeat(LAYOUT_ITERATIONS) {
        val w = DoubleArray(n) { i -> dot(x[i], v) }
        val next = DoubleArray(d)
        for (i in 0 until n) for (j in 0 until d) next[j] += w[i] * x[i][j]
        v = unit(next) ?: return DoubleArray(n) to DoubleArray(d)
    }
    var biggest = 0
    for (j in 1 until d) if (abs(v[j]) > abs(v[biggest]) + EPS) biggest = j
    if (v[biggest] < 0) v = DoubleArray(d) { -v[it] }
    return DoubleArray(n) { i -> dot(x[i], v) } to v
}

private fun dot(a: DoubleArray, b: DoubleArray): Double {
    var s = 0.0
    for (j in a.indices) s += a[j] * b[j]
    return s
}

private fun unit(v: DoubleArray): DoubleArray? {
    val norm = sqrt(dot(v, v))
    return if (norm < EPS) null else DoubleArray(v.size) { v[it] / norm }
}

/**
 * Star radii in unit-box units: grow with the artist's release count (square-root, so a huge catalogue does not
 * swallow the map), and shrink for a big library so the stars can all fit. With no counts at all the stars are equal.
 */
internal fun starRadii(releaseCounts: List<Int>): List<Double> {
    if (releaseCounts.isEmpty()) return emptyList()
    val top = releaseCounts.max()
    val base = min(MAX_STAR_RADIUS, 0.25 / sqrt(releaseCounts.size.toDouble()))
    return releaseCounts.map { c ->
        val t = if (top > 0) sqrt(c.toDouble() / top) else 1.0
        base * (0.55 + 0.45 * t)
    }
}

/** [points] scaled uniformly and centred in the unit box with [LAYOUT_MARGIN] clear. A cloud with no extent sits in the middle. */
internal fun fitToUnitBox(points: List<Pair<Double, Double>>): List<DoubleArray> {
    if (points.isEmpty()) return emptyList()
    val minX = points.minOf { it.first }
    val maxX = points.maxOf { it.first }
    val minY = points.minOf { it.second }
    val maxY = points.maxOf { it.second }
    val extent = max(maxX - minX, maxY - minY)
    val scale = if (extent > 1e-9) (1 - 2 * LAYOUT_MARGIN) / extent else 0.0
    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2
    return points.map { doubleArrayOf(0.5 + (it.first - cx) * scale, 0.5 + (it.second - cy) * scale) }
}

/**
 * Turns the projected cloud into one fixed orientation. PCA only fixes the plane: which way is "x", and which side
 * is "up", can flip when a single artist is added and two axes are nearly as strong as each other, and the whole
 * map would jump. So the cloud is rotated until the anchor star lies on the positive x axis, then mirrored so its
 * lopsided side is up (a cloud that is nearly symmetric has nothing to mirror, so a flip there moves almost no star).
 * [scores] must be in star order (by id), centred on 0.
 */
internal fun orientScores(scores: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    if (scores.size < 2) return scores
    val norms = scores.map { hypot(it.first, it.second) }
    val top = norms.max()
    if (top < 1e-9) return scores
    val anchor = norms.indexOfFirst { it >= ANCHOR_SHARE * top }
    val theta = atan2(scores[anchor].second, scores[anchor].first)
    val c = cos(-theta)
    val s = sin(-theta)
    val turned = scores.map { (x, y) -> (x * c - y * s) to (x * s + y * c) }
    val lean = turned.sumOf { it.first * it.first * it.second }
    return if (lean < 0) turned.map { it.first to -it.second } else turned
}

/** Pushes overlapping stars apart in place; see the file comment. [ids] only breaks the tie of two stars exactly on top of each other. */
internal fun relaxStars(p: List<DoubleArray>, radii: List<Double>, ids: List<String>) {
    val n = p.size
    val dx = DoubleArray(n)
    val dy = DoubleArray(n)
    repeat(RELAX_PASSES) {
        dx.fill(0.0)
        dy.fill(0.0)
        var overlapping = false
        for (i in 0 until n) for (j in i + 1 until n) {
            val gapX = p[j][0] - p[i][0]
            val gapY = p[j][1] - p[i][1]
            val dist = hypot(gapX, gapY)
            val wanted = radii[i] + radii[j] + STAR_GAP
            if (dist >= wanted) continue
            overlapping = true
            val ux: Double
            val uy: Double
            if (dist < 1e-9) { // exactly alike: a direction that depends on the two ids, not on list positions
                val h = ids[i].hashCode() * 31 + ids[j].hashCode()
                val angle = (((h % 360) + 360) % 360) * PI / 180
                ux = cos(angle)
                uy = sin(angle)
            } else {
                ux = gapX / dist
                uy = gapY / dist
            }
            val push = wanted - dist
            val shareI = radii[j] / (radii[i] + radii[j]) // the smaller star moves further
            dx[i] -= ux * push * shareI
            dy[i] -= uy * push * shareI
            dx[j] += ux * push * (1 - shareI)
            dy[j] += uy * push * (1 - shareI)
        }
        if (!overlapping) return
        for (i in 0 until n) {
            val lo = radii[i] + 0.01
            val hi = 1 - radii[i] - 0.01
            p[i][0] = (p[i][0] + RELAX_DAMPING * dx[i]).coerceIn(lo, hi)
            p[i][1] = (p[i][1] + RELAX_DAMPING * dy[i]).coerceIn(lo, hi)
        }
    }
}

/** The map for [graph]: one [StarPoint] per placed star, in the graph's (id) order. */
internal fun layoutConstellation(graph: ConstellationGraph): ConstellationLayout {
    val stars = graph.stars
    if (stars.isEmpty()) return ConstellationLayout(emptyList())
    val axes = principalAxes(stars.map { it.profile })
    val radii = starRadii(stars.map { countReleaseCards(it.artist.releases) })
    val placed = fitToUnitBox(orientScores(axes.scores))
    relaxStars(placed, radii, stars.map { it.id })
    return ConstellationLayout(stars.mapIndexed { i, s -> StarPoint(s.id, placed[i][0], placed[i][1], radii[i]) })
}
