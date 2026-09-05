package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush

/**
 * True for the row item currently being highlighted — the same state that drives
 * [NuvioShelfItemSlot]'s magnification, whether it came from the mouse or from keyboard navigation.
 *
 * Poster cards are built deep inside shared shelf components and take no focus parameter of their
 * own, so the slot publishes the flag here rather than threading it through every call site — the
 * same reasoning as [LocalCollectionsPosterSurface].
 */
internal val LocalNuvioShelfItemHighlighted = compositionLocalOf { false }

/**
 * Ring geometry, chosen by rendering the card offscreen at densities 1.0-2.0 and measuring how much
 * of the stroke's full-coverage core survives around the corner arc.
 *
 * A ring stroked along a curve loses core width to antialiasing — the pixel grid crosses the band
 * diagonally — and that loss is what reads as a stepped, ragged corner. It is worst at density 1.0,
 * which is the floor this app clamps to on 1080p displays. Measured necking of the core around the
 * corner quadrant at that density: 1.5dp lost 60%, 2dp lost 41%, 3dp lost 23%, and 2.5dp lost 18%,
 * dropping to 11% once the feather below is added. 2.5dp also had the lowest average across every
 * density tested, so this is not a 1080p-only tune.
 */
private val PosterHighlightRingWidth = 2.5.dp

/**
 * A half-strength hairline hugging each side of the core, which ramps the edge instead of stepping
 * off it. It hides the residual core-width wobble around the arc for a fraction of the visual weight
 * that widening the core further would cost.
 *
 * An earlier attempt used a single wide, very faint stroke as a glow instead. That measurably made
 * things worse: a uniform-alpha stroke is a flat band with its own hard edge at its own radius, so
 * it read as a second contour around the corner and lifted the artwork's apparent brightness.
 */
private val PosterHighlightFeatherWidth = 1.dp
private const val PosterHighlightFeatherAlpha = 0.5f

/**
 * How much brightness [PosterHighlightMode.Shine] adds to the highlighted card: the artwork is
 * composited over itself at this strength through an additive layer, so every pixel ends up at
 * 1.25x its own value.
 *
 * A gain, not a white wash. Painting a 25% white scrim over the card would lift black to mid-grey
 * and pull every colour toward white — the poster goes milky rather than lit. Multiplying leaves
 * black at black, scales the midtones proportionally, and drives what was already bright up into
 * clipping, which is what reads as a shine.
 */
private const val PosterHighlightShineGain = 0.25f

/**
 * The peak gain [PosterHighlightMode.Sweep] reaches under its band, and how long one pass takes.
 *
 * Higher than the steady [PosterHighlightShineGain] because no pixel holds it: the band crosses any
 * given point in a fraction of the pass, so matching Shine's 0.25 reads as the weaker of the two
 * rather than the equal. 0.35 over 650ms is what ElegantFin's CSS uses for the same effect; both are
 * starting points, worth re-tuning against real artwork.
 */
private const val PosterHighlightSweepGain = 0.35f
private const val PosterHighlightSweepDurationMillis = 650

/**
 * Draws the configured highlight ring around a poster while its row is highlighting it, on top of
 * the magnification that highlight already applies. Off by default; [PosterHighlightMode] is a
 * global poster preference, so this reads the saved style directly instead of taking a parameter.
 *
 * **Apply this before the card's `Modifier.clip`, not after.** Drawn after the clip, the ring is
 * inside the clip layer and its outer edge is cut by the layer's own rounded rect, thinning the
 * stroke further exactly where it is already weakest. Ahead of the clip it composites over the
 * finished card, the way the detail screen's focus rings do.
 *
 * [cornerRadius] is the card's own radius rather than a [androidx.compose.ui.graphics.Shape] so the
 * strokes can be centred on that path; the ring is concentric with the card at every radius preset,
 * including the sharp-cornered one.
 */
@Composable
internal fun Modifier.nuvioPosterHighlight(cornerRadius: Dp): Modifier {
    val mode = rememberPosterCardStyleUiState().posterHighlightMode
    if (mode == PosterHighlightMode.Off) return this
    val highlighted = LocalNuvioShelfItemHighlighted.current
    if (mode == PosterHighlightMode.Sweep) return nuvioSweepHighlight(highlighted, cornerRadius)
    val colors = MaterialTheme.nuvio.colors
    // Fades with the magnification instead of snapping on, so a cursor swept across a row leaves a
    // trail of rings settling rather than a strobe.
    val ringAlpha by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        label = "posterHighlightRing",
    )
    if (ringAlpha <= 0f) return this
    // Accent mode follows the theme's accent fill, so a gradient accent sweeps across the ring.
    val ringStops = when (mode) {
        PosterHighlightMode.Accent -> listOfNotNull(colors.accent, colors.accentGradientEnd)
        else -> listOf(Color.White)
    }

    return when (mode) {
        PosterHighlightMode.Shine -> drawWithContent {
            drawNuvioPosterShine(strength = PosterHighlightShineGain * ringAlpha)
        }
        else -> drawWithContent {
            drawContent()
            drawNuvioPosterRing(
                stops = ringStops.map { it.copy(alpha = it.alpha * ringAlpha) },
                featherStops = ringStops.map {
                    it.copy(alpha = it.alpha * ringAlpha * PosterHighlightFeatherAlpha)
                },
                cornerRadius = cornerRadius,
            )
        }
    }
}

/**
 * A single pass of light across the card each time it starts being highlighted: the artwork
 * brightens under a soft band travelling the card's long axis, then settles back to rest even while
 * the pointer stays on it.
 *
 * One pass rather than a loop. A cursor swept along a shelf already fires a pass per card it
 * crosses; repeating on each of those turns the row into a strobe.
 */
