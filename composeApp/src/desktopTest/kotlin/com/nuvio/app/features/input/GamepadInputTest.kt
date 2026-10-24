package com.nuvio.app.features.input

import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.PlayerShortcutAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the two pieces of gamepad handling that do not need a controller: how a stick deflection
 * becomes a direction, and how a held button turns into a repeat. Both are the parts most likely to
 * feel wrong in the hand, and neither can be checked by reading the poll loop.
 */
class GamepadInputTest {

    private companion object {
        /**
         * A trailing "(Enter)" / "(Space)" — a key name appended to an action. Parentheses alone
         * are not the tell: "Open Library (toggle back to Home)" is the action's own wording.
         */
        val KEY_SUFFIX = Regex("""\((?:Enter|Esc|Escape|Space|Backspace|Tab|Home|End|F\d+|[A-Z0-9]|Page (?:Up|Down))\)""")
    }

    private val deadZone = 0.30f
    private val repeatInterval = 110
    private val repeatDelay = GamepadSettingsRepository.DEFAULT_REPEAT_DELAY_MS

    // --- stick -> direction ---------------------------------------------------------------

    @Test
    fun `a resting stick produces no direction`() {
        assertNull(GamepadInput.stickDirection(0, 0, deadZone))
    }

    @Test
    fun `drift inside the dead zone produces no direction`() {
        // ~28% deflection on both axes: past a naive zero-check, short of the dead zone.
        assertNull(GamepadInput.stickDirection(9_000, -9_000, deadZone))
    }

    @Test
    fun `each axis maps to the matching direction`() {
        assertEquals(StickDirection.Right, GamepadInput.stickDirection(30_000, 0, deadZone))
        assertEquals(StickDirection.Left, GamepadInput.stickDirection(-30_000, 0, deadZone))
        // XInput reports Y positive upward, which is the opposite of screen coordinates.
        assertEquals(StickDirection.Up, GamepadInput.stickDirection(0, 30_000, deadZone))
        assertEquals(StickDirection.Down, GamepadInput.stickDirection(0, -30_000, deadZone))
    }

    @Test
    fun `a diagonal push resolves to one direction, not two`() {
        // Slightly more horizontal than vertical: a grid should move sideways, and only sideways.
        assertEquals(StickDirection.Right, GamepadInput.stickDirection(30_000, 25_000, deadZone))
        assertEquals(StickDirection.Up, GamepadInput.stickDirection(25_000, 30_000, deadZone))
    }

    @Test
    fun `raising the dead zone rejects a deflection the lower one accepted`() {
        val push = 12_000 // ~37%
        assertEquals(StickDirection.Right, GamepadInput.stickDirection(push, 0, 0.30f))
        assertNull(GamepadInput.stickDirection(push, 0, 0.50f))
    }

    // --- press / hold / repeat ------------------------------------------------------------

    @Test
    fun `a new press fires once`() {
        val due = HashMap<GamepadButton, Long>()
        assertEquals(listOf(GamepadButton.A), firing(setOf(GamepadButton.A), emptySet(), due, 0))
    }

    @Test
    fun `a held face button never repeats`() {
        val due = HashMap<GamepadButton, Long>()
        val held = setOf(GamepadButton.B)
        firing(held, emptySet(), due, 0)
        // Well past any repeat delay: "back" must not walk the whole navigation stack on one press.
        assertTrue(firing(held, held, due, repeatDelay + 10_000L).isEmpty())
    }

    @Test
    fun `a held direction waits out the delay, then repeats on the interval`() {
        val due = HashMap<GamepadButton, Long>()
        val held = setOf(GamepadButton.DpadDown)

        assertEquals(listOf(GamepadButton.DpadDown), firing(held, emptySet(), due, 0))
        assertTrue(firing(held, held, due, repeatDelay - 1L).isEmpty())
        assertEquals(listOf(GamepadButton.DpadDown), firing(held, held, due, repeatDelay.toLong()))

        val secondRepeatAt = repeatDelay + repeatInterval.toLong()
        assertTrue(firing(held, held, due, secondRepeatAt - 1L).isEmpty())
        assertEquals(listOf(GamepadButton.DpadDown), firing(held, held, due, secondRepeatAt))
    }

