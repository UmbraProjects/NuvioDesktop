package com.nuvio.app.features.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.ditheredBackground
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.accentGradientMask
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.accentFill
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.selectionTextColor
import com.nuvio.app.core.ui.selectionTextBrush
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogMarkerColor
import com.nuvio.app.features.home.composeColor
import com.nuvio.app.features.home.prefersDarkForeground
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.compose_action_off
import nuvio.composeapp.generated.resources.compose_action_on
import nuvio.composeapp.generated.resources.settings_homescreen_collection_with_addon
import nuvio.composeapp.generated.resources.settings_homescreen_bookmark_color
import nuvio.composeapp.generated.resources.settings_homescreen_bookmark_color_description
import nuvio.composeapp.generated.resources.settings_homescreen_bookmark_color_none
import nuvio.composeapp.generated.resources.settings_homescreen_display_name
import nuvio.composeapp.generated.resources.settings_homescreen_hero_source
import nuvio.composeapp.generated.resources.settings_homescreen_hidden
import nuvio.composeapp.generated.resources.settings_homescreen_not_in_hero
import nuvio.composeapp.generated.resources.settings_homescreen_pinned
import nuvio.composeapp.generated.resources.settings_homescreen_pinned_to_top
import nuvio.composeapp.generated.resources.settings_homescreen_visible
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import com.nuvio.app.core.ui.NuvioTextField
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal data class SettingsChoiceOption<T>(
    val value: T,
    val label: String,
)

/**
 * Settings help text. On desktop it wraps in full, as Windows Settings does: each row is its own
 * card with a compact control, so the text has the width to wrap into a line or two instead of
 * being cut to one. Elsewhere it stays one line, with the full text in a hover tooltip.
 */
@Composable
internal fun SettingsSubtext(
    text: String,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.nuvio.colors.textMuted,
) {
    if (isTablet) {
        Text(
            text = text,
            // Capped at the wrap column of a row with a control, so a row without one (or a note)
            // doesn't run its text out to the card edge.
            modifier = modifier.widthIn(max = LocalSettingsTextMaxWidth.current).fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        return
    }
    var hasOverflow by remember(text) { mutableStateOf(false) }
    NuvioPosterHoverTooltip(
        title = text.takeIf { hasOverflow }.orEmpty(),
        modifier = modifier,
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { hasOverflow = it.hasVisualOverflow },
        )
    }
}

/**
 * The mask that pairs with [settingsRowTitleColor]. A highlighted title is accent-coloured, so it
 * has to follow a gradient accent the way every filled accent surface does; `Text` takes a `Color`
 * and cannot express a `Brush`, which is what the mask is for. Only ever one row is highlighted at
 * a time, so its offscreen layer is paid once rather than per row.
 */
@Composable
private fun settingsRowTitleAccentMask(title: String): Modifier {
    val highlight by SettingsScrollAnchor.titleHighlight.collectAsStateWithLifecycle()
    // No mask when the accent is near-white: the title is gold then, and the mask would repaint it.
    return if (highlight?.title == title && MaterialTheme.nuvio.colors.selectionTextBrush() != null) {
        Modifier.accentGradientMask()
    } else {
        Modifier
    }
}

@Composable
private fun settingsRowTitleColor(title: String): Color {
    val highlight by SettingsScrollAnchor.titleHighlight.collectAsStateWithLifecycle()
    return if (highlight?.title == title) {
        // Not the raw accent: on the White theme that is white on white text.
        MaterialTheme.nuvio.colors.selectionTextColor()
    } else {
        MaterialTheme.nuvio.colors.textPrimary
    }
}

@Composable
internal fun settingsSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
    activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
    inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
    disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    disabledActiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
    disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
)

/** Thumb radius shared by the settings sliders. The track is inset by it at both ends. */
private val SettingsSliderThumbRadius = 9.dp

/**
 * The value under [x] on a track inset by [thumbRadiusPx] at each end.
 *
 * Shared by both settings sliders because both draw that inset: mapping the pointer across the full
 * width instead made the rendered endpoints unreachable — grabbing the thumb exactly at maximum
 * reported a value one thumb-radius short of it, and the drawn position then jumped away from the
 * cursor. Fraction is measured over the same `trackStart`..`trackEnd` span the Canvas draws.
 *
 * Step selection rounds rather than truncating; `toInt()` biased every stepped slider downward and
 * made the top step reachable only at the exact final pixel.
 */
internal fun settingsSliderValueForX(
    x: Float,
    widthPx: Int,
    thumbRadiusPx: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val trackStart = thumbRadiusPx
    val trackEnd = (widthPx.toFloat() - thumbRadiusPx).coerceAtLeast(trackStart)
    val trackSpan = (trackEnd - trackStart).takeIf { it > 0f } ?: 1f
    val fraction = ((x - trackStart) / trackSpan).coerceIn(0f, 1f)
    val valueSpan = valueRange.endInclusive - valueRange.start
    val resolvedFraction = if (steps <= 0) {
        fraction
    } else {
        val totalIntervals = steps + 1
        (fraction * totalIntervals).roundToInt().coerceIn(0, totalIntervals).toFloat() / totalIntervals
    }
    return (valueRange.start + valueSpan * resolvedFraction)
        .coerceIn(valueRange.start, valueRange.endInclusive)
}

/**
 * Two-thumb sibling of [SettingsModernSlider], drawn the same way so a range row sits alongside the
 * single-value rows without looking like a different control. Material3's `RangeSlider` is not used
 * for exactly that reason — the settings pages draw their own track.
 *
 * The thumb nearest the press is captured for the whole drag, so a thumb stays grabbed even when
 * the pointer crosses the other one. Ordering and minimum separation are the caller's to enforce in
 * [onValueChange]; this only reports what the user dragged to.
 */
