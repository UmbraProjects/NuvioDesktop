package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviouslyOnTest {
    private fun ep(s: Int, e: Int, watched: Long? = null, overview: String? = "Overview of $s-$e.") =
        PreviouslyOnEpisode(s, e, "Title $s-$e", overview, watched)

    private val source = TriviaSource(
        title = "Severance", year = "2022", synopsis = "Workers split their memories.", directors = emptyList(),
        cast = listOf("Adam Scott"), genres = listOf("Thriller"), studios = emptyList(), collection = null,
        awards = null, keywords = emptyList(), runtime = null, country = null, language = null,
    )

    @Test
    fun `recaps the episode just before`() {
        val t = previouslyOnTargetFor(listOf(ep(1, 1, 10), ep(1, 2, 20), ep(1, 3)), 1, 3)!!
        assertEquals(2, t.episode)
        assertFalse(t.previousSeason)
        assertFalse(t.skippedEpisodes)
        assertEquals("Overview of 1-1.", t.earlierOverview)
        assertEquals("LAST TIME", t.lastTimeHeading())
    }

    @Test
    fun `a premiere recaps the previous season`() {
        val t = previouslyOnTargetFor(listOf(ep(1, 1, 1), ep(1, 2, 2), ep(2, 1)), 2, 1)!!
        assertEquals(1 to 2, t.season to t.episode)
        assertTrue(t.previousSeason)
        assertEquals("LAST SEASON", t.lastTimeHeading())
    }

    @Test
    fun `skipped episodes recap the last one watched and say so`() {
        val t = previouslyOnTargetFor(listOf(ep(1, 1, 1), ep(1, 2), ep(1, 3), ep(1, 4)), 1, 4)!!
        assertEquals(1, t.episode)
        assertTrue(t.skippedEpisodes)
        assertEquals("LAST WATCHED · S1E1", t.lastTimeHeading())
    }

    @Test
    fun `nothing watched before gives no target`() {
        assertNull(previouslyOnTargetFor(listOf(ep(1, 1), ep(1, 2)), 1, 2))
        assertNull(previouslyOnTargetFor(listOf(ep(1, 1, 1)), 1, 1))
    }

    @Test
    fun `specials are ignored on both sides`() {
        assertNull(previouslyOnTargetFor(listOf(ep(0, 1, 5), ep(1, 1)), 1, 1))
        assertNull(previouslyOnTargetFor(listOf(ep(1, 1, 5), ep(0, 1)), 0, 1))
        assertEquals(1, previouslyOnTargetFor(listOf(ep(0, 1, 5), ep(1, 1, 3), ep(1, 2)), 1, 2)!!.episode)
    }

    @Test
    fun `later episodes are never recapped`() {
        // Watched out of order: S1E5 is done but the viewer is starting S1E3.
        val t = previouslyOnTargetFor(listOf(ep(1, 1, 1), ep(1, 2, 2), ep(1, 3), ep(1, 5, 9)), 1, 3)!!
        assertEquals(2, t.episode)
    }

    @Test
    fun `break rule`() {
        val day = 86_400_000L
        assertTrue(previouslyOnGapPassed(0L, 5 * day, breakOnly = true, gapMs = 3 * day))
        assertFalse(previouslyOnGapPassed(4 * day, 5 * day, breakOnly = true, gapMs = 3 * day))
        assertTrue(previouslyOnGapPassed(4 * day, 5 * day, breakOnly = false, gapMs = 3 * day))
        assertTrue(previouslyOnGapPassed(null, 5 * day, breakOnly = true, gapMs = 3 * day))
    }

    @Test
    fun `prompt carries the overview and the spoiler rule but not the next episode`() {
        val target = previouslyOnTargetFor(listOf(ep(1, 1, 1), ep(1, 2, 2, "Mark finds a door."), ep(1, 3, null, "SECRETNEXT")), 1, 3)!!
        val prompt = buildPreviouslyOnPrompt(source, target, "en")
        assertTrue("Mark finds a door." in prompt.user)
        assertTrue("S1E2" in prompt.user)
        assertFalse("SECRETNEXT" in prompt.user)
        assertTrue("SPOILER RULE" in prompt.system)
    }

    @Test
    fun `prompt says when there is no overview`() {
        val target = previouslyOnTargetFor(listOf(ep(1, 1, 1, overview = null), ep(1, 2)), 1, 2)!!
        assertTrue("(none given)" in buildPreviouslyOnPrompt(source, target, "en").user)
    }

    @Test
    fun `parser orders cards, caps them and drops weak or long ones`() {
        val long = "x".repeat(250)
        val reply = """
            {"cards":[
              {"kind":"keep_in_mind","text":"Helly distrusts the company.","confidence":0.9},
              {"kind":"where_it_ended","text":"Mark finds a hidden door.","confidence":0.95},
              {"kind":"last_time","text":"Mark meets Helly at work.","confidence":0.9},
              {"kind":"last_time","text":"A guess nobody is sure of.","confidence":0.4},
              {"kind":"last_time","text":"$long","confidence":0.9},
              {"kind":"mystery","text":"Unknown kind.","confidence":0.9}
            ],
            "quiz":[{"q":"Who does Mark meet?","options":["Helly","Irving","Dylan","Burt"],"answer":"Helly","explain":"Said on the first card.","confidence":0.9}]}
        """.trimIndent()
        val pack = parsePreviouslyOnPack(reply, "LAST SEASON")
        assertEquals(listOf("LAST SEASON", "WHERE IT ENDED", "KEEP IN MIND"), pack.slides.map { it.heading })
        assertEquals(1, pack.questions.size)
    }

    @Test
    fun `parser survives rubbish`() {
        assertTrue(parsePreviouslyOnPack("not json").isEmpty)
        assertTrue(parsePreviouslyOnPack("""{"cards":[],"quiz":[]}""").isEmpty)
    }

    @Test
    fun `no-key cards come from the overview`() {
        val target = previouslyOnTargetFor(
            listOf(ep(1, 1, 1, "Mark meets Helly on her first day. He finds a door that should not exist."), ep(1, 2)), 1, 2,
        )!!
        val pack = previouslyOnFromOverview(target, source)!!
        assertEquals(2, pack.slides.size)
        assertEquals("LAST TIME", pack.slides[0].heading)
        assertEquals("WHERE IT ENDED", pack.slides[1].heading)
        assertEquals(TriviaOrigin.Metadata, pack.origin)
        assertTrue(pack.questions.isEmpty())
    }

    @Test
    fun `no overview and no key gives nothing`() {
        val target = previouslyOnTargetFor(listOf(ep(1, 1, 1, overview = null), ep(1, 2)), 1, 2)!!
        assertNull(previouslyOnFromOverview(target, source))
    }

    @Test
    fun `cache fingerprint changes with the overview`() {
        val a = previouslyOnTargetFor(listOf(ep(1, 1, 1, "One."), ep(1, 2)), 1, 2)!!
        val b = previouslyOnTargetFor(listOf(ep(1, 1, 1, "Two."), ep(1, 2)), 1, 2)!!
        assertNotNull(a.fingerprint(source))
        assertTrue(a.fingerprint(source) != b.fingerprint(source))
    }
}
