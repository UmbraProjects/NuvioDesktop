package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderDebugTest {
    private fun cand(id: String, votes: Map<String, Int>, fans: Long? = null) =
        LadderCandidate(id = id, name = id, votes = votes, fans = fans)

    private val pool = listOf(
        cand("A", mapOf("shoegaze" to 3, "dream pop" to 7)),
        cand("B", mapOf("shoegaze" to 5, "dream pop" to 5)),
        cand("C", mapOf("shoegaze" to 7, "dream pop" to 3)),
        cand("D", mapOf("shoegaze" to 9, "dream pop" to 1)),
        cand("E", mapOf("shoegaze" to 10)),
        cand("Rock", mapOf("rock" to 10)),
    )

    @Test
    fun moreTagVotesWinATieOnPurityWhenThereAreNoFans() {
        // Since purity counts the evidence (PURITY_PRIOR_VOTES), 1 of 1 and 80 of 80 no longer tie. These two do:
        // 1 / (4 + 3) and 2 / (11 + 3) are both 1/7. The id order would pick "a-thin"; the evidence picks the other.
        val twins = listOf(
            cand("a-thin", mapOf("shoegaze" to 1, "dream pop" to 3)),
            cand("z-solid", mapOf("shoegaze" to 2, "dream pop" to 9)),
        )
        val ladder = buildLadder("shoegaze", emptyList(), twins, rungs = 1)
        assertEquals("z-solid", ladder.rungs.single().candidate.id)
    }

    @Test
    fun fansStillComeBeforeTagVotes() {
        val twins = listOf(
            // Equal adjusted purity (2 / (11 + 3) = 1 / (4 + 3)), so only the fans bonus tells them apart.
            cand("a-many-votes", mapOf("shoegaze" to 2, "dream pop" to 9), fans = 10),
            cand("z-many-fans", mapOf("shoegaze" to 1, "dream pop" to 3), fans = 9_000_000),
        )
        assertEquals("z-many-fans", buildLadder("shoegaze", emptyList(), twins, rungs = 1).rungs.single().candidate.id)
    }

    @Test
    fun aBuiltLadderCarriesThePoolAndAPickForEveryRung() {
        val ladder = buildLadder("shoegaze", emptyList(), pool, rungs = 3)
        val stats = assertNotNull(ladder.pool)
        assertEquals(6, stats.candidates)
        assertEquals(1, stats.offGenre)
        assertEquals(5, stats.scored)
        assertEquals(0.3, stats.purityMin)
        assertEquals(1.0, stats.purityMax)
        assertEquals(3, ladder.rungs.size)
        assertEquals(setOf(1, 2, 3), ladder.rungs.map { assertNotNull(it.pick).slot }.toSet())
        ladder.rungs.forEach { assertTrue(it.pick!!.tagVotes > 0) }
    }

    @Test
    fun theDebugTextNamesEveryRungAndWhoItBeat() {
        val ladder = buildLadder("shoegaze", emptyList(), pool, rungs = 3)
        val text = ladderDebugLines(ladder, "Into shoegaze").joinToString("\n")
        assertTrue("Ladder: Into shoegaze" in text)
        assertTrue("Pool: 6 candidates" in text)
        ladder.rungs.forEach { assertTrue("${it.step}. ${it.candidate.name} | purity" in text) }
        assertTrue("beat:" in text)
        assertTrue("Weights:" in text)
    }

    @Test
    fun aRungOnVeryFewVotesIsFlagged() {
        val ladder = buildLadder("shoegaze", emptyList(), listOf(cand("Thin", mapOf("shoegaze" to 1)), cand("Solid", mapOf("shoegaze" to 40, "dream pop" to 20))), rungs = 2)
        val lines = ladderDebugLines(ladder)
        assertTrue(lines.any { "Thin" in it && "THIN TAGS" in it })
        assertTrue(lines.none { "Solid" in it && "THIN TAGS" in it })
    }

    @Test
    fun aKeptLadderSaysItHasNoBuildDetails() {
        val kept = Ladder("shoegaze", listOf(LadderRung(1, cand("A", emptyMap()), 0.5, 0.4, null, "why")), coldStart = false)
        assertNull(kept.pool)
        val text = ladderDebugLines(kept).joinToString("\n")
        assertTrue("No build details: this ladder was kept from an earlier build." in text)
        assertTrue("no build details (a marked or kept rung)" in text)
    }

    @Test
    fun anEmptyLadderStillPrintsItsHeader() {
        val lines = ladderDebugLines(buildLadder("shoegaze", emptyList(), emptyList()))
        assertTrue(lines.first().startsWith("Ladder"))
        assertTrue("No rungs." in lines)
    }
}
