package com.nuvio.app.features.tmdb

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TmdbHttpGateTest {

    @Test
    fun `requests default to the interactive lane`() = runBlocking {
        // Nothing opts in to Interactive by name, so the default is what almost every request in
        // the app gets — if it ever became Background, the hero would queue behind Discover.
        assertNull(currentCoroutineContext()[TmdbLane])
    }

    @Test
    fun `the background lane travels with the coroutine context`() = runBlocking {
        withContext(TmdbBackgroundLane) {
            assertEquals(TmdbRequestLane.Background, currentCoroutineContext()[TmdbLane]?.lane)

            // The point of an element rather than a parameter: it survives the suspend calls in
            // between, so `DiscoverRecommendationsRepository.build` tagging the job once covers
            // the seed resolution, the row families and the prune pass beneath it.
            assertEquals(TmdbRequestLane.Background, nestedLane())
        }
    }

    private suspend fun nestedLane(): TmdbRequestLane? = currentCoroutineContext()[TmdbLane]?.lane

    @Test
    fun `the lanes have separate budgets`() {
        // Two queues only stop one starving the other if neither can consume the other's permits.
        // Sizes are judgement calls; that they are independent is not.
        assertEquals(12, INTERACTIVE_CONCURRENCY)
        assertEquals(8, BACKGROUND_CONCURRENCY)
    }

    @Test
    fun `logging a URL does not log the API key`() {
        val redacted = redactApiKey("https://api.themoviedb.org/3/movie/550?api_key=abc123&language=en-US")
        assertEquals("https://api.themoviedb.org/3/movie/550?api_key=***&language=en-US", redacted)
        assertFalse("abc123" in redacted)
    }

    @Test
    fun `redaction finds the key wherever it sits in the query`() {
        // `buildTmdbUrl` puts it first today, which is exactly the reason not to depend on that.
        val redacted = redactApiKey("https://api.themoviedb.org/3/search/movie?query=akira&api_key=abc123")
        assertEquals("https://api.themoviedb.org/3/search/movie?query=akira&api_key=***", redacted)
        assertFalse("abc123" in redacted)
    }

    @Test
    fun `a URL without a key is left alone`() {
        val url = "https://api.themoviedb.org/3/movie/550?language=en-US"
        assertEquals(url, redactApiKey(url))
    }
}
