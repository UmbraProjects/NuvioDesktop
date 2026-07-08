package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_action_off
import nuvio.composeapp.generated.resources.compose_action_on
import nuvio.composeapp.generated.resources.settings_homescreen_collection_with_addon
import nuvio.composeapp.generated.resources.settings_homescreen_display_name
import nuvio.composeapp.generated.resources.settings_homescreen_hero_source
import nuvio.composeapp.generated.resources.settings_homescreen_hidden
import nuvio.composeapp.generated.resources.settings_homescreen_not_in_hero
import nuvio.composeapp.generated.resources.settings_homescreen_pinned
import nuvio.composeapp.generated.resources.settings_homescreen_pinned_to_top
import nuvio.composeapp.generated.resources.settings_homescreen_reorder
import nuvio.composeapp.generated.resources.settings_homescreen_visible
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope

internal data class SettingsChoiceOption<T>(
    val value: T,
    val label: String,
)

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
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun valueFromX(x: Float): Float {
        val width = size.width.coerceAtLeast(1).toFloat()
        val fraction = (x / width).coerceIn(0f, 1f)
        val raw = valueRange.start + (valueRange.endInclusive - valueRange.start) * fraction
        if (steps <= 0) return raw.coerceIn(valueRange.start, valueRange.endInclusive)
        val totalIntervals = steps + 1
        val snappedFraction = (fraction * totalIntervals).toInt()
            .coerceIn(0, totalIntervals)
            .toFloat() / totalIntervals
        return (valueRange.start + (valueRange.endInclusive - valueRange.start) * snappedFraction)
            .coerceIn(valueRange.start, valueRange.endInclusive)
    }

    val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val progress = if (valueRange.endInclusive == valueRange.start) {
        0f
    } else {
        ((coercedValue - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    }
    val activeColor = tokens.colors.accent
    val inactiveColor = tokens.colors.borderDefault.copy(alpha = 0.72f)

    Box(
        modifier = modifier
            .height(28.dp)
            .alpha(if (enabled) 1f else tokens.opacity.medium)
            .onGloballyPositioned { size = it.size }
            .pointerInput(enabled, valueRange, steps, size) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragEnd = onValueChangeFinished,
                    onDragCancel = onValueChangeFinished,
                    onDragStart = { offset -> onValueChange(valueFromX(offset.x)) },
                    onDrag = { change, _ -> onValueChange(valueFromX(change.position.x)) },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
            val centerY = size.height / 2f
            val thumbRadius = 9.dp.toPx()
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
                color = activeColor,
                start = start,
                end = Offset(thumbX, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = activeColor,
                radius = thumbRadius,
                center = Offset(thumbX, centerY),
            )
        }
    }
}

/** Fixed width shared by dropdown and segmented controls so every picker on the desktop
 * settings pages lines up, regardless of control type. */
private val DesktopControlWidth = 210.dp

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
    val primary = tokens.colors.accent
    val background = if (selected) primary.copy(alpha = tokens.opacity.hover) else Color.Transparent
    val iconChip = if (selected) primary.copy(alpha = tokens.opacity.selected) else Color.Transparent
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
            modifier = Modifier.size(tokens.icons.xl),
            color = iconChip,
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
                    tint = if (selected) primary else contentColor,
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