    @Test
    fun `releasing a direction clears its repeat, so the next press waits the full delay again`() {
        val due = HashMap<GamepadButton, Long>()
        val held = setOf(GamepadButton.DpadLeft)

        firing(held, emptySet(), due, 0)
        firing(emptySet(), held, due, 50)
        assertTrue(due.isEmpty())

        assertEquals(listOf(GamepadButton.DpadLeft), firing(held, emptySet(), due, 100))
        assertTrue(firing(held, held, due, 100 + repeatDelay - 1L).isEmpty())
    }

    @Test
    fun `the repeat interval sets the gap between steps`() {
        // When the next step is due is decided by the interval in force as it is scheduled, so the
        // whole press has to be replayed at each setting rather than switched partway through.
        fun secondStepDueAt(interval: Int): Long {
            val due = HashMap<GamepadButton, Long>()
            val held = setOf(GamepadButton.DpadRight)
            GamepadInput.resolveFiring(held, emptySet(), due, 0, interval)
            GamepadInput.resolveFiring(held, held, due, repeatDelay.toLong(), interval)
            return due.getValue(GamepadButton.DpadRight)
        }

        val fast = GamepadSettingsRepository.MIN_REPEAT_INTERVAL_MS
        val slow = GamepadSettingsRepository.MAX_REPEAT_INTERVAL_MS
        assertEquals(repeatDelay + fast.toLong(), secondStepDueAt(fast))
        assertTrue(secondStepDueAt(fast) < secondStepDueAt(slow))
    }

    // --- default layout -------------------------------------------------------------------

    @Test
    fun `every button does something during playback`() {
        GamepadButton.entries.forEach { button ->
            assertTrue(
                GamepadDefaults.binding(button).player != GamepadTarget.Unbound,
                "${button.displayName} has no player target",
            )
        }
    }

    @Test
    fun `every button does something while browsing too`() {
        GamepadButton.entries.forEach { button ->
            assertTrue(
                GamepadDefaults.binding(button).browsing != GamepadTarget.Unbound,
                "${button.displayName} has no browsing target",
            )
        }
    }

    /**
     * The pad should reach everything the keyboard can. This is the check that says so — if someone
     * adds a shortcut and does not think about the controller, this fails and names it.
     *
     * The omissions are deliberate and listed with their reasons; adding to this set is a decision,
     * not a formality.
     */
    @Test
    fun `every keyboard action is reachable from the pad`() {
        val boundApp = allTargets().filterIsInstance<GamepadTarget.AppAction>().map { it.action }.toSet()
        assertEquals(
            emptySet(),
            AppShortcutAction.entries.toSet() - boundApp,
            "app actions with no button",
        )

        val boundPlayer = buildSet {
            allTargets().forEach { target ->
                when (target) {
                    is GamepadTarget.PlayerAction -> add(target.action)
                    is GamepadTarget.ShaderOrHdr -> {
                        add(target.shaderAction)
                        add(target.hdrAction)
                    }
                    else -> Unit
                }
            }
        }
        assertEquals(
            setOf(
                // The same action as PlayPause, which is on A.
                PlayerShortcutAction.AlternatePlayPause,
                // Reached by the D-pad and left stick: both send the fixed arrow keys, which the
                // player's dispatcher maps to seek before any rebindable action is considered.
                PlayerShortcutAction.SeekBackward,
                PlayerShortcutAction.SeekForward,
            ),
            PlayerShortcutAction.entries.toSet() - boundPlayer,
            "player actions with no button",
        )
    }

