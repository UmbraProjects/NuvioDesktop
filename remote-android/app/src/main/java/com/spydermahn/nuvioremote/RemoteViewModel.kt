package com.spydermahn.nuvioremote

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors

sealed interface Screen {
    data object Pick : Screen
    data class Pair(val name: String, val host: String, val port: Int) : Screen
    data object Remote : Screen
}

enum class Link { Connecting, Connected, Offline }

data class UiState(
    val screen: Screen = Screen.Pick,
    val saved: List<SavedPc> = emptyList(),
    val found: List<FoundPc> = emptyList(),
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val current: SavedPc? = null,
    val link: Link = Link.Offline,
    /** What the PC is playing; null when nothing is, or while offline. */
    val nowPlaying: NowPlaying? = null,
    /** [NowPlaying.imageUrl], once fetched. */
    val cover: Bitmap? = null,
    val video: VideoLibraryState = VideoLibraryState(),
    /** The PC's Continue Watching; empty from a PC that does not send it. */
    val continueWatching: List<ResumeItem> = emptyList(),
    /** The TV's Tonight picker while it is on screen; the remote mirrors it. */
    val tonight: TonightScreenInfo? = null,
    /** The TV's Live TV stage while it is on screen; the remote mirrors it. */
    val liveTv: LiveTvScreenInfo? = null,
    /** The source list while the TV shows one; the remote shows it to pick from. */
    val sources: SourceList? = null,
    /** Nuvio's catalog search for the Watch tab's query; null until asked. */
    val search: SearchState? = null,
    /** The film player's subtitles, while the subtitle sheet is open (asked for with a long press on the tile). */
    val subtitles: ServerLine.Subtitles? = null,
    /** The film player's audio tracks, while the audio sheet is open. */
    val audio: ServerLine.Audio? = null,
    /** The music player's queue, while the queue sheet is open. */
    val queue: ServerLine.Queue? = null,
    /** The playing song's lyrics, while the lyric view is open. */
    val lyrics: ServerLine.LyricsText? = null,
    /** The season list of the show sheet that is open, once the PC has sent it. */
    val episodes: ServerLine.Episodes? = null,
    /** The songs of the album sheet that is open, once the PC has sent them. */
    val albumTracks: ServerLine.AlbumTracks? = null,
    /** The PC can be woken from the phone (it told the app its network card); offered while it is offline. */
    val canWake: Boolean = false,
    /** Waking it: the packet went, and the app is waiting for it to come up. */
    val waking: Boolean = false,
    val music: MusicLibraryState = MusicLibraryState(),
    /** The long-press sheet that is open, if any. */
    val menu: MenuSheet? = null,
    /** The playlist picker one of its rows opened. */
    val playlistPick: ServerLine.Playlists? = null,
    /** The AI recap one of its rows asked for (state 0 while it is written). */
    val recap: ServerLine.Recap? = null,
    /** A short line about something just sent ("Opening Blade Runner on the TV"); clears itself. */
    val notice: String? = null,
    /** The TV's theme; the phone wears it. */
    val theme: RemoteTheme = RemoteTheme.Default,
    val padMode: PadMode = PadMode.Touchpad,
    /** Dark mode: the phone black and dim, gestures anywhere (see DarkModeScreen). */
    val dark: Boolean = false,
    val darkLevel: DarkLevel = DarkLevel.Medium,
)

/**
 * A long-press sheet: what was pressed ([heading], [detail], [image] shown straight away from what the phone knows) and
 * the rows, once the PC has sent them ([loaded]). [doing] is the code of a row sent and not yet answered.
 */
data class MenuSheet(
    val target: MenuTarget,
    val heading: String,
    val detail: String?,
    val image: String?,
    val imageFallback: String? = null,
    val wide: Boolean = false,
    val loaded: ServerLine.Menu? = null,
    val doing: String? = null,
)

/** Where a library list stands. [problem] is set when the PC never answered (a Nuvio from before the library). */
data class VideoLibraryState(
    val titles: List<LibraryTitle> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val problem: String? = null,
)