@Composable
internal fun SettingsSection(
    title: String,
    isTablet: Boolean,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    if (isTablet) {
        // Edge-to-edge desktop header: a real heading sitting directly on the page
        // background, not a small boxed label — rows below flow with no card wrapper.
        val page = LocalSettingsPage.current
        // Stable id for this heading, used both to scroll here from a pinned favorite and as the
        // favorite's identity. Empty string when there's no page context (favoriting disabled).
        val anchor = if (page != null) "heading:${page.name}:$title" else ""
        val highlight = rememberSettingsAnchorHighlight(anchor)
        val titleColor = if (highlight.highlighted) tokens.colors.accent else tokens.colors.textPrimary
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Text(
                        text = title,
                        style = MaterialTheme.nuvioTypeScale.titleSm,
                        color = titleColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = highlight.modifier.then(
                            if (page != null) {
                                // Right-click the heading to pin it as a favorite.
                                Modifier.secondaryClick {
                                    SettingsFavoritesRepository.add(
                                        SettingsFavorite(page = page.name, anchor = anchor, title = title),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                    )
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
            modifier = Modifier.fillMaxWidth(),
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
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.textMuted,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                .padding(end = 12.dp)
                .widthIn(max = if (isTablet) 560.dp else Dp.Unspecified),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null || iconPainter != null) {
                Surface(
                    modifier = Modifier.size(iconSize),
                    color = tokens.colors.accent.copy(alpha = tokens.opacity.pressed),
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
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(if (isTablet) 10.dp else 14.dp))
            }
            Column {
                Text(
                    text = title,
                    style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(if (isTablet) 1.dp else 2.dp))
                Text(
                    text = description,
                    style = if (isTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.alpha(0.92f),
                )
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
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - thumbInset else thumbInset,
        label = "settingsSquareSwitchThumb",
    )
    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium)
            .clip(trackShape)
            .background(trackColor)
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
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
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
                modifier = Modifier.size(if (isTablet) 34.dp else 36.dp),
                color = tokens.colors.accent.copy(alpha = tokens.opacity.pressed),
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
                    )
                }
            }
            Spacer(modifier = Modifier.width(if (isTablet) 10.dp else 14.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .widthIn(max = if (isTablet) 560.dp else Dp.Unspecified)
                .alpha(if (enabled) NuvioTokens.Opacity.visible else tokens.opacity.medium),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = if (isTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
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
    description: String,
    options: List<SettingsChoiceOption<T>>,
    selectedValue: T,
    enabled: Boolean = true,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
    onMoreOptionsClick: (() -> Unit)? = null,
) {
    if (options.size == 2) {
        SettingsSegmentedChoiceRow(
            title = title,
            description = description,
            options = options,
            selectedValue = selectedValue,
            enabled = enabled,
            isTablet = isTablet,
            modifier = modifier,
            onSelected = onSelected,
        )
    } else {
        SettingsDropdownChoiceRow(
            title = title,
            description = description,
            options = options,
            selectedValue = selectedValue,
            enabled = enabled,
            isTablet = isTablet,
            modifier = modifier,
            onSelected = onSelected,
        )
    }
}

@Composable
internal fun <T> SettingsSegmentedChoiceRow(
    title: String,
    description: String,
    options: List<SettingsChoiceOption<T>>,
    selectedValue: T,
    enabled: Boolean = true,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (isTablet) 16.dp else 16.dp
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
        SettingsRowText(
            title = title,
            description = description,
            isTablet = isTablet,
            modifier = Modifier
                .weight(1f)
                .padding(end = if (isTablet) 12.dp else 16.dp),
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
private fun <T> SettingsSegmentedControl(
    options: List<SettingsChoiceOption<T>>,
    selectedValue: T,
    enabled: Boolean,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .background(tokens.colors.surfaceCard, RoundedCornerShape(NuvioTokens.Radius.md))
            .border(tokens.borders.hairline, tokens.colors.borderSubtle, RoundedCornerShape(NuvioTokens.Radius.md))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.take(2).forEach { option ->
            val selected = option.value == selectedValue
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) tokens.colors.accent else Color.Transparent,
                        RoundedCornerShape(NuvioTokens.Radius.sm),
                    )
                    .clickable(enabled = enabled) { onSelected(option.value) }
                    .padding(horizontal = if (isTablet) 10.dp else 12.dp, vertical = if (isTablet) 6.dp else 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) tokens.colors.onAccent else tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun <T> SettingsDropdownChoiceRow(
    title: String,
    description: String,
    options: List<SettingsChoiceOption<T>>,
    selectedValue: T,
    enabled: Boolean = true,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (isTablet) 16.dp else 16.dp
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
        SettingsRowText(
            title = title,
            description = description,
            isTablet = isTablet,
            modifier = Modifier
                .weight(1f)
                .padding(end = if (isTablet) 12.dp else 16.dp),
        )
        Box(
            modifier = controlWidthModifier,
        ) {
            // While expanded, flatten the trigger's bottom corners and the menu's top
            // corners so they read as one cohesive control instead of two floating cards.
            val triggerShape = RoundedCornerShape(
                topStart = NuvioTokens.Radius.md,
                topEnd = NuvioTokens.Radius.md,
                bottomStart = if (expanded) 0.dp else NuvioTokens.Radius.md,
                bottomEnd = if (expanded) 0.dp else NuvioTokens.Radius.md,
            )
            val menuShape = RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomStart = NuvioTokens.Radius.md,
                bottomEnd = NuvioTokens.Radius.md,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.colors.surfaceCard, triggerShape)
                    .border(tokens.borders.thin, tokens.colors.borderDefault, triggerShape)
                    .clickable(enabled = enabled) { expanded = true }
                    .padding(horizontal = if (isTablet) 12.dp else 14.dp, vertical = if (isTablet) 7.dp else 10.dp),
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
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = controlWidthModifier,
                shape = menuShape,
                containerColor = tokens.colors.surfaceCard,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(tokens.borders.thin, tokens.colors.borderDefault),
            ) {
                options.forEach { option ->
                    val selected = option.value == selectedValue
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) tokens.colors.accent else tokens.colors.textPrimary,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(option.value)
                        },
                        modifier = Modifier.background(
                            if (selected) {
                                tokens.colors.accent.copy(alpha = tokens.opacity.selected)
                            } else {
                                Color.Transparent
                            },
                        ),
                    )
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
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.widthIn(max = if (isTablet) 560.dp else Dp.Unspecified),
        verticalArrangement = Arrangement.spacedBy(if (isTablet) 2.dp else 4.dp),
    ) {
        Text(
            text = title,
            style = if (isTablet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = if (isTablet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
    onEnabledChange: (Boolean) -> Unit,
    onSendToTop: () -> Unit,
    dragHandleScope: ReorderableCollectionItemScope,
    onPinnedDragAttempt: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 18.dp else 16.dp
    val hapticFeedback = LocalHapticFeedback.current

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
                    .padding(end = 12.dp)
                    .then(if (isTablet) Modifier.widthIn(max = 560.dp) else Modifier),
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
                            contentDescription = "Send to top",
                            tint = tokens.colors.textMuted,
                        )
                    }
                    IconButton(
                        modifier = with(dragHandleScope) {
                            Modifier.draggableHandle(
                                onDragStarted = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                            )
                        },
                        onClick = {},
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = stringResource(Res.string.settings_homescreen_reorder),
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
                OutlinedTextField(
                    value = item.customTitle,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(Res.string.settings_homescreen_display_name)) },
                    placeholder = { Text(item.defaultTitle) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.colors.borderFocus.copy(alpha = tokens.opacity.strong),
                        unfocusedBorderColor = tokens.colors.borderDefault.copy(alpha = tokens.opacity.medium),
                        focusedContainerColor = tokens.colors.surface,
                        unfocusedContainerColor = tokens.colors.surface,
                        disabledContainerColor = tokens.colors.surface,
                    ),
                )
            }
        }
    }
}
