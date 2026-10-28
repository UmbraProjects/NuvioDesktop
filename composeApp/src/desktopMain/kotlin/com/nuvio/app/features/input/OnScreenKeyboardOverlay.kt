package com.nuvio.app.features.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import java.awt.Cursor
import java.awt.MouseInfo
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine

/**
 * Controller text entry, docked to the bottom of the window or floating where the user put it.
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
 *
 * The panel is laid out once at full size and scaled as a whole (a smaller [Density]), so keys,
 * labels and gaps shrink together on a small window or a small floating panel.
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
    val placement by OskPlacement.state.collectAsState()
    // The popup positions within its parent, so give it the whole window to sit in.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (placement.floating) {
            FloatingKeyboard(transition, placement, maxWidth, maxHeight)
        } else {
            DockedKeyboard(transition, maxWidth, maxHeight)
        }
    }
}

@Composable
private fun DockedKeyboard(transition: MutableTransitionState<Boolean>, windowWidth: Dp, windowHeight: Dp) {
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
                ScaledDensity(dockedKeyboardScale(windowWidth, windowHeight)) {
                    OnScreenKeyboardPanel(
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                        modifier = Modifier.fillMaxWidth(),
                        header = {
                            Spacer(Modifier.weight(1f))
                            PanelButton(
                                icon = Icons.Default.PictureInPictureAlt,
                                label = "Undock",
                                onClick = { OskPlacement.setFloating(true) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingKeyboard(
    transition: MutableTransitionState<Boolean>,
    placement: OskPlacementState,
    windowWidth: Dp,
    windowHeight: Dp,
) {
    val density = LocalDensity.current
    val windowWidthPx = with(density) { windowWidth.toPx() }
    val windowHeightPx = with(density) { windowHeight.toPx() }

    // Live while dragging; written to OskPlacement when a drag ends, not on every pointer move.
    var xFraction by remember { mutableFloatStateOf(placement.xFraction) }
    var yFraction by remember { mutableFloatStateOf(placement.yFraction) }
    var widthDp by remember { mutableFloatStateOf(placement.widthDp) }
    var panelHeightPx by remember { mutableIntStateOf(0) }

    // Never wider than the window, so a panel sized on a big monitor still fits a small one.
    val maxWidthDp = (windowWidth.value - FloatingWindowMarginDp)
        .coerceIn(OskPlacement.MIN_FLOATING_WIDTH_DP, OskPlacement.MAX_FLOATING_WIDTH_DP)
    val shownWidthDp = widthDp.coerceIn(OskPlacement.MIN_FLOATING_WIDTH_DP, maxWidthDp)
    val panelWidthPx = shownWidthDp * density.density
    val freeX = (windowWidthPx - panelWidthPx).coerceAtLeast(0f)
    val freeY = (windowHeightPx - panelHeightPx).coerceAtLeast(0f)
    val persist = { OskPlacement.setFloatingBounds(xFraction, yFraction, shownWidthDp) }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset((xFraction * freeX).roundToInt(), (yFraction * freeY).roundToInt()),
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
    ) {
        AnimatedVisibility(visibleState = transition, enter = fadeIn(), exit = fadeOut()) {
            ScaledDensity(shownWidthDp / BasePanelWidth.value) {
                OnScreenKeyboardPanel(
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .width(BasePanelWidth)
                        .onSizeChanged { panelHeightPx = it.height },
                    header = {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR)))
                                .screenDrag(
                                    onDrag = { delta ->
                                        if (freeX > 0f) xFraction = ((xFraction * freeX + delta.x) / freeX).coerceIn(0f, 1f)
                                        if (freeY > 0f) yFraction = ((yFraction * freeY + delta.y) / freeY).coerceIn(0f, 1f)
                                    },
                                    onEnd = persist,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = "Move keyboard",
                                tint = MaterialTheme.nuvio.colors.textMuted,
                            )
                        }
                        PanelButton(
                            icon = Icons.Default.VerticalAlignBottom,
                            label = "Dock",
                            onClick = { OskPlacement.setFloating(false) },
                        )
                    },
                    overlay = {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp)
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)))
                                .screenDrag(
                                    onDrag = { delta ->
                                        // Resized from the corner: the top-left stays put, and the
                                        // height follows the width since the panel scales as a whole.
                                        val left = xFraction * freeX
                                        val top = yFraction * freeY
                                        val aspect = if (panelHeightPx > 0) panelWidthPx / panelHeightPx else 2.5f
                                        val grownPx = (delta.x + delta.y * aspect) / 2f
                                        val newWidthDp = ((panelWidthPx + grownPx) / density.density)
                                            .coerceIn(OskPlacement.MIN_FLOATING_WIDTH_DP, maxWidthDp)
                                        val newWidthPx = newWidthDp * density.density
                                        val newHeightPx = panelHeightPx * newWidthPx / panelWidthPx
                                        widthDp = newWidthDp
                                        val newFreeX = windowWidthPx - newWidthPx
                                        val newFreeY = windowHeightPx - newHeightPx
                                        if (newFreeX > 0f) xFraction = (left / newFreeX).coerceIn(0f, 1f)
                                        if (newFreeY > 0f) yFraction = (top / newFreeY).coerceIn(0f, 1f)
                                    },
                                    onEnd = persist,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.SouthEast,
                                contentDescription = "Resize keyboard",
                                tint = MaterialTheme.nuvio.colors.textMuted,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * Docked size: full size on a big window, smaller on a short or narrow one so the panel never takes
 * much more than a third of the height — at 720p the full-size panel covered most of a search page.
 */
