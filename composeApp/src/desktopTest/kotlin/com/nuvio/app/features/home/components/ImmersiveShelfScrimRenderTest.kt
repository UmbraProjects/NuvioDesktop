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
 * Whether TV Mode's shelf region still shows the backdrop.
 *
 * Two gradients land here and multiply together — the hero's bottom fade and the shelf container's
 * background — so neither one's numbers mean anything on their own. Reasoning about the product on
 * paper is what produces a "full backdrop" that is black anyway, or a legible-looking one that is
 * actually a washed-out mess, so this composites them exactly as the screens do and reads the
 * pixels back.
 *
 * The artwork stands in as saturated green: any surviving green channel is backdrop the user can
 * see, and the amount of it is the whole question.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ImmersiveShelfScrimRenderTest {

    private val width = 8
    private val height = 200
    private val artwork = Color(0f, 1f, 0f)
    private val background = Color.Black

    /** Composites the artwork, the hero's bottom fade and the shelf scrim in screen order. */
    private fun render(fullBackdrop: Boolean): Bitmap {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(artwork)) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            colorStops = immersiveHeroBottomFadeStops(background, fullBackdrop),
                        ),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            colorStops = immersiveShelfScrimStops(background, fullBackdrop),
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

    /** Surviving artwork at [row], 0f (scrimmed to black) to 1f (untouched). */
    private fun artworkVisibility(bitmap: Bitmap, row: Int): Float =
        (bitmap.getColor(width / 2, row) shr 8 and 0xFF) / 255f

    // Measured profile, top of the fade region to the bottom edge:
    //   row    0    40    80   120   160   199
    //   black 1.00 0.73  0.41  0.07  0.01  0.00   <- opaque band from ~60% down
    //   full  1.00 0.88  0.69  0.46  0.31  0.19   <- artwork readable all the way down

    @Test
    fun blackShelfEndsOpaqueAtTheBottom() {
        val bitmap = render(fullBackdrop = false)
        // The default: the shelf is a black band, which is exactly what the option exists to change.
        assertTrue(
            artworkVisibility(bitmap, height - 1) < 0.02f,
            "black shelf should reach opaque, saw ${artworkVisibility(bitmap, height - 1)}",
        )
    }

    @Test
    fun fullBackdropKeepsTheArtworkVisibleAtTheBottomEdge() {
        val bitmap = render(fullBackdrop = true)
        val bottom = artworkVisibility(bitmap, height - 1)
        // Enough to read as artwork rather than as a dark band...
        assertTrue(bottom > 0.12f, "full backdrop bottom edge too dark, saw $bottom")
        // ...and dark enough that white shelf labels and poster edges still separate from it. The
        // game library's Full backdrop lands in the same place at 0.82 alpha.
        assertTrue(bottom < 0.35f, "full backdrop bottom edge too bright, saw $bottom")
    }

    @Test
    fun fullBackdropIsBrighterThanBlackShelfEverywhereInTheRegion() {
        val full = render(fullBackdrop = true)
        val black = render(fullBackdrop = false)
        // Monotonic: no row anywhere in the region may come out darker than the layout that ends in
        // solid black, or the "full" option would be dimming part of the picture it claims to show.
        for (row in 0 until height) {
            assertTrue(
                artworkVisibility(full, row) >= artworkVisibility(black, row) - 0.01f,
                "row $row darker under full backdrop: " +
                    "${artworkVisibility(full, row)} vs ${artworkVisibility(black, row)}",
            )
        }
    }

    @Test
    fun bothLayoutsDarkenMonotonicallyTowardsTheShelf() {
        // A gradient that brightens again partway down reads as a band edge, which is the artefact
        // the stacked-gradient tuning is most likely to introduce.
        for (fullBackdrop in listOf(true, false)) {
            val bitmap = render(fullBackdrop)
            var previous = artworkVisibility(bitmap, 0)
            for (row in 1 until height) {
                val current = artworkVisibility(bitmap, row)
                assertTrue(
                    current <= previous + 0.01f,
                    "fullBackdrop=$fullBackdrop brightens at row $row: $previous -> $current",
                )
                previous = current
            }
        }
    }
}
