package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriviaThemeTest {
    private val slide = """{"fact":"A fact about the film.","category":"cast"}"""

    @Test
    fun `theme is parsed and colours normalised`() {
        val pack = parseTriviaPack(
            """{"slides":[$slide],"theme":{"primary":"b3202a","secondary":"#d4af37","atmosphere":"Embers","motif":"ring spotlight"}}""",
        )
        assertEquals("#B3202A", pack.theme?.primary)
        assertEquals("#D4AF37", pack.theme?.secondary)
        assertEquals(TriviaAtmosphere.Embers, pack.theme?.atmosphere)
    }

    @Test
    fun `unusable colours drop the theme instead of the pack`() {
        val pack = parseTriviaPack("""{"slides":[$slide],"theme":{"primary":"red","secondary":"#FFF"}}""")
        assertEquals(1, pack.slides.size)
        assertNull(pack.theme)
    }

    @Test
    fun `unknown atmosphere falls back to dust`() {
        assertEquals(TriviaAtmosphere.Dust, TriviaAtmosphere.from("lasers"))
        assertEquals(TriviaAtmosphere.Dust, TriviaAtmosphere.from(null))
    }

    @Test
    fun `fallback theme fills only what the model left out`() {
        val pack = TriviaPack(slides = listOf(TriviaSlide("x")), theme = TriviaTheme(primary = "#112233"))
            .withFallbackTheme(listOf("Horror"))
        assertEquals("#112233", pack.theme?.primary)
        assertNotNull(pack.theme?.secondary)
        assertEquals(
            TriviaAtmosphere.Fog,
            TriviaPack().withFallbackTheme(listOf("Horror")).theme?.atmosphere,
        )
    }

    @Test
    fun `prompt names every atmosphere and the theme block`() {
        val system = buildTriviaPrompt(
            TriviaSource("Rocky", "1976", null, listOf("John G. Avildsen"), emptyList(), emptyList(),
                emptyList(), null, null, emptyList(), null, null, null),
            "en", TriviaMode.ModelKnowledge,
        ).system
        TriviaAtmosphere.entries.forEach { assertTrue("\"${it.name.lowercase()}\"" in system, it.name) }
        assertTrue("\"theme\"" in system)
    }

    @Test
    fun `backdrops merge in priority order without duplicates`() {
        val merged = mergeBackdrops("a", listOf("b", "a", "c"), "c", limit = 3)
        assertEquals(listOf("a", "b", "c"), merged)
    }

    @Test
    fun `hex converts to opaque argb`() {
        assertEquals(0xFFB3202AL, hexToArgb("#b3202a"))
        assertNull(hexToArgb("nope"))
    }
}

class TriviaRetryTest {
    private fun http(status: Int) =
        com.nuvio.app.features.discover.DiscoverAiException(
            com.nuvio.app.features.discover.DiscoverAiError.Http(status, "x"),
        )

    @Test
    fun `overload is retried with growing backoff`() {
        assertEquals(3_000L, retryDelayMs(http(503), 0))
        assertEquals(6_000L, retryDelayMs(http(503), 1))
        assertEquals(20_000L, retryDelayMs(http(503), 6))
    }

    @Test
    fun `client errors and unknown failures are not retried`() {
        assertNull(retryDelayMs(http(400), 0))
        assertNull(retryDelayMs(RuntimeException("boom"), 0))
        assertNull(retryDelayMs(null, 0))
    }
}
