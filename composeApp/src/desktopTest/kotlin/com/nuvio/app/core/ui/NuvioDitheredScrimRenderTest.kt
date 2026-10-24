package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The details hero's scrim as a render effect, measured against the gradient boxes it replaces.
 *
 * The case that motivated it: a backdrop sitting a few levels above black. A translucent gradient
 * over that has only those few output levels to spend across the whole width, so it lands as
 * bands — every one of them a hard step. The dithered version has to (a) compile, since SkSL only
 * fails at runtime, (b) land on the same overall ramp as the gradient it replaces, and (c) spread
 * every step across neighbouring pixels rather than leaving it as one contour.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NuvioDitheredScrimRenderTest {

    private val width = 800
    private val height = 24
    private val scrim = Color.Black

    // The details hero's own ramps, so this measures what ships.
    private val side = NuvioScrimRamp(listOf(0f to 1f, 0.34f to 0.9f, 0.6f to 0.42f, 0.82f to 0.12f, 1f to 0f))
    private val noBottom = NuvioScrimRamp.None

    private fun render(content: Color, dithered: Boolean): Bitmap {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            if (dithered) {
                Box(Modifier.fillMaxSize().nuvioDitheredScrim(scrim, side, noBottom)) {
                    Box(Modifier.fillMaxSize().background(content))
                }
            } else {
                Box(Modifier.fillMaxSize().background(content)) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(
                                colorStops = side.stops.map { (at, alpha) -> at to scrim.copy(alpha = alpha) }
                                    .toTypedArray(),
                            ),
                        ),
                    )
                }
            }
        }
        val image = scene.render()
        return Bitmap().apply {
            allocN32Pixels(image.width, image.height)
            image.readPixels(this)
        }.also { scene.close() }
    }

    private fun green(bitmap: Bitmap, x: Int, y: Int): Int = bitmap.getColor(x, y) shr 8 and 0xFF

    /** Mean green level down one column: the dither averages out, the ramp remains. */
    private fun columnMean(bitmap: Bitmap, x: Int): Float =
        (0 until height).sumOf { y -> green(bitmap, x, y) }.toFloat() / height

    @Test
    fun theDitheredScrimFollowsTheGradientItReplaces() {
        val content = Color.White
        val dithered = render(content, dithered = true)
        val plain = render(content, dithered = false)
        for (fraction in listOf(0f, 0.2f, 0.34f, 0.5f, 0.6f, 0.75f, 0.82f, 0.95f, 1f)) {
            val x = (fraction * (width - 1)).toInt()
            val a = columnMean(dithered, x)
            val b = columnMean(plain, x)
            assertTrue(
                abs(a - b) <= 2.5f,
                "at ${fraction * 100}% across: dithered $a vs gradient $b",
            )
        }
    }

    @Test
    fun aNearBlackBackdropIsNoLongerBanded() {
        // Ten levels above black: the gradient version has exactly ten output levels to move
        // through across 800 px, i.e. ten stripes each ~80 px wide with a one-level cliff between.
        val content = Color(red = 10 / 255f, green = 10 / 255f, blue = 10 / 255f)
        val plain = render(content, dithered = false)
        val dithered = render(content, dithered = true)

        // The plain ramp: count columns where the level changes from the previous column. With
        // pure banding that is a handful of cliffs; the middle of the ramp is flat.
        fun cliffs(bitmap: Bitmap): Int = (1 until width).count { x ->
            green(bitmap, x, height / 2) != green(bitmap, x - 1, height / 2)
        }
        val plainCliffs = cliffs(plain)
        assertTrue(plainCliffs in 5..40, "expected the plain gradient to band, got $plainCliffs transitions")

        // Dithered: within any 4-px window the mean must never move by a full level — the step
        // has been spread across the neighbourhood instead of standing as a contour. And the
        // overall ramp still lands where the gradient's does.
        val windows = (0 until width - 4 step 4).map { x0 ->
            (x0 until x0 + 4).map { x -> columnMean(dithered, x) }.average().toFloat()
        }
        val largestJump = windows.zipWithNext { a, b -> abs(a - b) }.max()
        assertTrue(largestJump < 0.75f, "dithered ramp still steps: largest 4-px jump $largestJump levels")
        assertTrue(abs(columnMean(dithered, width - 1) - 10f) <= 1f, "right edge should be the backdrop")
        assertTrue(columnMean(dithered, 0) <= 1f, "left edge should be the scrim")
    }
}
