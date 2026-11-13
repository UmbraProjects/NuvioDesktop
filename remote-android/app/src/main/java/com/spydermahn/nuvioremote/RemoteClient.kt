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
    Next("next"), Previous("previous"),
    /** The film player's sources panel. */
    Sources("sources"),
}

/**
 * What the PC is playing, from its STATE line. The PC does not send position ticks: [positionAt] moves the position on
 * from when the line arrived ([receivedAtMs], on the phone's elapsed-time clock).
 */
data class NowPlaying(
    /** "film" (the film and YouTube player) or "music". */
    val kind: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    /** 0 when unknown, or for live TV. */
    val durationMs: Long,
    val volume: Int?,
    val volumeMax: Int,
    val canSkip: Boolean,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val receivedAtMs: Long,
    /** The film's subtitle in use ("English SDH" or "Off"); null from a PC that does not say. */
    val subtitleTrack: String? = null,
    val audioTrack: String? = null,
    /** The film's speed in percent (100 is 1x); null from a PC that does not say. */
    val speedPercent: Int? = null,
    /** "Skip intro" and the like while a skippable part plays. */
    val skipLabel: String? = null,
    val canNextEpisode: Boolean = false,
    /** Minutes left on the PC's sleep timer; 0 when it is off. */
    val sleepMinutes: Int = 0,
    /** Music only: shuffle, repeat (0 off, 1 all, 2 one) and whether the song is liked (null when not signed in or unknown). */
    val shuffle: Boolean? = null,
    val repeat: Int? = null,
    val liked: Boolean? = null,
) {
    val hasTimeline: Boolean get() = durationMs > 0L

    fun positionAt(nowMs: Long): Long {
        val moved = if (isPlaying) positionMs + (nowMs - receivedAtMs).coerceAtLeast(0L) else positionMs
        return if (hasTimeline) moved.coerceAtMost(durationMs) else moved
    }
}

/** A line the PC sends after the handshake. */
sealed interface ServerLine {
    data class State(val nowPlaying: NowPlaying) : ServerLine
    data object Idle : ServerLine
    /** The TV's theme, as ARGB colours. */
    data class Theme(val theme: RemoteTheme) : ServerLine
    data object Pong : ServerLine
    data class Opening(val what: String) : ServerLine
    data object CantOpen : ServerLine
    data class VideoLibrary(val ready: Boolean, val titles: List<LibraryTitle>) : ServerLine
    data class MusicLibrary(val ready: Boolean, val artists: List<LibraryArtistInfo>, val albums: List<LibraryAlbum>) : ServerLine
    /** The PC's Continue Watching, newest first. */
    data class Continue(val items: List<ResumeItem>) : ServerLine
    /** The TV's Tonight picker as mirrored on the phone; null while it is not on screen. */
    data class Tonight(val screen: TonightScreenInfo?) : ServerLine
    /** The source list on the TV; null once it closes. */
    data class Sources(val list: SourceList?) : ServerLine
    /** The film player's audio tracks (the same row type as subtitles; no Off, no groups). */
    data class Audio(val choices: List<SubtitleChoice>) : ServerLine
    /** The film player's subtitles, Off first; sent when asked for, and again as the add-ons answer. */
    data class Subtitles(val loading: Boolean, val choices: List<SubtitleChoice>) : ServerLine
    /** A show's episodes; [found] false when the PC could not fetch its details. */
    data class Episodes(val type: String, val id: String, val found: Boolean, val episodes: List<EpisodeInfo>) : ServerLine
    /** The music player's queue and where the playing track is in it (-1 when nothing plays). */
    data class Queue(val playing: Int, val tracks: List<QueueTrack>) : ServerLine
    /** An album's songs (each track's `upcoming` meaning it can be played); empty when the PC could not fetch it. */
    data class AlbumTracks(val browseId: String, val tracks: List<QueueTrack>) : ServerLine
    /** The playing song's lyrics; empty [lines] when it has none. */
    data class LyricsText(val synced: Boolean, val title: String?, val lines: List<LyricLine>) : ServerLine
    /** The PC's network cards and home-network broadcast addresses, kept to wake it from sleep. */
    data class Wake(val macs: List<String>, val broadcasts: List<String>) : ServerLine
    /** What Nuvio's search found for [query] in the addons' catalogs, so far while [loading]. */
    data class SearchResults(val loading: Boolean, val query: String, val titles: List<LibraryTitle>) : ServerLine

