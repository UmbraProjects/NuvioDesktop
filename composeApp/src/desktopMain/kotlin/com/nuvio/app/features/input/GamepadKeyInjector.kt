package com.nuvio.app.features.input

import com.nuvio.app.features.player.AppShortcutsRepository
import com.nuvio.app.features.player.PlayerShortcutsRepository
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent

/**
 * Turns a pad press into the keystroke the same action already has on a keyboard.
 *
 * The whole design rests on this: nothing downstream is told a gamepad exists. A synthetic
 * `KEY_PRESSED`/`KEY_RELEASED` pair posted to the AWT event queue runs the identical path a real
 * key does — `Component.dispatchEventImpl` hands key events to the focus manager, which runs every
 * registered `KeyEventDispatcher` (the player's, `Main`'s back-navigation one) and then delivers to
 * the focus owner, where Compose's `onPreviewKeyEvent` handlers see it. So every screen that is
 * already keyboard-navigable is pad-navigable with no change of its own.
 *
 * Known gap: with a text field focused, a button whose action resolves to a letter key (Search is
 * "S" by default) types that letter instead. Controller text entry needs an on-screen keyboard,
 * which is not part of this phase.
 */
internal object GamepadKeyInjector {

    /**
     * Resolves [target] against the user's current bindings and posts one press/release pair.
     * Returns false when nothing was sent — unbound, or no Nuvio window holds focus.
     */
    fun send(target: GamepadTarget): Boolean {
        val keyCode = resolveKeyCode(target) ?: return false
        return sendKeyCode(keyCode)
    }

    /**
     * Posts only the press for [target] and returns the key code it sent, for a button whose
     * release has to wait for the button itself — see [GamepadInput]'s hold-to-select handling.
     * Null when nothing was sent.
     */
    fun press(target: GamepadTarget): Int? {
        val keyCode = resolveKeyCode(target) ?: return null
        val source = focusTarget() ?: return null
        post(KeyEvent(source, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED))
        return keyCode
    }

    /** The release matching an earlier [press]. */
    fun release(keyCode: Int) {
        val source = focusTarget() ?: return
        post(KeyEvent(source, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED))
    }

    /** Posts one press/release pair for a `VK_` code. */
    fun sendKeyCode(keyCode: Int): Boolean {
        val source = focusTarget() ?: return false
        val now = System.currentTimeMillis()
        post(KeyEvent(source, KeyEvent.KEY_PRESSED, now, 0, keyCode, KeyEvent.CHAR_UNDEFINED))
        post(KeyEvent(source, KeyEvent.KEY_RELEASED, now, 0, keyCode, KeyEvent.CHAR_UNDEFINED))
        return true
    }

    /**
     * Types one character into whatever holds focus, for the on-screen keyboard. `KEY_TYPED` is the
     * event that actually carries a character into a Compose text field; the press and release
     * around it keep the sequence well-formed for anything watching keys rather than text.
     */
    fun typeCharacter(character: Char): Boolean {
        val source = focusTarget() ?: return false
        val now = System.currentTimeMillis()
        post(KeyEvent(source, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_UNDEFINED, character))
        post(KeyEvent(source, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, character))
        post(KeyEvent(source, KeyEvent.KEY_RELEASED, now, 0, KeyEvent.VK_UNDEFINED, character))
        return true
    }

    /**
     * The component synthetic events are addressed to, or null when the app is not frontmost. A pad
     * is a global device: without that check the controller would keep driving Nuvio from inside
     * whatever application the user actually switched to.
     */
    /** True while a Nuvio window is the one in front. */
    fun appFocused(): Boolean = focusTarget() != null

    private fun focusTarget(): Component? {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val window = focusManager.focusedWindow ?: return null
        return focusManager.focusOwner ?: window
    }

    private fun post(event: KeyEvent) {
        Toolkit.getDefaultToolkit().systemEventQueue.postEvent(event)
    }

    /**
     * The `VK_` code [target] currently means. Rebindable targets are resolved on every press
     * rather than cached, so changing a shortcut in settings moves the pad with it immediately.
     */
    private fun resolveKeyCode(target: GamepadTarget): Int? = when (target) {
        is GamepadTarget.FixedKey -> target.keyCode
        is GamepadTarget.AppAction -> {
            AppShortcutsRepository.ensureLoaded()
            AppShortcutsRepository.keyCode(target.action)
        }
        is GamepadTarget.PlayerAction -> {
            PlayerShortcutsRepository.ensureLoaded()
            PlayerShortcutsRepository.keyCode(target.action)
        }
        is GamepadTarget.ShaderOrHdr -> {
            PlayerShortcutsRepository.ensureLoaded()
            val action = if (GamepadContext.animeShaderActive) target.shaderAction else target.hdrAction
            PlayerShortcutsRepository.keyCode(action)
        }
        GamepadTarget.Unbound -> null
    }
}