/**
 * The sweep as a standalone modifier, driven by an explicit [highlighted] flag rather than the
 * shelf's highlight local, so interactive surfaces that are not poster cards — buttons picking up
 * keyboard focus or hover — can use the same pass of light instead of a Material focus overlay.
 */
@Composable
internal fun Modifier.nuvioSweepHighlight(highlighted: Boolean, cornerRadius: Dp): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        // Parking at 0 on the way out leaves the band just off the leading edge, where it is
        // invisible, so an interrupted pass simply stops. Animating it back instead — what the CSS
        // transition this is modelled on does — runs the sweep backwards through the card every
        // time the pointer leaves mid-pass.
        progress.snapTo(0f)
        if (!highlighted) return@LaunchedEffect
        progress.animateTo(
            targetValue = 1f,
            // Linear on purpose: eased, the band appears to slow down in the middle of the card,
            // which reads as the animation calling attention to itself rather than as light
            // crossing the artwork at a constant speed.
            animationSpec = tween(PosterHighlightSweepDurationMillis, easing = LinearEasing),
        )
    }
    // The progress read belongs inside the draw lambda, not out here. Read during composition it
    // would recompose the card on every frame of the pass; deferred to the draw phase it
    // invalidates drawing alone, which is all that changes.
    return drawWithContent {
        drawNuvioPosterSweep(
            progress = progress.value,
            strength = PosterHighlightSweepGain,
            cornerRadius = cornerRadius,
        )
    }
}

/**
 * Draws the card, then composites it over itself once more through an additive layer at
 * [strength] — leaving every pixel at `(1 + strength)` times its own value.
 *
 * Extracted from the modifier so the gain can be rendered and measured offscreen without going
 * anywhere near the saved poster preferences.
 */
internal fun ContentDrawScope.drawNuvioPosterShine(strength: Float) {
    drawContent()
    if (strength <= 0f) return
    val paint = Paint().apply {
        alpha = strength
        blendMode = BlendMode.Plus
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(size.toRect(), paint)
        drawContent()
        canvas.restore()
    }
}

/**
 * Draws the card, then a band-shaped copy of it over itself at [strength], positioned by [progress].
 *
 * The band is a mask over the same additive layer [drawNuvioPosterShine] uses, for the same reason:
 * a white gradient painted straight onto the card would lift its blacks to grey as it passed, so
 * the band would arrive as a milky smear rather than as light. Masking a gain leaves black at black
 * and drives what is already bright into clipping, which is what reads as a highlight travelling
 * over the artwork.
 */
internal fun ContentDrawScope.drawNuvioPosterSweep(
    progress: Float,
    strength: Float,
    cornerRadius: Dp,
) {
    drawContent()
    if (progress <= 0f || progress >= 1f || strength <= 0f) return
    // Along the card's long axis, so portrait posters wash top to bottom and landscape cards left
    // to right. Across the short axis the band would clear a wide card in a few tens of pixels.
    val vertical = size.height >= size.width
    val extent = if (vertical) size.height else size.width
    // The band is one card long and travels two card lengths: at 0 it sits entirely before the
    // leading edge, at 1 entirely past the trailing one.
    val head = extent * (2f * progress - 1f)
    val band = Brush.linearGradient(
        // A wide soft wash with a short plateau, not a hard streak — the fade is most of the band.
        0f to Color.Transparent,
        0.45f to Color.White,
        0.55f to Color.White,
        1f to Color.Transparent,
        start = if (vertical) Offset(0f, head) else Offset(head, 0f),
        end = if (vertical) Offset(0f, head + extent) else Offset(head + extent, 0f),
    )
    val paint = Paint().apply {
        alpha = strength
        blendMode = BlendMode.Plus
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(size.toRect(), paint)
        drawContent()
        // Keeps only what the band covers. DstIn multiplies the layer's alpha by the gradient's, so
        // the gain ramps in and out with the band instead of ending on a seam. Rounded to the
        // card's own radius because this modifier runs ahead of the card's clip: a plain rect would
        // paint the gain across the corners the clip is about to cut away.
        drawRoundRect(
            brush = band,
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }
}

/** The ring treatment shared by [PosterHighlightMode.White] and [PosterHighlightMode.Accent]. */
internal fun ContentDrawScope.drawNuvioPosterRing(
    stops: List<Color>,
    featherStops: List<Color>,
    cornerRadius: Dp,
) {
    // One stop paints flat; two sweep the ring left to right, matching every other accent fill.
    val ringBrush = stops.singleOrNull()?.let(::SolidColor) ?: Brush.horizontalGradient(stops)
    val featherBrush =
        featherStops.singleOrNull()?.let(::SolidColor) ?: Brush.horizontalGradient(featherStops)
    val radiusPx = cornerRadius.toPx()
    val corePx = PosterHighlightRingWidth.toPx()
    val featherPx = PosterHighlightFeatherWidth.toPx()
    // Inner feather, then outer: both centred half a core plus half a feather off the card's
    // edge, so the three strokes meet without overlapping or leaving a gap between them.
    listOf(1f, -1f).forEach { side ->
        val offset = side * (corePx + featherPx) / 2f
        drawRoundRect(
            brush = featherBrush,
            topLeft = Offset(offset, offset),
            size = Size(size.width - offset * 2f, size.height - offset * 2f),
            cornerRadius = CornerRadius((radiusPx - offset).coerceAtLeast(0f)),
            style = Stroke(width = featherPx),
        )
    }
    drawRoundRect(
        brush = ringBrush,
        cornerRadius = CornerRadius(radiusPx),
        style = Stroke(width = corePx),
    )
}
