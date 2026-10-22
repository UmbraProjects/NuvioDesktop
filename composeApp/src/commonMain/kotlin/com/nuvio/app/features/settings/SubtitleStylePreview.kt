package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.SubtitleStyleState

private const val PREVIEW_LINE = "This is how your subtitles will look.\nEvery change shows up here right away."

// mpv sizes text subtitles in units of a 720-line frame (sub-scale-by-window), whatever the window
// size, so scaling those units to the preview's height keeps the proportions the player shows.
private const val MPV_REFERENCE_HEIGHT = 720f
private const val MPV_DEFAULT_SUB_MARGIN_Y = 22f

/**
 * A 16:9 stand-in for the video with sample subtitle text drawn from [style] the way the desktop
 * player maps it to mpv: font size ×3 (capped 18..96), outline width as `sub-border-size`, shadow
 * offset in tenths of a pixel, and the bottom edge at `sub-pos` less mpv's default bottom margin.
 * The font family is not previewed; it is resolved by libass, not by Compose.
 */
@Composable
internal fun SubtitleStylePreview(style: SubtitleStyleState, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF3A4A5C), Color(0xFF8C7A5E), Color(0xFFD9C9A3)),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            ),
    ) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        val unit = heightPx / MPV_REFERENCE_HEIGHT
        val fontSizePx = (style.fontSizeSp * 3f).coerceIn(18f, 96f) * unit
        val outlinePx = if (style.outlineEnabled) style.outlineWidth * unit * 2f else 0f
        val shadowPx = style.shadowOffset / 10f * unit
        val subPos = (100 - (style.bottomOffset / 2)).coerceIn(0, 100)
        val bottomEdgePx = heightPx * subPos / 100f - MPV_DEFAULT_SUB_MARGIN_Y * unit

        val baseStyle = TextStyle(
            fontSize = with(density) { fontSizePx.toSp() },
            lineHeight = with(density) { (fontSizePx * 1.2f).toSp() },
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
            textAlign = TextAlign.Center,
        )
        val hasBox = style.backgroundColor.alpha > 0f
        // mpv draws the shadow only without an opaque box (sub-shadow-color aliases sub-back-color).
        val shadow = if (style.shadowEnabled && !hasBox && shadowPx > 0f) {
            Shadow(color = style.shadowColor, offset = Offset(shadowPx, shadowPx), blurRadius = 0f)
        } else {
            null
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // Anchor the text block's bottom edge on the sub-pos line; it grows upward like mpv.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val y = (bottomEdgePx - placeable.height).toInt()
                            .coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                        placeable.place((constraints.maxWidth - placeable.width) / 2, y)
                    }
                }
                .padding(horizontal = 12.dp)
                .then(if (style.blur > 0) Modifier.blur(with(density) { (style.blur * unit).toDp() }) else Modifier),
        ) {
            Box(
                modifier = Modifier
                    .then(if (hasBox) Modifier.background(style.backgroundColor) else Modifier)
                    .padding(horizontal = with(density) { (4f * unit).toDp() }),
            ) {
                if (outlinePx > 0f) {
                    Text(
                        text = PREVIEW_LINE,
                        style = baseStyle.copy(
                            color = style.outlineColor,
                            drawStyle = Stroke(width = outlinePx),
                            shadow = shadow,
                        ),
                    )
                }
                Text(
                    text = PREVIEW_LINE,
                    style = baseStyle.copy(
                        color = style.textColor,
                        shadow = if (outlinePx > 0f) null else shadow,
                    ),
                )
            }
        }
    }
}
