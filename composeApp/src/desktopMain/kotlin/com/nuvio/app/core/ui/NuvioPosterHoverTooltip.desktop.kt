package com.nuvio.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
actual fun NuvioPosterHoverTooltip(
    title: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (title.isBlank()) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            // Same surface treatment as the stream selector's right-click actions menu
            // (StreamActionsContextMenu.desktop.kt) so hover chrome looks consistent.
            Surface(
                modifier = Modifier.widthIn(max = 360.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFA18191D),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                shadowElevation = 16.dp,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        modifier = modifier,
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        content = content,
    )
}
