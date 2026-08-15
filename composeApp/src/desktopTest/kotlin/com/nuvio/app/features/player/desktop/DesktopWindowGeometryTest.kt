package com.nuvio.app.features.player.desktop

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWindowGeometryTest {
    // A 1080p primary with a 40px taskbar, plus a second display to its right.
    private val primary = screen(0, 0, 1920, 1080, taskbarHeight = 40)
    private val secondary = screen(1920, 0, 1920, 1080, taskbarHeight = 40)

    @Test
    fun rectFullyOnAConnectedDisplayIsRestoredUnchanged() {
        val saved = DesktopWindowGeometry(x = 200f, y = 120f, width = 1280f, height = 820f)

        assertEquals(saved, resolveWindowedGeometry(saved, listOf(primary, secondary)))
    }

    @Test
    fun rectOnASecondDisplayIsRestoredWhileThatDisplayIsConnected() {
        val saved = DesktopWindowGeometry(x = 2100f, y = 100f, width = 1280f, height = 820f)

        assertEquals(saved, resolveWindowedGeometry(saved, listOf(primary, secondary)))
    }

    @Test
    fun rectOnAnUnpluggedDisplayFallsBackToTheDefault() {
        val saved = DesktopWindowGeometry(x = 2100f, y = 100f, width = 1280f, height = 820f)

        assertNull(resolveWindowedGeometry(saved, listOf(primary)))
    }

    @Test
    fun rectOnADisplayThatMovedToTheOtherSideFallsBackToTheDefault() {
        val movedLeft = screen(-1920, 0, 1920, 1080, taskbarHeight = 40)
        val saved = DesktopWindowGeometry(x = 2100f, y = 100f, width = 1280f, height = 820f)

        assertNull(resolveWindowedGeometry(saved, listOf(primary, movedLeft)))
    }

    @Test
    fun barelyVisibleRectIsTreatedAsBelongingToAMissingDisplay() {
        // Only 60px of the window still reaches the primary; the rest lived on a display that is
        // no longer connected, so restoring it would leave a sliver of window in the corner.
        val saved = DesktopWindowGeometry(x = 1860f, y = 100f, width = 1280f, height = 820f)

        assertNull(resolveWindowedGeometry(saved, listOf(primary)))
    }

    @Test
    fun mostlyVisibleRectIsNudgedFullyBackOnScreen() {
        val saved = DesktopWindowGeometry(x = 1000f, y = 900f, width = 1280f, height = 820f)

        val restored = assertNotNull(resolveWindowedGeometry(saved, listOf(primary)))
        assertEquals(640f, restored.x)
        assertEquals(220f, restored.y)
        assertEquals(1280f, restored.width)
        assertEquals(820f, restored.height)
        assertContainedIn(restored, primary.usable)
    }

    @Test
    fun rectLargerThanTheCurrentResolutionIsShrunkIntoTheUsableArea() {
        val downgraded = screen(0, 0, 1280, 720, taskbarHeight = 40)
        val saved = DesktopWindowGeometry(x = 300f, y = 200f, width = 2400f, height = 1300f)

        val restored = assertNotNull(resolveWindowedGeometry(saved, listOf(downgraded)))
        assertEquals(0f, restored.x)
        assertEquals(0f, restored.y)
        assertEquals(1280f, restored.width)
        assertEquals(680f, restored.height)
        assertContainedIn(restored, downgraded.usable)
    }

    @Test
    fun maximizedFlagSurvivesClamping() {
        val saved = DesktopWindowGeometry(x = 1000f, y = 900f, width = 1280f, height = 820f, maximized = true)

        val restored = assertNotNull(resolveWindowedGeometry(saved, listOf(primary)))
        assertTrue(restored.maximized)
    }

    @Test
    fun minimizedWindowOriginIsRejected() {
        // Windows reports (-32000, -32000) for an iconified window.
        val saved = DesktopWindowGeometry(x = -32000f, y = -32000f, width = 1280f, height = 820f)

        assertNull(resolveWindowedGeometry(saved, listOf(primary)))
    }

    @Test
    fun collapsedAndNonFiniteRectsAreRejected() {
        assertNull(resolveWindowedGeometry(DesktopWindowGeometry(0f, 0f, 320f, 240f), listOf(primary)))
        assertNull(resolveWindowedGeometry(DesktopWindowGeometry(0f, 0f, 1280f, Float.NaN), listOf(primary)))
        assertNull(
            resolveWindowedGeometry(
                DesktopWindowGeometry(Float.POSITIVE_INFINITY, 0f, 1280f, 820f),
                listOf(primary),
            ),
        )
    }

    @Test
    fun nothingSavedOrNoDisplaysFallsBackToTheDefault() {
        assertNull(resolveWindowedGeometry(null, listOf(primary)))
        assertNull(resolveWindowedGeometry(DesktopWindowGeometry(0f, 0f, 1280f, 820f), emptyList()))
    }

    private fun screen(x: Int, y: Int, width: Int, height: Int, taskbarHeight: Int): DesktopScreenArea =
        DesktopScreenArea(
            bounds = Rectangle(x, y, width, height),
            usable = Rectangle(x, y, width, height - taskbarHeight),
        )

    private fun assertContainedIn(geometry: DesktopWindowGeometry, usable: Rectangle) {
        assertTrue(geometry.x >= usable.x, "left edge ${geometry.x} escapes $usable")
        assertTrue(geometry.y >= usable.y, "top edge ${geometry.y} escapes $usable")
        assertTrue(geometry.x + geometry.width <= usable.x + usable.width, "right edge escapes $usable")
        assertTrue(geometry.y + geometry.height <= usable.y + usable.height, "bottom edge escapes $usable")
    }
}
