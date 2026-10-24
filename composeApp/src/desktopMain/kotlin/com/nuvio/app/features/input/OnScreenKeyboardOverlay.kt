package com.nuvio.app.features.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.TextInputFocusTracker
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.flow.combine

/**
 * Controller text entry, anchored to the bottom of the window.
 *
 * Deliberately not a modal: search results update as the query is typed, and covering them with a
 * scrim would hide the one thing the user is typing to see. It also never takes Compose focus — the
 * text field keeps it, which is what lets the panel type into any field without touching it.
 */
@Composable
internal fun OnScreenKeyboardOverlay() {
    val visible by OnScreenKeyboard.visible.collectAsState()

    LaunchedEffect(Unit) {
        // One place decides visibility, so the panel cannot be left up by a pad that was unplugged
        // or a setting switched off mid-edit.
        combine(
            TextInputFocusTracker.active,
            GamepadSettingsRepository.connected,
            GamepadSettingsRepository.enabledState,
        ) { focused, connected, enabled -> focused to (connected && enabled) }
            .collect { (focused, padAvailable) ->
                // usedRecently keeps the panel away from someone who clicked into a settings field
                // with the mouse while a controller happens to be plugged in.
                OnScreenKeyboard.onTextFocusChanged(focused, padAvailable && GamepadInput.usedRecently())
            }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            OnScreenKeyboardPanel()
        }
    }
}

@Composable
private fun OnScreenKeyboardPanel() {
    val tokens = MaterialTheme.nuvio
    val selection by OnScreenKeyboard.selection.collectAsState()
    val caps by OnScreenKeyboard.caps.collectAsState()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = tokens.colors.surfaceSheet,
        tonalElevation = 8.dp,
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Column(
                // Capped so the keys stay a comfortable size on a large TV instead of stretching
                // into a strip the user has to sweep across.
                modifier = Modifier.widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                OskLayout.rows.forEachIndexed { rowIndex, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                    ) {
                        row.forEachIndexed { columnIndex, key ->
                            KeyCell(
                                key = key,
                                caps = caps,
                                selected = selection.row == rowIndex && selection.column == columnIndex,
                                // Action keys are wider, so the bottom row reads as controls rather
                                // than as four oversized letters.
                                modifier = Modifier.weight(if (key is OskKey.Action) 2.5f else 1f),
                            )
                        }
                    }
                }
            }
            Text(
                text = "A Select   ·   B Delete   ·   X Space   ·   Y Caps   ·   LB/RB Caret   ·   Start Done   ·   Back Close",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = NuvioTokens.Space.s4),
            )
        }
    }
}

@Composable
private fun KeyCell(
    key: OskKey,
    caps: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val label = when (key) {
        is OskKey.Character -> key.character(caps).toString()
        is OskKey.Action -> key.label
    }
    val isCapsOn = key is OskKey.Action && key.action == OskAction.Caps && caps

    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(46.dp)
            // accentFill rather than a flat accent so custom themes paint the highlight with their
            // gradient; it resolves to the solid accent on the built-in themes.
            .then(
                if (selected) {
                    Modifier.background(brush = tokens.colors.accentFill, shape = shape)
                } else {
                    Modifier.background(color = tokens.colors.surfaceCard, shape = shape)
                },
            )
            .border(
                width = if (isCapsOn && !selected) tokens.borders.medium else tokens.borders.thin,
                color = if (isCapsOn && !selected) tokens.colors.accent else tokens.colors.borderDefault,
                shape = shape,
            )
            // Mouse users get the same keys. Costs nothing, and makes the panel testable by hand.
            .clickable { OnScreenKeyboard.press(key) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (key is OskKey.Action) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = if (selected) tokens.colors.onAccent else tokens.colors.textPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
