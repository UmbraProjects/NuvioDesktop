package com.nuvio.app.features.remote

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneRemoteServerTest {
    private val keys = CopyOnWriteArrayList<RemoteKey>()
    private val texts = CopyOnWriteArrayList<String>()
    private val commands = CopyOnWriteArrayList<String>()
    private val tokens = mutableSetOf<String>()
    private val discoveryPort = DatagramSocket(0).use { it.localPort }

    private val server = PhoneRemoteServer(
        name = { "Living Room PC" },
        pairing = object : PhoneRemotePairing {
            override fun pin() = "123456"
            override fun isKnownToken(token: String) = token in tokens
            override fun issueToken(deviceName: String) = "tok-$deviceName".replace(' ', '_').also { tokens += it }
        },
        listener = object : PhoneRemoteListener {
            override fun onKey(key: RemoteKey) {
                keys += key
            }

            override fun onText(text: String) {
                texts += text
            }

            override fun onSeek(positionMs: Long) {
                commands += "seek $positionMs"
            }

            override fun onVolume(volume: Int) {
                commands += "volume $volume"
            }

            override fun onOpen(url: String): String? = if ("youtu" in url) "a YouTube video".also { commands += "open $url" } else null
        },
        preferredPort = 0,
        discoveryPort = discoveryPort,
    ).also { it.start() }

    @AfterTest
    fun tearDown() = server.stop()

    private inner class Phone : AutoCloseable {
        val socket = Socket(InetAddress.getLoopbackAddress(), server.port!!).apply { soTimeout = 3_000 }
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)

        fun send(line: String) {
            writer.write(line + "\n")
            writer.flush()
        }

        fun receive(): String? = reader.readLine()

        override fun close() = socket.close()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    }

    @Test
    fun pairsWithThePinThenDeliversKeysAndText() {
        Phone().use { phone ->
            phone.send("HELLO 1 - ${PhoneRemoteProtocol.encode("Mick's Pixel")}")
            assertEquals("PIN_REQUIRED", phone.receive())
            phone.send("PAIR 000000")
            assertEquals("BAD_PIN", phone.receive())
            phone.send("PAIR 123456")
            assertEquals("PAIRED tok-Mick's_Pixel ${PhoneRemoteProtocol.encode("Living Room PC")}", phone.receive())

            phone.send("KEY up")
            phone.send("KEY select")
            phone.send("KEY not-a-key")
            phone.send("TEXT ${PhoneRemoteProtocol.encode("blade runner 2049")}")
            phone.send("PING")
            assertEquals("PONG", phone.receive())
        }
        waitFor { texts.isNotEmpty() }
        assertEquals(listOf(RemoteKey.Up, RemoteKey.Select), keys.toList())
        assertEquals(listOf("blade runner 2049"), texts.toList())
    }

    @Test
    fun aSubscribedPhoneHearsTheStateAndCanSeekSetVolumeAndOpen() {
        tokens += "saved"
        server.publishState(PhoneRemoteProtocol.stateLine(RemoteNowPlaying(kind = "music", title = "Billie Jean", positionMs = 1_000L)))
        Phone().use { phone ->
            phone.send("HELLO 1 saved Phone")
            assertEquals("WELCOME Living%20Room%20PC", phone.receive())
            // Nothing is pushed before the phone asks for it, so an older remote never sees a line it does not know.
            phone.send("PING")
            assertEquals("PONG", phone.receive())
            phone.send("SUBSCRIBE")
            assertEquals("STATE music 0 1000 0 - 100 0 Billie%20Jean - -", phone.receive())
            server.publishState(PhoneRemoteProtocol.IDLE)
            assertEquals("IDLE", phone.receive())

            phone.send("SEEK 93000")
            phone.send("VOLUME 40")
            phone.send("SEEK -5")
            phone.send("OPEN ${PhoneRemoteProtocol.encode("https://youtu.be/dQw4w9WgXcQ")}")
            assertEquals("OPENING a%20YouTube%20video", phone.receive())
            phone.send("OPEN ${PhoneRemoteProtocol.encode("https://example.com/page")}")
            assertEquals("CANT_OPEN", phone.receive())
        }
        assertEquals(listOf("seek 93000", "volume 40", "open https://youtu.be/dQw4w9WgXcQ"), commands.toList())
    }

    @Test
    fun aKnownTokenSkipsThePin() {
        tokens += "saved"
        Phone().use { phone ->
            phone.send("HELLO 1 saved Phone")
            assertEquals("WELCOME Living%20Room%20PC", phone.receive())
            phone.send("KEY back")
            phone.send("PING")
            assertEquals("PONG", phone.receive())
        }
        assertEquals(listOf(RemoteKey.Back), keys.toList())
    }

    @Test
    fun nothingIsDeliveredBeforePairing() {
        Phone().use { phone ->
            phone.send("KEY up")
            assertNull(phone.receive())
        }
        Phone().use { phone ->
            phone.send("HELLO 1 wrong-token Phone")
            assertEquals("PIN_REQUIRED", phone.receive())
            phone.send("KEY up")
            assertNull(phone.receive())
        }
        assertTrue(keys.isEmpty())
    }

    @Test
    fun locksOutAfterRepeatedWrongPins() {
        Phone().use { phone ->
            phone.send("HELLO 1 - Phone")
            assertEquals("PIN_REQUIRED", phone.receive())
            repeat(PhoneRemoteProtocol.MAX_PIN_ATTEMPTS - 1) {
                phone.send("PAIR 999999")
                assertEquals("BAD_PIN", phone.receive())
            }
            phone.send("PAIR 999999")
            assertEquals("LOCKED", phone.receive())
        }
        // Even the right PIN is refused while locked.
        Phone().use { phone ->
            phone.send("HELLO 1 - Phone")
            assertEquals("PIN_REQUIRED", phone.receive())
            phone.send("PAIR 123456")
            assertEquals("LOCKED", phone.receive())
        }
    }

    @Test
    fun answersDiscovery() {
        DatagramSocket().use { udp ->
            udp.soTimeout = 3_000
            val request = PhoneRemoteProtocol.DISCOVER_REQUEST.toByteArray()
            udp.send(DatagramPacket(request, request.size, InetAddress.getLoopbackAddress(), discoveryPort))
            val buffer = ByteArray(512)
            val reply = DatagramPacket(buffer, buffer.size)
            udp.receive(reply)
            assertEquals(
                "NUVIO_REMOTE 1 ${server.port} Living%20Room%20PC",
                String(reply.data, 0, reply.length, StandardCharsets.UTF_8),
            )
        }
    }

    @Test
    fun rejectsAnotherProtocolVersion() {
        Phone().use { phone ->
            phone.send("HELLO 9 - Phone")
            assertEquals("UNSUPPORTED 1", phone.receive())
        }
    }

    @Test
    fun parsesTheWireNames() {
        RemoteKey.entries.forEach { key -> assertEquals(key, RemoteKey.fromWire(key.wire)) }
        assertEquals(RemoteMessage.Text("a b+c\n"), PhoneRemoteProtocol.parse("TEXT ${PhoneRemoteProtocol.encode("a b+c\n")}"))
        assertNull(PhoneRemoteProtocol.parse("HELLO x - Phone"))
        assertNull(PhoneRemoteProtocol.parse(""))
    }
}
