package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LadderBuilderTest {
    private fun artist(
        name: String,
        genres: List<String>,
        mood: String? = null,
        id: String = "lib-$name",
    ) = LibraryArtist(id = id, name = name, genres = genres, mood = mood)

    private fun cand(
        id: String,
        votes: Map<String, Int>,
        fans: Long? = null,
        playable: Boolean = true,
        mood: String? = null,
    ) = LadderCandidate(id = id, name = id, votes = votes, fans = fans, playable = playable, mood = mood)

    // A listener of dream pop and ambient, climbing into shoegaze.
    private val me = listOf(
        artist("Cocteau Twins", listOf("dream pop", "ambient"), mood = "dreamy"),
        artist("Grouper", listOf("dream pop", "drone")),
    )

    private val pool = listOf(
        cand("Slowdive", mapOf("shoegaze" to 6, "dream pop" to 8, "ambient" to 4), fans = 900_000),
        cand("BeachHouse", mapOf("shoegaze" to 3, "dream pop" to 9, "indie pop" to 3), fans = 1_500_000),
        cand("Ride", mapOf("shoegaze" to 8, "indie rock" to 6, "britpop" to 2), fans = 500_000),
        cand("MBV", mapOf("shoegaze" to 12, "noise rock" to 7, "indie rock" to 3), fans = 800_000),
        cand("Swervedriver", mapOf("shoegaze" to 7, "alternative rock" to 4, "indie rock" to 1), fans = 120_000),
        cand("PureGaze", mapOf("shoegaze" to 10), fans = 40_000),
        cand("PostMetalGaze", mapOf("shoegaze" to 5, "post-metal" to 5), fans = 60_000),
        cand("AmbientGaze", mapOf("ambient" to 8, "shoegaze" to 2), fans = 70_000),
    )

    @Test
    fun givesFiveRungsNumberedFromOne() {
        val ladder = buildLadder("shoegaze", me, pool)
        assertEquals("shoegaze", ladder.target)
        assertEquals(listOf(1, 2, 3, 4, 5), ladder.rungs.map { it.step })
        assertFalse(ladder.coldStart)
    }

    @Test
    fun purityRisesAndTheBridgeIsClosestToTheListener() {
        val ladder = buildLadder("shoegaze", me, pool)
        val purities = ladder.rungs.map { it.purity }
        assertEquals(purities.sorted(), purities)
        assertTrue(ladder.rungs.first().proximity > ladder.rungs.last().proximity)
        assertTrue(ladder.rungs.first().purity < ladder.rungs.last().purity)
    }

    @Test
    fun noCandidateStandsOnTwoRungs() {
        val ids = buildLadder("shoegaze", me, pool).rungs.map { it.candidate.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun theBridgeSharesGenresWithASavedArtistAndSaysSo() {
        val first = buildLadder("shoegaze", me, pool).rungs.first()
        // Both saved artists share dream pop with the bridge; whichever is closer is named.
        assertTrue(first.anchorName in setOf("Cocteau Twins", "Grouper"), first.anchorName.toString())
        assertTrue(first.why.startsWith("Closest to your taste: shares "), first.why)
        assertTrue("dream pop" in first.why, first.why)
        assertTrue(first.anchorName!! in first.why, first.why)
    }

    @Test
    fun theLastRungIsLabelledTheCore() {
        val last = buildLadder("shoegaze", me, pool).rungs.last()
        assertTrue(last.why.startsWith("The core of shoegaze"), last.why)
    }

    @Test
    fun artistsAlreadySavedNeverStandOnARung() {
        val owned = me + artist("Slowdive", listOf("shoegaze"), id = "lib-slowdive")
        val ids = buildLadder("shoegaze", owned, pool).rungs.map { it.candidate.id }
        assertFalse("Slowdive" in ids)
    }

    @Test
    fun anOwnedArtistIsMatchedByIdToo() {
        val owned = me + artist("Someone Else", listOf("shoegaze"), id = "Ride")
        val ids = buildLadder("shoegaze", owned, pool).rungs.map { it.candidate.id }
        assertFalse("Ride" in ids)
    }

    @Test
    fun theFarEndOfTheDialStartsFurtherUpTheGenre() {
        val known = buildLadder("shoegaze", me, pool, dial = 0.0).rungs
        val unknown = buildLadder("shoegaze", me, pool, dial = 1.0).rungs
        assertTrue(unknown.first().purity > known.first().purity, "known=${known.first().purity} unknown=${unknown.first().purity}")
        // Both still climb.
        assertEquals(unknown.map { it.purity }.sorted(), unknown.map { it.purity })
    }

    @Test
    fun theDialIsClampedToItsRange() {
        val a = buildLadder("shoegaze", me, pool, dial = -3.0)
        val b = buildLadder("shoegaze", me, pool, dial = 0.0)
        assertEquals(b.rungs.map { it.candidate.id }, a.rungs.map { it.candidate.id })
        val c = buildLadder("shoegaze", me, pool, dial = 9.0)
        val d = buildLadder("shoegaze", me, pool, dial = 1.0)
        assertEquals(d.rungs.map { it.candidate.id }, c.rungs.map { it.candidate.id })
    }

    @Test
    fun withoutALibraryTheLadderIsColdAndStillClimbs() {
        val ladder = buildLadder("shoegaze", emptyList(), pool)
        assertTrue(ladder.coldStart)
        assertEquals(5, ladder.rungs.size)
        assertEquals(ladder.rungs.map { it.purity }.sorted(), ladder.rungs.map { it.purity })
        assertTrue(ladder.rungs.all { it.proximity == 0.0 })
        assertTrue(ladder.rungs.all { it.anchorName == null })
        assertTrue(ladder.rungs.first().why.startsWith("A popular way into shoegaze"), ladder.rungs.first().why)
    }

    @Test
    fun aLibraryWithNothingBeyondTheTargetIsColdToo() {
        val onlyTarget = listOf(artist("A", listOf("shoegaze")))
        assertTrue(buildLadder("shoegaze", onlyTarget, pool).coldStart)
    }

    @Test
    fun whenPurityTiesInColdStartMoreFansWinTheBridge() {
        val twins = listOf(
            cand("Small", mapOf("shoegaze" to 3, "ambient" to 7), fans = 1_000),
            cand("Big", mapOf("shoegaze" to 3, "ambient" to 7), fans = 5_000_000),
            cand("Core", mapOf("shoegaze" to 10)),
        )
        val ladder = buildLadder("shoegaze", emptyList(), twins, rungs = 2)
        assertEquals("Big", ladder.rungs.first().candidate.id)
    }

    @Test
    fun aPlayableArtistBeatsAnUnplayableOneThatFitsEqually() {
        val twins = listOf(
            cand("Gone", mapOf("shoegaze" to 3, "dream pop" to 7), playable = false),
            cand("Here", mapOf("shoegaze" to 3, "dream pop" to 7), playable = true),
            cand("Core", mapOf("shoegaze" to 10)),
        )
        val ladder = buildLadder("shoegaze", me, twins, rungs = 2)
        assertEquals("Here", ladder.rungs.first().candidate.id)
    }

    @Test
    fun anUnplayableArtistStillTakesARungWhenItIsTheBestFit() {
        val only = listOf(cand("Gone", mapOf("shoegaze" to 6, "dream pop" to 6), playable = false))
        val ladder = buildLadder("shoegaze", me, only)
        assertEquals(listOf("Gone"), ladder.rungs.map { it.candidate.id })
        assertFalse(ladder.rungs.single().candidate.playable)
    }

    @Test
    fun fewerCandidatesThanRungsGivesAShorterLadder() {
        val ladder = buildLadder("shoegaze", me, pool.take(3))
        assertEquals(listOf(1, 2, 3), ladder.rungs.map { it.step })
    }

    @Test
    fun aSingleCandidateStillMakesALadderOfOne() {
        val ladder = buildLadder("shoegaze", me, listOf(pool[0]))
        assertEquals(1, ladder.rungs.size)
        assertFalse(ladder.rungs.single().why.startsWith("The core"), ladder.rungs.single().why)
    }

    @Test
    fun barelyOnGenreCandidatesAreSkippedWhenEnoughOthersExist() {
        val withNoise = pool + cand("Faint", mapOf("shoegaze" to 1, "dream pop" to 40), fans = 99_000_000)
        val ids = buildLadder("shoegaze", me, withNoise).rungs.map { it.candidate.id }
        assertFalse("Faint" in ids)
    }

    @Test
    fun whenNothingClearsTheBarTheBestOfTheRestIsUsed() {
        val faint = listOf(cand("Faint", mapOf("shoegaze" to 1, "dream pop" to 40)))
        assertEquals(listOf("Faint"), buildLadder("shoegaze", me, faint).rungs.map { it.candidate.id })
    }

    @Test
    fun candidatesWithoutTheTargetAreLeftOut() {
        val offTopic = listOf(cand("Metal", mapOf("metal" to 9)), cand("Junk", mapOf("seen live" to 9)))
        assertTrue(buildLadder("shoegaze", me, offTopic).isEmpty)
        assertTrue(buildLadder("shoegaze", me, emptyList()).isEmpty)
    }

    @Test
    fun aTargetThatIsNotAGenreGivesAnEmptyLadder() {
        assertTrue(buildLadder("seen live", me, pool).isEmpty)
        assertTrue(buildLadder("  ", me, pool).isEmpty)
        assertTrue(buildLadder("90s", me, pool).isEmpty)
    }

    @Test
    fun aTypedAliasIsNamedByItsCanonicalGenre() {
        val dnb = listOf(
            cand("A", mapOf("dnb" to 5, "electronic" to 5)),
            cand("B", mapOf("drum and bass" to 9, "jungle" to 1)),
        )
        val ladder = buildLadder("DnB", emptyList(), dnb)
        assertEquals("drum and bass", ladder.target)
        assertEquals(2, ladder.rungs.size)
    }

    @Test
    fun duplicateCandidateIdsAreCountedOnce() {
        val dup = pool + pool.first()
        val ids = buildLadder("shoegaze", me, dup).rungs.map { it.candidate.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun theLadderIsTheSameEveryTime() {
        val a = buildLadder("shoegaze", me, pool).rungs.map { it.candidate.id to it.why }
        val b = buildLadder("shoegaze", me, pool.reversed()).rungs.map { it.candidate.id to it.why }
        assertEquals(a, b)
    }

    @Test
    fun aRungWithNothingInCommonStillExplainsItself() {
        val farListener = listOf(artist("Mozart", listOf("classical")))
        val ladder = buildLadder("shoegaze", farListener, pool)
        // Cold, because nothing in the Library overlaps anything but the target: no anchor, but a sentence.
        assertTrue(ladder.rungs.all { it.why.isNotBlank() })
        assertTrue(ladder.rungs.all { it.anchorName == null })
    }
}
