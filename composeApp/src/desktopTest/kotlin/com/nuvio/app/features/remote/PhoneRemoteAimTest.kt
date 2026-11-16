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
    fun slowOverATargetHeadingForItsCentreIsSlowedDown() {
        // Left of the centre, moving right toward it, at a crawl: the full friction (55%), and no pull on top.
        val (x, y) = AimAssist.adjust(10.0, 0.0, Point(120, 130), pxPerSecond = 0.0, over = button, near = button)
        assertEquals(5.5, x, 1e-9)
        assertEquals(0.0, y, 1e-9)
        val (faster, _) = AimAssist.adjust(10.0, 0.0, Point(120, 130), pxPerSecond = 550.0, over = button, near = button)
        assertTrue(faster > x, "less help at half the slow limit ($faster vs $x)")
    }

    @Test
    fun leavingATargetIsNeitherSlowedMuchNorPulledBack() {
        // Right of the centre, moving further right: away.
        val (x, y) = AimAssist.adjust(10.0, 0.0, Point(200, 130), pxPerSecond = 0.0, over = button, near = button)
        // A quarter of the friction (1 - 0.45 * 0.25), and no pull back toward the centre.
        assertEquals(10.0 * (1 - (1 - AimAssist.FRICTION) * AimAssist.AWAY_FRICTION_SHARE), x, 1e-9)
        assertEquals(0.0, y, 1e-9)
    }

    @Test
    fun slowNearATargetIsDrawnTowardItButNoMoreThanTheMoveItself() {
        // Right of the button and below it, moving up and to the left: toward it.
        val cursor = Point(250, 190)
        val (x, y) = AimAssist.adjust(-3.0, -5.0, cursor, pxPerSecond = 100.0, over = null, near = button)
        assertTrue(x < -3.0, "pulled further left toward the centre ($x)")
        val pull = hypot(x + 3.0, y + 5.0)
        assertTrue(pull <= hypot(3.0, 5.0) * AimAssist.PULL_CAP + 1e-9, "pull $pull capped by the step")
    }

    @Test
    fun goingPastATargetIsNotPulledTowardIt() {
        // Beside the button, moving straight up past it (across, not toward): left alone.
        val (x, y) = AimAssist.adjust(0.0, -6.0, Point(250, 130), pxPerSecond = 100.0, over = null, near = button)
        assertEquals(0.0, x, 1e-9)
        assertEquals(-6.0, y, 1e-9)
    }

    @Test
    fun strengthScalesTheHelpAndOffTurnsItOff() {
        val cursor = Point(120, 130)
        fun moved(strength: Double) = AimAssist.adjust(10.0, 0.0, cursor, 0.0, over = button, near = button, strength = strength).first
        assertEquals(10.0, moved(0.0), 1e-9)
        val light = moved(0.35)
        val strong = moved(1.0)
        assertTrue(strong < light && light < 10.0, "strong $strong < light $light < untouched")
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
