package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The controls payload is a hand-built JSON string, and a single missing separator between two
 * fields invalidates the whole document — which silently blanks every setting in the HUD, because
 * `JSON.parse` throws inside the WebView and `window.playerControls` never runs. It has happened
 * twice for real. These cover the guard that turns that into a log line.
 */
class ControlsJsonGuardTest {

    private val wellFormed =
        """{"title":"Some Title","uiScalePercent":-20,"themeAccentStrongColor":"#3c7bff",""" +
            """"themeAccentFill":"#2f6fed","themeOnAccentColor":"#fff"}"""

    /** The exact 2026-08-29 shape: `themeAccentFill` appended without its `append(',')`. */
    private val missingSeparator =
        """{"title":"Some Title","uiScalePercent":-20,"themeAccentStrongColor":"#3c7bff"""" +
            """"themeAccentFill":"#2f6fed","themeOnAccentColor":"#fff"}"""

    @Test
    fun `a well-formed payload passes`() {
        assertTrue(controlsJsonIsWellFormed(wellFormed))
    }

    @Test
    fun `a missing separator is rejected rather than handed to the HUD`() {
        assertFalse(controlsJsonIsWellFormed(missingSeparator))
    }

    @Test
    fun `an empty or truncated payload is rejected`() {
        assertFalse(controlsJsonIsWellFormed(""))
        assertFalse(controlsJsonIsWellFormed("""{"title":"Some Title","""))
    }

    /**
     * The whole point of the excerpt is that it names the field to fix. Asserted against the real
     * parser message rather than a hand-written one, so a change to kotlinx's wording that broke
     * the offset extraction would fail here instead of silently degrading to a useless excerpt.
     */
    @Test
    fun `the excerpt names the field whose separator is missing`() {
        val message = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(missingSeparator) }
            .exceptionOrNull()
            ?.message
            .orEmpty()

        val excerpt = payloadExcerptAroundFailure(missingSeparator, message)

        assertContains(excerpt, "themeAccentFill")
        assertContains(excerpt, "themeAccentStrongColor")
    }

    @Test
    fun `an excerpt is still produced when the message carries no offset`() {
        val excerpt = payloadExcerptAroundFailure(missingSeparator, "no offset here")
        assertTrue(excerpt.isNotEmpty())
        assertContains(excerpt, "title")
    }
}
