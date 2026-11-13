package com.nuvio.app.features.input

import com.nuvio.app.features.input.GamepadInput.RingHold
import com.nuvio.app.features.panelring.PanelRingSize
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.PlayerShortcutAction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Controller rebinding (stored bindings and their codec) and the panel ring's hold button. */
class GamepadRebindTest {

    @AfterTest
    fun restoreDefaults() {
        GamepadSettingsRepository.resetAllButtons()
        GamepadSettingsRepository.setRingButton(GamepadSettingsRepository.DEFAULT_RING_BUTTON)
        GamepadSettingsRepository.setRingSize(PanelRingSize.Medium)
        GamepadSettingsRepository.setRingMouse(GamepadSettingsRepository.DEFAULT_RING_MOUSE)
    }

    @Test
    fun everyOfferedTargetSurvivesTheCodec() {
        for (inPlayer in listOf(false, true)) {
            GamepadTargetCodec.options(inPlayer).forEach { target ->
                val encoded = GamepadTargetCodec.encode(target)
                requireNotNull(encoded) { "no code for $target" }
                assertEquals(target, GamepadTargetCodec.decode(encoded), encoded)
            }
        }
    }

    @Test
    fun everyDefaultBindingSurvivesTheCodec() {
        GamepadButton.entries.forEach { button ->
            val binding = GamepadDefaults.binding(button)
            val encoded = requireNotNull(GamepadTargetCodec.encodeBinding(binding)) { "no code for $button" }
            assertEquals(binding, GamepadTargetCodec.decodeBinding(encoded, GamepadBinding(GamepadTarget.Unbound, GamepadTarget.Unbound)))
        }
    }

    @Test
    fun unknownCodesFallBackToTheDefaultSide() {
        val fallback = GamepadDefaults.binding(GamepadButton.A)
        assertNull(GamepadTargetCodec.decode("app:from_a_newer_build"))
        assertNull(GamepadTargetCodec.decodeBinding("garbage", fallback))
        assertEquals(
            GamepadBinding(GamepadTarget.Unbound, fallback.player),
            GamepadTargetCodec.decodeBinding("none|player:not_a_thing", fallback),
        )
    }

    @Test
    fun aRebindIsStoredAndReloaded() {
        GamepadSettingsRepository.setTarget(
            GamepadButton.Y,
            inPlayer = false,
            target = GamepadTarget.AppAction(AppShortcutAction.OpenMusic),
        )
        GamepadSettingsRepository.setTarget(
            GamepadButton.Y,
            inPlayer = true,
            target = GamepadTarget.PlayerAction(PlayerShortcutAction.CycleZoom),
        )
        // A profile switch reloads everything from disk.
        GamepadSettingsRepository.onProfileChanged()
        val binding = GamepadSettingsRepository.binding(GamepadButton.Y)
        assertEquals(GamepadTarget.AppAction(AppShortcutAction.OpenMusic), binding.browsing)
        assertEquals(GamepadTarget.PlayerAction(PlayerShortcutAction.CycleZoom), binding.player)
        assertTrue(GamepadSettingsRepository.isRebound(GamepadButton.Y))
        assertFalse(GamepadSettingsRepository.isRebound(GamepadButton.X))

        GamepadSettingsRepository.resetButton(GamepadButton.Y)
        GamepadSettingsRepository.onProfileChanged()
        assertEquals(GamepadDefaults.binding(GamepadButton.Y), GamepadSettingsRepository.binding(GamepadButton.Y))
        assertFalse(GamepadSettingsRepository.isRebound(GamepadButton.Y))
    }

    @Test
    fun settingABindingBackToTheDefaultLeavesNothingStored() {
        val default = GamepadDefaults.binding(GamepadButton.B)
        GamepadSettingsRepository.setTarget(GamepadButton.B, inPlayer = false, target = GamepadTarget.Unbound)
        assertTrue(GamepadSettingsRepository.isRebound(GamepadButton.B))
        GamepadSettingsRepository.setTarget(GamepadButton.B, inPlayer = false, target = default.browsing)
        assertFalse(GamepadSettingsRepository.isRebound(GamepadButton.B))
    }

    @Test
    fun ringSettingsAreStoredAndReloaded() {
        assertEquals(GamepadButton.LeftShoulder, GamepadSettingsRepository.ringButton)
        GamepadSettingsRepository.setRingButton(null)
        GamepadSettingsRepository.setRingSize(PanelRingSize.Large)
        GamepadSettingsRepository.setRingMouse(false)
        GamepadSettingsRepository.onProfileChanged()
        assertNull(GamepadSettingsRepository.ringButton)
        assertEquals(PanelRingSize.Large, GamepadSettingsRepository.ringSizeState.value)
        assertFalse(GamepadSettingsRepository.ringMouseState.value)

        GamepadSettingsRepository.setRingButton(GamepadButton.RightShoulder)
        GamepadSettingsRepository.onProfileChanged()
        assertEquals(GamepadButton.RightShoulder, GamepadSettingsRepository.ringButton)
    }

    @Test
    fun onlyButtonsThatCanHoldOpenTheRing() {
        assertEquals(GamepadButton.LeftShoulder, GamepadSettingsRepository.decodeRingButton(null))
        assertNull(GamepadSettingsRepository.decodeRingButton("off"))
        assertEquals(GamepadButton.RightShoulder, GamepadSettingsRepository.decodeRingButton("rb"))
        // A selects inside the ring, and View is push-to-talk: neither may open it.
        assertEquals(GamepadButton.LeftShoulder, GamepadSettingsRepository.decodeRingButton("a"))
        assertEquals(GamepadButton.LeftShoulder, GamepadSettingsRepository.decodeRingButton("back"))
    }

    /** Ticks every 16 ms holding the button for [heldMs], the stick pushed from [stickAtMs]. */
    private fun press(hold: RingHold, heldMs: Long, stickAtMs: Long? = null, start: Long = 1_000): List<RingHold.Result> {
        val results = mutableListOf<RingHold.Result>()
        var now = start
        while (now - start <= heldMs) {
            val pushed = stickAtMs != null && now - start >= stickAtMs
            results += hold.update(down = true, stickPushed = pushed, now = now)
            now += 16
        }
        results += hold.update(down = false, stickPushed = false, now = now)
        return results.filter { it != RingHold.Result.None }
    }

    @Test
    fun aTapOfTheRingButtonKeepsItsOwnAction() {
        assertEquals(listOf(RingHold.Result.Tap), press(RingHold(holdMs = 220), heldMs = 120))
    }

    @Test
    fun holdingOpensTheRingAndLettingGoSwitches() {
        assertEquals(listOf(RingHold.Result.Open, RingHold.Result.Release), press(RingHold(holdMs = 220), heldMs = 600))
    }

    @Test
    fun pushingAStickOpensTheRingAtOnce() {
        val results = mutableListOf<RingHold.Result>()
        val hold = RingHold(holdMs = 220)
        results += hold.update(down = true, stickPushed = false, now = 0)
        results += hold.update(down = true, stickPushed = true, now = 32)
        assertEquals(listOf(RingHold.Result.None, RingHold.Result.Open), results)
        assertEquals(RingHold.Result.Release, hold.update(down = false, stickPushed = true, now = 64))
        assertFalse(hold.pending)
    }
}
