package com.spydermahn.nuvioremote

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
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

data class SavedPc(val name: String, val host: String, val port: Int, val token: String) {
    val address get() = "$host:$port"
}

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
)

/**
 * Everything that touches the network runs on [io], one thread, so presses reach the PC in the
 * order they were made and the session is never used from two threads at once.
 */
class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("nuvio_remote", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nuvio-remote-io") }

    private val _state = MutableStateFlow(UiState(saved = loadSaved()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var session: RemoteSession? = null
    private var keepAlive: Job? = null
    private var foreground = false

    private val deviceName: String =
        Settings.Global.getString(application.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL ?: "Android phone"

    init {
        val last = prefs.getString(KEY_LAST, null)
        val lastPc = _state.value.saved.firstOrNull { it.address == last }
        if (lastPc != null) {
            _state.update { it.copy(screen = Screen.Remote, current = lastPc, link = Link.Connecting) }
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
        if (prefs.getString(KEY_LAST, null) == pc.address) prefs.edit().remove(KEY_LAST).apply()
    }

    fun showPicker() {
        keepAlive?.cancel()
        io.execute { closeSession() }
        prefs.edit().remove(KEY_LAST).apply()
        _state.update { it.copy(screen = Screen.Pick, link = Link.Offline, message = null, busy = false) }
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
            // A fresh session for every attempt: the PC drops a connection that sits idle for 20
            // seconds, which is less time than it can take to walk over and read the code.
            closeSession()
            val result = try {
                val opened = RemoteSession.open(target.host, target.port)
                session = opened
                when (val hello = opened.hello(null, deviceName)) {
                    HelloResult.PinRequired -> opened.pair(pin)
                    is HelloResult.Welcome -> PairResult.Failed("Unexpected reply from the PC.")
                    is HelloResult.Refused -> PairResult.Failed(hello.reason)
                }
            } catch (_: IOException) {
                PairResult.Failed("Couldn't reach ${target.name}. Check the PC is on and on the same Wi-Fi.")
            }
            when (result) {
                is PairResult.Paired -> onConnected(SavedPc(result.pcName, target.host, target.port, result.token))
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
    }

    fun cancelPairing() = showPicker()

    // --- The remote ---

    fun press(key: RemoteKey) = sendOrReconnect { it.key(key) }

    fun type(text: String) = sendOrReconnect { it.text(text) }

    fun backspace(count: Int) = sendOrReconnect { session -> repeat(count) { session.key(RemoteKey.Backspace) } }

    fun reconnect() {
        val pc = _state.value.current ?: return
        if (!foreground) return
        _state.update { it.copy(link = Link.Connecting) }
        io.execute {
            if (session?.isOpen == true && session?.ping() == true) {
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
        saveAll(_state.value.saved.filterNot { it.address == pc.address } + pc)
        prefs.edit().putString(KEY_LAST, pc.address).apply()
        _state.update { it.copy(busy = false, message = null, screen = Screen.Remote, current = pc, link = Link.Connected) }
        startKeepAlive()
    }

    private fun fail(reason: String) {
        closeSession()
        _state.update { it.copy(busy = false, message = reason) }
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    /** A ping every few seconds, so a PC that went to sleep shows as offline before the next press. */
    private fun startKeepAlive() {
        keepAlive?.cancel()
        keepAlive = viewModelScope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                io.execute {
                    val alive = session?.ping() == true
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

    // --- Storage: "host|port|token|name" per paired PC. ---

    private fun loadSaved(): List<SavedPc> = prefs.getStringSet(KEY_SAVED, emptySet()).orEmpty().mapNotNull { entry ->
        val parts = entry.split('|', limit = 4)
        if (parts.size < 4) return@mapNotNull null
        SavedPc(RemoteProtocol.decode(parts[3]), parts[0], parts[1].toIntOrNull() ?: return@mapNotNull null, parts[2])
    }.sortedBy { it.name.lowercase() }

    private fun saveAll(list: List<SavedPc>) {
        prefs.edit().putStringSet(KEY_SAVED, list.map { "${it.host}|${it.port}|${it.token}|${RemoteProtocol.encode(it.name)}" }.toSet()).apply()
        _state.update { it.copy(saved = list.sortedBy { pc -> pc.name.lowercase() }) }
    }

    private companion object {
        const val KEY_SAVED = "saved_pcs"
        const val KEY_LAST = "last_pc"
        const val PING_INTERVAL_MS = 5_000L
        const val RETRY_MS = 3_000L
    }
}
