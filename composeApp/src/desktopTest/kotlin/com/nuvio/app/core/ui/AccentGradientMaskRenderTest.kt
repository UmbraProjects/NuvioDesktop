package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlin.math.pow
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `accentGradientMask` exists because `Icon`/`Text` take a `Color` and cannot express a `Brush`, so
 * it is the only thing carrying the accent gradient onto icons and labels. It is pure drawing —
 * offscreen compositing plus a `SrcIn` blend — so the only honest way to check it is to render it
 * and read the pixels back.
 *
 * These go through the real `NuvioTheme`, so they also cover the direction plumbing end to end:
 * palette → tokens → brush.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AccentGradientMaskRenderTest {

    private val size = 64

    /** Red-to-blue is used throughout so each channel reads as a clean position along the ramp. */
    private fun render(
        direction: AccentGradientDirection,
        gradient: Boolean,
    ): Bitmap {
        val palette = ThemeColors.customPalette(
            accentHex = "#FF0000",
            accentEndHex = if (gradient) "#0000FF" else "#FF0000",
            backgroundHex = "#000000",
            elevatedHex = "#000000",
            cardHex = "#000000",
        )
        val scene = ImageComposeScene(width = size, height = size, density = Density(1f)) {
            NuvioTheme(
                appTheme = AppTheme.CUSTOM,
                customThemePalette = palette,
                accentGradientDirection = direction,
            ) {
                // White stands in for an icon's tinted glyph: if the mask works, none of this
                // white survives.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .accentGradientMask()
                        .background(Color.White),
                )
            }
        }
        val image = scene.render()
        val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
        require(image.readPixels(bitmap, 0, 0))
        scene.close()
        return bitmap
    }

    private fun Bitmap.red(x: Int, y: Int) = (getColor(x, y) shr 16) and 0xFF
    private fun Bitmap.blue(x: Int, y: Int) = getColor(x, y) and 0xFF

    @Test
    fun `horizontal runs accent to accent-end across the width`() {
        val bitmap = render(AccentGradientDirection.Horizontal, gradient = true)
        val mid = size / 2

        assertTrue(bitmap.red(1, mid) > 200, "left edge is not the accent: red=${bitmap.red(1, mid)}")
        assertTrue(bitmap.blue(1, mid) < 60, "left edge has too much of the end colour")
        assertTrue(
            bitmap.blue(size - 2, mid) > 200,
            "right edge is not the gradient end: blue=${bitmap.blue(size - 2, mid)}",
        )
        assertTrue(bitmap.red(size - 2, mid) < 60, "right edge still carries the start colour")
    }

    @Test
    fun `vertical runs down the height instead of across`() {
        val bitmap = render(AccentGradientDirection.Vertical, gradient = true)
        val mid = size / 2

        assertTrue(bitmap.red(mid, 1) > 200, "top is not the accent")
        assertTrue(bitmap.blue(mid, size - 2) > 200, "bottom is not the gradient end")
        // The axis really moved: a horizontal gradient would vary along this line, not hold steady.
        assertTrue(
            kotlin.math.abs(bitmap.red(1, mid) - bitmap.red(size - 2, mid)) < 12,
            "colour still varies horizontally, so the direction was ignored",
        )
    }

    @Test
    fun `diagonal puts the accent in the top-left corner and the end in the bottom-right`() {
        val bitmap = render(AccentGradientDirection.Diagonal, gradient = true)

        assertTrue(bitmap.red(1, 1) > 200, "top-left is not the accent")
        assertTrue(bitmap.blue(size - 2, size - 2) > 200, "bottom-right is not the gradient end")
    }

    @Test
    fun `diagonal reverse starts from the bottom-left corner`() {
        val bitmap = render(AccentGradientDirection.DiagonalReverse, gradient = true)

        assertTrue(bitmap.red(1, size - 2) > 200, "bottom-left is not the accent")
        assertTrue(bitmap.blue(size - 2, 1) > 200, "top-right is not the gradient end")
    }

    /**
     * The ramp is spaced in Oklab rather than straight down the sRGB channels, because a two-stop
     * lerp between accents of very different brightness arrives early and then only brightens: on a
     * real theme (`#534582` to `#00FFFF`) it had covered 79% of the hue arc by 60% of the width,
     * so the start colour all but vanished. Guarding the midpoint is enough to catch a revert to a
     * plain two-stop brush, which is the way this would regress.
     */
    @Test
    fun `the ramp is spaced perceptually, not down the raw sRGB channels`() {
        val start = Color(0xFFFF0000)
        val end = Color(0xFF0000FF)
        val bitmap = render(AccentGradientDirection.Horizontal, gradient = true)
        val mid = size / 2
        val rendered = Triple(bitmap.red(mid, mid), (bitmap.getColor(mid, mid) shr 8) and 0xFF, bitmap.blue(mid, mid))

        // What the GPU actually draws at the midpoint: an sRGB lerp between the two middle stops.
        val stops = accentGradientStops(start, end)
        val position = 0.5f * (stops.size - 1)
        val low = stops[position.toInt()]
        val high = stops[position.toInt() + 1]
        val fraction = position - position.toInt()
        fun channel(a: Float, b: Float) = ((a + (b - a) * fraction) * 255f).toInt()
        val perceptual = Triple(
            channel(low.red, high.red),
            channel(low.green, high.green),
            channel(low.blue, high.blue),
        )
        // What a plain two-stop brush would have drawn there.
        val channelWise = Triple(128, 0, 128)

        fun distance(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>) = kotlin.math.sqrt(
            ((a.first - b.first).toDouble().pow(2) +
                (a.second - b.second).toDouble().pow(2) +
                (a.third - b.third).toDouble().pow(2)),
        )

        assertTrue(
            distance(rendered, perceptual) < distance(rendered, channelWise),
            "midpoint $rendered is nearer the channel-wise $channelWise than the perceptual $perceptual",
        )
    }

    /**
     * The no-op path matters as much as the masking one: every built-in palette paints accents flat,
     * and they must not pay for an offscreen layer or have their content repainted.
     */
    @Test
    fun `a flat accent leaves the content untouched`() {
        val bitmap = render(AccentGradientDirection.Horizontal, gradient = false)
        val mid = size / 2

        assertEquals(0xFF, bitmap.red(1, mid), "content was repainted despite there being no gradient")
        assertEquals(0xFF, bitmap.blue(1, mid), "content was repainted despite there being no gradient")
    }
}