@Composable
internal fun SettingsModernRangeSlider(
    lowValue: Float,
    highValue: Float,
    onValueChange: (low: Float, high: Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    onValueChangeFinished: () -> Unit = {},
    /** Shown in a bubble over the cursor while dragging; see [SettingsSliderValueBubble]. */
    valueLabel: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val drag = remember { SliderDragState() }
    val currentLow by rememberUpdatedState(lowValue)
    val currentHigh by rememberUpdatedState(highValue)
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Which thumb the active drag owns. Set on press, held until release, so crossing the other
    // thumb mid-drag doesn't hand the gesture over to it.
    var draggingHighThumb by remember { mutableStateOf(false) }
    val thumbRadiusPx = with(LocalDensity.current) { SettingsSliderThumbRadius.toPx() }

    fun valueFromX(x: Float): Float = settingsSliderValueForX(
        x = x,
        widthPx = size.width.coerceAtLeast(1),
        thumbRadiusPx = thumbRadiusPx,
        valueRange = valueRange,
        steps = steps,
    )

    fun reportDragTo(x: Float) {
        val dragged = valueFromX(x)
        if (draggingHighThumb) {
            currentOnValueChange(currentLow, dragged)
        } else {
            currentOnValueChange(dragged, currentHigh)
        }
    }

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
    val lowProgress = ((lowValue - valueRange.start) / span).coerceIn(0f, 1f)
    val highProgress = ((highValue - valueRange.start) / span).coerceIn(0f, 1f)
    val activeBrush = tokens.colors.accentFill
    val inactiveColor = tokens.colors.borderDefault.copy(alpha = 0.72f)

    Box(
        modifier = modifier
            .height(28.dp)
            .alpha(if (enabled) 1f else tokens.opacity.medium)
            .onGloballyPositioned { size = it.size }
            // Same caveat as SettingsModernSlider: keying this to the measured size would cancel an
            // in-flight mouse gesture on recomposition and freeze the thumb until release.
            .pointerInput(enabled, valueRange.start, valueRange.endInclusive, steps) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragEnd = { drag.end(); currentOnValueChangeFinished() },
                    onDragCancel = { drag.end(); currentOnValueChangeFinished() },
                    onDragStart = { offset ->
                        val pressed = valueFromX(offset.x)
                        draggingHighThumb =
                            abs(pressed - currentHigh) <= abs(pressed - currentLow)
                        drag.moveTo(offset.x)
                        reportDragTo(offset.x)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        drag.moveTo(change.position.x)
                        reportDragTo(change.position.x)
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
            val centerY = size.height / 2f
            val thumbRadius = SettingsSliderThumbRadius.toPx()
            val trackStart = thumbRadius
            val trackEnd = (size.width.toFloat() - thumbRadius).coerceAtLeast(trackStart)
            val lowX = trackStart + (trackEnd - trackStart) * lowProgress
            val highX = trackStart + (trackEnd - trackStart) * highProgress
            drawGlassSliderTrack(trackStart, trackEnd, centerY)
            // Only the span between the thumbs is active: that band is the setting.
            drawGlassSliderFill(activeBrush, lowX, highX, centerY)
            drawGlassSliderThumb(activeBrush, Offset(lowX, centerY), thumbRadius)
            drawGlassSliderThumb(activeBrush, Offset(highX, centerY), thumbRadius)
        }
        if (valueLabel != null && drag.active) SettingsSliderValueBubble(valueLabel, drag.x)
    }
}

@Composable
internal fun SettingsModernSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    onValueChangeFinished: () -> Unit = {},
    /** Shown in a bubble over the cursor while dragging; see [SettingsSliderValueBubble]. */
    valueLabel: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val drag = remember { SliderDragState() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val thumbRadiusPx = with(LocalDensity.current) { SettingsSliderThumbRadius.toPx() }

    fun valueFromX(x: Float): Float = settingsSliderValueForX(
        x = x,
        widthPx = size.width.coerceAtLeast(1),
        thumbRadiusPx = thumbRadiusPx,
        valueRange = valueRange,
        steps = steps,
    )

    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val progress = if (valueRange.endInclusive == valueRange.start) {
        0f
    } else {
        ((coercedValue - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    }
    val activeBrush = tokens.colors.accentFill
    val inactiveColor = tokens.colors.borderDefault.copy(alpha = 0.72f)

    Box(
        modifier = modifier
            .height(28.dp)
            .alpha(if (enabled) 1f else tokens.opacity.medium)
            .onGloballyPositioned { size = it.size }
            // Keep the gesture coroutine alive while dragging. Keying pointerInput to the measured
            // size (or a freshly-created range object) can cancel an active mouse gesture during
            // recomposition, leaving the thumb frozen until the button is released.
            .pointerInput(enabled, valueRange.start, valueRange.endInclusive, steps) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragEnd = { drag.end(); currentOnValueChangeFinished() },
                    onDragCancel = { drag.end(); currentOnValueChangeFinished() },
                    onDragStart = { offset ->
                        drag.moveTo(offset.x)
                        currentOnValueChange(valueFromX(offset.x))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        drag.moveTo(change.position.x)
                        currentOnValueChange(valueFromX(change.position.x))
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
            val centerY = size.height / 2f
            val thumbRadius = SettingsSliderThumbRadius.toPx()
            val trackStart = thumbRadius
            val trackEnd = (size.width.toFloat() - thumbRadius).coerceAtLeast(trackStart)
            val thumbX = trackStart + (trackEnd - trackStart) * progress
            drawGlassSliderTrack(trackStart, trackEnd, centerY)
            drawGlassSliderFill(activeBrush, trackStart, thumbX, centerY)
            drawGlassSliderThumb(activeBrush, Offset(thumbX, centerY), thumbRadius)
        }
        if (valueLabel != null && drag.active) SettingsSliderValueBubble(valueLabel, drag.x)
    }
}

/** Where the pointer is while a settings slider is being dragged, for its value bubble. */
@Stable
private class SliderDragState {
    var active by mutableStateOf(false)
        private set
    var x by mutableStateOf(0f)
        private set

    fun moveTo(pointerX: Float) {
        x = pointerX
        active = true
    }

    fun end() {
        active = false
    }
}

/** Groove height of the glass slider track. */
private val SliderTrackHeight = 6.dp

/** The track: a recessed strip of the settings glass, with its one-pixel edge. */
private fun DrawScope.drawGlassSliderTrack(start: Float, end: Float, centerY: Float) {
    val height = SliderTrackHeight.toPx()
    val radius = CornerRadius(height / 2f)
    val topLeft = Offset(start, centerY - height / 2f)
    val trackSize = Size((end - start).coerceAtLeast(height), height)
    drawRoundRect(color = SliderTrackFill, topLeft = topLeft, size = trackSize, cornerRadius = radius)
    drawRoundRect(
        color = NuvioGlass.EdgeColor,
        topLeft = topLeft,
        size = trackSize,
        cornerRadius = radius,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** The filled part: the accent as tinted glass, like the selected half of an On/Off. */
private fun DrawScope.drawGlassSliderFill(accent: Brush, from: Float, to: Float, centerY: Float) {
    val height = SliderTrackHeight.toPx()
    if (to - from <= 0f) return
    val left = from - height / 2f
    val topLeft = Offset(left, centerY - height / 2f)
    val fillSize = Size(to - left, height)
    val radius = CornerRadius(height / 2f)
    drawRoundRect(brush = accent, topLeft = topLeft, size = fillSize, cornerRadius = radius, alpha = 0.6f)
    drawRoundRect(brush = accent, topLeft = topLeft, size = fillSize, cornerRadius = radius, style = Stroke(1.dp.toPx()), alpha = 0.9f)
}

/** The thumb: an accent glass bead with a light sheen across its top and a bright rim. */
private fun DrawScope.drawGlassSliderThumb(accent: Brush, center: Offset, radius: Float) {
    drawCircle(brush = accent, radius = radius, center = center, alpha = 0.85f)
    drawCircle(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.45f),
            0.55f to Color.White.copy(alpha = 0.05f),
            1f to Color.Transparent,
            startY = center.y - radius,
            endY = center.y + radius,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(color = Color.White.copy(alpha = 0.35f), radius = radius - 0.5.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
}

private val SliderTrackFill = Color.White.copy(alpha = 0.07f)

/**
 * The slider's value, shown only while it is being dragged, in a small glass bubble just above the
 * cursor. The rows used to spend a column on a readout nobody needs until they move the slider; a
 * Popup, so the first row's bubble is not clipped by its card.
 */
@Composable
private fun SettingsSliderValueBubble(label: String, pointerX: Float) {
    val gap = with(LocalDensity.current) { SliderBubbleGap.roundToPx() }
    val position = remember(pointerX, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset(
                x = (anchorBounds.left + pointerX.roundToInt() - popupContentSize.width / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0),
            )
        }
    }
    val tokens = MaterialTheme.nuvio
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false)) {
        Text(
            text = label,
            modifier = Modifier
                .background(NuvioGlass.night(tokens.colors.background, tokens.colors.accent), NuvioGlass.Shape)
                .ditheredBackground(NuvioGlass.Fill, NuvioGlass.Shape)
                .border(1.dp, NuvioGlass.Edge, NuvioGlass.Shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private val SliderBubbleGap = 6.dp

/** Fixed width shared by dropdown and segmented controls so every picker on the desktop
 * settings pages lines up, regardless of control type. */
internal val DesktopControlWidth = 190.dp

// Trailing inset on the title/description column, shared by every desktop settings row so they
// all wrap at the same column. This is the ONLY lever that moves the wrap point: the text column
// is laid out with Modifier.weight(1f), whose default fill=true pins it to a fixed slot width, and
// a widthIn(max=...) inside a fixed-width constraint is silently ignored. Raise this to wrap
// sooner; a max-width token here would do nothing. Small since descriptions wrap in full (as in
// Windows Settings), so the text should use the row's width rather than stop short of it.
internal val SettingsRowTextGap = 32.dp

/** Vertical padding shared by every desktop settings row (switch, navigation, dropdown,
 * segmented) so row spacing no longer depends on which control type it hosts. Sized for a row
 * that is its own card, as in Windows Settings. */
internal val DesktopRowVerticalPadding = 13.dp

@Composable
private fun SettingsCard(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Desktop is edge-to-edge: no card surface, no border, rows flow directly on the page
    // background (or, in the card layout, on the section card) and are separated only by
    // SettingsGroupDivider hairlines.
    if (isTablet) {
        Column(modifier = modifier.fillMaxWidth(), content = content)
        return
    }
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = tokens.shapes.compactCard,
        border = BorderStroke(
            tokens.borders.hairline,
            tokens.colors.borderSubtle,
        ),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsGroup(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsCard(
        isTablet = isTablet,
        modifier = modifier,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsGroupDivider(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    val cards = isTablet && LocalSettingsSectionCards.current
    if (cards) {
        // Splits the section card into one card per row; see SettingsSectionCardSurface. Inside
        // the card's offscreen layer the gap is cut out of the card so the starfield shows through;
        // anywhere else it falls back to painting the page colour.
        val clears = LocalSettingsCardGapClears.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SettingsRowCardGap)
                .then(
                    if (clears) {
                        val edge = tokens.borders.thin
                        Modifier.drawBehind {
                            drawRect(Color.Black, blendMode = BlendMode.Clear)
                            // Close the glass edge across the cut: the bottom of the card above
                            // and the top of the card below.
                            val stroke = edge.toPx()
                            drawRect(SettingsSectionGlassEdge, size = size.copy(height = stroke))
                            drawRect(
                                SettingsSectionGlassEdge,
                                topLeft = Offset(0f, size.height - stroke),
                                size = size.copy(height = stroke),
                            )
                        }
                    } else {
                        Modifier.background(tokens.colors.background)
                    },
                ),
        )
        return
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = if (isTablet) 0.dp else NuvioTokens.Space.s64 + NuvioTokens.Space.s2),
        thickness = tokens.borders.hairline,
        color = if (cards) tokens.colors.borderDefault else tokens.colors.borderSubtle,
    )
}

@Composable
internal fun TabletPageHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .nuvioConsumePointerEvents(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
        ) {
            if (showBack) {
                NuvioBackButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp),
                    shape = tokens.shapes.compactCard,
                    containerColor = tokens.colors.surface,
                    contentColor = tokens.colors.textPrimary,
                    buttonSize = NuvioTokens.Space.s36,
                    iconSize = tokens.icons.md,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.nuvioTypeScale.displaySm,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun SettingsSidebarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val transparent = SolidColor(Color.Transparent)
    val background = if (selected) tokens.colors.accentFill(tokens.opacity.hover) else transparent
    val iconChip = if (selected) tokens.colors.accentFill(tokens.opacity.selected) else transparent
    val contentColor = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.listGap, vertical = NuvioTokens.Space.s2)
            .background(background, RoundedCornerShape(NuvioTokens.Space.s10))
            .clickable(onClick = onClick)
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = tokens.spacing.listGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier
                .size(tokens.icons.xl)
                .background(iconChip, RoundedCornerShape(NuvioTokens.Radius.md)),
            color = Color.Transparent,
            shape = RoundedCornerShape(NuvioTokens.Radius.md),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) tokens.colors.accent else contentColor,
                    modifier = if (selected) Modifier.accentGradientMask() else Modifier,
                )
            }
        }
        Spacer(modifier = Modifier.width(tokens.spacing.listGap))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/**
 * The settings page currently being rendered. Provided once per page container so any
 * [SettingsSection] can derive a stable favorite/scroll-anchor id without every call site passing
 * it. Null when no page context is available (e.g. mobile), which disables heading favoriting.
 */
internal val LocalSettingsPage = staticCompositionLocalOf<SettingsPage?> { null }

/**
 * Whether the desktop page being rendered draws each [SettingsSection] as its own card - one
 * bordered surface with the heading as a band across the top and the section's rows flush beneath
 * it - instead of the edge-to-edge heading-over-rows layout. Provided by the desktop settings
 * screen for every page; the components read it so no page has to change.
 */
internal val LocalSettingsSectionCards = staticCompositionLocalOf { false }

/** The heading band's inset. Horizontal matches the 16.dp every row carries so the title sits on
 * the same column as the row titles under it. */
private val SettingsSectionCardHeaderPaddingHorizontal = 16.dp
private val SettingsSectionCardHeaderPaddingVertical = 12.dp
private val SettingsHeadingExtraGap = 4.dp

/**
 * A muted paragraph inside a [SettingsSection] - a summary line above the rows, or a footnote after
 * them. In the card layout it carries the same inset as a row so it lands on the row title column
 * with room to breathe under the heading band; edge-to-edge it sits bare, as it always did.
 */
@Composable
internal fun SettingsSectionNote(
    text: String,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val cards = isTablet && LocalSettingsSectionCards.current
    Text(
        text = text,
        modifier = modifier
            .then(
                if (cards) {
                    Modifier.padding(
                        horizontal = SettingsSectionCardHeaderPaddingHorizontal,
                        vertical = SettingsSectionNotePaddingVertical,
                    )
                } else {
                    Modifier
                },
            )
            .widthIn(max = LocalSettingsTextMaxWidth.current)
            .fillMaxWidth(),
        style = style,
        color = MaterialTheme.nuvio.colors.textMuted,
    )
}

// A row's own vertical padding, so a note inside a card reads as one more row rather than a line
// squeezed in above or below the rows.
private val SettingsSectionNotePaddingVertical = DesktopRowVerticalPadding

/** Space between a heading's subtitle and the card under it. */
private val SettingsSectionSubtitleGap = 8.dp

/**
 * Gap the settings list puts between sections. Exposed because a page that has to place two
 * sections inside one lazy item (to share remembered state between them) has to reproduce it by
 * hand, and a literal there would silently drift from the list's own spacing.
 */
internal val SettingsSectionGap = 18.dp

@Composable
internal fun SettingsSection(
    title: String,
    isTablet: Boolean,
    // Rendered immediately after the title text, so a header's own controls (e.g. a refresh or add
    // icon) sit next to the heading with a little padding rather than pushed to the far right the
    // way [actions] is.
    titleTrailing: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * A one-line summary of the section ("51 of 51 catalogs visible"). In the card layout it sits
     * centred under the heading, outside the card, rather than as a loose line inside it; elsewhere
     * it is the first line of the content, as a [SettingsSectionNote].
     */
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val displayTitle = settingsTitleCase(title)
    // Both anchors land the heading at the top of the page so the rows it names are in view.
    val sectionHighlight = rememberSettingsAnchorHighlight(
        SettingsScrollAnchor.section(title),
        alignToTop = true,
    )
    if (isTablet) {
        // Edge-to-edge desktop header: a real heading sitting directly on the page
        // background, not a small boxed label — rows below flow with no card wrapper.
        val page = LocalSettingsPage.current
        // Stable id for this heading, used both to scroll here from a pinned favorite and as the
        // favorite's identity. Empty string when there's no page context (favoriting disabled).
        val anchor = if (page != null) "heading:${page.name}:$title" else ""
        val highlight = rememberSettingsAnchorHighlight(anchor, alignToTop = true)
        // The heading turns accent while it is the search or favourite target, so it follows a
        // gradient accent for the same reason a filled accent surface does. Masked rather than
        // coloured because Text takes a Color and cannot express a Brush; at most one heading is
        // highlighted at a time, so the offscreen layer the mask costs is paid once.
        val titleHighlighted = highlight.highlighted || sectionHighlight.highlighted
        // selectionTextColor, not accent: a white accent would mark a white heading with white.
        val titleColor = if (titleHighlighted) {
            tokens.colors.selectionTextColor()
        } else {
            tokens.colors.textPrimary
        }
        val titleAccentMask = if (titleHighlighted && tokens.colors.selectionTextBrush() != null) {
            Modifier.accentGradientMask()
        } else {
            Modifier
        }
        val cards = LocalSettingsSectionCards.current
        // Click the heading to collapse the section down to its heading; persisted per profile
        // under the same anchor id favourites use. Needs a page context for a stable id.
        val collapsible = page != null
        val collapsedAnchors by SettingsCollapsedSectionsRepository.collapsed.collectAsStateWithLifecycle()
        val collapsed = collapsible && anchor in collapsedAnchors
        // A search result for a row in a collapsed section falls back to this heading. Open the
        // section for this visit (without persisting it) so the row mounts and takes the jump
        // itself; otherwise the user lands on a closed heading and has to hunt for the row.
        var revealed by remember(anchor) { mutableStateOf(false) }
        val requested by SettingsScrollAnchor.requested.collectAsStateWithLifecycle()
        val sectionAnchor = SettingsScrollAnchor.section(title)
        LaunchedEffect(requested, collapsed) {
            if (collapsed && requested?.fallbackAnchor == sectionAnchor) revealed = true
        }
        val expanded = !collapsed || revealed
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = tween(durationMillis = 160),
        )
        Column {
            // Card layout, after Windows Settings: a plain heading on the page, the rows in cards
            // under it.
            val headerModifier = if (cards) {
                // No band of its own: a centred title over the glass cards, with the section's
                // actions and chevron still at the right.
                Modifier.padding(
                    start = SettingsSectionCardHeaderPaddingHorizontal,
                    end = SettingsSectionCardHeaderPaddingHorizontal,
                    top = SettingsSectionCardHeaderPaddingVertical * 3 / 4,
                    // A touch more below than above, so the heading has room before its card.
                    bottom = SettingsSectionCardHeaderPaddingVertical * 3 / 4 + SettingsHeadingExtraGap,
                )
            } else {
                Modifier.padding(top = 6.dp, bottom = 10.dp)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (collapsible) {
                            // Header actions are their own clickables and consume their presses
                            // first, so only clicks on the band itself toggle.
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (expanded) {
                                    revealed = false
                                    SettingsCollapsedSectionsRepository.setCollapsed(anchor, true)
                                } else {
                                    SettingsCollapsedSectionsRepository.setCollapsed(anchor, false)
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .then(headerModifier),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Card layout centres the title: equal-weight slots either side of it, the right
                // one holding the actions.
                if (cards) Spacer(modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.nuvioTypeScale.titleSm,
                        color = titleColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = sectionHighlight.modifier
                            .then(highlight.modifier)
                            .then(titleAccentMask)
                            .then(
                                if (page != null) {
                                    // Right-click the heading to pin it as a favorite.
                                    Modifier.secondaryClick {
                                        SettingsFavoritesRepository.add(
                                            SettingsFavorite(
                                                page = page.name,
                                                anchor = anchor,
                                                title = displayTitle,
                                            ),
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
                    titleTrailing()
                }
                Row(
                    modifier = if (cards) Modifier.weight(1f) else Modifier,
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                    if (collapsible) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = tokens.colors.textMuted,
                            modifier = Modifier
                                .padding(start = NuvioTokens.Space.s4)
                                .size(tokens.icons.md)
                                .rotate(chevronRotation),
                        )
                    }
                }
            }
            if (cards && subtitle != null) {
                Text(
                    text = subtitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = SettingsSectionSubtitleGap),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            AnimatedVisibility(visible = expanded) {
                SettingsSectionCardSurface(cards = cards) {
                    if (!cards && subtitle != null) SettingsSectionNote(text = subtitle, isTablet = true)
                    content()
                }
            }
        }
        return
    }
    Column {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(sectionHighlight.modifier),
            color = tokens.colors.surface,
            shape = RoundedCornerShape(
                topStart = NuvioTokens.Radius.sm,
                topEnd = NuvioTokens.Radius.sm,
                bottomEnd = 0.dp,
                bottomStart = 0.dp,
            ),
            border = BorderStroke(tokens.borders.hairline, tokens.colors.borderSubtle),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textMuted,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    titleTrailing()
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        content()
    }
}

/**
 * The card layout's rows, on the card surface; the edge-to-edge layout is a bare column.
 *
 * Every row reads as its own card, as in Windows Settings: [SettingsGroupDivider] draws the gap
 * between two rows in the page colour rather than a hairline. Doing it here, once, rather than
 * giving each row its own surface, is what keeps the dozens of page-specific rows (which draw no
 * background of their own) looking the same as the shared ones.
 *
 * No border: the step from the page background carries the outline on its own.
 */
@Composable
private fun SettingsSectionCardSurface(
    cards: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!cards) {
        Column(content = content)
        return
    }
    val tokens = MaterialTheme.nuvio
    Surface(
        // Offscreen so SettingsGroupDivider can clear the gaps between rows out of this layer.
        // A translucent glass fill and a one-pixel light edge over the settings sky.
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .ditheredBackground(SettingsSectionGlassFill, SettingsSectionGlassShape)
            .border(tokens.borders.thin, SettingsSectionGlassEdge, SettingsSectionGlassShape),
        color = Color.Transparent,
        contentColor = contentColorFor(tokens.colors.surface),
        shape = SettingsSectionGlassShape,
    ) {
        BoxWithConstraints {
            // Where a row with a control wraps its text: the card less the row's side padding,
            // the control column and the gap before it.
            val textMaxWidth = maxWidth - SettingsSectionCardHeaderPaddingHorizontal * 2 -
                DesktopControlWidth - SettingsRowTextGap
            CompositionLocalProvider(
                LocalSettingsCardGapClears provides true,
                LocalSettingsTextMaxWidth provides textMaxWidth.coerceAtLeast(0.dp),
            ) {
                Column(content = content)
            }
        }
    }
}

/** True inside a section card's offscreen layer, where a row gap may clear pixels safely. */
private val LocalSettingsCardGapClears = staticCompositionLocalOf { false }

/** The shared wrap width for settings help text; unbounded outside a section card. */
private val LocalSettingsTextMaxWidth = staticCompositionLocalOf { Dp.Infinity }

// The settings names for the shared glass; see [NuvioGlass].
internal val SettingsGlassRadius = NuvioGlass.Radius
internal val SettingsSectionGlassShape = NuvioGlass.Shape
internal val SettingsSectionGlassFill = NuvioGlass.Fill
internal val SettingsSectionGlassEdgeColor = NuvioGlass.EdgeColor
internal val SettingsSectionGlassEdge = NuvioGlass.Edge

/** The page-coloured gap between two row cards. */
private val SettingsRowCardGap = 3.dp

/**
 * A labelled text setting: title, muted description, then the field.
 *
 * The shape every other settings control already has — [SettingsNavigationRow],
 * [SettingsSwitchRow] and [SettingsChoiceRow] all lead with a title and explain themselves
 * underneath. A bare [NuvioTextField] carrying its label in `supportingText` puts the explanation
 * *below* the box and gives it no title at all, so a dialog full of them reads as a different
 * product from the page that opened it.
 *
 * [placeholder] is for the hint inside an empty field; it is not a substitute for [description],
 * which stays visible once there is text in the box.
 */
@Composable
internal fun SettingsTextRow(
    title: String,
    description: String?,
    value: String,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = if (isTablet) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = MaterialTheme.nuvio.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
        }
        NuvioTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            secret = secret,
            singleLine = singleLine,
            minLines = minLines,
        )
    }
}

@Composable
internal fun SettingsNavigationRow(
    title: String,
    description: String,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    enabled: Boolean = true,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Shown in the control column, where another row has its switch or dropdown: a short status
     * ("Accepted") for a row that opens an editor rather than holding a control of its own.
     */
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val titleColor = settingsRowTitleColor(title)
    val titleAccentMask = settingsRowTitleAccentMask(title)
    val iconSize = if (isTablet) 34.dp else 36.dp
    val verticalPadding = if (isTablet) DesktopRowVerticalPadding else 14.dp
    val horizontalPadding = if (isTablet) 16.dp else 16.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = if (isTablet) SettingsRowTextGap else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null || iconPainter != null) {
                Surface(
                    modifier = Modifier
                        .size(iconSize)
                        .background(
                            tokens.colors.accentFill(tokens.opacity.pressed),
                            tokens.shapes.compactCard,
                        ),
                    color = Color.Transparent,
                    shape = tokens.shapes.compactCard,
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (iconPainter != null) {
                            androidx.compose.foundation.Image(
                                painter = iconPainter,
                                contentDescription = null,
                                modifier = Modifier.size(if (isTablet) 22.dp else 24.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = tokens.colors.accent,
                                modifier = Modifier.accentGradientMask(),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(if (isTablet) 10.dp else 14.dp))
            }
            Column {
                Text(
                    text = title,
                    modifier = titleAccentMask,
                    style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    fontWeight = FontWeight.Medium,
                )
                // A blank description draws no line at all. Rendered unconditionally it still
                // occupies a line box plus the spacer above it, which reads as a row that lost its
                // subtitle rather than one that never had it.
                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(if (isTablet) 1.dp else 2.dp))
                    SettingsSubtext(
                        text = description,
                        isTablet = isTablet,
                        modifier = Modifier.alpha(0.92f),
                    )
                }
            }
        }
        if (trailingContent != null) {
            // Centred in the control column, on the same line as the switches and dropdowns.
            Box(
                modifier = if (isTablet) Modifier.width(DesktopControlWidth) else Modifier,
                contentAlignment = if (isTablet) Alignment.Center else Alignment.CenterEnd,
            ) {
                trailingContent()
            }
        }
    }
}

/**
 * A settings action in the glass, sized to the control column so it lines up with the switches
 * and dropdowns: [accent] for the thing the row is for (Test, Connect), plain glass for the rest
 * (Disconnect). Replaces the Material buttons that used to sit on a line of their own.
 */
@Composable
internal fun SettingsGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    loading: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val shape = SettingsSectionGlassShape
    Box(
        modifier = modifier
            .width(DesktopControlWidth)
            .heightIn(min = SettingsGlassButtonHeight)
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium)
            .ditheredBackground(SettingsSectionGlassFill, shape)
            .then(
                if (accent) {
                    Modifier
                        .background(tokens.colors.accentFill(SegmentSelectedTintAlpha), shape)
                        .ditheredBackground(SegmentSelectedSheen, shape)
                        .border(tokens.borders.thin, tokens.colors.accentFill(SegmentSelectedEdgeAlpha), shape)
                } else {
                    Modifier.border(tokens.borders.thin, SettingsSectionGlassEdge, shape)
                },
            )
            // Clipped after the edge: a clip is not anti-aliased here and stair-steps an edge drawn inside it.
            .clip(shape)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = tokens.colors.textPrimary,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Height of [SettingsGlassButton], level with the On/Off selectors and dropdowns. */
private val SettingsGlassButtonHeight = 38.dp

/**
 * An icon drawn as the settings glass rather than flat paint: the accent seen through the glyph
 * (translucent, like the tint on a selected On/Off half) with a light sheen across its top half. For the bare icons in the
 * settings top bar, so they read as made of the same material as the buttons below them without a
 * tile behind each. [lit] false is the glass with no accent in it, for a toggle that is off.
 *
 * The glyph is only a mask here: whatever tint the [androidx.compose.material3.Icon] has is
 * replaced, so pass any opaque colour.
 */
@Composable
internal fun Modifier.settingsGlassGlyph(lit: Boolean = true): Modifier {
    val tokens = MaterialTheme.nuvio
    val body: Brush = if (lit) tokens.colors.accentFill else SolidColor(tokens.colors.textMuted)
    val bodyAlpha = if (lit) GlassGlyphLitAlpha else GlassGlyphUnlitAlpha
    return this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            // The glyph is the mask: recolour it as tinted glass, then lay the sheen over it alone.
            drawRect(body, alpha = bodyAlpha, blendMode = BlendMode.SrcIn)
            drawRect(GlassGlyphSheen, blendMode = BlendMode.SrcAtop)
        }
}

