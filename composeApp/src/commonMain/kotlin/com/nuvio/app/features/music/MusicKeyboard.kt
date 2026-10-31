package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusTargetModifierNode
import com.nuvio.app.core.ui.focusedControl
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import com.nuvio.app.core.ui.LocalScreenCovered
import com.nuvio.app.core.ui.arrowKeyFocusNavigation
import kotlinx.coroutines.delay

/*
 * fork:music
 *
 * Keyboard and pad navigation for the Music section. Its pages are ordinary focusable controls, so each page root is a
 * [musicKeyboardRoot]: the arrows move focus between its controls, and opening the page puts focus on its first one.
 * Before this, switching to Music left focus wherever it was (often nowhere) until a click, and the arrows did nothing.
 *
 * Focus still goes astray when the control holding it leaves the screen (a section swap, a panel closing). The window
 * then sees an arrow nobody took, and [MusicKeyboard.onUnhandledArrow] puts focus back on the page on top.
 */

internal object MusicKeyboard {
    internal class Root(
        val requester: FocusRequester,
        val takesFocus: Boolean,
        val entry: FocusRequester?,
        /** The player (dock, bar or card), which the player shortcut moves focus into and back out of. */
        val isPlayer: Boolean = false,
    ) {
        var hasFocus: Boolean = false
        var covered: Boolean = false
    }

    private val roots = mutableStateListOf<Root>()

    /** The page focus was on when the player shortcut moved it into the player, to go back to. */
    private var returnTo: Root? = null

    /** The exact control focus left for the player (any screen, Home too), and the focus manager to read it from. */
    private var returnControl: FocusTargetModifierNode? = null
    internal var focusManager: FocusManager? = null

    fun register(root: Root) {
        roots += root
    }

    fun unregister(root: Root) {
        roots -= root
    }

    /** Whether a music page (the tab, an album, an artist, the full player) is showing. */
    fun pageShowing(): Boolean = roots.any { it.takesFocus && !it.covered && !it.isPlayer }

    /**
     * The player shortcut: into the player's controls, or, from there, back to the control on the page that had focus
     * before. Returns whether it did anything (there is no player on screen when nothing plays).
     */
    fun togglePlayerFocus(): Boolean {
        val player = roots.lastOrNull { it.isPlayer } ?: return false
        if (player.hasFocus) {
            // Back onto exactly the control that was left, when it is still on screen.
            val control = returnControl
            returnControl = null
            if (control != null && control.node.isAttached && runCatching { control.requestFocus() }.getOrDefault(false)) {
                returnTo = null
                return true
            }
            val page = returnTo?.takeIf { it in roots && !it.covered }
                ?: roots.lastOrNull { it.takesFocus && !it.covered && !it.isPlayer }
            returnTo = null
            if (page == null) return true
            val restored = runCatching { page.requester.restoreFocusedChild() }.getOrDefault(false)
            if (!restored) runCatching { (page.entry ?: page.requester).requestFocus() }
            return true
        }
        val from = roots.lastOrNull { it.hasFocus && !it.isPlayer }
        from?.let { runCatching { it.requester.saveFocusedChild() } }
        returnTo = from
        returnControl = focusManager?.focusedControl()
        return runCatching { player.requester.requestFocus() }.getOrDefault(false)
    }

    /**
     * An arrow reached the window with no control taking it. While a music page is showing and nothing in Music holds
     * focus, focus goes to the page on top (the open full player over the page under it). Returns whether it did.
     */
    fun onUnhandledArrow(): Boolean {
        if (roots.any { it.hasFocus }) return false
        val target = roots.lastOrNull { it.takesFocus && !it.covered } ?: return false
        return runCatching { (target.entry ?: target.requester).requestFocus() }.getOrDefault(false) ||
            runCatching { target.requester.requestFocus() }.getOrDefault(false)
    }
}

/**
 * Makes this the root of a music page for the keyboard: the arrows move focus among its controls, and while
 * [takeFocus] its first control is focused when the page appears (and again when a page that covered it is closed).
 */
@Composable
internal fun Modifier.musicKeyboardRoot(
    takeFocus: Boolean = true,
    /** An overlay over the page (the full player): the arrows stay inside it rather than reaching the page beneath. */
    trapFocus: Boolean = false,
    /** Where focus goes on arrival instead of the page's first control (the Music tab's body, not its search box). */
    entry: FocusRequester? = null,
    /** The player itself (see [MusicKeyboard.togglePlayerFocus]). */
    isPlayer: Boolean = false,
): Modifier {
    val requester = remember { FocusRequester() }
    val root = remember(entry) { MusicKeyboard.Root(requester, takeFocus, entry, isPlayer) }
    val covered = LocalScreenCovered.current
    root.covered = covered
    DisposableEffect(root) {
        MusicKeyboard.register(root)
        onDispose { MusicKeyboard.unregister(root) }
    }
    if (takeFocus) {
        LaunchedEffect(covered) {
            if (covered) return@LaunchedEffect
            // The page's controls arrive over a few frames (and a route's enter animation can still hold focus), so the
            // request is retried for a short while until something inside has it.
            repeat(FOCUS_TRIES) { attempt ->
                delay(if (attempt == 0) 32 else 60)
                if (root.hasFocus) return@LaunchedEffect
                if (entry == null || !runCatching { entry.requestFocus() }.getOrDefault(false)) {
                    runCatching { requester.requestFocus() }
                }
            }
        }
    }
    return this
        .then(if (trapFocus) Modifier.focusProperties { onExit = { cancelFocusChange() } }.focusGroup() else Modifier)
        .onFocusChanged { root.hasFocus = it.hasFocus }
        // A focus group, so the requester belongs to a focus node that knows which control inside it was focused: the
        // player shortcut saves that on the way out and restores it on the way back.
        .focusRequester(requester)
        .focusGroup()
        .arrowKeyFocusNavigation()
        // An Enter no control used would otherwise be read by desktop Compose as "focus the first thing inside the
        // focused item" (Enter doubles as the D-pad centre), and focus seemed to jump down the page.
        .onKeyEvent { it.key == Key.Enter || it.key == Key.NumPadEnter }
}

private const val FOCUS_TRIES = 12