    @Test
    fun `the triggers do not repeat what the D-pad already does`() {
        // Seek is on the D-pad and left stick already; spending the triggers on it too would leave
        // real functionality stranded on the keyboard.
        val dpadSeek = listOf(
            GamepadDefaults.binding(GamepadButton.DpadLeft).player,
            GamepadDefaults.binding(GamepadButton.DpadRight).player,
        )
        assertTrue(dpadSeek.all { it is GamepadTarget.FixedKey })
        assertTrue(GamepadDefaults.binding(GamepadButton.LeftTrigger).player !in dpadSeek)
        assertTrue(GamepadDefaults.binding(GamepadButton.RightTrigger).player !in dpadSeek)
    }

    private fun allTargets(): List<GamepadTarget> = GamepadButton.entries.flatMap {
        val binding = GamepadDefaults.binding(it)
        listOf(binding.browsing, binding.player)
    }

    @Test
    fun `the triggers step playback speed in both directions`() {
        // The stick click only toggles between two speeds, so these are the only fine-grained
        // speed controls on the pad, and they must not be the same step twice.
        assertEquals(
            GamepadTarget.PlayerAction(PlayerShortcutAction.SpeedUp),
            GamepadDefaults.binding(GamepadButton.RightTrigger).player,
        )
        assertEquals(
            GamepadTarget.PlayerAction(PlayerShortcutAction.SpeedDown),
            GamepadDefaults.binding(GamepadButton.LeftTrigger).player,
        )
    }

    @Test
    fun `the right stick holds the whole picture pipeline during playback`() {
        // One stick to learn rather than four scattered buttons. If a picture control is moved off
        // the stick, that grouping is gone and this says so.
        assertEquals(
            setOf(
                GamepadTarget.PlayerAction(PlayerShortcutAction.CycleSvp),
                GamepadTarget.PlayerAction(PlayerShortcutAction.CycleColorProfile),
                GamepadTarget.PlayerAction(PlayerShortcutAction.ToggleMpvDiagnostics),
                GamepadTarget.ShaderOrHdr(
                    shaderAction = PlayerShortcutAction.CycleAnime,
                    hdrAction = PlayerShortcutAction.CycleHdr,
                ),
            ),
            listOf(
                GamepadButton.RightStickUp,
                GamepadButton.RightStickDown,
                GamepadButton.RightStickLeft,
                GamepadButton.RightStickRight,
            ).map { GamepadDefaults.binding(it).player }.toSet(),
        )
    }

    @Test
    fun `right stick down picks the shader over HDR only when a shader is running`() {
        val target = GamepadDefaults.binding(GamepadButton.RightStickDown).player
        assertEquals(
            GamepadTarget.ShaderOrHdr(
                shaderAction = PlayerShortcutAction.CycleAnime,
                hdrAction = PlayerShortcutAction.CycleHdr,
            ),
            target,
        )
    }

    @Test
    fun `no two player buttons resolve to the same action`() {
        val playerTargets = GamepadButton.entries
            .map { GamepadDefaults.binding(it).player }
            .filter { it != GamepadTarget.Unbound }
        assertEquals(
            playerTargets.size,
            playerTargets.toSet().size,
            "two buttons share a player action: $playerTargets",
        )
    }

    @Test
    fun `directions send the same fixed arrow key in both contexts`() {
        // Arrows are the one thing no screen lets the user rebind — every browsing screen matches
        // Key.DirectionUp literally, and the player pins them to seek and volume. The *key* must
        // therefore match across contexts even though what it does, and so how it is described,
        // does not.
        listOf(
            GamepadButton.DpadUp,
            GamepadButton.DpadDown,
            GamepadButton.DpadLeft,
            GamepadButton.DpadRight,
        ).forEach { button ->
            val binding = GamepadDefaults.binding(button)
            val browsing = binding.browsing as? GamepadTarget.FixedKey
            val player = binding.player as? GamepadTarget.FixedKey
            assertTrue(browsing != null && player != null, "${button.id} is not on a fixed key")
            assertEquals(browsing.keyCode, player.keyCode, "${button.id} sends a different key by context")
        }
    }

