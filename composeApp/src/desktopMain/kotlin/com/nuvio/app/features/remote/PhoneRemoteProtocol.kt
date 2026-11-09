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
 * Added later, still version 1 (an older PC ignores them, an older phone never sends them):
 * ```
 * phone: SUBSCRIBE                      the PC then sends STATE or IDLE now and whenever it changes
 * pc:    STATE <kind> <playing 0|1> <positionMs> <durationMs> <volume or -> <volumeMax> <canSkip 0|1>
 *              <title> <subtitle or -> <imageUrl or -> [<subtitleTrack or -> <audioTrack or ->]
 *    or: IDLE                           nothing is playing
 * pc:    THEME <accent> <accentEnd or -> <background>   after the first STATE, and when the TV's theme changes
 * phone: KEY sources                    the film player's sources panel
 * phone: SEEK <ms> | VOLUME <0..volumeMax>
 * phone: OPEN <url>                     a link from the phone's share menu
 * pc:    OPENING <what> | CANT_OPEN     (only for OPEN)
 * ```
 * The phone moves the position on by itself while playing; the PC re-sends STATE when anything but the position
 * changes, when the position jumps (a seek), and every few seconds as a check.
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

    const val IDLE = "IDLE"

    fun stateLine(state: com.nuvio.app.features.remote.RemoteNowPlaying?): String {
        if (state == null) return IDLE
        fun field(text: String?) = text?.takeIf { it.isNotBlank() }?.let(::encode) ?: "-"
        return listOf(
            "STATE",
            state.kind,
            if (state.isPlaying) "1" else "0",
            state.positionMs.coerceAtLeast(0L).toString(),
            state.durationMs.coerceAtLeast(0L).toString(),
            state.volume?.toString() ?: "-",
            state.volumeMax.toString(),
            if (state.canSkip) "1" else "0",
            field(state.title.take(MAX_FIELD_CHARS)),
            field(state.subtitle?.take(MAX_FIELD_CHARS)),
            field(state.imageUrl?.takeIf { it.length <= MAX_URL_CHARS }),
            // Added later: an older phone reads the first eleven fields and ignores these.
            field(state.subtitleTrack?.take(MAX_FIELD_CHARS)),
            field(state.audioTrack?.take(MAX_FIELD_CHARS)),
        ).joinToString(" ")
    }

    /**
     * `THEME <accent> <accentEnd or -> <background>`, six-digit hex without '#': the TV's theme, sent after SUBSCRIBE
     * and whenever it changes, so the phone wears the same colours.
     */
    fun themeLine(accent: Int, accentEnd: Int?, background: Int): String =
        "THEME ${hex(accent)} ${accentEnd?.let(::hex) ?: "-"} ${hex(background)}"

    private fun hex(argb: Int): String = "%06X".format(argb and 0xFFFFFF)

    /**
     * `WAKE <mac,mac> <broadcast,broadcast>`: this PC's network cards (twelve hex digits each) and the broadcast
     * addresses of its home networks, sent after SUBSCRIBE. The phone keeps them to wake the PC from sleep later with a
     * Wake-on-LAN packet. `-` when there are none.
     */
    fun wakeLine(macs: List<String>, broadcasts: List<String>): String =
        "WAKE ${macs.joinToString(",").ifEmpty { "-" }} ${broadcasts.joinToString(",").ifEmpty { "-" }}"

    private const val MAX_FIELD_CHARS = 200
    private const val MAX_URL_CHARS = 1500

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
            "SUBSCRIBE" -> RemoteMessage.Subscribe
            "SEEK" -> parts.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }?.let { RemoteMessage.Seek(it) }
            "VOLUME" -> parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 }?.let { RemoteMessage.Volume(it) }
            "OPEN" -> parts.getOrNull(1)?.let(::decode)?.takeIf { it.isNotBlank() }?.let { RemoteMessage.Open(it) }
            // fork:phone-remote library, see PhoneRemoteLibraryLines.
            "LIBRARY" -> parts.getOrNull(1)?.takeIf { it == PhoneRemoteLibraryLines.VIDEO || it == PhoneRemoteLibraryLines.MUSIC }
                ?.let { RemoteMessage.Library(it) }
            "OPEN_TITLE" -> if (parts.size >= 3) {
                RemoteMessage.OpenTitle(type = decode(parts[1]), id = decode(parts[2])).takeIf { it.type.isNotBlank() && it.id.isNotBlank() }
            } else null
            "PLAY_ALBUM" -> if (parts.size >= 3) {
                AlbumAction.fromWire(parts[2])?.let { RemoteMessage.PlayAlbum(decode(parts[1]), it) }?.takeIf { it.browseId.isNotBlank() }
            } else null
            "ARTIST" -> if (parts.size >= 3) {
                ArtistAction.fromWire(parts[2])?.let { RemoteMessage.Artist(decode(parts[1]), it) }?.takeIf { it.artistId.isNotBlank() }
            } else null
            // fork:phone-remote continue watching.
            "CONTINUE" -> RemoteMessage.Continue
            // fork:phone-remote search: Nuvio's catalog search, from the phone.
            // fork:phone-remote music: the queue and the lyrics, see PhoneRemoteMusic.
            "QUEUE" -> RemoteMessage.Queue
            "QUEUE_PLAY", "QUEUE_NEXT", "QUEUE_REMOVE" -> parts.getOrNull(1)?.let(::decode)?.takeIf { it.isNotBlank() }
                ?.let { RemoteMessage.QueueAction(parts[0].removePrefix("QUEUE_").lowercase(), it) }
            "LYRICS" -> RemoteMessage.LyricsRequest
            // fork:phone-remote episodes: a show's season list, and playing one of its episodes.
            "EPISODES" -> if (parts.size >= 3) {
                RemoteMessage.Episodes(decode(parts[1]), decode(parts[2])).takeIf { it.type.isNotBlank() && it.id.isNotBlank() }
            } else null
            "PLAY_EPISODE" -> if (parts.size >= 4) {
                RemoteMessage.PlayEpisode(decode(parts[1]), decode(parts[2]), decode(parts[3]))
                    .takeIf { it.type.isNotBlank() && it.id.isNotBlank() && it.videoId.isNotBlank() }
            } else null
            "ALBUM_TRACKS" -> parts.getOrNull(1)?.let(::decode)?.takeIf { it.isNotBlank() }?.let { RemoteMessage.AlbumTracks(it) }
            "PLAY_TRACK" -> if (parts.size >= 3) {
                parts[2].toIntOrNull()?.takeIf { it >= 0 }?.let { RemoteMessage.PlayTrack(decode(parts[1]), it) }?.takeIf { it.browseId.isNotBlank() }
            } else null
            "SEARCH" -> parts.getOrNull(1)?.let(::decode)?.trim()?.take(200)?.takeIf { it.isNotEmpty() }?.let { RemoteMessage.Search(it) }
            // fork:phone-remote sources: play the nth source of the list on screen.
            "PICK_SOURCE" -> parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 1 }?.let { RemoteMessage.PickSource(it) }
            "RESUME" -> if (parts.size >= 3) {
                RemoteMessage.Resume(parentId = decode(parts[1]), videoId = decode(parts[2]))
                    .takeIf { it.parentId.isNotBlank() && it.videoId.isNotBlank() }
            } else null
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
    data object Subscribe : RemoteMessage
    data class Seek(val positionMs: Long) : RemoteMessage
    data class Volume(val volume: Int) : RemoteMessage
    data class Open(val url: String) : RemoteMessage
    data class Library(val kind: String) : RemoteMessage
    data class OpenTitle(val type: String, val id: String) : RemoteMessage
    data class PlayAlbum(val browseId: String, val action: AlbumAction) : RemoteMessage
    data class Artist(val artistId: String, val action: ArtistAction) : RemoteMessage
    data object Continue : RemoteMessage
    data class Resume(val parentId: String, val videoId: String) : RemoteMessage
    data class PickSource(val number: Int) : RemoteMessage
    data class Search(val query: String) : RemoteMessage
    data object Queue : RemoteMessage
    data class QueueAction(val action: String, val videoId: String) : RemoteMessage
    data object LyricsRequest : RemoteMessage
    data class Episodes(val type: String, val id: String) : RemoteMessage
    data class PlayEpisode(val type: String, val id: String, val videoId: String) : RemoteMessage
    data class AlbumTracks(val browseId: String) : RemoteMessage
    data class PlayTrack(val browseId: String, val index: Int) : RemoteMessage
}

internal enum class AlbumAction(val wire: String) {
    Play("play"), Shuffle("shuffle"), Queue("queue");

    companion object {
        fun fromWire(wire: String): AlbumAction? = entries.firstOrNull { it.wire == wire.lowercase() }
    }
}

internal enum class ArtistAction(val wire: String) {
    /** The artist's albums mixed together, as Shuffle all on the artist page. */
    Mix("mix"),
    /** YouTube Music's radio for the artist. */
    Radio("radio");

    companion object {
        fun fromWire(wire: String): ArtistAction? = entries.firstOrNull { it.wire == wire.lowercase() }
    }
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
    Next("next"),
    Previous("previous"),
    /** The film player's sources panel. */
    Sources("sources"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(wire: String): RemoteKey? = byWire[wire.lowercase()]
    }
}
