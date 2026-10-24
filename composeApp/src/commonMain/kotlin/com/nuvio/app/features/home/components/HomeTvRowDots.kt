package com.nuvio.app.features.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.NuvioShelfHeaderTrailingSideMarginFraction
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.HomeTvRowDotsAnchor
import androidx.compose.material3.MaterialTheme

/**
 * One entry in the TV Mode row-jump strip — a single shelf on the home screen, in the same order
 * (and at the same index) as the rows the Up/Down keys and the mouse wheel step through.
 *
 * [rowKey] is the catalog / collection settings key, or [HomeTvContinueWatchingRowKey] for the
 * Continue Watching shelf. It is deliberately the same handle the home settings use for the
 * persisted per-catalog bookmark colour surfaced through [markerColor].
 */
internal data class HomeTvRowDot(
    val rowKey: String,
    val label: String,
    // null = unmarked, drawn in the neutral strip colour. A non-null colour is drawn as-is at full
    // strength for the active dot and dimmed for the rest, so a marked row stays findable while
    // scanning.
    val markerColor: Color? = null,
)

internal const val HomeTvContinueWatchingRowKey = "continue_watching"

private val DotHitSize = 16.dp
private val DotSize = 7.dp
private val ActiveDotSize = 10.dp
private val HoveredDotSize = 9.dp
private val DotVerticalOffset = 2.dp
private const val InactiveDotAlpha = 0.32f
private const val HoveredDotAlpha = 0.7f
// Floor for very narrow windows, where the side margins would otherwise leave no usable strip.
private const val MinVisibleDots = 5

/**
 * The strip's centre line, as a fraction of the header row.
 *
 * The backdrop anchor lines the dots up under the "Bottom of backdrop" hero badges. The badges are
 * centred on the backdrop region of the *window* while this is a fraction of the header row, which
 * is inset by the section padding — a few pixels of difference at most, not worth plumbing the
 * padding through for.
 */
private val HomeTvRowDotsAnchor.centreFraction: Float
    get() = when (this) {
        HomeTvRowDotsAnchor.RowTitle -> 0.5f
        HomeTvRowDotsAnchor.HeroBackdrop -> HeroBackdropCentreFraction
    }

/**
 * Share of the row kept clear to the right of the strip. Only the view-all pill sits over there, so
 * an anchor already pushed right doesn't need the wide margin the centred one takes from being
 * symmetric — it can spend that room on dots instead.
 */
private val HomeTvRowDotsAnchor.endMarginFraction: Float
    get() = when (this) {
        HomeTvRowDotsAnchor.RowTitle -> NuvioShelfHeaderTrailingSideMarginFraction
        HomeTvRowDotsAnchor.HeroBackdrop -> 0.08f
    }

/**
 * Clickable per-row dots rendered beside the shelf title in TV Mode.
 *
 * TV Mode shows one shelf at a time, so reaching row 30 otherwise costs 30 wheel steps or key
 * presses; clicking that row's dot jumps straight there. Mouse-only by design — keyboard users
 * already have Up/Down, Page Up/Down and Home/End — so nothing here takes focus (tap gestures
 * rather than `clickable`, which would make each dot focusable and fight the TV focus owner).
 *
 * The strip scrolls horizontally rather than wrapping or shrinking, so a large catalog list stays
 * on the title's line; the active dot is kept in view.
 */
