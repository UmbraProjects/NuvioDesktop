package com.spydermahn.nuvioremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun theMagicPacketIsSixFFsAndSixteenAddresses() {
        val mac = WakeOnLan.macBytes("001A2B3C4D5E")!!
        assertArrayEquals(byteArrayOf(0x00, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E), mac)
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertEquals((0..5).map { 0xFF.toByte() }, packet.take(6))
        assertArrayEquals(mac, packet.copyOfRange(96, 102))
        assertNull(WakeOnLan.macBytes("00:1A:2B"))
        assertNull(WakeOnLan.macBytes("ZZ1A2B3C4D5E"))
    }

    @Test
    fun readsThePcsWakeLine() {
        // The line PhoneRemoteWakeTest on the PC side produces.
        val wake = ServerLine.parse("WAKE 001A2B3C4D5E,A0B1C2D3E4F5 192.168.1.255", 0L) as ServerLine.Wake
        assertEquals(listOf("001A2B3C4D5E", "A0B1C2D3E4F5"), wake.macs)
        assertEquals(listOf("192.168.1.255"), wake.broadcasts)
        assertEquals(ServerLine.Wake(emptyList(), emptyList()), ServerLine.parse("WAKE - -", 0L))
    }
}
