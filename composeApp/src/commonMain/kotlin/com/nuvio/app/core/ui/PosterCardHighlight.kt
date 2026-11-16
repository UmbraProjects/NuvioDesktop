package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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

/**
 * Band length as a fraction of the card's long axis. 1.0 is a band exactly one card long - the
 * original design, described as "a wide soft wash with a short plateau, not a hard streak".
 *
 * Briefly shipped at 0.2 while the poster-row flicker was thought to be the sweep's fault. It was
 * not: the cause was the hero backdrop swapping in a single frame, which on a variable-refresh OLED
 * is an instantaneous full-screen brightness step the panel answers with a dim. The sweep only ADDED
 * to that step. With the backdrop crossfade in place, and the card now recorded into a GraphicsLayer
 * once instead of re-rasterised every frame, a full-length band at full gain measured clean.
 *
 * The narrow band was a workaround for a misdiagnosis, and it looked like an xray scanner. Kept as a
 * constant rather than inlined so it stays adjustable if a future effect needs the budget back.
 */
private const val PosterHighlightSweepBandFraction = 1f
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
internal fun Modifier.nuvioPosterHighlight(cornerRadius: Dp, artworkUrl: String? = null): Modifier {
    val mode = rememberPosterCardStyleUiState().posterHighlightMode
    if (mode == PosterHighlightMode.Off) return this
    val highlighted = LocalNuvioShelfItemHighlighted.current
    if (mode == PosterHighlightMode.Sweep) return nuvioSweepHighlight(highlighted, cornerRadius)
    if (mode == PosterHighlightMode.Glow) {
        // Tells the shelf slot this card shows its own highlight, so it can leave the card its normal size.
        val report = LocalNuvioShelfHighlightReport.current
        SideEffect { report?.drawsGlow = true }
        return nuvioGlowHighlight(highlighted, cornerRadius, artworkUrl, PosterGlowSpread)
    }
    val colors = MaterialTheme.nuvio.colors
    // Fades with the magnification instead of snapping on, so a cursor swept across a row leaves a
    // trail of rings settling rather than a strobe.
    val shineCache = rememberGraphicsLayer()
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
            drawNuvioPosterShine(
                strength = PosterHighlightShineGain * ringAlpha,
                cache = shineCache,
            )
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
    val sweepCache = rememberGraphicsLayer()
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
            sweepCache = sweepCache,
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
internal fun ContentDrawScope.drawNuvioPosterShine(
    strength: Float,
    /**
     * A recording of the card, so the additive copy is a texture composite rather than a second
     * full rasterisation of the card's draw commands every frame.
     *
     * Shine does not animate - the brightened copy it produces is identical on every frame - so
     * re-rendering the whole card 60 times a second to arrive at the same pixels is pure waste, and
     * it is enough waste to overrun the frame budget. Null keeps the original behaviour.
     */
    cache: GraphicsLayer? = null,
) {
    if (strength <= 0f || cache == null) drawContent()
    if (strength <= 0f) return
    val paint = Paint().apply {
        alpha = strength
        blendMode = BlendMode.Plus
    }
    if (cache != null) {
        // Re-record only when the card's size changes; its content is static for the life of a
        // highlight, which is the whole reason this can be cached at all.
        if (cache.size.width != size.width.toInt() || cache.size.height != size.height.toInt()) {
            cache.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
                this@drawNuvioPosterShine.drawContent()
            }
        }
        // ONE card-sized draw, not two. The additive form paints the card and then composites a
        // second full-card copy over it, so the highlighted card costs twice the fill of an
        // unhighlighted one - and painted area per frame is what tracks the flicker, independent of
        // frame timing. A colour matrix on the recording produces the same result in a single pass:
        // every channel scaled by 1+strength, so black stays black and bright pixels clip, which is
        // exactly what the additive copy was for.
        val gain = 1f + strength
        cache.colorFilter = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    gain, 0f, 0f, 0f, 0f,
                    0f, gain, 0f, 0f, 0f,
                    0f, 0f, gain, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        drawLayer(cache)
        return
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
    /**
     * Band length as a fraction of the card's long axis. 1.0 is the original: a band exactly one
     * card long, which at mid-pass covers the whole card and repaints all of it every frame. That
     * per-frame area is what triggers the row flicker - measured, by a variant that painted a flat
     * translucent wash over the whole card (flickers) against one that painted a gradient over a
     * fifth of it (clean). Shortening the band cuts the repainted area without changing anything
     * else about how the effect is drawn.
     */
    bandFraction: Float = PosterHighlightSweepBandFraction,
    /** Optional recording of the card; see [drawNuvioPosterShine]. */
    sweepCache: GraphicsLayer? = null,
) {
    drawContent()
    if (progress <= 0f || progress >= 1f || strength <= 0f) return
    drawSweepMaskedGain(progress, strength, cornerRadius, bandFraction, sweepCache)
}

/** The shipping sweep: a band-masked additive copy of the card. */
private fun ContentDrawScope.drawSweepMaskedGain(
    progress: Float,
    strength: Float,
    cornerRadius: Dp,
    bandFraction: Float,
    /** See [drawNuvioPosterShine]: the card recorded once, composited per frame. */
    cache: GraphicsLayer? = null,
) {
    // Along the card's long axis, so portrait posters wash top to bottom and landscape cards left
    // to right. Across the short axis the band would clear a wide card in a few tens of pixels.
    val vertical = size.height >= size.width
    val axis = if (vertical) size.height else size.width
    val extent = axis * bandFraction.coerceIn(0.05f, 1f)
    // The band travels from entirely before the leading edge to entirely past the trailing one.
    val head = (axis + extent) * progress - extent
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
    // The layer covers only the strip the band is currently over, not the whole card. Shortening
    // the band alone changed nothing, because `drawContent()` still replayed the entire card into a
    // full-card layer and the mask merely threw most of it away - the DRAWN area never shrank. That
    // drawn area is what triggers the flicker: Shine, which is a full-card layer and replay with no
    // band and no animation at all, flickers too, while White and Accent (which paint only a ring)
    // do not. Clipping the layer to the band means the replay rasterises a strip instead of a card.
    val start = head.coerceIn(0f, axis)
    val end = (head + extent).coerceIn(0f, axis)
    if (end - start <= 0f) return
    val layerBounds = if (vertical) {
        Rect(left = 0f, top = start, right = size.width, bottom = end)
    } else {
        Rect(left = start, top = 0f, right = end, bottom = size.height)
    }
    if (cache != null &&
        (cache.size.width != size.width.toInt() || cache.size.height != size.height.toInt())
    ) {
        cache.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
            this@drawSweepMaskedGain.drawContent()
        }
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(layerBounds, paint)
        if (cache != null) drawLayer(cache) else drawContent()
        // Keeps only what the band covers. DstIn multiplies the layer's alpha by the gradient's, so
        // the gain ramps in and out with the band instead of ending on a seam. Still drawn over the
        // whole card and still rounded to the card's own radius - the layer bounds clip it, and the
        // rounding matters wherever the strip overlaps a corner the card's clip is about to cut.
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

/**
 * [PosterHighlightMode.Glow]: the What to watch picker's bloom (TonightAmbient.AmbientGlow) round the card, in the
 * main colour of its own artwork (a green poster glows green), or a soft accent until that colour is read or when the
 * card has no artwork URL. Rings drawn outward from the edge, so it looks the same on every renderer; it draws past
 * the card's bounds, which is why the highlight goes ahead of the card's clip.
 */
@Composable
private fun Modifier.nuvioGlowHighlight(highlighted: Boolean, cornerRadius: Dp, artworkUrl: String?, spread: Dp): Modifier {
    val fallback = androidx.compose.ui.graphics.lerp(MaterialTheme.nuvio.colors.accent, Color.White, 0.2f)
    val colour = com.nuvio.app.features.tonight.rememberGlowOf(artworkUrl, fallback)
    val alpha by animateFloatAsState(if (highlighted) 1f else 0f, label = "posterGlow")
    val shown by androidx.compose.animation.animateColorAsState(colour, label = "posterGlowColour")
    if (alpha <= 0.001f) return this
    return drawBehind {
        val step = 1.dp.toPx()
        val rings = (spread.toPx() / step).toInt().coerceAtLeast(1)
        val layer = 1f - Math.pow((1f - PosterGlowStrength).toDouble(), 1.0 / rings).toFloat()
        val radius = cornerRadius.toPx()
        for (i in rings downTo 0) {
            val grow = i * step
            drawRoundRect(
                color = shown.copy(alpha = layer * alpha),
                topLeft = androidx.compose.ui.geometry.Offset(-grow, -grow),
                size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius + grow),
            )
        }
    }
}

/** A little wider than the picker's tiles: posters are smaller and sit further apart. */
private val PosterGlowSpread = 7.dp

/**
 * Set by a card inside a shelf slot when it draws the Glow highlight itself. The slot drops its enlarge only then: a
 * card with no highlight of its own (trailers, comments, cast) keeps the enlarge, so a row never loses its place.
 */
internal class NuvioShelfHighlightReport {
    var drawsGlow by mutableStateOf(false)
}

internal val LocalNuvioShelfHighlightReport = compositionLocalOf<NuvioShelfHighlightReport?> { null }
private const val PosterGlowStrength = 0.8f