private const val GlassGlyphLitAlpha = 0.78f
private const val GlassGlyphUnlitAlpha = 0.62f
// A bright catch-light along the top of the glyph that falls off fast, then a soft wash down to
// the middle where it stops: the glass buttons' sheen, steepened so it reads at icon size.
private val GlassGlyphSheen = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.62f),
    0.16f to Color.White.copy(alpha = 0.30f),
    0.5f to Color.White.copy(alpha = 0.06f),
    0.52f to Color.White.copy(alpha = 0f),
)

/**
 * A row whose control is an action: title and description on the left, a [SettingsGlassButton]
 * (or several, via [actions]) in the control column, the same place every other row keeps its
 * switch or dropdown.
 */
@Composable
internal fun SettingsActionRow(
    title: String,
    description: String?,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    descriptionColor: Color? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (isTablet) DesktopRowVerticalPadding else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = if (isTablet) SettingsRowTextGap else 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (!description.isNullOrBlank()) {
                SettingsSubtext(
                    text = description,
                    isTablet = isTablet,
                    color = descriptionColor ?: tokens.colors.textMuted,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = actions)
    }
}

/** Pill toggle used in place of Material3's [Switch] across the settings pages, drawn by hand so
 * its colours follow the theme accent (including gradient accents). */
@Composable
internal fun SettingsSquareSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    val trackWidth = 40.dp
    val trackHeight = 22.dp
    val thumbSize = 16.dp
    val thumbInset = 3.dp
    // Pill track and round thumb, to sit with the rounded glass cards.
    val trackShape = RoundedCornerShape(percent = 50)
    val thumbShape = CircleShape
    val trackColor by animateColorAsState(
        targetValue = if (checked) tokens.colors.accent else tokens.colors.borderDefault,
        label = "settingsSquareSwitchTrack",
    )
    // Off-to-on animates between flat colours; once it lands on "on" the accent fill takes over so
    // a gradient theme shows both stops.
    val trackBrush = if (checked) tokens.colors.accentFill else SolidColor(trackColor)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - thumbInset else thumbInset,
        label = "settingsSquareSwitchThumb",
    )
    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium)
            .clip(trackShape)
            .background(trackBrush)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                enabled = enabled,
                role = Role.Switch,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(thumbSize)
                .clip(thumbShape)
                .background(tokens.colors.onAccent),
        )
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    /** Replaces the title text, for the rare row whose label is editable in place. */
    titleContent: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val titleColor = settingsRowTitleColor(title)
    val titleAccentMask = settingsRowTitleAccentMask(title)
    val verticalPadding = if (isTablet) DesktopRowVerticalPadding else 14.dp
    val horizontalPadding = if (isTablet) 16.dp else 16.dp
    val controlWidthModifier = if (isTablet) {
        Modifier.width(DesktopControlWidth)
    } else {
        Modifier.widthIn(min = 220.dp)
    }
    // A boolean toggle is just a two-option binary choice, so render it with the same segmented
    // On/Off control the other binary settings use rather than a separate square switch.
    val toggleOptions = listOf(
        SettingsChoiceOption(true, stringResource(Res.string.compose_action_on)),
        SettingsChoiceOption(false, stringResource(Res.string.compose_action_off)),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                // Desktop, as Windows Settings: the whole card flips the toggle, not only the
                // switch. Not for a row whose title is an editable field.
                if (isTablet && titleContent == null) {
                    Modifier.toggleable(
                        value = checked,
                        onValueChange = onCheckedChange,
                        enabled = enabled,
                        role = Role.Switch,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Surface(
                modifier = Modifier
                    .size(if (isTablet) 34.dp else 36.dp)
                    .background(
                        tokens.colors.accentFill(tokens.opacity.pressed),
                        tokens.shapes.compactCard,
                    ),
                color = Color.Transparent,
                shape = tokens.shapes.compactCard,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                        modifier = Modifier.accentGradientMask(),
                    )
                }
            }
            Spacer(modifier = Modifier.width(if (isTablet) 10.dp else 14.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = if (isTablet) SettingsRowTextGap else 12.dp)
                .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    modifier = titleAccentMask,
                    style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (!description.isNullOrBlank()) {
                SettingsSubtext(
                    text = description,
                    isTablet = isTablet,
                )
            }
        }
        // An On/Off pair in the settings glass, the same size as every dropdown and A/B choice
        // on the page, rather than a small switch. SettingsSquareSwitch stays for the lists that
        // toggle many items at once (categories, catalogs, collections).
        SettingsSegmentedControl(
            options = toggleOptions,
            selectedValue = checked,
            enabled = enabled,
            isTablet = isTablet,
            modifier = controlWidthModifier
                .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
            onSelected = onCheckedChange,
        )
    }
}

