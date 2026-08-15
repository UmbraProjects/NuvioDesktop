package com.nuvio.app.features.player.desktop

import com.nuvio.app.desktopDisplayMetrics
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.Window
import javax.swing.JComponent
import javax.swing.RootPaneContainer

/**
 * One-shot dump of every layer that decides how large the app's content is drawn, for the
 * "white lines / black bars along the edges in fullscreen" report.
 *
 * Borderless fullscreen is applied by native code (see [applyNativeBorderlessFullscreen]): the
 * window style loses its caption/frame and the HWND is moved to the monitor rect by a raw
 * `SetWindowPos`, both behind AWT's back. Everything downstream — AWT's cached frame insets, the
 * content pane's size, the Skia layer's size, and the native mpv surface positioned through
 * `SwingPanel` — derives its geometry from AWT's own bookkeeping and from the display's DPI scale.
 * Any one of those going stale shows up as content that is smaller than the window, with the
 * uncovered strip along the right/bottom edge painting whatever is behind it.
 *
 * Reported only at 300% display scale on a 4K TV, and cured (for that session) by toggling the
 * Windows display scale to 250% and back — i.e. by a DPI-change event, which is exactly what
 * re-syncs all of the above. The mismatch is presumably present at every scale and simply too small
 * to notice below 300%, where a stale frame inset is worth ~24-96 device pixels.
 *
 * Reads geometry in AWT user space and multiplies by the display scale, so the numbers below are
 * directly comparable to the monitor's real pixel size: on a 3840x2160 display in fullscreen every
 * `devicePx` width should be 3840, and any layer reporting less is the one that is out of sync.
 */
internal object DesktopWindowGeometryDiagnostics {

    fun log(window: Window, reason: String) {
        runCatching { dump(window, reason) }
            .onFailure { error ->
                System.err.println("Error: (WindowGeometry) dump failed for '$reason': $error")
            }
    }

    private fun dump(window: Window, reason: String) {
        val lines = StringBuilder()
        fun line(text: String) {
            lines.append("Info: (WindowGeometry) ").append(text).append('\n')
        }

        val configuration = window.graphicsConfiguration
        val scaleX = configuration?.defaultTransform?.scaleX ?: 1.0
        val scaleY = configuration?.defaultTransform?.scaleY ?: 1.0

        line("--- $reason ---")
        line(
            "window showing=${window.isShowing} focused=${window.isFocused} " +
                "gcScale=${scaleX}x$scaleY gcBoundsUser=${configuration?.bounds}"
        )
        line(
            "monitor devicePx=${(configuration?.bounds?.width ?: 0) * scaleX}" +
                "x${(configuration?.bounds?.height ?: 0) * scaleY}"
        )

        val bounds = window.bounds
        line(
            "awtWindow user=${bounds.width}x${bounds.height}@${bounds.x},${bounds.y} " +
                "devicePx=${bounds.width * scaleX}x${bounds.height * scaleY}"
        )
        // Windows constrains a window that still carries the maximized state to the work area even
        // when it is moved by an explicit SetWindowPos, which would leave the taskbar strip
        // uncovered along one edge. Entering borderless fullscreen never clears that state.
        val screenInsets = configuration?.let { Toolkit.getDefaultToolkit().getScreenInsets(it) }
        line(
            "frameState extended=${(window as? Frame)?.extendedState} " +
                "screenInsets user=$screenInsets " +
                "workAreaDevicePx=${
                    ((configuration?.bounds?.width ?: 0) - (screenInsets?.left ?: 0) -
                        (screenInsets?.right ?: 0)) * scaleX
                }x${
                    ((configuration?.bounds?.height ?: 0) - (screenInsets?.top ?: 0) -
                        (screenInsets?.bottom ?: 0)) * scaleY
                }"
        )
        // Non-zero insets after the caption/frame have been stripped natively is the primary
        // suspect: the content pane is laid out inside them, so the window keeps a strip of
        // itself that Compose never draws into.
        val insets = runCatching { window.insets }.getOrNull()
        line("awtInsets user=$insets devicePx=${insets?.let {
            "t${it.top * scaleY} b${it.bottom * scaleY} l${it.left * scaleX} r${it.right * scaleX}"
        }}")

        (window as? RootPaneContainer)?.let { rootPaneContainer ->
            val rootPane = rootPaneContainer.rootPane
            line("rootPane ${describe(rootPane, scaleX, scaleY)}")
            line("layeredPane ${describe(rootPaneContainer.layeredPane, scaleX, scaleY)}")
            line("contentPane ${describe(rootPaneContainer.contentPane, scaleX, scaleY)}")
        }

        val metrics = desktopDisplayMetrics()
        // The density SwingPanel uses to convert Compose pixels back into AWT user-space bounds
        // for the native mpv surface. It has to equal gcScale above, or the video surface ends up
        // sized by the ratio between them.
        line("desktopDisplayMetrics density=${metrics?.density} sizePx=${metrics?.sizePx}")

        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEachIndexed { index, device ->
            val deviceConfiguration = device.defaultConfiguration
            val deviceScale = deviceConfiguration.defaultTransform.scaleX
            // User-space bounds of different-DPI displays can overlap, which would make the
            // "which display is this window on" lookup in Platform.desktop.kt pick the wrong one.
            line(
                "screen[$index] id=${device.iDstring} scale=$deviceScale " +
                    "boundsUser=${deviceConfiguration.bounds} " +
                    "devicePx=${deviceConfiguration.bounds.width * deviceScale}" +
                    "x${deviceConfiguration.bounds.height * deviceScale}"
            )
        }

        (window as? Container)?.let { container ->
            line("component tree (user-space bounds):")
            appendTree(container, depth = 1, maxDepth = 5, scaleX = scaleX, scaleY = scaleY, out = ::line)
        }

        print(lines)
    }

    private fun appendTree(
        container: Container,
        depth: Int,
        maxDepth: Int,
        scaleX: Double,
        scaleY: Double,
        out: (String) -> Unit,
    ) {
        if (depth > maxDepth) return
        container.components.forEach { child ->
            out("  ".repeat(depth) + describe(child, scaleX, scaleY))
            (child as? Container)?.let { appendTree(it, depth + 1, maxDepth, scaleX, scaleY, out) }
        }
    }

    private fun describe(component: Component, scaleX: Double, scaleY: Double): String {
        val bounds = component.bounds
        val opacity = (component as? JComponent)?.let { " opaque=${it.isOpaque}" }.orEmpty()
        return "${component.javaClass.simpleName} user=${bounds.width}x${bounds.height}" +
            "@${bounds.x},${bounds.y} devicePx=${bounds.width * scaleX}x${bounds.height * scaleY}" +
            " visible=${component.isVisible}$opacity bg=${component.background}"
    }
}
