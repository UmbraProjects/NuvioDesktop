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

/**
 * One row of the mapping inventory: what a button does while browsing, and what the same button
 * does during playback.
 */
data class GamepadMappingRow(
    val button: GamepadButton,
    val browsingLabel: String,
    val playerLabel: String,
)

/** The current layout. Read-only — per-button rebinding is not part of this phase. */
expect fun gamepadMappingRows(): List<GamepadMappingRow>
