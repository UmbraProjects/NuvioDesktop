package com.nuvio.app.core.ui

import coil3.decode.DecodeUtils
import coil3.size.Scale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hero used to send no size at all, so backdrops decoded at full source resolution — a 3840x2160
 * backdrop is 31.6 MB, against a 327 MB memory cache.
 *
 * These assert the *outcome* (how much memory a backdrop actually occupies) rather than the
 * arithmetic, and they do it through Coil's own `computeSizeMultiplier` so they cannot drift from
 * what the pipeline really decides.
 */
class HeroSourceSizeTest {

    /** What Coil will decode to, given a request size and a source. Mirrors HighQualityBitmapDecoder. */
    private fun decodedBytes(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Long {
        val multiplier = DecodeUtils.computeSizeMultiplier(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = reqWidth,
            dstHeight = reqHeight,
            // ContentScale.Crop, which is what every hero uses.
            scale = Scale.FILL,
        ).coerceAtMost(1.0)
        val width = (srcWidth * multiplier).toInt()
        val height = (srcHeight * multiplier).toInt()
        return width.toLong() * height.toLong() * 4
    }

    /** Physical display pixels, which is what the hero request is sized from. */
    private fun heroRequestFor(displayWidth: Int, displayHeight: Int): Pair<Int, Int> =
        quantizeHeroSourceAxis(displayWidth) to quantizeHeroSourceAxis(displayHeight)

    private fun fullBytes(width: Int, height: Int) = width.toLong() * height.toLong() * 4

    @Test
    fun `a 4K backdrop on a 1440p display costs well under full resolution`() {
        val (w, h) = heroRequestFor(2560, 1440)
        val decoded = decodedBytes(3840, 2160, w, h)
        val full = fullBytes(3840, 2160)
        assertTrue(decoded < full * 0.7, "decoded ${decoded / 1048576} MB of ${full / 1048576} MB full")
    }

    @Test
    fun `a 4K backdrop on a 1080p display costs less than half`() {
        val (w, h) = heroRequestFor(1920, 1080)
        val decoded = decodedBytes(3840, 2160, w, h)
        val full = fullBytes(3840, 2160)
        assertTrue(decoded < full * 0.5, "decoded ${decoded / 1048576} MB of ${full / 1048576} MB full")
    }

    @Test
    fun `a source smaller than the display is never upscaled`() {
        val (w, h) = heroRequestFor(2560, 1440)
        assertEquals(fullBytes(1280, 720), decodedBytes(1280, 720, w, h))
    }

    @Test
    fun `a 4K display still gets its backdrop at full resolution`() {
        // Nothing to save here - the source is already 1:1 with the display, so this must be a
        // no-op rather than a reduction. Getting it wrong is how a 4K user ends up with a soft hero,
        // and it is exactly what sizing from `LocalWindowInfo.containerSize` would have done: at
        // 200% scaling that reports 1920x1080, which would have asked for 2304 px to cover 3840.
        val (w, h) = heroRequestFor(3840, 2160)
        assertEquals(fullBytes(3840, 2160), decodedBytes(3840, 2160, w, h))
    }

    @Test
    fun `every common display gets a request at or above its own resolution`() {
        // The invariant that keeps the hero crisp everywhere: never ask for fewer pixels than the
        // display draws.
        val displays = listOf(1366 to 768, 1920 to 1080, 2560 to 1440, 3440 to 1440, 3840 to 2160)
        for ((width, height) in displays) {
            assertTrue(quantizeHeroSourceAxis(width) >= width, "width $width -> ${quantizeHeroSourceAxis(width)}")
            assertTrue(quantizeHeroSourceAxis(height) >= height, "height $height -> ${quantizeHeroSourceAxis(height)}")
        }
    }

    @Test
    fun `the request stays at or above the display, so the hero is never soft at rest`() {
        for (windowWidth in listOf(1280, 1600, 1920, 2560, 3440, 3840)) {
            assertTrue(
                quantizeHeroSourceAxis(windowWidth) >= windowWidth,
                "asked for ${quantizeHeroSourceAxis(windowWidth)} px for a $windowWidth px window",
            )
        }
    }

    @Test
    fun `a square request size would reduce nothing, which is why both axes are sent`() {
        // Scale.FILL takes the larger of the two ratios, so a square size lets the short axis decide.
        // This documents the trap rather than testing production code.
        val square = quantizeHeroSourceAxis(2560)
        assertEquals(fullBytes(3840, 2160), decodedBytes(3840, 2160, square, square))
    }

    @Test
    fun `nearby display resolutions collapse to the same request`() {
        // The step is applied after the headroom, so in *window* pixels it is ~128 / 1.15 = 111 -
        // an individual pair either side of a boundary will differ. What matters is that a whole
        // drag produces a handful of distinct requests rather than one per pixel.
        val distinct = (1280..2560).map(::quantizeHeroSourceAxis).distinct()
        assertTrue(distinct.size < 16, "a 1280 px range produced ${distinct.size} distinct requests")
    }

    @Test
    fun `a larger display never asks for a smaller source`() {
        var previous = 0
        for (windowWidth in 640..3840 step 7) {
            val current = quantizeHeroSourceAxis(windowWidth)
            assertTrue(current >= previous, "$windowWidth px asked for $current after $previous")
            previous = current
        }
    }
}
