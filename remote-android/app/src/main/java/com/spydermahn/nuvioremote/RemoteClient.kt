package com.spydermahn.nuvioremote

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The phone's half of the Nuvio remote protocol. Plain `java.net`, no Android types, so it can be
 * tested on a desktop JVM against the real server.
 *
 * Mirrors `PhoneRemoteProtocol.kt` in Nuvio Desktop (composeApp/src/desktopMain/.../features/remote);
 * change both together.
 */
object RemoteProtocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 47321
    const val DISCOVERY_PORT = 47320
    const val DISCOVER_REQUEST = "NUVIO_REMOTE_DISCOVER 1"

    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    fun decode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (_: IllegalArgumentException) {
        text
    }
}

/** The buttons the PC understands, by wire name. */
enum class RemoteKey(val wire: String) {
    Up("up"), Down("down"), Left("left"), Right("right"), Select("select"),
    Back("back"), Home("home"), Search("search"),
    PlayPause("playpause"), SeekBack("seekback"), SeekForward("seekforward"),
    VolumeUp("volup"), VolumeDown("voldown"), Mute("mute"),
    Subtitles("subtitles"), Audio("audio"),
    Backspace("backspace"), Enter("enter"),
}

data class FoundPc(val name: String, val host: String, val port: Int)

object Discovery {
    /**
     * Broadcasts a discovery request on every IPv4 network the phone is on (and the all-ones
     * address, for networks that only pass that) and collects replies for [timeoutMs].
     */
    fun find(timeoutMs: Int = 1500, port: Int = RemoteProtocol.DISCOVERY_PORT): List<FoundPc> {
        val found = linkedMapOf<String, FoundPc>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val request = RemoteProtocol.DISCOVER_REQUEST.toByteArray(Charsets.UTF_8)
            broadcastTargets().forEach { target ->
                runCatching { socket.send(DatagramPacket(request, request.size, target, port)) }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                parseReply(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address.hostAddress ?: continue)
                    ?.let { found["${it.host}:${it.port}"] = it }
            }
        }
        return found.values.toList()
    }

    internal fun parseReply(text: String, host: String): FoundPc? {
        val parts = text.trim().split(' ')
        if (parts.size < 4 || parts[0] != "NUVIO_REMOTE") return null
        val port = parts[2].toIntOrNull() ?: return null
        return FoundPc(name = RemoteProtocol.decode(parts[3]), host = host, port = port)
    }

    private fun broadcastTargets(): List<InetAddress> {
        val directed = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNull { it.broadcast }
        }.getOrDefault(emptyList())
        return (directed + InetAddress.getByName("255.255.255.255")).distinct()
    }
}

/** What happened when the phone said hello. */
sealed interface HelloResult {
    /** Trusted already; the session is ready for keys. */
    data class Welcome(val pcName: String) : HelloResult
    /** The PC wants the pairing code; call [RemoteSession.pair]. */
    data object PinRequired : HelloResult
    data class Refused(val reason: String) : HelloResult
}

sealed interface PairResult {
    data class Paired(val token: String, val pcName: String) : PairResult
    data object WrongPin : PairResult
    data object Locked : PairResult
    data class Failed(val reason: String) : PairResult
}

/**
 * One TCP session. Blocking calls; the app drives it from a single background thread so the order
 * of presses is kept exactly.
 */
class RemoteSession private constructor(private val socket: Socket) : AutoCloseable {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

    val isOpen: Boolean get() = !socket.isClosed

    fun hello(token: String?, deviceName: String): HelloResult {
        send("HELLO ${RemoteProtocol.VERSION} ${token ?: "-"} ${RemoteProtocol.encode(deviceName)}")
        val reply = readLine() ?: return HelloResult.Refused("The PC closed the connection.")
        return when {
            reply.startsWith("WELCOME") -> HelloResult.Welcome(RemoteProtocol.decode(reply.substringAfter(' ', "Nuvio")))
            reply == "PIN_REQUIRED" -> HelloResult.PinRequired
            reply.startsWith("UNSUPPORTED") -> HelloResult.Refused("This version of the app does not match Nuvio on the PC. Update both.")
            else -> HelloResult.Refused("Unexpected reply from the PC.")
        }
    }

    fun pair(pin: String): PairResult {
        send("PAIR ${pin.filter(Char::isDigit)}")
        val reply = readLine() ?: return PairResult.Failed("The PC closed the connection.")
        val parts = reply.split(' ')
        return when (parts[0]) {
            "PAIRED" -> PairResult.Paired(parts.getOrElse(1) { "" }, RemoteProtocol.decode(parts.getOrElse(2) { "Nuvio" }))
            "BAD_PIN" -> PairResult.WrongPin
            "LOCKED" -> PairResult.Locked
            else -> PairResult.Failed("Unexpected reply from the PC.")
        }
    }

    fun key(key: RemoteKey) = send("KEY ${key.wire}")

    fun text(text: String) {
        if (text.isNotEmpty()) send("TEXT ${RemoteProtocol.encode(text)}")
    }

    /** True if the PC answered; false means the session is dead and should be reopened. */
    fun ping(): Boolean = try {
        send("PING")
        readLine() == "PONG"
    } catch (_: IOException) {
        false
    }

    @Synchronized
    private fun send(line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    private fun readLine(): String? = try {
        reader.readLine()
    } catch (_: SocketTimeoutException) {
        null
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun open(host: String, port: Int, connectTimeoutMs: Int = 4000): RemoteSession {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.tcpNoDelay = true
                socket.soTimeout = 5000
                return RemoteSession(socket)
            } catch (error: IOException) {
                socket.close()
                throw error
            }
        }
    }
}
