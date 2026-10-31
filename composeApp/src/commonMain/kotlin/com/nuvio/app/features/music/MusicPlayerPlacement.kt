package com.nuvio.app.features.music

import com.nuvio.app.features.streams.streamPickerResizeCursor
import com.nuvio.app.features.streams.StreamPickerResizeEdge
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/*
 * fork:music
 *
 * The bar and the corner card can be dragged anywhere in the window and made wider or narrower, so
 * the player can sit out of the way while the rest of the app is in use. A placement is an offset
 * from where the player normally sits plus an optional width; it is kept for the session, and a
 * double-click on the player puts it back.
 */

/** How far the person has moved a player from its usual spot, and how wide they made it (null: the usual width). */
data class MusicPlayerPlacement(val dx: Dp = 0.dp, val dy: Dp = 0.dp, val width: Dp? = null) {
    val isDefault: Boolean get() = this == Default

    /** Lifted off the bottom edge: the page no longer needs to leave room under it. */
    val isFloating: Boolean get() = dy < -FLOAT_THRESHOLD

    companion object {
        val Default = MusicPlayerPlacement()
        private val FLOAT_THRESHOLD = 24.dp
    }
}

/** Where a player is drawn: its left edge, its width, and its lift off the bottom (zero or negative). */
internal data class MusicPlayerFrame(val left: Dp, val width: Dp, val dy: Dp)

/** Narrowest the bar may be made; below this its title and transport no longer fit. */
internal val MusicBarMinWidth = 560.dp

/** The corner card's range: its content has room down to this, and stops looking like a card above the max. */
internal val MusicCornerMinWidth = 320.dp
internal val MusicCornerMaxWidth = 640.dp

/**
 * The bar's frame in an area [areaWidth] by [areaHeight]: centred by default, at most the usual
 * width, shifted by [placement] and kept inside the area. [cardHeight] is the bar's own height.
 */
internal fun musicBarFrame(areaWidth: Dp, areaHeight: Dp, cardHeight: Dp, placement: MusicPlayerPlacement): MusicPlayerFrame {
    val usual = (areaWidth - musicBarSideMargin(areaWidth) * 2).coerceAtLeast(1.dp)
    val width = (placement.width ?: usual).coerceIn(minOf(MusicBarMinWidth, usual), usual)
    val left = ((areaWidth - width) / 2 + placement.dx).coerceIn(0.dp, (areaWidth - width).coerceAtLeast(0.dp))
    return MusicPlayerFrame(left, width, clampLift(placement.dy, areaHeight, cardHeight))
}

/** The corner card's frame: at the left margin by default, [usualWidth] wide unless resized, kept inside the area. */
internal fun musicCornerFrame(areaWidth: Dp, areaHeight: Dp, cardHeight: Dp, usualWidth: Dp, placement: MusicPlayerPlacement): MusicPlayerFrame {
    val most = minOf(MusicCornerMaxWidth, areaWidth)
    val width = (placement.width ?: usualWidth).coerceIn(minOf(MusicCornerMinWidth, most), most)
    val left = (musicBarSideMargin(areaWidth) + placement.dx).coerceIn(0.dp, (areaWidth - width).coerceAtLeast(0.dp))
    return MusicPlayerFrame(left, width, clampLift(placement.dy, areaHeight, cardHeight))
}

/** The bar's placement pulled inside the area (the inverse of [musicBarFrame]). */
internal fun clampBarPlacement(areaWidth: Dp, areaHeight: Dp, cardHeight: Dp, placement: MusicPlayerPlacement): MusicPlayerPlacement {
    val f = musicBarFrame(areaWidth, areaHeight, cardHeight, placement)
    return placement.copy(dx = f.left - (areaWidth - f.width) / 2, dy = f.dy)
}

/** The corner card's placement pulled inside the area (the inverse of [musicCornerFrame]). */
internal fun clampCornerPlacement(areaWidth: Dp, areaHeight: Dp, cardHeight: Dp, usualWidth: Dp, placement: MusicPlayerPlacement): MusicPlayerPlacement {
    val f = musicCornerFrame(areaWidth, areaHeight, cardHeight, usualWidth, placement)
    return placement.copy(dx = f.left - musicBarSideMargin(areaWidth), dy = f.dy)
}

/** The widest the folded notch gets (a long title); the drag keeps the notch, not the open panel, inside the window. */
internal val DockNotchMaxWidth = 320.dp

/** The folded notch's height, and the gap the host leaves between it and the window's bottom edge. */
internal val DockNotchHeight = 40.dp
internal val DockEdgeGap = 10.dp

/**
 * The dock's placement (an offset from bottom centre) pulled inside the area. The folded notch ([notchWidth] wide) may go
 * into any corner, one edge gap from the sides; there it opens downwards in the top half ([dockOpensDown]) and the
 * host pushes the wider open panel back inside the window, so it grows away from the edges it is docked to.
 */
internal fun clampDockPlacement(
    areaWidth: Dp,
    areaHeight: Dp,
    placement: MusicPlayerPlacement,
    notchWidth: Dp = DockNotchMaxWidth,
): MusicPlayerPlacement {
    val side = ((areaWidth - notchWidth) / 2 - DockEdgeGap).coerceAtLeast(0.dp)
    val dx = if (areaWidth == Dp.Unspecified || areaWidth == Dp.Infinity) placement.dx else placement.dx.coerceIn(-side, side)
    val dy = when {
        placement.dy > 0.dp -> 0.dp
        areaHeight == Dp.Unspecified || areaHeight == Dp.Infinity -> placement.dy
        else -> placement.dy.coerceAtLeast(-(areaHeight - DockNotchHeight - DockEdgeGap * 2).coerceAtLeast(0.dp))
    }
    return placement.copy(dx = dx, dy = dy)
}

