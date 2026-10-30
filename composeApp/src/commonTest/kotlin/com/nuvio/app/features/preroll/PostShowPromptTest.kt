package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostShowPromptTest {
    private val source = TriviaSource(
        title = "Inception", year = "2010", synopsis = "A thief enters dreams.",
        directors = listOf("Christopher Nolan"), cast = listOf("Leonardo DiCaprio"),
        genres = listOf("Sci-Fi"), studios = emptyList(), collection = null, awards = null,
        keywords = emptyList(), runtime = "148 min", country = null, language = null,
    )

    @Test
    fun `names the film and the language and allows plot`() {
        val p = buildPostShowPrompt(source, "en-GB")
        assertTrue("Inception (2010)" in p.user)
        assertTrue("en-GB" in p.user)
        assertTrue("JUST WATCHED" in p.system)
        assertFalse("NEVER reveal plot" in p.system)
    }

    @Test
    fun `forbids invention and timestamps`() {
        val p = buildPostShowPrompt(source, "en").system
        assertTrue("Never invent" in p)
        assertTrue("timestamps" in p)
    }

    @Test
    fun `asks for every card kind the parser accepts`() {
        val p = buildPostShowPrompt(source, "en").system
        listOf("ending", "easter_egg", "watch_again", "motivation", "connection", "behind_the_scenes")
            .forEach { assertTrue("\"$it\"" in p, it) }
    }

    @Test
    fun `a season finale is fenced to its season`() {
        val p = buildPostShowPrompt(source, "en", PostShowScope.SeasonFinale(3, 10, "The End"))
        assertTrue("SPOILER GATE" in p.system)
        assertTrue("seasons 1 to 3" in p.system)
        assertTrue("after season 3" in p.system)
        assertTrue("finale of season 3" in p.system)
        assertTrue("S3E10 \"The End\" (season finale)" in p.user)
        assertTrue(p.user.startsWith("Series: Inception"))
    }

    @Test
    fun `a series finale may use the whole show`() {
        val p = buildPostShowPrompt(source, "en", PostShowScope.SeriesFinale(5, 8, null))
        assertFalse("SPOILER GATE" in p.system)
        assertTrue("whole series may be discussed" in p.system)
        assertTrue("S5E8 (series finale)" in p.user)
    }

    @Test
    fun `finale prompts keep the shared rules and card kinds`() {
        val p = buildPostShowPrompt(source, "en", PostShowScope.SeasonFinale(1, 1, null)).system
        assertTrue("Never invent" in p)
        listOf("ending", "easter_egg", "watch_again", "motivation", "connection", "behind_the_scenes")
            .forEach { assertTrue("\"$it\"" in p, it) }
        assertFalse("watched the film" in p)
    }
}
