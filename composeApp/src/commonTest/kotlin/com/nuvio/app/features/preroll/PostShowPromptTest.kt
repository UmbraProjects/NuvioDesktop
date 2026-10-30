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
}