/** Whether the notch's middle is in the top half of the area, where the open panel would not fit above it. */
internal fun dockOpensDown(areaHeight: Dp, placement: MusicPlayerPlacement): Boolean {
    if (areaHeight == Dp.Unspecified || areaHeight == Dp.Infinity) return false
    val notchMiddleFromTop = areaHeight + placement.dy - DockEdgeGap - DockNotchHeight / 2
    return notchMiddleFromTop < areaHeight / 2
}

/**
 * A drag step of the dock from [from] to [moved], with the panel open [extra] taller than the notch. Dragged across the
 * middle, the open panel would swap to growing the other way and jump by [extra], off the cursor, folding and reopening
 * as the pointer left and found it again. The notch moves to the panel's other end instead, so the panel stays where it
 * is under the cursor; that also puts the notch well past the middle, so the swap does not flicker back.
 */
internal fun dockDragStep(areaHeight: Dp, from: MusicPlayerPlacement, moved: MusicPlayerPlacement, extra: Dp): MusicPlayerPlacement {
    val wasDown = dockOpensDown(areaHeight, from)
    val nowDown = dockOpensDown(areaHeight, moved)
    return when {
        wasDown == nowDown || extra <= 0.dp -> moved
        nowDown -> moved.copy(dy = moved.dy - extra)
        else -> moved.copy(dy = moved.dy + extra)
    }
}

/** A lift between the bottom edge (0) and the top of the area; unbounded while the area's height is unknown. */
private fun clampLift(dy: Dp, areaHeight: Dp, cardHeight: Dp): Dp {
    if (dy > 0.dp) return 0.dp
    if (areaHeight == Dp.Unspecified || areaHeight == Dp.Infinity) return dy
    val most = (areaHeight - cardHeight - MusicBarBottomMargin * 2).coerceAtLeast(0.dp)
    return dy.coerceAtLeast(-most)
}

/**
 * Drags the player by its body (its buttons and progress bar keep their own gestures and win), and a
 * double-click puts it back where it normally sits.
 */
@Composable
internal fun Modifier.musicPlayerDrag(
    placement: MutableStateFlow<MusicPlayerPlacement>,
    /** Pulls a placement back inside the window, so dragging past an edge does not build up an offset. */
    clamp: (MusicPlayerPlacement) -> MusicPlayerPlacement,
): Modifier {
    val density = LocalDensity.current
    val clampNow = androidx.compose.runtime.rememberUpdatedState(clamp)
    return this
        .pointerInput(placement) {
            detectDragGestures { change, drag ->
                change.consume()
                val x = with(density) { drag.x.toDp() }
                val y = with(density) { drag.y.toDp() }
                placement.update { clampNow.value(it.copy(dx = it.dx + x, dy = it.dy + y)) }
            }
        }
        .pointerInput(placement) {
            detectTapGestures(onDoubleTap = { placement.value = MusicPlayerPlacement.Default })
        }
}

/** Which side of a player a [MusicResizeGrip] sits on. */
internal enum class MusicGripEdge { Left, Right }

/**
 * A grip on a player's left or right edge: a full-height strip that shows the sideways resize cursor,
 * with a visible handle in the middle that brightens on hover. Dragging it makes the player wider
 * or narrower while the opposite edge stays where it is. [centred] says the player sits centred
 * (the bar), so a width change has to be offset by half of it to hold that edge still.
 */
@Composable
internal fun MusicResizeGrip(
    placement: MutableStateFlow<MusicPlayerPlacement>,
    currentWidth: Dp,
    minWidth: Dp,
    maxWidth: Dp,
    centred: Boolean,
    modifier: Modifier = Modifier,
    edge: MusicGripEdge = MusicGripEdge.Right,
) {
    val density = LocalDensity.current
    val current = androidx.compose.runtime.rememberUpdatedState(currentWidth)
    val bounds = androidx.compose.runtime.rememberUpdatedState(minWidth to maxWidth)
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        modifier
            .testTag("musicResizeGrip-${edge.name}")
            .width(18.dp)
            .fillMaxHeight()
            .hoverable(source)
            .pointerHoverIcon(streamPickerResizeCursor(StreamPickerResizeEdge.Right))
            .pointerInput(placement, centred, edge) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, drag ->
                    change.consume()
                    val d = with(density) { drag.x.toDp() }
                    val (least, most) = bounds.value
                    placement.update { p ->
                        val before = p.width ?: current.value
                        val grow = if (edge == MusicGripEdge.Right) d else -d
                        val width = (before + grow).coerceIn(least, maxOf(least, most))
                        val applied = width - before
                        // Hold the opposite edge still.
                        val shift = when {
                            edge == MusicGripEdge.Right && centred -> applied / 2
                            edge == MusicGripEdge.Right -> 0.dp
                            centred -> -applied / 2
                            else -> -applied
                        }
                        p.copy(width = width, dx = p.dx + shift)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(36.dp)
                .background(
                    Color.White.copy(alpha = if (hovered || dragging) 0.55f else 0.22f),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}
