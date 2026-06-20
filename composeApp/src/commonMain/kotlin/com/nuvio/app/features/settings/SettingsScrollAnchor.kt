package com.nuvio.app.features.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val SettingsScrollAnchorHighlightMillis = 3000L

/**
 * Lets a deep-link (e.g. from the Fork Enhancements overview) request that a specific
 * setting row be scrolled into view when its page opens. A row marks itself with
 * [settingsScrollAnchor]; the navigation requests an anchor id, and the matching row brings
 * itself into view and briefly draws a highlight around itself so it's easy to spot even on
 * short pages that can't scroll it to the top.
 */
internal object SettingsScrollAnchor {
    const val AdaptiveHero = "adaptive_hero"
    const val TvMode = "tv_mode"
    const val HeroAmbient = "hero_ambient"
    const val AutoPlayTrailer = "auto_play_trailer"
    const val TrailerSound = "trailer_sound"
    const val TrailerFullscreen = "trailer_fullscreen"
    const val HdrMode = "hdr_mode"
    const val ColorProfile = "color_profile"
    const val MouseMove = "mouse_move"
    const val DefaultSpeed = "default_speed"
    const val BingeMode = "binge_mode"
    const val ExtraLargePosters = "extra_large_posters"

    private val _requested = MutableStateFlow<String?>(null)
    val requested: StateFlow<String?> = _requested.asStateFlow()

    fun request(anchor: String) {
        _requested.value = anchor
    }

    fun consume(anchor: String) {
        if (_requested.value == anchor) _requested.value = null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.settingsScrollAnchor(anchor: String): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val requested by SettingsScrollAnchor.requested.collectAsStateWithLifecycle()
    // Bump a local token (and consume the request) without keying the highlight timer on the
    // shared flow — otherwise consuming would cancel the in-flight highlight.
    var highlightToken by remember { mutableStateOf(0) }
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(requested) {
        if (requested == anchor) {
            highlightToken++
            SettingsScrollAnchor.consume(anchor)
        }
    }
    LaunchedEffect(highlightToken) {
        if (highlightToken == 0) return@LaunchedEffect
        runCatching { requester.bringIntoView() }
        highlighted = true
        delay(SettingsScrollAnchorHighlightMillis)
        highlighted = false
    }
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(durationMillis = if (highlighted) 200 else 600),
        label = "settings_scroll_anchor_highlight",
    )
    val highlightColor = MaterialTheme.colorScheme.primary
    return this
        .bringIntoViewRequester(requester)
        .drawBehind {
            if (highlightAlpha <= 0f) return@drawBehind
            // Draw the highlight inset from the row edges (no layout shift) so it floats
            // inside the row and never clashes with the group card's rounded corners.
            val insetX = 8.dp.toPx()
            val insetY = 5.dp.toPx()
            val strokeWidth = 2.dp.toPx()
            drawRoundRect(
                color = highlightColor.copy(alpha = highlightAlpha),
                topLeft = Offset(insetX + strokeWidth / 2f, insetY + strokeWidth / 2f),
                size = Size(
                    width = size.width - 2f * insetX - strokeWidth,
                    height = size.height - 2f * insetY - strokeWidth,
                ),
                cornerRadius = CornerRadius(10.dp.toPx()),
                style = Stroke(width = strokeWidth),
            )
        }
}
