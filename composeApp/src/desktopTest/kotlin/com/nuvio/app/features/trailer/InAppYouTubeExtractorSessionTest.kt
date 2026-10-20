package com.nuvio.app.features.trailer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The watch page is only scraped for a session (`INNERTUBE_API_KEY` + `visitorData`). A Linux
 * user's log showed it answering 429 on every attempt, and since nothing was cached yet each
 * trailer failed before the player API was ever called. These cover the keyless fallback and
 * the backoff that stops the hero rotation re-requesting a throttled page.
 */
class InAppYouTubeExtractorSessionTest {
    private class FakeYouTube(var watchStatus: Int, var watchBody: String = "") {
        var nowMs = 1_000_000L
        var playerDown = false
        val calls = mutableListOf<String>()

        val watchPageHits get() = calls.count { it.startsWith("GET https://www.youtube.com/watch") }

        suspend fun request(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMillis: Long,
        ): TrailerRequestResponse {
            calls += "$method $url"
            return when {
                url.startsWith("https://www.youtube.com/watch") -> response(url, watchStatus, watchBody)
                url.startsWith("https://www.youtube.com/youtubei/v1/player") ->
                    if (playerDown) {
                        response(url, 500, "")
                    } else if (headers["x-goog-visitor-id"] == null) {
                        // What YouTube really does without a session: refuse, but issue one.
                        response(url, 200, """{"responseContext":{"visitorData":"VD1"},"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""")
                    } else {
                        response(url, 200, PLAYABLE)
                    }
                else -> response(url, 404, "")
            }
        }

        private fun response(url: String, status: Int, body: String) = TrailerRequestResponse(
            ok = status in 200..299,
            status = status,
            statusText = "HTTP $status",
            url = url,
            body = body,
        )

        fun extractor() = InAppYouTubeExtractor(::request) { nowMs }
    }

    @Test
    fun throttledWatchPageFallsBackToKeylessSession(): Unit = runBlocking {
        val youtube = FakeYouTube(watchStatus = 429)

        val result = youtube.extractor().extractPlaybackSource(TRAILER_URL)

        val available = assertIs<TrailerResolution.Available>(result)
        assertEquals("https://media/video", available.source.videoUrl)
        assertTrue(
            youtube.calls.filter { "youtubei/v1/player" in it }.none { "key=" in it },
            "keyless player requests must not carry a key: ${youtube.calls}",
        )
    }

    @Test
    fun watchPageWithoutApiKeyFallsBackToKeylessSession(): Unit = runBlocking {
        // e.g. a consent interstitial: 200, but nothing to scrape.
        val youtube = FakeYouTube(watchStatus = 200, watchBody = "<html>consent</html>")

        val result = youtube.extractor().extractPlaybackSource(TRAILER_URL)

        assertIs<TrailerResolution.Available>(result)
    }

    @Test
    fun throttledWatchPageIsNotRequestedAgainUntilBackoffExpires(): Unit = runBlocking {
        val youtube = FakeYouTube(watchStatus = 429)
        val extractor = youtube.extractor()

        // First attempt fails outright, so nothing is cached and the next one would normally
        // go straight back to the page.
        youtube.playerDown = true
        assertIs<TrailerResolution.Unavailable>(extractor.extractPlaybackSource(TRAILER_URL))
        assertEquals(1, youtube.watchPageHits)

        youtube.playerDown = false
        youtube.nowMs += WATCH_PAGE_BACKOFF_MS - 1
        assertIs<TrailerResolution.Available>(extractor.extractPlaybackSource(TRAILER_URL))
        assertEquals(1, youtube.watchPageHits, "page re-requested during backoff: ${youtube.calls}")

        // Once the keyless session ages out, the page is tried again and its key preferred.
        youtube.nowMs += 3L * 60L * 60L * 1000L + 1
        youtube.watchStatus = 200
        youtube.watchBody = """"INNERTUBE_API_KEY":"PAGEKEY","VISITOR_DATA":"VD2""""
        extractor.extractPlaybackSource(TRAILER_URL)
        assertEquals(2, youtube.watchPageHits)
        assertTrue(youtube.calls.last { "youtubei/v1/player" in it }.contains("key=PAGEKEY"))
    }

    @Test
    fun workingWatchPageStillUsesItsKey(): Unit = runBlocking {
        val youtube = FakeYouTube(
            watchStatus = 200,
            watchBody = """"INNERTUBE_API_KEY":"PAGEKEY","VISITOR_DATA":"VD2"""",
        )

        assertIs<TrailerResolution.Available>(youtube.extractor().extractPlaybackSource(TRAILER_URL))
        assertTrue(youtube.calls.filter { "youtubei/v1/player" in it }.all { "key=PAGEKEY" in it })
    }

    private companion object {
        const val TRAILER_URL = "https://www.youtube.com/watch?v=GXEocd6WZog"

        val PLAYABLE = """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
              {"url":"https://media/video","mimeType":"video/mp4; codecs=\"avc1\"","height":1080,"fps":24,"bitrate":4000000},
              {"url":"https://media/audio","mimeType":"audio/mp4; codecs=\"mp4a\"","bitrate":128000,"audioSampleRate":48000}
            ]}}
        """.trimIndent()
    }
}
