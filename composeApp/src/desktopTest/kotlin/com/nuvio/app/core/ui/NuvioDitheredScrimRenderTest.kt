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
import kotlin.math.pow
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

    private fun render(content: Color, dithered: Boolean, scrim: Color = this.scrim): Bitmap {
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

    /** The shader's HDR correction, sRGB_encode(x^2.2), in 0..255 levels. */
    private fun hdrCorrected(level: Float): Float {
        val linear = (level / 255f).toDouble().pow(2.2)
        val encoded = if (linear < 0.0031308) linear * 12.92 else 1.055 * linear.pow(1 / 2.4) - 0.055
        return (encoded * 255).toFloat()
    }

    private fun <T> withHdr(block: () -> T): T {
        DesktopHdrUiCompensation.active = true
        try {
            return block()
        } finally {
            DesktopHdrUiCompensation.active = false
        }
    }

    @Test
    fun hdrCorrectionPullsTheFadeDownToGamma22() {
        // A mid-dark backdrop: the near-black end of the fade is where Windows' sRGB decode lifts
        // furthest, so that is where the corrected ramp must sit visibly below the plain one.
        val level = 60f
        val content = Color(red = level / 255f, green = level / 255f, blue = level / 255f)
        val plain = render(content, dithered = true)
        val corrected = withHdr { render(content, dithered = true) }
        for (fraction in listOf(0.34f, 0.5f, 0.6f, 0.75f, 0.82f, 0.95f, 1f)) {
            val x = (fraction * (width - 1)).toInt()
            val uncorrected = columnMean(plain, x)
            val expected = hdrCorrected(uncorrected)
            val actual = columnMean(corrected, x)
            assertTrue(
                abs(actual - expected) <= 1.5f,
                "at ${fraction * 100}% across: corrected $actual, expected $expected (uncorrected $uncorrected)",
            )
        }
        // The two curves meet towards white and part near black: 60 only drops to ~57, while the
        // fade's tail (a few levels) is roughly halved.
        assertTrue(columnMean(corrected, width - 1) < level - 2, "the backdrop itself should be darkened")
        val tail = (0.34f * (width - 1)).toInt()
        assertTrue(
            columnMean(corrected, tail) < columnMean(plain, tail) * 0.75f,
            "the near-black tail should be pulled down hard",
        )
    }

    @Test
    fun hdrCorrectionLeavesAColouredScrimMeetingThePageWithoutASeam() {
        // The page around the hero is not corrected, so where the scrim is opaque the hero must
        // still be exactly the scrim colour, not its corrected (darker) version.
        val themeScrim = Color(red = 18 / 255f, green = 18 / 255f, blue = 30 / 255f)
        val corrected = withHdr { render(Color.White, dithered = true, scrim = themeScrim) }
        assertTrue(abs(columnMean(corrected, 0) - 18f) <= 1f, "left edge should stay the scrim colour")
    }

    private fun renderFadeCorrection(base: Color, content: Color): Bitmap {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(base)) {
                Box(Modifier.fillMaxSize().nuvioHdrFadeCorrection(base)) {
                    Box(Modifier.fillMaxSize().background(content))
                }
            }
        }
        val image = scene.render()
        return Bitmap().apply {
            allocN32Pixels(image.width, image.height)
            image.readPixels(this)
        }.also { scene.close() }
    }

    private fun grey(level: Int) = Color(red = level / 255f, green = level / 255f, blue = level / 255f)

    @Test
    fun hdrFadeCorrectionIsInertOnSdr() {
        val out = renderFadeCorrection(grey(13), grey(20))
        assertTrue(abs(columnMean(out, width / 2) - 20f) < 0.01f, "SDR must leave the pixels untouched")
    }

    @Test
    fun hdrFadeCorrectionMatchesTheCurveOverBlack() {
        for (level in listOf(6, 12, 20, 40, 120)) {
            val out = withHdr { renderFadeCorrection(Color.Black, grey(level)) }
            val actual = columnMean(out, width / 2)
            val expected = hdrCorrected(level.toFloat())
            assertTrue(abs(actual - expected) <= 1f, "level $level: corrected $actual, expected $expected")
        }
    }

    @Test
    fun hdrFadeCorrectionKeepsTheBackgroundColourSoThereIsNoSeam() {
        // TV Mode's near-black background: the hero's fades end on it, and the page around the hero
        // is not corrected, so it has to come out of the correction unchanged.
        val base = grey(13)
        val atBase = withHdr { renderFadeCorrection(base, base) }
        assertTrue(abs(columnMean(atBase, width / 2) - 13f) <= 0.6f, "the background colour must map to itself")
        // A little above it the correction is easing in: pulled down, but not all the way.
        val above = withHdr { renderFadeCorrection(base, grey(30)) }
        val mean = columnMean(above, width / 2)
        assertTrue(mean in 14f..29f, "a dark grey above the background should come down, got $mean")
    }

    @Test
    fun theShelfGradientIsDitheredAndStillLandsOnItsStops() {
        // TV Mode's shelf: clear at the top to the theme background (#0D0D0D) at the bottom, over
        // black. Thirteen levels across 600 px is thirteen ~46 px stripes undithered.
        val tall = 600
        val background = Color(0xFF0D0D0D)
        val stops = arrayOf(
            0f to background.copy(alpha = 0f),
            0.30f to background.copy(alpha = 0.28f),
            0.62f to background.copy(alpha = 0.88f),
            1f to background,
        )
        fun renderColumn(dithered: Boolean): Bitmap {
            val scene = ImageComposeScene(width = 16, height = tall, density = Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(
                        Modifier.fillMaxSize().background(
                            if (dithered) nuvioDitheredVerticalGradient(stops) else Brush.verticalGradient(colorStops = stops),
                        ),
                    )
                }
            }
            val image = scene.render()
            return Bitmap().apply {
                allocN32Pixels(image.width, image.height)
                image.readPixels(this)
            }.also { scene.close() }
        }
        fun transitions(bitmap: Bitmap): Int = (1 until tall).count { y -> green(bitmap, 8, y) != green(bitmap, 8, y - 1) }
        fun rowMean(bitmap: Bitmap, y: Int): Float = (0 until 16).sumOf { x -> green(bitmap, x, y) }.toFloat() / 16
        val plain = renderColumn(dithered = false)
        val dithered = renderColumn(dithered = true)
        assertTrue(transitions(dithered) > transitions(plain) * 4, "dithered ${transitions(dithered)} vs plain ${transitions(plain)}")
        for (y in listOf(0, 150, 300, 450, tall - 1)) {
            assertTrue(abs(rowMean(dithered, y) - rowMean(plain, y)) <= 1f, "row $y: dithered ${rowMean(dithered, y)} vs plain ${rowMean(plain, y)}")
        }
    }
}