data class SearchState(val query: String, val loading: Boolean, val titles: List<LibraryTitle>)

data class MusicLibraryState(
    val artists: List<LibraryArtistInfo> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val problem: String? = null,
)

/**
 * Everything that touches the network runs on [io], one thread, so presses reach the PC in the
 * order they were made and the session is never used from two threads at once.
 */
class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PcStore(application)
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nuvio-remote-io") }
    private val coverIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nuvio-remote-covers") }

    private val _state = MutableStateFlow(
        UiState(
            saved = store.saved(),
            theme = store.last()?.let { store.theme(it.address) } ?: RemoteTheme.Default,
            padMode = store.padMode,
            darkLevel = store.darkLevel,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: RemoteSession? = null
    private var keepAlive: Job? = null
    private var foreground = false

    private val deviceName: String = phoneName(application)

    init {
        val lastPc = store.last()
        if (lastPc != null) {
            _state.update { it.copy(screen = Screen.Remote, current = lastPc, link = Link.Connecting, canWake = store.wake(lastPc.address) != null) }
        } else {
            scan()
        }
    }

    // --- Lifecycle: drop the socket in the background, pick it up again on return. ---

    fun onForeground() {
        foreground = true
        if (_state.value.screen == Screen.Remote) reconnect()
    }

    fun onBackground() {
        foreground = false
        keepAlive?.cancel()
        io.execute { closeSession() }
        if (_state.value.screen == Screen.Remote) _state.update { it.copy(link = Link.Offline) }
    }

    override fun onCleared() {
        io.execute { closeSession() }
        io.shutdown()
        coverIo.shutdown()
    }

    // --- Picking a PC ---

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, message = null) }
        io.execute {
            val found = runCatching { Discovery.find() }.getOrDefault(emptyList())
            _state.update { state ->
                state.copy(scanning = false, found = found.filterNot { pc -> state.saved.any { it.host == pc.host && it.port == pc.port } })
            }
        }
    }

    fun connectTo(pc: FoundPc) = openAndGreet(pc.name, pc.host, pc.port, token = null)

    fun connectTo(pc: SavedPc) = openAndGreet(pc.name, pc.host, pc.port, token = pc.token)

    /** "192.168.1.20" or "192.168.1.20:47321". */
    fun connectToAddress(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':', "").toIntOrNull() ?: RemoteProtocol.DEFAULT_PORT
        openAndGreet(host, host, port, token = null)
    }

    fun forget(pc: SavedPc) {
        saveAll(_state.value.saved - pc)
        if (store.lastAddress == pc.address) {
            store.lastAddress = null
            NowPlayingService.stop(getApplication())
        }
    }

    fun showPicker() {
        keepAlive?.cancel()
        io.execute { closeSession() }
        store.lastAddress = null
        NowPlayingService.stop(getApplication())
        _state.update { it.copy(screen = Screen.Pick, link = Link.Offline, message = null, busy = false, nowPlaying = null, cover = null, dark = false) }
        scan()
    }

    private fun openAndGreet(name: String, host: String, port: Int, token: String?) {
        _state.update { it.copy(busy = true, message = null) }
        io.execute {
            closeSession()
            try {
                val opened = RemoteSession.open(host, port)
                session = opened
                when (val hello = opened.hello(token, deviceName)) {
                    is HelloResult.Welcome -> onConnected(SavedPc(hello.pcName, host, port, token.orEmpty()))
                    HelloResult.PinRequired -> {
                        // A saved phone being asked again means the PC's code was reset: pair afresh.
                        // The PIN goes over a new session (see pair), so this one is not kept.
                        closeSession()
                        _state.update { it.copy(busy = false, screen = Screen.Pair(name, host, port), link = Link.Offline) }
                    }
                    is HelloResult.Refused -> fail(hello.reason)
                }
            } catch (_: IOException) {
                fail("Couldn't reach $name. Check the PC is on, Nuvio is open with Phone remote turned on, and you're on the same Wi-Fi.")
            }
        }
    }

    // --- Pairing ---

    fun pair(pin: String) {
        val target = _state.value.screen as? Screen.Pair ?: return
        _state.update { it.copy(busy = true, message = null) }
        io.execute {
            val result = attemptPair(target.host, target.port, pin)
                ?: PairResult.Failed("Couldn't reach ${target.name}. Check the PC is on and on the same Wi-Fi.")
            onPairResult(result, target.host, target.port)
        }
    }

    /**
     * QR pairing: the code from Nuvio's Settings, scanned (or opened from the phone's camera). Each of the PC's
     * addresses is tried in turn until one answers, then it pairs with the code in it, no typing.
     */
    fun pairWith(link: PairingLink) {
        if (link.hosts.any { it == DemoTv.HOST } && link.port == DemoTv.PORT) io.execute { DemoTv.startAndWait() }
        _state.update { it.copy(busy = true, message = null, screen = Screen.Pair(link.name, link.hosts.first(), link.port)) }
        io.execute {
            for (host in link.hosts) {
                val result = attemptPair(host, link.port, link.pin) ?: continue
                // The target this pairs with is the address that answered, so it is the one kept.
                _state.update { it.copy(screen = Screen.Pair(link.name, host, link.port)) }
                onPairResult(result, host, link.port)
                return@execute
            }
            closeSession()
            _state.update {
                it.copy(
                    busy = false,
                    message = "Couldn't reach ${link.name} at ${link.hosts.joinToString(" or ")}. Check the phone is on the same Wi-Fi as the PC.",
                )
            }
        }
    }

    /** Says the code to the PC on a fresh session. Null when the PC could not be reached at all. On [io]. */
    private fun attemptPair(host: String, port: Int, pin: String): PairResult? {
        // A fresh session for every attempt: the PC drops a connection that sits idle for 20
        // seconds, which is less time than it can take to walk over and read the code.
        closeSession()
        return try {
            val opened = RemoteSession.open(host, port)
            session = opened
            when (val hello = opened.hello(null, deviceName)) {
                HelloResult.PinRequired -> opened.pair(pin)
                is HelloResult.Welcome -> PairResult.Failed("Unexpected reply from the PC.")
                is HelloResult.Refused -> PairResult.Failed(hello.reason)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun onPairResult(result: PairResult, host: String, port: Int) {
        when (result) {
            is PairResult.Paired -> onConnected(SavedPc(result.pcName, host, port, result.token))
            PairResult.WrongPin -> _state.update { it.copy(busy = false, message = "That code isn't right. Check Settings › Keyboard shortcuts › Phone remote on the PC.") }
            PairResult.Locked -> {
                closeSession()
                _state.update { it.copy(busy = false, message = "Too many wrong codes. Wait a minute, then try again.") }
            }
            is PairResult.Failed -> {
                closeSession()
                _state.update { it.copy(busy = false, message = result.reason) }
            }
        }
    }

    fun cancelPairing() = showPicker()

    /** A line under the PC list or the code box, such as why a scan did not work. */
    fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    // --- Debug mode: the pretend TV inside the app (DemoTv), for testing away from the PC. ---

    /** Connects to the demo TV; the first time it asks for its code, [DemoTv.PIN], like a real PC. */
    fun tryDemo() {
        io.execute { DemoTv.startAndWait() }
        openAndGreet(DemoTv.NAME, DemoTv.HOST, DemoTv.PORT, token = _state.value.saved.firstOrNull(DemoTv::isDemo)?.token)
    }

    fun setDarkLevel(level: DarkLevel) {
        store.darkLevel = level
        _state.update { it.copy(darkLevel = level) }
    }

    fun setDark(on: Boolean) {
        _state.update { it.copy(dark = on) }
    }

    // --- The remote ---

    fun press(key: RemoteKey) = sendOrReconnect { it.key(key) }

    fun setPadMode(mode: PadMode) {
        store.padMode = mode
        _state.update { it.copy(padMode = mode) }
    }

    fun type(text: String) = sendOrReconnect { it.text(text) }

    fun backspace(count: Int) = sendOrReconnect { session -> repeat(count) { session.key(RemoteKey.Backspace) } }

    /** Sends a film or music extra (see [RemoteSession.control]); [shown] updates the card at once rather than waiting for the PC. */
    fun control(line: String, shown: (NowPlaying) -> NowPlaying = { it }) {
        _state.update { state -> state.copy(nowPlaying = state.nowPlaying?.let(shown)) }
        sendOrReconnect { it.control(line) }
    }

    /** Sets the TV's volume from the slider, 0 to the player's maximum. */
    fun setVolume(level: Int) {
        _state.update { state -> state.copy(nowPlaying = state.nowPlaying?.copy(volume = level)) }
        sendOrReconnect { it.volume(level) }
    }

    /** Seeks the PC's player; the card shows the new position at once rather than waiting for the PC. */
    fun seek(positionMs: Long) {
        _state.update { state ->
            state.copy(nowPlaying = state.nowPlaying?.copy(positionMs = positionMs, receivedAtMs = SystemClock.elapsedRealtime()))
        }
        sendOrReconnect { it.seek(positionMs) }
    }

    fun reconnect() {
        val pc = _state.value.current ?: return
        if (!foreground) return
        _state.update { it.copy(link = Link.Connecting) }
        io.execute {
            val alive = session?.let { it.isOpen && it.silentFor(SystemClock.elapsedRealtime()) < SILENT_MS && it.ping() } == true
            if (alive) {
                _state.update { it.copy(link = Link.Connected) }
                startKeepAlive()
                return@execute
            }
            closeSession()
            try {
                val opened = RemoteSession.open(pc.host, pc.port)
                session = opened
                when (val hello = opened.hello(pc.token, deviceName)) {
                    is HelloResult.Welcome -> onConnected(pc.copy(name = hello.pcName))
                    HelloResult.PinRequired -> {
                        closeSession()
                        _state.update {
                            it.copy(screen = Screen.Pair(pc.name, pc.host, pc.port), link = Link.Offline, message = "The PC wants pairing again.")
                        }
                    }
                    is HelloResult.Refused -> {
                        closeSession()
                        _state.update { it.copy(link = Link.Offline, message = hello.reason) }
                    }
                }
            } catch (_: IOException) {
                closeSession()
                _state.update { it.copy(link = Link.Offline) }
                scheduleRetry()
            }
        }
    }

    private fun sendOrReconnect(action: (RemoteSession) -> Unit) {
        io.execute {
            val current = session
            if (current == null || !current.isOpen || _state.value.link != Link.Connected) {
                viewModelScope.launch { reconnect() }
                return@execute
            }
            try {
                action(current)
            } catch (_: IOException) {
                closeSession()
                _state.update { it.copy(link = Link.Offline) }
                viewModelScope.launch { reconnect() }
            }
        }
    }

    // --- Internals (called on io) ---

    private fun onConnected(pc: SavedPc) {
        // Another PC has another library: forget the one shown.
        if (_state.value.current?.address != pc.address) {
            _state.update {
                it.copy(video = VideoLibraryState(), music = MusicLibraryState(), continueWatching = emptyList(), theme = store.theme(pc.address) ?: RemoteTheme.Default)
            }
        }
        saveAll(_state.value.saved.filterNot { it.address == pc.address } + pc)
        store.lastAddress = pc.address
        _state.update {
            it.copy(busy = false, message = null, screen = Screen.Remote, current = pc, link = Link.Connected, waking = false, canWake = store.wake(pc.address) != null)
        }
        session?.let(::listenTo)
        startKeepAlive()
    }

    /** From here on the PC's lines (now playing, pongs) arrive on the session's own thread. */
    private fun listenTo(opened: RemoteSession) {
        opened.listen(
            clock = SystemClock::elapsedRealtime,
            onLine = { line ->
                when (line) {
                    is ServerLine.State -> showNowPlaying(line.nowPlaying)
                    ServerLine.Idle -> _state.update { it.copy(nowPlaying = null, cover = null) }
                    is ServerLine.Theme -> {
                        _state.update { it.copy(theme = line.theme) }
                        _state.value.current?.let { pc -> store.saveTheme(pc.address, line.theme) }
                    }
                    is ServerLine.VideoLibrary -> onVideoLibrary(line)
                    is ServerLine.MusicLibrary -> onMusicLibrary(line)
                    is ServerLine.Continue -> _state.update { it.copy(continueWatching = line.items) }
                    is ServerLine.Sources -> _state.update { it.copy(sources = line.list) }
                    is ServerLine.Tonight -> _state.update { it.copy(tonight = line.screen) }
                    is ServerLine.LiveTv -> _state.update { it.copy(liveTv = line.screen) }
                    is ServerLine.Audio -> _state.update { it.copy(audio = line) }
                    is ServerLine.Subtitles -> _state.update { it.copy(subtitles = line) }
                    is ServerLine.Queue -> _state.update { it.copy(queue = line) }
                    is ServerLine.LyricsText -> _state.update { it.copy(lyrics = line) }
                    is ServerLine.AlbumTracks -> _state.update { it.copy(albumTracks = line) }
                    is ServerLine.Episodes -> _state.update { it.copy(episodes = line) }
                    is ServerLine.Wake -> _state.value.current?.let { pc ->
                        store.saveWake(pc.address, line)
                        _state.update { it.copy(canWake = line.macs.isNotEmpty()) }
                    }
                    is ServerLine.SearchResults -> _state.update { state ->
                        // Only the answer to the latest query: an older one still arriving is dropped.
                        if (state.search?.query?.equals(line.query, ignoreCase = true) == true) {
                            state.copy(search = SearchState(state.search.query, line.loading, line.titles))
                        } else {
                            state
                        }
                    }
                    is ServerLine.Opening -> notify("Opening ${line.what} on the TV")
                    is ServerLine.Score -> ScoreNotifications.show(getApplication(), line)
                    is ServerLine.Menu -> _state.update { state ->
                        val open = state.menu
                        if (open == null || open.target != line.target) state else state.copy(menu = open.copy(loaded = line))
                    }
                    is ServerLine.Done -> onMenuDone(line)
                    is ServerLine.Playlists -> _state.update { it.copy(playlistPick = line, menu = null) }
                    is ServerLine.Recap -> _state.update { state ->
                        // A late answer for a recap the phone already closed is dropped.
                        if (line.state != 0 && state.recap == null) state else state.copy(recap = line, menu = null)
                    }
                    ServerLine.CantOpen -> notify("Nuvio couldn't open that.")
                    else -> Unit
                }
            },
            onClosed = {
                onIo {
                    // Only the session in use going away matters; one closed on purpose has already been replaced.
                    if (session === opened) {
                        closeSession()
                        _state.update { it.copy(link = Link.Offline) }
                        viewModelScope.launch { reconnect() }
                    }
                }
            },
        )
        runCatching { opened.subscribe() }
    }

    /** After [onCleared] the executor refuses work; a socket closing late must not crash the app. */
    private fun onIo(work: () -> Unit) {
        try {
            io.execute(work)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun showNowPlaying(playing: NowPlaying) {
        val previousUrl = _state.value.nowPlaying?.imageUrl
        _state.update { state ->
            state.copy(nowPlaying = playing, cover = if (playing.imageUrl == previousUrl) state.cover else playing.imageUrl?.let(Covers::cached))
        }
        val url = playing.imageUrl
        if (url != null && url != previousUrl && Covers.cached(url) == null) {
            runCatching { coverIo.execute {
                val bitmap = Covers.load(url) ?: return@execute
                _state.update { state -> if (state.nowPlaying?.imageUrl == url) state.copy(cover = bitmap) else state }
            } }
        }
        // The lock screen and the notification take over while something plays; started from here, in the
        // foreground, as Android only lets a foreground app start one.
        if (foreground) NowPlayingService.start(getApplication())
    }

    private fun fail(reason: String) {
        closeSession()
        _state.update { it.copy(busy = false, message = reason) }
    }

    private fun closeSession() {
        session?.close()
        session = null
        _state.update { it.copy(nowPlaying = null, cover = null, sources = null, tonight = null, liveTv = null) }
    }

    /** A ping every few seconds, so a PC that went to sleep shows as offline before the next press. */
    private fun startKeepAlive() {
        keepAlive?.cancel()
        keepAlive = viewModelScope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                io.execute {
                    val current = session
                    val alive = current != null && current.silentFor(SystemClock.elapsedRealtime()) < SILENT_MS && current.ping()
                    if (!alive && _state.value.link == Link.Connected) {
                        closeSession()
                        _state.update { it.copy(link = Link.Offline) }
                        viewModelScope.launch { reconnect() }
                    }
                }
            }
        }
    }

    private var retry: Job? = null

    private fun scheduleRetry() {
        retry?.cancel()
        retry = viewModelScope.launch {
            delay(RETRY_MS)
            if (foreground && _state.value.screen == Screen.Remote && _state.value.link == Link.Offline) reconnect()
        }
    }

    private fun saveAll(list: List<SavedPc>) {
        store.saveAll(list)
        _state.update { it.copy(saved = list.sortedBy { pc -> pc.name.lowercase() }) }
    }

    // --- The library: browsed here, opened or played on the TV. ---

    private var videoTimeout: Job? = null
    private var musicTimeout: Job? = null
    private var videoTries = 0
    private var musicTries = 0

    /** Asks the PC for its films and shows; [force] asks again even when a list is already here. */
    fun loadVideo(force: Boolean = false) {
        val current = _state.value.video
        if (current.loading || (current.loaded && !force)) return
        videoTries = 0
        _state.update { it.copy(video = it.video.copy(loading = true, problem = null)) }
        askVideo()
    }

    fun loadMusic(force: Boolean = false) {
        val current = _state.value.music
        if (current.loading || (current.loaded && !force)) return
        musicTries = 0
        _state.update { it.copy(music = it.music.copy(loading = true, problem = null)) }
        askMusic()
    }

    private fun askVideo() {
        sendOrReconnect { it.requestLibrary(LibraryLines.VIDEO) }
        videoTimeout?.cancel()
        videoTimeout = viewModelScope.launch {
            delay(LIBRARY_TIMEOUT_MS)
            _state.update {
                if (!it.video.loading) it else it.copy(video = it.video.copy(loading = false, problem = noAnswer()))
            }
        }
    }

    private fun askMusic() {
        sendOrReconnect { it.requestLibrary(LibraryLines.MUSIC) }
        musicTimeout?.cancel()
        musicTimeout = viewModelScope.launch {
            delay(LIBRARY_TIMEOUT_MS)
            _state.update {
                if (!it.music.loading) it else it.copy(music = it.music.copy(loading = false, problem = noAnswer()))
            }
        }
    }

    private fun noAnswer(): String =
        if (_state.value.link == Link.Connected) "${_state.value.current?.name ?: "The PC"} didn't send its library. Update Nuvio on the PC, then try again."
        else "Not connected to the PC."

    /** The PC is still loading its library while `ready` is 0: what it has is shown, and it is asked again shortly. */
    private fun onVideoLibrary(line: ServerLine.VideoLibrary) {
        videoTimeout?.cancel()
        val again = !line.ready && videoTries++ < LIBRARY_RETRIES
        _state.update {
            // Still partial after every retry: shown, but asked for again the next time the Watch tab opens.
            it.copy(video = it.video.copy(titles = line.titles, loading = again, loaded = !again && line.ready, problem = null))
        }
        if (again) viewModelScope.launch { delay(LIBRARY_RETRY_MS); askVideo() }
    }

    private fun onMusicLibrary(line: ServerLine.MusicLibrary) {
        musicTimeout?.cancel()
        val again = !line.ready && musicTries++ < LIBRARY_RETRIES
        _state.update {
            it.copy(music = it.music.copy(artists = line.artists, albums = line.albums, loading = again, loaded = !again, problem = null))
        }
        if (again) viewModelScope.launch { delay(LIBRARY_RETRY_MS); askMusic() }
    }

    /** Asks again every time the Watch tab shows: what is in progress changes with every film. */
    fun loadContinue() = sendOrReconnect { it.requestContinue() }

    // --- The music queue and lyrics (phase 8) ---

    /** Asks the TV for its subtitle list (the sheet shows "Asking the TV…" until it comes). */
    fun loadSubtitles() {
        _state.update { it.copy(subtitles = null) }
        sendOrReconnect { it.requestSubtitles() }
    }

    fun loadAudio() {
        _state.update { it.copy(audio = null) }
        sendOrReconnect { it.requestAudio() }
    }

    fun pickAudio(choice: SubtitleChoice) = sendOrReconnect { it.pickAudio(choice.number) }

    fun pickSubtitle(choice: SubtitleChoice) = sendOrReconnect { it.pickSubtitle(choice.number) }

    /** A show's season list, for its sheet. */
    fun loadEpisodes(title: LibraryTitle) {
        val known = _state.value.episodes
        if (known != null && known.id == title.id && known.found) return
        _state.update { it.copy(episodes = null) }
        sendOrReconnect { it.episodes(title.type, title.id) }
    }

    fun playEpisode(title: LibraryTitle, episode: EpisodeInfo) = sendOrReconnect { it.playEpisode(title.type, title.id, episode.videoId) }

    /** The album sheet's song list. */
    fun loadAlbumTracks(album: LibraryAlbum) {
        if (_state.value.albumTracks?.browseId == album.browseId) return
        _state.update { it.copy(albumTracks = null) }
        sendOrReconnect { it.albumTracks(album.browseId) }
    }

    fun playTrack(album: LibraryAlbum, index: Int) = sendOrReconnect { it.playTrack(album.browseId, index) }

    fun loadQueue() = sendOrReconnect { it.requestQueue() }

    fun queueAction(action: String, track: QueueTrack) = sendOrReconnect { it.queueAction(action, track.videoId) }

    /** Asks for the playing song's lyrics; what was shown stays until the new ones arrive, unless it was another song. */
    fun loadLyrics(title: String) {
        _state.update { if (it.lyrics?.title == title) it else it.copy(lyrics = null) }
        sendOrReconnect { it.requestLyrics() }
    }

    private var wakeJob: Job? = null

    /**
     * Wakes the PC from sleep (a Wake-on-LAN packet to its network card), then keeps trying to connect for a minute and
     * a half while it starts up.
     */
    fun wakePc() {
        val pc = _state.value.current ?: return
        val wake = store.wake(pc.address) ?: return
        _state.update { it.copy(waking = true, message = null) }
        io.execute {
            val sent = runCatching { WakeOnLan.send(wake.macs, wake.broadcasts + pc.host) }.getOrDefault(0)
            if (sent == 0) _state.update { it.copy(waking = false, message = "Couldn't send the wake-up. Is the phone on the home Wi-Fi?") }
        }
        wakeJob?.cancel()
        wakeJob = viewModelScope.launch {
            repeat(WAKE_TRIES) {
                delay(WAKE_RETRY_MS)
                if (_state.value.link == Link.Connected) return@launch
                reconnect()
            }
            if (_state.value.link != Link.Connected) {
                _state.update { it.copy(waking = false, message = "${pc.name} didn't wake. Check Wake-on-LAN is on for its network card.") }
            }
        }
    }

    /** Searches every catalog on the PC (as its Search tab does); blank clears it. */
    fun searchEverywhere(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(search = null) }
            return
        }
        if (_state.value.search?.query == trimmed) return
        _state.update { it.copy(search = SearchState(trimmed, loading = true, titles = emptyList())) }
        sendOrReconnect { it.search(trimmed) }
    }

    fun pickSource(source: SourceInfo) = sendOrReconnect { it.pickSource(source.number) }

    /** Taps on the Live TV mirror (Nov 14). */
    fun liveTvStage(name: String) = sendOrReconnect { it.liveTvStage(name) }
    fun liveTvItem(group: String, index: Int) = sendOrReconnect { it.liveTvItem(group, index) }
    fun liveTvAll() = sendOrReconnect { it.liveTvAll() }

    /** A tap on the Tonight mirror; see RemoteSession.tonight. */
    fun tonight(action: String, number: Int = 0) = sendOrReconnect { it.tonight(action, number) }

    fun resume(item: ResumeItem) = sendOrReconnect { it.resume(item.parentId, item.videoId) }

    fun openDetails(item: ResumeItem) = sendOrReconnect { it.openTitle(item.type, item.parentId) }

    fun openTitle(title: LibraryTitle) = sendOrReconnect { it.openTitle(title.type, title.id) }

    fun album(album: LibraryAlbum, action: AlbumAction) = sendOrReconnect { it.album(album.browseId, action) }

    fun artist(artist: LibraryArtistInfo, action: ArtistAction) = sendOrReconnect { it.artist(artist.id, action) }

    // --- The long-press sheet on posters, Continue Watching cards, episodes and seasons ---

    /** Opens the sheet for [target] at once (heading and picture from the phone) and asks the PC for its rows. */
    fun openMenu(target: MenuTarget, heading: String, detail: String?, image: String?, imageFallback: String? = null, wide: Boolean = false) {
        _state.update { it.copy(menu = MenuSheet(target, heading, detail, image, imageFallback, wide)) }
        sendOrReconnect { it.menu(target) }
    }

    fun closeMenu() = _state.update { it.copy(menu = null) }

    /**
     * A row of the sheet. The playlist and recap rows open their own sheet when the PC answers; the rest close it, and
     * the PC's DONE line says how it went.
     */
    fun menuAction(action: MenuAction) {
        val sheet = _state.value.menu ?: return
        when (action.code) {
            MenuCode.PLAYLIST, MenuCode.PLAYLIST_SEASON -> _state.update { it.copy(menu = sheet.copy(doing = action.code)) }
            MenuCode.RECAP -> _state.update { it.copy(menu = null, recap = ServerLine.Recap(0, sheet.heading, null)) }
            else -> _state.update { it.copy(menu = null) }
        }
        sendOrReconnect { it.act(sheet.target, action.code) }
    }

    /** Adds what the picker was opened for to [playlistId] ([MenuCode.NEW_PLAYLIST] makes one); [season] 0 is every season. */
    fun addToPlaylist(playlistId: String, season: Int = 0) {
        val pick = _state.value.playlistPick ?: return
        _state.update { it.copy(playlistPick = null) }
        sendOrReconnect { it.act(pick.target, MenuCode.ADD_TO_PLAYLIST, listOf(playlistId, pick.scope, season.toString())) }
    }

    fun closePlaylists() = _state.update { it.copy(playlistPick = null) }

    fun closeRecap() = _state.update { it.copy(recap = null) }

    private fun onMenuDone(line: ServerLine.Done) {
        _state.update { state -> state.copy(menu = state.menu?.takeIf { it.doing == null }) }
        notify(line.message.ifBlank { if (line.ok) "Done" else "That didn't work on the TV" })
        if (LibraryLines.REFRESH_LIBRARY in line.refresh) loadVideo(force = true)
        if (LibraryLines.REFRESH_CONTINUE in line.refresh) loadContinue()
        if (LibraryLines.REFRESH_EPISODES in line.refresh) {
            _state.value.episodes?.let { open -> sendOrReconnect { it.episodes(open.type, open.id) } }
        }
    }

    private var noticeJob: Job? = null

    private fun notify(text: String) {
        _state.update { it.copy(notice = text) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _state.update { if (it.notice == text) it.copy(notice = null) else it }
        }
    }

    private companion object {
        const val LIBRARY_TIMEOUT_MS = 8_000L
        const val LIBRARY_RETRY_MS = 1_500L
        /** About half a minute: a Trakt or MDBList library can take that long to load after Nuvio starts. */
        const val LIBRARY_RETRIES = 20
        const val NOTICE_MS = 3_500L
        /** A PC that has said nothing for this long (it answers every ping) has gone. */
        const val SILENT_MS = 12_000L
        const val PING_INTERVAL_MS = 5_000L
        const val RETRY_MS = 3_000L
        const val WAKE_RETRY_MS = 3_000L
        const val WAKE_TRIES = 30
    }
}
