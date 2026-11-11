package com.nuvio.app.features.tonight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TonightEndsByAndPileTest {
    @Test
    fun filmsThatRunPastBedtimeSinkToTheBottom() {
        val pool = listOf(film("heat", minutes = 170), film("nice", minutes = 116), film("short", minutes = 90))
        val rows = tonightEndsByRows(pool, TonightTaste.Empty, minutesUntilBed = 140)
        assertEquals("heat", rows.last().title.id)
        assertFalse(rows.last().fits)
        assertTrue(rows.dropLast(1).all { it.fits })
        // The one that fills the evening best leads.
        assertEquals("nice", rows.first().title.id)
    }

    @Test
    fun aShowGetsOneBlockPerEpisodeThatFitsPlusOnePastBed() {
        val row = tonightEndsByRows(listOf(show("det", episodeMinutes = 30)), TonightTaste.Empty, 100).single()
        assertEquals(3, row.episodesBeforeBed)
        assertEquals(listOf(30, 30, 30, 30), row.blockMinutes)
        assertEquals(90, row.minutesBeforeBed)
        assertEquals("3 eps · 22:40", row.endLabel(21 * 60 + 10))
    }

    @Test
    fun blocksStopAtFour() {
        val row = tonightEndsByRows(listOf(show("s", episodeMinutes = 20)), TonightTaste.Empty, 200).single()
        assertEquals(TONIGHT_MAX_EPISODE_BLOCKS, row.blockMinutes.size)
        assertEquals(4, row.episodesBeforeBed)
    }

    @Test
    fun aShowWithNoEpisodeThatFitsStillShowsOne() {
        val row = tonightEndsByRows(listOf(show("long", episodeMinutes = 60)), TonightTaste.Empty, 30).single()
        assertFalse(row.fits)
        assertEquals(listOf(60), row.blockMinutes)
        assertEquals("1 ep · 22:10", row.endLabel(21 * 60 + 10))
    }

    @Test
    fun titlesWithNoLengthAreLeftOff() {
        assertTrue(tonightEndsByRows(listOf(film("x", minutes = null)), TonightTaste.Empty, 120).isEmpty())
    }

    @Test
    fun filmLabelSaysWhenItEnds() {
        val row = tonightEndsByRows(listOf(film("f", minutes = 116)), TonightTaste.Empty, 140).single()
        assertEquals("ends 23:06", row.endLabel(21 * 60 + 10))
    }

    @Test
    fun axisRunsWholeHoursPastBedtime() {
        assertEquals(21 * 60 to 24 * 60, tonightEndsByAxis(21 * 60 + 10, 140))
        assertEquals(19 * 60 to 25 * 60, tonightEndsByAxis(19 * 60 + 5, 400))
    }

    @Test
    fun pileIsLibraryTitlesNeverStartedNewestFirst() {
        val day = 86_400_000L
        val now = 1_000 * day
        val pool = listOf(
            film("old").copy(addedAtEpochMs = now - 241 * day),
            film("new").copy(addedAtEpochMs = now - 3 * day),
            film("seen", watched = true).copy(addedAtEpochMs = now - 10 * day),
            film("trend", origins = setOf(TonightOrigin.Trending)),
            film("going", started = true).copy(addedAtEpochMs = now - 5 * day),
        )
        val pile = tonightPile(pool, now)
        assertEquals(listOf("new", "old"), pile.map { it.title.id })
        assertEquals(241, pile.last().waitedDays)
        assertEquals(1f, pile.last().dust)
        assertEquals(0f, pile.first().dust)
    }

    @Test
    fun ageLabels() {
        assertEquals("added today", pileAgeLabel(0))
        assertEquals("added 3 days ago", pileAgeLabel(3))
        assertEquals("added last week", pileAgeLabel(9))
        assertEquals("added 3 weeks ago", pileAgeLabel(21))
        assertEquals("added 8 months ago", pileAgeLabel(241))
        assertEquals("added 2 years ago", pileAgeLabel(800))
    }
}