    /** The long-press sheet for [target], worded by the PC. An empty [actions] means the PC could not find it. */
    data class Menu(val target: MenuTarget, val heading: String, val detail: String?, val actions: List<MenuAction>) : ServerLine

    /** A sheet row was done (or not); [refresh] names what to ask the PC for again (library, continue, episodes). */
    data class Done(val ok: Boolean, val message: String, val refresh: Set<String>) : ServerLine

    /** The playlist picker: [seasons] to choose from when a whole show is added from its poster. */
    data class Playlists(
        val target: MenuTarget,
        val scope: String,
        val label: String,
        val seasons: List<Int>,
        val playlists: List<PlaylistOption>,
    ) : ServerLine

    /** An AI recap: [state] 0 while it is written, 1 ready, 2 failed ([text] then says why). */
    data class Recap(val state: Int, val heading: String, val text: String?) : ServerLine
    data object Other : ServerLine

    companion object {
        fun parse(line: String, nowMs: Long): ServerLine {
            val parts = line.trim().split(' ')
            return when (parts[0]) {
                "PONG" -> Pong
                "IDLE" -> Idle
                "CANT_OPEN" -> CantOpen
                "OPENING" -> Opening(RemoteProtocol.decode(parts.getOrElse(1) { "" }))
                "STATE" -> parseState(parts, nowMs)?.let(::State) ?: Other
                "LIBRARY" -> LibraryLines.parse(line) ?: Other
                "CONTINUE" -> LibraryLines.parseContinue(line) ?: Other
                "SOURCES" -> LibraryLines.parseSources(line) ?: Other
                "TONIGHT" -> LibraryLines.parseTonight(line) ?: Other
                "SEARCH_RESULTS" -> LibraryLines.parseSearch(line) ?: Other
                "QUEUE" -> LibraryLines.parseQueue(line) ?: Other
                "EPISODES" -> LibraryLines.parseEpisodes(line) ?: Other
                "SUBTITLES" -> LibraryLines.parseSubtitles(line) ?: Other
                "AUDIO" -> LibraryLines.parseAudio(line) ?: Other
                "LYRICS" -> LibraryLines.parseLyrics(line) ?: Other
                "ALBUM_TRACKS" -> LibraryLines.parseAlbumTracks(line) ?: Other
                "MENU" -> LibraryLines.parseMenu(line) ?: Other
                "DONE" -> LibraryLines.parseDone(line) ?: Other
                "PLAYLISTS" -> LibraryLines.parsePlaylists(line) ?: Other
                "RECAP" -> LibraryLines.parseRecap(line) ?: Other
                "WAKE" -> Wake(
                    macs = parts.getOrNull(1)?.takeUnless { it == "-" }?.split(',')?.filter { WakeOnLan.macBytes(it) != null }.orEmpty(),
                    broadcasts = parts.getOrNull(2)?.takeUnless { it == "-" }?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
                )
                "THEME" -> RemoteTheme.parse(parts)?.let(::Theme) ?: Other
                else -> Other
            }
        }

        private fun parseState(parts: List<String>, nowMs: Long): NowPlaying? {
            if (parts.size < 11) return null
            fun field(index: Int) = parts[index].takeUnless { it == "-" || it.isBlank() }?.let(RemoteProtocol::decode)
            return NowPlaying(
                kind = parts[1],
                isPlaying = parts[2] == "1",
                positionMs = parts[3].toLongOrNull() ?: return null,
                durationMs = parts[4].toLongOrNull() ?: return null,
                volume = parts[5].toIntOrNull(),
                volumeMax = parts[6].toIntOrNull()?.coerceAtLeast(1) ?: 100,
                canSkip = parts[7] == "1",
                title = field(8) ?: return null,
                subtitle = field(9),
                imageUrl = field(10),
                receivedAtMs = nowMs,
                subtitleTrack = parts.getOrNull(11)?.let { if (it == "-" || it.isBlank()) null else RemoteProtocol.decode(it) },
                audioTrack = parts.getOrNull(12)?.let { if (it == "-" || it.isBlank()) null else RemoteProtocol.decode(it) },
                speedPercent = parts.getOrNull(13)?.toIntOrNull(),
                skipLabel = parts.getOrNull(14)?.let { if (it == "-" || it.isBlank()) null else RemoteProtocol.decode(it) },
                canNextEpisode = parts.getOrNull(15) == "1",
                sleepMinutes = parts.getOrNull(16)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                shuffle = parts.getOrNull(17)?.let { if (it == "1") true else if (it == "0") false else null },
                repeat = parts.getOrNull(18)?.toIntOrNull(),
                liked = parts.getOrNull(19)?.let { if (it == "1") true else if (it == "0") false else null },
            )
        }
    }
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