@Composable
internal fun <T> SettingsChoiceRow(
    title: String,
    description: String?,
    options: List<SettingsChoiceOption<T>>,
    selectedValue: T,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    enabled: Boolean = true,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    flushContent: Boolean = false,
    onSelected: (T) -> Unit,
    onMoreOptionsClick: (() -> Unit)? = null,
) {
    if (options.size == 2) {
        SettingsSegmentedChoiceRow(
            title = title,
            description = description,
            options = options,
            selectedValue = selectedValue,
            icon = icon,
            iconPainter = iconPainter,
            enabled = enabled,
            isTablet = isTablet,
            modifier = modifier,
            flushContent = flushContent,
            onSelected = onSelected,
        )
    } else {
        SettingsDropdownChoiceRow(
            title = title,
            description = description,
            options = options,
            selectedValue = selectedValue,
            icon = icon,
            iconPainter = iconPainter,
            enabled = enabled,
            isTablet = isTablet,
            modifier = modifier,
            flushContent = flushContent,
            onSelected = onSelected,
        )
    }
}

@Composable
internal fun <T> SettingsSegmentedChoiceRow(
    title: String,
    description: String?,
    options: List<SettingsChoiceOption<T>>,
    selectedValue: T,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    enabled: Boolean = true,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    flushContent: Boolean = false,
    onSelected: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (flushContent) 0.dp else 16.dp
    val verticalPadding = if (isTablet) DesktopRowVerticalPadding else 12.dp
    val controlWidthModifier = if (isTablet) {
        Modifier.width(DesktopControlWidth)
    } else {
        Modifier.widthIn(min = 220.dp)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsChoiceLeadingIcon(icon = icon, iconPainter = iconPainter, isTablet = isTablet)
        SettingsRowText(
            title = title,
            description = description,
            isTablet = isTablet,
            trailingInset = !flushContent,
            modifier = Modifier
                .weight(1f)
                .padding(end = if (flushContent || isTablet) 0.dp else 16.dp),
        )
        SettingsSegmentedControl(
            options = options,
            selectedValue = selectedValue,
            enabled = enabled,
            isTablet = isTablet,
            modifier = controlWidthModifier,
            onSelected = onSelected,
        )
    }
}

