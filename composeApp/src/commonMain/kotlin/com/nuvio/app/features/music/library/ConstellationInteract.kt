package com.nuvio.app.features.music.library

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Library views, phase C4 (pure part): everything the Constellation's interaction decides that is not Compose, so
 * it is unit-tested. [ConstellationView] only feeds it pointer and key events and draws the answer.
 *
 *  - [Camera]: zoom and pan on top of the fitted map ([MapFrame]). A screen position is `base * zoom + pan`, so the
 *    point under the cursor stays put while zooming ([Camera.zoomedAt]) and the map can never be dragged off the canvas
 *    ([Camera.clamped]). Positions scale with the zoom; star radii only with its square root, so zooming in also pulls
 *    crowded stars apart.
 *  - [ScreenMap]: frame plus camera, the one place that turns unit-box coordinates into pixels, for drawing and hit tests alike.
 *  - [hitStar]: which star is under a pixel.
 *  - [stepStar], [cycleStar]: keyboard selection. Arrow keys move to the nearest star in that direction; Tab walks
 *    every star in reading order, so every star can be reached by keyboard.
 *  - [focusIds], [matchesQuery]: the search box and genre chips. A star outside the focus set is dimmed, never hidden.
 *  - [topGenres]: the genres named on hover and in the side panel.
 */

/** Below 1 the whole map shrinks into the middle of the canvas, so a big library fits with room to spare. */
internal const val MIN_ZOOM = 0.4f
internal const val MAX_ZOOM = 8f

/** However far it is dragged, the map keeps at least this share of the canvas, so it can always be found again. */
internal const val MIN_VISIBLE_SHARE = 0.25f

/** Zoom factor of one scroll notch. */
internal const val ZOOM_PER_NOTCH = 1.18f

/** Extra pixels around a star that still count as pointing at it. */
internal const val HIT_SLACK_PX = 6f

/** How far a keyboard move may stray off its axis and still count as "that way": perpendicular offset up to this times the distance along it. */
internal const val STEP_CONE = 1.2

internal data class Camera(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    fun x(base: Float): Float = base * zoom + panX
    fun y(base: Float): Float = base * zoom + panY

    /**
     * This camera with zoom in range and the pan free to roam, as long as the zoomed map still
     * overlaps a [MIN_VISIBLE_SHARE] of the [width] by [height] canvas on each axis.
     */
    fun clamped(width: Float, height: Float): Camera {
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return Camera(
            z,
            panX.coerceIn(width * MIN_VISIBLE_SHARE - width * z, width * (1 - MIN_VISIBLE_SHARE)),
            panY.coerceIn(height * MIN_VISIBLE_SHARE - height * z, height * (1 - MIN_VISIBLE_SHARE)),
        )
    }

    fun panned(dx: Float, dy: Float, width: Float, height: Float): Camera = copy(panX = panX + dx, panY = panY + dy).clamped(width, height)

    /** Zoom by [factor] about the canvas point ([anchorX], [anchorY]), which stays where it is. */
    fun zoomedAt(anchorX: Float, anchorY: Float, factor: Float, width: Float, height: Float): Camera {
        val z = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = z / zoom
        return Camera(z, anchorX - (anchorX - panX) * k, anchorY - (anchorY - panY) * k).clamped(width, height)
    }

    /** Panned just enough that the screen point ([screenX], [screenY]), under this camera, is at least [margin] inside the canvas. */
    fun revealing(screenX: Float, screenY: Float, width: Float, height: Float, margin: Float): Camera {
        val dx = when {
            screenX < margin -> margin - screenX
            screenX > width - margin -> width - margin - screenX
            else -> 0f
        }
        val dy = when {
            screenY < margin -> margin - screenY
            screenY > height - margin -> height - margin - screenY
            else -> 0f
        }
        return if (dx == 0f && dy == 0f) this else panned(dx, dy, width, height)
    }
}

/** The fitted map seen through a camera: unit-box coordinates to canvas pixels. */
internal class ScreenMap(val frame: MapFrame, val camera: Camera) {
    fun x(u: Double): Float = camera.x(frame.x(u))
    fun y(u: Double): Float = camera.y(frame.y(u))

    /** Star radius in pixels: grows with the square root of the zoom. */
    fun starRadius(u: Double): Float = frame.radius(u) * sqrt(camera.zoom)

    /** Halo half-width and half-height: they follow the positions, so they scale with the full zoom. */
    fun haloRadiusX(u: Double): Float = (u * frame.width * camera.zoom).toFloat()
    fun haloRadiusY(u: Double): Float = (u * frame.height * camera.zoom).toFloat()
}

