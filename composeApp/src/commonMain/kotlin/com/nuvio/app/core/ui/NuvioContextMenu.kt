package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where the most recent right-click landed, in window pixels. [secondaryClick] records it just
 * before running its callback, so whatever that callback opens can [consume] it in the same call
 * stack and appear at the cursor. A menu opened any other way (long-press, keyboard, gamepad) finds
 * nothing here and keeps its own presentation.
 */
object ContextMenuInvocation {
    private var pending: IntOffset? = null
    private var recordedAt: TimeMark? = null
    private var pendingFromKeyboard = false

    /**
     * Whether the position last handed out by [consume] came from a held select key rather than a
     * right-click. The menu reads it as it opens: a keyboard-opened menu focuses its first row and
     * ignores a cursor that merely happens to sit where it appeared.
     */
    var lastConsumedFromKeyboard: Boolean = false
        private set

    internal fun recordSecondaryPress(windowPosition: IntOffset) {
        pending = windowPosition
        recordedAt = TimeSource.Monotonic.markNow()
        pendingFromKeyboard = false
    }

    /** A hold-to-select on a keyboard-focused card, opening its menu beside the card. */
    internal fun recordKeyboardInvocation(windowPosition: IntOffset) {
        recordSecondaryPress(windowPosition)
        pendingFromKeyboard = true
    }

    /**
     * The cursor position of a right-click that happened just now, or null. The age check is what
     * stops a right-click some other `secondaryClick` swallowed from resurfacing later as the
     * position of a keyboard-opened menu.
     */
    fun consume(): IntOffset? {
        val position = pending
        val fresh = recordedAt?.let { it.elapsedNow() < ConsumeWindow } == true
        lastConsumedFromKeyboard = pendingFromKeyboard && fresh && position != null
        pending = null
        recordedAt = null
        pendingFromKeyboard = false
        return position?.takeIf { fresh }
    }

    private val ConsumeWindow = 750.milliseconds
}

private const val PointerEngageDistancePx = 4f

/**
 * Desktop-style right-click menu: a compact panel opened at the cursor, the same look as the
 * streams screen's row menu. Takes the same [PosterZoomOverlayAction] list the zoom preview and
 * bottom sheets render, so every presentation of an item's actions stays in step.
 */
