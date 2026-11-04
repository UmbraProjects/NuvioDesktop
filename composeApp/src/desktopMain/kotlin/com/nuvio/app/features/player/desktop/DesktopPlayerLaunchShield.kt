package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlaybackStartTrace
import java.awt.Color
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JWindow
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.Timer

internal object DesktopPlayerLaunchShield {
    private var shieldWindow: JWindow? = null
    private var hideTimer: Timer? = null
    // Set while one player hands over to the next (playlist advance). The outgoing player's dispose
    // calls hide(); honouring it would flash the screen underneath before the next player's own
    // launch shields it. Cleared when the shield actually goes away: the incoming player's first
    // paint (hideAfter) or the safety timeout.
    @Volatile private var handoffHold = false

    /**
     * The surface of the player on screen, set by it while it is attached; read by [holdForHandoff] for
     * a hand-over that should black out only the video's box.
     */
    @Volatile var activeVideoHost: Component? = null

    /**
     * Covers the window until the next player paints, across the outgoing player's teardown. With
     * [videoOnly] (fork:youtube-mini-player - the video in its corner box over the YouTube tab), only
     * the outgoing video's box is covered: the tab around it stays in sight, as it does on the website.
     */
    fun holdForHandoff(videoOnly: Boolean = false) {
        handoffHold = true
        val box = if (videoOnly) activeVideoHost?.let(::screenBounds) else null
        showForActiveWindow(box)
    }

    /** [region]: what to cover, in screen coordinates; the window's content when null. */
    fun showForActiveWindow(region: Rectangle? = null) {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        SwingUtilities.invokeLater {
            val owner = activeOwnerWindow() ?: return@invokeLater
            val bounds = region ?: ownerContentBounds(owner) ?: return@invokeLater
            hideTimer?.stop()
            hideTimer = null

            val shield = shieldWindow?.takeIf { it.owner === owner } ?: JWindow(owner).also { window ->
                window.background = Color.BLACK
                window.contentPane.background = Color.BLACK
                window.focusableWindowState = false
                window.setType(Window.Type.POPUP)
                shieldWindow = window
            }
            shield.bounds = bounds
            if (!shield.isVisible) {
                shield.isVisible = true
            }
            shield.toFront()
            hideAfter(3_000)
        }
    }

    fun hideAfter(delayMs: Int = 48) {
        SwingUtilities.invokeLater {
            hideTimer?.stop()
            hideTimer = Timer(delayMs) {
                hideNow()
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    fun hide() {
        SwingUtilities.invokeLater {
            if (handoffHold) return@invokeLater
            hideTimer?.stop()
            hideTimer = null
            hideNow()
        }
    }

    private fun hideNow() {
        handoffHold = false
        val shield = shieldWindow ?: return
        if (shield.isVisible) {
            shield.isVisible = false
            PlaybackStartTrace.markStartupDetail("launchShield:hidden")
        }
    }

    private fun activeOwnerWindow(): Window? {
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        if (active?.isShowing == true) return active
        return Window.getWindows()
            .filter { it.isShowing && it !is JWindow }
            .firstOrNull()
    }

    private fun screenBounds(component: Component): Rectangle? = runCatching {
        if (!component.isShowing || component.width <= 1 || component.height <= 1) return null
        val location = component.locationOnScreen
        Rectangle(location.x, location.y, component.width, component.height)
    }.getOrNull()

    private fun ownerContentBounds(owner: Window): Rectangle? {
        val content = (owner as? RootPaneContainer)?.contentPane
        return runCatching {
            if (content != null && content.isShowing) {
                val location = content.locationOnScreen
                Rectangle(location.x, location.y, content.width, content.height)
            } else {
                val location = owner.locationOnScreen
                Rectangle(location.x, location.y, owner.width, owner.height)
            }
        }.getOrNull()
    }
}
