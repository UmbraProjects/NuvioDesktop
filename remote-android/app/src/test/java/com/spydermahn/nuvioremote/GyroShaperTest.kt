package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroShaperTest {
    private val flat = floatArrayOf(0f, 0f, 9.81f) // screen up, held like a remote
    private val upright = floatArrayOf(0f, 9.81f, 0f) // top up, screen facing you

    private fun run(shaper: GyroShaper, gyro: FloatArray, gravity: FloatArray?, samples: Int = 50): Pair<Int, Int> {
        var x = 0
        var y = 0
        repeat(samples) {
            val (dx, dy) = shaper.step(gyro, gravity, 0.02f)
            x += dx
            y += dy
        }
        return x to y
    }

    @Test
    fun turningLeftMovesTheCursorLeftInEitherGrip() {
        // Anticlockwise seen from above: about z when flat, about y when upright.
        val (flatX, flatY) = run(GyroShaper(), floatArrayOf(0f, 0f, 0.5f), flat)
        val (uprightX, uprightY) = run(GyroShaper(), floatArrayOf(0f, 0.5f, 0f), upright)
        assertTrue(flatX < 0)
        assertTrue(uprightX < 0)
        assertEquals(0, flatY)
        assertEquals(0, uprightY)
        assertEquals(flatX.toFloat(), uprightX.toFloat(), 2f)
    }

    @Test
    fun tiltingTheTopUpMovesTheCursorUp() {
        val (x, y) = run(GyroShaper(), floatArrayOf(0.5f, 0f, 0f), flat)
        assertEquals(0, x)
        assertTrue(y < 0)
    }

    @Test
    fun aPhoneAtRestDoesNotDrift() {
        // A gyro's noise floor, below the dead zone.
        assertEquals(0 to 0, run(GyroShaper(), floatArrayOf(0.01f, -0.01f, 0.015f), flat, samples = 500))
    }

    @Test
    fun aSecondOfMediumTurnCrossesMostOfTheScreen() {
        // 0.5 rad/s for one second is about 29 degrees.
        val (x, _) = run(GyroShaper(), floatArrayOf(0f, 0f, -0.5f), flat)
        assertTrue("moved $x", x in 7_000..13_000)
    }

    @Test
    fun fastTurnsGoFurtherPerDegreeThanSlowOnes() {
        // The same 0.4 rad of turn, slowly and quickly.
        val (slow, _) = run(GyroShaper(), floatArrayOf(0f, 0f, -0.2f), flat, samples = 100)
        val (fast, _) = run(GyroShaper(), floatArrayOf(0f, 0f, -2f), flat, samples = 10)
        assertTrue("slow $slow fast $fast", fast > slow * 1.5)
    }

    @Test
    fun speedScalesTheMotion() {
        val (normal, _) = run(GyroShaper(PointerSpeed.Normal), floatArrayOf(0f, 0f, -0.5f), flat)
        val (fast, _) = run(GyroShaper(PointerSpeed.Fast), floatArrayOf(0f, 0f, -0.5f), flat)
        assertEquals(normal * PointerSpeed.Fast.factor, fast.toFloat(), normal * 0.05f)
    }

    @Test
    fun withoutGravityThePhoneIsTakenAsFlat() {
        val (x, _) = run(GyroShaper(), floatArrayOf(0f, 0f, 0.5f), null)
        assertTrue(x < 0)
    }
}
