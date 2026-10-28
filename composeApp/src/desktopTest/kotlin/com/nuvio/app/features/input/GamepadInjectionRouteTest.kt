package com.nuvio.app.features.input

import nuvio.composeapp.generated.resources.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The load-bearing assumption of gamepad support: a synthetic AWT [KeyEvent] posted to the system
 * event queue travels the same route a real keypress does, reaching both the global
 * [KeyEventDispatcher]s the player and Main install *and* Compose's `onPreviewKeyEvent` handlers
 * that every browsing screen navigates with.
 *
 * If this stops holding — a Compose Multiplatform upgrade changing how skiko takes key input, say —
 * the pad goes silent everywhere with nothing else failing, so it is worth a real window rather
 * than a reasoned argument.
 *
 * Needs a display and a focused window, and focus is global — while this runs, keystrokes meant for
 * another application land in its window instead. It is therefore **opt-in**:
 *
 *     ./gradlew desktopTest -Pnuvio.focusTests
 *
 * Run it before shipping input changes, and on CI where nobody is at the keyboard. There is no
 * offscreen equivalent: `ImageComposeScene` takes an already-converted Compose event, and Compose's
 * own AWT conversion (`toComposeEvent`) is `internal`, so an offscreen version would assert against
 * an event this test hand-built rather than the one the injector actually posts — which is the very
 * thing worth checking.
 *
 * Skipped rather than failed where a window cannot take focus, because a skip is honest and a green
 * fake is not.
 */
class GamepadInjectionRouteTest {

    @Test
    fun `a synthetic key event reaches both the AWT dispatcher and Compose`() {
        if (System.getProperty("nuvio.focusTests") != "true") {
            println("SKIPPED: takes OS focus and would swallow keystrokes; run with -Pnuvio.focusTests")
            return
        }
        if (GraphicsEnvironment.isHeadless()) {
            println("SKIPPED: headless environment, cannot focus a window")
            return
        }

        val composeSawKeyDown = CountDownLatch(1)
        val dispatcherSawKey = AtomicBoolean(false)
        val focusRequester = FocusRequester()
        val probeKeyCode = KeyEvent.VK_F13 // Nothing in the app binds F13, so nothing else reacts.

        val dispatcher = KeyEventDispatcher { event ->
            if (event.keyCode == probeKeyCode) dispatcherSawKey.set(true)
            false // Never consume: the event must go on to reach Compose.
        }

        var window: ComposeWindow? = null
        try {
            onEdt {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
                window = ComposeWindow().apply {
                    setSize(320, 240)
                    setContent {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .focusRequester(focusRequester)
                                .focusable()
                                .onPreviewKeyEvent { event ->
                                    if (
                                        event.type == KeyEventType.KeyDown &&
                                        event.key.nativeKeyCode == probeKeyCode
                                    ) {
                                        composeSawKeyDown.countDown()
                                    }
                                    false
                                },
                        )
                    }
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }

            if (!awaitWindowFocus(window)) {
                println("SKIPPED: the test window never took focus (locked session or focus-stealing prevention)")
                return
            }
            onEdt { runCatching { focusRequester.requestFocus() } }

            // isFocused flips before AWT has assigned a focus owner, so the owner has to be waited
            // for separately rather than read straight after.
            val focusOwner = awaitFocusOwner()
            if (focusOwner == null) {
                println("SKIPPED: the window took focus but AWT never assigned a focus owner")
                return
            }
            val now = System.currentTimeMillis()
            val queue = Toolkit.getDefaultToolkit().systemEventQueue
            // Exactly what GamepadKeyInjector.send posts.
            queue.postEvent(KeyEvent(focusOwner, KeyEvent.KEY_PRESSED, now, 0, probeKeyCode, KeyEvent.CHAR_UNDEFINED))
            queue.postEvent(KeyEvent(focusOwner, KeyEvent.KEY_RELEASED, now, 0, probeKeyCode, KeyEvent.CHAR_UNDEFINED))

            assertTrue(
                composeSawKeyDown.await(5, TimeUnit.SECONDS),
                "Compose never saw the synthetic key: onPreviewKeyEvent handlers would be deaf to the gamepad",
            )
            assertTrue(
                dispatcherSawKey.get(),
                "no KeyEventDispatcher saw the synthetic key: the player's shortcuts would be deaf to the gamepad",
            )
        } finally {
            onEdt {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
                window?.apply { isVisible = false; dispose() }
            }
        }
    }

    @Test
    fun `nothing is injected while no window holds focus`() {
        // A pad is a global device. Without this guard a controller would keep driving Nuvio from
        // inside whatever application the user actually switched to.
        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow != null) {
            println("SKIPPED: a window currently holds focus, so the unfocused path cannot be observed")
            return
        }
        assertTrue(
            !GamepadKeyInjector.send(GamepadTarget.FixedKey(KeyEvent.VK_DOWN, Res.string.gamepad_target_move_down)),
            "the injector posted a key with no focused window",
        )
    }

    private fun awaitFocusOwner(): java.awt.Component? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    private fun awaitWindowFocus(window: ComposeWindow?): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (window?.isFocused == true) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun onEdt(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeAndWait(block)
    }
}
