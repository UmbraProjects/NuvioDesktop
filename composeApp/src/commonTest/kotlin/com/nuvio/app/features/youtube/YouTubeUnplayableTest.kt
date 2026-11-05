package com.nuvio.app.features.youtube

import com.nuvio.app.features.trailer.TrailerUnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeUnplayableTest {
    private fun refusal(reason: TrailerUnavailableReason, text: String?) = YouTubeUnplayable.Refusal(reason, text, atMs = 0L)

    @Test
    fun anAgeGateIsDefinitiveAndSaysSo() {
        val gate = refusal(TrailerUnavailableReason.AGE_RESTRICTED, "This video may be inappropriate for some users.")
        assertTrue(gate.ageGated)
        assertTrue(gate.definitive)
        assertEquals(
            "\"Cat\" is age-restricted, and this build of Nuvio can't play age-restricted videos. It opens on youtube.com.",
            YouTubeUnplayable.openErrorMessage("Cat", gate, paused = false),
        )
    }

    @Test
    fun aBotCheckIsNotAnAgeGateAndIsAskedAgain() {
        val botCheck = refusal(TrailerUnavailableReason.AGE_RESTRICTED, "Sign in to confirm you're not a bot")
        assertFalse(botCheck.ageGated)
        assertFalse(botCheck.definitive)
        assertEquals(
            "\"Cat\" couldn't be opened. YouTube says: Sign in to confirm you're not a bot",
            YouTubeUnplayable.openErrorMessage("Cat", botCheck, paused = false),
        )
    }

    @Test
    fun throttlingIsOnlyBlamedWhileRequestsArePaused() {
        assertEquals("\"Cat\" couldn't be opened. Try again shortly.", YouTubeUnplayable.openErrorMessage("Cat", null, paused = false))
        assertEquals(
            "\"Cat\" couldn't be opened: YouTube is limiting requests. Try again shortly.",
            YouTubeUnplayable.openErrorMessage("Cat", null, paused = true),
        )
    }

    @Test
    fun noteAndClear() {
        YouTubeUnplayable.note("abc", TrailerUnavailableReason.REMOVED_OR_PRIVATE, "This video has been removed")
        assertTrue(YouTubeUnplayable.refusal("abc")?.definitive == true)
        YouTubeUnplayable.clear("abc")
        assertEquals(null, YouTubeUnplayable.refusal("abc"))
    }
}
