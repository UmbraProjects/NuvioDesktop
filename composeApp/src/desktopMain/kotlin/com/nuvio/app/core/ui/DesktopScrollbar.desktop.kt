package com.nuvio.app.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.home.HomeCatalogSettingsRepository

@Composable internal actual fun NuvioDesktopVerticalScrollbar(state: LazyListState, modifier: Modifier) {
    if (scrollbarsAllowed()) VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier, style = nuvioScrollbarStyle())
}
@Composable internal actual fun NuvioDesktopVerticalScrollbar(state: LazyGridState, modifier: Modifier) {
    if (scrollbarsAllowed()) VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier, style = nuvioScrollbarStyle())
}
@Composable internal actual fun NuvioDesktopVerticalScrollbar(state: ScrollState, modifier: Modifier, showInTvMode: Boolean) {
    if (showInTvMode || scrollbarsAllowed()) VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier, style = nuvioScrollbarStyle())
}

@Composable internal actual fun NuvioGlassVerticalScrollbar(state: ScrollState, modifier: Modifier) {
    // Nothing to scroll, nothing drawn: a short menu keeps its clean edge.
    if (state.maxValue <= 0) return
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        style = ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 5.dp,
            shape = RoundedCornerShape(100),
            hoverDurationMillis = 160,
            unhoverColor = Color.White.copy(alpha = 0.18f),
            hoverColor = Color.White.copy(alpha = 0.42f),
        ),
    )
}

@Composable private fun scrollbarsAllowed(): Boolean {
    val settings by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    return !settings.tvModeEnabled
}

@Composable private fun nuvioScrollbarStyle() = ScrollbarStyle(
    minimalHeight = 48.dp,
    thickness = 6.dp,
    shape = RoundedCornerShape(100),
    hoverDurationMillis = 180,
    unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
    hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
)
