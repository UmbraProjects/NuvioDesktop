package com.nuvio.app.features.trailer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * fork:ytmusic-login
 *
 * A video YouTube refuses to serve anonymously (LOGIN_REQUIRED) is retried once as the signed-in
 * account. Every other request stays anonymous, so the account's cookies are only sent when a video
 * needs them.
 */
class InAppYouTubeExtractorAccountTest {
    private class GatedYouTube {
        val playerHeaders = mutableListOf<Map<String, String>>()

        suspend fun request(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMillis: Long,
        ): TrailerRequestResponse = when {
            url.startsWith("https://www.youtube.com/watch") -> response(url, WATCH_PAGE)
            url.startsWith("https://www.youtube.com/youtubei/v1/player") -> {
                playerHeaders += headers
                // Only a request carrying the account's cookie is allowed to play this video.
                response(url, if (headers["cookie"] != null) PLAYABLE else LOGIN_REQUIRED)
            }
            else -> TrailerRequestResponse(ok = false, status = 404, statusText = "HTTP 404", url = url, body = "")
        }

        private fun response(url: String, body: String) = TrailerRequestResponse(
            ok = true,
            status = 200,
            statusText = "HTTP 200",
            url = url,
            body = body,
        )
    }

    @Test
    fun loginGatedVideoStaysUnavailableWithoutAnAccount(): Unit = runBlocking {
        val youtube = GatedYouTube()
        val extractor = InAppYouTubeExtractor(performRequest = youtube::request, nowMs = { 1_000_000L })

        val result = extractor.extractPlaybackSource(TRAILER_URL)

        val unavailable = assertIs<TrailerResolution.Unavailable>(result)
        assertEquals(TrailerUnavailableReason.AGE_RESTRICTED, unavailable.reason)
        assertTrue(youtube.playerHeaders.none { "cookie" in it }, "no account, so no cookie: ${youtube.playerHeaders}")
    }

    @Test
    fun loginGatedVideoIsRetriedAsTheSignedInAccount(): Unit = runBlocking {
        val youtube = GatedYouTube()
        val extractor = InAppYouTubeExtractor(performRequest = youtube::request, nowMs = { 1_000_000L }).apply {
            accountHeaders = { origin ->
                assertEquals("https://www.youtube.com", origin)
                mapOf("cookie" to "SAPISID=abc", "authorization" to "SAPISIDHASH 1_deadbeef")
            }
        }

        val result = extractor.extractPlaybackSource(TRAILER_URL)

        val available = assertIs<TrailerResolution.Available>(result)
        assertEquals("https://media/video", available.source.videoUrl)

        // The anonymous pass goes first and never carries the account; only the retry does.
        val withCookie = youtube.playerHeaders.indexOfFirst { "cookie" in it }
        assertTrue(withCookie > 0, "anonymous requests must come first: ${youtube.playerHeaders}")
        assertTrue(youtube.playerHeaders.take(withCookie).none { "cookie" in it })
        assertEquals("SAPISIDHASH 1_deadbeef", youtube.playerHeaders[withCookie]["authorization"])
    }

    @Test
    fun playableVideoNeverSendsTheAccount(): Unit = runBlocking {
        val sent = mutableListOf<Map<String, String>>()
        val extractor = InAppYouTubeExtractor(
            performRequest = { url, _, headers, _, _ ->
                if ("youtubei/v1/player" in url) sent += headers
                TrailerRequestResponse(
                    ok = true,
                    status = 200,
                    statusText = "HTTP 200",
                    url = url,
                    body = if ("youtubei/v1/player" in url) PLAYABLE else WATCH_PAGE,
                )
            },
            nowMs = { 1_000_000L },
        ).apply { accountHeaders = { mapOf("cookie" to "SAPISID=abc") } }

        assertIs<TrailerResolution.Available>(extractor.extractPlaybackSource(TRAILER_URL))
        assertTrue(sent.isNotEmpty())
        sent.forEach { assertNull(it["cookie"], "account sent for a video that did not need it") }
    }

    @Test
    fun otherAgeGateWordingsAreAlsoRetriedAsTheAccount(): Unit = runBlocking {
        val gates = listOf(
            """{"playabilityStatus":{"status":"AGE_CHECK_REQUIRED"}}""",
            """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"This video may be inappropriate for some users."}}""",
        )
        for (gate in gates) {
            var accountRequests = 0
            val extractor = InAppYouTubeExtractor(
                performRequest = { url, _, headers, _, _ ->
                    val isPlayer = "youtubei/v1/player" in url
                    if (isPlayer && "cookie" in headers) accountRequests++
                    TrailerRequestResponse(
                        ok = true,
                        status = 200,
                        statusText = "HTTP 200",
                        url = url,
                        body = when {
                            !isPlayer -> WATCH_PAGE
                            "cookie" in headers -> PLAYABLE
                            else -> gate
                        },
                    )
                },
                nowMs = { 1_000_000L },
            ).apply { accountHeaders = { mapOf("cookie" to "SAPISID=abc") } }

            assertIs<TrailerResolution.Available>(extractor.extractPlaybackSource(TRAILER_URL), gate)
            assertTrue(accountRequests > 0, "no account retry for: $gate")
        }
    }

    private companion object {
        const val TRAILER_URL = "https://www.youtube.com/watch?v=GXEocd6WZog"

        const val WATCH_PAGE = """"INNERTUBE_API_KEY":"PAGEKEY","VISITOR_DATA":"VD2""""

        const val LOGIN_REQUIRED =
            """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"}}"""

        val PLAYABLE = """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
              {"url":"https://media/video","mimeType":"video/mp4; codecs=\"avc1\"","height":1080,"fps":24,"bitrate":4000000},
              {"url":"https://media/audio","mimeType":"audio/mp4; codecs=\"mp4a\"","bitrate":128000,"audioSampleRate":48000}
            ]}}
        """.trimIndent()
    }
}