    fun subscribe() = send("SUBSCRIBE")

    fun seek(positionMs: Long) = send("SEEK ${positionMs.coerceAtLeast(0L)}")

    fun volume(level: Int) = send("VOLUME ${level.coerceAtLeast(0)}")

    /**
     * A film or music extra: `SPEED <percent>`, `SKIP_INTRO`, `NEXT_EPISODE`, `SLEEP <minutes>`, `SHUFFLE <0|1>`,
     * `REPEAT <0|1|2>`, `LIKE`, `RADIO`. The PC answers by sending STATE again.
     */
    fun control(line: String) = send(line)

    /** The answer comes back as a [ServerLine.VideoLibrary] or [ServerLine.MusicLibrary] through [listen]. */
    fun requestLibrary(kind: String) = send("LIBRARY $kind")

    /** Opens the title's details on the TV. The PC answers OPENING or CANT_OPEN through [listen]. */
    fun openTitle(type: String, id: String) = send("OPEN_TITLE ${RemoteProtocol.encode(type)} ${RemoteProtocol.encode(id)}")

    /** The answer comes back as a [ServerLine.Continue]; a PC from before Continue Watching does not answer. */
    fun requestContinue() = send("CONTINUE")

    /** Plays it on the TV from the same source, from where it stopped. The PC answers OPENING or CANT_OPEN. */
    fun resume(parentId: String, videoId: String) = send("RESUME ${RemoteProtocol.encode(parentId)} ${RemoteProtocol.encode(videoId)}")

    /** A tap on the phone's mirror of the Tonight picker: [action] as the PC's RemoteTonightAction, [number] a card from 1. */
    fun tonight(action: String, number: Int = 0) = send(if (number > 0) "TONIGHT_DO $action $number" else "TONIGHT_DO $action")

    /** Plays the source at [number] (from 1) in the list on the TV. The PC answers OPENING or CANT_OPEN. */
    fun pickSource(number: Int) = send("PICK_SOURCE $number")

    /** The film player's audio list; the answer comes back as [ServerLine.Audio]. */
    fun requestAudio() = send("AUDIO")

    /** Picks the audio track at [number] (from 1); the PC answers with the list again. */
    fun pickAudio(number: Int) = send("PICK_AUDIO $number")

    /** The film player's subtitle list; the answer comes back as [ServerLine.Subtitles]. */
    fun requestSubtitles() = send("SUBTITLES")

    /** Picks the subtitle at [number] (Off is 1); the PC answers with the list again, the pick marked. */
    fun pickSubtitle(number: Int) = send("PICK_SUBTITLE $number")

    /** A show's season list; the answer comes back as [ServerLine.Episodes]. */
    fun episodes(type: String, id: String) = send("EPISODES ${RemoteProtocol.encode(type)} ${RemoteProtocol.encode(id)}")

