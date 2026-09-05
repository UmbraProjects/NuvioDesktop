package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PosterHighlightSweepRenderTest {
    /**
     * A 256x8 card — wider than it is tall, so the band travels the horizontal axis and one pixel
     * row samples the whole of it. At progress 0.5 the band spans the card exactly, putting its
     * plateau over the middle 10% and its transparent ends on the two edges.
     */
    @Test
    fun `sweep gains the artwork under the band and leaves the ends alone`() {
        val plain = render(progress = null)
        val swept = render(progress = 0.5f)

        val expected = (plain[128] * (1f + Strength)).roundToInt().coerceAtMost(255)
        assertTrue(
            abs(swept[128] - expected) <= 3,
            "band centre is not a ${1f + Strength}x gain: ${swept[128]} against $expected",
        )
        // The gradient's own ends are transparent, so the card's edges must come out untouched --
        // otherwise the band has a hard boundary and would arrive as a moving block of light.
        listOf(0, 255).forEach { x ->
            assertTrue(abs(swept[x] - plain[x]) <= 1, "band leaked to x=$x: ${swept[x]} vs ${plain[x]}")
        }
    }

    /** The same property [PosterHighlightMode.Shine] is held to, checked while the band is over it. */
    @Test
    fun `sweep leaves black at black`() {
        val swept = render(progress = 0.5f, colors = listOf(Color.Black, Color.Black))
        assertTrue(swept[128] <= 1, "sweep lifted black to ${swept[128]}")
    }

    /** Both ends of the pass park the band off the card, so neither may touch a pixel. */
    @Test
    fun `sweep is inert at rest`() {
        val plain = render(progress = null)
        listOf(0f, 1f).forEach { progress ->
            val rendered = render(progress = progress)
            val worst = (0 until 256).maxOf { abs(rendered[it] - plain[it]) }
            assertTrue(worst <= 1, "progress $progress altered the card by $worst")
        }
    }

    private fun render(
        progress: Float?,
        colors: List<Color> = listOf(Color.Black, Color.White),
    ): IntArray {
        val scene = ImageComposeScene(width = 256, height = 8, density = Density(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (progress == null) {
                            Modifier
                        } else {
                            Modifier.drawWithContent {
                                drawNuvioPosterSweep(
                                    progress = progress,
                                    strength = Strength,
                                    cornerRadius = 0.dp,
                                )
                            }
                        },
                    )
                    .background(Brush.horizontalGradient(colors)),
            )
        }
        val image = scene.render()
        val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
        require(image.readPixels(bitmap, 0, 0))
        val row = IntArray(256) { x -> bitmap.getColor(x, 4) and 0xFF }
        scene.close()
        return row
    }

    private companion object {
        const val Strength = 0.35f
    }
}