@Composable
internal fun HomeTvRowDotStrip(
    dots: List<HomeTvRowDot>,
    activeIndex: Int,
    onDotClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Hoisted by the caller: TV Mode swaps the whole shelf (header included) on every row change,
    // so a state remembered in here would be discarded — and re-created — on each jump, snapping
    // the strip back to the start every time. See HomeScreen's tvRowDotsListState.
    listState: LazyListState = rememberLazyListState(),
    // Horizontal placement only — the strip always sits on the shelf's title line.
    anchor: HomeTvRowDotsAnchor = HomeTvRowDotsAnchor.RowTitle,
) {
    if (dots.size <= 1) return

    LaunchedEffect(activeIndex, dots.size) {
        if (activeIndex !in dots.indices) return@LaunchedEffect
        // First composition runs this before the row has been measured; deciding on an empty
        // layout would "scroll" the active dot to the strip's start and hide every earlier row.
        if (listState.layoutInfo.visibleItemsInfo.isEmpty()) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        }
        val layoutInfo = listState.layoutInfo
        val viewportSize = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val isFullyVisible = layoutInfo.visibleItemsInfo.any { item ->
            item.index == activeIndex &&
                item.offset >= layoutInfo.viewportStartOffset &&
                item.offset + item.size <= layoutInfo.viewportEndOffset
        }
        if (isFullyVisible) return@LaunchedEffect
        // Centre the active dot when it is off-screen (negative offset = placed that far *into*
        // the viewport), so the rows on either side of it stay clickable after the jump.
        val itemSize = layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
        listState.animateScrollToItem(
            index = activeIndex,
            scrollOffset = -((viewportSize - itemSize) / 2).coerceAtLeast(0),
        )
    }

    // Sized to its dots and centred on [anchor]'s line, so the strip grows outward from a fixed
    // midpoint in both directions as catalogs are added — 11 catalogs put row 6 on that line with
    // 5 either side. Order is unchanged: the leftmost dot is always row 1.
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val centreX = maxWidth * anchor.centreFraction
        // Growth stops before the strip can reach the catalog title on its left or the view-all
        // pill on its right; past that it scrolls within this width instead of widening, keeping
        // the active dot in view. The two limits are measured from the anchor line independently,
        // since an off-centre anchor has less room on one side than on the other.
        // A short strip — Search and Library build one row per result section, so two or three is
        // normal there — never widens past its own dots, so the floor gives way to the dot count.
        val minDots = minOf(MinVisibleDots, dots.size)
        val halfWidthBudget = minOf(
            centreX - maxWidth * NuvioShelfHeaderTrailingSideMarginFraction,
            maxWidth * (1f - anchor.endMarginFraction) - centreX,
        ).coerceAtLeast(DotHitSize * minDots / 2f)
        val visibleDotCount = (halfWidthBudget * 2f / DotHitSize).toInt()
            .coerceIn(minDots, dots.size)
        var hoveredDotIndex by remember { mutableIntStateOf(-1) }

        // One tooltip area owns the whole continuous strip. The initial entry still observes the
        // normal tooltip delay, but moving from one dot's hit target to the next only changes the
        // tooltip text, so it stays visible and updates immediately.
        NuvioPosterHoverTooltip(
            title = dots.getOrNull(hoveredDotIndex)?.label
                ?: dots.getOrNull(activeIndex)?.label.orEmpty(),
            modifier = Modifier
                .width(DotHitSize * visibleDotCount)
                // Box centring puts the strip on the row's midpoint; shift it onto the anchor's.
                .offset(
                    x = centreX - maxWidth / 2f,
                    y = DotVerticalOffset,
                ),
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(dots) { index, dot ->
                    RowDot(
                        dot = dot,
                        isActive = index == activeIndex,
                        onClick = { onDotClick(index) },
                        onHoverChanged = { hovered ->
                            if (hovered) {
                                hoveredDotIndex = index
                            } else if (hoveredDotIndex == index) {
                                hoveredDotIndex = -1
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowDot(
    dot: HomeTvRowDot,
    isActive: Boolean,
    onClick: () -> Unit,
    onHoverChanged: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val baseColor = dot.markerColor ?: tokens.colors.textPrimary
    val targetColor = when {
        isActive -> baseColor
        isHovered -> baseColor.copy(alpha = HoveredDotAlpha)
        else -> baseColor.copy(alpha = InactiveDotAlpha)
    }
    val color by animateColorAsState(targetColor, label = "tv_row_dot_color")
    val size by animateDpAsState(
        targetValue = when {
            isActive -> ActiveDotSize
            isHovered -> HoveredDotSize
            else -> DotSize
        },
        label = "tv_row_dot_size",
    )

    Box(
        modifier = Modifier
            .size(DotHitSize)
            .hoverable(interactionSource)
            .onPointerEvent(PointerEventType.Enter) { onHoverChanged(true) }
            .onPointerEvent(PointerEventType.Exit) { onHoverChanged(false) }
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .background(color = color, shape = CircleShape),
        )
    }
}
