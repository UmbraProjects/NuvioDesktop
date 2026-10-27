package com.nuvio.app.features.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSweepHighlight

private val WizardRowShape = RoundedCornerShape(NuvioTokens.Radius.md)

/**
 * The panel's buttons — the compact counterparts of [com.nuvio.app.core.ui.NuvioPrimaryButton],
 * which fills its width. Same treatment: the accent fill painted directly, no Material state layer,
 * and focus or hover signalled with one pass of the poster sweep.
 */
@Composable
internal fun WizardPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 120.dp)
            .nuvioSweepHighlight(
                highlighted = enabled && (isFocused || isHovered),
                cornerRadius = NuvioTokens.Radius.button,
            )
            .clip(tokens.shapes.button)
            .background(brush = tokens.colors.accentFill, shape = tokens.shapes.button)
            .alpha(if (enabled) 1f else tokens.opacity.disabled)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = NuvioTokens.Space.s20, vertical = NuvioTokens.Space.s10),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.onAccent,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** A text action in the footer (Skip, Back, Finish later): quiet until focused or hovered. */
@Composable
internal fun WizardTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val active = interactionSource.isActive()
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            active -> tokens.colors.textPrimary
            emphasised -> tokens.colors.textSecondary
            else -> tokens.colors.textMuted
        },
        fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier
            .wizardFocusFrame(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s6),
    )
}

/** Small capitalised heading between groups of rows inside a step. */
@Composable
internal fun WizardSectionLabel(text: String, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = tokens.colors.textMuted,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = NuvioTokens.Space.s8, start = NuvioTokens.Space.s4),
    )
}

/**
 * One choice of a single-select list. Picking it writes the setting straight away, so Home behind
 * the panel changes as the user moves through the list with Enter, A or the mouse.
 */
@Composable
internal fun WizardOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    badge: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val fill by animateColorAsState(
        if (selected) tokens.colors.accent.copy(alpha = 0.16f) else Color.Transparent,
        label = "wizard_option_fill",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .wizardFocusFrame(interactionSource)
            .background(fill, WizardRowShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** A setting with an on/off answer; the whole row flips it, as in Settings. */
@Composable
internal fun WizardToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .wizardFocusFrame(interactionSource)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        WizardSwitchTrack(checked = checked)
    }
}

@Composable
private fun WizardSwitchTrack(checked: Boolean) {
    val tokens = MaterialTheme.nuvio
    val track by animateColorAsState(
        if (checked) tokens.colors.accent else Color.White.copy(alpha = 0.14f),
        label = "wizard_switch_track",
    )
    val knobOffset by animateDpAsState(if (checked) 18.dp else 2.dp, label = "wizard_switch_knob")
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 22.dp)
            .background(track, CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .size(18.dp)
                .background(if (checked) tokens.colors.onAccent else tokens.colors.textSecondary, CircleShape),
        )
    }
}

/**
 * A numeric setting stepped in fixed increments: Left/Right on the focused row (or the D-pad), or
 * the chevrons with the mouse. A slider would need Left/Right for itself and could not be reached
 * at all from a gamepad without a separate "grab" mode.
 */
@Composable
internal fun WizardStepperRow(
    title: String,
    valueLabel: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .wizardFocusFrame(interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (canDecrease) onDecrease()
                        true
                    }
                    Key.DirectionRight -> {
                        if (canIncrease) onIncrease()
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interactionSource)
            .hoverable(interactionSource)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StepperChevron(enabled = canDecrease, increase = false, onClick = onDecrease)
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            // Sized for the longest preset names ("Comfort", "Extra Large") on one line; they
            // wrapped mid-word in a fixed 52dp.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 52.dp, max = 104.dp),
        )
        StepperChevron(enabled = canIncrease, increase = true, onClick = onIncrease)
    }
}

@Composable
private fun StepperChevron(enabled: Boolean, increase: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.03f))
            // Not focusable: the row owns focus and takes Left/Right itself, so the chevrons are
            // mouse targets only and do not add two extra stops to every stepper.
            .focusProperties { canFocus = false }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (increase) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
            contentDescription = null,
            tint = if (enabled) tokens.colors.textPrimary else tokens.colors.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A round colour sample for the theme step; the ring marks the current theme. */
@Composable
internal fun WizardSwatch(
    color: Color,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val active = interactionSource.isActive()
    val ring = when {
        selected -> tokens.colors.textPrimary
        active -> tokens.colors.textSecondary
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .border(BorderStroke(2.dp, ring), CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = contentDescription,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A framed block for the panel's inline notes. */
@Composable
internal fun WizardNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(WizardRowShape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(BorderStroke(1.dp, NuvioGlass.Edge), WizardRowShape)
            .padding(NuvioTokens.Space.s12),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textSecondary,
        )
    }
}

/** A hairline between rows of one group. */
@Composable
internal fun WizardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s12)
            .height(1.dp)
            .background(NuvioGlass.EdgeColor),
    )
}

/**
 * The panel's focus and hover marker: a soft fill on hover, and an accent outline once the row
 * holds keyboard focus. Without the outline a gamepad user could not tell which row Enter acts on.
 */
@Composable
private fun Modifier.wizardFocusFrame(interactionSource: MutableInteractionSource): Modifier {
    val tokens = MaterialTheme.nuvio
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val border by animateColorAsState(
        if (focused) tokens.colors.accent else Color.Transparent,
        label = "wizard_focus_border",
    )
    val fill by animateColorAsState(
        if (hovered || focused) Color.White.copy(alpha = 0.06f) else Color.Transparent,
        label = "wizard_focus_fill",
    )
    return this
        .clip(WizardRowShape)
        .background(fill, WizardRowShape)
        .border(BorderStroke(1.5.dp, border), WizardRowShape)
}

/** Focused or hovered: either way, the control the next Enter or click lands on. */
@Composable
private fun MutableInteractionSource.isActive(): Boolean {
    val focused by collectIsFocusedAsState()
    val hovered by collectIsHoveredAsState()
    return focused || hovered
}
