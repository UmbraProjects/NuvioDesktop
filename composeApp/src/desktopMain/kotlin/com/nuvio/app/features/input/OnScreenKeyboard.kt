package com.nuvio.app.features.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.event.KeyEvent
import kotlin.math.roundToInt

/** A single key on the on-screen keyboard: either a character to type, or an action to perform. */
internal sealed interface OskKey {
    val label: String

    data class Character(val lower: Char, val upper: Char = lower.uppercaseChar()) : OskKey {
        override val label: String get() = lower.toString()
        fun character(caps: Boolean): Char = if (caps) upper else lower
    }

    data class Action(override val label: String, val action: OskAction) : OskKey
}

internal enum class OskAction { Space, Backspace, Caps, Done }

/** Row and column of the highlighted key. */
internal data class OskSelection(val row: Int, val column: Int)

internal enum class OskDirection { Up, Down, Left, Right }

/**
 * The key grid. Deliberately small: a controller user reaches exactly one text field in normal use
 * — Search — and a full symbol set would cost more in navigation distance than it buys. Letters,
 * digits, and the punctuation that actually turns up in titles.
 */
internal object OskLayout {
    private fun c(char: Char) = OskKey.Character(char)

    val rows: List<List<OskKey>> = listOf(
        "1234567890".map(::c),
        "qwertyuiop".map(::c),
        ("asdfghjkl".map(::c) + OskKey.Character('\'')),
        ("zxcvbnm".map(::c) + listOf(OskKey.Character(','), OskKey.Character('.'), OskKey.Character('-'))),
        listOf(
            OskKey.Action("Caps", OskAction.Caps),
            OskKey.Action("Space", OskAction.Space),
            OskKey.Action("Delete", OskAction.Backspace),
            OskKey.Action("Done", OskAction.Done),
        ),
    )

    fun keyAt(selection: OskSelection): OskKey? =
        rows.getOrNull(selection.row)?.getOrNull(selection.column)

    /**
     * Moves the highlight, clamping at the edges rather than wrapping — on a grid this size wrapping
     * from "1" to "Done" feels like a misfire rather than a shortcut.
     *
     * Rows are different widths (ten keys against the bottom row's four), so a vertical move maps
     * the column proportionally. Clamping instead would send everything past the fourth column onto
     * "Done", which is the one key a stray press must not hit.
     */
    fun move(from: OskSelection, direction: OskDirection): OskSelection {
        val currentRow = rows.getOrNull(from.row) ?: return OskSelection(0, 0)
        return when (direction) {
            OskDirection.Left -> from.copy(column = (from.column - 1).coerceAtLeast(0))
            OskDirection.Right -> from.copy(column = (from.column + 1).coerceAtMost(currentRow.lastIndex))
            OskDirection.Up, OskDirection.Down -> {
                val targetRow = if (direction == OskDirection.Up) from.row - 1 else from.row + 1
                if (targetRow !in rows.indices) return from
                OskSelection(targetRow, scaleColumn(from.column, currentRow.size, rows[targetRow].size))
            }
        }
    }

    private fun scaleColumn(column: Int, fromWidth: Int, toWidth: Int): Int {
        if (fromWidth <= 1 || toWidth <= 1) return 0
        val ratio = column.toFloat() / (fromWidth - 1)
        return (ratio * (toWidth - 1)).roundToInt().coerceIn(0, toWidth - 1)
    }
}

/**
 * On-screen keyboard for controller text entry.
 *
 * It types the same way the pad's buttons navigate: by synthesizing key events at the focused
 * component. The field being typed into never loses focus and never learns the keyboard exists, so
 * this works with every text field in the app rather than the ones somebody remembered to wire up.
 *
 * Visibility follows [com.nuvio.app.core.ui.TextInputFocusTracker] — the app-wide signal that some
 * editable field holds focus — gated on a pad actually being connected, so a keyboard user never
 * sees it.
 */
internal object OnScreenKeyboard {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _selection = MutableStateFlow(OskSelection(1, 0))
    val selection: StateFlow<OskSelection> = _selection.asStateFlow()

    private val _caps = MutableStateFlow(false)
    val caps: StateFlow<Boolean> = _caps.asStateFlow()

    /**
     * Set when the user closes the keyboard themselves. Without it, "Done" would be undone
     * immediately: the field still holds focus, and that is the very condition that opens it.
     * Cleared when focus leaves, so the next field starts fresh.
     */
    @Volatile
    private var dismissedWhileFocused = false

