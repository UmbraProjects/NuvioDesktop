package com.nuvio.app.features.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import javax.swing.SwingUtilities
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneRemoteAimTest {
    private val button = Rectangle(100, 100, 120, 60) // centre (160, 130)
    private val screen = Rectangle(0, 0, 1920, 1080)

    @Test
    fun aFastSweepIsNeverBent() {
        val (x, y) = AimAssist.adjust(40.0, 0.0, Point(150, 120), pxPerSecond = 2_400.0, over = button, near = button)
        assertEquals(40.0, x)
        assertEquals(0.0, y)
    }

    @Test
    fun slowOverATargetIsSlowedDown() {
        val (x, _) = AimAssist.adjust(10.0, 0.0, Point(160, 130), pxPerSecond = 0.0, over = button, near = button)
        // Friction to 55% at a crawl; the cursor is on the centre, so no pull.
        assertEquals(5.5, x, 0.01)
        val (faster, _) = AimAssist.adjust(10.0, 0.0, Point(160, 130), pxPerSecond = 550.0, over = button, near = button)
        assertTrue(faster > 5.5 && faster < 10.0, "half the slow limit: half the friction ($faster)")
    }

    @Test
    fun slowNearATargetIsDrawnTowardItButNoMoreThanTheMoveItself() {
        // Moving up past the button's right side, 60 px off its centre line.
        val cursor = Point(230, 160)
        val (x, y) = AimAssist.adjust(0.0, -6.0, cursor, pxPerSecond = 100.0, over = null, near = button)
        assertTrue(x < 0, "pulled left toward the centre ($x)")
        val pull = hypot(x, y + 6.0)
        assertTrue(pull <= 6.0 * AimAssist.PULL_CAP + 1e-9, "pull $pull capped by the step")
    }

    @Test
    fun aStillPhoneNeverMovesTheCursor() {
        assertEquals(0.0 to 0.0, AimAssist.adjust(0.0, 0.0, Point(230, 160), 0.0, over = button, near = button))
    }

    @Test
    fun aTargetFarAwayDoesNotPull() {
        val (x, y) = AimAssist.adjust(0.0, -6.0, Point(800, 600), pxPerSecond = 100.0, over = null, near = button)
        assertEquals(0.0, x)
        assertEquals(-6.0, y)
    }

    @Test
    fun wholePageBackgroundsAreNotTargets() {
        assertTrue(AimAssist.isTargetSized(button, screen))
        assertFalse(AimAssist.isTargetSized(Rectangle(0, 0, 1920, 900), screen))
        assertFalse(AimAssist.isTargetSized(Rectangle(10, 10, 4, 4), screen))
    }

    @Test
    fun targetsAreReadFromTheWindowsAccessibilityTree() {
        if (GraphicsEnvironment.isHeadless()) return
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow()
            window.setSize(500, 420)
            window.setLocation(40, 40)
            window.setContent {
                Column {
                    Box(Modifier.size(120.dp, 60.dp).clickable { })
                    Spacer(Modifier.size(80.dp))
                    Text("Just text")
                }
            }
            window.isVisible = true
        }
        try {
            Thread.sleep(1_500)
            val origin = window.contentPane.locationOnScreen
            // Java's screen coordinates are already scaled for the display (as the cursor's are): dp = units here.
            val density = 1.0
            val boxCentre = Point(origin.x + (60 * density).toInt(), origin.y + (30 * density).toInt())
            fun refreshAt(point: Point) {
                Thread.sleep(120) // past the refresh interval
                AimTargets.refresh(point, screen, 90)
                SwingUtilities.invokeAndWait { } // the lookup runs on the Swing thread
            }
            refreshAt(boxCentre)
            assertNotNull(AimTargets.over, "the clickable box under the cursor")
            assertTrue(AimTargets.over!!.contains(boxCentre))

            // Just below the box: not over it, but near enough to be pulled.
            val below = Point(boxCentre.x, origin.y + (60 * density).toInt() + 30)
            refreshAt(below)
            assertNull(AimTargets.over)
            assertNotNull(AimTargets.near, "the box is within reach")

            // Over the text, far from the box: nothing.
            val text = Point(origin.x + (20 * density).toInt(), origin.y + (150 * density).toInt())
            refreshAt(text)
            assertNull(AimTargets.over)
        } finally {
            AimTargets.clear()
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }
}
