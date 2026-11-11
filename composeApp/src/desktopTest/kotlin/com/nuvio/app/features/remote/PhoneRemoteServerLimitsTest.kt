package com.nuvio.app.features.remote

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the remote's server does with connections that behave badly (fork:hardening, Nov 10 2026). */
class PhoneRemoteServerLimitsTest {
    private val servers = mutableListOf<PhoneRemoteServer>()

    private fun server(maxConnections: Int = 16, handshakeTimeoutMs: Long = 30_000L): PhoneRemoteServer =
        PhoneRemoteServer(
            name = { "Living Room PC" },
            pairing = object : PhoneRemotePairing {
                override fun pin() = "123456"
                override fun isKnownToken(token: String) = false
                override fun issueToken(deviceName: String) = "tok"
            },
            listener = object : PhoneRemoteListener {
                override fun onKey(key: RemoteKey) = Unit
                override fun onText(text: String) = Unit
            },
            preferredPort = 0,
            discoveryPort = DatagramSocket(0).use { it.localPort },
            maxConnections = maxConnections,
            handshakeTimeoutMs = handshakeTimeoutMs,
        ).also {
            it.start()
            servers += it
        }

    @AfterTest
    fun tearDown() = servers.forEach { it.stop() }

    private fun connect(port: Int): Socket =
        Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 3_000 }

    /** True once the server has closed this socket (end of stream, or a reset). */
    private fun closedByServer(socket: Socket): Boolean =
        try {
            BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine() == null
        } catch (_: IOException) {
            true
        }

    @Test
    fun connectionsBeyondTheCapAreClosedAndAFreedSlotIsReused() {
        val server = server(maxConnections = 2)
        val port = server.port!!
        val first = connect(port)
        val second = connect(port)
        // Let the accept loop count the first two before the third arrives.
        Thread.sleep(200)

        val third = connect(port)
        assertTrue(closedByServer(third), "the third connection should be refused")

        // One of the first two leaves: its slot is free again and the next phone gets a normal handshake.
        first.close()
        Thread.sleep(300)
        val fourth = connect(port)
        val writer = OutputStreamWriter(fourth.getOutputStream(), StandardCharsets.UTF_8)
        writer.write("HELLO 1 - Phone\n")
        writer.flush()
        val reader = BufferedReader(InputStreamReader(fourth.getInputStream(), StandardCharsets.UTF_8))
        assertEquals("PIN_REQUIRED", reader.readLine())

        listOf(second, third, fourth).forEach { runCatching { it.close() } }
    }

    @Test
    fun aPeerThatDripsBytesCannotHoldTheHandshakeOpen() {
        val server = server(handshakeTimeoutMs = 300L)
        val socket = connect(server.port!!)
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        // Never a newline, one byte every 100 ms: each byte restarts the socket's read timeout.
        try {
            repeat(30) {
                writer.write("H")
                writer.flush()
                Thread.sleep(100)
            }
        } catch (_: IOException) {
            // The server closed first, which is what is being tested.
        }
        assertTrue(closedByServer(socket), "the handshake deadline should have dropped the connection")
        socket.close()
    }
}
