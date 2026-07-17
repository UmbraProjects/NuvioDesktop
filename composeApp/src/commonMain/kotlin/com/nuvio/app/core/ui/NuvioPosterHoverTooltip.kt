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
    content: @Composable () -> Unit,
)
