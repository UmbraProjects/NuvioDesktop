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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

/**
 * The ambient wash used to be two stacked full-screen layers — an accent gradient, then a flat dim
 * over the whole window. On a 4K display each is 8.3 million blended pixels every frame, and
 * measurement put Adaptive Ambient at 5.71 ms of frame CPU against 2.74 ms for Adaptive, which
 * differ only by this wash. [ambientWashStops] folds the two into one.
 *
 * The fold is exact at each stop but not between them: the original interpolates the gradient and
 * then composites the dim, while the folded form interpolates an already-composited result. That
 * error is a real visual difference, so it is measured here rather than argued about.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HeroAmbientWashRenderTest {

    private val width = 48
    private val height = 256

    private val accent = Color(0xFF3D7BD9)
    private val background = Color(0xFF0D0D0D)

    /** Stands in for the blurred backdrop underneath: a bright ramp, so errors have room to show. */
    private fun Modifier.base(): Modifier = background(
        Brush.verticalGradient(listOf(Color(0xFFF0E0C0), Color(0xFF203040))),
    )

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit): Bitmap {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), content = content)
        val image = scene.render()
        val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
        image.readPixels(bitmap)
        scene.close()
        return bitmap
    }

    private fun twoLayerReference(): Bitmap = render {
        Box(modifier = Modifier.fillMaxSize().base()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to accent.copy(alpha = 0.46f),
                                0.42f to accent.copy(alpha = 0.32f),
                                1f to background.copy(alpha = 0.72f),
                            ),
                        ),
                    ),
            )
            Box(modifier = Modifier.fillMaxSize().background(background.copy(alpha = 0.38f)))
        }
    }

    private fun foldedActual(): Bitmap = render {
        Box(modifier = Modifier.fillMaxSize().base()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = ambientWashStops(accent = accent, background = background),
                        ),
                    ),
            )
        }
    }

    @Test
    fun `folding the dim into the gradient keeps the same picture`() {
        val reference = twoLayerReference()
        val actual = foldedActual()

        var worst = 0
        var worstY = -1
        var total = 0L
        var samples = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = reference.getColor(x, y)
                val b = actual.getColor(x, y)
                val delta = maxOf(
                    abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
                    abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
                    abs((a and 0xFF) - (b and 0xFF)),
                )
                if (delta > worst) {
                    worst = delta
                    worstY = y
                }
                total += delta
                samples++
            }
        }
        val mean = total.toDouble() / samples
        java.io.File("build/ambient-wash-delta.txt").apply {
            parentFile?.mkdirs()
            writeText("worst=" + worst + " atY=" + worstY + " mean=" + mean)
        }
        // Measured: worst 2/255, mean 0.93/255 — invisible on a blurred, dimmed backdrop. The
        // thresholds sit just above that so a regression in the fold shows up rather than sliding
        // under a loose bound.
        assertTrue(worst <= 3, "worst channel delta $worst at y=$worstY (mean $mean)")
        assertTrue(mean <= 1.2, "mean channel delta $mean (worst $worst at y=$worstY)")
    }

    @Test
    fun `the fold is exact at the gradient stops`() {
        val reference = twoLayerReference()
        val actual = foldedActual()
        // y=0 and y=height-1 are the first and last stops, where the algebra is exact.
        for (y in listOf(0, height - 1)) {
            val a = reference.getColor(width / 2, y)
            val b = actual.getColor(width / 2, y)
            for (shift in listOf(16, 8, 0)) {
                val delta = abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                assertTrue(delta <= 1, "stop at y=$y differs by $delta on channel shift=$shift")
            }
        }
    }
}
