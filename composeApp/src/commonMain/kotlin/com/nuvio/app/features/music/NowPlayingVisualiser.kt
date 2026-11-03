package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.features.music.visualiser.MapScale
import com.nuvio.app.features.music.visualiser.LiveTrackMap
import com.nuvio.app.features.music.visualiser.MAP_BANDS
import com.nuvio.app.features.music.visualiser.MAP_FRAME_MS
import com.nuvio.app.features.music.visualiser.Onset
import com.nuvio.app.features.music.visualiser.SectionKind
import com.nuvio.app.features.music.visualiser.SongSection
import com.nuvio.app.features.music.visualiser.TrackMap
import com.nuvio.app.features.music.visualiser.VisualiserStyle
import com.nuvio.app.features.music.visualiser.clockLabel
import com.nuvio.app.features.music.visualiser.mapScale
import com.nuvio.app.features.music.visualiser.vuFor
import com.nuvio.app.features.music.visualiser.nextChorusStartMs
import com.nuvio.app.features.music.visualiser.sectionAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * fork:visualiser - the four styles of the Stage's visualiser, drawn from the track map (visualiser/TrackMap.kt) at the
 * smoothed playhead. Everything is a function of the playhead, so a seek or a pause needs nothing special, and nothing
 * recomposes per frame (the canvases read the position in their draw blocks).
 */

@Composable
internal fun VisualiserView(
    style: VisualiserStyle,
    live: LiveTrackMap?,
    position: State<Long>,
    durationMs: Long,
    sections: List<SongSection>,
    coverUrl: String?,
    accent: Color,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val map = live?.map
    val scale = remember(map) { map?.let(::mapScale) }
    val status = when {
        live?.unavailable == true && map == null -> "CAN'T ANALYSE THIS TRACK HERE"
        map == null -> "LISTENING…"
        else -> null
    }
    when (style) {
        VisualiserStyle.SongMap -> {
            val cache = remember(map, durationMs) { SongMapColumns() }
            Canvas(modifier) { drawSongMap(measurer, map, position.value, durationMs, sections, cache, accent, reduced, status) }
        }
        VisualiserStyle.Halo -> HaloView(measurer, map, scale, position, coverUrl, accent, reduced, status, modifier)
        VisualiserStyle.TwinVu -> Canvas(modifier) { drawTwinVu(measurer, map, scale, position.value, live?.lufs, accent, status) }
        VisualiserStyle.BeatGrid -> Canvas(modifier) { drawBeatGrid(measurer, map, position.value, accent, reduced, status) }
    }
}

// ---- shared ----

private fun labelStyle(sizePx: Float, alpha: Float, density: DrawScope): TextStyle = with(density) {
    TextStyle(
        color = Color.White.copy(alpha = alpha),
        fontSize = sizePx.toSp(),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        letterSpacing = (sizePx * 0.08f).toSp(),
    )
}

/** Draws [text] with its top at [y]; [align] 0 puts its left edge at [x], 0.5 its centre, 1 its right edge. */
private fun DrawScope.label(m: TextMeasurer, text: String, x: Float, y: Float, sizePx: Float, alpha: Float, align: Float = 0f): Float {
    val layout = m.measure(text, labelStyle(sizePx, alpha, this), maxLines = 1)
    drawText(layout, topLeft = Offset(x - layout.size.width * align, y))
    return layout.size.width.toFloat()
}

private fun DrawScope.statusLine(m: TextMeasurer, status: String?) {
    if (status == null) return
    label(m, status, size.width / 2f, size.height * 0.46f, labelSize(), 0.55f, align = 0.5f)
}

private fun DrawScope.labelSize(): Float = (size.minDimension * 0.05f).coerceIn(9.dp.toPx(), 13.dp.toPx())

/** Band levels sit around -40..-15 dB in music; this spreads them over the drawing. */
private fun lift(v: Float): Float = ((v - 0.35f) / 0.55f).coerceIn(0f, 1f)

/** How strongly the last kick still lands at [atMs]: 1 on the hit, fading over about a tenth of a second. */
private fun kickPulse(map: TrackMap?, atMs: Long): Float {
    val since = map?.sinceLast(Onset.KICK, atMs, 400L) ?: return 0f
    return exp(-since / 110f)
}

