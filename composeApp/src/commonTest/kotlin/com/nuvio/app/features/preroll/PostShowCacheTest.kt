package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PostShowCacheTest {
    @Test
    fun `key carries the prompt version and everything an answer depends on`() {
        val base = postShowCacheKey("tt1", PostShowScope.Film, "m", "en", "f")
        assertTrue(base.startsWith("postshow|p$POST_SHOW_PROMPT_VERSION|"))
        assertNotEquals(base, postShowCacheKey("tt2", PostShowScope.Film, "m", "en", "f"))
        assertNotEquals(base, postShowCacheKey("tt1", PostShowScope.Film, "m2", "en", "f"))
        assertNotEquals(base, postShowCacheKey("tt1", PostShowScope.Film, "m", "fr", "f"))
        assertNotEquals(base, postShowCacheKey("tt1", PostShowScope.Film, "m", "en", "g"))
    }

    @Test
    fun `each season and the series finale have their own entry`() {
        val s1 = postShowCacheKey("tt1", PostShowScope.SeasonFinale(1, 10, null), "m", "en", "f")
        val s2 = postShowCacheKey("tt1", PostShowScope.SeasonFinale(2, 10, null), "m", "en", "f")
        val series = postShowCacheKey("tt1", PostShowScope.SeriesFinale(2, 10, null), "m", "en", "f")
        assertEquals(3, setOf(s1, s2, series).size)
        // The episode's title is for the prompt only: renaming it must not orphan a paid-for entry.
        assertEquals(s1, postShowCacheKey("tt1", PostShowScope.SeasonFinale(1, 10, "Other"), "m", "en", "f"))
    }

    @Test
    fun `key never collides with a trivia key`() {
        val trivia = triviaCacheKey("tt1", "m", "en", "f", TriviaMode.ModelKnowledge)
        assertNotEquals(trivia, postShowCacheKey("tt1", PostShowScope.Film, "m", "en", "f"))
    }

    @Test
    fun `file round trips and an old shape with missing fields still loads`() {
        val pack = PostShowPack(
            cards = listOf(PostShowCard(PostShowCardKind.Ending, "It ends.")),
            questions = listOf(TriviaQuestion("Q?", listOf("a", "b", "c"), 1)),
        )
        val file = PostShowCacheFile(entries = mapOf("k" to PostShowCacheEntry(pack, 5L, "m")))
        assertEquals(file, PostShowCache.decodeForTest(PostShowCache.encodeForTest(file)))
        assertEquals(PostShowCacheFile.VERSION, PostShowCache.decodeForTest("{}").version)
    }
}
