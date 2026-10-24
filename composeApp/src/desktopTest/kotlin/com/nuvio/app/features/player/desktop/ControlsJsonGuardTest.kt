package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlIptvChannel
import com.nuvio.app.features.player.PlayerControlIptvGroup
import com.nuvio.app.features.player.PlayerControlIptvState
import com.nuvio.app.features.player.PlayerControlPartyMember
import com.nuvio.app.features.player.PlayerControlPartyState
import com.nuvio.app.features.player.PlayerControlsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** The party block is a nested object with its own separators, so check a real payload. */
    @Test
    fun `a payload with a watch party is well formed and keeps its members and labels`() {
        val state = PlayerControlsState(
            title = "Severance",
            party = PlayerControlPartyState(
                available = true,
                active = true,
                code = "K7Q-4MX",
                pillMessage = "Waiting for \"Sam\"",
                members = listOf(
                    PlayerControlPartyMember(0, "Jordan", "J", "#1E88E5", "", "Watching", "watching", true, true, false),
                    PlayerControlPartyMember(1, "Sam", "S", "", "https://x/a.png", "Buffering", "busy", false, false, true),
                ),
                labels = mapOf("title" to "Watch party", "removeTitle" to "Remove {name}?"),
            ),
        )

        val payload = state.toControlsJson(
            appFullscreenKeyCode = 122,
            playerShortcutKeyCodes = emptyMap(),
            skipIntervalKeyLabel = "S",
        )

        assertTrue(controlsJsonIsWellFormed(payload))
        val party = Json.parseToJsonElement(payload).jsonObject.getValue("party").jsonObject
        assertEquals("Waiting for \"Sam\"", party.getValue("pillMessage").jsonPrimitive.content)
        assertEquals(2, party.getValue("members").jsonArray.size)
        assertEquals("Remove {name}?", party.getValue("labels").jsonObject.getValue("removeTitle").jsonPrimitive.content)
    }

    /** Live TV's block is nested too; a missing comma after `groupsLabel` blanked the IPTV HUD. */
    @Test
    fun `a payload with live TV is well formed and keeps its channels`() {
        val state = PlayerControlsState(
            title = "Comedy Central",
            iptv = PlayerControlIptvState(
                channelLabel = "4  Comedy \"Central\"",
                nowTitle = "Maury",
                groups = listOf(PlayerControlIptvGroup(0, "Favourites", 2, false), PlayerControlIptvGroup(1, "US | Comedy", 9, true)),
                channels = listOf(PlayerControlIptvChannel(3, 4, "Comedy Central", "", "Maury", true)),
                channelsNote = "9 channels match",
            ),
        )

        val payload = state.toControlsJson(
            appFullscreenKeyCode = 122,
            playerShortcutKeyCodes = emptyMap(),
            skipIntervalKeyLabel = "S",
        )

        assertTrue(controlsJsonIsWellFormed(payload))
        val iptv = Json.parseToJsonElement(payload).jsonObject.getValue("iptv").jsonObject
        assertEquals("Search channels", iptv.getValue("searchLabel").jsonPrimitive.content)
        assertEquals(2, iptv.getValue("groups").jsonArray.size)
        assertEquals(1, iptv.getValue("channels").jsonArray.size)
    }

    @Test
    fun `a payload without a party is still well formed`() {
        val payload = PlayerControlsState().toControlsJson(
            appFullscreenKeyCode = 122,
            playerShortcutKeyCodes = emptyMap(),
            skipIntervalKeyLabel = "S",
        )
        assertTrue(controlsJsonIsWellFormed(payload))
    }
}
