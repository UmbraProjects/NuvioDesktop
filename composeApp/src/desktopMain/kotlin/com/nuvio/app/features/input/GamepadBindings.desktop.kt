package com.nuvio.app.features.input

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.core.i18n.localizedString
import com.nuvio.app.features.panelring.PanelRingSize
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.*

actual fun gamepadSupported(): Boolean = DesktopHostOs.current == DesktopHostOs.WINDOWS

actual fun ensureGamepadSettingsLoaded() = GamepadSettingsRepository.ensureLoaded()

actual fun gamepadSettingsProfileChanged() = GamepadSettingsRepository.onProfileChanged()

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

actual fun mouseKeyboardState(): StateFlow<Boolean> = GamepadSettingsRepository.mouseKeyboardState
actual fun setMouseKeyboard(enabled: Boolean) = GamepadSettingsRepository.setMouseKeyboard(enabled)

actual fun gamepadMappingRows(): List<GamepadMappingRow> = GamepadButton.entries.map { button ->
    val binding = GamepadSettingsRepository.binding(button)
    GamepadMappingRow(
        button = button,
        browsingLabel = describe(binding.browsing),
        playerLabel = describe(binding.player),
        rebound = GamepadSettingsRepository.isRebound(button),
    )
}

actual fun gamepadBindingsVersion(): StateFlow<Int> = GamepadSettingsRepository.bindingsVersion

actual fun gamepadTargetChoices(inPlayer: Boolean): List<GamepadTargetChoice> =
    GamepadTargetCodec.options(inPlayer).mapNotNull { target ->
        GamepadTargetCodec.encode(target)?.let { GamepadTargetChoice(it, describe(target)) }
    }

actual fun gamepadButtonTargetIds(button: GamepadButton): Pair<String, String> {
    val binding = GamepadSettingsRepository.binding(button)
    return (GamepadTargetCodec.encode(binding.browsing) ?: "") to (GamepadTargetCodec.encode(binding.player) ?: "")
}

actual fun setGamepadButtonTarget(button: GamepadButton, inPlayer: Boolean, id: String) {
    val target = GamepadTargetCodec.decode(id) ?: return
    GamepadSettingsRepository.setTarget(button, inPlayer, target)
}

actual fun resetGamepadButton(button: GamepadButton) = GamepadSettingsRepository.resetButton(button)
actual fun resetGamepadButtons() = GamepadSettingsRepository.resetAllButtons()

actual fun panelRingPadButtonState(): StateFlow<GamepadButton?> = GamepadSettingsRepository.ringButtonState
actual fun setPanelRingPadButton(button: GamepadButton?) = GamepadSettingsRepository.setRingButton(button)
actual fun panelRingPadButtonChoices(): List<GamepadButton> = GamepadSettingsRepository.RING_BUTTON_CHOICES

actual fun panelRingSizeState(): StateFlow<PanelRingSize> = GamepadSettingsRepository.ringSizeState
actual fun setPanelRingSize(size: PanelRingSize) = GamepadSettingsRepository.setRingSize(size)

actual fun panelRingMouseState(): StateFlow<Boolean> = GamepadSettingsRepository.ringMouseState
actual fun setPanelRingMouse(enabled: Boolean) = GamepadSettingsRepository.setRingMouse(enabled)

/**
 * What a target does, in words. Deliberately no key names: the pad presses whatever key the action
 * is bound to, and those bindings are already listed in full in the keyboard sections above this
 * one — repeating them on every row was noise.
 */
private fun describe(target: GamepadTarget): String = when (target) {
    is GamepadTarget.FixedKey -> localizedString(target.description)
    is GamepadTarget.AppAction -> localizedString(target.action.displayNameRes)
    is GamepadTarget.PlayerAction -> localizedString(target.action.displayNameRes)
    is GamepadTarget.ShaderOrHdr -> localizedString(Res.string.gamepad_target_shader_or_hdr)
    GamepadTarget.Unbound -> localizedString(Res.string.gamepad_target_unbound)
}
