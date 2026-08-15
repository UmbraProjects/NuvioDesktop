package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

/**
 * The remembered windowed placement.
 *
 * Coordinates are AWT screen coordinates: Compose Desktop hands `WindowState` positions and sizes
 * straight to `java.awt.Window` without a density conversion, so these values, `Window.bounds` and
 * `GraphicsConfiguration.bounds` all live in the same space and can be compared directly.
 *
 * [maximized] records that the window was maximized when it was last windowed; [x]/[y]/[width]/
 * [height] then hold the floating rect to restore to when it is un-maximized.
 */
internal data class DesktopWindowGeometry(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val maximized: Boolean = false,
)

/** A connected display: [usable] is [bounds] minus OS-reserved areas (taskbar, dock, menu bar). */
internal data class DesktopScreenArea(val bounds: Rectangle, val usable: Rectangle)

/** Anything smaller is a collapsed or garbage rect rather than a window the user chose. */
internal const val DesktopWindowMinWidth = 640f
internal const val DesktopWindowMinHeight = 420f

// A remembered rect has to keep at least this much of itself on a connected display to be treated
// as still belonging to that display. Sized so a window whose title bar remains grabbable survives
// and gets nudged back on-screen, while one left on a monitor that is now gone does not.
private const val MinVisibleWidth = 200
private const val MinVisibleHeight = 120

internal object DesktopWindowModeStorage {
    private val store = DesktopStorage.store("nuvio_window_state")

    fun loadWindowedGeometry(): DesktopWindowGeometry? {
        val x = store.getFloat("window_x") ?: return null
        val y = store.getFloat("window_y") ?: return null
        val width = store.getFloat("window_width") ?: return null
        val height = store.getFloat("window_height") ?: return null
        return DesktopWindowGeometry(
            x = x,
            y = y,
            width = width,
            height = height,
            maximized = store.getBoolean("window_maximized") ?: false,
        )
    }

    /**
     * The geometry to open with, already reconciled with the displays connected right now. Null
     * means the caller should fall back to its default size and centred position.
     */
    fun restoredWindowedGeometry(): DesktopWindowGeometry? =
        resolveWindowedGeometry(loadWindowedGeometry(), connectedScreenAreas())

    fun saveWindowedGeometry(value: DesktopWindowGeometry) {
        store.putFloat("window_x", value.x)
        store.putFloat("window_y", value.y)
        store.putFloat("window_width", value.width)
        store.putFloat("window_height", value.height)
        store.putBoolean("window_maximized", value.maximized)
    }
}

internal fun connectedScreenAreas(): List<DesktopScreenArea> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val configuration = device.defaultConfiguration
        val bounds = Rectangle(configuration.bounds)
        val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(configuration) }.getOrNull()
        val usable = if (insets == null) {
            Rectangle(bounds)
        } else {
            Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                (bounds.width - insets.left - insets.right).coerceAtLeast(1),
                (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
            )
        }
        DesktopScreenArea(bounds, usable)
    }
}.getOrDefault(emptyList())

/**
 * Maps a remembered window rect onto the displays that exist now.
 *
 * Returns null when the rect cannot be honoured — nothing saved, a nonsense or collapsed rect, a
 * headless environment, or a window that lived on a display since unplugged or moved elsewhere in
 * the desktop layout — so the caller opens at its default centred placement instead of somewhere
 * the user cannot reach. Otherwise the rect is clamped into the usable area of the display it
 * overlaps most, which also covers the softer cases: a lower resolution than last time, a taskbar
 * that moved, or a window left hanging off an edge.
 */
internal fun resolveWindowedGeometry(
    saved: DesktopWindowGeometry?,
    screens: List<DesktopScreenArea>,
): DesktopWindowGeometry? {
    if (saved == null || screens.isEmpty()) return null
    if (!saved.x.isFinite() || !saved.y.isFinite() || !saved.width.isFinite() || !saved.height.isFinite()) {
        return null
    }
    if (saved.width < DesktopWindowMinWidth || saved.height < DesktopWindowMinHeight) return null

    val rect = Rectangle(
        saved.x.roundToInt(),
        saved.y.roundToInt(),
        saved.width.roundToInt(),
        saved.height.roundToInt(),
    )
    val screen = screens.maxByOrNull { visibleArea(rect, it.usable) } ?: return null
    val visible = rect.intersection(screen.usable)
    if (visible.width < MinVisibleWidth || visible.height < MinVisibleHeight) return null

    val usable = screen.usable
    val width = rect.width.coerceAtMost(usable.width)
    val height = rect.height.coerceAtMost(usable.height)
    val maxX = (usable.x + usable.width - width).coerceAtLeast(usable.x)
    val maxY = (usable.y + usable.height - height).coerceAtLeast(usable.y)
    return DesktopWindowGeometry(
        x = rect.x.coerceIn(usable.x, maxX).toFloat(),
        y = rect.y.coerceIn(usable.y, maxY).toFloat(),
        width = width.toFloat(),
        height = height.toFloat(),
        maximized = saved.maximized,
    )
}

private fun visibleArea(rect: Rectangle, usable: Rectangle): Long {
    val intersection = rect.intersection(usable)
    // Rectangle.intersection reports negative extents for disjoint rects.
    return intersection.width.coerceAtLeast(0).toLong() * intersection.height.coerceAtLeast(0).toLong()
}
