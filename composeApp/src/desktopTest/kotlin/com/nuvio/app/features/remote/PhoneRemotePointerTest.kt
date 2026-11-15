package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneRemotePointerTest {
    @Test
    fun thePointerLinesParse() {
        assertEquals(RemoteMessage.Point(120, -45), PhoneRemoteProtocol.parse("POINT 120 -45"))
        assertEquals(RemoteMessage.PointCentre, PhoneRemoteProtocol.parse("POINT_CENTRE"))
        assertEquals(RemoteMessage.Click(PointerButton.Left), PhoneRemoteProtocol.parse("CLICK left"))
        assertEquals(RemoteMessage.Click(PointerButton.Right), PhoneRemoteProtocol.parse("CLICK RIGHT"))
        assertEquals(RemoteMessage.Scroll(-3), PhoneRemoteProtocol.parse("SCROLL -3"))
        assertEquals(RemoteMessage.ScrollSideways(4), PhoneRemoteProtocol.parse("HSCROLL 4"))
        assertEquals(RemoteMessage.Press(PointerButton.Left), PhoneRemoteProtocol.parse("PRESS left"))
        assertEquals(RemoteMessage.Release(PointerButton.Left), PhoneRemoteProtocol.parse("RELEASE left"))
        assertNull(PhoneRemoteProtocol.parse("PRESS thumb"))
        assertNull(PhoneRemoteProtocol.parse("HSCROLL 0"))
    }

    @Test
    fun hugeStepsAreClampedAndBadOnesRefused() {
        assertEquals(
            RemoteMessage.Point(PhoneRemoteProtocol.MAX_POINT_STEP, -PhoneRemoteProtocol.MAX_POINT_STEP),
            PhoneRemoteProtocol.parse("POINT 999999 -999999"),
        )
        assertEquals(RemoteMessage.Scroll(PhoneRemoteProtocol.MAX_SCROLL), PhoneRemoteProtocol.parse("SCROLL 500"))
        assertNull(PhoneRemoteProtocol.parse("POINT 5"))
        assertNull(PhoneRemoteProtocol.parse("POINT a b"))
        assertNull(PhoneRemoteProtocol.parse("CLICK double"))
        assertNull(PhoneRemoteProtocol.parse("SCROLL 0"))
    }

    @Test
    fun stepsAreAShareOfTheScreenWidth() {
        val acc = PointerAccumulator()
        // A tenth of the width is 192 px on a 1080p screen and 384 on a 4K one.
        assertEquals(192 to -96, acc.step(1000, -500, 1920))
        assertEquals(384 to 0, PointerAccumulator().step(1000, 0, 3840))
    }

    @Test
    fun slowPointingKeepsItsFractions() {
        val acc = PointerAccumulator()
        // Two units is 0.384 px on 1920: nothing at first, then a pixel once enough has built up.
        val moved = (1..10).sumOf { acc.step(2, 0, 1920).first }
        assertEquals(3, moved)
        acc.reset()
        assertEquals(0 to 0, acc.step(2, 2, 1920))
    }
}
