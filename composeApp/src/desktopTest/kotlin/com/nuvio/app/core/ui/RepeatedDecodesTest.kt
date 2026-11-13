package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RepeatedDecodesTest {
    private val second = 1_000_000_000L

    @Test
    fun reportsOnceWhenOnePictureIsDecodedFourTimesInTheWindow() {
        val url = "https://example.test/a-${System.nanoTime()}.jpg"
        assertNull(RepeatedDecodes.note(url, 848, 477, now = 0))
        assertNull(RepeatedDecodes.note(url, 841, 473, now = second))
        assertNull(RepeatedDecodes.note(url, 839, 472, now = 2 * second))
        val line = assertNotNull(RepeatedDecodes.note(url, 834, 469, now = 3 * second))
        assertEquals(true, line.startsWith("REDECODE 4x in 3000ms"))
        assertEquals(true, line.contains("sizes=848x477,841x473,839x472,834x469"))
        assertNull(RepeatedDecodes.note(url, 821, 462, now = 4 * second))
    }

    @Test
    fun decodesSpreadOverMoreThanTheWindowAreNotReported() {
        val url = "https://example.test/b-${System.nanoTime()}.jpg"
        repeat(8) { i -> assertNull(RepeatedDecodes.note(url, 800, 450, now = i * 11 * second)) }
    }
}
