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
 * The panel fill is a twelve-level spread, narrow enough that a level boundary lands every fifty
 * or so pixels — squarely in the range that reads as stripes, and the reason the first attempt at
 * a gradient here was reverted. These read rendered pixels rather than trusting the brush, because
 * banding is a property of the 8-bit output, not of the stops that produced it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NuvioSurfaceBackdropRenderTest {

    private val base = Color(0xFF1A1A1A)
    private val baseLevel = 0x1A

    /** Matches `BACKDROP_HALF_SPREAD`; restated so the test says what it is asserting on. */
    private val halfSpread = 4f

    /** Matches `BACKDROP_DARKEN`. */
    private val darken = 4f

    private val width = 320
    private val height = 620

    /**
     * The fill sits a few levels below the theme's panel colour. Asserted rather than left to
     * drift: it is the difference between a panel that reads as a panel and one that reads as the
     * page, and nothing else in the app would notice if it moved.
     */
    @Test
    fun `fill sits just below the panel colour`() {
        val mean = render(NuvioBackdropDirection.Vertical).average()
        val expected = baseLevel - darken

        assertTrue(
            abs(mean - expected) <= 1.0,
            "fill averages $mean; expected about $expected ($baseLevel darkened by $darken)",
        )
    }

    @Test
    fun `fill spans the intended spread`() {
        val profile = rowMeans(render(NuvioBackdropDirection.Vertical))
        val lit = profile.first()
        val shaded = profile.last()

        assertTrue(lit > shaded, "fill is not darkening downward: $lit -> $shaded")
        val span = lit - shaded
        // Wide enough to see, narrow enough that neither end fights the text over it. Both bounds
        // are load-bearing: wider spreads were tried twice and read as too much distance between
        // the two ends, and much below six levels it is invisible and only the banding survives.
        assertTrue(span >= halfSpread * 1.5f, "fill only spans $span levels; that is invisible")
        assertTrue(
            span <= halfSpread * 2.5f,
            "fill spans $span levels; that is more distance between the ends than the panel wants",
        )
    }

    /**
     * A band is a hard edge: one row averages a whole level away from the row above it, across the
     * full width. Dithering trades that edge for a gradual change in how many pixels have already
     * stepped, so the same profile climbs in fractions of a level instead.
     */
    @Test
    fun `dither dissolves the contours`() {
        val plain = steepestStep(rowMeans(renderUndithered()))
        val dithered = steepestStep(rowMeans(render(NuvioBackdropDirection.Vertical)))

        // If this ever stops holding, the spread has widened enough not to need dithering and the
        // assertion below has nothing left to prove.
        assertTrue(plain >= 0.9f, "undithered ramp has no contours to dissolve (steepest $plain)")
        assertTrue(
            dithered <= 0.5f,
            "rows still step by $dithered levels at once; contours will read as bands",
        )
    }

    @Test
    fun `dither is imperceptible on its own`() {
        val pixels = render(NuvioBackdropDirection.Vertical)
        // A vertical fill is constant across a row, so any spread within one row is the noise.
        var worst = 0
        for (y in intArrayOf(8, height / 3, height / 2, height - 9)) {
            val row = IntArray(width) { x -> pixels[y * width + x] }
            worst = maxOf(worst, row.max() - row.min())
        }
        assertTrue(worst <= 3, "dither spans $worst levels; that is a visible texture, not noise")
    }

    @Test
    fun `diagonal fill runs corner to corner`() {
        val pixels = render(NuvioBackdropDirection.Diagonal)
        val topLeft = corner(pixels, 0, 0)
        val topRight = corner(pixels, width - 24, 0)
        val bottomLeft = corner(pixels, 0, height - 24)
        val bottomRight = corner(pixels, width - 24, height - 24)

        assertTrue(topLeft > topRight, "top edge is flat: $topLeft vs $topRight")
        assertTrue(topLeft > bottomLeft, "left edge is flat: $topLeft vs $bottomLeft")
        assertTrue(
            bottomRight < topRight && bottomRight < bottomLeft,
            "bottom-right is not the dark corner: $bottomRight",
        )
    }

    /** Green channel of every pixel — the fill is neutral, so one channel says it all. */
    private fun render(direction: NuvioBackdropDirection): IntArray =
        paint(nuvioBackdropBrush(base, direction))

    private fun renderUndithered(): IntArray = paint(
        Brush.verticalGradient(
            listOf(
                Color(0xFF1A1A1A + (halfSpread.toInt() * 0x010101)),
                Color(0xFF1A1A1A - (halfSpread.toInt() * 0x010101)),
            ),
        ),
    )

    private fun paint(brush: Brush): IntArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            Box(modifier = Modifier.fillMaxSize().background(brush))
        }
        val image = scene.render()
        val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
        require(image.readPixels(bitmap, 0, 0))
        val pixels = IntArray(width * height) { index ->
            (bitmap.getColor(index % width, index / width) shr 8) and 0xFF
        }
        scene.close()
        return pixels
    }

    /** Mean over a 24px block, so a single dithered pixel cannot decide a comparison. */
    private fun corner(pixels: IntArray, x0: Int, y0: Int): Float {
        var sum = 0
        for (y in y0 until y0 + 24) for (x in x0 until x0 + 24) sum += pixels[y * width + x]
        return sum.toFloat() / (24 * 24)
    }

    private fun rowMeans(pixels: IntArray): FloatArray = FloatArray(height) { y ->
        (0 until width).sumOf { x -> pixels[y * width + x] }.toFloat() / width
    }

    private fun steepestStep(profile: FloatArray): Float {
        var worst = 0f
        for (y in 1 until profile.size) {
            worst = maxOf(worst, abs(profile[y] - profile[y - 1]))
        }
        return worst
    }
}
