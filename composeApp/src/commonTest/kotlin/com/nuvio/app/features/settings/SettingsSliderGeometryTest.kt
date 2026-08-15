package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both settings sliders draw their track inset by the thumb radius, so the pointer has to be mapped
 * over that same span. Mapping it over the full width made the drawn endpoints unreachable and the
 * thumb drift away from the cursor near either end.
 */
class SettingsSliderGeometryTest {

    private val width = 200
    private val thumbRadius = 9f

    // Snapping divides and re-multiplies, so a stop lands a few ULPs off its nominal value. The
    // assertions are about which stop was chosen, not about exact float reconstruction.
    private val tolerance = 1e-3f

    private fun valueAt(x: Float, steps: Int = 0, range: ClosedFloatingPointRange<Float> = 0f..100f) =
        settingsSliderValueForX(
            x = x,
            widthPx = width,
            thumbRadiusPx = thumbRadius,
            valueRange = range,
            steps = steps,
        )

    @Test
    fun `the rendered endpoints map to the range endpoints`() {
        assertEquals(0f, valueAt(thumbRadius), tolerance)
        assertEquals(100f, valueAt(width - thumbRadius), tolerance)
    }

    @Test
    fun `input beyond the track is clamped rather than wrapped`() {
        assertEquals(0f, valueAt(-40f), tolerance)
        assertEquals(100f, valueAt(width + 40f), tolerance)
    }

    @Test
    fun `the midpoint of the drawn track is the middle of the range`() {
        assertEquals(50f, valueAt(width / 2f), tolerance)
    }

    @Test
    fun `steps round to the nearest rather than flooring`() {
        // 4 steps over 0..100 => stops at 0/20/40/60/80/100. A point just past the 60 stop must
        // stay on 60, and one just short of 80 must round up to it — toInt() gave 60 for both.
        val trackStart = thumbRadius
        val trackSpan = width - 2 * thumbRadius
        fun atFraction(fraction: Float) = valueAt(trackStart + trackSpan * fraction, steps = 4)

        assertEquals(60f, atFraction(0.61f), tolerance)
        assertEquals(80f, atFraction(0.79f), tolerance)
        assertEquals(100f, atFraction(1f), tolerance)
        assertEquals(0f, atFraction(0f), tolerance)
    }

    @Test
    fun `a zero-width track does not divide by zero`() {
        val value = settingsSliderValueForX(
            x = 5f,
            widthPx = 1,
            thumbRadiusPx = thumbRadius,
            valueRange = 0f..100f,
            steps = 0,
        )

        assertTrue(value in 0f..100f)
    }

    @Test
    fun `a non-zero-based range maps across its own span`() {
        assertEquals(0.5f, valueAt(thumbRadius, range = 0.5f..2f), tolerance)
        assertEquals(2f, valueAt(width - thumbRadius, range = 0.5f..2f), tolerance)
        assertEquals(1.25f, valueAt(width / 2f, range = 0.5f..2f), tolerance)
    }
}
