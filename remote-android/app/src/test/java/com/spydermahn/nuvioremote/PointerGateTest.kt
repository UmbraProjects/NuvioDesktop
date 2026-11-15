package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerGateTest {
    private val sent = mutableListOf<Pair<Int, Int>>()
    private var presses = 0
    private fun PointerGate.turn(dx: Int, dy: Int) = gyro(dx, dy, send = { x, y -> sent += x to y }, pressLeft = { presses++ })

    @Test
    fun theCursorMovesFreelyAndHoldsStillUnderATouch() {
        val gate = PointerGate()
        gate.turn(5, 3)
        gate.fingerDown()
        gate.turn(40, 0) // the hand twitches as the thumb taps
        assertEquals(PointerGate.Lift.Nothing, gate.fingerUp())
        gate.turn(1, 1)
        assertEquals(listOf(5 to 3, 1 to 1), sent)
    }

    @Test
    fun aStillHoldIsARightClick() {
        val gate = PointerGate()
        gate.fingerDown()
        gate.hold()
        gate.turn(30, -20) // a steady hand, well under the drag threshold
        assertEquals(PointerGate.Lift.RightClick, gate.fingerUp())
        assertEquals(0, presses)
        assertEquals(emptyList<Pair<Int, Int>>(), sent)
    }

    @Test
    fun holdingAndTurningDragsFromWhereTheHoldBegan() {
        val gate = PointerGate(dragStartUnits = 200)
        gate.fingerDown()
        gate.hold()
        gate.turn(120, 0)
        gate.turn(100, 0) // 220 crosses the threshold: press, then the whole turn so far
        gate.turn(50, 10)
        assertEquals(1, presses)
        assertEquals(listOf(220 to 0, 50 to 10), sent)
        assertEquals(PointerGate.Lift.EndDrag, gate.fingerUp())
        // Back to free pointing after the drop.
        gate.turn(2, 2)
        assertEquals(2 to 2, sent.last())
    }
}