/** The pill-shaped two-segment control shared by [SettingsSegmentedChoiceRow] and the boolean
 * [SettingsSwitchRow], so on/off toggles read the same as any other binary choice. */
@Composable
internal fun <T> SettingsSegmentedControl(
    options: List<SettingsChoiceOption<T>>,
    selectedValue: T,
    enabled: Boolean,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // The settings glass, like the dropdowns beside it; the selected half is tinted glass in the
    // accent, inset by the track padding so its corners follow the track's.
    val controlShape = SettingsSectionGlassShape
    val segmentShape = RoundedCornerShape(SettingsGlassRadius - SegmentedTrackPadding)
    Row(
        modifier = modifier
            .ditheredBackground(SettingsSectionGlassFill, controlShape)
            .border(tokens.borders.thin, SettingsSectionGlassEdge, controlShape)
            .padding(SegmentedTrackPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.take(2).forEach { option ->
            val selected = option.value == selectedValue
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (selected) {
                            // Accent seen through glass rather than painted on: a translucent tint
                            // (a brush, so gradient accents keep both stops), a soft sheen across
                            // the top half, and a brighter accent edge.
                            Modifier
                                .background(tokens.colors.accentFill(SegmentSelectedTintAlpha), segmentShape)
                                .ditheredBackground(SegmentSelectedSheen, segmentShape)
                                .border(
                                    tokens.borders.thin,
                                    tokens.colors.accentFill(SegmentSelectedEdgeAlpha),
                                    segmentShape,
                                )
                        } else {
                            Modifier
                        },
                    )
                    // Clipped after the fill and edge, not before: a clip is not anti-aliased
                    // here, and drawn inside one the accent edge came out stair-stepped. The clip
                    // is only there to keep the hover/press feedback inside the rounded corners.
                    .clip(segmentShape)
                    .clickable(enabled = enabled) { onSelected(option.value) }
                    .padding(horizontal = if (isTablet) SegmentHorizontalPadding else 12.dp, vertical = if (isTablet) 6.dp else 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    // Primary rather than onAccent: the tint is see-through, so the label sits on the
                    // dark glass behind it, and onAccent is dark for the pale accents.
                    color = if (selected) tokens.colors.textPrimary else tokens.colors.textSecondary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val SegmentedTrackPadding = 3.dp

private const val SegmentSelectedTintAlpha = 0.42f
private const val SegmentSelectedEdgeAlpha = 0.85f
private val SegmentSelectedSheen = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.16f),
    0.5f to Color.White.copy(alpha = 0.04f),
    1f to Color.Transparent,
)

/** Horizontal padding inside one segment of [SettingsSegmentedControl] on desktop. */
private val SegmentHorizontalPadding = 8.dp

/** A menu never shrinks below this, however cramped the anchor is; it scrolls instead. */
private val MenuMinHeight = 160.dp

/**
 * Inset shared by a dropdown's trigger and its menu rows.
 *
 * One definition for both, because they are one shape: a menu row indented differently from the
 * label above it makes the seam read as two controls that happen to be touching.
 */
private fun menuHorizontalPadding(isTablet: Boolean): Dp = if (isTablet) 10.dp else 14.dp

/** Vertical padding either side of a menu row's label. */
private val MenuRowVerticalPadding = 10.dp

/** Vertical padding that keeps dropdown triggers level with [SettingsSegmentedControl]. */
private fun triggerVerticalPadding(isTablet: Boolean): Dp = if (isTablet) 6.dp else 9.dp

/** Padding on the menu's free edge, away from the trigger. */
private val MenuEdgePadding = 6.dp

/** Thickness of the rule along the seam. */
private val MenuSeamThickness = 1.dp

/**
 * How long a menu takes to open, and — shorter still — to close.
 *
 * Both are deliberately quick. `AnimatedVisibility`'s default springs settle over roughly a third
 * of a second, and a menu that is still visibly folding away when the user goes to open it again
 * reads as an unresponsive control rather than a slow one. The exit is the half that matters most,
 * so it gets the shorter time and an accelerating curve: once the answer is chosen the panel's
 * only remaining job is to be gone.
 */
private const val MenuEnterMillis = NuvioTokens.Motion.fastMillis
private const val MenuExitMillis = 100

/**
 * Padding on the edge where the menu meets its trigger.
 *
 * Sized so the gap across the seam matches the gap between two menu rows. The trigger already
 * contributes its own padding on its side of the join, so the menu only owes the difference —
 * without that the last option sits closer to the trigger's label than the options do to each
 * other, and the seam reads as a squeeze.
 */
private fun menuSeamPadding(isTablet: Boolean): Dp =
    (MenuRowVerticalPadding * 2 - triggerVerticalPadding(isTablet)).coerceAtLeast(MenuEdgePadding)

/**
 * A rule along the seam in the glass edge tone, dividing the options from the value the trigger
 * already shows. Painted on the panel so it stays pinned to the joined edge while a long list
 * scrolls beneath it.
 */
private fun Modifier.menuSeam(openUp: Boolean): Modifier = drawWithContent {
    drawContent()
    val thickness = MenuSeamThickness.toPx()
    drawRect(
        brush = SettingsSectionGlassEdge,
        topLeft = Offset(0f, if (openUp) size.height - thickness else 0f),
        size = Size(size.width, thickness),
    )
}

/** Roughly one option row, used to guess the menu's height before it is measured. */
private val MenuRowHeight = 41.dp

/**
 * A menu never grows past this, however much room the window has.
 *
 * It caps the height and, just as importantly, the appetite: without it a long list would decide
 * it "wants" more room than either side can offer and flip upward on any anchor with a little more
 * space above, when what it should do is stay put and scroll.
 */
private val MenuPreferredMaxHeight = 360.dp

/** Kept clear of the window edge so a menu never runs to the very bottom of the screen. */
private val MenuWindowMargin = 12.dp

/** Which side of its trigger a menu opened on, and how much room it was given. */
@Immutable
private data class MenuPlacement(val openUp: Boolean, val maxHeight: Dp) {
    /** The trigger's shape: square on the edge the menu is joined to, rounded elsewhere. */
    fun triggerShape(expanded: Boolean): Shape = joinedShape(expanded, squareOnTop = openUp)

    /** The menu's shape, mirroring [triggerShape] so the two meet as one outline. */
    fun menuShape(): Shape = joinedShape(joined = true, squareOnTop = !openUp)

    private fun joinedShape(joined: Boolean, squareOnTop: Boolean): Shape {
        val square = 0.dp
        val round = SettingsGlassRadius
        val top = if (joined && squareOnTop) square else round
        val bottom = if (joined && !squareOnTop) square else round
        return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    }
}

/**
 * Which way a menu of [optionCount] options should open from [anchorBounds], and how tall it may be.
 *
 * Decided here rather than left to Material because the trigger has to square the edge it is
 * joined to, and it can only do that if it knows the answer before the menu is placed. The height
 * it hands back is the room on the chosen side, so the menu always fits where it was put and
 * scrolls rather than being shunted somewhere else.
 *
 * The height is estimated from the option count rather than measured. Measuring would mean placing
 * the menu, reading it back and moving it, which is a visible jump on the first frame; being a row
 * or two out only costs a little slack or a little scrolling.
 */
@Composable
private fun rememberMenuPlacement(anchorBounds: Rect?, optionCount: Int): MenuPlacement {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current
    return remember(anchorBounds, optionCount, windowHeight, density) {
        with(density) {
            if (anchorBounds == null) return@with MenuPlacement(openUp = false, maxHeight = MenuMinHeight)
            val margin = MenuWindowMargin.toPx()
            val below = (windowHeight - anchorBounds.bottom - margin).coerceAtLeast(0f)
            val above = (anchorBounds.top - margin).coerceAtLeast(0f)
            val wanted = minOf(optionCount * MenuRowHeight.toPx(), MenuPreferredMaxHeight.toPx())
            // Downward is the default and only given up when it genuinely cannot hold the menu and
            // the other side can do better; a menu that flips for a couple of pixels is worse than
            // one that scrolls slightly.
            val openUp = below < wanted && above > below
            val room = if (openUp) above else below
            val height = minOf(room, MenuPreferredMaxHeight.toPx())
            MenuPlacement(openUp, height.coerceAtLeast(MenuMinHeight.toPx()).toDp())
        }
    }
}

/** Places a menu flush against its anchor, on the side [placement] chose. */
private class AnchoredMenuPosition(private val placement: MenuPlacement) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = anchorBounds.left,
        y = if (placement.openUp) {
            anchorBounds.top - popupContentSize.height
        } else {
            anchorBounds.bottom
        },
    )
}

