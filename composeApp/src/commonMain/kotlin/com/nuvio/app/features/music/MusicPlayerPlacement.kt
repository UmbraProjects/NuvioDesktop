package com.nuvio.app.features.music

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.music.library.musicGlass
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

/**
 * The grip on a player's right edge: dragging it sideways makes the player wider or narrower. With
 * [centred] (the bar, which sits centred) the left edge is held still by shifting the offset by
 * half the change, so the grip stays under the pointer.
 */
@Composable
internal fun MusicResizeGrip(
    placement: MutableStateFlow<MusicPlayerPlacement>,
    currentWidth: Dp,
    minWidth: Dp,
    maxWidth: Dp,
    centred: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val current = androidx.compose.runtime.rememberUpdatedState(currentWidth)
    val bounds = androidx.compose.runtime.rememberUpdatedState(minWidth to maxWidth)
    Box(
        modifier
            .padding(end = 4.dp)
            .width(6.dp)
            .height(40.dp)
            .musicGlass(RoundedCornerShape(3.dp))
            .pointerInput(placement, centred) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val d = with(density) { drag.x.toDp() }
                    val (least, most) = bounds.value
                    placement.update { p ->
                        val before = p.width ?: current.value
                        val width = (before + d).coerceIn(least, maxOf(least, most))
                        val applied = width - before
                        p.copy(width = width, dx = if (centred) p.dx + applied / 2 else p.dx)
                    }
                }
            },
    )
}
