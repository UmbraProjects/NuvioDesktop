package com.nuvio.app.core.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A small spinner, named as upstream's so its media-server screens port as they are. */
@Composable
fun NuvioLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    CircularProgressIndicator(
        modifier = modifier,
        color = if (color == Color.Unspecified) MaterialTheme.nuvio.colors.accent else color,
        strokeWidth = 2.dp,
    )
}
