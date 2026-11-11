package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's music "more" menu, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import com.nuvio.app.core.ui.ContextMenuInvocation
import com.nuvio.app.core.ui.HoldToSelectState
import com.nuvio.app.core.ui.NuvioContextMenu
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.secondaryClick
import kotlin.math.roundToInt

/*
 * fork:music-menu - the music pages' "more" menu: the app's own context menu (the one the YouTube tab and the catalogues
 * use), opened beside a song row or a release tile by right-click, the Menu key, or holding OK (a pad's A is OK). One
 * look and one set of keys everywhere: Up and Down move, OK picks, Back or a click outside closes.
 */

/** Where one item's menu is open (null when shut) and where the item sits, so a keyboard-opened menu appears beside it. */
internal class MusicMenuState {
    var at by mutableStateOf<IntOffset?>(null)
    internal var bounds: Rect? = null

    private fun beside(): IntOffset? = bounds?.let { IntOffset(it.left.roundToInt() + 24, it.top.roundToInt() + 24) }

    /** Opened from the keyboard or a pad: beside the item, with its first row focused. */
    fun openFromKeys() {
        beside()?.let { ContextMenuInvocation.recordKeyboardInvocation(it) }
        at = ContextMenuInvocation.consume() ?: beside()
    }

    /** Opened by a right-click: at the cursor. */
    fun openAtCursor() {
        at = ContextMenuInvocation.consume() ?: beside()
    }
}

@Composable
internal fun rememberMusicMenu(): MusicMenuState = remember { MusicMenuState() }

/**
 * Makes an item open [menu]: right-click at the cursor, the Menu key or a held OK beside it. A tap of OK is [onSelect]
 * (fired on release, so a hold can be told from a tap). Put it before the item's own `clickable`.
 */
internal fun Modifier.musicMenuTrigger(menu: MusicMenuState, hold: HoldToSelectState, onSelect: () -> Unit): Modifier =
    this.onGloballyPositioned { menu.bounds = it.boundsInWindow() }
        .onPreviewKeyEvent { event ->
            when (event.key) {
                Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> hold.handle(event, onSelect = onSelect, onHold = { menu.openFromKeys() })
                Key.Menu -> {
                    if (event.type == KeyEventType.KeyDown) menu.openFromKeys()
                    true
                }
                else -> false
            }
        }
        .secondaryClick { menu.openAtCursor() }

/** The open menu for one item, with [actions] read as it opens. Nothing while [menu] is shut. */
@Composable
internal fun MusicItemMenu(menu: MusicMenuState, actions: () -> List<PosterZoomOverlayAction>) {
    val at = menu.at ?: return
    NuvioContextMenu(windowPosition = at, actions = remember(at) { actions() }, onDismiss = { menu.at = null })
}
