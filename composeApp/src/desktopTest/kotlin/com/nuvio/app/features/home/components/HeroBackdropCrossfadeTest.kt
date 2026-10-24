package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hero backdrop fade, measured in rendered pixels while the two things that used to break it
 * are happening: another fade still running, and artwork that has not arrived yet.
 *
 * The first is what `Crossfade` could not do. It drives every layer off one Transition whose clock
 * keeps running across a target change, so a fade that starts while another is in flight inherits
 * the elapsed time and only gets what is left of the first one's budget: measured here at a 320ms
 * fade reaching halfway two frames early and completing six frames early, with a 71-level jump
 * partway through.
 *
 * The second is the memory cache. A backdrop Coil has not seen draws nothing at all while it
 * loads, so a fade started on arrival of the *url* spends itself on an empty layer and the picture
 * lands at whatever alpha the clock reached — a hard cut when the download outlasts the fade. Which
 * one you got depended on whether the item had been on screen before, which is what made the hero
 * look inconsistent rather than simply wrong.
 *
 * Everything is asserted against an undisturbed fade of the same length rather than against fixed
 * frame counts, so these say the thing that actually matters without pinning the easing curve.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HeroBackdropCrossfadeTest {

    private val width = 32
    private val height = 32
    private val durationMillis = 320
    private val frameMillis = 16L
    private val framesPerFade = durationMillis / frameMillis.toInt()

    @Test
    fun `an interrupted fade reaches halfway when an undisturbed one does`() {
        val undisturbed = frameReaching(sample(interrupt = false), 128)
        val interrupted = frameReaching(sample(interrupt = true), 128)

        assertTrue(
            undisturbed > 2,
            "the reference fade was halfway after $undisturbed frames; it is not measuring a fade",
        )
        assertTrue(
            abs(interrupted - undisturbed) <= 1,
            "a fade started over an unfinished one was halfway after $interrupted frames against " +
                "$undisturbed undisturbed; it is being cut short by the fade it interrupted",
        )
    }

    @Test
    fun `an interrupted fade finishes when an undisturbed one does`() {
        val undisturbed = frameReaching(sample(interrupt = false), 250)
        val interrupted = frameReaching(sample(interrupt = true), 250)

        assertTrue(
            abs(interrupted - undisturbed) <= 2,
            "a fade started over an unfinished one completed in $interrupted frames against " +
                "$undisturbed undisturbed",
        )
    }

    @Test
    fun `an interrupted fade never jumps`() {
        assertNoJump(sample(interrupt = true))
    }

    /** The download outlasts the fade three times over, which is the hard-cut case. */
    @Test
    fun `a slow backdrop still fades once it arrives`() {
        val samples = sample(interrupt = false, framesToLoad = 3 * framesPerFade)
        assertNoJump(samples)
        assertTrue(
            samples.last() >= 250,
            "the backdrop never finished fading in: ${samples.toList()}",
        )
    }

    /** The same, with a second backdrop already mid-fade when the slow one is asked for. */
    @Test
    fun `a slow backdrop still fades when it interrupts another`() {
        assertNoJump(sample(interrupt = true, framesToLoad = 3 * framesPerFade))
    }

    private fun assertNoJump(samples: IntArray) {
        var worst = 0
        for (i in 1 until samples.size) {
            worst = maxOf(worst, samples[i] - samples[i - 1])
        }
        // The steepest frame of this curve moves about 34 levels. Half again as much is no longer a
        // curve, it is the backdrop appearing rather than fading.
        assertTrue(worst <= 45, "the backdrop moved $worst levels in one frame: ${samples.toList()}")
    }

    /**
     * Renders the fade to white and returns its brightness per frame from the moment the white
     * backdrop is requested.
     *
     * Every backdrop before the last one is black, so whatever the stack underneath is doing it is
     * black, and the sampled pixel reads the white layer's own alpha.
     *
     * [framesToLoad] stands in for Coil: a layer draws nothing and reports nothing until that many
     * frames after it is first composed, the way an uncached backdrop behaves.
     */
    private fun sample(interrupt: Boolean, framesToLoad: Int = 0): IntArray {
        val model = mutableStateOf("a")
        val clock = mutableStateOf(0L)
        val scene = ImageComposeScene(width, height, Density(1f)) {
            HeroBackdropCrossfade(model = model.value, durationMillis = durationMillis) { current, onResolved ->
                val key = current ?: return@HeroBackdropCrossfade
                val arrivesAt = remember(key) { clock.value + framesToLoad }
                if (clock.value >= arrivesAt) {
                    LaunchedEffect(key) { onResolved(true) }
                    val color = if (key == "white") Color.White else Color.Black
                    Box(Modifier.fillMaxSize().background(color))
                }
            }
        }
        var frame = 0L
        fun render(): Int {
            clock.value = frame
            val image = scene.render(frame++ * frameMillis * 1_000_000L)
            val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
            require(image.readPixels(bitmap, 0, 0))
            return (bitmap.getColor(width / 2, height / 2) shr 8) and 0xFF
        }

        // Settle on the first backdrop, which has nothing to fade over.
        repeat(3 + framesToLoad) { render() }
        if (interrupt) {
            // A second black backdrop, left mid-fade when the white one is asked for.
            model.value = "b"
            repeat(framesToLoad + framesPerFade / 2) { render() }
        }
        model.value = "white"
        val samples = IntArray(2 * framesPerFade + 2 * framesToLoad) { render() }
        scene.close()
        return samples
    }

    /**
     * A backdrop whose artwork 404s draws nothing, so raising its own layer would change nothing at
     * all. The fade has to become the one underneath going out, or the hero keeps the previous
     * item's picture under the new item's title.
     */
    @Test
    fun `a backdrop with no artwork fades the previous one out`() {
        val model = mutableStateOf("white")
        val scene = ImageComposeScene(width, height, Density(1f)) {
            // The hero's own background. Without an opaque ground the readback is unpremultiplied,
            // so a layer fading out over a transparent canvas keeps reading as full white.
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HeroBackdropCrossfade(model = model.value, durationMillis = durationMillis) { current, onResolved ->
                    if (current == "white") {
                        LaunchedEffect(current) { onResolved(true) }
                        Box(Modifier.fillMaxSize().background(Color.White))
                    } else {
                        // Resolved, with nothing to show for it.
                        LaunchedEffect(current) { onResolved(false) }
                    }
                }
            }
        }
        var frame = 0L
        fun render(): Int {
            val image = scene.render(frame++ * frameMillis * 1_000_000L)
            val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
            require(image.readPixels(bitmap, 0, 0))
            return (bitmap.getColor(width / 2, height / 2) shr 8) and 0xFF
        }
        repeat(3) { render() }
        model.value = "broken"
        val samples = IntArray(2 * framesPerFade) { render() }
        scene.close()

        var worst = 0
        for (i in 1 until samples.size) worst = maxOf(worst, samples[i - 1] - samples[i])
        assertTrue(
            samples.last() <= 5,
            "the hero kept the previous backdrop after the new one failed: ${samples.toList()}",
        )
        assertTrue(worst <= 45, "the previous backdrop vanished in a $worst-level step: ${samples.toList()}")
    }

    private fun frameReaching(samples: IntArray, level: Int): Int {
        val index = samples.indexOfFirst { it >= level }
        assertTrue(index >= 0, "the backdrop never reached $level: ${samples.toList()}")
        return index
    }
}
