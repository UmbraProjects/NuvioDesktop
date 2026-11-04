package com.nuvio.app.features.input

import com.nuvio.app.features.input.GamepadInput.BackHold
import com.nuvio.app.features.player.AppShortcutAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GamepadBackHoldTest {

    /** Ticks every 16 ms, as the poller does, holding View for [heldMs]; returns what each tick said. */
    private fun press(hold: BackHold, heldMs: Long, start: Long = 1_000): List<BackHold.Result> {
        val results = mutableListOf<BackHold.Result>()
        var now = start
        while (now - start <= heldMs) {
            results += hold.update(down = true, now = now)
            now += 16
        }
        results += hold.update(down = false, now = now)
        return results.filter { it != BackHold.Result.None }
    }

    @Test
    fun aShortPressIsViewOnRelease() {
        val hold = BackHold(holdMs = 600)
        assertEquals(listOf(BackHold.Result.Tap), press(hold, heldMs = 120))
        assertFalse(hold.pending)
    }

    @Test
    fun holdingStartsVoiceOnceAndTheReleaseDoesNothing() {
        val hold = BackHold(holdMs = 600)
        assertEquals(listOf(BackHold.Result.Talk), press(hold, heldMs = 2_000))
        // The next press starts over.
        assertEquals(listOf(BackHold.Result.Tap), press(hold, heldMs = 100, start = 10_000))
    }

    @Test
    fun aPressInProgressStaysPendingUntilItIsResolved() {
        val hold = BackHold(holdMs = 600)
        hold.update(down = true, now = 0)
        assertTrue(hold.pending)
        assertEquals(BackHold.Result.Tap, hold.update(down = false, now = 50))
        assertEquals(BackHold.Result.None, hold.update(down = false, now = 66))
    }

    @Test
    fun theDualSenseMicButtonIsPushToTalkEverywhere() {
        val binding = GamepadDefaults.binding(GamepadButton.Mic)
        assertEquals(GamepadTarget.AppAction(AppShortcutAction.VoiceCommand), binding.browsing)
        assertEquals(GamepadTarget.AppAction(AppShortcutAction.VoiceCommand), binding.player)
    }
}
