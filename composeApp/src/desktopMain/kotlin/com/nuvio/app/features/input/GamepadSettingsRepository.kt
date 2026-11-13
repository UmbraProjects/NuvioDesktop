package com.nuvio.app.features.input

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.panelring.PanelRingSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted gamepad tunables. Modelled on `PlayerShortcutsRepository`: profile-scoped keys, a
 * defaults map that an untouched install matches exactly, and a `@Volatile` mirror because the
 * poll thread reads these on every tick while the UI writes them.
 *
 * Rebound buttons are stored here too, one key per button ([GamepadButton.id]) holding both of its
 * meanings (see [GamepadTargetCodec]); a button never rebound has no key and follows
 * [GamepadDefaults]. So are the panel ring's settings: which button opens it, its size, and
 * whether the middle mouse button opens it.
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

    /**
     * Off by default: with it on, every click into a text field raises the panel, which a desk user
     * with a real keyboard would find in the way. It is for the couch, with only a mouse to hand.
     */
    const val DEFAULT_MOUSE_KEYBOARD = false

    private const val KEY_ENABLED = "gamepad_enabled"
    private const val KEY_DEAD_ZONE = "gamepad_dead_zone_pct"
    private const val KEY_REPEAT_INTERVAL = "gamepad_repeat_interval_ms"
    private const val KEY_MOUSE_KEYBOARD = "osk_mouse_enabled"
    private const val KEY_BINDING_PREFIX = "gamepad_bind_"
    private const val KEY_RING_BUTTON = "panel_ring_pad_button"
    private const val KEY_RING_SIZE = "panel_ring_size"
    private const val KEY_RING_MOUSE = "panel_ring_middle_mouse"

    /** Hold this to open the panel ring; a tap still does the button's own action. */
    val DEFAULT_RING_BUTTON: GamepadButton? = GamepadButton.LeftShoulder
    const val RING_BUTTON_OFF = "off"

    /**
     * Buttons that can open the ring. Not View: holding it is push-to-talk. Not the D-pad, sticks'
     * directions or A/B: they move and select inside the ring.
     */
    val RING_BUTTON_CHOICES: List<GamepadButton> = listOf(
        GamepadButton.LeftShoulder,
        GamepadButton.RightShoulder,
        GamepadButton.LeftTrigger,
        GamepadButton.RightTrigger,
        GamepadButton.LeftThumb,
        GamepadButton.RightThumb,
        GamepadButton.X,
        GamepadButton.Y,
        GamepadButton.Start,
    )
    const val DEFAULT_RING_MOUSE = true

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

    /**
     * Not read by the poll thread, but kept with the pad settings because it is the same panel —
     * and deliberately independent of [enabled], since the point is that no controller is needed.
     */
    private val _mouseKeyboardState = MutableStateFlow(DEFAULT_MOUSE_KEYBOARD)
    val mouseKeyboardState: StateFlow<Boolean> = _mouseKeyboardState.asStateFlow()

    private val _enabledState = MutableStateFlow(DEFAULT_ENABLED)
    val enabledState: StateFlow<Boolean> = _enabledState.asStateFlow()

    private val _deadZoneState = MutableStateFlow(DEFAULT_DEAD_ZONE)
    val deadZoneState: StateFlow<Float> = _deadZoneState.asStateFlow()

    private val _repeatIntervalState = MutableStateFlow(DEFAULT_REPEAT_INTERVAL_MS)
    val repeatIntervalState: StateFlow<Int> = _repeatIntervalState.asStateFlow()

    /** Buttons the user has rebound; read by the poll thread on every press. */
    @Volatile
    private var overrides: Map<GamepadButton, GamepadBinding> = emptyMap()

    @Volatile
    var ringButton: GamepadButton? = DEFAULT_RING_BUTTON
        private set

    private val _ringButtonState = MutableStateFlow(DEFAULT_RING_BUTTON)
    val ringButtonState: StateFlow<GamepadButton?> = _ringButtonState.asStateFlow()

    private val _ringSizeState = MutableStateFlow(PanelRingSize.Medium)
    val ringSizeState: StateFlow<PanelRingSize> = _ringSizeState.asStateFlow()

    private val _ringMouseState = MutableStateFlow(DEFAULT_RING_MOUSE)
    val ringMouseState: StateFlow<Boolean> = _ringMouseState.asStateFlow()

    /** Bumped on every rebind, so the settings list redraws. */
    private val _bindingsVersion = MutableStateFlow(0)
    val bindingsVersion: StateFlow<Int> = _bindingsVersion.asStateFlow()

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
        _mouseKeyboardState.value = store.getBoolean(scoped(KEY_MOUSE_KEYBOARD)) ?: DEFAULT_MOUSE_KEYBOARD
        overrides = GamepadButton.entries.mapNotNull { button ->
            val fallback = GamepadDefaults.binding(button)
            GamepadTargetCodec.decodeBinding(store.getString(scoped(bindingKey(button))), fallback)
                ?.let { button to it }
        }.toMap()
        ringButton = decodeRingButton(store.getString(scoped(KEY_RING_BUTTON)))
        _ringSizeState.value = PanelRingSize.fromKey(store.getString(scoped(KEY_RING_SIZE)))
        _ringMouseState.value = store.getBoolean(scoped(KEY_RING_MOUSE)) ?: DEFAULT_RING_MOUSE
        publish()
    }

    /** What [button] does now: the user's rebind if there is one, else the default layout. */
    fun binding(button: GamepadButton): GamepadBinding {
        ensureLoaded()
        return overrides[button] ?: GamepadDefaults.binding(button)
    }

    fun isRebound(button: GamepadButton): Boolean {
        ensureLoaded()
        return button in overrides
    }

    /** Sets one of [button]'s two meanings; the other keeps whatever it has now. */
    fun setTarget(button: GamepadButton, inPlayer: Boolean, target: GamepadTarget) {
        val current = binding(button)
        val next = if (inPlayer) current.copy(player = target) else current.copy(browsing = target)
        setBinding(button, next)
    }

    fun setBinding(button: GamepadButton, binding: GamepadBinding) {
        ensureLoaded()
        val encoded = GamepadTargetCodec.encodeBinding(binding)
        if (binding == GamepadDefaults.binding(button) || encoded == null) {
            overrides = overrides - button
            store.remove(scoped(bindingKey(button)))
        } else {
            overrides = overrides + (button to binding)
            store.putString(scoped(bindingKey(button)), encoded)
        }
        _bindingsVersion.value++
    }

    fun resetButton(button: GamepadButton) = setBinding(button, GamepadDefaults.binding(button))

    fun resetAllButtons() {
        ensureLoaded()
        GamepadButton.entries.forEach { store.remove(scoped(bindingKey(it))) }
        overrides = emptyMap()
        _bindingsVersion.value++
    }

    fun setRingButton(button: GamepadButton?) {
        ensureLoaded()
        val value = button?.takeIf { it in RING_BUTTON_CHOICES }
        ringButton = value
        if (value == DEFAULT_RING_BUTTON) {
            store.remove(scoped(KEY_RING_BUTTON))
        } else {
            store.putString(scoped(KEY_RING_BUTTON), value?.id ?: RING_BUTTON_OFF)
        }
        _ringButtonState.value = value
    }

    fun setRingSize(size: PanelRingSize) {
        ensureLoaded()
        if (size == PanelRingSize.Medium) store.remove(scoped(KEY_RING_SIZE)) else store.putString(scoped(KEY_RING_SIZE), size.key)
        _ringSizeState.value = size
    }

    fun setRingMouse(enabled: Boolean) {
        ensureLoaded()
        if (enabled == DEFAULT_RING_MOUSE) store.remove(scoped(KEY_RING_MOUSE)) else store.putBoolean(scoped(KEY_RING_MOUSE), enabled)
        _ringMouseState.value = enabled
    }

    /** Never set: the default. "off": no button. Anything else: that button, if it may open the ring. */
    internal fun decodeRingButton(stored: String?): GamepadButton? = when (stored) {
        null -> DEFAULT_RING_BUTTON
        RING_BUTTON_OFF -> null
        else -> GamepadButton.fromId(stored)?.takeIf { it in RING_BUTTON_CHOICES } ?: DEFAULT_RING_BUTTON
    }

    private fun bindingKey(button: GamepadButton) = KEY_BINDING_PREFIX + button.id

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

    fun setMouseKeyboard(value: Boolean) {
        ensureLoaded()
        if (_mouseKeyboardState.value == value) return
        _mouseKeyboardState.value = value
        if (value == DEFAULT_MOUSE_KEYBOARD) {
            store.remove(scoped(KEY_MOUSE_KEYBOARD))
        } else {
            store.putBoolean(scoped(KEY_MOUSE_KEYBOARD), value)
        }
    }

    /** Controller tunables only — the mouse keyboard has its own section and is not a pad setting. */
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
        _ringButtonState.value = ringButton
        _bindingsVersion.value++
        _enabledState.value = enabled
        _deadZoneState.value = deadZone
        _repeatIntervalState.value = repeatIntervalMs
    }

    private fun scoped(key: String): String = ProfileScopedKey.of(key)
}
