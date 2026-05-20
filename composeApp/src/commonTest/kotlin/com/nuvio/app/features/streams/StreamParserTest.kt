package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamParserTest {

    @Test
    fun `parse keeps bingeGroup and explicit notWebReady`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://example.com/video.mp4",
                      "name": "1080p",
                      "behaviorHints": {
                        "bingeGroup": "addon-1080p",
                        "notWebReady": true
                      }
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        val stream = streams.single()
        assertEquals("addon-1080p", stream.behaviorHints.bingeGroup)
        assertTrue(stream.behaviorHints.notWebReady)
    }

    @Test
    fun `parse forces notWebReady when proxyHeaders are provided`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://example.com/video.m3u8",
                      "name": "Proxy stream",
                      "behaviorHints": {
                        "proxyHeaders": {
                          "request": {
                            "User-Agent": "Stremio"
                          }
                        }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        val stream = streams.single()
        assertTrue(stream.behaviorHints.notWebReady)
        val requestHeaders = stream.behaviorHints.proxyHeaders?.request
        assertNotNull(requestHeaders)
        assertEquals("Stremio", requestHeaders["User-Agent"])
    }

    @Test
    fun `parse keeps notWebReady false when no proxyHeaders and no hint`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://example.com/video.mp4",
                      "name": "Direct"
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        val stream = streams.single()
        assertFalse(stream.behaviorHints.notWebReady)
    }

    @Test
    fun `parse keeps proxy response headers`() {
        val streams = StreamParser.parse(
            payload =
                """
                {
                  "streams": [
                    {
                      "url": "https://example.com/video.mp4",
                      "behaviorHints": {
                        "proxyHeaders": {
                          "response": {
                            "content-type": "video/mp4",
                            "x-test": "ok"
                          }
                        }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            addonName = "Addon",
            addonId = "addon.id",
        )

        val responseHeaders = streams.single().behaviorHints.proxyHeaders?.response
        assertNotNull(responseHeaders)
        assertEquals("video/mp4", responseHeaders["content-type"])
        assertEquals("ok", responseHeaders["x-test"])
    }

}
