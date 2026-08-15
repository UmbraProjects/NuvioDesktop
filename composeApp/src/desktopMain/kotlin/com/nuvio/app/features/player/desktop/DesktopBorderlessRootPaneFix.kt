package com.nuvio.app.features.player.desktop

import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import javax.swing.RootPaneContainer

/**
 * Keeps the root pane covering the whole window while borderless fullscreen is active.
 *
 * [applyNativeBorderlessFullscreen] strips `WS_CAPTION | WS_THICKFRAME` with a raw
 * `SetWindowLongPtrW`, which AWT does not fully observe: it keeps reporting the *decorated*
 * window's frame insets. `java.awt.Frame` lays its root pane out inside `getInsets()`, so every
 * pixel of Compose content — and the native video surface positioned through `SwingPanel` — ends up
 * offset by a title bar that is no longer on screen, with the far edge clipped off.
 *
 * Measured on a 3840x2160 display at 300%: insets of 18px on the left/right/bottom and 84px on top
 * (6dp and 28dp), placing the content pane at (18, 84) sized 3804x2058 inside a full-size window.
 * The visible result is a band of the window's own background down the left and top edges, and —
 * because the embedded mpv surface draws at the window origin rather than the offset one — a band
 * of unpainted Swing default grey along the right and bottom.
 *
 * Rather than trying to talk AWT into re-reading the insets (its refresh is driven by native
 * messages we would be guessing at), this bypasses them: the root pane is repositioned to the full
 * window rect whenever the frame re-lays it out. Re-applied on every resize because the frame's own
 * layout manager puts it back inside the insets each time it validates.
 */
internal object DesktopBorderlessRootPaneFix {

    private val listeners = mutableMapOf<Window, ComponentListener>()

    fun setActive(window: Window, active: Boolean) {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        if (active) install(window) else uninstall(window)
    }

    private fun install(window: Window) {
        if (window !is RootPaneContainer) return
        if (listeners.containsKey(window)) {
            pinRootPane(window)
            return
        }
        val listener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = pinRootPane(window)
            override fun componentMoved(event: ComponentEvent) = pinRootPane(window)
            override fun componentShown(event: ComponentEvent) = pinRootPane(window)
        }
        window.addComponentListener(listener)
        listeners[window] = listener
        pinRootPane(window)
    }

    private fun uninstall(window: Window) {
        listeners.remove(window)?.let(window::removeComponentListener)
        // Hand the layout back to the frame, which reinstates the (now genuine) decorated insets.
        window.invalidate()
        window.validate()
    }

    private fun pinRootPane(window: Window) {
        val rootPane = (window as? RootPaneContainer)?.rootPane ?: return
        val width = window.width
        val height = window.height
        if (width <= 0 || height <= 0) return
        val bounds = rootPane.bounds
        if (bounds.x == 0 && bounds.y == 0 && bounds.width == width && bounds.height == height) {
            return
        }
        System.out.println(
            "Info: (WindowGeometry) borderless root pane corrected from " +
                "${bounds.width}x${bounds.height}@${bounds.x},${bounds.y} to ${width}x$height " +
                "(stale AWT insets=${runCatching { window.insets }.getOrNull()})"
        )
        rootPane.setBounds(0, 0, width, height)
        // validate() on the root pane, not the window: validating the window would run the frame's
        // layout manager again and immediately undo the correction.
        rootPane.validate()
        rootPane.repaint()
    }
}