private fun oneDecimal(x: Double): String {
    val tenths = (abs(x) * 10).roundToInt()
    return (if (x < 0) "−" else "") + "${tenths / 10}.${tenths % 10}"
}

// ---- Song Map ----

/** The song's columns, worked out once per map and width. */
private class SongMapColumns {
    var count = -1
    var values: Array<Float?> = emptyArray()
    var lo = 0f
    var hi = 1f
}

private fun DrawScope.drawSongMap(
    m: TextMeasurer,
    map: TrackMap?,
    nowMs: Long,
    durationMs: Long,
    sections: List<SongSection>,
    cache: SongMapColumns,
    accent: Color,
    reduced: Boolean,
    status: String?,
) {
    val w = size.width
    val h = size.height
    val total = if (durationMs > 0) durationMs else map?.analysedMs ?: 0L
    val ls = labelSize()
    val stripTop = h * 0.84f
    val stripH = h * 0.11f
    val mid = h * 0.47f
    val amp = h * 0.30f
    val cols = (w / 3.dp.toPx()).toInt().coerceAtLeast(20)
    if (cache.count != cols && map != null && total > 0) {
        cache.count = cols
        cache.values = Array(cols) { c -> map.peakLevel(c * total / cols, (c + 1) * total / cols) }
        val known = cache.values.filterNotNull().sorted()
        cache.lo = known.getOrNull(known.size / 20) ?: 0f
        cache.hi = known.lastOrNull() ?: 1f
    }
    val px = if (total > 0) (nowMs.toFloat() / total).coerceIn(0f, 1f) * w else 0f
    val colW = w / cols
    val live = if (map != null && !reduced) lift(map.meanOver(map.level, nowMs, 100L) * 1.2f) else 0f
    for (c in 0 until cols) {
        val x = c * colW
        val value = if (cache.count == cols) cache.values.getOrNull(c) else null
        if (value == null) {
            drawCircle(Color.White.copy(alpha = 0.12f), radius = 1.dp.toPx(), center = Offset(x + colW / 2, mid))
            continue
        }
        var v = ((value - cache.lo) / (cache.hi - cache.lo).coerceAtLeast(0.05f)).coerceIn(0.06f, 1f)
        val d = abs(x - px)
        if (d < w * 0.06f) v *= 1f + live * 0.45f * (1f - d / (w * 0.06f))
        val played = x < px
        val color = if (played) accent.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.22f)
        drawRect(color, topLeft = Offset(x, mid - amp * v), size = Size((colW - 0.8f).coerceAtLeast(1f), amp * v * 1.6f))
    }
    // The playhead, with a soft glow either side.
    val glow = 10.dp.toPx()
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.35f), Color.Transparent), startX = px - glow, endX = px + glow),
        topLeft = Offset(px - glow, h * 0.10f),
        size = Size(glow * 2, h * 0.70f),
    )
    drawRect(Color.White, topLeft = Offset(px - 0.75.dp.toPx(), h * 0.10f), size = Size(1.5.dp.toPx(), h * 0.70f))
    // The parts of the song, from its lyrics.
    val current = sectionAt(sections, nowMs)
    for (s in sections) {
        if (total <= 0) break
        val x0 = s.startMs.toFloat() / total * w
        val x1 = s.endMs.toFloat() / total * w
        val here = s == current
        val fill = when {
            s.kind == SectionKind.Chorus -> accent.copy(alpha = if (here) 0.30f else 0.16f)
            else -> Color.White.copy(alpha = if (here) 0.12f else 0.05f)
        }
        drawRoundRect(fill, topLeft = Offset(x0, stripTop), size = Size((x1 - x0 - 1.dp.toPx()).coerceAtLeast(1f), stripH), cornerRadius = CornerRadius(3.dp.toPx()))
        val text = s.kind.label.uppercase()
        val small = ls * 0.82f
        val width = m.measure(text, labelStyle(small, 1f, this), maxLines = 1).size.width
        if (width + 6.dp.toPx() < x1 - x0) label(m, text, x0 + 3.dp.toPx(), stripTop + (stripH - small * 1.3f) / 2, small, if (here) 0.95f else 0.45f)
    }
    // What part this is, and how long until the next chorus.
    label(m, current?.kind?.label?.uppercase() ?: "SONG MAP", 0f, 0f, ls, 0.9f)
    val next = nextChorusStartMs(sections, nowMs)
    val right = when {
        next != null -> "CHORUS IN ${clockLabel(next - nowMs)}"
        total > 0 -> "−${clockLabel(total - nowMs)}"
        else -> ""
    }
    if (right.isNotEmpty()) label(m, right, w, 0f, ls, 0.55f, align = 1f)
    statusLine(m, status)
}

