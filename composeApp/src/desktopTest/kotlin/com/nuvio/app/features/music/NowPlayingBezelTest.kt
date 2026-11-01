package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NowPlayingBezelTest {
    private val spec = BezelSpec(innerWidth = 400, innerHeight = 225, rim = 9, halo = 8, lip = 3, radius = 16)
    private val pixels = renderBezel(spec)

    private fun alphaAt(x: Int, y: Int): Int = pixels[y * spec.width + x] ushr 24

    @Test
    fun theImageIsTheVideoPlusTheMarginOnEverySide() {
        assertEquals(400 + 2 * 17, spec.width)
        assertEquals(225 + 2 * 17, spec.height)
        assertEquals(spec.width * spec.height, pixels.size)
    }

    @Test
    fun thePictureShowsThroughTheOpening() {
        assertEquals(0, alphaAt(spec.width / 2, spec.height / 2))
        // Just inside the opening's straight edge, clear of the corner arc and the dark edge line.
        assertEquals(0, alphaAt(spec.width / 2, spec.margin + spec.lip + 6))
    }

    @Test
    fun theSquareCornersOfTheVideoWindowAreCovered() {
        // The window's own corner pixel, and one a little inside it, lie outside the opening's rounded corner.
        assertTrue(alphaAt(spec.margin + 1, spec.margin + 1) > 200)
        assertTrue(alphaAt(spec.margin + 3, spec.margin + 3) > 200)
    }

    @Test
    fun theRingReachesOutsideTheVideoAndTheShadowFadesOut() {
        assertTrue(alphaAt(spec.width / 2, spec.margin - spec.rim / 2) > 200)
        assertEquals(0, alphaAt(0, 0))
        assertTrue(alphaAt(spec.width / 2, spec.halo / 2) < alphaAt(spec.width / 2, spec.margin - 1))
    }
}
