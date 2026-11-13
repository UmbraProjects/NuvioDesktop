package com.nuvio.app.features.input

import kotlinx.coroutines.flow.StateFlow

/**
 * Bridge exposing the desktop gamepad layer to shared settings code, shaped like
 * `PlayerShortcutBindings` next door. Only a desktop actual exists (the sole KMP target), and even
 * there the whole feature is inert off Windows — see [gamepadSupported].
 */

/** False where XInput does not exist; the settings section hides itself entirely. */
expect fun gamepadSupported(): Boolean

expect fun ensureGamepadSettingsLoaded()

/** The pad settings are per profile: reload them for the profile just switched to (see ProfileRepository.selectProfile). */
expect fun gamepadSettingsProfileChanged()

expect fun gamepadEnabledState(): StateFlow<Boolean>
expect fun setGamepadEnabled(enabled: Boolean)

/** True while a pad is answering XInput, so the settings row can say so instead of guessing. */
expect fun gamepadConnectedState(): StateFlow<Boolean>

expect fun gamepadDeadZoneState(): StateFlow<Float>
expect fun setGamepadDeadZone(value: Float)
expect fun gamepadDeadZoneRange(): ClosedFloatingPointRange<Float>

expect fun gamepadRepeatIntervalState(): StateFlow<Int>
expect fun setGamepadRepeatIntervalMs(value: Int)
expect fun gamepadRepeatIntervalRange(): IntRange

expect fun resetGamepadSettings()

/** Raise the on-screen keyboard whenever a text field is focused, so it can be typed on by mouse. */
expect fun mouseKeyboardState(): StateFlow<Boolean>
expect fun setMouseKeyboard(enabled: Boolean)

/**
 * One row of the mapping inventory: what a button does while browsing, and what the same button
 * does during playback.
 */
data class GamepadMappingRow(
    val button: GamepadButton,
    val browsingLabel: String,
    val playerLabel: String,
    /** True when the user changed this button from the default layout. */
    val rebound: Boolean = false,
)

/** The current layout, rebinds included. Re-read it whenever [gamepadBindingsVersion] changes. */
expect fun gamepadMappingRows(): List<GamepadMappingRow>

/** Changes on every rebind or reset. */
expect fun gamepadBindingsVersion(): StateFlow<Int>

/** One thing a button can be set to, for the rebind picker. [id] is what is stored. */
data class GamepadTargetChoice(val id: String, val label: String)

/** Everything a button can do while browsing ([inPlayer] false) or during playback (true). */
expect fun gamepadTargetChoices(inPlayer: Boolean): List<GamepadTargetChoice>

/** The ids of what [button] does now, browsing first, then in the player. */
expect fun gamepadButtonTargetIds(button: GamepadButton): Pair<String, String>

expect fun setGamepadButtonTarget(button: GamepadButton, inPlayer: Boolean, id: String)
expect fun resetGamepadButton(button: GamepadButton)
expect fun resetGamepadButtons()

/** The button held to open the panel ring, or null for none. */
expect fun panelRingPadButtonState(): StateFlow<GamepadButton?>
expect fun setPanelRingPadButton(button: GamepadButton?)
expect fun panelRingPadButtonChoices(): List<GamepadButton>

expect fun panelRingSizeState(): StateFlow<com.nuvio.app.features.panelring.PanelRingSize>
expect fun setPanelRingSize(size: com.nuvio.app.features.panelring.PanelRingSize)

/** Hold the middle mouse button to open the ring. */
expect fun panelRingMouseState(): StateFlow<Boolean>
expect fun setPanelRingMouse(enabled: Boolean)
