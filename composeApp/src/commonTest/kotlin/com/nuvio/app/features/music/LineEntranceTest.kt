package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LineEntranceTest {
    @Test
    fun neighbouringLinesNeverEnterTheSameWay() {
        for (i in 0..40) assertNotEquals(lineEntranceFor(i), lineEntranceFor(i + 1))
    }

    @Test
    fun aLineAlwaysEntersTheSameWay() {
        assertEquals(lineEntranceFor(7), lineEntranceFor(7))
        assertEquals(lineEntranceFor(0), lineEntranceFor(LineEntrance.entries.size))
    }

    @Test
    fun wordsStartInOrderAndAllArriveByTheEnd() {
        assertEquals(0f, wordEntranceProgress(0f, 0, 5))
        for (w in 0 until 5) assertEquals(1f, wordEntranceProgress(1f, w, 5))
        val mid = (0 until 5).map { wordEntranceProgress(0.5f, it, 5) }
        assertTrue(mid.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(mid.first() > mid.last())
    }

    @Test
    fun aSingleWordLineDoesNotDivideByZero() {
        assertEquals(1f, wordEntranceProgress(1f, 0, 0))
    }
}