/**
 * The menu half of a dropdown: a panel joined flush to its trigger, on either side of it.
 *
 * Built on [Popup] rather than Material's `DropdownMenu`, because the trigger and the menu are one
 * shape here and `DropdownMenu` cannot keep that promise. It scales its content up from 0.8 about
 * its own transform origin as it opens, so through the whole animation a smaller, differently
 * proportioned panel sits against a trigger that has already squared its edge — the seam visibly
 * slides into place. This grows the panel out of the joined edge instead, so that edge is pinned
 * from the first frame and only the far edge moves.
 */
@Composable
private fun SettingsMenuPanel(
    expanded: Boolean,
    placement: MenuPlacement,
    widthModifier: Modifier,
    isTablet: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = expanded
    if (!transition.currentState && !transition.targetState) return

    val grownFrom = if (placement.openUp) Alignment.Bottom else Alignment.Top
    Popup(
        popupPositionProvider = remember(placement) { AnchoredMenuPosition(placement) },
        onDismissRequest = onDismissRequest,
        // Focusable only while the menu is genuinely open. A focusable popup swallows the click
        // that lands outside it — that is how clicking away dismisses one — but this popup stays
        // composed through its exit animation, and left focusable for that stretch it goes on
        // swallowing clicks after the menu is logically closed, including the click on the trigger
        // that was meant to reopen it. Dropping focus the moment it starts closing hands the
        // trigger back immediately, so a reopen never has to wait for the animation to finish.
        properties = PopupProperties(focusable = expanded),
    ) {
        AnimatedVisibility(
            visibleState = transition,
            enter = expandVertically(
                animationSpec = tween(MenuEnterMillis, easing = NuvioTokens.Motion.standard),
                expandFrom = grownFrom,
            ) + fadeIn(tween(MenuEnterMillis, easing = NuvioTokens.Motion.standard)),
            exit = shrinkVertically(
                animationSpec = tween(MenuExitMillis, easing = NuvioTokens.Motion.accelerate),
                shrinkTowards = grownFrom,
            ) + fadeOut(tween(MenuExitMillis, easing = NuvioTokens.Motion.accelerate)),
        ) {
            Column(
                modifier = widthModifier
                    .heightIn(max = placement.maxHeight)
                    // It floats over other rows, so the glass needs a solid base under it to stay
                    // readable; the fill and edge on top match the trigger it hangs from.
                    .background(tokens.colors.background, placement.menuShape())
                    .ditheredBackground(SettingsSectionGlassFill, placement.menuShape())
                    .border(tokens.borders.thin, SettingsSectionGlassEdge, placement.menuShape())
                    .menuSeam(placement.openUp)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = if (placement.openUp) MenuEdgePadding else menuSeamPadding(isTablet),
                        bottom = if (placement.openUp) menuSeamPadding(isTablet) else MenuEdgePadding,
                    ),
                content = content,
            )
        }
    }
}

