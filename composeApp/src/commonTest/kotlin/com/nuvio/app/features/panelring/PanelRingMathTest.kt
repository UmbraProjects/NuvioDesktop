package com.nuvio.app.features.panelring

import com.nuvio.app.features.navbar.NavBarEntry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelRingMathTest {
    private fun near(expected: Float, actual: Float, tolerance: Float = 0.001f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

    @Test
    fun slotZeroIsAtTwelveOClockAndTheRestGoClockwise() {
        val (x0, y0) = PanelRingMath.slotUnitOffset(0, 4)
        near(0f, x0); near(-1f, y0)
        val (x1, y1) = PanelRingMath.slotUnitOffset(1, 4)
        near(1f, x1); near(0f, y1)
        val (x2, y2) = PanelRingMath.slotUnitOffset(2, 4)
        near(0f, x2); near(1f, y2)
        val (x3, y3) = PanelRingMath.slotUnitOffset(3, 4)
        near(-1f, x3); near(0f, y3)
    }

    @Test
    fun anglesAreClockwiseFromUpInScreenCoordinates() {
        near(0f, PanelRingMath.angleDegrees(0f, -1f))
        near(90f, PanelRingMath.angleDegrees(1f, 0f))
        near(180f, PanelRingMath.angleDegrees(0f, 1f))
        near(270f, PanelRingMath.angleDegrees(-1f, 0f))
        near(45f, PanelRingMath.angleDegrees(1f, -1f))
    }

    @Test
    fun insideTheDeadZoneAimsAtNothing() {
        assertNull(PanelRingMath.slotFor(0f, 0f, 8, deadZone = 0.4f))
        assertNull(PanelRingMath.slotFor(0.2f, -0.2f, 8, deadZone = 0.4f))
        assertEquals(0, PanelRingMath.slotFor(0f, -0.5f, 8, deadZone = 0.4f))
    }

    @Test
    fun aDirectionSnapsToTheNearestSlot() {
        // Ten slots, 36 degrees apart.
        assertEquals(0, PanelRingMath.slotFor(0f, -1f, 10, deadZone = 0.1f))
        assertEquals(0, PanelRingMath.slotFor(dirX(17f), dirY(17f), 10, deadZone = 0.1f))
        assertEquals(1, PanelRingMath.slotFor(dirX(19f), dirY(19f), 10, deadZone = 0.1f))
        assertEquals(5, PanelRingMath.slotFor(0f, 1f, 10, deadZone = 0.1f))
        // Just short of straight up from the left wraps to slot 0, not slot 10.
        assertEquals(0, PanelRingMath.slotFor(dirX(355f), dirY(355f), 10, deadZone = 0.1f))
        assertEquals(9, PanelRingMath.slotFor(dirX(330f), dirY(330f), 10, deadZone = 0.1f))
    }

    @Test
    fun theAimedSlotHoldsAPastItsEdgeSoTheAimDoesNotFlicker() {
        // Slot 0 spans -18..18 degrees; with 8 degrees of hysteresis it is kept until 26.
        assertEquals(0, PanelRingMath.slotFor(dirX(24f), dirY(24f), 10, deadZone = 0.1f, current = 0))
        assertEquals(1, PanelRingMath.slotFor(dirX(24f), dirY(24f), 10, deadZone = 0.1f, current = null))
        assertEquals(1, PanelRingMath.slotFor(dirX(28f), dirY(28f), 10, deadZone = 0.1f, current = 0))
        // A current slot that no longer exists is ignored.
        assertEquals(1, PanelRingMath.slotFor(dirX(28f), dirY(28f), 10, deadZone = 0.1f, current = 42))
    }

    @Test
    fun smallRingsStillWork() {
        assertNull(PanelRingMath.slotFor(1f, 0f, 0, deadZone = 0.1f))
        assertEquals(0, PanelRingMath.slotFor(0f, 1f, 1, deadZone = 0.1f))
        assertEquals(1, PanelRingMath.slotFor(0f, 1f, 2, deadZone = 0.1f))
    }

    @Test
    fun numbersRunOneToNineThenZero() {
        assertEquals("1", panelRingNumberLabel(0))
        assertEquals("9", panelRingNumberLabel(8))
        assertEquals("0", panelRingNumberLabel(9))
        assertNull(panelRingNumberLabel(10))
        assertEquals(0, panelRingIndexForDigit(1))
        assertEquals(9, panelRingIndexForDigit(0))
        assertNull(panelRingIndexForDigit(11))
    }

    @Test
    fun slotsFollowTheNavigationThenSettings() {
        val slots = panelRingSlots(listOf(NavBarEntry.Home, NavBarEntry.Library, NavBarEntry.Music))
        assertEquals(
            listOf(
                PanelRingSlot.Nav(NavBarEntry.Home),
                PanelRingSlot.Nav(NavBarEntry.Library),
                PanelRingSlot.Nav(NavBarEntry.Music),
                PanelRingSlot.Settings,
            ),
            slots,
        )
    }

    @Test
    fun controllerAimsCommitsAndSteps() {
        PanelRingController.close()
        PanelRingController.slots = panelRingSlots(listOf(NavBarEntry.Home, NavBarEntry.Search, NavBarEntry.Library))
        PanelRingController.open(PanelRingSource.Gamepad)
        PanelRingController.step(-1)
        assertEquals(3, PanelRingController.state.value?.hovered) // Settings, wrapping backwards
        PanelRingController.step(1)
        assertEquals(0, PanelRingController.state.value?.hovered)
        PanelRingController.aim(2)
        assertTrue(PanelRingController.commit())
        assertNull(PanelRingController.state.value)
        PanelRingController.open(PanelRingSource.Mouse)
        assertTrue(!PanelRingController.commit()) // nothing aimed: closes without switching
        assertNull(PanelRingController.state.value)
    }

    private fun dirX(degrees: Float) = kotlin.math.sin(degrees * kotlin.math.PI / 180.0).toFloat()
    private fun dirY(degrees: Float) = -kotlin.math.cos(degrees * kotlin.math.PI / 180.0).toFloat()
}
