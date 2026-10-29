package com.nuvio.app.features.music.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LadderProgressTest {
    private fun cand(id: String, name: String, votes: Map<String, Int>) = LadderCandidate(id = id, name = name, votes = votes)

    private fun rung(id: String, step: Int = 1, purity: Double = 0.5) = LadderRung(
        step = step, candidate = cand(id, "Artist $id", mapOf("shoegaze" to 1)), purity = purity, proximity = 0.4,
        anchorName = "Cocteau Twins", why = "why $id",
    )

    private val pool = listOf(
        cand("p1", "Pale Saints", mapOf("shoegaze" to 3, "dream pop" to 6)),
        cand("p2", "Lush", mapOf("shoegaze" to 5, "dream pop" to 5)),
        cand("p3", "Ride", mapOf("shoegaze" to 6, "dream pop" to 3)),
        cand("p4", "Slowdive", mapOf("shoegaze" to 9, "dream pop" to 4)),
        cand("p5", "Chapterhouse", mapOf("shoegaze" to 8, "dream pop" to 2)),
        cand("p6", "Swervedriver", mapOf("shoegaze" to 7)),
        cand("p7", "Catherine Wheel", mapOf("shoegaze" to 10)),
    )
    private val me = listOf(LibraryArtist(id = "l1", name = "Cocteau Twins", genres = listOf("dream pop")))
    private val request = LadderRequest.Genre("shoegaze")

    private fun load(progress: LadderProgress): LadderView = runBlocking {
        val resolver = ArtistTargetResolver(now = { 0L }) { emptyList() }
        assertIs<LadderState.Ready>(loadLadder(request, me, resolver, { pool }, progress)).view
    }

    @Test
    fun tooFarMovesTheDialTowardKnownAndTooCloseTowardUnknown() {
        val start = LadderProgress(dial = 0.5)
        assertEquals(0.25, applyFeedback(start, rung("a"), RungFeedback.TooFar).dial)
        assertEquals(0.75, applyFeedback(start, rung("a"), RungFeedback.TooClose).dial)
        assertEquals(0.5, applyFeedback(start, rung("a"), RungFeedback.Love).dial)
    }

    @Test
    fun theDialStaysBetweenZeroAndOne() {
        assertEquals(0.0, applyFeedback(LadderProgress(), rung("a"), RungFeedback.TooFar).dial)
        assertEquals(1.0, applyFeedback(LadderProgress(dial = 1.0), rung("a"), RungFeedback.TooClose).dial)
    }

    @Test
    fun skipHidesTheArtistWithoutMovingTheDialOrMarkingIt() {
        val p = applyFeedback(LadderProgress(dial = 0.5), rung("a"), RungFeedback.Skip)
        assertEquals(listOf("a"), p.skipped)
        assertTrue(p.climbed.isEmpty())
        assertEquals(0.5, p.dial)
    }

    @Test
    fun otherFeedbackMarksTheRungAndNewFeedbackReplacesTheOldMark() {
        var p = applyFeedback(LadderProgress(), rung("a"), RungFeedback.Love)
        p = applyFeedback(p, rung("a"), RungFeedback.TooClose)
        assertEquals(listOf(RungFeedback.TooClose), p.climbed.map { it.feedback })
        p = applyFeedback(p, rung("a"), RungFeedback.Skip)
        assertTrue(p.climbed.isEmpty())
        assertEquals(listOf("a"), p.skipped)
        p = applyFeedback(p, rung("a"), RungFeedback.Love)
        assertTrue(p.skipped.isEmpty())
    }

    @Test
    fun onlyTheLastFourMarksAreKeptSoARungIsAlwaysLeftToBuild() {
        var p = LadderProgress()
        for (i in 1..6) p = applyFeedback(p, rung("r$i"), RungFeedback.Love)
        assertEquals(listOf("r3", "r4", "r5", "r6"), p.climbed.map { it.id })
        assertEquals(1, remainingRungs(p))
        assertEquals(LADDER_RUNGS, remainingRungs(LadderProgress()))
    }

    @Test
    fun markedRungsComeFirstAndStepsAreRenumbered() {
        val p = applyFeedback(LadderProgress(), rung("m1"), RungFeedback.Love)
        val fresh = Ladder("shoegaze", listOf(rung("f1", step = 1), rung("f2", step = 2)), coldStart = false)
        val merged = mergeClimbed(p, fresh)
        assertEquals(listOf("m1", "f1", "f2"), merged.rungs.map { it.candidate.id })
        assertEquals(listOf(1, 2, 3), merged.rungs.map { it.step })
        assertEquals(RungFeedback.Love, merged.rungs.first().mark)
        assertEquals("why m1", merged.rungs.first().why)
        assertEquals(fresh, mergeClimbed(LadderProgress(), fresh))
    }

    @Test
    fun aMarkedRungStaysAndTheRestAreRebuiltWithoutIt() {
        val first = load(LadderProgress())
        val picked = first.ladder.rungs[1]
        val p = applyFeedback(LadderProgress(), picked, RungFeedback.Love)
        val after = load(p)
        assertEquals(LADDER_RUNGS, after.ladder.rungs.size)
        assertEquals(picked.candidate.id, after.ladder.rungs.first().candidate.id)
        assertEquals(1, after.ladder.rungs.count { it.candidate.id == picked.candidate.id })
        assertEquals((1..after.ladder.rungs.size).toList(), after.ladder.rungs.map { it.step })
    }

    @Test
    fun aSkippedArtistNeverComesBack() {
        val first = load(LadderProgress())
        val gone = first.ladder.rungs.first()
        val after = load(applyFeedback(LadderProgress(), gone, RungFeedback.Skip))
        assertTrue(after.ladder.rungs.none { it.candidate.id == gone.candidate.id })
    }

    @Test
    fun theDialChangesWhichRungsComeNext() {
        val known = load(LadderProgress(dial = 0.0)).ladder.rungs.map { it.candidate.id }
        val unknown = load(LadderProgress(dial = 1.0)).ladder.rungs.map { it.candidate.id }
        assertFalse(known == unknown)
    }

    @Test
    fun keysIgnoreSpellingAndSeparateModes() {
        assertEquals(progressKey(LadderRequest.Genre("Post-Punk")), progressKey(LadderRequest.Genre("post punk")))
        assertFalse(
            progressKey(LadderRequest.Artist("A", LadderMode.Into)) == progressKey(LadderRequest.Artist("A", LadderMode.OutFrom)),
        )
        assertFalse(
            progressKey(LadderRequest.Artist("A", LadderMode.OutFrom, "dream pop")) ==
                progressKey(LadderRequest.Artist("A", LadderMode.OutFrom, "noise pop")),
        )
    }

    @Test
    fun progressSurvivesARestartThroughTheStore() {
        var disk: String? = null
        fun book() = LadderProgressBook(load = { disk }, save = { disk = it })
        val key = progressKey(request)
        var p = applyFeedback(LadderProgress(), rung("a"), RungFeedback.TooFar)
        p = applyFeedback(p, rung("b"), RungFeedback.TooClose)
        p = applyFeedback(p, rung("c"), RungFeedback.Skip)
        book().put(key, p)
        val reopened = book().get(key)
        assertEquals(p, reopened)
        assertEquals(listOf("a", "b"), reopened.climbed.map { it.id })
        assertEquals(listOf("c"), reopened.skipped)
        assertEquals(LadderProgress(), book().get("genre:other"))
    }

    @Test
    fun startingOverLeavesNothingBehindAndBadDataIsIgnored() {
        var disk: String? = null
        val book = LadderProgressBook(load = { disk }, save = { disk = it })
        book.put("k", applyFeedback(LadderProgress(), rung("a"), RungFeedback.Love))
        book.put("k", LadderProgress())
        assertEquals(LadderProgress(), book.get("k"))
        assertNotNull(disk)
        assertFalse("\"k\"" in disk!!)
        disk = "not json"
        assertEquals(LadderProgress(), book.get("k"))
    }

    @Test
    fun aSkipOnOneLadderHidesTheArtistOnEveryLadderAndSurvivesStartingOver() {
        var disk: String? = null
        val book = LadderProgressBook(load = { disk }, save = { disk = it })
        book.put("genre:hip hop", applyFeedback(LadderProgress(), rung("nicki"), RungFeedback.Skip))
        assertEquals(setOf("nicki"), book.skippedEverywhere())
        // A different ladder has no skips of its own, but the book still knows who is gone.
        assertTrue(book.get("genre:pop").skipped.isEmpty())
        assertEquals(setOf("nicki"), book.skippedEverywhere())
        // Starting the first ladder over does not forgive the skip.
        book.put("genre:hip hop", LadderProgress())
        assertEquals(setOf("nicki"), book.skippedEverywhere())
    }
}
