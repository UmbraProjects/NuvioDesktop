package com.nuvio.app.features.tonight

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.pow

/*
 * fork:tonight - the launcher's colour (user, Nov 12): the screen behind is a wash of the chosen tile's colours, like
 * the Home hero's ambient background but made of colour only, and the chosen tile glows all round in its own
 * backdrop's colour (a green backdrop glows green) instead of a bright border. Written so other screens can use the
 * glow as a highlight too.
 */

/** Each backdrop's main colours, most present first; kept for the session. Empty = looked, nothing usable. */
private val BackdropColours = mutableStateMapOf<String, List<Color>>()

/** The main colours of each of [urls], filled in as they are read (a small copy of each picture is enough). */
@Composable
internal fun rememberBackdropColours(urls: Collection<String>): Map<String, List<Color>> {
    val context = coil3.compose.LocalPlatformContext.current
    for (url in urls) androidx.compose.runtime.key(url) {
        LaunchedEffect(url) {
            if (url in BackdropColours) return@LaunchedEffect
            val image = runCatching {
                (
                    coil3.SingletonImageLoader.get(context).execute(
                        coil3.request.ImageRequest.Builder(context).data(url).size(320, 180).build(),
                    ) as? coil3.request.SuccessResult
                    )?.image
            }.getOrNull()
            BackdropColours[url] = image?.let {
                runCatching { com.nuvio.app.features.home.extractHeroPaletteColors(it, count = 3) }.getOrNull()
            }.orEmpty()
        }
    }
    return BackdropColours
}

/** The main colours of one picture, or null until read (or when it has none worth using). */
@Composable
internal fun rememberColoursOf(url: String?): List<Color>? {
    val known = rememberBackdropColours(listOfNotNull(url))
    return url?.let { known[it] }?.takeIf { it.isNotEmpty() }
}

/** The glow for a picture: its main colour, lifted (glowColourOf), or [fallback] until it is read. */
@Composable
internal fun rememberGlowOf(url: String?, fallback: Color): Color =
    rememberColoursOf(url)?.first()?.let(::glowColourOf) ?: fallback

/**
 * A colour bright enough to glow: the backdrop's own hue, lifted so a dark picture still lights the space around it,
 * and a grey one stays a soft white rather than turning muddy.
 */
internal fun glowColourOf(colour: Color): Color {
    val r = colour.red
    val g = colour.green
    val b = colour.blue
    val top = max(r, max(g, b))
    if (top <= 0.01f) return Color(0xFFDDE3F0)
    val lift = (0.92f / top).coerceAtMost(4f)
    val bright = Color((r * lift).coerceIn(0f, 1f), (g * lift).coerceIn(0f, 1f), (b * lift).coerceIn(0f, 1f))
    // Keep a little white in it so the glow reads as light, not paint.
    return lerp(bright, Color.White, 0.12f)
}

/**
 * A backdrop colour made fit for the wash: pushed away from grey by half again and lifted so its brightest channel
 * reaches at least 0.6, keeping its hue. A grey stays grey.
 */
internal fun washColourOf(colour: Color): Color {
    val r = colour.red
    val g = colour.green
    val b = colour.blue
    val grey = (r + g + b) / 3f
    fun sat(v: Float) = (grey + (v - grey) * 1.5f).coerceIn(0f, 1f)
    val sr = sat(r)
    val sg = sat(g)
    val sb = sat(b)
    val top = max(sr, max(sg, sb))
    val lift = if (top in 0.01f..0.6f) 0.6f / top else 1f
    return Color((sr * lift).coerceIn(0f, 1f), (sg * lift).coerceIn(0f, 1f), (sb * lift).coerceIn(0f, 1f))
}

/**
 * The full-screen wash: the ground, lit from the top right by the main colour and from the lower left by the second,
 * both dimmed so the white text keeps its contrast. Eases between tiles.
 */
@Composable
internal fun TonightAmbientWash(colours: List<Color>?, modifier: Modifier = Modifier) {
    val main = colours?.getOrNull(0)
    val second = colours?.getOrNull(1) ?: main
    val third = colours?.getOrNull(2) ?: second
    // Richer than the backdrop's own (often muted) colours (user, Nov 12: "boost the saturation/brightness a bit").
    fun tone(c: Color?, keep: Float) = if (c == null) Ground else lerp(Ground, washColourOf(c), keep)
    val spec = tween<Color>(700)
    val a by animateColorAsState(tone(main, 0.68f), spec, label = "wash-a")
    val b by animateColorAsState(tone(second, 0.52f), spec, label = "wash-b")
    val c by animateColorAsState(tone(third, 0.40f), spec, label = "wash-c")
    Box(
        modifier
            .fillMaxSize()
            .background(Ground)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(a.copy(alpha = 0.95f), a.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(size.width * 0.82f, size.height * 0.02f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(b.copy(alpha = 0.85f), Color.Transparent),
                        center = Offset(size.width * 0.08f, size.height * 1.02f),
                        radius = size.maxDimension * 0.55f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(c.copy(alpha = 0.6f), Color.Transparent),
                        center = Offset(size.width * 0.62f, size.height * 0.98f),
                        radius = size.maxDimension * 0.4f,
                    ),
                )
                // A dark band on the left keeps the header's white text crisp over any colour.
                drawRect(
                    Brush.horizontalGradient(
                        0f to Ground.copy(alpha = 0.55f),
                        0.45f to Color.Transparent,
                    ),
                )
            },
    )
}

/**
 * A bloom all round the item, in [colour], shown while [lit] (user, Nov 12: "like bloom", an even halo hugging the
 * edge): thin rounded rings stacked outward from the edge, each a little faint, so the light is strongest at the
 * edge and fades evenly over [spread] (subtle: user, Nov 12, a quarter of the first 22dp). Drawn rather than blurred, so it looks the same on every renderer. Place it
 * first in a Box the same size as the item, whose rounded corners are [cornerRadius]; it draws past the Box's bounds,
 * so the parent must not clip, and the item itself must be opaque (the rings fill behind it).
 */
@Composable
internal fun AmbientGlow(
    lit: Boolean,
    colour: Color,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    spread: Dp = 5.5.dp,
    strength: Float = 0.75f,
) {
    val alpha by animateFloatAsState(if (lit) 1f else 0f, tween(260), label = "glow-alpha")
    val shown by animateColorAsState(colour, tween(260), label = "glow-colour")
    if (alpha <= 0.001f) return
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val step = 1.dp.toPx()
                val rings = (spread.toPx() / step).toInt().coerceAtLeast(1)
                // Each ring adds `layer`, and the rings overlap inward, so the edge reaches `strength` and the light
                // thins out evenly to nothing at `spread`.
                val layer = 1f - (1f - strength).toDouble().pow(1.0 / rings).toFloat()
                val radius = cornerRadius.toPx()
                // Ring 0 sits exactly under the item, so its anti-aliased edge blends into the light rather than
                // leaving a dark hairline between the two.
                for (i in rings downTo 0) {
                    val grow = i * step
                    drawRoundRect(
                        color = shown.copy(alpha = layer * alpha),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(radius + grow),
                    )
                }
            },
    )
}
