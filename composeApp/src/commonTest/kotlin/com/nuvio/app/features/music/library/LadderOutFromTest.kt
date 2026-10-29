package com.nuvio.app.features.music.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderOutFromTest {
    private fun cand(id: String, name: String, votes: Map<String, Int>, fans: Long? = null) =
        LadderCandidate(id = id, name = name, votes = votes, fans = fans)

    private val mbv = cand("a1", "My Bloody Valentine", mapOf("shoegaze" to 14, "noise pop" to 8, "dream pop" to 5))

    // The pool of the destination genre ("noise pop").
    private val noisePool = listOf(
        mbv,
        cand("n1", "Pale Saints", mapOf("noise pop" to 3, "shoegaze" to 4, "dream pop" to 2)),
        cand("n2", "The Jesus and Mary Chain", mapOf("noise pop" to 5, "shoegaze" to 3, "alternative rock" to 4)),
        cand("n3", "Guided by Voices", mapOf("noise pop" to 6, "lo-fi" to 5, "indie rock" to 4)),
        cand("n4", "Pavement", mapOf("noise pop" to 3, "lo-fi" to 6, "indie rock" to 8)),
        cand("n5", "Dinosaur Jr.", mapOf("noise pop" to 7, "indie rock" to 3)),
        cand("n6", "Beat Happening", mapOf("noise pop" to 9, "twee pop" to 2)),
        cand("n7", "Not Noise", mapOf("jazz" to 9)),
    )

    private val shoegazePool = listOf(
        mbv,
        cand("s1", "Slowdive", mapOf("shoegaze" to 9, "dream pop" to 7)),
        cand("s2", "Ride", mapOf("shoegaze" to 6, "dream pop" to 3)),
        cand("s3", "Lush", mapOf("shoegaze" to 5, "dream pop" to 5)),
        cand("s4", "Swervedriver", mapOf("shoegaze" to 4, "alternative rock" to 6)),
    )

    private val me = listOf(
        LibraryArtist(id = "lib1", name = "Pavement", genres = listOf("indie rock", "lo-fi")),
    )

    private fun target(c: LadderCandidate = mbv) = artistTarget(c)!!

    @Test
    fun destinationDefaultsToTheArtistsSecondGenre() {
        val t = target()
        val options = outwardOptions(t)
        assertEquals(listOf("noise pop", "dream pop").map(::genreKey), options.map { genreKey(it) })
        assertEquals(genreKey("noise pop"), genreKey(pickDestination(t, options, null)!!))
    }

    @Test
    fun aChosenGenreWinsUnlessItIsThePrimaryOneOrJunk() {
        val t = target()
        val options = outwardOptions(t)
        assertEquals(genreKey("dream pop"), genreKey(pickDestination(t, options, "Dream Pop")!!))
        assertEquals(genreKey("noise pop"), genreKey(pickDestination(t, options, "shoegaze")!!))
        assertEquals(genreKey("noise pop"), genreKey(pickDestination(t, options, "seen live")!!))
        assertEquals(genreKey("noise pop"), genreKey(pickDestination(t, options, "  ")!!))
    }

    @Test
    fun anArtistWithOneGenreFindsNeighboursFromTheirGenresPool() {
        val solo = cand("a1", "Solo", mapOf("shoegaze" to 10))
        val t = target(solo)
        assertTrue(outwardOptions(t).isEmpty())
        val options = outwardOptions(t, shoegazePool)
        assertEquals(genreKey("dream pop"), genreKey(options.first()))
        assertFalse(options.any { genreKey(it) == genreKey("shoegaze") })
    }

    @Test
    fun aGenreSeenOnlyOnceIsNotANeighbour() {
        val solo = cand("a1", "Solo", mapOf("shoegaze" to 10))
        val pool = listOf(
            cand("p1", "P1", mapOf("shoegaze" to 5, "dream pop" to 5)),
            cand("p2", "P2", mapOf("shoegaze" to 5, "alternative rock" to 5)),
        )
        assertTrue(outwardOptions(target(solo), pool).isEmpty())
    }

    @Test
    fun theArtistsOwnRowInThePoolDoesNotVoteForNeighbours() {
        val solo = cand("a1", "Solo", mapOf("shoegaze" to 10))
        val pool = listOf(
            cand("a1", "Solo", mapOf("shoegaze" to 5, "dream pop" to 5)),
            cand("p1", "P1", mapOf("shoegaze" to 5, "dream pop" to 5)),
        )
        assertTrue(outwardOptions(target(solo), pool).isEmpty())
    }

    @Test
    fun theArtistNeverStandsOnTheirOwnOutwardLadder() {
        val ladder = buildOutFromLadder(target(), "noise pop", me, noisePool + cand("zz", "my bloody valentine", mapOf("noise pop" to 9)))
        assertFalse(ladder.isEmpty)
        assertTrue(ladder.rungs.none { it.candidate.id == "a1" || it.candidate.id == "zz" })
        assertTrue(ladder.rungs.none { it.candidate.id == "n7" })
    }

    @Test
    fun savedArtistsStillNeverStandOnARung() {
        val ladder = buildOutFromLadder(target(), "noise pop", me, noisePool)
        assertTrue(ladder.rungs.none { it.candidate.name == "Pavement" })
    }

    @Test
    fun purityRisesAndTheDestinationIsTheLadderTarget() {
        val ladder = buildOutFromLadder(target(), "noise pop", me, noisePool)
        assertEquals("noise pop", ladder.target)
        val purities = ladder.rungs.map { it.purity }
        assertEquals(purities.sorted(), purities)
    }

    @Test
    fun closenessIsMeasuredAgainstTheArtistNotTheLibrary() {
        // Pale Saints shares shoegaze with the artist; Pavement-like artists share indie rock with the Library.
        val fromArtist = buildOutFromLadder(target(), "noise pop", me, noisePool)
        val first = fromArtist.rungs.first().candidate.name
        assertEquals("Pale Saints", first)
        assertFalse(fromArtist.coldStart)
    }

    @Test
    fun theWhyLineNamesTheArtistNotASavedOne() {
        val ladder = buildOutFromLadder(target(), "noise pop", me, noisePool)
        val first = ladder.rungs.first()
        assertEquals("My Bloody Valentine", first.anchorName)
        assertTrue(first.why.startsWith("Nearest to My Bloody Valentine: shares "), first.why)
        assertFalse(ladder.rungs.any { "your saved" in it.why || "your taste" in it.why }, ladder.rungs.joinToString { it.why })
    }

    @Test
    fun outFromGivesADifferentLadderThanIntoTheSameArtist() {
        val into = buildArtistLadder(target(), me, noisePool + shoegazePool)
        val out = buildOutFromLadder(target(), "noise pop", me, noisePool + shoegazePool)
        assertTrue(into.target != out.target)
    }

    @Test
    fun plainBuildLadderIsUnchangedWhenAnchorsAreNotGiven() {
        val a = buildLadder("noise pop", me, noisePool)
        val b = buildLadder("noise pop", me, noisePool, anchors = null, from = null)
        assertEquals(a, b)
        assertTrue(a.rungs.all { "your" in it.why || "way into" in it.why || "Deeper" in it.why || "core" in it.why })
    }

    @Test
    fun fullPathLeadsOutIntoTheSecondGenreAndOffersTheOthers() = runBlocking<Unit> {
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(mbv) }
        val asked = ArrayList<String>()
        val out = ladderOutFrom(resolver, { g -> asked += genreKey(g); noisePool }, "My Bloody Valentine", me)
        val ready = assertIs<ArtistLadderOutcome.Ready>(out)
        assertEquals(LadderMode.OutFrom, ready.mode)
        assertEquals(genreKey("noise pop"), genreKey(ready.destination!!))
        assertEquals(listOf("noise pop").map(::genreKey), asked)
        assertEquals(listOf("dream pop").map(::genreKey), ready.alternatives.map { genreKey(it) })
        assertTrue(ready.ladder.rungs.isNotEmpty())
    }

    @Test
    fun fullPathHonoursAChosenDestination() = runBlocking<Unit> {
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(mbv) }
        val asked = ArrayList<String>()
        val out = ladderOutFrom(resolver, { g -> asked += genreKey(g); shoegazePool }, "My Bloody Valentine", me, destination = "Dream Pop")
        val ready = assertIs<ArtistLadderOutcome.Ready>(out)
        assertEquals(listOf("dream pop").map(::genreKey), asked)
        assertEquals(genreKey("dream pop"), genreKey(ready.destination!!))
        assertEquals(listOf("noise pop").map(::genreKey), ready.alternatives.map { genreKey(it) })
    }

    @Test
    fun anArtistWithOneGenreFetchesItsPoolFirstThenTheNeighbour() = runBlocking<Unit> {
        val solo = cand("a1", "Solo", mapOf("shoegaze" to 10))
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(solo) }
        val asked = ArrayList<String>()
        val out = ladderOutFrom(resolver, { g -> asked += genreKey(g); shoegazePool }, "Solo", me)
        val ready = assertIs<ArtistLadderOutcome.Ready>(out)
        assertEquals(listOf("shoegaze", "dream pop").map(::genreKey), asked)
        assertEquals(genreKey("dream pop"), genreKey(ready.destination!!))
    }

    @Test
    fun noNeighbourGivesNoOutward() = runBlocking<Unit> {
        val solo = cand("a1", "Solo", mapOf("shoegaze" to 10))
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(solo) }
        val out = ladderOutFrom(resolver, { listOf(cand("p", "P", mapOf("shoegaze" to 3))) }, "Solo", me)
        assertEquals(ArtistLadderOutcome.Failed(ArtistLadderFailure.NoOutward), out)
    }

    @Test
    fun unreachableCandidatesAndUnknownArtistsFailCleanly() = runBlocking<Unit> {
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(mbv) }
        assertEquals(
            ArtistLadderOutcome.Failed(ArtistLadderFailure.Unreachable),
            ladderOutFrom(resolver, { null }, "My Bloody Valentine", me),
        )
        val none = ArtistTargetResolver(now = { 0L }) { emptyList() }
        assertEquals(
            ArtistLadderOutcome.Failed(ArtistLadderFailure.NoArtist),
            ladderOutFrom(none, { noisePool }, "Nobody", me),
        )
    }

    @Test
    fun aThinArtistCanLeadOutThroughBorrowedGenres() = runBlocking<Unit> {
        val thin = cand("t", "Tiny Band", emptyMap())
        val lenders = listOf(
            cand("r1", "R1", mapOf("shoegaze" to 6, "dream pop" to 5)),
            cand("r2", "R2", mapOf("shoegaze" to 4, "dream pop" to 3)),
        )
        val resolver = ArtistTargetResolver(now = { 0L }, relatedTags = { lenders }) { listOf(thin) }
        val ready = assertIs<ArtistLadderOutcome.Ready>(ladderOutFrom(resolver, { shoegazePool }, "Tiny Band", me))
        assertEquals(genreKey("dream pop"), genreKey(ready.destination!!))
        assertEquals(listOf("R1", "R2"), ready.target.borrowedFrom)
        assertNull(ready.ladder.rungs.firstOrNull { it.candidate.id == "t" })
    }
}
