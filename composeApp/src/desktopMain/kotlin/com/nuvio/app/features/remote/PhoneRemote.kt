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

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _state.update {
            it.copy(
                enabled = store.getBoolean(KEY_ENABLED) ?: false,
                pin = store.getString(KEY_PIN).orEmpty(),
                pairedDevices = pairedEntries().map { entry -> entry.second }.sorted(),
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
        server = created
        _state.update { it.copy(listening = created.port != null, port = created.port, addresses = localAddresses()) }
    }

    private fun closeServer() {
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
            RemoteKey.Audio -> player(PlayerShortcutAction.NextAudio)
            // The player pins the up/down arrows to volume; outside it they would move the focus,
            // which is not what a volume button should do, so they are dropped there.
            RemoteKey.VolumeUp, RemoteKey.VolumeDown -> null
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
        val inPlayer = GamepadContext.playerActive
        val sent = keyCodeFor(key, inPlayer)?.let(GamepadKeyInjector::sendKeyCode)
            ?: targetFor(key, inPlayer)?.let(GamepadKeyInjector::send)
            ?: false
        if (!sent) log.d { "Phone remote: ${key.wire} did nothing (unbound, or Nuvio is not the focused window)" }
    }

    private fun computerName(): String =
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Nuvio"

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
