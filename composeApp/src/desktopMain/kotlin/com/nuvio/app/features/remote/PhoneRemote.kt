package com.nuvio.app.features.remote

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.input.GamepadButton
import com.nuvio.app.features.input.GamepadContext
import com.nuvio.app.features.input.GamepadDefaults
import com.nuvio.app.features.input.GamepadKeyInjector
import com.nuvio.app.features.input.GamepadTarget
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.PlayerShortcutAction
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicPlayerState
import com.nuvio.app.features.music.coverUrl
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.loadPlaylistQueue
import com.nuvio.app.features.music.loadSongRadio
import com.nuvio.app.features.music.MusicRepeat
import com.nuvio.app.features.music.library.MusicAccountShelves
import com.nuvio.app.features.music.library.MusicLikedSongs
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.toArgb
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.features.settings.ThemeSettingsRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.event.KeyEvent
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * fork:phone-remote — lets the Android remote in `remote-android/` drive Nuvio over the home network.
 *
 * It adds no input path of its own: every button becomes the same keystroke or shortcut a
 * controller would send through [GamepadKeyInjector], so anything that works with a pad works from
 * the phone, and rebinding a keyboard shortcut moves the phone's button with it.
 *
 * Off by default. The setting, the PIN and the paired phones are per machine rather than per
 * profile: the phone pairs with the PC, whoever happens to be signed in.
 */
internal object PhoneRemote {
    private val log = Logger.withTag("PhoneRemote")
    private val store = DesktopStorage.store("nuvio_phone_remote")
    private val random = SecureRandom()

    private const val KEY_ENABLED = "enabled"
    private const val KEY_PIN = "pin"
    private const val KEY_DEVICES = "paired_devices"

    private val _state = MutableStateFlow(PhoneRemoteState())
    val state: StateFlow<PhoneRemoteState> = _state.asStateFlow()

    private var loaded = false
    private var started = false
    private var server: PhoneRemoteServer? = null

