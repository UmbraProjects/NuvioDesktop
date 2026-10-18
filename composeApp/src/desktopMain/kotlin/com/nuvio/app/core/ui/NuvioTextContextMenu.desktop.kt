package com.nuvio.app.core.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.TextContextMenuArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

/**
 * A [ContextMenuItem] that also says which icon [NuvioContextMenuRepresentation] draws for it, and
 * exposes whether it is available ([ContextMenuItem]'s own flag is internal to Compose).
 */
class NuvioContextMenuItem(
    label: String,
    val icon: ImageVector,
    val isAvailable: Boolean,
    onClick: () -> Unit,
) : ContextMenuItem(label, isAvailable, onClick)

/**
 * Text fields' right-click menu: always Cut/Copy/Paste/Select all, greyed out while unavailable
 * as on Windows. Provided as `LocalTextContextMenu` in Main.kt.
 */
object NuvioTextContextMenu : TextContextMenu {
    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        val localization = LocalLocalization.current
        // Compose's own text area wrapper, so right-click still selects the word under the cursor.
        TextContextMenuArea(
            textManager = textManager,
            items = {
                listOfNotNull(
                    textManager.cut.item(localization.cut, Icons.Rounded.ContentCut),
                    textManager.copy.item(localization.copy, Icons.Rounded.ContentCopy),
                    textManager.paste.item(localization.paste, Icons.Rounded.ContentPaste),
                    textManager.selectAll.item(localization.selectAll, Icons.Rounded.SelectAll),
                )
            },
            state = state,
            content = content,
        )
    }

    // A null action is one the field does not offer at all (e.g. Cut in a read-only field).
    private fun TextContextMenu.Action?.item(label: String, icon: ImageVector): ContextMenuItem? =
        this?.let { action -> NuvioContextMenuItem(label, icon, action.enabled, action.execute) }
}

/**
 * Draws every Compose context menu (the text fields' above, and any `ContextMenuArea`) as
 * [NuvioContextMenu] instead of Compose's white desktop popup. Provided as
 * `LocalContextMenuRepresentation` in Main.kt.
 */
object NuvioContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val open = state.status as? ContextMenuState.Status.Open ?: return
        // Keyed on the open status so a second right-click while open rebuilds the menu at the new
        // spot.
        key(open) {
            // Rebuilt on every recomposition rather than remembered: reading a text field's Paste
            // starts an asynchronous clipboard check and reports "unavailable" until it lands, so
            // a snapshot taken on open never offered Paste. The rows follow that state instead.
            val actions = items().map { item ->
                PosterZoomOverlayAction(
                    icon = (item as? NuvioContextMenuItem)?.icon ?: Icons.Rounded.MoreHoriz,
                    label = item.label,
                    onSelected = item.onClick,
                    enabled = (item as? NuvioContextMenuItem)?.isAvailable ?: true,
                )
            }
            // No early return here: a `return@key` out of this inline composable lambda compiled
            // (Kotlin 2.3 Compose plugin) to an unresolved $$$$$NON_LOCAL_RETURN$$$$$ call, and the
            // class then failed to load with ClassFormatError on both the JVM and native builds.
            if (actions.isNotEmpty()) {
                NuvioContextMenu(
                    positionProvider = remember { AnchoredCursorPositionProvider(open.rect) },
                    actions = actions,
                    onDismiss = { state.status = ContextMenuState.Status.Closed },
                    liveActions = true,
                )
            }
        }
    }
}

/**
 * Compose reports the click as [rect] inside the area that opened the menu; [anchorBounds] is that
 * area in window pixels, so together they are the cursor's window position.
 */
private class AnchoredCursorPositionProvider(private val rect: Rect) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val cursor = IntOffset(
            x = anchorBounds.left + rect.left.roundToInt(),
            y = anchorBounds.top + rect.top.roundToInt(),
        )
        return CursorMenuPositionProvider(cursor)
            .calculatePosition(anchorBounds, windowSize, layoutDirection, popupContentSize)
    }
}
