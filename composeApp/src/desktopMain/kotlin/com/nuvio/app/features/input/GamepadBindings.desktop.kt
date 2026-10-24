package com.nuvio.app.features.input

import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlinx.coroutines.flow.StateFlow

actual fun gamepadSupported(): Boolean = DesktopHostOs.current == DesktopHostOs.WINDOWS

actual fun ensureGamepadSettingsLoaded() = GamepadSettingsRepository.ensureLoaded()

actual fun gamepadEnabledState(): StateFlow<Boolean> = GamepadSettingsRepository.enabledState
actual fun setGamepadEnabled(enabled: Boolean) = GamepadSettingsRepository.setEnabled(enabled)

actual fun gamepadConnectedState(): StateFlow<Boolean> = GamepadSettingsRepository.connected

actual fun gamepadDeadZoneState(): StateFlow<Float> = GamepadSettingsRepository.deadZoneState
actual fun setGamepadDeadZone(value: Float) = GamepadSettingsRepository.setDeadZone(value)
actual fun gamepadDeadZoneRange(): ClosedFloatingPointRange<Float> =
    GamepadSettingsRepository.MIN_DEAD_ZONE..GamepadSettingsRepository.MAX_DEAD_ZONE

actual fun gamepadRepeatIntervalState(): StateFlow<Int> = GamepadSettingsRepository.repeatIntervalState
actual fun setGamepadRepeatIntervalMs(value: Int) = GamepadSettingsRepository.setRepeatIntervalMs(value)
actual fun gamepadRepeatIntervalRange(): IntRange =
    GamepadSettingsRepository.MIN_REPEAT_INTERVAL_MS..GamepadSettingsRepository.MAX_REPEAT_INTERVAL_MS

actual fun resetGamepadSettings() = GamepadSettingsRepository.resetAll()

actual fun gamepadMappingRows(): List<GamepadMappingRow> = GamepadButton.entries.map { button ->
    val binding = GamepadDefaults.binding(button)
    GamepadMappingRow(
        button = button,
        browsingLabel = describe(binding.browsing),
        playerLabel = describe(binding.player),
    )
}

/**
 * What a target does, in words. Deliberately no key names: the pad presses whatever key the action
 * is bound to, and those bindings are already listed in full in the keyboard sections above this
 * one — repeating them on every row was noise.
 */
private fun describe(target: GamepadTarget): String = when (target) {
    is GamepadTarget.FixedKey -> target.description
    is GamepadTarget.AppAction -> target.action.displayName
    is GamepadTarget.PlayerAction -> target.action.displayName
    is GamepadTarget.ShaderOrHdr -> "Cycle shader, or HDR mode when no shader is running"
    GamepadTarget.Unbound -> "Not bound"
}
