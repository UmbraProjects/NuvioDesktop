package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.smoothBorder
import com.nuvio.app.core.ui.nuvio

/*
 * fork:live-tv-lineup - the menu of one channel: one centred glass card, the same size whatever it holds, a button per
 * action. It opens from the "..." beside a channel's star.
 */
@Composable
internal fun IptvLineupMenu(
    channelName: String,
    note: String,
    actions: List<IptvLineupAction>,
    focused: Int,
    onAction: (IptvLineupAction) -> Unit,
    onBack: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .iptvDialog(NuvioGlass.Shape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = channelName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = note, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, textAlign = TextAlign.Center)
            actions.forEachIndexed { index, action ->
                LineupMenuButton(
                    label = if (action == IptvLineupAction.Never) "Never again: hide it for good" else action.label,
                    focused = index == focused,
                    danger = action == IptvLineupAction.Never,
                    onClick = { onAction(action) },
                )
            }
            LineupMenuButton(label = "Close", focused = false, danger = false, onClick = onBack)
        }
    }
}

@Composable
internal fun LineupMenuButton(label: String, focused: Boolean, danger: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = NuvioGlass.Shape
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            // fork:live-tv-polish - a solid button, and the highlighted one filled with the accent so it reads at a glance.
            .background(
                when {
                    focused -> tokens.colors.accent.copy(alpha = 0.42f)
                    hovered -> Color.White.copy(alpha = 0.10f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
                shape,
            )
            .border(1.dp, if (focused) tokens.colors.accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.10f), shape)
            .then(if (danger && !focused) Modifier.border(1.dp, tokens.colors.danger.copy(alpha = 0.6f), shape) else Modifier)
            .clip(shape)
            .then(if (focused) Modifier.smoothBorder(1.5.dp, tokens.colors.borderFocus, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (danger) tokens.colors.danger else tokens.colors.textPrimary,
            maxLines = 1,
        )
    }
}
