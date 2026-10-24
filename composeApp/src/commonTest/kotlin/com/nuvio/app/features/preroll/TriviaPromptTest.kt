package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TriviaPromptTest {
    private fun source(
        title: String = "Parasite",
        year: String? = "2019",
        directors: List<String> = listOf("Bong Joon-ho"),
    ) = TriviaSource(
        title = title, year = year, synopsis = "A poor family schemes its way into a wealthy household.",
        directors = directors, cast = listOf("Song Kang-ho"), genres = listOf("Thriller"),
        studios = listOf("Barunson E&A"), collection = null, awards = null, keywords = emptyList(),
        runtime = "132 min", country = "South Korea", language = "ko",
    )

    @Test
    fun `prompt carries the metadata the model must ground on`() {
        val prompt = buildTriviaPrompt(source(), "en", TriviaMode.ModelKnowledge)
        assertTrue("Parasite (2019)" in prompt.user)
        assertTrue("Bong Joon-ho" in prompt.user)
        assertTrue("\"en\"" in prompt.user)
    }

    @Test
    fun `both modes forbid plot and invention`() {
        for (mode in TriviaMode.entries) {
            val system = buildTriviaPrompt(source(), "en", mode).system
            assertTrue("NEVER reveal plot" in system, "mode $mode")
            assertTrue("Never invent" in system, "mode $mode")
        }
    }

    @Test
    fun `metadata only mode does not invite the model's own memory`() {
        val strict = buildTriviaPrompt(source(), "en", TriviaMode.MetadataOnly).system
        val open = buildTriviaPrompt(source(), "en", TriviaMode.ModelKnowledge).system
        assertTrue("ONLY source" in strict)
        assertFalse("reliably know" in strict)
        assertTrue("reliably know" in open)
    }

    @Test
    fun `a bare title is not enough to ask`() {
        assertFalse(source(year = null, directors = emptyList()).copy(cast = emptyList()).isUsable)
        assertTrue(source().isUsable)
    }

    @Test
    fun `fingerprint tracks the inputs that change the answer`() {
        assertEquals(source().fingerprint(), source().fingerprint())
        assertNotEquals(source().fingerprint(), source(year = "2020").fingerprint())
    }

    @Test
    fun `cache key separates mode, model and prompt version`() {
        fun key(model: String = "m", mode: TriviaMode = TriviaMode.ModelKnowledge) =
            triviaCacheKey("tt6751668", model, "en", "fp", mode)
        assertEquals(key(), key())
        assertNotEquals(key(), key(model = "other"))
        assertNotEquals(key(), key(mode = TriviaMode.MetadataOnly))
        assertTrue("p$TRIVIA_PROMPT_VERSION" in key())
    }
}