    // Read from the gamepad poll thread on every tick.
    @Volatile
    private var isVisible = false

    /**
     * Drives visibility from the app-wide text-focus signal. [padReady] keeps the panel away from
     * anyone typing on a real keyboard.
     */
    fun onTextFocusChanged(textFieldFocused: Boolean, padReady: Boolean) {
        if (!textFieldFocused) {
            dismissedWhileFocused = false
            setVisible(false)
            return
        }
        if (padReady && !dismissedWhileFocused) {
            _selection.value = OskSelection(1, 0)
            setVisible(true)
        } else if (!padReady) {
            setVisible(false)
        }
    }

    /**
     * Opens the keyboard when the pad is used while a text field already holds focus — the case
     * where the field was reached with the mouse, so nothing had marked the controller as in use.
     * Returns true if the press was spent opening the panel rather than doing its usual job.
     *
     * A keyboard the user closed stays closed: after "Done" the pad has to navigate the results,
     * which is the whole reason they closed it.
     */
    fun onPadInputWhileTextFocused(textFieldFocused: Boolean): Boolean {
        if (isVisible || !textFieldFocused || dismissedWhileFocused) return false
        _selection.value = OskSelection(1, 0)
        setVisible(true)
        return true
    }

    /**
     * Finishes editing: submits the field, then closes. Search is what this is for — typing already
     * updates the results live, but the query is not *acted on* until the field is left, so without
     * this the user closes the keyboard and then has to press again to reach what they searched for.
     * Enter is the same key a keyboard user would press, so nothing new has to understand it.
     */
    fun commitAndDismiss() {
        GamepadKeyInjector.sendKeyCode(KeyEvent.VK_ENTER)
        dismiss()
    }

    /** Closed by the user — stays closed until focus moves elsewhere. */
    fun dismiss() {
        dismissedWhileFocused = true
        setVisible(false)
    }

    private fun setVisible(value: Boolean) {
        if (isVisible == value) return
        isVisible = value
        _visible.value = value
        if (!value) _caps.value = false
    }

    /**
     * Handles a pad button while the keyboard is up, returning true if it was consumed. Everything
     * is consumed while visible — a press that fell through to the screen behind would fire a
     * browsing shortcut mid-word, which is exactly the letter-typing problem this exists to fix.
     */
    fun consume(button: GamepadButton): Boolean {
        if (!isVisible) return false
        when (button) {
            GamepadButton.DpadUp -> moveSelection(OskDirection.Up)
            GamepadButton.DpadDown -> moveSelection(OskDirection.Down)
            GamepadButton.DpadLeft -> moveSelection(OskDirection.Left)
            GamepadButton.DpadRight -> moveSelection(OskDirection.Right)
            GamepadButton.A -> press(OskLayout.keyAt(_selection.value))
            GamepadButton.B -> backspace()
            GamepadButton.X -> perform(OskAction.Space)
            GamepadButton.Y -> perform(OskAction.Caps)
            // Start commits like "Done"; Back is the cancel, so it only closes the panel.
            GamepadButton.Start -> commitAndDismiss()
            GamepadButton.Back -> dismiss()
            // The shoulders move the caret, which is the only way to fix a typo in the middle of a
            // word without deleting back to it.
            GamepadButton.LeftShoulder -> GamepadKeyInjector.sendKeyCode(KeyEvent.VK_LEFT)
            GamepadButton.RightShoulder -> GamepadKeyInjector.sendKeyCode(KeyEvent.VK_RIGHT)
            else -> Unit // Swallowed on purpose.
        }
        return true
    }

    fun moveSelection(direction: OskDirection) {
        _selection.value = OskLayout.move(_selection.value, direction)
    }

    /** Presses a key, from the pad or from a mouse click on the panel. */
    fun press(key: OskKey?) {
        when (key) {
            is OskKey.Character -> GamepadKeyInjector.typeCharacter(key.character(_caps.value))
            is OskKey.Action -> perform(key.action)
            null -> Unit
        }
    }

    private fun perform(action: OskAction) {
        when (action) {
            OskAction.Space -> GamepadKeyInjector.typeCharacter(' ')
            OskAction.Backspace -> backspace()
            OskAction.Caps -> _caps.value = !_caps.value
            OskAction.Done -> commitAndDismiss()
        }
    }

    private fun backspace() {
        GamepadKeyInjector.sendKeyCode(KeyEvent.VK_BACK_SPACE)
    }
}
