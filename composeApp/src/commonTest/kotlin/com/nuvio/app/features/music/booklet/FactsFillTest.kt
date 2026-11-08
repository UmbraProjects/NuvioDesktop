package com.nuvio.app.features.music.booklet

import com.nuvio.app.features.discover.AiTokenSpend
import com.nuvio.app.features.discover.retryAfterInMessage
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
    fun everyKindHasAnEstimateAndTheStoryCostsMost() {
        assertTrue(estimateTokens(FillKind.Story) >= estimateTokens(FillKind.Notes))
        assertTrue(estimateTokens(FillKind.TrackNotes) > 0)
    }

    @Test
    fun aLookupGoesAtOnceWhenItFitsTheMinute() {
        val now = 1_000_000L
        assertEquals(0L, minuteRoomInMs(emptyList(), 5_000, FILL_TOKENS_PER_MIN, now))
        assertEquals(0L, minuteRoomInMs(listOf(AiTokenSpend(now - 10_000, 1_000)), 5_000, FILL_TOKENS_PER_MIN, now))
        // Spends older than a minute no longer count.
        assertEquals(0L, minuteRoomInMs(listOf(AiTokenSpend(now - 61_000, 6_000)), 5_000, FILL_TOKENS_PER_MIN, now))
        assertTrue(FILL_TOKENS_PER_MIN < 8_000L)
    }

    @Test
    fun aLookupWaitsUntilEnoughOfTheMinuteHasPassed() {
        val now = 1_000_000L
        // 3,000 at -50 s and 2,000 at -20 s: a 2,500 lookup needs the first to leave the minute, 10 s from now.
        val spends = listOf(AiTokenSpend(now - 20_000, 2_000), AiTokenSpend(now - 50_000, 3_000))
        assertEquals(10_000L, minuteRoomInMs(spends, 2_500, FILL_TOKENS_PER_MIN, now))
        // A 4,500 lookup needs both gone: the newer leaves in 40 s.
        assertEquals(40_000L, minuteRoomInMs(spends, 4_500, FILL_TOKENS_PER_MIN, now))
        // Bigger than the limit on its own: it waits for an empty minute, then goes.
        assertEquals(40_000L, minuteRoomInMs(spends, 9_000, FILL_TOKENS_PER_MIN, now))
    }

    @Test
    fun theExpectedCostLearnsFromTheLastStepOfItsKind() {
        assertEquals(estimateTokens(FillKind.Story), expectedCost(FillKind.Story, emptyMap()))
        assertEquals(1_800L, expectedCost(FillKind.Story, mapOf(FillKind.Story to 1_800L)))
        assertEquals(estimateTokens(FillKind.Notes), expectedCost(FillKind.Notes, mapOf(FillKind.Story to 1_800L)))
    }

    @Test
    fun groqsRetryHintIsReadFromItsMessage() {
        assertEquals(8, retryAfterInMessage("Rate limit reached on tokens per minute (TPM). Please try again in 7.26s. Visit…"))
        assertEquals(91, retryAfterInMessage("Please try again in 1m30.5s."))
        assertEquals(1, retryAfterInMessage("Please try again in 860ms."))
        assertEquals(7_440, retryAfterInMessage("tokens per day (TPD): Please try again in 2h4m."))
        assertNull(retryAfterInMessage("Something else went wrong"))
    }

    @Test
    fun capsAreInTokensAndLabelled() {
        assertEquals("150k tokens", capLabel(150_000L))
        assertTrue(FILL_CAP_DEFAULT in FILL_CAP_CHOICES)
        assertTrue(FILL_CAP_DEFAULT < 200_000L, "leaves some of a free key's day for the person")
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
    fun budgetResetsAfterADayAndCountsTokens() {
        val start = 1_000L
        var b = FillBudget().spend(2_000L, start)
        assertEquals(2_000L, b.used)
        assertEquals(148_000L, b.left(150_000L, start + 1))
        b = b.spend(148_000L, start + 5)
        assertEquals(0L, b.left(150_000L, start + 10))
        assertEquals(start + FILL_WINDOW_MS, b.resumesAtMs(150_000L, start + 10))
        assertEquals(150_000L, b.left(150_000L, start + FILL_WINDOW_MS), "a new window starts empty")
        assertNull(b.resumesAtMs(150_000L, start + FILL_WINDOW_MS))
        assertNull(FillBudget().resumesAtMs(150_000L, 5L))
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
        assertEquals(FillBudget(5L, 7L), decodeBudget(encodeBudget(FillBudget(5L, 7L))))
        assertEquals(FillBudget(), decodeBudget("junk"))
        assertEquals(300_000L, decodeCap("300000"))
        assertEquals(FILL_CAP_DEFAULT, decodeCap("150"))
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
