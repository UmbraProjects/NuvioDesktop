package com.nuvio.app.features.input

import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.PlayerShortcutAction
import java.awt.event.KeyEvent
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * The default pad layout, in the shape a console user expects: A selects, B backs out, the
 * shoulders switch sections, and the player reuses the same face buttons for transport.
 *
 * Directions are [GamepadTarget.FixedKey] arrows because arrows are not rebindable anywhere —
 * every browsing screen matches `Key.DirectionUp`/`Down`/`Left`/`Right` literally, and the player
 * dispatcher pins them to seek/volume for exactly that reason. Everything else names an action so
 * a user who rebinds a key on the keyboard moves the pad with it.
 */
internal object GamepadDefaults {
    // The description says what the key does here, not which key it is — the same arrow means
    // "move the focus" while browsing and "volume" during playback, and the settings inventory is
    // read to find out what a button does.
    private fun fixed(keyCode: Int, description: StringResource) = GamepadTarget.FixedKey(keyCode, description)
    private fun app(action: AppShortcutAction) = GamepadTarget.AppAction(action)
    private fun player(action: PlayerShortcutAction) = GamepadTarget.PlayerAction(action)

    val bindings: Map<GamepadButton, GamepadBinding> = mapOf(
        // Same key in both contexts, different meaning: the player's dispatcher pins the arrows to
        // volume and seek, while every browsing screen matches them as focus movement.
        GamepadButton.DpadUp to GamepadBinding(
            browsing = fixed(KeyEvent.VK_UP, Res.string.gamepad_target_move_up),
            player = fixed(KeyEvent.VK_UP, Res.string.gamepad_target_volume_up),
        ),
        GamepadButton.DpadDown to GamepadBinding(
            browsing = fixed(KeyEvent.VK_DOWN, Res.string.gamepad_target_move_down),
            player = fixed(KeyEvent.VK_DOWN, Res.string.gamepad_target_volume_down),
        ),
        GamepadButton.DpadLeft to GamepadBinding(
            browsing = fixed(KeyEvent.VK_LEFT, Res.string.gamepad_target_move_left),
            player = fixed(KeyEvent.VK_LEFT, Res.string.gamepad_target_seek_backward),
        ),
        GamepadButton.DpadRight to GamepadBinding(
            browsing = fixed(KeyEvent.VK_RIGHT, Res.string.gamepad_target_move_right),
            player = fixed(KeyEvent.VK_RIGHT, Res.string.gamepad_target_seek_forward),
        ),

        GamepadButton.A to GamepadBinding(
            browsing = app(AppShortcutAction.SelectFocused),
            // Enter in the player only does something while a panel is open, so A takes the
            // transport role instead — and the panel case still works, because the player's
            // dispatcher sees play/pause and a panel key as different keys entirely.
            player = player(PlayerShortcutAction.PlayPause),
        ),
        GamepadButton.B to GamepadBinding(
            browsing = app(AppShortcutAction.GoBack),
            // Escape, not GoBack: in the player Escape closes the panel that is up, then the
            // player itself. GoBack (Backspace) is not wired into the player dispatcher at all.
            player = app(AppShortcutAction.DismissOverlay),
        ),
        GamepadButton.X to GamepadBinding(
            browsing = app(AppShortcutAction.OpenSearch),
            player = player(PlayerShortcutAction.NextSubtitle),
        ),
        GamepadButton.Y to GamepadBinding(
            browsing = app(AppShortcutAction.ToggleTrailer),
            player = player(PlayerShortcutAction.NextAudio),
        ),

        GamepadButton.LeftShoulder to GamepadBinding(
            browsing = app(AppShortcutAction.OpenLibrary),
            player = player(PlayerShortcutAction.OpenEpisodes),
        ),
        GamepadButton.RightShoulder to GamepadBinding(
            browsing = app(AppShortcutAction.OpenDiscover),
            player = player(PlayerShortcutAction.OpenSources),
        ),
        // Speed, not seek: the D-pad and left stick already seek, and an analogue trigger reads as a
        // rate control. Putting speed here also clears the right stick to hold the picture
        // pipeline as one group.
        GamepadButton.LeftTrigger to GamepadBinding(
            browsing = fixed(KeyEvent.VK_PAGE_UP, Res.string.gamepad_target_page_up),
            player = player(PlayerShortcutAction.SpeedDown),
        ),
        GamepadButton.RightTrigger to GamepadBinding(
            browsing = fixed(KeyEvent.VK_PAGE_DOWN, Res.string.gamepad_target_page_down),
            player = player(PlayerShortcutAction.SpeedUp),
        ),

        GamepadButton.Start to GamepadBinding(
            browsing = app(AppShortcutAction.GoHome),
            player = player(PlayerShortcutAction.SkipInterval),
        ),
        GamepadButton.Back to GamepadBinding(
            browsing = app(AppShortcutAction.DismissOverlay),
            player = player(PlayerShortcutAction.CycleZoom),
        ),
        GamepadButton.LeftThumb to GamepadBinding(
            browsing = app(AppShortcutAction.ToggleFullscreen),
            player = player(PlayerShortcutAction.ToggleMute),
        ),
        GamepadButton.RightThumb to GamepadBinding(
            browsing = app(AppShortcutAction.TogglePeoplePanel),
            player = player(PlayerShortcutAction.ToggleSpeed),
        ),

        // During playback the right stick is the picture pipeline, all four directions: motion
        // interpolation, colour profile, shader/HDR, and the mpv overlay that reports on them.
        // Grouping them means one stick to learn instead of four scattered buttons.
        GamepadButton.RightStickRight to GamepadBinding(
            browsing = app(AppShortcutAction.ToggleTrailerMute),
            player = player(PlayerShortcutAction.ToggleMpvDiagnostics),
        ),
        GamepadButton.RightStickLeft to GamepadBinding(
            // The calendar has no button anywhere in the UI — the keyboard shortcut was the only
            // way in, which made it unreachable from a controller entirely.
            browsing = app(AppShortcutAction.OpenCalendar),
            player = player(PlayerShortcutAction.CycleSvp),
        ),
        GamepadButton.RightStickUp to GamepadBinding(
            browsing = fixed(KeyEvent.VK_HOME, Res.string.gamepad_target_jump_to_top),
            player = player(PlayerShortcutAction.CycleColorProfile),
        ),
        GamepadButton.RightStickDown to GamepadBinding(
            // A game launcher is a reasonable thing to want from a controller, and nothing else on
            // the pad wanted this direction while browsing.
            browsing = app(AppShortcutAction.ToggleGameMode),
            player = GamepadTarget.ShaderOrHdr(
                shaderAction = PlayerShortcutAction.CycleAnime,
                hdrAction = PlayerShortcutAction.CycleHdr,
            ),
        ),
    )

    fun binding(button: GamepadButton): GamepadBinding =
        bindings[button] ?: GamepadBinding(GamepadTarget.Unbound, GamepadTarget.Unbound)
}
