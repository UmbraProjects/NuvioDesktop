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
import org.jetbrains.skia.Bitmap
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PosterHighlightShineRenderTest {
    /**
     * A 256px horizontal black-to-white ramp, drawn once plain and once through the shine, so the
     * transfer curve can be read off directly instead of assumed.
     */
    @Test
    fun `shine lifts along its tone curve without a white wash or clipping`() {
        val plain = renderRamp(shine = false)
        val shone = renderRamp(shine = true)

        var worst = 0
        var worstAt = 0
        for (x in intArrayOf(0, 8, 32, 64, 96, 128, 160, 192, 224, 255)) {
            val input = plain[x]
            val expected = (shineToneCurve(input / 255f, PosterHighlightShineGain) * 255f).roundToInt()
            val deviation = kotlin.math.abs(shone[x] - expected)
            if (deviation > worst) {
                worst = deviation
                worstAt = input
            }
        }

        assertTrue(worst <= 3, "shine is off its tone curve by $worst at input $worstAt")
        // Black staying black is the whole difference from painting a white scrim over the card.
        assertTrue(shone[0] <= 1, "shine lifted black to ${shone[0]}")
        // ...and near-white stays distinct from white: the old flat gain clipped everything above 80%.
        assertTrue(shone[224] < shone[255], "shine clipped near-white (${shone[224]}) into white")
        assertTrue(shone[128] > plain[128] + 18, "shine barely lifted the midtones: ${plain[128]} to ${shone[128]}")
        // Light artwork keeps its contrast: the top stays nearly as steep as it was (no milky flattening).
        assertTrue(shone[255] - shone[224] >= (plain[255] - plain[224]) * 0.85, "shine flattened the highlights")
        assertTrue(shone[224] - plain[224] <= 4, "shine lifted near-white by ${shone[224] - plain[224]}")
    }

    private fun renderRamp(shine: Boolean): IntArray {
        val scene = ImageComposeScene(width = 256, height = 8, density = Density(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (shine) {
                            Modifier.drawWithContent { drawNuvioPosterShine(strength = PosterHighlightShineGain) }
                        } else {
                            Modifier
                        },
                    )
                    .background(Brush.horizontalGradient(listOf(Color.Black, Color.White))),
            )
        }
        val image = scene.render()
        val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
        require(image.readPixels(bitmap, 0, 0))
        val row = IntArray(256) { x -> bitmap.getColor(x, 4) and 0xFF }
        scene.close()
        return row
    }
}
