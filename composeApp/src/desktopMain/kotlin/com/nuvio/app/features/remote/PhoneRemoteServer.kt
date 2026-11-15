package com.nuvio.app.features.remote

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Where pairing state lives. The app backs it with settings storage; tests use a map. */
internal interface PhoneRemotePairing {
    fun pin(): String
    fun isKnownToken(token: String): Boolean
    /** Called once a phone gets the PIN right. Returns the token it keeps for next time. */
    fun issueToken(deviceName: String): String
}

/** What the server reports upward. Called on the connection threads, never on the UI thread. */
internal interface PhoneRemoteListener {
    fun onKey(key: RemoteKey)
    fun onText(text: String)
    fun onConnectedDevicesChanged(names: List<String>) = Unit
    fun onSeek(positionMs: Long) = Unit
    fun onVolume(volume: Int) = Unit
    /** A film or music extra from the phone (speed, skip, sleep timer, shuffle...). */
    fun onControl(action: ControlAction, value: Int) = Unit
    /** A link shared from the phone. Returns what is being opened, for the phone to say, or null if it cannot be. */
    fun onOpen(url: String): String? = null
    /** The whole LIBRARY reply line for [kind] (see PhoneRemoteLibraryLines), or null when there is none to give. */
    fun onLibrary(kind: String): String? = null
    /** Each returns what is being opened, for the phone to say, or null if it cannot be. */
    fun onOpenTitle(type: String, id: String): String? = null
    fun onPlayAlbum(browseId: String, action: AlbumAction): String? = null
    fun onArtist(artistId: String, action: ArtistAction): String? = null
    /** The whole CONTINUE reply line, or null when there is none to give. */
    fun onContinue(): String? = null
    fun onResume(parentId: String, videoId: String): String? = null
    /** Plays the [number]th source on screen; what is opening, or null when it cannot. */
    fun onPickSource(number: Int): String? = null
    /** Hands the SUBTITLES line to [reply] (this phone only); again when the add-ons answer. */
    fun onSubtitles(reply: (String) -> Unit) = Unit
    /** Hands the AUDIO line to [reply] (this phone only). */
    fun onAudio(reply: (String) -> Unit) = Unit
    /** Picks the [number]th audio track, then hands the fresh AUDIO line to [reply]. */
    fun onPickAudio(number: Int, reply: (String) -> Unit) = Unit
    /** Picks the [number]th subtitle (Off is 1), then hands the fresh SUBTITLES line to [reply]. */
    fun onPickSubtitle(number: Int, reply: (String) -> Unit) = Unit
    /** Searches the catalogs for [query], handing each SEARCH_RESULTS line to [reply] (this phone only) as it fills. */
    fun onSearch(query: String, reply: (String) -> Unit) = Unit
    /** The QUEUE line; after a [onQueueAction] too, so the phone sees the change. */
    fun onQueue(): String? = null
    /** Then hands the fresh QUEUE line to [reply], once the change has landed. */
    fun onQueueAction(action: String, videoId: String, reply: (String) -> Unit) = Unit
    /** Hands the LYRICS line for the playing song to [reply]; the first lookup can take a few seconds. */
    fun onLyrics(reply: (String) -> Unit) = Unit
    /** Hands a show's EPISODES line to [reply] once its details are fetched. */
    fun onEpisodes(type: String, id: String, reply: (String) -> Unit) = Unit
    /** Plays an episode as the details page does; what is opening, or null. */
    fun onPlayEpisode(type: String, id: String, videoId: String): String? = null
    /** Hands an album's ALBUM_TRACKS line to [reply] once the album is fetched. */
    fun onAlbumTracks(browseId: String, reply: (String) -> Unit) = Unit
    /** Plays the album from its [index]th song; what is opening, or null. */
    fun onPlayTrack(browseId: String, index: Int): String? = null
    /** fork:phone-remote tonight: a tap on the phone's Tonight mirror. */
    fun onTonight(action: com.nuvio.app.features.remote.RemoteTonightAction, number: Int) = Unit
    /** fork:phone-remote live-tv: a tap on the phone's mirror of a Live TV stage. */
    fun onLiveTv(action: com.nuvio.app.features.remote.RemoteLiveTvDo) = Unit
    /** A long press on the phone: answer with a MENU line (PhoneRemoteActions). */
    fun onMenu(target: RemoteMenuTarget, reply: (String) -> Unit) = Unit
    /** A row of that menu: answer with DONE, PLAYLISTS or RECAP lines. */
    fun onMenuAction(target: RemoteMenuTarget, code: String, args: List<String>, reply: (String) -> Unit) = Unit
}

