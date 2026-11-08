package com.nuvio.app.features.remote

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The wire protocol between Nuvio and the Android remote (`remote-android/` in this repo).
 *
 * Deliberately tiny: one UTF-8 line per message over a plain TCP socket, so the phone side is a
 * `java.net.Socket` and a reader, with no HTTP or WebSocket layer on either end. The Android app
 * carries a copy of these constants in `RemoteProtocol.kt`; change both together.
 *
 * Discovery (UDP, [DISCOVERY_PORT]): the phone broadcasts [DISCOVER_REQUEST]; every Nuvio with the
 * remote turned on answers `NUVIO_REMOTE 1 <tcpPort> <name>` straight back to the sender.
 *
 * Session (TCP, [DEFAULT_PORT] or the next free port above it):
 * ```
 * phone: HELLO 1 <token or -> <device name>
 * pc:    WELCOME <pc name>              (a token it already knows)
 *    or: PIN_REQUIRED                   (then)  phone: PAIR <pin>
 *                                               pc:    PAIRED <token> <pc name> | BAD_PIN | LOCKED
 * phone: KEY <name> | TEXT <text> | PING
 * pc:    PONG                           (only for PING; keys and text get no reply)
 * ```
 * Every free-text field (names, text) is URL-encoded so a space or a newline can never split a line.
 */
internal object PhoneRemoteProtocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 47321
    const val PORT_ATTEMPTS = 10
    const val DISCOVERY_PORT = 47320
    const val DISCOVER_REQUEST = "NUVIO_REMOTE_DISCOVER 1"
    const val MAX_LINE_CHARS = 4096
    const val MAX_PIN_ATTEMPTS = 5
    const val PIN_LENGTH = 6

    fun encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
    fun decode(text: String): String = try {
        URLDecoder.decode(text, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        text
    }

    fun discoveryReply(tcpPort: Int, name: String) = "NUVIO_REMOTE $VERSION $tcpPort ${encode(name)}"

    /** Parses one line from the phone. Null for anything malformed or unknown. */
    fun parse(line: String): RemoteMessage? {
        val parts = line.trim().split(' ')
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "HELLO" -> if (parts.size >= 4) {
                RemoteMessage.Hello(
                    version = parts[1].toIntOrNull() ?: return null,
                    token = parts[2].takeUnless { it == "-" || it.isBlank() },
                    deviceName = decode(parts[3]).take(64),
                )
            } else null
            "PAIR" -> parts.getOrNull(1)?.let { RemoteMessage.Pair(it) }
            "KEY" -> parts.getOrNull(1)?.let { RemoteKey.fromWire(it) }?.let { RemoteMessage.Key(it) }
            "TEXT" -> parts.getOrNull(1)?.let { RemoteMessage.Text(decode(it)) }
            "PING" -> RemoteMessage.Ping
            else -> null
        }
    }
}

internal sealed interface RemoteMessage {
    data class Hello(val version: Int, val token: String?, val deviceName: String) : RemoteMessage
    data class Pair(val pin: String) : RemoteMessage
    data class Key(val key: RemoteKey) : RemoteMessage
    data class Text(val text: String) : RemoteMessage
    data object Ping : RemoteMessage
}

/** Every button the remote can send. The wire names are lower-case and stable. */
internal enum class RemoteKey(val wire: String) {
    Up("up"),
    Down("down"),
    Left("left"),
    Right("right"),
    Select("select"),
    Back("back"),
    Home("home"),
    Search("search"),
    PlayPause("playpause"),
    SeekBack("seekback"),
    SeekForward("seekforward"),
    VolumeUp("volup"),
    VolumeDown("voldown"),
    Mute("mute"),
    Subtitles("subtitles"),
    Audio("audio"),
    Backspace("backspace"),
    Enter("enter"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(wire: String): RemoteKey? = byWire[wire.lowercase()]
    }
}
