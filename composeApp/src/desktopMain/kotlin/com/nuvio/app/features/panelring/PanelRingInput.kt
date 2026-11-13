package com.nuvio.app.features.panelring

import com.nuvio.app.features.input.GamepadContext
import com.nuvio.app.features.input.GamepadSettingsRepository
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutsRepository
import java.awt.AWTEvent
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

/**
 * Keyboard and mouse for the panel ring (the pad's share is in GamepadInput).
 *
 * Keyboard: the ring key (R) opens it from Main's shortcut handler ([openFromKeyboard]); while it is
 * open a key dispatcher, installed ahead of every other one, takes every key: 1-9 and 0 switch,
 * arrows step round, Enter switches to the aimed slot, Escape or the ring key again closes. Holding
 * the ring key and moving the mouse aims; letting go of the key then switches.
 *
 * Mouse: holding the middle button opens the ring at the pointer; dragging aims; letting go switches
 * (or, after a quick click, leaves it open to click a slot). A left click switches to the aimed slot,
 * a right click closes. AWT listeners only observe, so the overlay's own scrim is what stops a click
 * reaching the screen beneath.
 */
internal object PanelRingInput {
    /** Points (AWT units) from the centre that count as "not aiming yet". */
    private const val MOUSE_DEAD_ZONE = 34f

    /** A middle-button press shorter than this is a click: the ring stays open for clicking. */
    private const val CLICK_MS = 300L

    /** Pointer travel after the ring opens before the mouse takes over the aim from a key or pad. */
    private const val MOUSE_TAKEOVER_POINTS = 6

    @Volatile
    private var ringKeyHeld = false

    @Volatile
    private var middleHeld = false
    private var middlePressedAt = 0L

    /** Where the pointer was when the ring opened, to tell a moved mouse from a resting one. */
    private var pointerAtOpen: Point? = null
    private var mouseAiming = false

    /** The ring's drawn centre on screen (AWT points), published by the overlay each time it lays out. */
    @Volatile
    var centerOnScreen: Point? = null

    fun install(): () -> Unit {
        val dispatcher = KeyEventDispatcher { event -> onKey(event) }
        val mouseListener = AWTEventListener { event -> (event as? MouseEvent)?.let(::onMouse) }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        Toolkit.getDefaultToolkit().addAWTEventListener(
            mouseListener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )
        return {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouseListener)
            PanelRingController.close()
        }
    }

    /** The ring key pressed on a browsing screen. True when the ring opened. */
    fun openFromKeyboard(): Boolean {
        if (GamepadContext.playerActive) return false
        if (PanelRingController.isOpen) {
            PanelRingController.close()
            return true
        }
        PanelRingController.open(PanelRingSource.Keyboard)
        if (!PanelRingController.isOpen) return false
        ringKeyHeld = true
        resetMouseAim()
        return true
    }

    private fun resetMouseAim() {
        pointerAtOpen = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        mouseAiming = false
        centerOnScreen = null
    }

    private fun ringKeyCode(): Int {
        AppShortcutsRepository.ensureLoaded()
        return AppShortcutsRepository.keyCode(AppShortcutAction.OpenPanelRing)
    }

    private fun onKey(event: KeyEvent): Boolean {
        if (!PanelRingController.isOpen) {
            ringKeyHeld = false
            return false
        }
        // Consumed as well as swallowed, so Main's "key nobody took" fallback does not replay it
        // to the screen once the ring has closed.
        event.consume()
        val ringKey = ringKeyCode()
        when (event.id) {
            KeyEvent.KEY_PRESSED -> {
                val code = event.keyCode
                // Held key auto-repeat while aiming with the mouse.
                if (code == ringKey && ringKeyHeld) return true
                digitOf(code)?.let { digit ->
                    panelRingIndexForDigit(digit)?.let(PanelRingController::choose)
                    return true
                }
                when (code) {
                    ringKey, KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> PanelRingController.close()
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> PanelRingController.commit()
                    KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN, KeyEvent.VK_TAB -> PanelRingController.step(1)
                    KeyEvent.VK_LEFT, KeyEvent.VK_UP -> PanelRingController.step(-1)
                }
            }
            KeyEvent.KEY_RELEASED -> {
                if (event.keyCode == ringKey && ringKeyHeld) {
                    ringKeyHeld = false
                    // Held and aimed with the mouse: letting go switches. A plain tap leaves the
                    // ring open with its numbers showing.
                    if (mouseAiming && PanelRingController.state.value?.hovered != null) PanelRingController.commit()
                }
            }
        }
        // Everything else is swallowed: a key reaching the screen under the ring would act unseen.
        return true
    }

    private fun digitOf(code: Int): Int? = when (code) {
        in KeyEvent.VK_0..KeyEvent.VK_9 -> code - KeyEvent.VK_0
        in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9 -> code - KeyEvent.VK_NUMPAD0
        else -> null
    }

    private fun onMouse(event: MouseEvent) {
        when (event.id) {
            MouseEvent.MOUSE_PRESSED -> onPress(event)
            MouseEvent.MOUSE_RELEASED -> if (event.button == MouseEvent.BUTTON2 && middleHeld) onMiddleRelease()
            MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED -> if (PanelRingController.isOpen) aimAt(event.locationOnScreen)
        }
    }

    private fun onPress(event: MouseEvent) {
        val open = PanelRingController.isOpen
        when (event.button) {
            MouseEvent.BUTTON2 -> {
                if (open) {
                    PanelRingController.close()
                    return
                }
                if (!GamepadSettingsRepository.ringMouseState.value || GamepadContext.playerActive) return
                val at = event.locationOnScreen
                PanelRingController.open(PanelRingSource.Mouse, at.x.toFloat(), at.y.toFloat())
                if (!PanelRingController.isOpen) return
                middleHeld = true
                middlePressedAt = System.currentTimeMillis()
                pointerAtOpen = at
                mouseAiming = true
                centerOnScreen = at
            }
            MouseEvent.BUTTON1 -> if (open) PanelRingController.commit()
            MouseEvent.BUTTON3 -> if (open) PanelRingController.close()
        }
    }

    private fun onMiddleRelease() {
        middleHeld = false
        if (!PanelRingController.isOpen) return
        if (PanelRingController.state.value?.hovered != null) {
            PanelRingController.commit()
        } else if (System.currentTimeMillis() - middlePressedAt >= CLICK_MS) {
            // Held, then let go in the middle: changed their mind.
            PanelRingController.close()
        }
    }

    private fun aimAt(point: Point) {
        if (!mouseAiming) {
            val start = pointerAtOpen
            if (start != null && start.distance(point) < MOUSE_TAKEOVER_POINTS) return
            mouseAiming = true
        }
        val center = centerOnScreen ?: pointerAtOpen ?: return
        val dx = (point.x - center.x).toFloat()
        val dy = (point.y - center.y).toFloat()
        PanelRingController.aim(
            PanelRingMath.slotFor(
                dx = dx,
                dy = dy,
                count = PanelRingController.slots.size,
                deadZone = MOUSE_DEAD_ZONE,
                current = PanelRingController.state.value?.hovered,
            ),
        )
    }
}