// ---- Halo ----

@Composable
private fun HaloView(
    m: TextMeasurer,
    map: TrackMap?,
    scale: MapScale?,
    position: State<Long>,
    coverUrl: String?,
    accent: Color,
    reduced: Boolean,
    status: String?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier) {
        val cover = min(maxWidth.value, maxHeight.value).dp * 0.40f
        Canvas(Modifier.fillMaxSize()) { drawHalo(map, scale, position.value, accent, reduced) }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(cover)
                .graphicsLayer {
                    val p = if (reduced) 0f else kickPulse(map, position.value)
                    scaleX = 1f + p * 0.06f
                    scaleY = 1f + p * 0.06f
                }
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.35f)),
        ) {
            if (coverUrl != null) {
                NuvioAsyncImage(model = coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        if (status != null) Canvas(Modifier.fillMaxSize()) { label(m, status, size.width / 2f, size.height * 0.88f, labelSize(), 0.55f, align = 0.5f) }
    }
}

private const val HALO_SPOKES = 72

private fun DrawScope.drawHalo(map: TrackMap?, scale: MapScale?, nowMs: Long, accent: Color, reduced: Boolean) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val minD = size.minDimension
    val pulse = if (reduced) 0f else kickPulse(map, nowMs)
    val radius = minD * 0.20f * (1f + pulse * 0.06f)
    // Ripples from the last few kicks.
    if (map != null && !reduced) {
        val now = map.frameAt(nowMs)
        var drawn = 0
        for (f in now downTo maxOf(0, now - 1_200 / MAP_FRAME_MS)) {
            if (!map.hits(f, Onset.KICK)) continue
            val age = (now - f) * MAP_FRAME_MS / 1_200f
            drawCircle(accent.copy(alpha = 0.45f * (1f - age)), radius = radius * 1.12f + age * minD * 0.42f, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
            if (++drawn == 4) break
        }
    }
    // Each band between its own usual quiet and loud for this track, so the ring moves on any master instead of sitting full.
    val held = FloatArray(MAP_BANDS) { b ->
        val raw = map?.heldBand(b, nowMs) ?: 0f
        if (scale != null) scale.band(b, raw) else lift(raw)
    }
    val stroke = (minD * 0.012f).coerceAtLeast(1.5.dp.toPx())
    val half = HALO_SPOKES / 2
    for (i in 0 until HALO_SPOKES) {
        // Mirrored: bass at the top, treble at the bottom, the same on both sides.
        val k = if (i < half) i else HALO_SPOKES - 1 - i
        val pos = k.toFloat() / (half - 1) * (MAP_BANDS - 1)
        val lo = floor(pos).toInt().coerceIn(0, MAP_BANDS - 1)
        val hi = (lo + 1).coerceAtMost(MAP_BANDS - 1)
        val v = held[lo] + (held[hi] - held[lo]) * (pos - lo)
        val angle = i.toFloat() / HALO_SPOKES * 2f * PI.toFloat() - PI.toFloat() / 2f
        val r0 = radius + 4.dp.toPx()
        val r1 = r0 + 3.dp.toPx() + v.pow(1.2f) * minD * 0.27f * (if (reduced) 0.45f else 1f)
        drawLine(
            lerp(accent, Color.White, v * 0.4f).copy(alpha = 0.35f + 0.65f * v),
            start = Offset(cx + cos(angle) * r0, cy + sin(angle) * r0),
            end = Offset(cx + cos(angle) * r1, cy + sin(angle) * r1),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

// ---- Twin VU ----

/** The scale runs from -20 to +3 VU, spaced as a real meter's (linear in voltage, so 0 VU sits at about 70%). */
private fun vuPosition(vu: Double): Float = (10.0.pow(vu / 20.0) / 1.4125).toFloat().coerceIn(0f, 1.05f)

private val VU_TICKS = listOf(-20.0, -10.0, -7.0, -5.0, -3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0)
private val VU_NUMBERS = listOf(-20.0 to "20", -10.0 to "10", -5.0 to "5", 0.0 to "0", 3.0 to "3")
private val VU_RED = Color(0xFFF05A46)

private fun DrawScope.drawTwinVu(m: TextMeasurer, map: TrackMap?, scale: MapScale?, nowMs: Long, lufs: Double?, accent: Color, status: String?) {
    val w = size.width
    val h = size.height
    val ls = labelSize()
    val gap = w * 0.06f
    val mw = (w - gap) / 2f
    val mh = h * 0.66f
    val corner = CornerRadius(8.dp.toPx())
    for (side in 0..1) {
        val mx = side * (mw + gap)
        drawRoundRect(Color(0x0FFFF8E6), topLeft = Offset(mx, 0f), size = Size(mw, mh), cornerRadius = corner)
        drawRoundRect(Color.White.copy(alpha = 0.10f), topLeft = Offset(mx, 0f), size = Size(mw, mh), cornerRadius = corner, style = Stroke(1.dp.toPx()))
        // The channel name and the peak lamp share the top row; the dial fills the rest, its pivot near the bottom edge.
        val pad = 8.dp.toPx()
        label(m, if (side == 0) "L" else "R", mx + pad, pad * 0.7f, ls * 0.85f, 0.55f)
        val cx = mx + mw / 2f
        val cy = mh - pad * 1.2f
        val top = pad + ls * 1.6f
        val r = min(mw * 0.5f - pad * 0.5f, (cy - top) / 1.1f)
        fun at(pos: Float, rr: Float): Offset {
            val a = ((-150f + pos * 120f) * PI / 180f).toFloat()
            return Offset(cx + cos(a) * rr, cy + sin(a) * rr)
        }
        // The red zone from 0 to +3 VU, the ticks, and the numbers outside them.
        val p0 = vuPosition(0.0)
        val p3 = vuPosition(3.0)
        drawArc(
            VU_RED.copy(alpha = 0.6f),
            startAngle = -150f + p0 * 120f,
            sweepAngle = (p3 - p0) * 120f,
            useCenter = false,
            topLeft = Offset(cx - r * 0.86f, cy - r * 0.86f),
            size = Size(r * 1.72f, r * 1.72f),
            style = Stroke(2.5.dp.toPx()),
        )
        for (vu in VU_TICKS) {
            val p = vuPosition(vu)
            val major = VU_NUMBERS.any { it.first == vu }
            drawLine(
                if (vu > 0) VU_RED else Color.White.copy(alpha = 0.6f),
                start = at(p, r * 0.80f),
                end = at(p, r * if (major) 0.94f else 0.88f),
                strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
            )
        }
        val small = ls * 0.72f
        for ((vu, text) in VU_NUMBERS) {
            val spot = at(vuPosition(vu), r * 1.03f)
            label(m, text, spot.x, spot.y - small * 1.1f, small, if (vu > 0) 0.8f else 0.45f, align = 0.5f)
        }
        label(m, "VU", cx, cy - r * 0.42f, ls * 0.8f, 0.3f, align = 0.5f)
        // The needle reads the last 300 ms, as a VU meter integrates, against this track's own middle level; the lamp
        // lights for a peak above +2 VU in the last second.
        val values = if (side == 0) map?.left else map?.right
        var needle = 0f
        var lamp = false
        if (map != null && values != null && scale != null) {
            val level = map.meanOver(values, nowMs, 300L)
            if (level > 0f) needle = vuPosition(vuFor(level, scale))
            val now = map.frameAt(nowMs)
            var peak = 0f
            for (f in maxOf(0, now - 1_000 / MAP_FRAME_MS)..now) if (map.has(f)) peak = maxOf(peak, (values[f].toInt() and 0xFF) / 255f)
            lamp = peak > 0f && vuFor(peak, scale) > 2.0
        }
        drawLine(lerp(accent, Color.White, 0.55f), start = Offset(cx, cy), end = at(needle, r * 0.97f), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.White.copy(alpha = 0.7f), radius = 3.dp.toPx(), center = Offset(cx, cy))
        drawCircle(Color(0xFFFF5A46).copy(alpha = if (lamp) 0.95f else 0.15f), radius = 3.dp.toPx(), center = Offset(mx + mw - pad - 3.dp.toPx(), pad + ls * 0.45f))
    }
    // The loudness Nuvio measured for this track, and what it brings everything to.
    val big = (h * 0.10f).coerceIn(14.dp.toPx(), 26.dp.toPx())
    label(m, lufs?.let { "${oneDecimal(it)} LUFS" } ?: "MEASURING…", w / 2f, mh + h * 0.07f, big, 0.95f, align = 0.5f)
    label(m, "MEASURED · LEVELLED TO −${-com.nuvio.app.features.preroll.LOUDNESS_TARGET_LUFS}", w / 2f, mh + h * 0.07f + big * 1.5f, ls * 0.85f, 0.45f, align = 0.5f)
    statusLine(m, status)
}

// ---- Beat Grid ----

private val GRID_ROWS = listOf("KICK" to Onset.KICK, "SNARE" to Onset.SNARE, "HATS" to Onset.HATS, "MELODY" to Onset.MELODY)

private fun DrawScope.drawBeatGrid(m: TextMeasurer, map: TrackMap?, nowMs: Long, accent: Color, reduced: Boolean, status: String?) {
    val w = size.width
    val h = size.height
    val ls = labelSize()
    val labelW = w * 0.21f
    val gridX = labelW
    val cellW = (w - labelW) / 16f
    val top = ls * 2.2f
    val rowH = (h - top - ls * 2.4f) / GRID_ROWS.size
    val beat = map?.beat
    val now = nowMs.toDouble()
    val stepMs: Double
    val current: Long
    if (beat != null) {
        stepMs = beat.stepMs
        current = beat.stepAt(now)
    } else {
        // No steady beat: the columns are the last two seconds, newest on the right.
        stepMs = 125.0
        current = floor(now / stepMs).toLong()
    }
    val bar = current.floorDiv(16L)
    val cursor = current.mod(16L).toInt()
    GRID_ROWS.forEachIndexed { r, (name, kind) ->
        val y = top + r * rowH
        label(m, name, 0f, y + (rowH - ls * 1.3f) / 2, ls * 0.85f, 0.55f)
        for (s in 0 until 16) {
            val x = gridX + s * cellW
            val lit: Boolean
            var alpha: Float
            if (map == null) {
                lit = false
                alpha = 0.06f
            } else if (beat != null) {
                val inThisBar = s <= cursor
                val step = (if (inThisBar) bar else bar - 1) * 16 + s
                val start = beat.stepStartMs(step) - stepMs / 2
                lit = map.hitsBetween(kind, start, start + stepMs)
                alpha = when {
                    !lit -> 0.06f
                    s == cursor -> {
                        val since = (now - beat.stepStartMs(step)).toFloat().coerceAtLeast(0f)
                        if (reduced) 0.8f else 0.35f + 0.65f * exp(-since / 150f)
                    }
                    inThisBar -> 0.32f
                    else -> 0.12f
                }
            } else {
                val end = now - (15 - s) * stepMs
                lit = map.hitsBetween(kind, end - stepMs, end)
                alpha = if (lit) 0.32f + 0.3f * (s / 15f) else 0.06f
            }
            val color = if (lit) lerp(accent, Color.White, if (s == cursor && beat != null) 0.35f else 0f) else Color.White
            drawRoundRect(
                color.copy(alpha = alpha),
                topLeft = Offset(x + 1.5.dp.toPx(), y + 2.dp.toPx()),
                size = Size(cellW - 3.dp.toPx(), rowH - 4.dp.toPx()),
                cornerRadius = CornerRadius(min(4.dp.toPx(), cellW * 0.2f)),
            )
        }
    }
    if (beat != null && map != null) {
        drawRoundRect(
            Color.White.copy(alpha = 0.7f),
            topLeft = Offset(gridX + cursor * cellW + 0.5f, top),
            size = Size(cellW - 1f, rowH * GRID_ROWS.size),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(1.2.dp.toPx()),
        )
        for (b in 0 until 4) label(m, "${b + 1}", gridX + b * 4 * cellW + 2.dp.toPx(), top + rowH * GRID_ROWS.size + ls * 0.5f, ls * 0.85f, 0.4f)
    }
    val left = beat?.let { "${it.bpm.roundToInt()} BPM" } ?: if (map?.complete == true) "NO STEADY BEAT" else "FINDING THE BEAT"
    label(m, left, 0f, 0f, ls, 0.9f)
    if (beat != null) label(m, if (current < 0) "COUNT-IN" else "BAR ${bar + 1}", w, 0f, ls, 0.5f, align = 1f)
    statusLine(m, status)
}
