package com.nuvio.app.features.music

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import kotlin.math.exp

private const val HOLE = 0.085f
private const val HUB_OUTER = 0.30f
private const val HUB_RING = 0.215f

/**
 * A compact disc that turns while [spinning] and winds down to a stop when it ends, rather than
 * freezing: speed eases toward the target a bit each frame, so pausing a track feels like a platter
 * slowing.
 *
 * The face is [cdArtUrl] when there is one (the printed disc: a round transparent PNG from
 * Fanart.tv or TheAudioDB, drawn as it is). Otherwise the disc is drawn here, with [coverUrl] as
 * its printed face, a clear plastic hub and a centre hole, so every album has a disc. If the
 * disc image fails to load the drawn disc takes over.
 *
 * The light does not turn with the disc: the sheen is a fixed layer above the turning one, which is
 * what makes it read as a shiny object and not a flat picture rotating.
 *
 * No frame callbacks run while the disc is at rest.
 */
@Composable
fun MusicCdSpinner(
    coverUrl: String?,
    cdArtUrl: String?,
    spinning: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    secondsPerTurn: Float = 2.4f,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    // Degrees a second. Held outside state: only the angle needs to invalidate drawing.
    val speed = remember { FloatArray(1) }
    val topSpeed = 360f / secondsPerTurn

    LaunchedEffect(spinning, topSpeed) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            val goal = if (spinning) topSpeed else 0f
            val rate = if (spinning) 2.0f else 1.3f
            speed[0] += (goal - speed[0]) * (1f - exp(-rate * dt))
            if (!spinning && speed[0] < 0.5f) {
                speed[0] = 0f
                break
            }
            angle = (angle + speed[0] * dt) % 360f
        }
    }

    var cdArtFailed by remember(cdArtUrl) { mutableStateOf(false) }
    val usePrintedDisc = cdArtUrl != null && !cdArtFailed

    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = angle
                    // Offscreen so the centre hole can really be cut out (BlendMode.Clear) of the disc.
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            if (usePrintedDisc) {
                NuvioAsyncImage(
                    model = cdArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { cdArtFailed = true },
                )
            } else {
                DrawnDisc(coverUrl)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = center
            val hole = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r * HOLE)) }
            clipPath(hole, clipOp = ClipOp.Difference) {
                // Two opposite glints, as light off a real disc, plus a faint diffraction wash.
                drawCircle(
                    brush = Brush.sweepGradient(
                        0.00f to Color.Transparent,
                        0.07f to Color.White.copy(alpha = 0.34f),
                        0.15f to Color.Transparent,
                        0.50f to Color.Transparent,
                        0.57f to Color.White.copy(alpha = 0.34f),
                        0.65f to Color.Transparent,
                        1.00f to Color.Transparent,
                        center = c,
                    ),
                    radius = r,
                    center = c,
                )
                drawCircle(
                    brush = Brush.sweepGradient(
                        0.00f to Color(0x00FF3366),
                        0.20f to Color(0x2200E5FF),
                        0.40f to Color(0x00FFD600),
                        0.60f to Color(0x2200E676),
                        0.80f to Color(0x00B388FF),
                        1.00f to Color(0x00FF3366),
                        center = c,
                    ),
                    radius = r,
                    center = c,
                )
            }
            // Bevel at the rim.
            drawCircle(Color.White.copy(alpha = 0.30f), radius = r - 0.75f, center = c, style = Stroke(1.5f))
            drawCircle(Color.Black.copy(alpha = 0.25f), radius = r - 2.5f, center = c, style = Stroke(1f))
        }
    }
}

/** The disc as drawn here: printed face (the cover, or silver when there is none), grooves, clear hub, hole. */
@Composable
private fun DrawnDisc(coverUrl: String?) {
    Box(Modifier.fillMaxSize().clip(CircleShape)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.sweepGradient(
                    0.0f to Color(0xFFD9DBE1), 0.25f to Color(0xFF9EA2AD),
                    0.5f to Color(0xFFD9DBE1), 0.75f to Color(0xFF9EA2AD), 1.0f to Color(0xFFD9DBE1),
                ),
            ),
        )
        if (coverUrl != null) {
            NuvioAsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = center
            // Data-area grooves: faint concentric lines that make the rotation visible on a flat cover.
            for (i in 0 until 14) {
                drawCircle(
                    color = Color.Black.copy(alpha = if (i % 3 == 0) 0.09f else 0.04f),
                    radius = r * (HUB_OUTER + 0.04f + i * 0.047f),
                    center = c,
                    style = Stroke(1f),
                )
            }
            // A mark on the cover side so the turning is visible even on a plain cover.
            drawCircle(Color.Black.copy(alpha = 0.20f), radius = r * 0.97f, center = c, style = Stroke(r * 0.03f))
            // The clear plastic hub.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xE6F4F5F8), Color(0xE6B8BCC6)),
                    center = c,
                    radius = r * HUB_OUTER,
                ),
                radius = r * HUB_OUTER,
                center = c,
            )
            drawCircle(Color.Black.copy(alpha = 0.22f), radius = r * HUB_OUTER, center = c, style = Stroke(1.5f))
            drawCircle(Color.White.copy(alpha = 0.55f), radius = r * HUB_RING, center = c, style = Stroke(1.5f))
            drawCircle(Color.Black.copy(alpha = 0.15f), radius = r * (HUB_RING - 0.02f), center = c, style = Stroke(1f))
            // The hole.
            drawCircle(Color.Black, radius = r * HOLE, center = c, blendMode = BlendMode.Clear)
        }
    }
}

/**
 * A square cover with a disc behind it. While [spinning] the disc slides out from behind the cover
 * and turns; at rest it tucks back in, so the cover reads as a sleeve.
 *
 * The composable is `size` high and `size * (1 + peek)` wide, where `peek` is how far the disc comes out.
 */
@Composable
fun MusicCdSleeve(
    coverUrl: String?,
    cdArtUrl: String?,
    spinning: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    peek: Float = 0.38f,
    /** Whether the disc sits out of the sleeve. Defaults to following [spinning]; the album object sets it apart. */
    slideOut: Boolean = spinning,
    secondsPerTurn: Float = 2.4f,
    /** Snap instead of sliding (reduced motion). */
    instant: Boolean = false,
) {
    val discSize = size * 0.96f
    val out by animateDpAsState(
        targetValue = if (slideOut) size * peek else size * 0.06f,
        animationSpec = if (instant) snap() else tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "discSlide",
    )
    Box(modifier.width(size * (1f + peek)).height(size), contentAlignment = Alignment.CenterStart) {
        MusicCdSpinner(
            coverUrl = coverUrl,
            cdArtUrl = cdArtUrl,
            spinning = spinning,
            secondsPerTurn = secondsPerTurn,
            size = discSize,
            modifier = Modifier.offset(x = out + (size - discSize) / 2),
        )
        MusicCover(coverUrl, description = null, size = size)
    }
}
