package com.nuvio.app.features.tonight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonightQuestionsAndDealTest {
    private val mixed = listOf(
        film("comedy", listOf("Comedy"), minutes = 95, year = 2024),
        film("drama", listOf("Drama"), minutes = 140, year = 2001),
        film("thriller", listOf("Thriller"), minutes = 118, year = 2019, watched = true),
        show("sitcom", listOf("Comedy"), episodeMinutes = 25, started = true),
        show("crime", listOf("Crime", "Drama"), episodeMinutes = 55, origins = setOf(TonightOrigin.Trending)),
    )

    @Test
    fun filmOrShowAlwaysComesFirstAndTheRestVaryByNight() {
        val a = tonightQuestionOrder("2026-11-10", 2023)
        assertEquals("film_show", a.first().id)
        val orders = (10..20).map { day -> tonightQuestionOrder("2026-11-$day", 2023).drop(1).map { it.id } }.toSet()
        assertTrue(orders.size > 1, "the rotating questions should not come in the same order every night")
    }

    @Test
    fun theSameNightGivesTheSameOrder() {
        assertEquals(
            tonightQuestionOrder("2026-11-10", 2023).map { it.id },
            tonightQuestionOrder("2026-11-10", 2023).map { it.id },
        )
    }

    @Test
    fun answeringFiltersAndSkipKeepsEverything() {
        assertEquals(3, tonightApply(mixed, TonightFilmOrShow, TonightSide.Left).size)
        assertEquals(2, tonightApply(mixed, TonightFilmOrShow, TonightSide.Right).size)
        assertEquals(mixed, tonightApply(mixed, TonightFilmOrShow, TonightSide.Skip))
    }

    @Test
    fun aQuestionThatWouldNotSplitIsPassedOver() {
        val films = mixed.filter { it.kind == TonightKind.Film }
        val order = tonightQuestionOrder("2026-11-10", 2023)
        val next = tonightNextQuestion(order, asked = emptyList(), pool = films)
        // Every title is a film, so "Film or show?" has nothing to split.
        assertNotEquals("film_show", next?.id)
    }

    @Test
    fun questionsStopAtThreeOrWhenOneTitleIsLeft() {
        val order = tonightQuestionOrder("2026-11-10", 2023)
        assertNull(tonightNextQuestion(order, listOf("a", "b", "c"), mixed))
        assertNull(tonightNextQuestion(order, emptyList(), mixed.take(1)))
    }

    @Test
    fun dealGivesThreeDifferentCardsInSlotOrder() {
        val cards = tonightDeal(mixed, TonightTaste.from(mixed), seed = 1L)
        assertEquals(listOf(TonightSlot.Safe, TonightSlot.New, TonightSlot.Wildcard), cards.map { it.slot })
        assertEquals(3, cards.map { it.title.key }.toSet().size)
    }

    @Test
    fun safeIsSomethingStartedOrSeenAndNewIsUnseen() {
        val cards = tonightDeal(mixed, TonightTaste.from(mixed), seed = 7L)
        val safe = cards.first { it.slot == TonightSlot.Safe }.title
        assertTrue(safe.started || safe.watched)
        cards.filter { it.slot != TonightSlot.Safe }.forEach { assertTrue(!it.title.started && !it.title.watched) }
    }

    @Test
    fun aStartedShowSaysWhichEpisode() {
        val pool = listOf(show("s", listOf("Comedy"), started = true).copy(nextEpisodeLabel = "S2 E4"))
        val card = tonightDeal(pool, TonightTaste.from(pool), seed = 1L).single()
        assertEquals("You're on S2 E4", card.why)
    }

    @Test
    fun reshuffleNeverDealsTheSameTitleTwice() {
        val pool = (1..12).map { film("f$it", listOf(if (it % 2 == 0) "Comedy" else "Drama"), watched = it <= 3) }
        val taste = TonightTaste.from(pool)
        val first = tonightDeal(pool, taste, seed = 1L)
        val second = tonightDeal(pool, taste, seed = 2L, exclude = first.map { it.title.key }.toSet())
        assertTrue(first.map { it.title.key }.intersect(second.map { it.title.key }.toSet()).isEmpty())
    }

    @Test
    fun aTinyPoolStillDealsWhatItHas() {
        val pool = listOf(film("only"), film("two"))
        assertEquals(2, tonightDeal(pool, TonightTaste.Empty, seed = 3L).size)
        assertEquals(0, tonightDeal(emptyList(), TonightTaste.Empty, seed = 3L).size)
    }
}
