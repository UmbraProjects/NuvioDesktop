package com.nuvio.app.features.input

import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.PlayerShortcutAction
import java.awt.event.KeyEvent
import nuvio.composeapp.generated.resources.*

/**
 * How a rebound button is written to disk, and the list of things a button can be set to.
 *
 * A stored binding is "<browsing>|<player>", each side one of:
 * `none`, `app:<AppShortcutAction.id>`, `player:<PlayerShortcutAction.id>`, `key:<fixed id>`
 * (one of [fixedKeys]) or `shader_or_hdr`. An id this build does not know decodes to null and the
 * button falls back to its default, so a binding saved by a newer build never breaks an older one.
 */
internal object GamepadTargetCodec {
    private const val NONE = "none"
    private const val SHADER_OR_HDR = "shader_or_hdr"

    /** The fixed keys a button can send. Directions and paging are not shortcuts, so they are listed here. */
    data class FixedKeyOption(val id: String, val target: GamepadTarget.FixedKey, val inPlayer: Boolean)

    val fixedKeys: List<FixedKeyOption> = listOf(
        FixedKeyOption("move_up", GamepadTarget.FixedKey(KeyEvent.VK_UP, Res.string.gamepad_target_move_up), false),
        FixedKeyOption("move_down", GamepadTarget.FixedKey(KeyEvent.VK_DOWN, Res.string.gamepad_target_move_down), false),
        FixedKeyOption("move_left", GamepadTarget.FixedKey(KeyEvent.VK_LEFT, Res.string.gamepad_target_move_left), false),
        FixedKeyOption("move_right", GamepadTarget.FixedKey(KeyEvent.VK_RIGHT, Res.string.gamepad_target_move_right), false),
        FixedKeyOption("page_up", GamepadTarget.FixedKey(KeyEvent.VK_PAGE_UP, Res.string.gamepad_target_page_up), false),
        FixedKeyOption("page_down", GamepadTarget.FixedKey(KeyEvent.VK_PAGE_DOWN, Res.string.gamepad_target_page_down), false),
        FixedKeyOption("jump_top", GamepadTarget.FixedKey(KeyEvent.VK_HOME, Res.string.gamepad_target_jump_to_top), false),
        FixedKeyOption("volume_up", GamepadTarget.FixedKey(KeyEvent.VK_UP, Res.string.gamepad_target_volume_up), true),
        FixedKeyOption("volume_down", GamepadTarget.FixedKey(KeyEvent.VK_DOWN, Res.string.gamepad_target_volume_down), true),
        FixedKeyOption("seek_back", GamepadTarget.FixedKey(KeyEvent.VK_LEFT, Res.string.gamepad_target_seek_backward), true),
        FixedKeyOption("seek_forward", GamepadTarget.FixedKey(KeyEvent.VK_RIGHT, Res.string.gamepad_target_seek_forward), true),
    )

    fun encode(target: GamepadTarget): String? = when (target) {
        GamepadTarget.Unbound -> NONE
        is GamepadTarget.AppAction -> "app:${target.action.id}"
        is GamepadTarget.PlayerAction -> "player:${target.action.id}"
        is GamepadTarget.FixedKey -> fixedKeys.firstOrNull { it.target == target }?.let { "key:${it.id}" }
        is GamepadTarget.ShaderOrHdr -> SHADER_OR_HDR
    }

    fun decode(value: String?): GamepadTarget? {
        val text = value?.trim().orEmpty()
        return when {
            text == NONE -> GamepadTarget.Unbound
            text == SHADER_OR_HDR -> GamepadDefaults.shaderOrHdr
            text.startsWith("app:") ->
                AppShortcutAction.entries.firstOrNull { it.id == text.removePrefix("app:") }
                    ?.let { GamepadTarget.AppAction(it) }
            text.startsWith("player:") ->
                PlayerShortcutAction.fromId(text.removePrefix("player:"))?.let { GamepadTarget.PlayerAction(it) }
            text.startsWith("key:") -> fixedKeys.firstOrNull { it.id == text.removePrefix("key:") }?.target
            else -> null
        }
    }

    fun encodeBinding(binding: GamepadBinding): String? {
        val browsing = encode(binding.browsing) ?: return null
        val player = encode(binding.player) ?: return null
        return "$browsing|$player"
    }

    /** Null when the stored text is unreadable; a side this build does not know keeps [fallback]'s. */
    fun decodeBinding(value: String?, fallback: GamepadBinding): GamepadBinding? {
        val parts = value?.split('|') ?: return null
        if (parts.size != 2) return null
        return GamepadBinding(
            browsing = decode(parts[0]) ?: fallback.browsing,
            player = decode(parts[1]) ?: fallback.player,
        )
    }

    /**
     * Everything a button can be set to in one context, in the order the picker lists it: nothing,
     * the fixed keys, then the shortcuts. Push-to-talk is offered in both, as its default is.
     */
    fun options(inPlayer: Boolean): List<GamepadTarget> = buildList {
        add(GamepadTarget.Unbound)
        fixedKeys.filter { it.inPlayer == inPlayer }.forEach { add(it.target) }
        if (inPlayer) {
            PlayerShortcutAction.entries.forEach { add(GamepadTarget.PlayerAction(it)) }
            add(GamepadDefaults.shaderOrHdr)
            add(GamepadTarget.AppAction(AppShortcutAction.DismissOverlay))
            add(GamepadTarget.AppAction(AppShortcutAction.VoiceCommand))
        } else {
            AppShortcutAction.entries.forEach { add(GamepadTarget.AppAction(it)) }
        }
    }
}
