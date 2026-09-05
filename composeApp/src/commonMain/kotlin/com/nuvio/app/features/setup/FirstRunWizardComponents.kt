package com.nuvio.app.features.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSweepHighlight

/**
 * The wizard's buttons — the compact counterparts of [com.nuvio.app.core.ui.NuvioPrimaryButton],
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
            .defaultMinSize(minWidth = 180.dp)
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
            .padding(horizontal = NuvioTokens.Space.s28, vertical = NuvioTokens.Space.s14),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.onAccent,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** Outlined counterpart, for secondary actions inside a step (currently "Add addon"). */
@Composable
internal fun WizardButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val borderColor by animateColorAsState(
        if (enabled) tokens.colors.accent else tokens.colors.borderSubtle,
        label = "wizard_button_border",
    )
    Box(
        modifier = modifier
            .clip(tokens.shapes.button)
            .border(BorderStroke(tokens.borders.hairline, borderColor), tokens.shapes.button)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = NuvioTokens.Space.s20, vertical = NuvioTokens.Space.s14),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) tokens.colors.accent else tokens.colors.textMuted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A framed block used for the wizard's inline notes (empty-sources warning, debrid pointer). */
@Composable
internal fun WizardNote(
    text: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val border = if (emphasised) tokens.colors.accent.copy(alpha = 0.5f) else tokens.colors.borderSubtle
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(tokens.colors.surface)
            .border(BorderStroke(tokens.borders.hairline, border), RoundedCornerShape(NuvioTokens.Radius.md))
            .padding(NuvioTokens.Space.s12),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textSecondary,
        )
    }
}