/** The star under the pixel ([x], [y]): the nearest centre among those within radius plus [HIT_SLACK_PX], ties to the lowest id. */
internal fun hitStar(points: List<StarPoint>, map: ScreenMap, x: Float, y: Float): String? {
    var best: String? = null
    var bestDistance = Float.MAX_VALUE
    for (p in points) {
        val d = hypot(map.x(p.x) - x, map.y(p.y) - y)
        if (d > map.starRadius(p.radius) + HIT_SLACK_PX) continue
        val better = d < bestDistance - 1e-3f || (abs(d - bestDistance) <= 1e-3f && best != null && p.id < best)
        if (best == null || better) {
            best = p.id
            bestDistance = d
        }
    }
    return best
}

internal enum class StepDirection(val dx: Double, val dy: Double) {
    Left(-1.0, 0.0), Right(1.0, 0.0), Up(0.0, -1.0), Down(0.0, 1.0)
}

/** Where the keyboard selection starts: the star nearest the middle of the map, ties to the lowest id. */
internal fun startStar(points: List<StarPoint>, among: Set<String>? = null): String? =
    points.filter { among == null || it.id in among }
        .sortedWith(compareBy<StarPoint> { hypot(it.x - 0.5, it.y - 0.5) }.thenBy { it.id })
        .firstOrNull()?.id

/**
 * The star an arrow key moves to from [fromId]: the nearest in that direction, preferring those within [STEP_CONE]
 * of the axis, else the nearest anywhere that way. Null when nothing lies that way. With no star selected the key
 * starts at [startStar]. [among] limits the choice (the focus set while a filter is on).
 */
internal fun stepStar(points: List<StarPoint>, fromId: String?, direction: StepDirection, among: Set<String>? = null): String? {
    val from = points.firstOrNull { it.id == fromId } ?: return startStar(points, among)
    var inCone: StarPoint? = null
    var inConeScore = Double.MAX_VALUE
    var anywhere: StarPoint? = null
    var anywhereScore = Double.MAX_VALUE
    for (c in points) {
        if (c.id == from.id || (among != null && c.id !in among)) continue
        val dx = c.x - from.x
        val dy = c.y - from.y
        val along = dx * direction.dx + dy * direction.dy
        if (along <= 1e-9) continue
        val across = abs(dx * direction.dy - dy * direction.dx)
        val coneScore = hypot(dx, dy) * (1 + across / along)
        if (across <= along * STEP_CONE && (coneScore < inConeScore - 1e-12 || (abs(coneScore - inConeScore) <= 1e-12 && inCone != null && c.id < inCone.id))) {
            inCone = c
            inConeScore = coneScore
        }
        val looseScore = along + across
        if (looseScore < anywhereScore - 1e-12 || (abs(looseScore - anywhereScore) <= 1e-12 && anywhere != null && c.id < anywhere.id)) {
            anywhere = c
            anywhereScore = looseScore
        }
    }
    return (inCone ?: anywhere)?.id
}

/** Tab order: every star, left to right then top to bottom. [step] is 1 or -1; from nothing it starts at the first (or last). */
internal fun cycleStar(points: List<StarPoint>, fromId: String?, step: Int, among: Set<String>? = null): String? {
    val order = points.filter { among == null || it.id in among }
        .sortedWith(compareBy<StarPoint>({ it.x }, { it.y }, { it.id }))
    if (order.isEmpty()) return null
    val index = order.indexOfFirst { it.id == fromId }
    if (index < 0) return if (step >= 0) order.first().id else order.last().id
    return order[((index + step) % order.size + order.size) % order.size].id
}

/** Does [artist] match what was typed: part of the name, genres, tags, style or mood, ignoring case. A blank query matches all. */
internal fun matchesQuery(artist: LibraryArtist, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    if (artist.name.lowercase().contains(q)) return true
    return (artist.genres + artist.tags + listOfNotNull(artist.style, artist.mood)).any { it.lowercase().contains(q) }
}

/**
 * The stars that stay lit: those in the cluster [chipKey] (when a chip is chosen) that also match [query] (when
 * something is typed). Null means no filter, so nothing is dimmed. Live artist data in [byId] is preferred to the
 * snapshot the model was built from.
 */
internal fun focusIds(model: ConstellationModel, byId: Map<String, LibraryArtist>, query: String, chipKey: String?): Set<String>? {
    val typed = query.isNotBlank()
    if (!typed && chipKey == null) return null
    return model.graph.stars
        .filter { star ->
            (chipKey == null || model.clusters.clusterKeyOf[star.id] == chipKey) &&
                (!typed || matchesQuery(byId[star.id] ?: star.artist, query))
        }
        .map { it.id }
        .toSet()
}

/** Up to [count] distinct genres for display, from the artist's genres then tags, as the artist spells them. */
internal fun topGenres(artist: LibraryArtist, count: Int = 3): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (g in normalizeGenres(artist.genres) + normalizeGenres(artist.tags)) {
        if (out.size >= count) break
        if (seen.add(genreKey(g))) out += g
    }
    return out
}