@Composable
fun NuvioContextMenu(
    windowPosition: IntOffset,
    actions: List<PosterZoomOverlayAction>,
    onDismiss: () -> Unit,
) {
    // A context menu shows a snapshot of the moment it was invoked; don't let repository updates
    // while it is open relabel or reorder the rows under the pointer.
    val frozenActions = remember { actions }
    val fadeIn = remember { Animatable(0f) }
    val rowFocus = remember(frozenActions) { List(frozenActions.size) { FocusRequester() } }
    val menuFocus = remember { FocusRequester() }
    val focusedIndex = remember { intArrayOf(-1) }
    // Opened by a held select key: the keyboard owns the menu until the mouse is actually moved.
    // Without this the row under a resting cursor lit up and took focus the moment the menu
    // appeared, and the keyboard had nothing focused to start from.
    val openedByKeyboard = remember { ContextMenuInvocation.lastConsumedFromKeyboard }
    var pointerEngaged by remember { mutableStateOf(!openedByKeyboard) }

    // Stands the hover preview down, same as the zoom overlay: the menu opens on the very card the
    // preview is anchored to.
    DisposableEffect(Unit) {
        PosterZoomOverlayCoordinator.show()
        onDispose { PosterZoomOverlayCoordinator.hide() }
    }
    LaunchedEffect(Unit) {
        // Falls back to the panel if the row cannot take focus: a menu with nothing focused would
        // leave the keyboard with no way in at all.
        val rowFocused = openedByKeyboard && rowFocus.isNotEmpty() &&
            runCatching { rowFocus[0].requestFocus() }.isSuccess
        if (!rowFocused) runCatching { menuFocus.requestFocus() }
        fadeIn.animateTo(1f, tween(durationMillis = 110, easing = NuvioTokens.Motion.standard))
    }
    PlatformBackHandler(enabled = true, onBack = onDismiss)

    fun select(action: PosterZoomOverlayAction) {
        action.onSelected()
        onDismiss()
    }

    fun moveFocus(step: Int) {
        if (rowFocus.isEmpty()) return
        val next = when {
            focusedIndex[0] < 0 -> if (step > 0) 0 else rowFocus.lastIndex
            else -> (focusedIndex[0] + step).mod(rowFocus.size)
        }
        runCatching { rowFocus[next].requestFocus() }
    }

    Popup(
        popupPositionProvider = remember(windowPosition) { CursorMenuPositionProvider(windowPosition) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = ContextMenuMinWidth, max = ContextMenuMaxWidth)
                .width(IntrinsicSize.Max)
                .graphicsLayer {
                    alpha = fadeIn.value
                    translationY = (1f - fadeIn.value) * -4.dp.toPx()
                },
            shape = RoundedCornerShape(10.dp),
            color = ContextMenuFill,
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (pointerEngaged) {
                            Modifier
                        } else {
                            // Initial pass, so the rows see the menu as engaged on the very move
                            // that engaged it and the row under the cursor takes focus there.
                            Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    var origin: Offset? = null
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.type != PointerEventType.Move) continue
                                        val position = event.changes.firstOrNull()?.position ?: continue
                                        val start = origin ?: position.also { origin = it }
                                        // Compose replays a synthetic move when content appears
                                        // under a still cursor; only real travel counts.
                                        if ((position - start).getDistance() > PointerEngageDistancePx) {
                                            pointerEngaged = true
                                        }
                                    }
                                }
                            }
                        },
                    )
                    .focusRequester(menuFocus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> { moveFocus(1); true }
                            Key.DirectionUp -> { moveFocus(-1); true }
                            Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .padding(6.dp),
            ) {
                frozenActions.forEachIndexed { index, action ->
                    if (index > 0 && action.group != frozenActions[index - 1].group) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 5.dp, vertical = 4.dp)
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.10f)),
                        )
                    }
                    ContextMenuRow(
                        action = action,
                        focusRequester = rowFocus[index],
                        pointerEngaged = { pointerEngaged },
                        onFocused = { focusedIndex[0] = index },
                        onClick = { select(action) },
                        onSecondaryClick = action.onSecondarySelected?.let { secondary ->
                            {
                                secondary()
                                onDismiss()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextMenuRow(
    action: PosterZoomOverlayAction,
    focusRequester: FocusRequester,
    pointerEngaged: () -> Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onSecondaryClick: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val highlighted = focused || (hovered && pointerEngaged())
    val labelColor = if (action.isDestructive) MaterialTheme.nuvio.colors.danger else Color.White
    val iconTint = when {
        action.isDestructive -> MaterialTheme.nuvio.colors.danger
        highlighted -> MaterialTheme.colorScheme.primary
        else -> Color.White.copy(alpha = 0.76f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ContextMenuRowHeight)
            .background(
                color = if (highlighted) Color.White.copy(alpha = 0.10f) else Color.Transparent,
                shape = RoundedCornerShape(7.dp),
            )
            // Hovering moves keyboard focus too, so arrow keys continue from under the pointer
            // and only one row is ever lit.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var focusedByPointer = false
                    while (true) {
                        when (awaitPointerEvent().type) {
                            // Move as well as Enter: on a keyboard-opened menu the cursor may
                            // already be over this row when the first real move engages it.
                            PointerEventType.Enter, PointerEventType.Move -> {
                                if (!focusedByPointer && pointerEngaged()) {
                                    focusedByPointer = true
                                    runCatching { focusRequester.requestFocus() }
                                }
                            }
                            PointerEventType.Exit -> focusedByPointer = false
                        }
                    }
                }
            }
            .focusRequester(focusRequester)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .secondaryClick(onSecondaryClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = iconTint,
        )
        Text(
            text = action.label,
            modifier = Modifier.padding(start = 11.dp, end = 6.dp),
            color = labelColor,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Opens down-right of the cursor like a Windows menu, flipping to the other side of the cursor
 * rather than sliding over it when it would run off the window.
 */
private class CursorMenuPositionProvider(
    private val cursor: IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = 8
        val x = if (cursor.x + popupContentSize.width + margin <= windowSize.width) {
            cursor.x
        } else {
            cursor.x - popupContentSize.width
        }
        val y = if (cursor.y + popupContentSize.height + margin <= windowSize.height) {
            cursor.y
        } else {
            cursor.y - popupContentSize.height
        }
        return IntOffset(
            x = x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            y = y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
        )
    }
}

private val ContextMenuFill = Color(0xFA18191D)
private val ContextMenuMinWidth = 220.dp
private val ContextMenuMaxWidth = 380.dp
private val ContextMenuRowHeight = 38.dp

/**
 * The bottom-sheet presentation of the same action list: [header] above, one row per action, and
 * the sheet slides away after a row runs. Used when the menu was opened without a cursor to anchor
 * to and the zoom preview is off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioActionBottomSheet(
    actions: List<PosterZoomOverlayAction>,
    onDismiss: () -> Unit,
    bottomPadding: Dp = 16.dp,
    header: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    fun dismissAfter(action: () -> Unit) {
        action()
        coroutineScope.launch {
            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(bottomPadding)),
        ) {
            header()
            actions.forEach { action ->
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = action.icon,
                    title = action.label,
                    onClick = { dismissAfter(action.onSelected) },
                    onSecondaryClick = action.onSecondarySelected?.let { secondary ->
                        { dismissAfter(secondary) }
                    },
                )
            }
        }
    }
}