@Composable
internal fun <T> SettingsDropdownChoiceRow(
    title: String,
    description: String?,
    options: List<SettingsChoiceOption<T>>,
    selectedValue: T,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    enabled: Boolean = true,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    flushContent: Boolean = false,
    onSelected: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (flushContent) 0.dp else 16.dp
    val verticalPadding = if (isTablet) DesktopRowVerticalPadding else 12.dp
    val controlWidthModifier = if (isTablet) {
        Modifier.width(DesktopControlWidth)
    } else {
        Modifier.widthIn(min = 220.dp, max = 320.dp)
    }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsChoiceLeadingIcon(icon = icon, iconPainter = iconPainter, isTablet = isTablet)
        SettingsRowText(
            title = title,
            description = description,
            isTablet = isTablet,
            trailingInset = !flushContent,
            modifier = Modifier
                .weight(1f)
                .padding(end = if (flushContent || isTablet) 0.dp else 16.dp),
        )
        var anchorBounds by remember { mutableStateOf<Rect?>(null) }
        Box(
            modifier = controlWidthModifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() },
        ) {
            // Trigger and menu are one shape: whichever side the menu opens on, the two square the
            // edge they meet at and stay rounded everywhere else. See [SettingsMenuPanel] for why
            // that needs a Popup of our own rather than Material's DropdownMenu.
            val placement = rememberMenuPlacement(anchorBounds, options.size)
            SettingsDropdownTrigger(
                label = selectedLabel,
                labelColor = tokens.colors.textPrimary,
                expanded = expanded,
                placement = placement,
                enabled = enabled,
                isTablet = isTablet,
                onClick = { expanded = true },
            )
            SettingsMenuPanel(
                expanded = expanded,
                placement = placement,
                widthModifier = controlWidthModifier,
                isTablet = isTablet,
                onDismissRequest = { expanded = false },
            ) {
                // Rolled by hand rather than using DropdownMenuItem. Both the selection and the
                // hover are colour on the label here, and DropdownMenuItem paints a state layer
                // behind the row on hover that no styling of its slot can reach — that plate is
                // the "hover backdrop" this control is meant not to have. A plain clickable Row
                // with `indication = null` has no such layer.
                //
                // Hover and selection share a colour and separate on weight: only the selected
                // label is bold, so a pointer moving down the list never looks like it is changing
                // the setting.
                val markColor = tokens.colors.selectionTextColor()
                // A gradient accent has two stops and a Color can only carry the first, so the
                // mark has to go on as a brush or a themed sweep arrives flat. Null on the flat
                // themes and on the near-white accent that falls back to onAccent.
                val markBrush = tokens.colors.selectionTextBrush()
                val labelStyle = MaterialTheme.typography.bodyMedium
                val markStyle = markBrush?.let { labelStyle.copy(brush = it) } ?: labelStyle
                options.forEach { option ->
                    val selected = option.value == selectedValue
                    val interactionSource = remember { MutableInteractionSource() }
                    val hovered by interactionSource.collectIsHoveredAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) {
                                expanded = false
                                onSelected(option.value)
                            }
                            .padding(
                                horizontal = menuHorizontalPadding(isTablet),
                                vertical = MenuRowVerticalPadding,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val marked = selected || hovered
                        Text(
                            text = option.label,
                            // Centred on desktop, under the centred value in the trigger.
                            modifier = if (isTablet) Modifier.fillMaxWidth() else Modifier,
                            style = if (marked) markStyle else labelStyle,
                            color = if (marked) markColor else tokens.colors.textPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = if (isTablet) TextAlign.Center else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A dropdown's closed face, in the settings glass: the card fill and edge, and a plain chevron that
 * turns over while the menu is open (the same chevron the section headings use). Squares the edge
 * the open menu is joined to, see [MenuPlacement].
 */
@Composable
private fun SettingsDropdownTrigger(
    label: String,
    labelColor: Color,
    expanded: Boolean,
    placement: MenuPlacement,
    enabled: Boolean,
    isTablet: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = placement.triggerShape(expanded)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = MenuEnterMillis),
    )
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .ditheredBackground(SettingsSectionGlassFill, shape)
            .border(tokens.borders.thin, SettingsSectionGlassEdge, shape)
            // Clipped after the edge: a clip is not anti-aliased here and stair-steps an edge drawn inside it.
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = menuHorizontalPadding(isTablet),
                vertical = triggerVerticalPadding(isTablet) + DropdownTriggerExtraPadding,
            ),
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier
                    .size(DropdownChevronSize)
                    .rotate(chevronRotation),
            )
        },
    ) { measurables, constraints ->
        // The label is centred across the whole control, like the On/Off labels beside it, and
        // only gives way (sliding left) when centring would run it into the chevron.
        val chevron = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val reserve = chevron.width + DropdownChevronGap.roundToPx()
        val width = constraints.maxWidth
        val text = measurables[0].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (width - reserve).coerceAtLeast(0)),
        )
        val height = maxOf(text.height, chevron.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val centred = (width - text.width) / 2
            val latest = width - reserve - text.width
            text.place(minOf(centred, latest).coerceAtLeast(0), (height - text.height) / 2)
            chevron.place(width - chevron.width, (height - chevron.height) / 2)
        }
    }
}

private val DropdownChevronSize = 20.dp
private val DropdownChevronGap = 6.dp

/** Keeps the trigger at the old height now that the boxed arrow no longer props it up. */
private val DropdownTriggerExtraPadding = 2.dp


@Composable
private fun SettingsChoiceLeadingIcon(
    icon: ImageVector?,
    iconPainter: Painter?,
    isTablet: Boolean,
) {
    if (icon == null && iconPainter == null) return
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .size(if (isTablet) 34.dp else 36.dp)
            .background(tokens.colors.accentFill(tokens.opacity.pressed), tokens.shapes.compactCard),
        color = Color.Transparent,
        shape = tokens.shapes.compactCard,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (iconPainter != null) {
                androidx.compose.foundation.Image(
                    painter = iconPainter,
                    contentDescription = null,
                    modifier = Modifier.size(if (isTablet) 22.dp else 24.dp),
                    contentScale = ContentScale.Fit,
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.accentGradientMask(),
                )
            }
        }
    }
    Spacer(modifier = Modifier.width(if (isTablet) 10.dp else 14.dp))
}

