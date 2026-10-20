package com.nuvio.app.features.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlin.math.roundToInt

sealed interface StreamPickerPlacement {
    /** Over the screen that opened it, behind a scrim — every entry point but the details page. */
    data object Modal : StreamPickerPlacement

    /** In the details hero's trailer area, leaving the rest of the page usable. */
    data class Docked(val bounds: Rect) : StreamPickerPlacement
}

private val PanelShape = RoundedCornerShape(20.dp)

/** The last request the docked panel animated in for; see StreamPickerFrame's animateInKey. */
private var lastRevealedKey: Any? = null

/**
 * Positions the source picker panel. The frame fills the window but takes no input itself, so in
 * the docked case the details page around the panel keeps working; the modal case adds a scrim
 * that closes the picker when clicked.
 */
@Composable
fun StreamPickerFrame(
    placement: StreamPickerPlacement,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Source for the docked panel's glass: the page content behind it. Null draws it solid. */
    hazeState: HazeState? = null,
    /**
     * Identifies the request on screen. The docked panel fades and slides in once per key, in step
     * with the backdrop frosting behind it, and not again when the same request redraws (its
     * loading frame handing over to the list).
     */
    animateInKey: Any? = null,
    content: @Composable () -> Unit,
) {
    val reveal = remember(animateInKey) {
        Animatable(if (animateInKey != null && animateInKey == lastRevealedKey) 1f else 0f)
    }
    LaunchedEffect(animateInKey) {
        lastRevealedKey = animateInKey
        reveal.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
    }
    // The dock arrives in window coordinates; this frame need not start at the window origin.
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        when (placement) {
            is StreamPickerPlacement.Docked -> {
                val bounds = placement.bounds
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (bounds.left - origin.x).roundToInt(),
                                (bounds.top - origin.y).roundToInt(),
                            )
                        }
                        .size(
                            width = with(density) { bounds.width.toDp() },
                            height = with(density) { bounds.height.toDp() },
                        )
                        .streamPickerGlassSurface(hazeState),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = reveal.value
                                translationX = (1f - reveal.value) * 24.dp.toPx()
                            },
                    ) {
                        content()
                    }
                }
            }
            StreamPickerPlacement.Modal -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = MaterialTheme.nuvio.opacity.overlayMedium))
                        .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
                    contentAlignment = Alignment.Center,
                ) {
                    ResizableModalCard(content = content)
                }
            }
        }
    }
}

/**
 * The modal card, resizable from any edge or corner. It stays centred, so a drag grows it on both
 * sides. The size is kept as a fraction of the window (per account, like the other picker
 * preferences) so it carries over to other window sizes; double-clicking an edge restores the
 * default.
 */