/**
 * The listener the Android remote talks to: a TCP socket for the session and a UDP socket that
 * answers discovery broadcasts. Plain threads, in the same style as `CastRelayServer`; both
 * sockets are daemon-threaded so a forgotten [stop] can never hold the app open.
 *
 * Nothing reaches [PhoneRemoteListener] until a phone has proven it was paired — either a token
 * from an earlier pairing or the PIN shown in Settings. Failed PINs are counted per address and
 * the address is locked out for a minute after [PhoneRemoteProtocol.MAX_PIN_ATTEMPTS] misses, so
 * a six-digit code cannot be walked from another machine on the network.
 */
internal class PhoneRemoteServer(
    private val name: () -> String,
    private val pairing: PhoneRemotePairing,
    private val listener: PhoneRemoteListener,
    private val log: (String) -> Unit = {},
    private val preferredPort: Int = PhoneRemoteProtocol.DEFAULT_PORT,
    private val discoveryPort: Int = PhoneRemoteProtocol.DISCOVERY_PORT,
    private val readTimeoutMs: Int = 20_000,
    private val now: () -> Long = System::currentTimeMillis,
    /** fork:hardening - open connections (paired or not) beyond this are closed on arrival. */
    private val maxConnections: Int = MAX_CONNECTIONS,
    /** fork:hardening - how long a new connection has to finish pairing before it is dropped. */
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) {
    private val openConnections = java.util.concurrent.atomic.AtomicInteger(0)
    private var server: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    private val clients = CopyOnWriteArrayList<Session>()
    private val failures = ConcurrentHashMap<String, Failures>()

    /** The last STATE or IDLE line, sent to a phone as soon as it subscribes. */
    @Volatile
    private var lastState: String = PhoneRemoteProtocol.IDLE

    /** The TCP port in use, or null while stopped. */
    val port: Int? get() = server?.takeUnless { it.isClosed }?.localPort

    @Synchronized
    fun start() {
        if (server != null) return
        val socket = openServerSocket() ?: return
        server = socket
        thread(isDaemon = true, name = "phone-remote-accept") { acceptLoop(socket) }
        log("Phone remote listening on port ${socket.localPort}")
        discovery = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(discoveryPort))
            }.also { udp -> thread(isDaemon = true, name = "phone-remote-discovery") { discoveryLoop(udp, socket.localPort) } }
        } catch (error: SocketException) {
            // The remote still works by typing the PC's address; only finding it automatically is lost.
            log("Phone remote discovery unavailable on UDP $discoveryPort: ${error.message}")
            null
        }
    }

    @Synchronized
    fun stop() {
        discovery?.close()
        discovery = null
        server?.close()
        server = null
        clients.forEach { it.close() }
        clients.clear()
        publishClients()
    }

    /** Sends [line] (a STATE or IDLE line) to every phone that asked for it. Safe from any thread. */
    /** The last THEME line, sent to a phone after its first STATE; null until the app has published one. */
    @Volatile
    private var lastTheme: String? = null

    fun publishTheme(line: String) {
        lastTheme = line
        clients.forEach { session -> if (session.subscribed) session.trySend(line) }
    }

    @Volatile
    private var lastSources: String? = null

    /** This PC's WAKE line (see PhoneRemoteProtocol.wakeLine), for phones to wake it from sleep later. */
    @Volatile
    var wakeLine: String? = null

    @Volatile
    private var lastTonight: String? = null

    @Volatile
    private var lastLiveTv: String? = null

    /** fork:phone-remote live-tv: the Live TV stage as the phone mirrors it (PhoneRemoteLibraryLines.liveTvLine). */
    fun publishLiveTv(line: String) {
        lastLiveTv = line
        clients.forEach { session -> if (session.subscribed) session.trySend(line) }
    }

    /** The Tonight picker as the phone mirrors it (see PhoneRemoteLibraryLines.tonightLine), for every subscribed phone. */
    fun publishTonight(line: String) {
        lastTonight = line
        clients.forEach { session -> if (session.subscribed) session.trySend(line) }
    }

    /** The source list on screen (see PhoneRemoteLibraryLines.sourcesLine), for every subscribed phone. */
    fun publishSources(line: String) {
        lastSources = line
        clients.forEach { session -> if (session.subscribed) session.trySend(line) }
    }

    /** fork:live-scores - a favourite's match event (PhoneRemoteProtocol.scoreLine) for every subscribed phone; not replayed. */
    fun publishScore(line: String) {
        clients.forEach { session -> if (session.subscribed) session.trySend(line) }
    }

    fun publishState(line: String) {
        lastState = line
        clients.forEach { session -> if (session.subscribed) session.trySend(line) }
    }

    /** Drops every open session, e.g. after the PIN is changed and old tokens are forgotten. */
    fun disconnectAll() {
        clients.forEach { it.close() }
    }

    private fun openServerSocket(): ServerSocket? {
        val candidates = if (preferredPort == 0) listOf(0) else (0 until PhoneRemoteProtocol.PORT_ATTEMPTS).map { preferredPort + it }
        for (candidate in candidates) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate))
                return socket
            } catch (_: BindException) {
                socket.close()
            } catch (error: IOException) {
                socket.close()
                log("Phone remote could not listen on $candidate: ${error.message}")
            }
        }
        log("Phone remote found no free port from $preferredPort")
        return null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            // One thread per connection, so without a cap anyone on the network could open sockets until
            // the PC ran out of threads. A phone uses one connection (two while it reconnects).
            if (openConnections.incrementAndGet() > maxConnections) {
                openConnections.decrementAndGet()
                log("Phone remote: connection from ${client.inetAddress?.hostAddress} refused, $maxConnections already open")
                try {
                    client.close()
                } catch (_: IOException) {
                }
                continue
            }
            thread(isDaemon = true, name = "phone-remote-conn") {
                try {
                    Session(client).run()
                } finally {
                    openConnections.decrementAndGet()
                }
            }
        }
    }

    private fun discoveryLoop(socket: DatagramSocket, tcpPort: Int) {
        val buffer = ByteArray(512)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8).trim()
                if (text != PhoneRemoteProtocol.DISCOVER_REQUEST) continue
                val reply = PhoneRemoteProtocol.discoveryReply(tcpPort, name()).toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            } catch (_: IOException) {
                if (socket.isClosed) break
            }
        }
    }

    private fun publishClients() {
        listener.onConnectedDevicesChanged(clients.mapNotNull { it.deviceName })
    }

    private class Failures(var count: Int, var lockedUntil: Long)

    private fun lockedOut(address: String): Boolean = (failures[address]?.lockedUntil ?: 0L) > now()

    private fun recordFailure(address: String): Boolean {
        val entry = failures.getOrPut(address) { Failures(0, 0L) }
        synchronized(entry) {
            entry.count += 1
            if (entry.count >= PhoneRemoteProtocol.MAX_PIN_ATTEMPTS) {
                entry.count = 0
                entry.lockedUntil = now() + LOCKOUT_MS
                return true
            }
        }
        return false
    }

    private inner class Session(private val socket: Socket) {
        @Volatile var deviceName: String? = null
        @Volatile var subscribed: Boolean = false
        private val address: String = (socket.inetAddress ?: InetAddress.getLoopbackAddress()).hostAddress
        private lateinit var out: Writer

        fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }

        fun run() {
            try {
                socket.use {
                    socket.soTimeout = readTimeoutMs
                    socket.tcpNoDelay = true
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    out = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                    val name = handshake(reader) ?: return
                    deviceName = name
                    clients += this
                    publishClients()
                    log("Phone remote connected: $name ($address)")
                    while (!socket.isClosed) {
                        val line = readLine(reader) ?: break
                        when (val message = PhoneRemoteProtocol.parse(line)) {
                            is RemoteMessage.Key -> listener.onKey(message.key)
                            is RemoteMessage.Text -> if (message.text.isNotEmpty()) listener.onText(message.text)
                            RemoteMessage.Ping -> send("PONG")
                            RemoteMessage.Subscribe -> {
                                subscribed = true
                                send(lastState)
                                lastTheme?.let(::send)
                                lastSources?.let(::send)
                                lastTonight?.let(::send)
                                lastLiveTv?.let(::send)
                                wakeLine?.let(::send)
                            }
                            is RemoteMessage.Seek -> listener.onSeek(message.positionMs)
                            is RemoteMessage.Volume -> listener.onVolume(message.volume)
                            is RemoteMessage.Control -> listener.onControl(message.action, message.value)
                            is RemoteMessage.Open -> sendOpening(listener.onOpen(message.url))
                            is RemoteMessage.Library -> listener.onLibrary(message.kind)?.let(::send)
                            is RemoteMessage.OpenTitle -> sendOpening(listener.onOpenTitle(message.type, message.id))
                            is RemoteMessage.PlayAlbum -> sendOpening(listener.onPlayAlbum(message.browseId, message.action))
                            is RemoteMessage.Artist -> sendOpening(listener.onArtist(message.artistId, message.action))
                            RemoteMessage.Continue -> listener.onContinue()?.let(::send)
                            is RemoteMessage.Resume -> sendOpening(listener.onResume(message.parentId, message.videoId))
                            is RemoteMessage.PickSource -> sendOpening(listener.onPickSource(message.number))
                            RemoteMessage.AudioRequest -> listener.onAudio(::trySend)
                            is RemoteMessage.PickAudio -> listener.onPickAudio(message.number, ::trySend)
                            RemoteMessage.SubtitlesRequest -> listener.onSubtitles(::trySend)
                            is RemoteMessage.PickSubtitle -> listener.onPickSubtitle(message.number, ::trySend)
                            is RemoteMessage.Search -> listener.onSearch(message.query, ::trySend)
                            RemoteMessage.Queue -> listener.onQueue()?.let(::send)
                            is RemoteMessage.QueueAction -> listener.onQueueAction(message.action, message.videoId, ::trySend)
                            RemoteMessage.LyricsRequest -> listener.onLyrics(::trySend)
                            is RemoteMessage.Episodes -> listener.onEpisodes(message.type, message.id, ::trySend)
                            is RemoteMessage.PlayEpisode -> sendOpening(listener.onPlayEpisode(message.type, message.id, message.videoId))
                            is RemoteMessage.AlbumTracks -> listener.onAlbumTracks(message.browseId, ::trySend)
                            is RemoteMessage.PlayTrack -> sendOpening(listener.onPlayTrack(message.browseId, message.index))
                            is RemoteMessage.TonightDo -> listener.onTonight(message.action, message.number)
                            is RemoteMessage.LiveTvDo -> listener.onLiveTv(message.action)
                            is RemoteMessage.Menu -> listener.onMenu(message.target, ::trySend)
                            is RemoteMessage.Do -> listener.onMenuAction(message.target, message.code, message.args, ::trySend)
                            else -> Unit
                        }
                    }
                }
            } catch (_: IOException) {
                // The phone went away: screen off, Wi-Fi dropped, app closed.
            } finally {
                if (clients.remove(this)) {
                    publishClients()
                    log("Phone remote disconnected: $deviceName")
                }
            }
        }

        /** Returns the phone's name once it is trusted, or null after telling it why not. */
        private fun handshake(reader: BufferedReader): String? {
            // fork:hardening - the read timeout restarts with every byte, so a peer dripping one character
            // at a time could keep a thread for ever; the whole handshake gets one deadline.
            val deadline = now() + handshakeTimeoutMs
            val hello = PhoneRemoteProtocol.parse(readLine(reader, deadline) ?: return null) as? RemoteMessage.Hello ?: return null
            if (hello.version != PhoneRemoteProtocol.VERSION) {
                send("UNSUPPORTED ${PhoneRemoteProtocol.VERSION}")
                return null
            }
            val deviceName = hello.deviceName.ifBlank { "Phone" }
            if (hello.token != null && pairing.isKnownToken(hello.token)) {
                send("WELCOME ${PhoneRemoteProtocol.encode(name())}")
                return deviceName
            }
            send("PIN_REQUIRED")
            while (true) {
                if (lockedOut(address)) {
                    send("LOCKED")
                    return null
                }
                val pair = PhoneRemoteProtocol.parse(readLine(reader, deadline) ?: return null) as? RemoteMessage.Pair ?: return null
                if (java.security.MessageDigest.isEqual(pair.pin.toByteArray(StandardCharsets.UTF_8), pairing.pin().toByteArray(StandardCharsets.UTF_8))) {
                    failures.remove(address)
                    val token = pairing.issueToken(deviceName)
                    send("PAIRED $token ${PhoneRemoteProtocol.encode(name())}")
                    return deviceName
                }
                if (recordFailure(address)) {
                    log("Phone remote: too many wrong codes from $address, locked for a minute")
                    send("LOCKED")
                    return null
                }
                send("BAD_PIN")
            }
        }

        /**
         * One line, read a character at a time so a peer that never sends a newline cannot make us
         * buffer without limit. A line this long is not something the remote sends; the connection
         * is dropped rather than the line parsed.
         */
        private fun readLine(reader: BufferedReader, deadline: Long = 0L): String? {
            val line = StringBuilder()
            while (true) {
                if (deadline != 0L && now() > deadline) throw IOException("handshake took too long")
                val next = reader.read()
                if (next == -1) return if (line.isEmpty()) null else line.toString()
                val char = next.toChar()
                if (char == '\n') return line.toString().trimEnd('\r')
                if (line.length >= PhoneRemoteProtocol.MAX_LINE_CHARS) throw IOException("line too long")
                line.append(char)
            }
        }

        /** A push from another thread: a phone that has gone away is closed, and its own thread cleans up. */
        fun trySend(line: String) {
            try {
                send(line)
            } catch (_: IOException) {
                close()
            }
        }

        private fun sendOpening(opening: String?) =
            send(if (opening != null) "OPENING ${PhoneRemoteProtocol.encode(opening)}" else "CANT_OPEN")

        @Synchronized
        private fun send(line: String) {
            out.write(line)
            out.write("\n")
            out.flush()
        }
    }

    private companion object {
        const val LOCKOUT_MS = 60_000L
        const val MAX_CONNECTIONS = 16
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
    }
}