private fun dockedKeyboardScale(windowWidth: Dp, windowHeight: Dp): Float =
    minOf(
        1f,
        windowHeight.value * DockedMaxHeightFraction / BasePanelHeightDp,
        windowWidth.value / BasePanelWidth.value,
    ).coerceAtLeast(MinDockedScale)

@Composable
private fun ScaledDensity(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density * scale, density.fontScale),
        content = content,
    )
}

/**
 * Drag measured from the OS pointer in screen pixels rather than Compose's local positions: the
 * handle sits in a popup that moves with the drag, which would otherwise feed its own movement back
 * in as part of the next delta.
 */
@Composable
private fun Modifier.screenDrag(onDrag: (Offset) -> Unit, onEnd: () -> Unit): Modifier {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnEnd by rememberUpdatedState(onEnd)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var last = pointerOnScreenPx() ?: return@awaitEachGesture
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                change.consume()
                val now = pointerOnScreenPx() ?: continue
                if (now != last) currentOnDrag(now - last)
                last = now
            }
            currentOnEnd()
        }
    }
}

private fun pointerOnScreenPx(): Offset? {
    val info = runCatching { MouseInfo.getPointerInfo() }.getOrNull() ?: return null
    val transform = info.device.defaultConfiguration.defaultTransform
    return Offset((info.location.x * transform.scaleX).toFloat(), (info.location.y * transform.scaleY).toFloat())
}

@Composable
private fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .ditheredBackground(NuvioGlass.Fill, NuvioGlass.Shape)
            .border(width = 1.dp, brush = NuvioGlass.Edge, shape = NuvioGlass.Shape)
            // Same rule as the keys: the field being typed into keeps focus.
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
    }
}

@Composable
private fun OnScreenKeyboardPanel(
    shape: Shape,
    modifier: Modifier,
    header: @Composable RowScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val selection by OnScreenKeyboard.selection.collectAsState()
    val caps by OnScreenKeyboard.caps.collectAsState()
    val padEnabled by GamepadSettingsRepository.enabledState.collectAsState()
    val padConnected by GamepadSettingsRepository.connected.collectAsState()

    // The modal glass (see NuvioDialogSurface): the night base it needs over busy content, the
    // glass fill and the one-pixel edge, so the panel matches the dialogs it now opens over.
    Surface(
        modifier = modifier
            .background(
                nuvioBackdropBrush(NuvioGlass.night(tokens.colors.background, tokens.colors.accent), NuvioBackdropDirection.Vertical),
                shape,
            )
            .ditheredBackground(NuvioGlass.Fill, shape)
            .border(width = 1.dp, brush = NuvioGlass.Edge, shape = shape),
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 16.dp,
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s12),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                Row(
                    modifier = Modifier.widthIn(max = KeyGridMaxWidth).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    content = header,
                )
                Column(
                    // Capped so the keys stay a comfortable size on a large TV instead of stretching
                    // into a strip the user has to sweep across.
                    modifier = Modifier.widthIn(max = KeyGridMaxWidth),
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
                                    // Action keys are wider, so the bottom row reads as controls
                                    // rather than as four oversized letters.
                                    modifier = Modifier.weight(if (key is OskKey.Action) 2.5f else 1f),
                                )
                            }
                        }
                    }
                }
                Text(
                    // Pad legend only when a pad could have opened this; a mouse-only user cannot
                    // press any of those buttons.
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
            overlay()
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

private val KeyGridMaxWidth = 760.dp

/** The full-size panel's width: the key grid plus its side padding. Floating sizes scale from it. */
private val BasePanelWidth = 792.dp

/** Roughly the full-size panel's height: header, five key rows, legend and padding. */
private const val BasePanelHeightDp = 340f
private const val DockedMaxHeightFraction = 0.32f
private const val MinDockedScale = 0.55f
private const val FloatingWindowMarginDp = 16f
