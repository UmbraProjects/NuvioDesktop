package com.nuvio.app.features.usenet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class UsenetLiveStatsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parseMap(payload: String): Map<String, String> =
        json.parseToJsonElement(payload).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }

    @Test fun `parses engine diagnostics and formats multi-provider stats`() {
        val root = json.parseToJsonElement(
            """
            {
              "filledArticles": 2,
              "providers": [
                {"server": 0, "articles": 98, "bytes": 70000000, "missing": 2, "errors": 0, "ttfbMs": 48.4},
                {"server": 1, "articles": 2, "bytes": 1400000, "missing": 0, "errors": 1, "ttfbMs": 360.0}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val live = UsenetLiveStats.fromDiagnostics(root, providerNames = listOf("Easynews", "Eweka"))
        assertEquals(100L, live.articles)
        assertEquals(2L, live.missing)
        assertEquals(1L, live.errors)
        assertEquals(98.0, live.completion!!, 0.01)
        assertEquals(48.4, live.ttfbMs!!, 0.01)

        val fields = parseMap(formatUsenetMpvDiagnostics(live, lastMissing = 2, lastErrors = 0))
        assertEquals("Easynews (98%) • Eweka (100%)", fields["providers"])
        assertEquals("98.0% (2 missing, 2 filled)", fields["articles"])
        assertEquals("normal", fields["articles_sev"])
        assertEquals("48 ms", fields["ttfb"])
        assertEquals("normal", fields["ttfb_sev"])
        assertEquals("1 · Eweka", fields["nntp_err"])
        assertEquals("warn", fields["nntp_err_sev"])
        assertEquals("provider errors", fields["stall_hint"])
        assertNull(fields["failure"])
    }

    @Test fun `flags slow TTFB, unrecovered missing articles, and permanent stream failure`() {
        val root = json.parseToJsonElement(
            """
            {
              "filledArticles": 0,
              "streamFailure": "missing-articles",
              "providers": [
                {"server": 0, "articles": 10, "bytes": 5000000, "missing": 5, "errors": 0, "ttfbMs": 420.0}
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val live = UsenetLiveStats.fromDiagnostics(root, providerNames = listOf("News"))
        val fields = parseMap(formatUsenetMpvDiagnostics(live, lastMissing = 3, lastErrors = 0))
        assertEquals("News", fields["providers"])
        assertEquals("66.7% (5 missing)", fields["articles"])
        assertEquals("danger", fields["articles_sev"])
        assertEquals("420 ms", fields["ttfb"])
        assertEquals("warn", fields["ttfb_sev"])
        assertEquals("missing articles", fields["nntp_err"])
        assertEquals("danger", fields["nntp_err_sev"])
        assertEquals("missing articles", fields["failure"])
        assertEquals("missing articles", fields["stall_hint"])
    }
}
