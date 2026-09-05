package com.nuvio.app.features.home.components

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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the hero's white text survives a full-width backdrop.
 *
 * The first attempt at full backdrop reused the shelf-bounded layout's fade, which reaches zero by
 * 30% of the window. That is fine when it is only blending the backdrop's own left edge into
 * background, and useless when the text is sitting on the picture: the title, synopsis and cast
 * panel all extend past that point and landed on unscrimmed artwork. Against a bright backdrop they
 * were unreadable.
 *
 * The backdrop here is pure white, which is the worst case and close to the real one that exposed
 * this (a near-white face filling the frame). Every measurement is of surviving artwork luminance,
 * so it doubles as the contrast figure for white text drawn on top.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ImmersiveHeroSideScrimRenderTest {

    private val width = 400
    private val height = 8
    private val background = Color.Black

    private fun render(): Bitmap {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            colorStops = immersiveHeroSideScrimStops(background),
                        ),
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

    /** Surviving artwork brightness at [fraction] across the window, 0f (black) to 1f (white). */
    private fun brightnessAt(bitmap: Bitmap, fraction: Float): Float {
        val x = (fraction * (width - 1)).toInt().coerceIn(0, width - 1)
        return (bitmap.getColor(x, height / 2) shr 8 and 0xFF) / 255f
    }

    // Measured against pure white, left edge to right edge:
    //   across      0%   8%  16%  24%  30%  33%  45%  60%  70%+
    //   brightness 0.04 0.12 0.20 0.29 0.38 0.42 0.61 0.87 1.00
    // The synopsis is the longest line and ends near 33%, where white-on-0.42 is about 5.3:1 —
    // above the 4.5:1 normal-text bar, and against a white backdrop, which is the worst case there
    // is. The old fade was already back to 1.00 by 30%.

    @Test
    fun theTextColumnStaysDarkEnoughForWhiteText() {
        val bitmap = render()
        // The hero's metadata column — logo, genres, rating chips, synopsis — runs from the left
        // edge to roughly a third of the window, and the cast panel sits inside the same band.
        // White-on-background needs the artwork held well below mid-grey across all of it.
        for (fraction in listOf(0f, 0.08f, 0.16f, 0.24f, 0.33f)) {
            val brightness = brightnessAt(bitmap, fraction)
            assertTrue(
                brightness < 0.45f,
                "white text unreadable at ${fraction * 100}% across: artwork at $brightness",
            )
        }
        // Darkest where the longest lines start, so the synopsis has the most contrast of all.
        assertTrue(brightnessAt(bitmap, 0f) < 0.08f, "left edge should be near-black")
    }

    @Test
    fun theRightHandSideIsLeftAsSupplied() {
        val bitmap = render()
        // Nothing is written over the right of the hero, and dimming it there would defeat the
        // point of the option. The ramp reaches zero at 70% and must stay there.
        for (fraction in listOf(0.75f, 0.85f, 1f)) {
            val brightness = brightnessAt(bitmap, fraction)
            assertTrue(
                brightness > 0.98f,
                "artwork dimmed at ${fraction * 100}% across: $brightness",
            )
        }
    }

    @Test
    fun theRampNeverBrightensOnItsWayOut() {
        // A non-monotonic ramp shows up as a visible vertical seam down the middle of the hero.
        val bitmap = render()
        var previous = brightnessAt(bitmap, 0f)
        for (step in 1..100) {
            val current = brightnessAt(bitmap, step / 100f)
            assertTrue(
                current >= previous - 0.01f,
                "scrim darkens again at ${step}% across: $previous -> $current",
            )
            previous = current
        }
    }

    @Test
    fun theRampIsFarWiderThanTheShelfBoundedFade() {
        val bitmap = render()
        // The regression this test exists for: the old fade was fully transparent by 30%, which is
        // still inside the synopsis. Whatever the exact numbers, the scrim must still be doing real
        // work there.
        assertTrue(
            brightnessAt(bitmap, 0.30f) < 0.6f,
            "scrim has already given up at 30% across: ${brightnessAt(bitmap, 0.30f)}",
        )
    }
}
