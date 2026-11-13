package com.spydermahn.nuvioremote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A pretend Nuvio, inside the app, for testing the remote away from the PC (debug mode). It listens on the phone's own
 * loopback address and speaks the real protocol, so the app connects to it exactly as it does to a PC: pairing (code
 * [PIN]), keys, typing, now playing, seeking, volume, the library and the TV's theme all go over the same code. It plays
 * pretend films and albums whose clock runs like the real one, and keeps a log of what it was sent ([log]).
 *
 * Mirrors the PC's PhoneRemoteServer only as far as the phone can see it; nothing here is used when talking to a PC.
 */
object DemoTv {
    const val HOST = "127.0.0.1"
    const val PORT = 47399
    const val PIN = "123456"
    const val NAME = "Demo TV"
    private const val TOKEN = "demo-token"

    val address: String get() = "$HOST:$PORT"

    fun isDemo(pc: SavedPc?): Boolean = pc != null && pc.host == HOST && pc.port == PORT

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** The last things the pretend TV was sent, newest last. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val ticker = Executors.newSingleThreadScheduledExecutor { Thread(it, "demo-tv-clock").apply { isDaemon = true } }

    private val ready = java.util.concurrent.CountDownLatch(1)
    private var started = false

    /** Starts listening, once, on a thread of its own; later calls do nothing. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        Thread({
            val socket = try {
                ServerSocket(PORT, 8, InetAddress.getByName(HOST))
            } catch (_: IOException) {
                ready.countDown()
                return@Thread
            }
            server = socket
            ready.countDown()
            while (!socket.isClosed) {
                val accepted = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                // Asleep: as a sleeping PC, nothing answers.
                if (asleep) {
                    runCatching { accepted.close() }
                    continue
                }
                Client(accepted).also(clients::add).start()
            }
        }, "demo-tv-accept").apply { isDaemon = true }.start()
        // Music moves on to the next song at the end of one, as the TV does.
        ticker.scheduleWithFixedDelay({ advanceIfEnded() }, 1, 1, TimeUnit.SECONDS)
    }

    /** [start], then wait (briefly) until it is listening. Not on the main thread. */
    fun startAndWait() {
        start()
        ready.await(2, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------------------------------ What the TV is doing

    private data class Film(val title: String, val subtitle: String, val image: String?, val durationMs: Long)

    private data class Song(val title: String, val artist: String, val album: String, val image: String?, val durationMs: Long)

    private enum class Mode { Browsing, Film, Music }

    private val subtitleTracks = listOf("Off", "English", "English SDH", "French", "English (SubSource)", "Français (Wizdom)")

    private fun audioLine(): String = "AUDIO 0 " + audioTracks.mapIndexed { i, name ->
        listOf(RemoteProtocol.encode(name), "-", "-", if (i == audioIndex) "1" else "0").joinToString(",")
    }.joinToString(";")

    /** The pretend subtitle list: the file's own (1 to 3) and an add-on's (4 and 5), Off first. */
    private fun subtitlesLine(): String {
        val rows = subtitleTracks.mapIndexed { i, name ->
            val group = when { i == 0 -> "Off"; i <= 3 -> "Built-in"; else -> "OpenSubtitles v3" }
            listOf(RemoteProtocol.encode(name), "-", RemoteProtocol.encode(group), if (i == subtitleIndex) "1" else "0").joinToString(",")
        }
        return "SUBTITLES 0 " + rows.joinToString(";")
    }
    private val audioTracks = listOf("English 5.1", "English Stereo", "Commentary")

    private val themes = listOf(
        "1E88E5 - 0D0D0F", // Ocean, Nuvio's default
        "E53935 - 0D0D0D", // Crimson
        "FFD700 FF8800 0B0F10", // a custom gold gradient
        "8E24AA 26C6DA 0C0B12", // a custom violet to teal
    )

    private var mode = Mode.Browsing
    private var film: Film? = null
    private var songs: List<Song> = emptyList()
    private var songIndex = 0
    private var playing = false
    private var basePositionMs = 0L
    private var baseAtMs = 0L
    private var volume = 70
    private var speedPercent = 100
    private var sleepMinutes = 0
    private var shuffle = false
    private var repeat = 0
    private var liked = false
    private var muted = false
    private var subtitleIndex = 1
    private var audioIndex = 0
    private var themeIndex = 0

    // The JVM clock rather than SystemClock, so the demo also runs in plain unit tests.
    private fun now() = System.nanoTime() / 1_000_000L

    @Synchronized
    private fun position(): Long {
        val moved = if (playing) basePositionMs + (now() - baseAtMs) else basePositionMs
        return moved.coerceIn(0L, durationMs())
    }

    @Synchronized
    private fun durationMs(): Long = when (mode) {
        Mode.Film -> film?.durationMs ?: 0L
        Mode.Music -> songs.getOrNull(songIndex)?.durationMs ?: 0L
        Mode.Browsing -> 0L
    }

    @Synchronized
    private fun setPosition(ms: Long) {
        basePositionMs = ms.coerceIn(0L, durationMs())
        baseAtMs = now()
    }

    @Synchronized
    private fun stateLine(): String {
        fun field(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
        val position = position()
        val shownVolume = if (muted) 0 else volume
        return when (mode) {
            Mode.Browsing -> "IDLE"
            Mode.Film -> {
                val f = film ?: return "IDLE"
                "STATE film ${if (playing) 1 else 0} $position ${f.durationMs} $shownVolume 100 0 ${field(f.title)} " +
                    "${field(f.subtitle)} ${field(f.image)} ${field(subtitleTracks[subtitleIndex])} ${field(audioTracks[audioIndex])} " +
                    "$speedPercent ${if (position < 60_000L) "Skip%20intro" else "-"} 1 $sleepMinutes - - -"
            }
            Mode.Music -> {
                val s = songs.getOrNull(songIndex) ?: return "IDLE"
                "STATE music ${if (playing) 1 else 0} $position ${s.durationMs} $shownVolume 100 1 ${field(s.title)} " +
                    "${field("${s.artist} · ${s.album}")} ${field(s.image)} - - - - 0 $sleepMinutes ${if (shuffle) 1 else 0} $repeat ${if (liked) 1 else 0}"
            }
        }
    }

    private fun themeLine(): String = "THEME " + themes[themeIndex]

    // ------------------------------------------------------------------------------------------ The source list

    /** The title and line the source list on screen is for; null while none shows. */
    private var sourcesFor: Pair<String, String>? = null

    private fun sourcesLine(complete: Boolean): String {
        val showing = synchronized(this) { sourcesFor } ?: return "SOURCES 0 0 - -"
        fun f(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
        val shown = if (complete) DEMO_SOURCES else DEMO_SOURCES.take(2)
        return "SOURCES 1 ${if (complete) 0 else 1} ${f(showing.first)} " + shown.joinToString(";") { src ->
            listOf(f(src.name), f(src.detail), f(src.addon), src.sizeBytes?.toString() ?: "-", if (src.instant) "1" else "0", if (src.playable) "1" else "0")
                .joinToString(",")
        }
    }

    /** As the TV does after Play on a title: the list opens, fills over a couple of seconds, and waits for a pick. */
    private fun showSources(title: String, subtitle: String) {
        synchronized(this) { sourcesFor = title to subtitle }
        broadcast(sourcesLine(complete = false))
        ticker.schedule({ broadcast(sourcesLine(complete = true)) }, 2, TimeUnit.SECONDS)
    }

    private fun closeSources() {
        synchronized(this) { sourcesFor = null }
        broadcast(sourcesLine(complete = true))
    }

    private fun broadcastState() {
        val line = stateLine()
        clients.forEach { if (it.subscribed) it.send(line) }
    }

    private fun broadcast(line: String) {
        clients.forEach { if (it.subscribed) it.send(line) }
    }

    private fun note(text: String) {
        _log.update { (it + text).takeLast(LOG_SIZE) }
    }

    // ------------------------------------------------------------------------------------------ Controls for testing

    /** Starts a pretend film, as if it had been picked on the TV. */
    fun playFilm(
        title: String = "The Long Signal",
        subtitle: String = "2021 · Sci-Fi · 2h 44m",
        durationMs: Long = (2 * 60 + 44) * 60_000L,
        positionMs: Long = 17 * 60_000L,
    ) {
        synchronized(this) {
            mode = Mode.Film
            film = Film(title, subtitle, poster(title), durationMs)
            playing = true
            setPosition(positionMs)
        }
        note("Playing the film $title")
        broadcastState()
    }

    /** Starts a pretend album. */
    fun playMusic(album: DemoAlbum = DEMO_ALBUMS.first(), shuffle: Boolean = false) {
        synchronized(this) {
            mode = Mode.Music
            songs = album.tracks.map { (title, seconds) -> Song(title, album.artist, album.title, cover(album.title), seconds * 1_000L) }
                .let { if (shuffle) it.shuffled() else it }
            songIndex = 0
            playing = true
            setPosition(0L)
        }
        note("Playing ${album.title}${if (shuffle) " (shuffled)" else ""}")
        broadcastState()
    }

    /** Back to browsing, as if the player had been closed. */
    fun stop() {
        synchronized(this) {
            mode = Mode.Browsing
            playing = false
        }
        note("Player closed")
        broadcastState()
    }

    @Volatile
    private var asleep = false

    /** Puts the pretend PC to sleep: every phone is dropped and nothing answers until a wake-up packet arrives. */
    fun sleep() {
        note("Gone to sleep")
        asleep = true
        clients.forEach { runCatching { it.drop() } }
    }

    /** A Wake-on-LAN packet for the demo's own card arrived (see WakeOnLan): up again after a few seconds. */
    fun wake(packet: ByteArray) {
        val mac = WakeOnLan.macBytes(DEMO_MAC) ?: return
        if (!asleep || !packet.contentEquals(WakeOnLan.magicPacket(mac))) return
        ticker.schedule({
            asleep = false
            note("Woken by the phone")
        }, 4, TimeUnit.SECONDS)
    }

    /** The next of a few themes, as if the TV's theme had been changed in Settings. */
    fun nextTheme() {
        synchronized(this) { themeIndex = (themeIndex + 1) % themes.size }
        note("Theme changed")
        broadcast(themeLine())
    }

    private fun advanceIfEnded() {
        val ended = synchronized(this) {
            if (mode != Mode.Music || !playing || position() < durationMs()) return@synchronized false
            if (songIndex + 1 < songs.size) {
                songIndex++
                setPosition(0L)
            } else {
                playing = false
                setPosition(durationMs())
            }
            true
        }
        if (ended) broadcastState()
    }

    // ------------------------------------------------------------------------------------------ The protocol

    private fun onKey(wire: String) {
        val key = RemoteKey.entries.firstOrNull { it.wire == wire }
        note("Key ${key?.name ?: wire}")
        synchronized(this) {
            when (key) {
                RemoteKey.PlayPause -> if (mode != Mode.Browsing) {
                    setPosition(position())
                    playing = !playing
                }
                RemoteKey.SeekBack -> if (mode != Mode.Browsing) setPosition(position() - 10_000L)
                RemoteKey.SeekForward -> if (mode != Mode.Browsing) setPosition(position() + 10_000L)
                RemoteKey.Next -> if (mode == Mode.Music && songIndex + 1 < songs.size) {
                    songIndex++
                    setPosition(0L)
                }
                RemoteKey.Previous -> if (mode == Mode.Music) {
                    if (position() > 3_000L || songIndex == 0) setPosition(0L) else {
                        songIndex--
                        setPosition(0L)
                    }
                }
                RemoteKey.Subtitles -> subtitleIndex = (subtitleIndex + 1) % subtitleTracks.size
                RemoteKey.Audio -> audioIndex = (audioIndex + 1) % audioTracks.size
                RemoteKey.VolumeUp -> { volume = (volume + 5).coerceAtMost(100); muted = false }
                RemoteKey.VolumeDown -> volume = (volume - 5).coerceAtLeast(0)
                RemoteKey.Mute -> muted = !muted
                // Back out of the player, as on the TV.
                RemoteKey.Back -> when {
                    sourcesFor != null -> sourcesFor = null
                    mode != Mode.Browsing -> mode = Mode.Browsing
                }
                RemoteKey.Sources -> if (mode == Mode.Film) film?.let { sourcesFor = it.title to it.subtitle }
                else -> Unit
            }
        }
        if (key in STATE_KEYS) broadcastState()
        if (key == RemoteKey.Back || key == RemoteKey.Sources) broadcast(sourcesLine(complete = true))
    }

    private fun libraryLine(kind: String): String {
        fun f(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
        return when (kind) {
            LibraryLines.VIDEO -> "LIBRARY video 1 " + DEMO_TITLES.joinToString(";") { t ->
                listOf(f(t.section), f(t.type), f(t.id), f(t.name), f(t.year), f(poster(t.name)), "-", if (watchedNow(t)) "1" else "0").joinToString(",")
            }
            else -> {
                val artists = DEMO_ALBUMS.map { it.artist }.distinct().joinToString(";") { name ->
                    listOf(f(artistId(name)), f(name), f(photo(name)), if (name == DEMO_ALBUMS.first().artist) "1" else "0").joinToString(",")
                }
                val albums = DEMO_ALBUMS.joinToString(";") { a ->
                    listOf(f(artistId(a.artist)), f(a.id), f(a.title), f(a.kind), f(a.year), f(cover(a.title)), if (a.starred) "1" else "0").joinToString(",")
                }
                "LIBRARY music 1 $artists $albums"
            }
        }
    }

    // ------------------------------------------------------------------------------------------ Queue and lyrics

    private fun songId(index: Int) = "demo-song-$index"

    // ------------------------------------------------------------------------------------------ Long-press menus

    /** Titles and episodes whose watched mark the phone flipped (title ids, and `show:season:episode`). */
    private val flipped: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val playlists = mutableListOf("demo-p1" to "Sunday night", "demo-p2" to "Short ones")
    private val playlistCounts = java.util.concurrent.ConcurrentHashMap(mapOf("demo-p1" to 6, "demo-p2" to 2))

    private fun watchedNow(title: DemoTitle) = title.watched != (title.id in flipped)

    private fun episodeProgress(showId: String, season: Int, episode: Int): Int {
        val base = if (season == 1 && episode <= 3) 1000 else if (season == 1 && episode == 4) 400 else 0
        return if ("$showId:$season:$episode" !in flipped) base else if (base == 1000) 0 else 1000
    }

    private fun setEpisodes(showId: String, episodes: List<Pair<Int, Int>>, watched: Boolean) {
        episodes.forEach { (season, episode) ->
            if ((episodeProgress(showId, season, episode) == 1000) != watched) {
                val key = "$showId:$season:$episode"
                if (!flipped.add(key)) flipped.remove(key)
            }
        }
    }

    private val demoEpisodes = (1..2).flatMap { season -> (1..6).map { season to it } }

    /** `MENU <kind> <type> <id> <ref>` answered as the PC would, with the demo's own titles. */
    private fun menuLine(parts: List<String>): String {
        fun f(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
        val kind = parts.getOrElse(1) { "title" }
        val id = RemoteProtocol.decode(parts.getOrElse(3) { "" })
        val ref = parts.getOrNull(4)?.takeUnless { it == "-" }?.let(RemoteProtocol::decode)
        val echo = parts.drop(1).take(4).joinToString(" ")
        val title = DEMO_TITLES.firstOrNull { it.id == id }
        note("Long press on ${title?.name ?: id} ($kind)")
        val (heading, detail, rows) = when (kind) {
            "title" -> {
                val t = title ?: return "MENU $echo ${f("Couldn't load this")} - -"
                val show = t.type == "series"
                Triple(
                    t.name,
                    listOfNotNull(if (show) "Show" else "Film", t.year).joinToString(" · "),
                    listOfNotNull(
                        "watched" to if (watchedNow(t)) "Mark as unwatched" else "Mark as watched",
                        "library" to "Remove from library",
                        if (show) null else "choose_source" to "Choose a source on the TV",
                        "playlist" to "Add to a playlist",
                        "not_interested" to "Not interested",
                        "details" to "Details on the TV",
                    ),
                )
            }
            "resume" -> {
                val r = DEMO_CONTINUE.firstOrNull { it.parentId == id } ?: return "MENU $echo ${f("Couldn't load this")} - -"
                Triple(
                    r.title,
                    r.episode ?: "Film",
                    listOf(
                        "resume" to "Resume on the TV", "from_start" to "Start from the beginning",
                        "choose_source" to "Choose a source on the TV",
                        "watched" to if (r.episode != null) "Mark episode as watched" else "Mark as watched",
                        "playlist" to if (r.episode != null) "Add episode to a playlist" else "Add to a playlist",
                        "details" to "Details on the TV", "remove" to "Remove from Continue watching",
                    ),
                )
            }
            "episode" -> {
                val bits = ref.orEmpty().split(':')
                val season = bits.getOrNull(1)?.toIntOrNull() ?: 1
                val episode = bits.getOrNull(2)?.toIntOrNull() ?: 1
                val seasonAll = demoEpisodes.filter { it.first == season }.all { (s, e) -> episodeProgress(id, s, e) == 1000 }
                Triple(
                    "S$season E$episode · Chapter ${season * 10 + episode}",
                    title?.name,
                    listOfNotNull(
                        "watched" to if (episodeProgress(id, season, episode) == 1000) "Mark as unwatched" else "Mark as watched",
                        if (season == 1 && episode == 1) null else "previous_watched" to "Mark earlier episodes as watched",
                        "season_watched" to if (seasonAll) "Mark Season $season as unwatched" else "Mark Season $season as watched",
                        if (episodeProgress(id, season, episode) in 1..999) "from_start" to "Start from the beginning" else null,
                        "choose_source" to "Choose a source on the TV",
                        if (season == 1 && episode == 1) null else "recap" to "Recap the story so far",
                        "playlist" to "Add episode to a playlist",
                        "playlist_season" to "Add Season $season to a playlist",
                    ),
                )
            }
            else -> {
                val season = ref?.toIntOrNull() ?: 1
                val seasonAll = demoEpisodes.filter { it.first == season }.all { (s, e) -> episodeProgress(id, s, e) == 1000 }
                Triple(
                    "Season $season",
                    title?.name,
                    listOfNotNull(
                        "season_watched" to if (seasonAll) "Mark Season $season as unwatched" else "Mark Season $season as watched",
                        if (season > 1) "earlier_seasons_watched" to "Mark earlier seasons as watched" else null,
                        if (season > 1) "recap" to "Recap before Season $season" else null,
                        "playlist_season" to "Add Season $season to a playlist",
                        "details" to "Details on the TV",
                    ),
                )
            }
        }
        return "MENU $echo ${f(heading)} ${f(detail)} " + rows.joinToString(";") { (code, label) -> "$code,${f(label)}" }
    }

    /** `DO <kind> <type> <id> <ref> <code> [args]`: pretends, and answers as the PC would. */
    private fun menuAction(parts: List<String>, send: (String) -> Unit) {
        fun f(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
        val kind = parts.getOrElse(1) { "title" }
        val id = RemoteProtocol.decode(parts.getOrElse(3) { "" })
        val ref = parts.getOrNull(4)?.takeUnless { it == "-" }?.let(RemoteProtocol::decode)
        val code = parts.getOrElse(5) { "" }
        val args = parts.drop(6).map(RemoteProtocol::decode)
        val echo = parts.drop(1).take(4).joinToString(" ")
        val name = DEMO_TITLES.firstOrNull { it.id == id }?.name ?: DEMO_CONTINUE.firstOrNull { it.parentId == id }?.title ?: "the title"
        fun done(message: String, refresh: String = "library,continue,episodes") = send("DONE 1 ${f(message)} $refresh")
        val bits = ref.orEmpty().split(':')
        val season = if (kind == "season") ref?.toIntOrNull() ?: 1 else bits.getOrNull(1)?.toIntOrNull() ?: 1
        val episode = bits.getOrNull(2)?.toIntOrNull() ?: 1
        note("Menu: $code on $name")
        when (code) {
            "watched" -> if (kind == "episode") {
                val was = episodeProgress(id, season, episode) == 1000
                setEpisodes(id, listOf(season to episode), !was)
                done(if (was) "S$season E$episode marked as unwatched" else "S$season E$episode marked as watched")
            } else {
                if (!flipped.add(id)) flipped.remove(id)
                done("$name marked as ${if (DEMO_TITLES.firstOrNull { it.id == id }?.let(::watchedNow) == false) "unwatched" else "watched"}")
            }
            "previous_watched" -> {
                setEpisodes(id, demoEpisodes.takeWhile { it != season to episode }, true)
                done("Earlier episodes marked as watched")
            }
            "season_watched" -> {
                val list = demoEpisodes.filter { it.first == season }
                val all = list.all { (s, e) -> episodeProgress(id, s, e) == 1000 }
                setEpisodes(id, list, !all)
                done("Season $season marked as ${if (all) "unwatched" else "watched"}")
            }
            "earlier_seasons_watched" -> {
                setEpisodes(id, demoEpisodes.filter { it.first < season }, true)
                done("Earlier seasons marked as watched")
            }
            "playlist", "playlist_season" -> {
                val scope = when {
                    code == "playlist_season" || kind == "season" -> "season"
                    kind == "title" -> "title"
                    else -> "episode"
                }
                val label = when (scope) {
                    "season" -> "$name · Season $season"
                    "episode" -> if (kind == "resume") name else "$name S$season E$episode"
                    else -> name
                }
                val seasons = if (scope == "title" && DEMO_TITLES.firstOrNull { it.id == id }?.type == "series") "1;2" else "-"
                val lists = playlists.joinToString(";") { (pid, pname) -> "${f(pid)},${f(pname)},${playlistCounts[pid] ?: 0}" }.ifEmpty { "-" }
                send("PLAYLISTS $echo $scope ${f(label)} $seasons $lists")
            }
            "add_to_playlist" -> {
                val target = args.getOrNull(0).orEmpty()
                val pid = if (target == "+") {
                    "demo-p${playlists.size + 1}".also { playlists += it to name }
                } else {
                    target
                }
                playlistCounts[pid] = (playlistCounts[pid] ?: 0) + 1
                done("Added to ${playlists.firstOrNull { it.first == pid }?.second ?: "the playlist"}", "-")
            }
            "recap" -> {
                val heading = "$name · Before " + if (kind == "season") "Season $season" else "S$season E$episode"
                send("RECAP 0 ${f(heading)} -")
                ticker.schedule({
                    send(
                        "RECAP 1 ${f(heading)} " + f(
                            "This is the demo's pretend recap. On a real TV, Nuvio's AI writes the story so far from the " +
                                "episodes before this one, without spoiling what comes next.\n\nThe lighthouse keeper found the " +
                                "letters; the harbour master still doesn't know who sent them.",
                        ),
                    )
                }, 2, TimeUnit.SECONDS)
            }
            "remove" -> done("Removed $name from Continue watching (the demo puts it back)", "continue")
            "library" -> done("Removed $name from the library (the demo keeps it)", "library")
            "not_interested" -> done("$name won't be suggested again", "-")
            "details" -> {
                done("Opening the details on the TV", "-")
                showSources(name, "Details")
            }
            "resume", "from_start", "choose_source" -> {
                done(if (code == "choose_source") "Sources for $name are on the TV" else "Playing $name on the TV", "-")
                showSources(name, if (code == "from_start") "From the start" else "Pick a source")
            }
            else -> send("DONE 0 ${f("The demo doesn't do that")} -")
        }
    }

    private fun queueLine(): String {
        fun f(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
        val (list, at) = synchronized(this) { songs to songIndex }
        if (mode != Mode.Music || list.isEmpty()) return "QUEUE -1 -"
        return "QUEUE $at " + list.withIndex().joinToString(";") { (i, song) ->
            listOf(f(songId(i)), f(song.title), f(song.artist), (song.durationMs / 1000).toString(), f(song.image), if (i > at) "1" else "0")
                .joinToString(",")
        }
    }

    @Synchronized
    private fun queueAction(action: String, id: String) {
        val index = (0 until songs.size).firstOrNull { songId(it) == id } ?: return
        when (action) {
            "play" -> if (index != songIndex) {
                songIndex = index
                playing = true
                setPosition(0L)
            }
            "next" -> if (index > songIndex + 1) {
                val list = songs.toMutableList()
                val song = list.removeAt(index)
                list.add(songIndex + 1, song)
                songs = list
            }
            "remove" -> if (index > songIndex) songs = songs.filterIndexed { i, _ -> i != index }
        }
    }

    /** Pretend timed lyrics: a line every few seconds, so the phone's lyric view can be seen following the song. */
    private fun lyricsLine(): String {
        fun f(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
        val song = synchronized(this) { if (mode == Mode.Music) songs.getOrNull(songIndex) else null } ?: return "LYRICS none - -"
        if (song.durationMs < 60_000L) return "LYRICS none ${f(song.title)} -"
        val words = listOf(
            "Static on the window", "Lights along the water", "Every station humming", "Turn the dial slowly",
            "", "Somewhere in the night bus", "Someone hums the chorus", "Static on the window", "Lights along the water",
        )
        val lines = (0 until (song.durationMs / 6_000L).toInt()).map { i -> (i * 6_000L + 2_000L) to words[i % words.size] }
        return "LYRICS synced ${f(song.title)} " + lines.joinToString(";") { (t, w) -> "$t,${f(w)}" }
    }

    /** Pretend catalog results: the library's titles that match, and a couple more from "the addons". */
    private fun searchLine(query: String, loading: Boolean): String {
        fun f(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        val extra = if (loading) emptyList() else listOf(
            DemoTitle("Movies · Demo catalog", "movie", "demo-x1", "${query.replaceFirstChar { it.uppercase() }}: The Film", "2020", false),
            DemoTitle("Series · Demo catalog", "series", "demo-x2", "${query.replaceFirstChar { it.uppercase() }} Stories", "2024", false),
        )
        val matches = DEMO_TITLES.filter { t -> words.all { it in t.name.lowercase() } }
            .map { it.copy(section = "Movies · Demo catalog") } + extra
        return "SEARCH_RESULTS ${if (loading) 1 else 0} ${f(query)} " + if (matches.isEmpty()) "-" else matches.joinToString(";") { t ->
            listOf(f(t.section), f(t.type), f(t.id), f(t.name), f(t.year), f(poster(t.name)), "-", if (t.watched) "1" else "0").joinToString(",")
        }
    }

    private fun continueLine(): String {
        fun f(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
        return "CONTINUE " + DEMO_CONTINUE.joinToString(";") { c ->
            listOf(
                f(c.type), f(c.parentId), f(c.videoId), f(c.title), f(c.episode), f(poster(c.title)), f(still(c.title)),
                c.positionMs.toString(), c.durationMs.toString(), f(c.lastSource), (c.positionMs * 1000 / c.durationMs).toString(),
            ).joinToString(",")
        }
    }

    private class Client(private val socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

        @Volatile
        var subscribed = false
            private set

        fun start() {
            Thread({ run() }, "demo-tv-client").apply { isDaemon = true }.start()
        }

        @Synchronized
        fun send(line: String) {
            try {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            } catch (_: IOException) {
                close()
            }
        }

        private fun close() {
            runCatching { socket.close() }
            clients.remove(this)
        }

        fun drop() = close()

        private fun run() {
            try {
                var trusted = false
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val parts = line.trim().split(' ')
                    when (parts[0]) {
                        "HELLO" -> {
                            trusted = parts.getOrNull(2) == TOKEN
                            note("Hello from ${RemoteProtocol.decode(parts.getOrElse(3) { "a phone" })}")
                            send(if (trusted) "WELCOME ${RemoteProtocol.encode(NAME)}" else "PIN_REQUIRED")
                        }
                        "PAIR" -> if (parts.getOrNull(1) == PIN) {
                            trusted = true
                            note("Paired")
                            send("PAIRED $TOKEN ${RemoteProtocol.encode(NAME)}")
                        } else {
                            note("Wrong code ${parts.getOrNull(1).orEmpty()}")
                            send("BAD_PIN")
                        }
                        "PING" -> send("PONG")
                        else -> if (trusted) onCommand(parts) else send("PIN_REQUIRED")
                    }
                }
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        private fun onCommand(parts: List<String>) {
            when (parts[0]) {
                "SUBSCRIBE" -> {
                    subscribed = true
                    send(stateLine())
                    send(themeLine())
                    send(sourcesLine(complete = true))
                    send("WAKE $DEMO_MAC $HOST")
                }
                "KEY" -> onKey(parts.getOrElse(1) { "" })
                "TEXT" -> note("Typed \"${RemoteProtocol.decode(parts.getOrElse(1) { "" })}\"")
                "SEEK" -> {
                    val ms = parts.getOrNull(1)?.toLongOrNull() ?: return
                    setPosition(ms)
                    note("Seek to ${ms / 60_000}:${"%02d".format((ms / 1000) % 60)}")
                    broadcastState()
                }
                "VOLUME" -> {
                    val level = parts.getOrNull(1)?.toIntOrNull() ?: return
                    synchronized(DemoTv) { volume = level.coerceIn(0, 100) }
                    note("Volume $level")
                    broadcastState()
                }
                "SPEED" -> {
                    synchronized(DemoTv) { speedPercent = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(50, 400) ?: return }
                    note("Speed $speedPercent%")
                    broadcastState()
                }
                "SKIP_INTRO" -> {
                    setPosition(90_000L)
                    note("Skipped the intro")
                    broadcastState()
                }
                "NEXT_EPISODE" -> note("Next episode (the demo has only one)")
                "SLEEP" -> {
                    synchronized(DemoTv) { sleepMinutes = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: return }
                    note(if (sleepMinutes == 0) "Sleep timer off" else "Sleep timer: $sleepMinutes min")
                    broadcastState()
                }
                "SHUFFLE" -> {
                    synchronized(DemoTv) { shuffle = parts.getOrNull(1) == "1" }
                    note("Shuffle ${if (shuffle) "on" else "off"}")
                    broadcastState()
                }
                "REPEAT" -> {
                    synchronized(DemoTv) { repeat = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 2) ?: return }
                    note("Repeat mode $repeat")
                    broadcastState()
                }
                "LIKE" -> {
                    synchronized(DemoTv) { liked = !liked }
                    note(if (liked) "Liked the song" else "Unliked the song")
                    broadcastState()
                }
                "RADIO" -> note("Radio from this song")
                "LIBRARY" -> {
                    val kind = parts.getOrElse(1) { LibraryLines.VIDEO }
                    note("Library ($kind) asked for")
                    send(libraryLine(kind))
                }
                "OPEN_TITLE" -> {
                    val id = RemoteProtocol.decode(parts.getOrElse(2) { "" })
                    val title = DEMO_TITLES.firstOrNull { it.id == id }
                        ?: if (id.startsWith("demo-x")) DemoTitle("Found by search", "movie", id, "A search result", null, false) else null
                    if (title == null) {
                        send("CANT_OPEN")
                    } else {
                        send("OPENING ${RemoteProtocol.encode(title.name)}")
                        note("Opened ${title.name}; its sources fill in over 2 s")
                        showSources(title.name, listOfNotNull(title.year, title.section).joinToString(" · "))
                    }
                }
                "PLAY_ALBUM" -> {
                    val album = DEMO_ALBUMS.firstOrNull { it.id == RemoteProtocol.decode(parts.getOrElse(1) { "" }) } ?: return
                    when (parts.getOrNull(2)) {
                        "queue" -> note("Queued ${album.title}")
                        "shuffle" -> playMusic(album, shuffle = true)
                        else -> playMusic(album)
                    }
                }
                "ARTIST" -> {
                    val id = RemoteProtocol.decode(parts.getOrElse(1) { "" })
                    val albums = DEMO_ALBUMS.filter { artistId(it.artist) == id }
                    if (albums.isEmpty()) return
                    val mixed = DemoAlbum("${albums.first().artist} mix", albums.first().artist, "mix-$id", "Mix", null, false, albums.flatMap { it.tracks })
                    playMusic(mixed, shuffle = parts.getOrNull(2) == "mix")
                }
                "CONTINUE" -> {
                    note("Continue watching asked for")
                    send(continueLine())
                }
                "RESUME" -> {
                    val parentId = RemoteProtocol.decode(parts.getOrElse(1) { "" })
                    val resume = DEMO_CONTINUE.firstOrNull { it.parentId == parentId }
                    if (resume == null) {
                        send("CANT_OPEN")
                    } else {
                        send("OPENING ${RemoteProtocol.encode(resume.title)}")
                        note("Resuming ${resume.title} from its last source")
                        ticker.schedule({
                            playFilm(resume.title, resume.episode ?: "Resumed", resume.durationMs, resume.positionMs)
                        }, 2, TimeUnit.SECONDS)
                    }
                }
                "ALBUM_TRACKS" -> {
                    val album = DEMO_ALBUMS.firstOrNull { it.id == RemoteProtocol.decode(parts.getOrElse(1) { "" }) }
                    fun f(value: String) = RemoteProtocol.encode(value)
                    send(
                        "ALBUM_TRACKS ${parts.getOrElse(1) { "-" }} " + (album?.tracks?.withIndex()?.joinToString(";") { (i, t) ->
                            "${f("${album.id}-$i")},${f(t.first)},${f(album.artist)},${t.second},1"
                        }?.ifEmpty { "-" } ?: "-"),
                    )
                }
                "PLAY_TRACK" -> {
                    val album = DEMO_ALBUMS.firstOrNull { it.id == RemoteProtocol.decode(parts.getOrElse(1) { "" }) }
                    val index = parts.getOrNull(2)?.toIntOrNull()
                    if (album == null || index == null || index !in album.tracks.indices) {
                        send("CANT_OPEN")
                    } else {
                        send("OPENING ${RemoteProtocol.encode(album.tracks[index].first)}")
                        playMusic(album)
                        synchronized(DemoTv) {
                            songIndex = index
                            setPosition(0L)
                        }
                        broadcastState()
                    }
                }
                "EPISODES" -> {
                    val type = parts.getOrElse(1) { "-" }
                    val id = RemoteProtocol.decode(parts.getOrElse(2) { "" })
                    val show = DEMO_TITLES.firstOrNull { it.id == id && it.type == "series" }
                    note("Episodes of ${show?.name ?: id} asked for")
                    fun f(value: String?) = value?.takeIf { it.isNotBlank() }?.let(RemoteProtocol::encode) ?: "-"
                    val episodes = if (show == null) "-" else (1..2).flatMap { season -> (1..6).map { season to it } }
                        .joinToString(";") { (season, episode) ->
                            val watched = episodeProgress(id, season, episode)
                            listOf(
                                season.toString(), episode.toString(), f("Chapter ${season * 10 + episode}"), f("$id:$season:$episode"),
                                f("https://picsum.photos/seed/${id}e$season$episode/320/180"), f("202${season}-0$episode-01"), watched.toString(),
                            ).joinToString(",")
                        }
                    send("EPISODES $type ${parts.getOrElse(2) { "-" }} ${if (show == null) 0 else 1} $episodes")
                }
                "PLAY_EPISODE" -> {
                    val id = RemoteProtocol.decode(parts.getOrElse(2) { "" })
                    val video = RemoteProtocol.decode(parts.getOrElse(3) { "" })
                    val show = DEMO_TITLES.firstOrNull { it.id == id }
                    val bits = video.split(':')
                    if (show == null || bits.size != 3) {
                        send("CANT_OPEN")
                    } else {
                        send("OPENING ${RemoteProtocol.encode("${show.name} S${bits[1]} E${bits[2]}")}")
                        note("Episode S${bits[1]} E${bits[2]} of ${show.name}: its sources")
                        showSources(show.name, "S${bits[1]} E${bits[2]} · Chapter ${bits[1].toInt() * 10 + bits[2].toInt()}")
                    }
                }
                "MENU" -> send(menuLine(parts))
                "DO" -> menuAction(parts, ::send)
                "QUEUE" -> send(queueLine())
                "QUEUE_PLAY", "QUEUE_NEXT", "QUEUE_REMOVE" -> {
                    val id = RemoteProtocol.decode(parts.getOrElse(1) { "" })
                    note("Queue: ${parts[0].removePrefix("QUEUE_").lowercase()} $id")
                    queueAction(parts[0].removePrefix("QUEUE_").lowercase(), id)
                    send(queueLine())
                    broadcastState()
                }
                "LYRICS" -> {
                    note("Lyrics asked for")
                    send(lyricsLine())
                }
                "SEARCH" -> {
                    val query = RemoteProtocol.decode(parts.getOrElse(1) { "" }).trim()
                    note("Search for \"$query\"")
                    send(searchLine(query, loading = true))
                    ticker.schedule({ send(searchLine(query, loading = false)) }, 1, TimeUnit.SECONDS)
                }
                "AUDIO" -> {
                    note("Audio list asked for")
                    send(audioLine())
                }
                "PICK_AUDIO" -> {
                    val number = parts.getOrNull(1)?.toIntOrNull() ?: return
                    if (number in 1..audioTracks.size) {
                        synchronized(DemoTv) { audioIndex = number - 1 }
                        note("Picked audio $number: ${audioTracks[number - 1]}")
                        broadcastState()
                        send(audioLine())
                    }
                }
                "SUBTITLES" -> {
                    note("Subtitle list asked for")
                    send(subtitlesLine())
                }
                "PICK_SUBTITLE" -> {
                    val number = parts.getOrNull(1)?.toIntOrNull() ?: return
                    if (number in 1..subtitleTracks.size) {
                        synchronized(DemoTv) { subtitleIndex = number - 1 }
                        note("Picked subtitle $number: ${subtitleTracks[number - 1]}")
                        broadcastState()
                        send(subtitlesLine())
                    }
                }
                "PICK_SOURCE" -> {
                    val number = parts.getOrNull(1)?.toIntOrNull() ?: return
                    val source = DEMO_SOURCES.getOrNull(number - 1)
                    val pending = synchronized(DemoTv) { sourcesFor }
                    if (source == null || pending == null || !source.playable) {
                        send("CANT_OPEN")
                    } else {
                        send("OPENING ${RemoteProtocol.encode(source.name)}")
                        note("Picked source $number: ${source.name}")
                        closeSources()
                        playFilm(pending.first, pending.second)
                    }
                }
                "OPEN" -> {
                    val link = RemoteProtocol.decode(parts.getOrElse(1) { "" })
                    note("Sent a link: $link")
                    if (link.startsWith("http")) {
                        send("OPENING ${RemoteProtocol.encode("the link")}")
                        playFilm("A shared video", link.take(60))
                    } else {
                        send("CANT_OPEN")
                    }
                }
                else -> note("Unknown: ${parts[0]}")
            }
        }
    }

    private const val LOG_SIZE = 6
    /** The pretend PC's network card, for Wake-on-LAN. */
    private const val DEMO_MAC = "02DE40DE40AA"
    private val STATE_KEYS = setOf(
        RemoteKey.PlayPause, RemoteKey.SeekBack, RemoteKey.SeekForward, RemoteKey.Next, RemoteKey.Previous,
        RemoteKey.Subtitles, RemoteKey.Audio, RemoteKey.VolumeUp, RemoteKey.VolumeDown, RemoteKey.Mute, RemoteKey.Back,
    )

    // Pictures from picsum.photos (free placeholder photos, the same one per name); without a connection the app shows
    // its usual placeholder instead.
    private fun poster(name: String) = "https://picsum.photos/seed/${slug(name)}/300/450"
    private fun still(name: String) = "https://picsum.photos/seed/${slug(name)}-still/640/360"
    private fun cover(name: String) = "https://picsum.photos/seed/${slug(name)}/400/400"
    private fun photo(name: String) = "https://picsum.photos/seed/${slug(name)}-artist/300/300"
    private fun slug(name: String) = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "nuvio" }
    private fun artistId(name: String) = "demo-artist-" + slug(name)
}

data class DemoTitle(val section: String, val type: String, val id: String, val name: String, val year: String?, val watched: Boolean)

data class DemoSource(
    val name: String,
    val detail: String?,
    val addon: String,
    val sizeBytes: Long?,
    val instant: Boolean,
    val playable: Boolean,
)

private val DEMO_SOURCES = listOf(
    DemoSource("Demo 4K HDR", "Pretend.Title.2160p.WEB-DL.DV.HEVC.mkv\n💾 18.4 GB  👤 212", "Demo addon", 18_400_000_000L, instant = true, playable = true),
    DemoSource("Demo 1080p", "Pretend.Title.1080p.BluRay.HDR10.x264.mkv\n💾 9.1 GB  👤 88", "Demo addon", 9_100_000_000L, instant = true, playable = true),
    DemoSource("Demo 720p", "Pretend.Title.720p.WEB.mkv\n💾 2.3 GB  👤 14", "Second demo addon", 2_300_000_000L, instant = false, playable = true),
    DemoSource("Demo P2P 1080p", "No debrid service: the TV greys this one out", "Second demo addon", 8_700_000_000L, instant = false, playable = false),
)

data class DemoResume(
    val type: String,
    val parentId: String,
    val videoId: String,
    val title: String,
    val episode: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastSource: String?,
)

private val DEMO_CONTINUE = listOf(
    DemoResume("series", "demo-s1", "demo-s1:2:5", "Harbour Lights", "S2 E5 · The Breakwater", 21 * 60_000L, 47 * 60_000L, "Demo addon 1080p WEB-DL"),
    DemoResume("movie", "demo-m3", "demo-m3", "Northbound Night Train", null, 72 * 60_000L, 118 * 60_000L, "Demo addon 4K HDR"),
    DemoResume("series", "demo-s2", "demo-s2:1:2", "The Quiet Engineers", "S1 E2 · Tolerances", 5 * 60_000L, 52 * 60_000L, null),
)

data class DemoAlbum(
    val title: String,
    val artist: String,
    val id: String,
    val kind: String,
    val year: String?,
    val starred: Boolean,
    /** Title and length in seconds. */
    val tracks: List<Pair<String, Int>>,
)

// Invented titles, so nothing here stands for a real film or record.
private val DEMO_TITLES = listOf(
    DemoTitle("Favourites", "movie", "demo-m1", "The Long Signal", "2021", false),
    DemoTitle("Favourites", "series", "demo-s1", "Harbour Lights", "2019", false),
    DemoTitle("Library", "movie", "demo-m2", "Glass Orchard", "2018", true),
    DemoTitle("Library", "movie", "demo-m3", "Northbound Night Train", "2023", false),
    DemoTitle("Library", "series", "demo-s2", "The Quiet Engineers", "2022", false),
    DemoTitle("Library", "movie", "demo-m4", "A Very Long Title For Testing How Cards Cope With Names That Run On", "2020", false),
    DemoTitle("Watchlist", "movie", "demo-m5", "Paper Lanterns", "2024", false),
    DemoTitle("Watchlist", "series", "demo-s3", "Low Tide", "2017", true),
)

val DEMO_ALBUMS = listOf(
    DemoAlbum(
        "Neon Weather", "The Static Hours", "demo-a1", "Album", "2019", true,
        listOf("Opening Frequencies" to 42, "Neon Weather" to 214, "Glasshouse" to 187, "Night Bus" to 233, "Slow Return" to 268),
    ),
    DemoAlbum(
        "Field Recordings", "The Static Hours", "demo-a2", "EP", "2021", false,
        listOf("Morning Room" to 156, "Rain on Tin" to 201, "Last Light" to 245),
    ),
    DemoAlbum(
        "Small Hours", "Mira Okafor", "demo-a3", "Album", "2022", false,
        listOf("Small Hours" to 198, "Driftwood" to 176, "Copper Sky" to 222, "Lullaby for Engines" to 264),
    ),
    DemoAlbum(
        "Coastline (Single)", "Mira Okafor", "demo-a4", "Single", "2023", false,
        listOf("Coastline" to 189),
    ),
)
