package com.nuvio.app.features.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.ui.NuvioBackdropDirection
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.ditheredBackground
import com.nuvio.app.core.ui.nuvioBackdropBrush
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
 *
 * Hosted in its own non-focusable popup layer rather than drawn in the window's base layer. A field
 * inside a dialog (naming a playlist) lives in the dialog's layer, above the base one, so the panel
 * sat under the dialog's scrim and a click on a key landed outside the dialog — which dismissed it.
 * The popup opens after the field takes focus, so it stacks above that dialog, and a click inside
 * the topmost layer is not an outside click for anything beneath it.
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
            GamepadSettingsRepository.mouseKeyboardState,
        ) { focused, connected, enabled, mouseKeyboard -> Triple(focused, connected && enabled, mouseKeyboard) }
            .collect { (focused, padAvailable, mouseKeyboard) ->
                // usedRecently keeps the panel away from someone who clicked into a settings field
                // with the mouse while a controller happens to be plugged in — unless they asked
                // for exactly that, because a mouse is all they have on the couch.
                OnScreenKeyboard.onTextFocusChanged(
                    focused,
                    mouseKeyboard || (padAvailable && GamepadInput.usedRecently()),
                )
            }
    }

    // Kept composed until the slide-out finishes, so closing still animates.
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    if (!transition.currentState && !transition.targetState) return
    // The popup aligns within its parent, so give it the whole window to sit at the bottom of.
    Box(modifier = Modifier.fillMaxSize()) {
        Popup(
            alignment = Alignment.BottomCenter,
            properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                AnimatedVisibility(
                    visibleState = transition,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                ) {
                    OnScreenKeyboardPanel()
                }
            }
        }
    }
}

@Composable
private fun OnScreenKeyboardPanel() {
    val tokens = MaterialTheme.nuvio
    val selection by OnScreenKeyboard.selection.collectAsState()
    val caps by OnScreenKeyboard.caps.collectAsState()
    val padEnabled by GamepadSettingsRepository.enabledState.collectAsState()
    val padConnected by GamepadSettingsRepository.connected.collectAsState()

    // The modal glass (see NuvioDialogSurface): the night base it needs over busy content, the
    // glass fill and the one-pixel edge, so the panel matches the dialogs it now opens over.
    val panelShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                nuvioBackdropBrush(NuvioGlass.night(tokens.colors.background, tokens.colors.accent), NuvioBackdropDirection.Vertical),
                panelShape,
            )
            .ditheredBackground(NuvioGlass.Fill, panelShape)
            .border(width = 1.dp, brush = NuvioGlass.Edge, shape = panelShape),
        shape = panelShape,
        color = Color.Transparent,
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
                // Pad legend only when a pad could have opened this; a mouse-only user cannot press
                // any of those buttons.
                text = if (padEnabled && padConnected) {
                    "A Select   ·   B Delete   ·   X Space   ·   Y Caps   ·   LB/RB Caret   ·   Start Done   ·   Back Close"
                } else {
                    "Click a key to type   ·   Done to finish"
                },
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

    val shape = NuvioGlass.Shape
    Box(
        modifier = modifier
            .height(46.dp)
            // Keys are glass like the settings controls; the highlighted one is the accent seen
            // through it. accentFill rather than a flat accent so custom themes paint it with their
            // gradient; it resolves to the solid accent on the built-in themes.
            .then(
                if (selected) {
                    Modifier.background(brush = tokens.colors.accentFill, shape = shape, alpha = SelectedKeyGlassAlpha)
                } else {
                    Modifier.ditheredBackground(NuvioGlass.Fill, shape)
                },
            )
            .then(
                if (isCapsOn && !selected) {
                    Modifier.border(width = tokens.borders.medium, color = tokens.colors.accent, shape = shape)
                } else {
                    Modifier.border(width = 1.dp, brush = NuvioGlass.Edge, shape = shape)
                },
            )
            // Mouse users get the same keys. The field being typed into must keep focus, since the
            // synthetic keystrokes go to whatever holds it — so a click must never take it.
            .focusProperties { canFocus = false }
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

private const val SelectedKeyGlassAlpha = 0.85f
