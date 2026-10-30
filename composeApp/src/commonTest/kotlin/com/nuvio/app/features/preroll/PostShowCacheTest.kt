package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PostShowCacheTest {
    @Test
    fun `key carries the prompt version and everything an answer depends on`() {
        val base = postShowCacheKey("tt1", "m", "en", "f")
        assertTrue(base.startsWith("postshow|p$POST_SHOW_PROMPT_VERSION|"))
        assertNotEquals(base, postShowCacheKey("tt2", "m", "en", "f"))
        assertNotEquals(base, postShowCacheKey("tt1", "m2", "en", "f"))
        assertNotEquals(base, postShowCacheKey("tt1", "m", "fr", "f"))
        assertNotEquals(base, postShowCacheKey("tt1", "m", "en", "g"))
    }

    @Test
    fun `key never collides with a trivia key`() {
        val trivia = triviaCacheKey("tt1", "m", "en", "f", TriviaMode.ModelKnowledge)
        assertNotEquals(trivia, postShowCacheKey("tt1", "m", "en", "f"))
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
