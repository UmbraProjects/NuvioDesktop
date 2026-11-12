package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchBarPickerTest {
    private val live = listOf("batman", "batman begins", "bat out of hell").map { Prediction(it, PredictionSource.Title) }
    private val start = SearchBarPicker().typed("bat")

    @Test
    fun nothingIsHighlightedAtFirstSoEnterSearchesWhatWasTyped() {
        assertEquals(-1, start.index)
        assertEquals("bat", start.fieldText)
    }

    @Test
    fun downWalksTheListAndStopsAtTheLast() {
        val once = start.down(live)
        assertEquals("batman", once.fieldText)
        val end = once.down(live).down(live).down(live)
        assertEquals("bat out of hell", end.fieldText)
    }

    @Test
    fun theListIsHeldWhileWalkingEvenWhenTheLiveListChanges() {
        val walked = start.down(live)
        val newLive = listOf(Prediction("batman returns", PredictionSource.Title))
        assertEquals(live, walked.shown(newLive))
        assertEquals("batman begins", walked.down(newLive).fieldText)
    }

    @Test
    fun upFromTheFirstLineGivesBackWhatWasTyped() {
        val back = start.down(live).up(live)
        assertEquals("bat", back.fieldText)
        assertNull(back.held)
        assertEquals(live, back.shown(live))
    }

    @Test
    fun tabTakesTheTopLineThenCyclesRound() {
        val once = start.tab(live)
        assertEquals("batman", once.fieldText)
        assertEquals("batman begins", once.tab(live).fieldText)
        assertEquals("batman", once.tab(live).tab(live).tab(live).fieldText)
    }

    @Test
    fun tabAfterDownCarriesOnFromTheHighlight() {
        assertEquals("batman begins", start.down(live).tab(live).fieldText)
    }

    @Test
    fun typingLetsGoOfTheList() {
        val typed = start.down(live).typed("batm")
        assertNull(typed.held)
        assertEquals("batm", typed.fieldText)
    }

    @Test
    fun anEmptyListLeavesEverythingAsItWas() {
        assertEquals(start, start.down(emptyList()))
        assertEquals(start, start.tab(emptyList()))
        assertEquals(start, start.up(emptyList()))
    }

    @Test
    fun predictionsPutRecentSearchesFirstAndNeverRepeatTheText() {
        val items = predictionItems("bat", listOf("batman", "superman"), emptyList(), listOf("Batman", "bat", "batgirl"), PredictionSource.Title)
        assertEquals(
            listOf(Prediction("batman", PredictionSource.History), Prediction("batgirl", PredictionSource.Title)),
            items,
        )
    }

    @Test
    fun channelsThatStartWithTheTextComeFirst() {
        assertEquals(listOf("BBC One", "CBBC"), channelPredictions("bb", sequenceOf("CBBC", "BBC One", "ITV", "bb")))
    }
}