    /** Plays an episode as the TV's details page does (its sources, then the player). The PC answers OPENING or CANT_OPEN. */
    fun playEpisode(type: String, id: String, videoId: String) =
        send("PLAY_EPISODE ${RemoteProtocol.encode(type)} ${RemoteProtocol.encode(id)} ${RemoteProtocol.encode(videoId)}")

    /** A long press; the sheet comes back as [ServerLine.Menu]. */
    fun menu(target: MenuTarget) = send("MENU ${target.wire()}")

    /** A row of that sheet; answers come back as [ServerLine.Done], [ServerLine.Playlists] or [ServerLine.Recap]. */
    fun act(target: MenuTarget, code: String, args: List<String> = emptyList()) =
        send((listOf("DO ${target.wire()} $code") + args.map { RemoteProtocol.encode(it).ifEmpty { "-" } }).joinToString(" "))

    /** The music queue; the answer comes back as [ServerLine.Queue]. */
    fun requestQueue() = send("QUEUE")

    /** "play", "next" or "remove" on a queued track; the PC answers with the queue as it then is. */
    fun queueAction(action: String, videoId: String) = send("QUEUE_${action.uppercase()} ${RemoteProtocol.encode(videoId)}")

    /** An album's songs; the answer comes back as [ServerLine.AlbumTracks]. */
    fun albumTracks(browseId: String) = send("ALBUM_TRACKS ${RemoteProtocol.encode(browseId)}")

    /** Plays the album on the TV from its [index]th song (from 0). The PC answers OPENING or CANT_OPEN. */
    fun playTrack(browseId: String, index: Int) = send("PLAY_TRACK ${RemoteProtocol.encode(browseId)} $index")

    /** The playing song's lyrics; the answer comes back as [ServerLine.LyricsText]. */
    fun requestLyrics() = send("LYRICS")

    /** Nuvio's catalog search; answers come back as [ServerLine.SearchResults] while it fills. */
    fun search(query: String) = send("SEARCH ${RemoteProtocol.encode(query)}")

    fun album(browseId: String, action: AlbumAction) = send("PLAY_ALBUM ${RemoteProtocol.encode(browseId)} ${action.wire}")

    fun artist(artistId: String, action: ArtistAction) = send("ARTIST ${RemoteProtocol.encode(artistId)} ${action.wire}")

    /** Sends a shared link and waits for the PC's answer. Only on a session that is not [listen]ing. */
    fun open(text: String): ServerLine {
        send("OPEN ${RemoteProtocol.encode(text)}")
        val reply = readLine() ?: return ServerLine.Other
        return ServerLine.parse(reply, 0L)
    }

    @Volatile
    private var listening = false

    /** When the PC last said anything, on the elapsed-time clock; only kept while [listen]ing. */
    @Volatile
    var lastHeardAtMs: Long = 0L
        private set

    /**
     * Hands every line from the PC to [onLine] on a thread of its own, until the session closes ([onClosed] then runs
     * once). After this, [ping] no longer waits for its answer: a PONG arrives as a line, and a session that has been
     * silent too long ([silentFor]) is dead.
     */
    fun listen(clock: () -> Long, onLine: (ServerLine) -> Unit, onClosed: () -> Unit) {
        listening = true
        lastHeardAtMs = clock()
        // The keep-alive notices a dead PC; the reader just blocks, so a timeout cannot cut a line in half.
        runCatching { socket.soTimeout = 0 }
        Thread({
            try {
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    lastHeardAtMs = clock()
                    onLine(ServerLine.parse(line, clock()))
                }
            } catch (_: IOException) {
            } finally {
                close()
                onClosed()
            }
        }, "nuvio-remote-reader").apply { isDaemon = true }.start()
    }

    fun silentFor(nowMs: Long): Long = nowMs - lastHeardAtMs

    /**
     * True if the PC answered; false means the session is dead and should be reopened. While [listen]ing it only sends
     * the PING (the answer comes through the listener) and reports whether that worked.
     */
    fun ping(): Boolean = try {
        send("PING")
        if (listening) true else readLine() == "PONG"
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
