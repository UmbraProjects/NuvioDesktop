package com.nuvio.app.features.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GamepadCursorTest {
    private val max = 1_500.0

    @Test
    fun restingStickDoesNotMove() {
        assertEquals(0.0 to 0.0, GamepadCursor.pointerVelocity(0.1f, -0.08f, max))
    }

    @Test
    fun fullTiltIsFullSpeedAndScreenYIsDown() {
        val (x, y) = GamepadCursor.pointerVelocity(0f, 1f, max) // XInput up
        assertEquals(0.0, x, 1e-6)
        assertEquals(-max, y, 1e-6)
        val (rx, _) = GamepadCursor.pointerVelocity(1f, 0f, max)
        assertEquals(max, rx, 1e-6)
    }

    @Test
    fun lowTiltIsFineAiming() {
        val (half, _) = GamepadCursor.pointerVelocity(0.5f, 0f, max)
        // The curve keeps half tilt well under half speed.
        assertTrue(half in 1.0..(max * 0.25))
    }

    @Test
    fun diagonalKeepsItsDirection() {
        val (x, y) = GamepadCursor.pointerVelocity(0.7f, 0.7f, max)
        assertEquals(abs(x), abs(y), 1e-6)
        assertTrue(x > 0 && y < 0)
        assertTrue(hypot(x, y) <= max + 1e-6)
    }

    @Test
    fun scrollNeedsAFirmPushAndDownIsPositive() {
        assertEquals(0.0 to 0.0, GamepadCursor.scrollRate(0f, 0.25f))
        val (_, down) = GamepadCursor.scrollRate(0f, -1f)
        assertEquals(GamepadCursor.MAX_NOTCHES_PER_SECOND, down, 1e-6)
    }

    @Test
    fun holdTogglesOnceAndTapIsSentOnRelease() {
        val hold = CursorHold(holdMs = 450)
        assertEquals(CursorHold.Result.None, hold.update(down = true, now = 0))
        assertEquals(CursorHold.Result.Hold, hold.update(down = true, now = 460))
        assertEquals(CursorHold.Result.None, hold.update(down = true, now = 900))
        assertEquals(CursorHold.Result.None, hold.update(down = false, now = 950))

        assertEquals(CursorHold.Result.None, hold.update(down = true, now = 2_000))
        assertEquals(CursorHold.Result.Tap, hold.update(down = false, now = 2_100))
        assertEquals(CursorHold.Result.None, hold.update(down = false, now = 2_200))
    }
}
