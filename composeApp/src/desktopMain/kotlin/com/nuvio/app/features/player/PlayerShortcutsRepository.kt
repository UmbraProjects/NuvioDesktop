package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.ui.WasdNavigation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.event.KeyEvent

/**
 * Desktop store for user-rebound player shortcuts. Only the rebindable actions in
 * [PlayerShortcutAction] are persisted; fixed keys (arrows, K, Tab, panel nav) live in the
 * dispatcher. Defaults reproduce the historical hardcoded bindings exactly, so an untouched
 * install behaves identically — and because no override can be written until the rebinding UI
 * ships, [bindings] equals [defaults] until then.
 *
 * Bindings are single-key (no modifiers), matching the dispatcher which ignores Ctrl/Alt/Meta,
 * and use `java.awt` `VK_` key codes so the dispatcher can look them up without translation.
 */
object PlayerShortcutsRepository {
    /** Default key code (java.awt VK_ constant) for each rebindable action. */
    private val defaults: Map<PlayerShortcutAction, Int> = mapOf(
        PlayerShortcutAction.PlayPause to KeyEvent.VK_SPACE,
        PlayerShortcutAction.AlternatePlayPause to KeyEvent.VK_K,
        PlayerShortcutAction.ToggleMute to KeyEvent.VK_M,
        PlayerShortcutAction.SeekBackward to KeyEvent.VK_J,
        PlayerShortcutAction.SeekForward to KeyEvent.VK_L,
        PlayerShortcutAction.SpeedUp to KeyEvent.VK_CLOSE_BRACKET,
        PlayerShortcutAction.SpeedDown to KeyEvent.VK_OPEN_BRACKET,
        PlayerShortcutAction.ToggleSpeed to KeyEvent.VK_R,
        PlayerShortcutAction.NextSubtitle to KeyEvent.VK_S,
        PlayerShortcutAction.NextAudio to KeyEvent.VK_A,
        PlayerShortcutAction.OpenSources to KeyEvent.VK_O,
        PlayerShortcutAction.OpenEpisodes to KeyEvent.VK_E,
        PlayerShortcutAction.CycleZoom to KeyEvent.VK_C,
        PlayerShortcutAction.SkipInterval to KeyEvent.VK_TAB,
        PlayerShortcutAction.CycleSvp to KeyEvent.VK_F7,
        PlayerShortcutAction.CycleHdr to KeyEvent.VK_F8,
        PlayerShortcutAction.CycleColorProfile to KeyEvent.VK_F9,
        PlayerShortcutAction.CycleAnime to KeyEvent.VK_F10,
        // "I" mirrors mpv's own stats-overlay convention (and Stremio Kai's binding).
        PlayerShortcutAction.ToggleMpvDiagnostics to KeyEvent.VK_I,
    )

    private val store = DesktopStorage.store("nuvio_player_shortcuts")

    // Read from the AWT event-dispatch thread on every keypress; written from the UI thread.
    @Volatile
    private var bindings: Map<PlayerShortcutAction, Int> = defaults
    private var hasLoaded = false

    private val _uiState = MutableStateFlow(defaults)
    val uiState: StateFlow<Map<PlayerShortcutAction, Int>> = _uiState.asStateFlow()

    // Display labels ("Space", "F10", "←") for the shared settings reference page.
    private val _keyLabels = MutableStateFlow(defaults.mapValues { playerShortcutKeyCodeLabel(it.value) })
    val keyLabels: StateFlow<Map<PlayerShortcutAction, String>> = _keyLabels.asStateFlow()

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    /** Must be called on profile switch once this repo is reachable from the profile plumbing. */
    fun onProfileChanged() = loadFromDisk()

    private fun loadFromDisk() {
        hasLoaded = true
        bindings = PlayerShortcutAction.entries.associateWith { action ->
            store.getInt(scoped(action.id)) ?: defaults.getValue(action)
        }
        publish()
    }

    fun defaultKeyCode(action: PlayerShortcutAction): Int = defaults.getValue(action)

    /** Current key code bound to [action]. */
    fun keyCode(action: PlayerShortcutAction): Int = bindings[action] ?: defaults.getValue(action)

    /** The rebindable action currently bound to [keyCode], or null if none. */
    fun actionForKeyCode(keyCode: Int): PlayerShortcutAction? =
        bindings.entries.firstOrNull { it.value == keyCode }?.key

