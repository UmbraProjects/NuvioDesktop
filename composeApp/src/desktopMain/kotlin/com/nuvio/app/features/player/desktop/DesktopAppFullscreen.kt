package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutsRepository
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import com.nuvio.app.features.settings.SettingsTextInputTracker

private object DesktopAppFullscreen {
    private var toggleHandler: ((Window?) -> Unit)? = null
    val isFullscreen: MutableState<Boolean> = mutableStateOf(false)

    fun setToggleHandler(handler: ((Window?) -> Unit)?): () -> Unit {
        toggleHandler = handler
        return {
            if (toggleHandler === handler) {
                toggleHandler = null
            }
        }
    }

    fun toggle(window: Window? = null) {
        val handler = toggleHandler ?: return
        SwingUtilities.invokeLater { handler(window) }
    }
}

internal fun registerDesktopAppFullscreenToggle(handler: (Window?) -> Unit): () -> Unit =
    DesktopAppFullscreen.setToggleHandler(handler)

internal fun toggleDesktopAppFullscreen(window: Window? = null) {
    DesktopAppFullscreen.toggle(window)
}

internal val desktopAppFullscreenState: MutableState<Boolean>
    get() = DesktopAppFullscreen.isFullscreen

internal fun installDesktopAppFullscreenShortcuts(window: Window): () -> Unit {
    val dispatcher = KeyEventDispatcher { event ->
        if (!event.isDesktopAppFullscreenShortcut()) return@KeyEventDispatcher false
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (focusOwner is JTextComponent || SettingsTextInputTracker.active.value) {
            return@KeyEventDispatcher false
        }
        toggleDesktopAppFullscreen(window)
        true
    }
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    return {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
    }
}

private fun KeyEvent.isDesktopAppFullscreenShortcut(): Boolean {
    if (id != KeyEvent.KEY_PRESSED) return false
    AppShortcutsRepository.ensureLoaded()
    if (keyCode == KeyEvent.VK_F11) return true
    if (keyCode == AppShortcutsRepository.keyCode(AppShortcutAction.ToggleFullscreen)) return true
    if (keyCode != KeyEvent.VK_F) return false
    val modifiers = modifiersEx
    val hasMacFullscreenModifiers =
        modifiers and KeyEvent.META_DOWN_MASK != 0 &&
            modifiers and KeyEvent.CTRL_DOWN_MASK != 0 &&
            modifiers and KeyEvent.ALT_DOWN_MASK == 0
    return hasMacFullscreenModifiers
}
