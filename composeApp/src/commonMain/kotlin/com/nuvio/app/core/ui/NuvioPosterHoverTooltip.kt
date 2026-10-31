package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows the full (untruncated) content title in a styled cursor tooltip while the pointer
 * hovers over the wrapped poster *label* (not the artwork — users who hide labels read the
 * name off the poster itself and shouldn't get hover chrome). Callers wrap only the label
 * Text, inside their hide-labels check, which gates the tooltip for free. Styled to match
 * the stream selector's right-click menu (dark surface, rounded, subtle border) rather than
 * the bare default tooltip.
 *
 * Mouse-only affordance: the desktop actual wraps content in a TooltipArea; touch platforms
 * would just render content unchanged.
 */
@Composable
expect fun NuvioPosterHoverTooltip(
    title: String,
    modifier: Modifier = Modifier,
    /** Optional dimmer second line under [title]; the card shows when either is non-blank. */
    subtitle: String = "",
    /**
     * Shows the card centred above the wrapped control instead of under the cursor. For controls at the bottom
     * of the window, where the cursor-anchored card is pushed back up over the pointer and catches its clicks.
     */
    aboveControl: Boolean = false,
    content: @Composable () -> Unit,
)
