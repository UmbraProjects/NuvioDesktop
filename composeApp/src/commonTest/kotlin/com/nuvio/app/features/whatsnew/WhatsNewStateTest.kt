package com.nuvio.app.features.whatsnew

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsNewStateTest {

    private fun entry(number: Int, weight: WhatsNewWeight = WhatsNewWeight.Small) = WhatsNewEntry(
        number = number,
        title = "Feature $number",
        pitch = "What feature $number does.",
        weight = weight,
    )

    private fun stateOf(vararg numbers: Int): WhatsNewUiState {
        val entries = numbers.map { entry(it) }
        return whatsNewStateFor(planWhatsNew(entries, lastSeen = 0))
    }

    @Test
    fun `no plan means nothing is visible`() {
        val state = whatsNewStateFor(null)
        assertFalse(state.visible)
        assertNull(state.current)
        assertTrue(state.cards.isEmpty())
        assertEquals(0, state.moreCount)
    }

    @Test
    fun `a plan opens on its spotlight`() {
        val state = stateOf(1, 2, 3)
        assertTrue(state.visible)
        assertEquals(0, state.index)
        assertTrue(state.isFirst)
        assertTrue(state.isSpotlight)
        assertEquals(3, state.current?.number)
        assertEquals(3, state.cards.size)
    }

    @Test
    fun `next walks the cards and stops on the last one`() {
        var state = stateOf(1, 2, 3)
        assertFalse(state.isLast)
        state = state.next()
        assertEquals(1, state.index)
        assertFalse(state.isSpotlight)
        assertFalse(state.isFirst)
        state = state.next()
        assertTrue(state.isLast)
        state = state.next()
        assertEquals(2, state.index)
        assertTrue(state.isLast)
    }

    @Test
    fun `back walks toward the spotlight and stops there`() {
        var state = stateOf(1, 2, 3).next().next()
        state = state.back()
        assertEquals(1, state.index)
        state = state.back().back()
        assertEquals(0, state.index)
        assertTrue(state.isFirst)
    }

    @Test
    fun `a single card is both first and last`() {
        val state = stateOf(1)
        assertTrue(state.isFirst)
        assertTrue(state.isLast)
        assertEquals(0, state.next().index)
        assertEquals(0, state.back().index)
    }

    @Test
    fun `stepping an empty state does nothing`() {
        val state = whatsNewStateFor(null)
        assertEquals(0, state.next().index)
        assertEquals(0, state.back().index)
    }

    @Test
    fun `the count of cards left out comes from the plan`() {
        val entries = (1..9).map { entry(it) }
        val state = whatsNewStateFor(planWhatsNew(entries, lastSeen = 0))
        assertEquals(4, state.moreCount)
    }

    @Test
    fun `a reopened screen remembers it came from settings`() {
        val plan = planWhatsNewHistory(listOf(entry(1)))
        assertTrue(whatsNewStateFor(plan, fromSettings = true).fromSettings)
        assertFalse(whatsNewStateFor(plan).fromSettings)
    }
}
