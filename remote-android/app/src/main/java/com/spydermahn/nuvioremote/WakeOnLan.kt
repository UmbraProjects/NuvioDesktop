package com.spydermahn.nuvioremote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wakes a sleeping PC with a Wake-on-LAN "magic packet": six 0xFF bytes and the network card's address sixteen times,
 * broadcast over UDP on the home network. The PC tells the app its cards and broadcast addresses while connected (the
 * WAKE line), so this works once it has been paired and connected at least once with this Nuvio.
 */
object WakeOnLan {
    /** Twelve hex digits ("001A2B3C4D5E") to bytes; null for anything else. */
    fun macBytes(mac: String): ByteArray? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12 || hex.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun magicPacket(mac: ByteArray): ByteArray = ByteArray(6) { 0xFF.toByte() } + List(16) { mac }.reduce { a, b -> a + b }

    /** Sends the packet for every card to every target, on ports 9 and 7. Returns how many were sent. Blocking. */
    fun send(macs: List<String>, targets: List<String>): Int {
        var sent = 0
        val addresses = (targets + "255.255.255.255").distinct().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            macs.mapNotNull(::macBytes).forEach { mac ->
                val packet = magicPacket(mac)
                // The demo TV lives in the app: its packet is handed over rather than broadcast.
                if (DemoTv.HOST in targets) {
                    DemoTv.wake(packet)
                    sent++
                }
                addresses.forEach { address ->
                    listOf(9, 7).forEach { port ->
                        if (runCatching { socket.send(DatagramPacket(packet, packet.size, address, port)) }.isSuccess) sent++
                    }
                }
            }
        }
        return sent
    }
}