@Composable
private fun ResizableModalCard(content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val frameWidth = maxWidth
        val frameHeight = maxHeight
        val maxCardWidth = (frameWidth - ModalMargin * 2).coerceAtLeast(ModalMinWidth)
        val maxCardHeight = (frameHeight - ModalMargin * 2).coerceAtLeast(ModalMinHeight)
        var savedFractions by remember { mutableStateOf(loadModalSizeFractions()) }
        val cardWidth = (savedFractions?.let { frameWidth * it.first } ?: (frameWidth * 0.6f).coerceIn(520.dp, 860.dp))
            .coerceIn(ModalMinWidth.coerceAtMost(maxCardWidth), maxCardWidth)
        val cardHeight = (savedFractions?.let { frameHeight * it.second } ?: (frameHeight * 0.86f))
            .coerceIn(ModalMinHeight.coerceAtMost(maxCardHeight), maxCardHeight)
        val density = LocalDensity.current
        val currentSize by rememberUpdatedState(Pair(cardWidth, cardHeight))
        val onResize: (StreamPickerResizeEdge, Offset) -> Unit = { edge, drag ->
            val (width, height) = currentSize
            // Centred: each side moves by the drag, so the card changes by twice it.
            val nextWidth = (width + with(density) { drag.x.toDp() } * 2f * edge.dx)
                .coerceIn(ModalMinWidth.coerceAtMost(maxCardWidth), maxCardWidth)
            val nextHeight = (height + with(density) { drag.y.toDp() } * 2f * edge.dy)
                .coerceIn(ModalMinHeight.coerceAtMost(maxCardHeight), maxCardHeight)
            savedFractions = (nextWidth / frameWidth) to (nextHeight / frameHeight)
        }
        Box(
            modifier = Modifier
                .size(width = cardWidth, height = cardHeight)
                .streamPickerPanelSurface(),
        ) {
            content()
            StreamPickerResizeEdge.entries.forEach { edge ->
                ModalResizeHandle(
                    edge = edge,
                    onResize = onResize,
                    onResizeEnd = {
                        savedFractions?.let { (w, h) ->
                            StreamBadgeSettingsStorage.saveStreamPickerModalSize("$w,$h")
                        }
                    },
                    onReset = {
                        savedFractions = null
                        StreamBadgeSettingsStorage.saveStreamPickerModalSize(null)
                    },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.ModalResizeHandle(
    edge: StreamPickerResizeEdge,
    onResize: (StreamPickerResizeEdge, Offset) -> Unit,
    onResizeEnd: () -> Unit,
    onReset: () -> Unit,
) {
    val currentOnResize by rememberUpdatedState(onResize)
    val currentOnResizeEnd by rememberUpdatedState(onResizeEnd)
    val currentOnReset by rememberUpdatedState(onReset)
    val placement = when (edge) {
        StreamPickerResizeEdge.Left -> Modifier.align(Alignment.CenterStart).fillMaxHeight().width(ResizeGrip)
        StreamPickerResizeEdge.Right -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(ResizeGrip)
        StreamPickerResizeEdge.Top -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(ResizeGrip)
        StreamPickerResizeEdge.Bottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(ResizeGrip)
        StreamPickerResizeEdge.TopLeft -> Modifier.align(Alignment.TopStart).size(ResizeCorner)
        StreamPickerResizeEdge.TopRight -> Modifier.align(Alignment.TopEnd).size(ResizeCorner)
        StreamPickerResizeEdge.BottomLeft -> Modifier.align(Alignment.BottomStart).size(ResizeCorner)
        StreamPickerResizeEdge.BottomRight -> Modifier.align(Alignment.BottomEnd).size(ResizeCorner)
    }
    Box(
        modifier = placement
            .pointerHoverIcon(streamPickerResizeCursor(edge))
            .pointerInput(edge) {
                detectDragGestures(
                    onDragEnd = { currentOnResizeEnd() },
                    onDragCancel = { currentOnResizeEnd() },
                ) { change, drag ->
                    change.consume()
                    currentOnResize(edge, drag)
                }
            }
            .pointerInput(edge) {
                detectTapGestures(onDoubleTap = { currentOnReset() })
            },
    )
}

private fun loadModalSizeFractions(): Pair<Float, Float>? {
    val parts = StreamBadgeSettingsStorage.loadStreamPickerModalSize()?.split(',') ?: return null
    val width = parts.getOrNull(0)?.toFloatOrNull() ?: return null
    val height = parts.getOrNull(1)?.toFloatOrNull() ?: return null
    if (width !in 0.05f..1f || height !in 0.05f..1f) return null
    return width to height
}

private val ModalMargin = 16.dp
private val ModalMinWidth = 420.dp
private val ModalMinHeight = 320.dp
private val ResizeGrip = 8.dp
private val ResizeCorner = 20.dp

/**
 * The modal card is opaque true black, not the theme's near-black: on the HTPC's VRR OLED, large
 * near-black greys visibly flicker whenever hover redraws make the frame rate uneven (the same
 * cause as the TV shelf flicker fixed 2026-10-13), while true black is a switched-off pixel and
 * cannot.
 */
@Composable
private fun Modifier.streamPickerPanelSurface(): Modifier = this
    .shadow(elevation = 24.dp, shape = PanelShape)
    .clip(PanelShape)
    .background(Color.Black)
    .border(1.dp, MaterialTheme.nuvio.colors.borderDefault, PanelShape)
    .swallowPointerInput()

/**
 * Docked in the details hero the panel is simply part of the page: no edge, corner or shadow to
 * mark it as something on top. With [hazeState] it blurs what is behind it; without, the details
 * hero frosts its whole backdrop itself (DetailHero backdropFrosted) and the panel only adds a
 * light tint for the list, faded in from its left edge so it has no visible boundary.
 */
@Composable
private fun Modifier.streamPickerGlassSurface(hazeState: HazeState?): Modifier = this
    .then(
        if (hazeState != null) {
            Modifier.hazeEffect(state = hazeState) {
                blurRadius = 32.dp
                noiseFactor = 0f
                tints = listOf(HazeTint(Color.Black.copy(alpha = 0.42f)))
            }
        } else {
            Modifier.drawBehind {
                drawRect(
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)),
                        endX = 160.dp.toPx(),
                    ),
                )
            }
        },
    )
    .swallowPointerInput()

private fun Modifier.swallowPointerInput(): Modifier = this
    // Swallow what the panel's own rows leave unconsumed: in the docked case it would otherwise
    // click through to the details page underneath, and in the modal case reach the scrim and close
    // the picker. Presses, releases and wheel only, never moves: a parent consuming a move cancels
    // the click of whatever is pressed below it, so any jitter during a click on a chip or a stream
    // card did nothing (2026-10-19 (ChipClick) trace: every failed click moved 2-18 px, every still
    // one worked). Nothing outside can click or scroll without a press or a wheel event anyway.
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press ||
                    event.type == PointerEventType.Release ||
                    event.type == PointerEventType.Scroll
                ) {
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
