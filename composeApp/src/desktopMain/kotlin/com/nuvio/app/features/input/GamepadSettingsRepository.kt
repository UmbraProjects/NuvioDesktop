package com.nuvio.app.features.input

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted gamepad tunables. Modelled on `PlayerShortcutsRepository`: profile-scoped keys, a
 * defaults map that an untouched install matches exactly, and a `@Volatile` mirror because the
 * poll thread reads these on every tick while the UI writes them.
 *
 * Bindings themselves are not stored yet — [GamepadDefaults] is the whole layout. When a rebinding
 * UI lands it belongs here, keyed by [GamepadButton.id] the way shortcuts are keyed by action id.
 */
object GamepadSettingsRepository {
    /** Off by default: a pad plugged in for a game should not start driving the UI unasked. */
    const val DEFAULT_ENABLED = false

    /**
     * Fraction of full stick deflection ignored as noise. Xbox pads rest well inside 0.20 even
     * when worn; below about 0.12 a drifting stick scrolls the UI on its own.
     */
    const val DEFAULT_DEAD_ZONE = 0.30f
    const val MIN_DEAD_ZONE = 0.10f
    const val MAX_DEAD_ZONE = 0.60f

    /** Milliseconds a direction must be held before it starts repeating, and the repeat gap. */
    const val DEFAULT_REPEAT_DELAY_MS = 400
    const val DEFAULT_REPEAT_INTERVAL_MS = 110
    const val MIN_REPEAT_INTERVAL_MS = 60
    const val MAX_REPEAT_INTERVAL_MS = 250

    private const val KEY_ENABLED = "gamepad_enabled"
    private const val KEY_DEAD_ZONE = "gamepad_dead_zone_pct"
    private const val KEY_REPEAT_INTERVAL = "gamepad_repeat_interval_ms"

    private val store = DesktopStorage.store("nuvio_gamepad")

    private var hasLoaded = false

    // Read from the poll thread on every tick, written from the UI thread.
    @Volatile
    var enabled: Boolean = DEFAULT_ENABLED
        private set

    @Volatile
    var deadZone: Float = DEFAULT_DEAD_ZONE
        private set

    @Volatile
    var repeatIntervalMs: Int = DEFAULT_REPEAT_INTERVAL_MS
        private set

    private val _enabledState = MutableStateFlow(DEFAULT_ENABLED)
    val enabledState: StateFlow<Boolean> = _enabledState.asStateFlow()

    private val _deadZoneState = MutableStateFlow(DEFAULT_DEAD_ZONE)
    val deadZoneState: StateFlow<Float> = _deadZoneState.asStateFlow()

    private val _repeatIntervalState = MutableStateFlow(DEFAULT_REPEAT_INTERVAL_MS)
    val repeatIntervalState: StateFlow<Int> = _repeatIntervalState.asStateFlow()

    /** True while a pad is actually answering XInput, for the settings page's status line. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    private fun loadFromDisk() {
        hasLoaded = true
        enabled = store.getBoolean(scoped(KEY_ENABLED)) ?: DEFAULT_ENABLED
        // Stored as whole percent so the settings file stays readable and diffable.
        deadZone = (store.getInt(scoped(KEY_DEAD_ZONE))?.let { it / 100f } ?: DEFAULT_DEAD_ZONE)
            .coerceIn(MIN_DEAD_ZONE, MAX_DEAD_ZONE)
        repeatIntervalMs = (store.getInt(scoped(KEY_REPEAT_INTERVAL)) ?: DEFAULT_REPEAT_INTERVAL_MS)
            .coerceIn(MIN_REPEAT_INTERVAL_MS, MAX_REPEAT_INTERVAL_MS)
        publish()
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (enabled == value) return
        enabled = value
        if (value == DEFAULT_ENABLED) store.remove(scoped(KEY_ENABLED)) else store.putBoolean(scoped(KEY_ENABLED), value)
        publish()
        GamepadInput.onEnabledChanged(value)
    }

    fun setDeadZone(value: Float) {
        ensureLoaded()
        val clamped = value.coerceIn(MIN_DEAD_ZONE, MAX_DEAD_ZONE)
        if (deadZone == clamped) return
        deadZone = clamped
        store.putInt(scoped(KEY_DEAD_ZONE), (clamped * 100f).toInt())
        publish()
    }

    fun setRepeatIntervalMs(value: Int) {
        ensureLoaded()
        val clamped = value.coerceIn(MIN_REPEAT_INTERVAL_MS, MAX_REPEAT_INTERVAL_MS)
        if (repeatIntervalMs == clamped) return
        repeatIntervalMs = clamped
        store.putInt(scoped(KEY_REPEAT_INTERVAL), clamped)
        publish()
    }

    fun resetAll() {
        ensureLoaded()
        listOf(KEY_ENABLED, KEY_DEAD_ZONE, KEY_REPEAT_INTERVAL).forEach { store.remove(scoped(it)) }
        loadFromDisk()
        GamepadInput.onEnabledChanged(enabled)
    }

    internal fun setConnected(value: Boolean) {
        _connected.value = value
    }

    private fun publish() {
        _enabledState.value = enabled
        _deadZoneState.value = deadZone
        _repeatIntervalState.value = repeatIntervalMs
    }

    private fun scoped(key: String): String = ProfileScopedKey.of(key)
}