/**
 * A dropdown that takes several answers at once.
 *
 * Deliberately styled as the single-select [SettingsDropdownChoiceRow] — same trigger, same flattened
 * corners while open — because the only difference the user should perceive is that the menu stays
 * open and the ticks accumulate. Two differences that are not styling:
 *
 * - **The menu does not close on click.** Picking three genres should be three clicks, not three
 *   round trips through a collapsing menu.
 * - **The trigger summarises rather than lists.** Past [maxSummaryItems] the label becomes a count;
 *   a trigger that ellipsises "Action, Adventure, Comedy, Cri…" tells the user nothing about what
 *   is selected beyond the first two.
 *
 * The menu is height-capped: a list as long as the genre vocabulary will otherwise run off a short
 * window with no way to reach the bottom of it.
 */
@Composable
internal fun <T> SettingsMultiSelectRow(
    title: String,
    description: String?,
    options: List<SettingsChoiceOption<T>>,
    selectedValues: Set<T>,
    emptyLabel: String,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxSummaryItems: Int = 2,
    summaryForCount: @Composable (Int) -> String = { "$it" },
    onToggle: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val verticalPadding = if (isTablet) DesktopRowVerticalPadding else 12.dp
    val controlWidthModifier = if (isTablet) {
        Modifier.width(DesktopControlWidth)
    } else {
        Modifier.widthIn(min = 220.dp, max = 320.dp)
    }
    var expanded by remember { mutableStateOf(false) }
    val selectedLabels = options.filter { it.value in selectedValues }.map { it.label }
    val summary = when {
        selectedLabels.isEmpty() -> emptyLabel
        selectedLabels.size <= maxSummaryItems -> selectedLabels.joinToString(", ")
        else -> summaryForCount(selectedLabels.size)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = verticalPadding)
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowText(
            title = title,
            description = description,
            isTablet = isTablet,
            trailingInset = true,
            modifier = Modifier
                .weight(1f)
                .padding(end = if (isTablet) 0.dp else 16.dp),
        )
        var anchorBounds by remember { mutableStateOf<Rect?>(null) }
        Box(
            modifier = controlWidthModifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() },
        ) {
            // Same treatment as the single-choice menu above: one joined shape and accent outline.
            val placement = rememberMenuPlacement(anchorBounds, options.size)
            SettingsDropdownTrigger(
                label = summary,
                labelColor = if (selectedLabels.isEmpty()) tokens.colors.textMuted else tokens.colors.textPrimary,
                expanded = expanded,
                placement = placement,
                enabled = enabled,
                isTablet = isTablet,
                onClick = { expanded = true },
            )
            SettingsMenuPanel(
                expanded = expanded,
                placement = placement,
                widthModifier = controlWidthModifier,
                isTablet = isTablet,
                onDismissRequest = { expanded = false },
            ) {
                val markColor = tokens.colors.selectionTextColor()
                val markBrush = tokens.colors.selectionTextBrush()
                val labelStyle = MaterialTheme.typography.bodyMedium
                val markStyle = markBrush?.let { labelStyle.copy(brush = it) } ?: labelStyle
                options.forEach { option ->
                    val selected = option.value in selectedValues
                    val interactionSource = remember { MutableInteractionSource() }
                    val hovered by interactionSource.collectIsHoveredAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // No `expanded = false` here — see the note above.
                            .clickable(interactionSource = interactionSource, indication = null) {
                                onToggle(option.value)
                            }
                            .padding(
                                horizontal = menuHorizontalPadding(isTablet),
                                vertical = MenuRowVerticalPadding,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val marked = selected || hovered
                        Text(
                            text = option.label,
                            modifier = Modifier.weight(1f),
                            style = if (marked) markStyle else labelStyle,
                            color = if (marked) markColor else tokens.colors.textPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // A multi-select cannot lean on weight alone the way the single-choice
                        // menu does — several rows are "on" at once, and a column of bold labels
                        // does not read as a set of ticked boxes.
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                // Icon takes a Color, so the gradient reaches it by mask instead.
                                // Gated on the same condition as the label's brush: on a gradient
                                // theme whose accent is too pale to mark text, the label falls
                                // back to onAccent and a swept tick beside it would not match.
                                tint = markColor,
                                modifier = Modifier
                                    .size(18.dp)
                                    .then(if (markBrush != null) Modifier.accentGradientMask() else Modifier),
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SettingsRowText(
    title: String,
    description: String?,
    isTablet: Boolean,
    trailingInset: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val titleColor = settingsRowTitleColor(title)
    val titleAccentMask = settingsRowTitleAccentMask(title)
    Column(
        // Inset the text on the trailing side so long descriptions wrap with a comfortable gap
        // before the selector. Must be padding, not widthIn — see SettingsRowTextGap. Dropdown and
        // segmented rows route through here, so applying the shared gap keeps them wrapping at the
        // same column as the switch rows rather than running closer to their control.
        modifier = modifier
            .padding(end = if (isTablet && trailingInset) SettingsRowTextGap else 0.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTablet) 2.dp else 4.dp),
    ) {
        Text(
            text = title,
            modifier = titleAccentMask,
            style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = titleColor,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!description.isNullOrBlank()) {
            SettingsSubtext(
                text = description,
                isTablet = isTablet,
            )
        }
    }
}

@Composable
internal fun HomescreenCatalogRow(
    item: HomeCatalogSettingsItem,
    isTablet: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
    onMarkerColorChange: (HomeCatalogMarkerColor?) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSendToTop: () -> Unit,
    onPinnedDragAttempt: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 18.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandedChange(!expanded) }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (isTablet) SettingsRowTextGap else 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item.isCollection) {
                        stringResource(Res.string.settings_homescreen_collection_with_addon, item.addonName)
                    } else {
                        item.addonName
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                Text(
                    text = buildString {
                        append(
                            if (item.enabled) {
                                stringResource(Res.string.settings_homescreen_visible)
                            } else {
                                stringResource(Res.string.settings_homescreen_hidden)
                            },
                        )
                        if (item.isCollection) {
                            if (item.isPinnedToTop) {
                                append(" • ")
                                append(stringResource(Res.string.settings_homescreen_pinned_to_top))
                            }
                        } else {
                            append(" • ")
                            append(
                                if (item.heroSourceEnabled) {
                                    stringResource(Res.string.settings_homescreen_hero_source)
                                } else {
                                    stringResource(Res.string.settings_homescreen_not_in_hero)
                                },
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsSquareSwitch(
                    checked = item.enabled,
                    onCheckedChange = onEnabledChange,
                )
                if (item.isPinnedToTop) {
                    IconButton(
                        onClick = onPinnedDragAttempt,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = stringResource(Res.string.settings_homescreen_pinned),
                            tint = tokens.colors.textMuted.copy(alpha = tokens.opacity.medium),
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSendToTop,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = stringResource(Res.string.cd_send_to_top),
                            tint = tokens.colors.textMuted,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded && !item.isCollection) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsTextInputRow(
                    title = stringResource(Res.string.settings_homescreen_display_name),
                    description = item.defaultTitle,
                    value = item.customTitle,
                    placeholder = item.defaultTitle,
                    isTablet = isTablet,
                    onSave = onTitleChange,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.settings_homescreen_bookmark_color),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.settings_homescreen_bookmark_color_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CatalogMarkerColorOption(
                            markerColor = null,
                            selected = item.markerColor == null,
                            contentDescription = stringResource(
                                Res.string.settings_homescreen_bookmark_color_none,
                            ),
                            onClick = { onMarkerColorChange(null) },
                        )
                        HomeCatalogMarkerColor.entries.forEach { markerColor ->
                            CatalogMarkerColorOption(
                                markerColor = markerColor,
                                selected = item.markerColor == markerColor,
                                contentDescription =
                                    "${stringResource(Res.string.settings_homescreen_bookmark_color)}: " +
                                        markerColor.accessibleName,
                                onClick = { onMarkerColorChange(markerColor) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogMarkerColorOption(
    markerColor: HomeCatalogMarkerColor?,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val swatchColor = markerColor?.composeColor ?: tokens.colors.surface
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(swatchColor)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) {
                        tokens.colors.borderFocus
                    } else {
                        tokens.colors.borderDefault.copy(alpha = tokens.opacity.strong)
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (markerColor == null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(7.dp),
                ) {
                    drawLine(
                        color = tokens.colors.textMuted,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            } else if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = if (markerColor.prefersDarkForeground) {
                        Color.Black.copy(alpha = 0.78f)
                    } else {
                        Color.White
                    },
                )
            }
        }
    }
}
