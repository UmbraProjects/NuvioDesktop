package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
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

/** One-line settings help text whose full value remains available from a mouse hover. */
@Composable
internal fun SettingsSubtext(
    text: String,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.nuvio.colors.textMuted,
) {
    var hasOverflow by remember(text) { mutableStateOf(false) }
    NuvioPosterHoverTooltip(
        title = text.takeIf { hasOverflow }.orEmpty(),
        modifier = modifier,
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = if (isTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
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
    return if (highlight?.title == title) Modifier.accentGradientMask() else Modifier
}

@Composable
private fun settingsRowTitleColor(title: String): Color {
    val highlight by SettingsScrollAnchor.titleHighlight.collectAsStateWithLifecycle()
    return if (highlight?.title == title) {
        MaterialTheme.nuvio.colors.accent
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
) {
    val tokens = MaterialTheme.nuvio
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
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
                    onDragEnd = { currentOnValueChangeFinished() },
                    onDragCancel = { currentOnValueChangeFinished() },
                    onDragStart = { offset ->
                        val pressed = valueFromX(offset.x)
                        draggingHighThumb =
                            abs(pressed - currentHigh) <= abs(pressed - currentLow)
                        reportDragTo(offset.x)
                    },
                    onDrag = { change, _ ->
                        change.consume()
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
            drawLine(
                color = inactiveColor,
                start = Offset(trackStart, centerY),
                end = Offset(trackEnd, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Only the span between the thumbs is active: that band is the setting.
            drawLine(
                brush = activeBrush,
                start = Offset(lowX, centerY),
                end = Offset(highX, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(brush = activeBrush, radius = thumbRadius, center = Offset(lowX, centerY))
            drawCircle(brush = activeBrush, radius = thumbRadius, center = Offset(highX, centerY))
        }
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
) {
    val tokens = MaterialTheme.nuvio
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
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
                    onDragEnd = { currentOnValueChangeFinished() },
                    onDragCancel = { currentOnValueChangeFinished() },
                    onDragStart = { offset -> currentOnValueChange(valueFromX(offset.x)) },
                    onDrag = { change, _ ->
                        change.consume()
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
            val start = Offset(trackStart, centerY)
            val end = Offset(trackEnd, centerY)
            drawLine(
                color = inactiveColor,
                start = start,
                end = end,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                brush = activeBrush,
                start = start,
                end = Offset(thumbX, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                brush = activeBrush,
                radius = thumbRadius,
                center = Offset(thumbX, centerY),
            )
        }
    }
}

/** Fixed width shared by dropdown and segmented controls so every picker on the desktop
 * settings pages lines up, regardless of control type. */
private val DesktopControlWidth = 210.dp

// Trailing inset on the title/description column, shared by every desktop settings row so they
// all wrap at the same column. This is the ONLY lever that moves the wrap point: the text column
// is laid out with Modifier.weight(1f), whose default fill=true pins it to a fixed slot width, and
// a widthIn(max=...) inside a fixed-width constraint is silently ignored. Raise this to wrap
// sooner; a max-width token here would do nothing.
internal val SettingsRowTextGap = 120.dp

/** Vertical padding shared by every desktop settings row (switch, navigation, dropdown,
 * segmented) so row spacing no longer depends on which control type it hosts. Based on the
 * dropdown/segmented row spacing (8.dp), +10% for a little extra breathing room. */
private val DesktopRowVerticalPadding = 8.8.dp

@Composable
private fun SettingsCard(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Desktop is edge-to-edge: no card surface, no border, rows flow directly on the
    // page background and are separated only by SettingsGroupDivider hairlines.
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
    HorizontalDivider(
        modifier = Modifier.padding(start = if (isTablet) 0.dp else NuvioTokens.Space.s64 + NuvioTokens.Space.s2),
        thickness = tokens.borders.hairline,
        color = tokens.colors.borderSubtle,
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val displayTitle = settingsTitleCase(title)
    val sectionHighlight = rememberSettingsAnchorHighlight(SettingsScrollAnchor.section(title))
    if (isTablet) {
        // Edge-to-edge desktop header: a real heading sitting directly on the page
        // background, not a small boxed label — rows below flow with no card wrapper.
        val page = LocalSettingsPage.current
        // Stable id for this heading, used both to scroll here from a pinned favorite and as the
        // favorite's identity. Empty string when there's no page context (favoriting disabled).
        val anchor = if (page != null) "heading:${page.name}:$title" else ""
        val highlight = rememberSettingsAnchorHighlight(anchor)
        // The heading turns accent while it is the search or favourite target, so it follows a
        // gradient accent for the same reason a filled accent surface does. Masked rather than
        // coloured because Text takes a Color and cannot express a Brush; at most one heading is
        // highlighted at a time, so the offscreen layer the mask costs is paid once.
        val titleHighlighted = highlight.highlighted || sectionHighlight.highlighted
        val titleColor = if (titleHighlighted) {
            tokens.colors.accent
        } else {
            tokens.colors.textPrimary
        }
        val titleAccentMask = if (titleHighlighted) Modifier.accentGradientMask() else Modifier
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
            content()
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
    }
}

/** Square toggle used in place of Material3's pill-shaped [Switch] across the settings pages,
 * to match the desktop redesign's squared-off control language. */
@Composable
internal fun SettingsSquareSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    val trackWidth = 38.dp
    val trackHeight = 20.dp
    val thumbSize = 14.dp
    val thumbInset = 3.dp
    val trackShape = RoundedCornerShape(NuvioTokens.Radius.xs)
    val thumbShape = RoundedCornerShape(3.dp)
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
    val controlShape = RoundedCornerShape(NuvioTokens.Radius.md)
    Row(
        modifier = modifier
            .background(tokens.colors.surfaceCard, controlShape)
            .border(
                tokens.borders.thin,
                tokens.colors.accentFill(tokens.opacity.overlayLight),
                controlShape,
            )
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.take(2).forEach { option ->
            val selected = option.value == selectedValue
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) tokens.colors.accentFill else SolidColor(Color.Transparent),
                        RoundedCornerShape(NuvioTokens.Radius.sm),
                    )
                    .clickable(enabled = enabled) { onSelected(option.value) }
                    .padding(horizontal = if (isTablet) 10.dp else 12.dp, vertical = if (isTablet) 6.dp else 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A menu never shrinks below this, however cramped the anchor is; it scrolls instead. */
private val MenuMinHeight = 160.dp

/**
 * Inset shared by a dropdown's trigger and its menu rows.
 *
 * One definition for both, because they are one shape: a menu row indented differently from the
 * label above it makes the seam read as two controls that happen to be touching.
 */
private fun menuHorizontalPadding(isTablet: Boolean): Dp = if (isTablet) 12.dp else 14.dp

/** Vertical padding either side of a menu row's label. */
private val MenuRowVerticalPadding = 10.dp

/** Vertical padding that keeps dropdown triggers level with [SettingsSegmentedControl]. */
private fun triggerVerticalPadding(isTablet: Boolean): Dp = if (isTablet) 6.dp else 9.dp

/** Padding on the menu's free edge, away from the trigger. */
private val MenuEdgePadding = 6.dp

/** Thickness of the accent rule along the seam. */
private val MenuSeamAccentThickness = 2.dp

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
 * The accent rule along the seam, dividing the options from the value the trigger already shows.
 *
 * Painted onto the panel rather than added as a row so it stays pinned to the joined edge while a
 * long list scrolls beneath it, and taken as a brush so a gradient accent sweeps across it instead
 * of arriving as its first stop alone. The seam edge is the one the panel squares off, so a
 * full-bleed rule meets both corners without fighting the rounding.
 */
private fun Modifier.menuSeamAccent(accent: Brush, openUp: Boolean): Modifier = drawWithContent {
    drawContent()
    val thickness = MenuSeamAccentThickness.toPx()
    drawRect(
        brush = accent,
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
        val round = NuvioTokens.Radius.md
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
                    .background(tokens.colors.surfaceCard, placement.menuShape())
                    .menuSeamAccent(tokens.colors.accentFill, placement.openUp)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.colors.surfaceCard, placement.triggerShape(expanded))
                    .border(
                        tokens.borders.thin,
                        tokens.colors.accentFill(tokens.opacity.overlayLight),
                        placement.triggerShape(expanded),
                    )
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(
                        horizontal = menuHorizontalPadding(isTablet),
                        vertical = triggerVerticalPadding(isTablet),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SettingsDropdownArrow()
            }
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
                            style = if (marked) markStyle else labelStyle,
                            color = if (marked) markColor else tokens.colors.textPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDropdownArrow() {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.sm)
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(tokens.colors.surfaceCard, shape)
            .border(
                tokens.borders.thin,
                tokens.colors.accentFill(tokens.opacity.overlayLight),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.ArrowDropDown,
            contentDescription = null,
            tint = tokens.colors.accent,
            modifier = Modifier
                .size(18.dp)
                .accentGradientMask(),
        )
    }
}

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.colors.surfaceCard, placement.triggerShape(expanded))
                    .border(
                        tokens.borders.thin,
                        tokens.colors.accentFill(tokens.opacity.overlayLight),
                        placement.triggerShape(expanded),
                    )
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(
                        horizontal = menuHorizontalPadding(isTablet),
                        vertical = triggerVerticalPadding(isTablet),
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectedLabels.isEmpty()) tokens.colors.textMuted else tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SettingsDropdownArrow()
            }
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
