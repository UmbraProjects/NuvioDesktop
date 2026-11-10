package com.nuvio.app.features.tonight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonightCoinTossAndKnockoutTest {
    private val pool = listOf(
        film("epic", listOf("War", "Drama"), minutes = 175),
        film("romcom", listOf("Comedy", "Romance"), minutes = 98),
        film("caper", listOf("Comedy", "Crime"), minutes = 112),
        film("mystery", listOf("Mystery"), minutes = 118),
        film("tear", listOf("Drama"), minutes = 125),
    )

    @Test
    fun aLongWarDramaIsTooHeavyAndALongComedyTooLong() {
        assertEquals(TonightTrait.Heavy, tonightVetoTrait(pool[0], pool))
        val longComedy = film("long", listOf("Comedy", "Crime"), minutes = 160)
        assertEquals(TonightTrait.Long, tonightVetoTrait(longComedy, pool + longComedy))
    }

    @Test
    fun anOrdinaryTitleGivesNoReason() {
        assertNull(tonightVetoTrait(pool[2], pool))
    }

    @Test
    fun leanSaysWhatTheVetoesHadInCommon() {
        assertNull(tonightLeanLabel(emptyList()))
        val label = tonightLeanLabel(
            listOf(
                TonightVeto(pool[0], TonightTrait.Long),
                TonightVeto(pool[4], TonightTrait.Heavy),
                TonightVeto(pool[3], TonightTrait.Long),
            ),
        )
        assertEquals("leaning shorter and lighter", label)
    }

    @Test
    fun aTossNeverRepeatsAVeto() {
        val taste = TonightTaste.from(pool)
        val vetoes = pool.dropLast(1).map { TonightVeto(it, null) }
        repeat(20) { seed ->
            assertEquals("tear", tonightToss(pool, taste, vetoes, seed.toLong())?.id)
        }
        assertNull(tonightToss(pool, taste, pool.map { TonightVeto(it, null) }, 1L))
    }

    @Test
    fun aTossLeansAwayFromVetoedTraits() {
        val shortOnes = (1..6).map { film("s$it", listOf("Comedy"), minutes = 90) }
        val longOnes = (1..6).map { film("l$it", listOf("Comedy"), minutes = 160) }
        val all = shortOnes + longOnes
        val veto = listOf(TonightVeto(film("x", listOf("Comedy"), minutes = 170), TonightTrait.Long))
        val picks = (0 until 200).mapNotNull { tonightToss(all, TonightTaste.Empty, veto, it.toLong()) }
        val shortShare = picks.count { it.runtimeMinutes == 90 }.toFloat() / picks.size
        assertTrue(shortShare > 0.55f, "short films should come up more often, got $shortShare")
    }

    @Test
    fun bracketOfEightPlaysSevenMatchesInThreeRounds() {
        val eight = (1..8).map { film("f$it") }
        var b = TonightBracket(eight)
        assertEquals(7, b.totalMatches)
        assertEquals(3, b.roundCount)
        assertEquals(1, b.roundNumber)
        assertEquals("f1" to "f2", b.currentMatch!!.let { it.first.id to it.second.id })
        repeat(4) { b = b.keep(left = true) }
        assertEquals(2, b.roundNumber)
        assertEquals("f1" to "f3", b.currentMatch!!.let { it.first.id to it.second.id })
        b = b.keep(left = false).keep(left = true)
        assertEquals(3, b.roundNumber)
        assertEquals("f3" to "f5", b.currentMatch!!.let { it.first.id to it.second.id })
        b = b.keep(left = false)
        assertTrue(b.isFinished)
        assertEquals("f5", b.champion?.id)
        assertNull(b.currentMatch)
    }

    @Test
    fun undoStepsBackOneMatch() {
        val four = (1..4).map { film("f$it") }
        val b = TonightBracket(four).keep(left = false)
        assertEquals("f2", b.winners.single().id)
        assertEquals(TonightBracket(four), b.undo())
        assertEquals(TonightBracket(four), TonightBracket(four).undo())
    }

    @Test
    fun columnsKeepTheirWidthWhileFilling() {
        val eight = (1..8).map { film("f$it") }
        val b = TonightBracket(eight).keep(left = true)
        assertEquals(8, b.column(0).size)
        assertEquals(listOf("f1", null, null, null), b.column(1).map { it?.id })
        assertEquals(listOf(null, null), b.column(2).map { it?.id })
        assertEquals(1, b.column(3).size)
    }

    @Test
    fun entrantsAreAPowerOfTwoAndVaried() {
        val many = (1..20).map { film("f$it", listOf(listOf("Comedy", "Drama", "Horror", "Action", "Crime", "Family", "Mystery", "War")[it % 8])) }
        val chosen = tonightKnockoutEntrants(many, TonightTaste.Empty, 4L)
        assertEquals(8, chosen.size)
        assertEquals(8, chosen.map { it.key }.toSet().size)
        assertTrue(chosen.map { it.genres.first() }.toSet().size >= 6)
        assertEquals(4, tonightKnockoutEntrants(many.take(5), TonightTaste.Empty, 1L).size)
        assertEquals(2, tonightKnockoutEntrants(many.take(3), TonightTaste.Empty, 1L).size)
        assertTrue(tonightKnockoutEntrants(many.take(1), TonightTaste.Empty, 1L).isEmpty())
    }

    @Test
    fun aBracketOfTwoIsJustAFinal() {
        val b = TonightBracket(listOf(film("a"), film("b")))
        assertEquals(1, b.roundCount)
        assertNotNull(b.currentMatch)
        assertFalse(b.isFinished)
        assertEquals("b", b.keep(left = false).champion?.id)
    }
}
