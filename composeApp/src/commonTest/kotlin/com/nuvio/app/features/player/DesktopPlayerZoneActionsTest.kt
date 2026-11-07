package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlayerZoneActionsTest {
    @Test
    fun `nothing stored is the original speed seek volume split`() {
        val actions = DesktopPlayerZoneActions.fromStorage(null)
        assertEquals(DesktopPlayerZoneActions(), actions)
        assertEquals("speed,seek,volume", actions.hudValue(DesktopPlayerZoneInput.Wheel))
    }

    @Test
    fun `stored actions round trip and bad entries fall back per zone`() {
        val actions = DesktopPlayerZoneActions(
            left = DesktopPlayerZoneAction.FastForward,
            middle = DesktopPlayerZoneAction.None,
            right = DesktopPlayerZoneAction.Seek,
        )
        assertEquals(actions, DesktopPlayerZoneActions.fromStorage(actions.toStorage()))
        assertEquals(
            DesktopPlayerZoneActions(middle = DesktopPlayerZoneAction.Volume),
            DesktopPlayerZoneActions.fromStorage("bogus,volume"),
        )
    }

    @Test
    fun `fast forward is drag only and reaches the wheel HUD as none`() {
        val actions = DesktopPlayerZoneActions(left = DesktopPlayerZoneAction.FastForward)
        assertEquals("fastforward,seek,volume", actions.hudValue(DesktopPlayerZoneInput.Drag))
        assertEquals("none,seek,volume", actions.hudValue(DesktopPlayerZoneInput.Wheel))
    }

    @Test
    fun `every button drag keeps fast forward and the HUD keys match controls js`() {
        val actions = DesktopPlayerZoneActions(left = DesktopPlayerZoneAction.FastForward)
        listOf(DesktopPlayerZoneInput.RightDrag, DesktopPlayerZoneInput.Back, DesktopPlayerZoneInput.Forward).forEach {
            assertEquals("fastforward,seek,volume", actions.hudValue(it))
        }
        // ZONE_INPUT_BUTTONS in controls.js is keyed by these.
        assertEquals(
            listOf("off", "wheel", "drag", "rmb", "xb1", "xb2"),
            DesktopPlayerZoneInput.entries.map { it.hudKey },
        )
        assertEquals(DesktopPlayerZoneInput.Back, DesktopPlayerZoneInput.fromStorage("Back"))
    }
}