    fun setBinding(action: PlayerShortcutAction, keyCode: Int) {
        ensureLoaded()
        if (bindings[action] == keyCode) return
        bindings = bindings.toMutableMap().apply { put(action, keyCode) }
        if (keyCode == defaults.getValue(action)) {
            store.remove(scoped(action.id))
        } else {
            store.putInt(scoped(action.id), keyCode)
        }
        publish()
    }

    fun resetToDefault(action: PlayerShortcutAction) = setBinding(action, defaults.getValue(action))

    fun resetAll() {
        ensureLoaded()
        bindings = defaults
        PlayerShortcutAction.entries.forEach { store.remove(scoped(it.id)) }
        publish()
    }

    private fun publish() {
        _uiState.value = bindings
        _keyLabels.value = bindings.mapValues { playerShortcutKeyCodeLabel(it.value) }
    }

    private fun scoped(key: String): String = ProfileScopedKey.of(key)
}

object AppShortcutsRepository {
    private val defaults = mapOf(
        AppShortcutAction.GoHome to KeyEvent.VK_H,
        AppShortcutAction.OpenSearch to KeyEvent.VK_S,
        AppShortcutAction.OpenLibrary to KeyEvent.VK_L,
        AppShortcutAction.OpenCalendar to KeyEvent.VK_C,
        AppShortcutAction.ToggleTrailer to KeyEvent.VK_T,
        AppShortcutAction.ToggleTrailerMute to KeyEvent.VK_M,
        AppShortcutAction.TogglePeoplePanel to KeyEvent.VK_P,
        AppShortcutAction.ToggleFullscreen to KeyEvent.VK_F11,
        AppShortcutAction.SelectFocused to KeyEvent.VK_ENTER,
        AppShortcutAction.GoBack to KeyEvent.VK_BACK_SPACE,
        AppShortcutAction.DismissOverlay to KeyEvent.VK_ESCAPE,
    )
    private val store = DesktopStorage.store("nuvio_app_shortcuts")
    @Volatile private var bindings: Map<AppShortcutAction, Int> = defaults
    @Volatile private var overriddenActions: Set<AppShortcutAction> = emptySet()
    private var hasLoaded = false
    private val _keyLabels = MutableStateFlow(defaults.mapValues { playerShortcutKeyCodeLabel(it.value) })
    val keyLabels: StateFlow<Map<AppShortcutAction, String>> = _keyLabels.asStateFlow()

    fun ensureLoaded() { if (!hasLoaded) loadFromDisk() else publish() }
    fun onProfileChanged() = loadFromDisk()
    private fun loadFromDisk() {
        hasLoaded = true
        val stored = AppShortcutAction.entries.associateWith { action ->
            store.getInt(ProfileScopedKey.of(action.id))
        }
        overriddenActions = stored.filterValues { it != null }.keys
        bindings = stored.mapValues { (action, keyCode) -> keyCode ?: defaults.getValue(action) }
        publish()
    }
    fun keyCode(action: AppShortcutAction): Int {
        if (action == AppShortcutAction.OpenSearch && action !in overriddenActions && WasdNavigation.enabled) {
            return KeyEvent.VK_Q
        }
        return bindings[action] ?: defaults.getValue(action)
    }
    fun actionForKeyCode(keyCode: Int): AppShortcutAction? =
        AppShortcutAction.entries.firstOrNull { keyCode(it) == keyCode }
    fun setBinding(action: AppShortcutAction, keyCode: Int) {
        ensureLoaded()
        bindings = bindings.toMutableMap().apply { put(action, keyCode) }
        if (keyCode == defaults.getValue(action)) {
            overriddenActions = overriddenActions - action
            store.remove(ProfileScopedKey.of(action.id))
        } else {
            overriddenActions = overriddenActions + action
            store.putInt(ProfileScopedKey.of(action.id), keyCode)
        }
        publish()
    }
    fun resetToDefault(action: AppShortcutAction) = setBinding(action, defaults.getValue(action))
    fun resetAll() {
        bindings = defaults
        overriddenActions = emptySet()
        AppShortcutAction.entries.forEach { store.remove(ProfileScopedKey.of(it.id)) }
        publish()
    }
    private fun publish() {
        _keyLabels.value = AppShortcutAction.entries.associateWith { playerShortcutKeyCodeLabel(keyCode(it)) }
    }
}
