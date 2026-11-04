package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlaybackStartTrace
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Image
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import javax.swing.JPanel
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
     * [videoOnly] (fork:youtube-mini-player - the video in its corner box over the YouTube tab), the
     * window is covered by a still of itself with only the video's box blacked out: between the two
     * players the route under them (another copy of the tab, wherever it was left) would otherwise show,
     * and the tab around the box is drawn afresh by the next player. Must be called before anything
     * moves, as the still is what is on screen at the time.
     */
    fun holdForHandoff(videoOnly: Boolean = false) {
        handoffHold = true
        if (videoOnly && DesktopHostOs.current == DesktopHostOs.WINDOWS) {
            val capture = {
                val owner = activeOwnerWindow()
                val content = owner?.let(::ownerContentBounds)
                val box = activeVideoHost?.let(::screenBounds)
                val still = content?.let(::captureScreen)
                if (owner != null && content != null && still != null) {
                    showStill(owner, content, still, box?.let { Rectangle(it.x - content.x, it.y - content.y, it.width, it.height) })
                } else {
                    showForActiveWindow(box)
                }
            }
            if (SwingUtilities.isEventDispatchThread()) capture() else SwingUtilities.invokeLater(capture)
            return
        }
        showForActiveWindow()
    }

    /** A still of the window's content up; [hideAfter] lets it stay a little longer, so the page under it has drawn. */
    @Volatile private var stillShown = false

    private fun captureScreen(bounds: Rectangle): Image? = runCatching {
        val shot = Robot().createMultiResolutionScreenCapture(bounds)
        // The device-resolution variant, so the still is as sharp as the window on a scaled display.
        shot.resolutionVariants.maxByOrNull { it.getWidth(null) } ?: (shot as? Image)
    }.getOrNull()

    private fun showStill(owner: Window, bounds: Rectangle, still: Image, videoBox: Rectangle?) {
        hideTimer?.stop()
        hideTimer = null
        val shield = shieldFor(owner)
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                g.drawImage(still, 0, 0, width, height, null)
                if (videoBox != null) {
                    g.color = Color.BLACK
                    g.fillRect(videoBox.x, videoBox.y, videoBox.width, videoBox.height)
                }
            }
        }
        panel.background = Color.BLACK
        shield.contentPane = panel
        shield.bounds = bounds
        stillShown = true
        if (!shield.isVisible) shield.isVisible = true
        shield.toFront()
        shield.repaint()
        hideAfter(3_000)
    }

    private fun shieldFor(owner: Window): JWindow =
        shieldWindow?.takeIf { it.owner === owner } ?: JWindow(owner).also { window ->
            window.background = Color.BLACK
            window.contentPane.background = Color.BLACK
            window.focusableWindowState = false
            window.setType(Window.Type.POPUP)
            shieldWindow = window
        }

    /** [region]: what to cover, in screen coordinates; the window's content when null. */
    fun showForActiveWindow(region: Rectangle? = null) {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        SwingUtilities.invokeLater {
            val owner = activeOwnerWindow() ?: return@invokeLater
            val bounds = region ?: ownerContentBounds(owner) ?: return@invokeLater
            hideTimer?.stop()
            hideTimer = null

            val shield = shieldFor(owner)
            if (stillShown) {
                // Back to plain black after a still.
                shield.contentPane = JPanel().apply { background = Color.BLACK }
                stillShown = false
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
            // A still comes down once the next player's page has had a few frames to draw its pictures.
            val delay = if (stillShown && delayMs < STILL_MIN_MS) STILL_MIN_MS else delayMs
            hideTimer = Timer(delay) {
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
            if (stillShown) {
                shield.contentPane = JPanel().apply { background = Color.BLACK }
                stillShown = false
            }
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

    private const val STILL_MIN_MS = 220
}
