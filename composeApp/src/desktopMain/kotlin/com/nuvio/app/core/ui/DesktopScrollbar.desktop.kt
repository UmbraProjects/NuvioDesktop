package com.nuvio.app.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import kotlinx.coroutines.delay

@Composable internal actual fun NuvioDesktopVerticalScrollbar(state: LazyListState, modifier: Modifier) {
    if (!scrollbarsAllowed()) return
    val interaction = remember { MutableInteractionSource() }
    val alpha = rememberScrollbarVisibilityAlpha(state.isScrollInProgress, interaction)
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.hoverable(interaction).graphicsLayer { this.alpha = alpha },
        style = nuvioScrollbarStyle(),
    )
}

@Composable internal actual fun NuvioDesktopVerticalScrollbar(state: LazyGridState, modifier: Modifier) {
    if (!scrollbarsAllowed()) return
    val interaction = remember { MutableInteractionSource() }
    val alpha = rememberScrollbarVisibilityAlpha(state.isScrollInProgress, interaction)
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.hoverable(interaction).graphicsLayer { this.alpha = alpha },
        style = nuvioScrollbarStyle(),
    )
}

@Composable internal actual fun NuvioDesktopVerticalScrollbar(state: ScrollState, modifier: Modifier, showInTvMode: Boolean) {
    if (!showInTvMode && !scrollbarsAllowed()) return
    val interaction = remember { MutableInteractionSource() }
    val alpha = rememberScrollbarVisibilityAlpha(state.isScrollInProgress, interaction)
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.hoverable(interaction).graphicsLayer { this.alpha = alpha },
        style = nuvioScrollbarStyle(),
    )
}

@Composable
private fun rememberScrollbarVisibilityAlpha(
    scrolling: Boolean,
    interaction: MutableInteractionSource,
): Float {
    val hovered by interaction.collectIsHoveredAsState()
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            recentlyScrolled = true
        } else if (recentlyScrolled) {
            delay(900L)
            recentlyScrolled = false
        }
    }
    val active = scrolling || hovered || recentlyScrolled
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.28f,
        animationSpec = tween(durationMillis = if (active) 140 else 420),
        label = "desktop_scrollbar_alpha",
    )
    return alpha
}

@Composable internal actual fun NuvioGlassVerticalScrollbar(state: ScrollState, modifier: Modifier) {
    // Nothing to scroll, nothing drawn: a short menu keeps its clean edge.
    if (state.maxValue <= 0) return
    val interaction = remember { MutableInteractionSource() }
    val alpha = rememberScrollbarVisibilityAlpha(state.isScrollInProgress, interaction)
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.hoverable(interaction).graphicsLayer { this.alpha = alpha },
        style = ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 5.dp,
            shape = RoundedCornerShape(100),
            hoverDurationMillis = 160,
            unhoverColor = Color.White.copy(alpha = 0.22f),
            hoverColor = Color.White.copy(alpha = 0.48f),
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
    unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f),
    hoverColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
)