    /** Main for anything that touches a player; the state watcher runs off it. */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateWatch: Job? = null
    private var themeWatch: Job? = null
    private var sourcesWatch: Job? = null
    private var tonightWatch: Job? = null // fork:phone-remote tonight
    private var liveTvWatch: Job? = null // fork:phone-remote live-tv
    private var scoreWatch: Job? = null // fork:live-scores

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _state.update {
            it.copy(
                enabled = store.getBoolean(KEY_ENABLED) ?: false,
                pin = store.getString(KEY_PIN).orEmpty(),
                pairedDevices = pairedEntries().map { entry -> entry.second }.sorted(),
                pcName = computerName(),
            )
        }
    }

    /** Called with the main window; listens only while the setting is on. */
    @Synchronized
    fun start() {
        ensureLoaded()
        started = true
        if (_state.value.enabled) openServer()
    }

    @Synchronized
    fun stop() {
        started = false
        closeServer()
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (value) ensurePin()
        store.putBoolean(KEY_ENABLED, value)
        _state.update { it.copy(enabled = value) }
        if (value && started) openServer() else if (!value) closeServer()
    }

    /** A new PIN, and every phone has to pair again. */
    @Synchronized
    fun resetPairing() {
        ensureLoaded()
        store.putString(KEY_PIN, newPin())
        store.putStringSet(KEY_DEVICES, emptySet())
        _state.update { it.copy(pin = store.getString(KEY_PIN).orEmpty(), pairedDevices = emptyList()) }
        server?.disconnectAll()
    }

    private fun openServer() {
        if (server != null) return
        ensurePin()
        val created = PhoneRemoteServer(
            name = ::computerName,
            pairing = pairing,
            listener = listener,
            log = { message -> log.i { message } },
        )
        created.start()
        created.wakeLine = wakeInfo()
        server = created
        watchState(created)
        watchTheme(created)
        watchSources(created)
        watchTonight(created)
        watchLiveTv(created)
        watchScores(created)
        _state.update { it.copy(listening = created.port != null, port = created.port, addresses = localAddresses()) }
    }

    private fun closeServer() {
        stateWatch?.cancel()
        stateWatch = null
        themeWatch?.cancel()
        themeWatch = null
        sourcesWatch?.cancel()
        sourcesWatch = null
        tonightWatch?.cancel()
        tonightWatch = null
        liveTvWatch?.cancel()
        liveTvWatch = null
        scoreWatch?.cancel()
        scoreWatch = null
        server?.stop()
        server = null
        _state.update { it.copy(listening = false, port = null, connectedDevices = emptyList()) }
    }

    private fun ensurePin() {
        if (store.getString(KEY_PIN).isNullOrBlank()) store.putString(KEY_PIN, newPin())
        _state.update { it.copy(pin = store.getString(KEY_PIN).orEmpty()) }
    }

    private fun newPin(): String =
        (0 until PhoneRemoteProtocol.PIN_LENGTH).joinToString("") { random.nextInt(10).toString() }

    /** Stored as "token|name" so Settings can list who is paired without a second store. */
    private fun pairedEntries(): List<Pair<String, String>> =
        store.getStringSet(KEY_DEVICES).orEmpty().map { it.substringBefore('|') to it.substringAfter('|', "Phone") }

    private val pairing = object : PhoneRemotePairing {
        override fun pin(): String = store.getString(KEY_PIN).orEmpty().ifBlank { "\u0000" }

        override fun isKnownToken(token: String): Boolean = pairedEntries().any { it.first == token }

        override fun issueToken(deviceName: String): String {
            val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val safeName = deviceName.replace('|', ' ')
            synchronized(this@PhoneRemote) {
                // One entry per phone name: pairing the same phone again replaces its old token.
                val kept = store.getStringSet(KEY_DEVICES).orEmpty().filterNot { it.substringAfter('|') == safeName }
                store.putStringSet(KEY_DEVICES, (kept + "$token|$safeName").toSet())
                _state.update { it.copy(pairedDevices = pairedEntries().map { entry -> entry.second }.sorted()) }
            }
            return token
        }
    }

    private val listener = object : PhoneRemoteListener {
        override fun onKey(key: RemoteKey) = dispatch(key)

        override fun onText(text: String) {
            text.forEach { char ->
                when {
                    char == '\n' -> GamepadKeyInjector.sendKeyCode(KeyEvent.VK_ENTER)
                    char.isISOControl() -> Unit
                    else -> GamepadKeyInjector.typeCharacter(char)
                }
            }
        }

        override fun onConnectedDevicesChanged(names: List<String>) {
            _state.update { it.copy(connectedDevices = names) }
        }

        override fun onSeek(positionMs: Long) {
            mainScope.launch {
                val film = RemotePlayerHub.filmControls
                when {
                    film != null -> film.seekTo(positionMs)
                    musicActive() -> MusicPlayback.player.seekTo(positionMs)
                }
            }
        }

        override fun onVolume(volume: Int) {
            mainScope.launch {
                val film = RemotePlayerHub.filmControls
                when {
                    film != null -> film.setVolume(volume)
                    musicActive() -> MusicPlayback.player.setVolume(volume.coerceIn(0, 100))
                }
            }
        }

        override fun onControl(action: ControlAction, value: Int) = control(action, value)

        override fun onOpen(url: String): String? = openShared(url)

        // fork:phone-remote library: browsing on the phone, choosing plays or opens on the TV.
        override fun onLibrary(kind: String): String? = PhoneRemoteLibrary.line(kind)
        override fun onOpenTitle(type: String, id: String): String? = PhoneRemoteLibrary.openTitle(type, id)
        override fun onPlayAlbum(browseId: String, action: AlbumAction): String? = PhoneRemoteLibrary.playAlbum(browseId, action)
        override fun onArtist(artistId: String, action: ArtistAction): String? = PhoneRemoteLibrary.artist(artistId, action)
        override fun onContinue(): String? = PhoneRemoteLibrary.continueLine()
        override fun onResume(parentId: String, videoId: String): String? = PhoneRemoteLibrary.resume(parentId, videoId)
        override fun onPickSource(number: Int): String? = pickSource(number)
        override fun onAudio(reply: (String) -> Unit) = reply(PhoneRemoteLibraryLines.audioLine(RemotePlayerHub.filmControls?.audioChoices()))
        override fun onPickAudio(number: Int, reply: (String) -> Unit) = pickAudio(number, reply)
        override fun onSubtitles(reply: (String) -> Unit) = subtitles(reply)
        override fun onPickSubtitle(number: Int, reply: (String) -> Unit) = pickSubtitle(number, reply)
        override fun onSearch(query: String, reply: (String) -> Unit) = PhoneRemoteLibrary.search(query, reply)
        override fun onQueue(): String = PhoneRemoteMusic.queueLine()
        override fun onQueueAction(action: String, videoId: String, reply: (String) -> Unit) =
            PhoneRemoteMusic.queueAction(action, videoId, reply)
        override fun onLyrics(reply: (String) -> Unit) = PhoneRemoteMusic.lyrics(reply)
        override fun onEpisodes(type: String, id: String, reply: (String) -> Unit) = PhoneRemoteLibrary.episodes(type, id, reply)
        override fun onPlayEpisode(type: String, id: String, videoId: String): String? = PhoneRemoteLibrary.playEpisode(type, id, videoId)
        override fun onMenu(target: RemoteMenuTarget, reply: (String) -> Unit) = PhoneRemoteActions.menu(target, reply)
        override fun onMenuAction(target: RemoteMenuTarget, code: String, args: List<String>, reply: (String) -> Unit) =
            PhoneRemoteActions.act(target, code, args, reply)
        override fun onAlbumTracks(browseId: String, reply: (String) -> Unit) = PhoneRemoteMusic.albumTracks(browseId, reply)
        override fun onPlayTrack(browseId: String, index: Int): String? = PhoneRemoteMusic.playTrack(browseId, index)
        override fun onLiveTv(action: com.nuvio.app.features.remote.RemoteLiveTvDo) {
            mainScope.launch { com.nuvio.app.features.remote.RemoteLiveTv.act(action) }
        }
        override fun onTonight(action: RemoteTonightAction, number: Int) {
            mainScope.launch {
                // "What to watch?" on the phone: the same as the button beside Search on the TV.
                if (action == RemoteTonightAction.Open) {
                    com.nuvio.app.features.navbar.NavBarNavigation.request(com.nuvio.app.features.navbar.NavBarEntry.Tonight)
                } else {
                    RemoteTonight.act(action, number)
                }
            }
        }
    }

    // --- fork:phone-remote: now playing on the phone ---

    private fun musicActive(): Boolean = MusicPlayback.player.state.value.isActive

    /** The film player while it is open, else the music player while it has a track, else nothing. */
    private fun watchState(target: PhoneRemoteServer) {
        stateWatch?.cancel()
        val throttle = RemoteStateThrottle()
        // Read here, on the caller's (main) thread: the music player is created on first use.
        val musicState = MusicPlayback.player.state
        stateWatch = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(
                combine(RemotePlayerHub.film, musicState, _sleepMinutes) { film, music, sleep -> Triple(film, music, sleep) },
                MusicAccountShelves.state,
                MusicLikedSongs.overrides,
            ) { (film, music, sleep), account, overrides ->
                if (RemotePlayerHub.filmControls != null) {
                    film?.copy(sleepMinutes = sleep)
                } else {
                    val liked = music.track?.videoId?.takeIf { YouTubeMusicAuth.isSignedIn }
                        ?.let { MusicLikedSongs.isLiked(it, account.liked, overrides) }
                    musicNowPlaying(music)?.copy(liked = liked, sleepMinutes = sleep)
                }
            }.collect { state ->
                if (throttle.shouldSend(state)) target.publishState(PhoneRemoteProtocol.stateLine(state))
            }
        }
    }

    // --- fork:phone-remote extras: speed, skip, next episode, sleep timer, shuffle, repeat, like, radio ---

    private val _sleepMinutes = MutableStateFlow(0)
    private var sleepJob: Job? = null

    private fun control(action: ControlAction, value: Int) {
        mainScope.launch {
            val film = RemotePlayerHub.filmControls
            when (action) {
                ControlAction.Speed -> film?.setSpeedPercent(value.coerceIn(50, 400))
                ControlAction.SkipIntro -> film?.skipSegment()
                ControlAction.NextEpisode -> film?.nextEpisode()
                ControlAction.Sleep -> setSleep(value)
                ControlAction.Shuffle -> if (musicActive()) MusicPlayback.player.setShuffled(value == 1)
                ControlAction.Repeat -> if (musicActive()) {
                    MusicPlayback.player.setRepeat(MusicRepeat.entries.getOrElse(value) { MusicRepeat.Off })
                }
                ControlAction.Like -> likeCurrent()
                ControlAction.Radio -> startRadio()
            }
        }
    }

    /** Pauses whatever is playing after [minutes] (0 turns the timer off); the phone shows the minutes left. */
    private fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepMinutes.value = 0
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepMinutes.value = minutes
        log.i { "Phone remote: sleep timer set for $minutes min" }
        sleepJob = mainScope.launch {
            while (true) {
                val left = endsAt - System.currentTimeMillis()
                if (left <= 0L) break
                _sleepMinutes.value = ((left + 59_999L) / 60_000L).toInt()
                delay(minOf(left, SLEEP_TICK_MS))
            }
            _sleepMinutes.value = 0
            val film = RemotePlayerHub.filmControls
            if (film != null) film.pause() else if (musicActive()) MusicPlayback.player.pause()
            log.i { "Phone remote: sleep timer ended, paused" }
        }
    }

    private fun likeCurrent() {
        val videoId = MusicPlayback.player.state.value.track?.videoId ?: return
        if (!YouTubeMusicAuth.isSignedIn) return
        val liked = MusicLikedSongs.isLiked(videoId, MusicAccountShelves.state.value.liked, MusicLikedSongs.overrides.value)
        MusicLikedSongs.toggle(defaultMusicCatalog(), videoId, liked)
    }

    private suspend fun startRadio() {
        val track = MusicPlayback.player.state.value.track ?: return
        val queue = loadSongRadio(defaultMusicCatalog(), track.videoId, track.title, track.artists) ?: return
        MusicPlayback.player.play(queue)
    }

    private const val SLEEP_TICK_MS = 15_000L

    /**
     * fork:phone-remote theme: the phone wears the TV's theme. The accent (and the second stop of a custom gradient)
     * and the background go over as a THEME line, again whenever the theme is changed in Settings.
     */
    private fun watchTheme(target: PhoneRemoteServer) {
        themeWatch?.cancel()
        ThemeSettingsRepository.ensureLoaded()
        themeWatch = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(ThemeSettingsRepository.selectedTheme, ThemeSettingsRepository.customTheme) { theme, custom ->
                val palette = if (theme == AppTheme.CUSTOM) custom.palette else ThemeColors.getColorPalette(theme)
                PhoneRemoteProtocol.themeLine(
                    accent = palette.secondary.toArgb(),
                    accentEnd = palette.accentGradientEnd?.toArgb(),
                    background = palette.background.toArgb(),
                )
            }.distinctUntilChanged().collect(target::publishTheme)
        }
    }

    /** fork:live-scores - a favourite team's goal, card or whistle as a notification on the phone. */
    private fun watchScores(target: PhoneRemoteServer) {
        scoreWatch?.cancel()
        scoreWatch = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            com.nuvio.app.features.livescores.LiveScoreEngine.events.collect { event ->
                val settings = com.nuvio.app.features.livescores.LiveScoreSettingsRepository.state.value
                if (!settings.enabled || !settings.phoneNotifications || !event.favourite) return@collect
                target.publishScore(com.nuvio.app.features.livescores.phoneScoreLine(event))
            }
        }
    }

    /** fork:phone-remote live-tv: the Live TV stage as the phone mirrors it, whenever it changes. */
    private fun watchLiveTv(target: PhoneRemoteServer) {
        liveTvWatch?.cancel()
        liveTvWatch = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            com.nuvio.app.features.remote.RemoteLiveTv.current
                .map(PhoneRemoteLibraryLines::liveTvLine)
                .distinctUntilChanged()
                .collect(target::publishLiveTv)
        }
    }

    /** fork:phone-remote tonight: the Tonight picker as the phone mirrors it, whenever it changes. */
    private fun watchTonight(target: PhoneRemoteServer) {
        tonightWatch?.cancel()
        tonightWatch = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            RemoteTonight.current
                .map(PhoneRemoteLibraryLines::tonightLine)
                .distinctUntilChanged()
                .collect(target::publishTonight)
        }
    }

    /**
     * fork:phone-remote sources: the source list on screen goes to the phone as it fills, at most a few times a second
     * (each provider that answers re-sorts it), and once more when it closes.
     */
    private fun watchSources(target: PhoneRemoteServer) {
        sourcesWatch?.cancel()
        sourcesWatch = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            RemoteSources.current
                .map(PhoneRemoteLibraryLines::sourcesLine)
                .distinctUntilChanged()
                .conflate()
                .collect { line ->
                    target.publishSources(line)
                    delay(SOURCES_THROTTLE_MS)
                }
        }
    }

    /**
     * fork:phone-remote subtitles: the film player's subtitle list, to this phone. When only Off is there the add-ons
     * are asked again, and the list is sent once more a few seconds later with whatever they found.
     */
    private fun subtitles(reply: (String) -> Unit) {
        val film = RemotePlayerHub.filmControls
        if (film == null) {
            reply(PhoneRemoteLibraryLines.subtitlesLine(null))
            return
        }
        reply(PhoneRemoteLibraryLines.subtitlesLine(film.subtitleChoices()))
        if (film.subtitleChoices().choices.size <= 1) {
            film.fetchSubtitles()
            mainScope.launch {
                repeat(SUBTITLE_REFRESHES) {
                    delay(SUBTITLE_REFRESH_MS)
                    val now = film.subtitleChoices()
                    reply(PhoneRemoteLibraryLines.subtitlesLine(now))
                    if (!now.loading && now.choices.size > 1) return@launch
                }
            }
        }
    }

    private fun pickAudio(number: Int, reply: (String) -> Unit) {
        val film = RemotePlayerHub.filmControls ?: return
        if (!film.pickAudio(number)) return
        log.i { "Phone remote: audio $number picked" }
        mainScope.launch {
            delay(SUBTITLE_PICK_SETTLE_MS)
            reply(PhoneRemoteLibraryLines.audioLine(film.audioChoices()))
        }
    }

    private fun pickSubtitle(number: Int, reply: (String) -> Unit) {
        val film = RemotePlayerHub.filmControls ?: return
        if (!film.pickSubtitle(number)) return
        log.i { "Phone remote: subtitle $number picked" }
        mainScope.launch {
            delay(SUBTITLE_PICK_SETTLE_MS)
            reply(PhoneRemoteLibraryLines.subtitlesLine(film.subtitleChoices()))
        }
    }

    private const val SUBTITLE_REFRESHES = 4
    private const val SUBTITLE_REFRESH_MS = 2_000L
    private const val SUBTITLE_PICK_SETTLE_MS = 400L

    /** Plays the [number]th source on screen as a click on it would; null when there is none to play. */
    private fun pickSource(number: Int): String? {
        val list = RemoteSources.current.value ?: return null
        val source = list.sources.getOrNull(number - 1)?.takeIf { it.playable } ?: return null
        val target = com.nuvio.app.features.voice.VoiceScreenTargets.sources ?: return null
        log.i { "Phone remote: playing source $number of ${list.sources.size}" }
        mainScope.launch { target(number)?.let { problem -> log.w { "Phone remote: source $number: $problem" } } }
        return source.name
    }

    internal fun musicNowPlaying(state: MusicPlayerState): RemoteNowPlaying? {
        val track = state.track ?: return null
        val album = track.albumTitle ?: state.album?.title
        return RemoteNowPlaying(
            kind = "music",
            title = track.title,
            subtitle = listOfNotNull(
                track.artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                album?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            imageUrl = state.coverUrl,
            isPlaying = state.isPlaying,
            positionMs = state.positionMs,
            durationMs = state.durationMs.takeIf { it > 0L } ?: track.durationSeconds * 1000L,
            volume = state.volume,
            volumeMax = 100,
            canSkip = true,
            shuffle = state.shuffled,
            repeat = state.repeat.ordinal,
        )
    }

    /** A link from the phone's share menu. Returns what is opening, for the phone to show, or null if it cannot be. */
    private fun openShared(text: String): String? {
        val link = PhoneRemoteLinks.parse(text) ?: return null
        log.i { "Phone remote: opening a shared link ($link)" }
        when (link) {
            is SharedLink.YouTubeVideo -> {
                RemotePlayerHub.openYouTube(RemoteYouTubeOpen(link.videoId, link.startSeconds))
                return "a YouTube video"
            }
            is SharedLink.MusicSong -> mainScope.launch {
                NuvioToastController.show("Playing a song from your phone…")
                val queue = loadSongRadio(defaultMusicCatalog(), link.videoId, "Song")
                val first = queue?.tracks?.firstOrNull()
                if (queue == null || first == null) {
                    NuvioToastController.show("Couldn't play the song from your phone")
                } else {
                    MusicPlayback.player.play(queue.copy(title = "${first.title} radio", artists = first.artists))
                }
            }
            is SharedLink.MusicList -> mainScope.launch {
                NuvioToastController.show("Playing a playlist from your phone…")
                val queue = loadPlaylistQueue(defaultMusicCatalog(), link.playlistId, "Playlist")
                if (queue == null) NuvioToastController.show("Couldn't play the playlist from your phone")
                else MusicPlayback.player.play(queue)
            }
            is SharedLink.App -> mainScope.launch { com.nuvio.app.core.deeplink.handleAppUrl(link.url) }
            is SharedLink.Stream -> mainScope.launch { com.nuvio.app.features.player.LocalFileDrop.emit(link.url) }
        }
        return when (link) {
            is SharedLink.MusicSong -> "a song"
            is SharedLink.MusicList -> "a playlist"
            is SharedLink.App -> "a Nuvio link"
            is SharedLink.Stream -> "a stream"
            is SharedLink.YouTubeVideo -> "a YouTube video"
        }
    }

    /**
     * Select and Back mirror the controller's A and B exactly, player behaviour included (A is
     * play/pause in the player, B closes the panel that is up), so the phone and a pad never
     * disagree. The rest name an action outright.
     */
    internal fun targetFor(key: RemoteKey, inPlayer: Boolean): GamepadTarget? {
        fun pad(button: GamepadButton) = GamepadDefaults.binding(button).let { if (inPlayer) it.player else it.browsing }
        fun app(action: AppShortcutAction) = GamepadTarget.AppAction(action)
        fun player(action: PlayerShortcutAction) = GamepadTarget.PlayerAction(action)
        return when (key) {
            RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right,
            RemoteKey.Backspace, RemoteKey.Enter -> null // plain keys, see keyCodeFor
            RemoteKey.Select -> pad(GamepadButton.A)
            RemoteKey.Back -> pad(GamepadButton.B)
            RemoteKey.Home -> app(AppShortcutAction.GoHome)
            RemoteKey.Search -> app(AppShortcutAction.OpenSearch)
            RemoteKey.PlayPause -> player(PlayerShortcutAction.PlayPause)
            RemoteKey.SeekBack -> player(PlayerShortcutAction.SeekBackward)
            RemoteKey.SeekForward -> player(PlayerShortcutAction.SeekForward)
            RemoteKey.Mute -> player(PlayerShortcutAction.ToggleMute)
            RemoteKey.Subtitles -> player(PlayerShortcutAction.NextSubtitle)
            RemoteKey.Sources -> player(PlayerShortcutAction.OpenSources)
            RemoteKey.Audio -> player(PlayerShortcutAction.NextAudio)
            // The player pins the up/down arrows to volume; outside it they would move the focus,
            // which is not what a volume button should do, so they are dropped there.
            RemoteKey.VolumeUp, RemoteKey.VolumeDown -> null
            // Only the music queue has a next and previous; the film player has no shortcut for them.
            RemoteKey.Next, RemoteKey.Previous -> null
        }
    }

    internal fun keyCodeFor(key: RemoteKey, inPlayer: Boolean): Int? = when (key) {
        RemoteKey.Up -> KeyEvent.VK_UP
        RemoteKey.Down -> KeyEvent.VK_DOWN
        RemoteKey.Left -> KeyEvent.VK_LEFT
        RemoteKey.Right -> KeyEvent.VK_RIGHT
        RemoteKey.Backspace -> KeyEvent.VK_BACK_SPACE
        RemoteKey.Enter -> KeyEvent.VK_ENTER
        RemoteKey.VolumeUp -> if (inPlayer) KeyEvent.VK_UP else null
        RemoteKey.VolumeDown -> if (inPlayer) KeyEvent.VK_DOWN else null
        else -> null
    }

    private fun dispatch(key: RemoteKey) {
        // The music player, when no film is up, takes the transport keys directly: they work with Nuvio in the
        // background too (the phone's lock screen), and outside the film player there are no shortcuts for them.
        if (RemotePlayerHub.filmControls == null && musicActive() && dispatchToMusic(key)) return
        val inPlayer = GamepadContext.playerActive
        val sent = keyCodeFor(key, inPlayer)?.let(GamepadKeyInjector::sendKeyCode)
            ?: targetFor(key, inPlayer)?.let(GamepadKeyInjector::send)
            ?: false
        if (sent) return
        // Nuvio is not the focused window: the transport keys still reach the film player directly.
        val film = RemotePlayerHub.filmControls
        if (film != null && dispatchToFilm(key, film)) return
        log.d { "Phone remote: ${key.wire} did nothing (unbound, or Nuvio is not the focused window)" }
    }

    private fun dispatchToMusic(key: RemoteKey): Boolean {
        val player = MusicPlayback.player
        val action: (() -> Unit)? = when (key) {
            RemoteKey.PlayPause -> player::togglePlayback
            RemoteKey.Next -> player::next
            RemoteKey.Previous -> player::previous
            RemoteKey.SeekBack -> { { player.seekTo((player.state.value.positionMs - SEEK_STEP_MS).coerceAtLeast(0L)) } }
            RemoteKey.SeekForward -> { { player.seekTo(player.state.value.positionMs + SEEK_STEP_MS) } }
            RemoteKey.VolumeUp -> { { player.setVolume(player.state.value.volume + VOLUME_STEP) } }
            RemoteKey.VolumeDown -> { { player.setVolume(player.state.value.volume - VOLUME_STEP) } }
            else -> null
        }
        action ?: return false
        mainScope.launch { action() }
        return true
    }

    private fun dispatchToFilm(key: RemoteKey, film: RemoteFilmControls): Boolean {
        val action: (() -> Unit)? = when (key) {
            RemoteKey.PlayPause -> { { if (RemotePlayerHub.film.value?.isPlaying == true) film.pause() else film.play() } }
            RemoteKey.SeekBack -> { { film.seekBy(-SEEK_STEP_MS) } }
            RemoteKey.SeekForward -> { { film.seekBy(SEEK_STEP_MS) } }
            else -> null
        }
        action ?: return false
        mainScope.launch { action() }
        return true
    }

    private const val SEEK_STEP_MS = 10_000L
    private const val SOURCES_THROTTLE_MS = 300L
    private const val VOLUME_STEP = 5

    private fun computerName(): String =
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Nuvio"

    /**
     * fork:phone-remote wake: the network cards behind this PC's home-network addresses, and those networks' broadcast
     * addresses, so a phone can send a Wake-on-LAN packet to it while it sleeps.
     */
    private fun wakeInfo(): String = runCatching {
        val cards = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filter { card -> card.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress } }
        val macs = cards.mapNotNull { card ->
            card.hardwareAddress?.takeIf { it.size == 6 }?.joinToString("") { "%02X".format(it) }
        }.distinct()
        val broadcasts = cards.flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
            .mapNotNull { it.broadcast?.hostAddress }
            .distinct()
        PhoneRemoteProtocol.wakeLine(macs, broadcasts)
    }.getOrDefault(PhoneRemoteProtocol.wakeLine(emptyList(), emptyList()))

    /** The PC's home-network addresses, for typing into the phone when discovery is blocked. */
    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())
}
