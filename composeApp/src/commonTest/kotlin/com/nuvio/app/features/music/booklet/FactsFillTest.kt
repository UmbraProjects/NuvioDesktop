package com.nuvio.app.features.music.booklet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactsFillTest {
    private fun cand(id: String, artist: String, album: String = id, fav: Boolean = false, year: Int? = null) =
        FillCandidate(id, "ch-$artist", artist, album, fav, year)

    @Test
    fun planAsksForNotesThenTrackNotesThenEachSongInOrder() {
        val states = AlbumFactStates(FactState.Missing, FactState.Missing, listOf("A" to FactState.Missing, "B" to FactState.Have, "C" to FactState.Missing))
        val plan = planAlbum("Artist", "Album", "id", states)
        assertEquals(listOf(FillKind.Notes, FillKind.TrackNotes, FillKind.Story, FillKind.Story), plan.steps.map { it.kind })
        assertEquals(listOf("A", "C"), plan.steps.mapNotNull { it.song })
        assertTrue(plan.missed.isEmpty())
        assertFalse(plan.isDone)
    }

    @Test
    fun recentFailuresGoToTheRetryListNotTheQueue() {
        val states = AlbumFactStates(FactState.Have, FactState.Missed, listOf("A" to FactState.Missed, "B" to FactState.Have))
        val plan = planAlbum("Artist", "Album", "id", states)
        assertTrue(plan.isDone)
        assertEquals(listOf("Artist - Album (Track notes)", "Artist - A"), plan.missed.map { it.title })
    }

    @Test
    fun notesCostTwoRequestsTheRestOne() {
        assertEquals(2, requestsFor(FillKind.Notes))
        assertEquals(1, requestsFor(FillKind.TrackNotes))
        assertEquals(1, requestsFor(FillKind.Story))
    }

    @Test
    fun mostPlayedFirstThenLatestThenFavouritesThenTheRest() {
        val albums = listOf(
            cand("never-b", "Beta", year = 2001),
            cand("fav", "Zed", fav = true),
            cand("often", "Omega"),
            cand("recent", "Rho"),
            cand("never-a", "Alpha", year = 1999),
            cand("old", "Old"),
        )
        val plays = mapOf(
            "often" to PlayStat(9, 100L),
            "recent" to PlayStat(2, 900L),
            "old" to PlayStat(2, 50L),
        )
        assertEquals(listOf("often", "recent", "old", "fav", "never-a", "never-b"), fillOrder(albums, plays).map { it.albumId })
    }

    @Test
    fun budgetResetsAfterADayAndCountsRequests() {
        val start = 1_000L
        var b = FillBudget().spend(2, start)
        assertEquals(2, b.used)
        assertEquals(148, b.left(150, start + 1))
        b = b.spend(148, start + 5)
        assertEquals(0, b.left(150, start + 10))
        assertEquals(start + FILL_WINDOW_MS, b.resumesAtMs(150, start + 10))
        assertEquals(150, b.left(150, start + FILL_WINDOW_MS), "a new window starts empty")
        assertNull(b.resumesAtMs(150, start + FILL_WINDOW_MS))
        assertNull(FillBudget().resumesAtMs(150, 5L))
    }

    @Test
    fun gapsGrowWithFailuresThenPause() {
        assertEquals(FILL_GAP_MS, nextGapMs(0))
        assertEquals(FILL_FAIL_GAP_MS, nextGapMs(1))
        assertEquals(FILL_FAIL_GAP_MS, nextGapMs(FILL_FAILS_BEFORE_PAUSE - 1))
        assertEquals(FILL_PAUSE_MS, nextGapMs(FILL_FAILS_BEFORE_PAUSE))
    }

    @Test
    fun savedValuesRoundTripAndDamageReadsAsEmpty() {
        assertEquals(PlayStat(3, 77L), decodePlayStat(encodePlayStat(PlayStat(3, 77L))))
        assertNull(decodePlayStat("junk"))
        assertNull(decodePlayStat(null))
        assertEquals(FillBudget(5L, 7), decodeBudget(encodeBudget(FillBudget(5L, 7))))
        assertEquals(FillBudget(), decodeBudget("junk"))
        assertEquals(300, decodeCap("300"))
        assertEquals(FILL_CAP_DEFAULT, decodeCap("7"))
        assertEquals(FILL_CAP_DEFAULT, decodeCap(null))
    }

    @Test
    fun progressLineReadsPlainly() {
        assertEquals("Nothing to fill yet", fillProgressLine(0, 0, 0))
        assertEquals("3 of 10 albums filled (40 songs)", fillProgressLine(3, 10, 40))
        assertEquals("All 10 albums are filled (120 songs)", fillProgressLine(10, 10, 120))
    }

    @Test
    fun theRetryListSurvivesARestart() {
        val steps = listOf(
            FillStep(FillKind.Notes, "Adele", "21", "MPREb_21"),
            FillStep(FillKind.Story, "Adele", "21", "MPREb_21", "Rumour\tHas It"),
            FillStep(FillKind.TrackNotes, "Jamiroquai", "Space Cowboy", "MPREb_sc"),
        )
        val back = decodeRetry(encodeRetry(steps))
        assertEquals(3, back.size)
        assertEquals("Rumour Has It", back[1].song) // a tab in a title cannot break the line
        assertEquals(steps[0], back[0])
        assertEquals(steps[2], back[2])
        assertEquals(emptyList(), decodeRetry(null))
        assertEquals(1, decodeRetry("Notes\tA\tB\tid\t\nOddKind\tA\tB\tid\t\nbroken line").size)
    }
}
