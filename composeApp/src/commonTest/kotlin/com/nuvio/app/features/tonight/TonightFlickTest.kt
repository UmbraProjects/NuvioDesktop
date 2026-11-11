package com.nuvio.app.features.tonight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TonightFlickTest {
    @Test
    fun everyTitleGetsOneChannel() {
        val pool = (1..10).map { film("f$it") } + film("f1")
        val order = tonightFlickOrder(pool, TonightTaste.Empty, 1L)
        assertEquals(10, order.size)
        assertEquals(10, order.map { it.key }.toSet().size)
    }

    @Test
    fun unseenTitlesComeFirstOnAverage() {
        val seen = (1..10).map { film("s$it", watched = true) }
        val unseen = (1..10).map { film("u$it") }
        val firstFive = (0 until 50).flatMap { seed ->
            tonightFlickOrder(seen + unseen, TonightTaste.Empty, seed.toLong()).take(5)
        }
        val unseenShare = firstFive.count { !it.watched }.toFloat() / firstFive.size
        assertTrue(unseenShare > 0.7f, "unseen titles should lead, got $unseenShare")
    }

    @Test
    fun theSameNightGivesTheSameLineUp() {
        val pool = (1..10).map { film("f$it") }
        assertEquals(
            tonightFlickOrder(pool, TonightTaste.Empty, 9L).map { it.id },
            tonightFlickOrder(pool, TonightTaste.Empty, 9L).map { it.id },
        )
    }

    @Test
    fun originLineSaysWhereATitleComesFrom() {
        assertEquals("YOU'RE ON S2 E4", show("s", started = true).copy(nextEpisodeLabel = "S2 E4").flickOriginLine())
        assertEquals("FROM YOUR LIBRARY", film("l").flickOriginLine())
        assertEquals("TRENDING THIS WEEK", film("t", origins = setOf(TonightOrigin.Trending)).flickOriginLine())
        assertEquals("SEEN BEFORE", film("w", watched = true).flickOriginLine())
    }
}
