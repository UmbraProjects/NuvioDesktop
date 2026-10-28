package com.nuvio.app.features.input

import com.nuvio.app.core.i18n.localizedString
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.PlayerShortcutAction
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * A physical control on an XInput pad. Face-button names follow the Xbox layout because that is
 * what XInput reports; a DualShock routed through DS4Windows or Steam Input arrives here with its
 * buttons already translated (Cross = [A], Circle = [B], Square = [X], Triangle = [Y]).
 *
 * Sticks are not listed: the left stick is folded into the D-pad directions by the poller's dead
 * zone, and the right stick is unbound. Only controls that can be *bound to an action* appear
 * here — [displayName] is what the settings inventory shows.
 */
enum class GamepadButton(val id: String, val displayNameRes: StringResource) {
    DpadUp("dpad_up", Res.string.gamepad_button_dpad_up),
    DpadDown("dpad_down", Res.string.gamepad_button_dpad_down),
    DpadLeft("dpad_left", Res.string.gamepad_button_dpad_left),
    DpadRight("dpad_right", Res.string.gamepad_button_dpad_right),
    A("a", Res.string.gamepad_button_a),
    B("b", Res.string.gamepad_button_b),
    X("x", Res.string.gamepad_button_x),
    Y("y", Res.string.gamepad_button_y),
    LeftShoulder("lb", Res.string.gamepad_button_lb),
    RightShoulder("rb", Res.string.gamepad_button_rb),
    LeftTrigger("lt", Res.string.gamepad_button_lt),
    RightTrigger("rt", Res.string.gamepad_button_rt),
    Start("start", Res.string.gamepad_button_start),
    Back("back", Res.string.gamepad_button_back),
    LeftThumb("ls", Res.string.gamepad_button_ls),
    RightThumb("rs", Res.string.gamepad_button_rs),
    // The right stick is a set of one-shot controls, not navigation: each push past the dead zone
    // fires once and has to return to centre before it fires again. That is what makes it usable
    // for stepping playback speed, where a repeat would run the speed away from the user.
    RightStickUp("rs_up", Res.string.gamepad_button_rs_up),
    RightStickDown("rs_down", Res.string.gamepad_button_rs_down),
    RightStickLeft("rs_left", Res.string.gamepad_button_rs_left),
    RightStickRight("rs_right", Res.string.gamepad_button_rs_right),
    ;

    val displayName: String get() = localizedString(displayNameRes)

    companion object {
        fun fromId(id: String): GamepadButton? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where a button's press is sent. Every value resolves to a keystroke — the pad never reaches the
 * app through a path of its own, it synthesizes the key the same action already has on a keyboard.
 * That is what keeps controller support from needing changes in any screen: whatever is navigable
 * by keyboard is navigable by pad, and a user's rebind moves both at once.
 *
 * [FixedKey] carries a `java.awt` `VK_` code for the keys that are not rebindable (arrows, page
 * keys). [AppAction] and [PlayerAction] name a rebindable action instead, resolved against the
 * user's current binding at the moment of the press.
 */
sealed interface GamepadTarget {
    data class FixedKey(val keyCode: Int, val description: StringResource) : GamepadTarget

    data class AppAction(val action: AppShortcutAction) : GamepadTarget

    data class PlayerAction(val action: PlayerShortcutAction) : GamepadTarget

    /**
     * Cycles the shader chain while one is running, and the HDR mode when none is. Shaders and HDR
     * apply at the same time — the playback-info panel lists them as separate rows — so a single
     * control cannot serve both unconditionally, and the shader is the one worth reaching for when
     * both are on.
     */
    data class ShaderOrHdr(
        val shaderAction: PlayerShortcutAction,
        val hdrAction: PlayerShortcutAction,
    ) : GamepadTarget

    /** Nothing bound — the press is swallowed rather than sent as a stray key. */
    data object Unbound : GamepadTarget
}

/**
 * A button's two meanings. The native player installs its own global key dispatcher only while it
 * is on screen, so the same synthesized key already routes correctly — but a pad wants A to mean
 * "select" in a grid and "play/pause" in the player, which is a difference of intent, not routing.
 * [player] applies while playback is up, [browsing] everywhere else.
 */
data class GamepadBinding(
    val browsing: GamepadTarget,
    val player: GamepadTarget,
)