    @Test
    fun `the inventory says what a button does, not which key it sends`() {
        // The settings rows are read to find out what a button does. "In player: arrow up" answers
        // nothing; the arrow means volume there. Key names are omitted entirely because the
        // keyboard sections above the controller one already list every binding.
        // The guard below only means something if it would catch the old format, and would not
        // catch an action whose own name happens to use brackets.
        assertTrue(KEY_SUFFIX.containsMatchIn("Select / open focused item (Enter)"))
        assertTrue(KEY_SUFFIX.containsMatchIn("Play / pause (Space)"))
        assertTrue(KEY_SUFFIX.find("Open Library (toggle back to Home)") == null)

        val rows = gamepadMappingRows().associateBy { it.button }

        // A read "Select / open focused item (Enter)" before; the action name stands alone now.
        assertEquals(
            AppShortcutAction.SelectFocused.displayName,
            rows.getValue(GamepadButton.A).browsingLabel,
        )
        assertEquals(
            PlayerShortcutAction.PlayPause.displayName,
            rows.getValue(GamepadButton.A).playerLabel,
        )
        assertEquals("Move up", rows.getValue(GamepadButton.DpadUp).browsingLabel)
        assertEquals("Volume up", rows.getValue(GamepadButton.DpadUp).playerLabel)
        assertEquals("Seek forward", rows.getValue(GamepadButton.DpadRight).playerLabel)
        assertEquals("Jump to top", rows.getValue(GamepadButton.RightStickUp).browsingLabel)

        rows.values.forEach { row ->
            listOf(row.browsingLabel, row.playerLabel).forEach { label ->
                assertTrue(label.isNotBlank(), "${row.button.displayName} has a blank label")
                assertTrue(
                    label.none { it in "←→↑↓" },
                    "${row.button.displayName} describes a key glyph rather than an action: \"$label\"",
                )
                assertTrue(
                    KEY_SUFFIX.find(label) == null,
                    "${row.button.displayName} still carries a key name: \"$label\"",
                )
            }
        }
    }

    @Test
    fun `face buttons carry different intent in the player than while browsing`() {
        val a = GamepadDefaults.binding(GamepadButton.A)
        assertEquals(GamepadTarget.AppAction(AppShortcutAction.SelectFocused), a.browsing)
        assertEquals(GamepadTarget.PlayerAction(PlayerShortcutAction.PlayPause), a.player)

        // B closes overlays in the player rather than going Back: the player dispatcher wires up
        // Escape, and never sees GoBack at all.
        val b = GamepadDefaults.binding(GamepadButton.B)
        assertEquals(GamepadTarget.AppAction(AppShortcutAction.GoBack), b.browsing)
        assertEquals(GamepadTarget.AppAction(AppShortcutAction.DismissOverlay), b.player)
    }

    @Test
    fun `no two browsing buttons resolve to the same action`() {
        val browsingTargets = GamepadButton.entries
            .map { GamepadDefaults.binding(it).browsing }
            .filter { it != GamepadTarget.Unbound }
        assertEquals(
            browsingTargets.size,
            browsingTargets.toSet().size,
            "two buttons share a browsing action: $browsingTargets",
        )
    }

    @Test
    fun `only select keeps its key down while the button is held`() {
        // Hold-to-select needs a real press and release from A; every other button keeps the
        // instant pair, so nothing else changes timing.
        val browsingA = GamepadDefaults.binding(GamepadButton.A).browsing
        assertTrue(GamepadInput.holdsUntilRelease(browsingA))
        assertEquals(
            listOf(browsingA),
            allTargets().filter(GamepadInput::holdsUntilRelease).distinct(),
        )
    }

    private fun firing(
        current: Set<GamepadButton>,
        previous: Set<GamepadButton>,
        due: MutableMap<GamepadButton, Long>,
        now: Long,
    ): List<GamepadButton> = GamepadInput.resolveFiring(current, previous, due, now, repeatInterval)
}
