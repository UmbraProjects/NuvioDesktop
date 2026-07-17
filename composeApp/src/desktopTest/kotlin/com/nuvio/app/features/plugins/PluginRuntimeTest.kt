package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginRuntimeTest {
    @Test
    fun pluginRequestHeadersDropJavaRestrictedAndCompressionHeaders() {
        val sanitized = sanitizePluginRequestHeaders(
            linkedMapOf(
                "Connection" to "keep-alive",
                "HOST" to "example.com",
                "Accept-Encoding" to "gzip, deflate",
                "Content-Length" to "42",
                "Referer" to "https://example.com/title",
                "X-Custom" to "value",
            ),
        )

        assertEquals(
            mapOf(
                "Referer" to "https://example.com/title",
                "X-Custom" to "value",
            ),
            sanitized,
        )
    }

    @Test
    fun malformedResultArraySalvagesCompleteEntries() {
        val raw = """[{"url":"https://one","title":"One"},{"url":"https://two","title":"Two"},{"url":"broken""""

        val results = PluginRuntime.parseJsonResults(raw, scraperId = "test")

        assertEquals(listOf("https://one", "https://two"), results.map { it.url })
    }

    @Test
    fun resultPayloadNormalizationRemovesBridgeNoiseAndEscapesControlCharacters() {
        val normalized = normalizePluginJsonPayload(
            "prefix\u0000 [{\"url\":\"https://example.com\",\"title\":\"Line\nBreak\"}] suffix",
        )

        assertTrue(normalized.startsWith("["))
        assertTrue(normalized.endsWith("]"))
        assertTrue("\\n" in normalized)
        assertFalse('\u0000' in normalized)
        assertEquals("https://example.com", PluginRuntime.parseJsonResults(normalized).single().url)
    }

    @Test
    fun pluginTextRepairsUtf8MojibakeWithoutChangingCorrectSymbols() {
        val broken = "ðŸŽ¦ Your Friends â†’ S01E06 â€¢ ðŸŽž Dual-Audio | ☁ WEB-DL"

        assertEquals(
            "🎦 Your Friends → S01E06 • 🎞 Dual-Audio | ☁ WEB-DL",
            repairPluginTextEncoding(broken),
        )
    }

    @Test
    fun pluginTextLeavesCorrectInternationalTextAlone() {
        assertEquals("Amélie • 日本語 • ☁", repairPluginTextEncoding("Amélie • 日本語 • ☁"))
    }

    @Test
    fun pluginTextRepairsRepeatedMojibake() {
        val original = "🔊 DDP 5.1 • 🔥 Atmos | 👷 Worker"
        fun misdecodeUtf8AsWindows1252(text: String): String =
            text.encodeToByteArray().joinToString("") { byte ->
                when (val value = byte.toInt() and 0xff) {
                    0x80 -> "€"
                    0x8A -> "Š"
                    0x91 -> "‘"
                    0x94 -> "”"
                    0x9F -> "Ÿ"
                    else -> value.toChar().toString()
                }
            }
        val encodedOnce = misdecodeUtf8AsWindows1252(original)
        val encodedTwice = misdecodeUtf8AsWindows1252(encodedOnce)

        assertEquals(original, repairPluginTextEncoding(encodedTwice))
    }

    @Test
    fun pluginTextRepairsMojibakeBesideCorrectWindows1252Punctuation() {
        val broken = "🎞 MKV | ð\u009f\u0094§ DDP 5.1 • ð\u009f\u0094\u008a Atmos |\nð\u009f\u0091· Worker | ☁ WEB-DL"

        assertEquals(
            "🎞 MKV | 🔧 DDP 5.1 • 🔊 Atmos |\n👷 Worker | ☁ WEB-DL",
            repairPluginTextEncoding(broken),
        )
    }

    @Test
    fun pluginResultReadsNestedPlaybackRequestHeaders() {
        val results = PluginRuntime.parseJsonResults(
            """[{"url":"https://video.example/stream.m3u8","title":"Stream","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://provider.example/","Origin":"https://provider.example","Cookie":"session=abc"}}},"headers":{"User-Agent":"Plugin UA"}}]""",
        )

        assertEquals(
            mapOf(
                "Referer" to "https://provider.example/",
                "Origin" to "https://provider.example",
                "Cookie" to "session=abc",
                "User-Agent" to "Plugin UA",
            ),
            results.single().headers,
        )
    }
}
