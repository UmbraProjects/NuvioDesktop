package com.nuvio.app.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_view_all
import nuvio.composeapp.generated.resources.poster_logo_content_description
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.sqrt

enum class NuvioPosterShape {
    Poster,
    Square,
    Landscape,
}

enum class NuvioViewAllPillSize {
    Default,
    Compact,
}

@Composable
fun <T> NuvioShelfSection(
    title: String,
    entries: List<T>,
    modifier: Modifier = Modifier,
    headerHorizontalPadding: Dp = 0.dp,
    rowContentPadding: PaddingValues = PaddingValues(0.dp),
    itemSpacing: Dp = 10.dp,
    showHeaderAccent: Boolean = true,
    onViewAllClick: (() -> Unit)? = null,
    viewAllPillSize: NuvioViewAllPillSize = NuvioViewAllPillSize.Default,
    // Optional content placed on the header's line, immediately after the title (TV Mode's
    // row-jump dots). Present only where a caller opts in, so ordinary shelves are unchanged.
    headerTrailingContent: (@Composable () -> Unit)? = null,
    focusedItemIndex: Int? = null,
    onHoverItem: ((Int) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    isLoadingMore: Boolean = false,
    isKeyboardNavigation: Boolean = false,
    key: ((T) -> Any)? = null,
    rowState: LazyListState = rememberLazyListState(),
    itemContent: @Composable (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // Horizontal infinite scroll: request the next page when the row is scrolled within a few items
    // of the end. onLoadMore is idempotent, so repeated triggers while a page loads are harmless.
    if (onLoadMore != null) {
        val latestOnLoadMore by rememberUpdatedState(onLoadMore)
        LaunchedEffect(rowState, entries.size) {
            snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { lastVisibleIndex ->
                    if (entries.isNotEmpty() && lastVisibleIndex >= entries.size - ShelfLoadMoreThreshold) {
                        latestOnLoadMore()
                    }
                }
        }
    }
    LaunchedEffect(focusedItemIndex, entries) {
        val target = focusedItemIndex
        if (target == null || target !in entries.indices) return@LaunchedEffect
        // Item 0 must always snap to scroll offset 0 — after scrolling right and back,
        // item 0 can be partially clipped on the left while still counting as "visible",
        // so the normal off-screen check would leave it cut off.
        if (target == 0) {
            rowState.animateScrollToItem(0, scrollOffset = 0)
            return@LaunchedEffect
        }
        
        // Wait for the LazyRow to lay out the new items if they were just added via pagination
        if (target >= rowState.layoutInfo.totalItemsCount) {
            androidx.compose.runtime.snapshotFlow { rowState.layoutInfo.totalItemsCount }
                .first { it > target }
        }

        val layoutInfo = rowState.layoutInfo
        // For mouse hover, only scroll when the item is completely off-screen to prevent
        // hover cascade loops. For keyboard navigation, scroll if the item is even partially clipped.
        val isVisible = if (isKeyboardNavigation) {
            layoutInfo.visibleItemsInfo.any { item -> 
                item.index == target && 
                item.offset >= layoutInfo.viewportStartOffset && 
                item.offset + item.size <= layoutInfo.viewportEndOffset 
            }
        } else {
            layoutInfo.visibleItemsInfo.any { item -> item.index == target }
        }
        if (!isVisible) {
            // When navigating with keyboard, scrolling an item into view perfectly from the right edge
            // by just using animateScrollToItem(target) snaps it to the far LEFT of the screen, which is
            // visually jarring. We use an offset scroll if possible to bring it into view gently.
            // Wait, animateScrollToItem(target) natively snaps to the start, but we can't easily calculate
            // the offset without knowing the item widths. For now, snapping to start is acceptable and ensures visibility.
            rowState.animateScrollToItem(target)
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap + NuvioTokens.Space.s2),
    ) {
        if (title.isNotBlank()) {
            NuvioShelfSectionHeader(
                title = title,
                modifier = Modifier.padding(horizontal = headerHorizontalPadding),
                showAccent = showHeaderAccent,
                onViewAllClick = onViewAllClick,
                viewAllPillSize = viewAllPillSize,
                trailingContent = headerTrailingContent,
            )
        }
        LazyRow(
            state = rowState,
            modifier = Modifier
                .desktopShelfDragScroll(rowState)
                .desktopShelfEdgeScroll(rowState, isMouseActive = !isKeyboardNavigation),
            contentPadding = rowContentPadding,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            if (key != null) {
                val keyedEntries = entries.withDuplicateSafeLazyKeys(key)
                itemsIndexed(
                    items = keyedEntries,
                    key = { _, keyedEntry -> keyedEntry.lazyKey },
                ) { index, keyedEntry ->
                    NuvioShelfItemSlot(
                        focused = index == focusedItemIndex,
                        onHover = onHoverItem?.let { { it(index) } },
                    ) {
                        itemContent(keyedEntry.value)
                    }
                }
            } else {
                itemsIndexed(entries) { index, entry ->
                    NuvioShelfItemSlot(
                        focused = index == focusedItemIndex,
                        onHover = onHoverItem?.let { { it(index) } },
                    ) {
                        itemContent(entry)
                    }
                }
            }
            if (isLoadingMore) {
                item(key = "nuvio-shelf-load-more") {
                    Box(
                        modifier = Modifier.fillMaxHeight().padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

private const val ShelfLoadMoreThreshold = 6

// How much of the header row the title may occupy when trailing content is present. Longer names
// ellipsize rather than run under the centred trailing slot.
private const val HeaderTitleMaxWidthFraction = 0.26f

/**
 * The share of the header row kept clear on *each* side of centred trailing content.
 *
 * Trailing content is centred on the row, so one margin governs both edges: it must clear the
 * title's own [HeaderTitleMaxWidthFraction] column on the left, which in turn leaves far more than
 * the view-all pill needs on the right. Content wider than what's left over is expected to cap
 * itself at that width rather than spill — see HomeTvRowDotStrip.
 */
internal const val NuvioShelfHeaderTrailingSideMarginFraction = 0.28f

// Below-poster labels are centered and capped to this fraction of the poster width;
// anything truncated is readable in full via NuvioPosterHoverTooltip.
const val PosterLabelWidthFraction = 0.65f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NuvioShelfItemSlot(
    focused: Boolean,
    onHover: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scale by animateFloatAsState(targetValue = if (focused) 1.04f else 1f)
    Box(
        modifier = Modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (onHover != null) {
                    Modifier.onPointerEvent(PointerEventType.Enter) { onHover() }
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

private fun Modifier.desktopShelfDragScroll(
    state: LazyListState,
): Modifier {
    if (!isDesktop) return this

    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            var totalDx = 0f
            var totalDy = 0f
            var dragging = false

            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (!dragging) {
                    val horizontalDrag =
                        abs(totalDx) > viewConfiguration.touchSlop && abs(totalDx) > abs(totalDy)
                    val verticalDrag =
                        abs(totalDy) > viewConfiguration.touchSlop && abs(totalDy) > abs(totalDx)

                    when {
                        verticalDrag -> break
                        horizontalDrag -> dragging = true
                        else -> continue
                    }
                }

                state.dispatchRawDelta(-delta.x)
                change.consume()
            }
        }
    }
}

private fun Modifier.desktopShelfEdgeScroll(state: LazyListState, isMouseActive: Boolean): Modifier {
    if (!isDesktop) return this
    return this.pointerInput(state) {
        // PointerInputScope (Compose 1.7+) no longer extends CoroutineScope, so we
        // need coroutineScope { } to get a scope for launching child coroutines.
        val pointerScope = this
        val positionChannel = Channel<Float?>(Channel.CONFLATED)
        coroutineScope {
            val scope = this
            scope.launch {
                var scrollJob: Job? = null
                var currentDir = 0
                for (x in positionChannel) {
                    val width = pointerScope.size.width.toFloat()
                    val edgeZone = width * 0.10f
                    val newDir = when {
                        !isMouseActive || x == null -> 0
                        x < edgeZone -> -1
                        x > width - edgeZone -> 1
                        else -> 0
                    }
                    if (newDir != currentDir) {
                        currentDir = newDir
                        scrollJob?.cancel()
                        scrollJob = if (newDir != 0) {
                            scope.launch {
                                val delta = newDir * 6f
                                while (true) {
                                    state.dispatchRawDelta(delta)
                                    delay(16)
                                }
                            }
                        } else null
                    }
                }
            }
            pointerScope.awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.Exit -> positionChannel.trySend(null)
                        PointerEventType.Move ->
                            positionChannel.trySend(event.changes.firstOrNull()?.position?.x)
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
fun NuvioPosterCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    fallbackImageUrl: String? = null,
    shape: NuvioPosterShape = NuvioPosterShape.Poster,
    basePosterWidthDpOverride: Int? = null,
    detailLine: String? = null,
    showTitleBelow: Boolean = true,
    bottomLeftLogoUrl: String? = null,
    bottomLeftText: String? = null,
    artworkContent: (@Composable BoxScope.() -> Unit)? = null,
    isWatched: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    // Only Home and Collections (FolderDetailScreen) render posters through this component
    // today, both of which respect TV Mode — see rememberHomePosterCardStyleUiState's doc.
    // If a non-home screen ever adopts NuvioPosterCard, reconsider this call.
    val posterCardStyle = rememberHomePosterCardStyleUiState()
    val tokens = MaterialTheme.nuvio
    val basePosterWidthDp = basePosterWidthDpOverride ?: posterCardStyle.widthDp
    val cardWidth = shape.cardWidth(basePosterWidthDp = basePosterWidthDp)
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp)
    val catalogLogoOverlaySize = catalogLogoOverlaySize(
        basePosterWidthDp = basePosterWidthDp,
        shape = shape,
    )
    val hasArtwork = imageUrl != null || artworkContent != null
    val shouldShowTitleBelow = showTitleBelow && !posterCardStyle.hideLabelsEnabled
    // Upstream's 14sp label is balanced around its 126dp poster. This fork supports much larger
    // posters, so scale gently by the square root of the size ratio. The tight clamp preserves
    // upstream sizing on ordinary layouts while LocalDensity continues handling monitor DPI.
    val posterLabelScale = sqrt(basePosterWidthDp.toFloat() / DefaultPosterCardWidthDp)
        .coerceIn(0.96f, 1.32f)
    // Scale the below-poster label with the poster size. A fixed type size reads as tiny next to
    // large artwork (big width slider / high-DPI display), so grow it with the card width — but
    // dampened (75% of the proportional growth) so labels don't dominate large posters — and
    // clamped so small posters stay sensible and huge ones don't get an oversized caption.
    // Base sizes match the pre-scaling styles (bodyMedium 14sp / labelSmall 12sp) so the default
    // poster width renders identically to before; only wider posters grow the label.

    Column(
        modifier = modifier.width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(shape.aspectRatio)
                .clip(cardShape)
                .background(
                    if (!hasArtwork) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                    } else {
                        tokens.colors.surface
                    },
                )
                .then(
                    if (!hasArtwork) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                            shape = cardShape,
                        )
                    } else {
                        Modifier
                    },
                )
                .nuvioPosterDepth(cardShape)
                .posterCardClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    zoomImageUrl = imageUrl,
                    zoomCornerRadius = posterCardStyle.cornerRadiusDp.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkContent != null) {
                artworkContent()
            } else if (imageUrl != null) {
                var currentUrl by remember(imageUrl, fallbackImageUrl) { mutableStateOf(imageUrl) }
                NuvioAsyncImage(
                    model = currentUrl,
                    contentDescription = title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (currentUrl != fallbackImageUrl && fallbackImageUrl != null) {
                            currentUrl = fallbackImageUrl
                        }
                    }
                )
            } else {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s14),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (hasArtwork &&
                (!bottomLeftLogoUrl.isNullOrBlank() || !bottomLeftText.isNullOrBlank())
            ) {
                if (shape == NuvioPosterShape.Landscape) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Transparent,
                                        0.48f to Color.Transparent,
                                        0.76f to Color.Black.copy(alpha = 0.34f),
                                        1f to Color.Black.copy(alpha = 0.76f),
                                    ),
                                ),
                            ),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s10),
                ) {
                    if (!bottomLeftLogoUrl.isNullOrBlank()) {
                        NuvioAsyncImage(
                            model = bottomLeftLogoUrl,
                            contentDescription = stringResource(Res.string.poster_logo_content_description, title),
                            modifier = Modifier
                                .width(catalogLogoOverlaySize.width)
                                .height(catalogLogoOverlaySize.height),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(
                            text = bottomLeftText.orEmpty(),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = tokens.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = catalogLogoOverlaySize.textMaxWidth),
                        )
                    }
                }
            }

            NuvioPosterWatchedOverlay(isWatched = isWatched)
        }
        if (shouldShowTitleBelow) {
            // Label is centered and capped at 65% of the poster width; truncated names are
            // readable in full via the hover tooltip, so no shrink-to-fit here.
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.let { style ->
                    style.copy(
                        fontSize = style.fontSize * posterLabelScale,
                        lineHeight = style.lineHeight * posterLabelScale,
                    )
                },
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detailLine.isNullOrBlank()) {
                Text(
                    text = detailLine,
                    style = MaterialTheme.typography.labelSmall.let { style ->
                        style.copy(
                            fontSize = style.fontSize * posterLabelScale,
                            lineHeight = style.lineHeight * posterLabelScale,
                        )
                    },
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Box(modifier = Modifier.height(NuvioTokens.Space.none))
            }
        } else {
            Box(modifier = Modifier.height(NuvioTokens.Space.none))
        }
    }
}

@Composable
private fun NuvioShelfSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    showAccent: Boolean = true,
    onViewAllClick: (() -> Unit)? = null,
    viewAllPillSize: NuvioViewAllPillSize = NuvioViewAllPillSize.Default,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val viewAllPlaceholderModifier = if (onViewAllClick == null) {
        Modifier
            .alpha(0f)
            .clearAndSetSemantics { }
    } else {
        Modifier
    }
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (trailingContent == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NuvioViewAllPill(
                    onClick = onViewAllClick,
                    size = viewAllPillSize,
                    modifier = viewAllPlaceholderModifier,
                )
            }
        } else {
            // Trailing content is centred on the header itself — i.e. on the window's centre line —
            // rather than packed next to the title, so it stays put as the title changes from row to
            // row. That makes the three pieces overlapping siblings instead of a Row: title pinned
            // left, trailing content centred, view-all pill pinned right exactly where it sits
            // without trailing content. Keeping content clear of the title and the pill is the
            // trailing slot's job — see NuvioShelfHeaderTrailingSideMarginFraction.
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .widthIn(max = maxWidth * HeaderTitleMaxWidthFraction),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                trailingContent()
                NuvioViewAllPill(
                    onClick = onViewAllClick,
                    size = viewAllPillSize,
                    modifier = viewAllPlaceholderModifier.align(Alignment.CenterEnd),
                )
            }
        }
        if (showAccent) {
            Box(
                modifier = Modifier
                    .padding(top = NuvioTokens.Space.s6)
                    .width(NuvioTokens.Space.s64 - NuvioTokens.Space.s4)
                    .height(NuvioTokens.Space.s4)
                    .background(
                        color = tokens.colors.accent,
                        shape = tokens.shapes.chip,
                    ),
            )
        }
    }
}

@Composable
private fun NuvioViewAllPill(
    onClick: (() -> Unit)?,
    size: NuvioViewAllPillSize,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val actionSize = if (size == NuvioViewAllPillSize.Compact) NuvioTokens.Space.s32 else NuvioTokens.Space.s40
    val iconSize = if (size == NuvioViewAllPillSize.Compact) NuvioTokens.Icon.sm else tokens.icons.md
    val viewAllText = stringResource(Res.string.home_view_all)

    Box(
        modifier = modifier
            .size(actionSize)
            .background(
                color = tokens.colors.surface,
                shape = RoundedCornerShape(NuvioTokens.Radius.xl),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = viewAllText,
            tint = tokens.colors.textMuted,
            modifier = Modifier.size(iconSize),
        )
    }
}

private val NuvioPosterShape.aspectRatio: Float
    get() = when (this) {
        NuvioPosterShape.Poster -> 0.675f
        NuvioPosterShape.Square -> 1f
        NuvioPosterShape.Landscape -> PosterLandscapeAspectRatio
    }

private data class CatalogLogoOverlaySize(
    val width: Dp,
    val height: Dp,
    val textMaxWidth: Dp,
)

private fun catalogLogoOverlaySize(
    basePosterWidthDp: Int,
    shape: NuvioPosterShape,
): CatalogLogoOverlaySize =
    if (shape == NuvioPosterShape.Landscape) {
        when {
            basePosterWidthDp <= 108 -> CatalogLogoOverlaySize(width = 92.dp, height = 24.dp, textMaxWidth = 120.dp)
            basePosterWidthDp <= 120 -> CatalogLogoOverlaySize(width = 104.dp, height = 28.dp, textMaxWidth = 132.dp)
            basePosterWidthDp <= 132 -> CatalogLogoOverlaySize(width = 116.dp, height = 30.dp, textMaxWidth = 144.dp)
            else -> CatalogLogoOverlaySize(width = 128.dp, height = 34.dp, textMaxWidth = 156.dp)
        }
    } else {
        when {
            basePosterWidthDp <= 108 -> CatalogLogoOverlaySize(width = 72.dp, height = 18.dp, textMaxWidth = 92.dp)
            basePosterWidthDp <= 120 -> CatalogLogoOverlaySize(width = 80.dp, height = 20.dp, textMaxWidth = 104.dp)
            basePosterWidthDp <= 132 -> CatalogLogoOverlaySize(width = 88.dp, height = 22.dp, textMaxWidth = 112.dp)
            else -> CatalogLogoOverlaySize(width = 96.dp, height = 24.dp, textMaxWidth = 124.dp)
        }
    }

private fun NuvioPosterShape.cardWidth(basePosterWidthDp: Int): Dp =
    when (this) {
        NuvioPosterShape.Poster -> basePosterWidthDp.dp
        NuvioPosterShape.Square -> basePosterWidthDp.dp
        NuvioPosterShape.Landscape -> landscapePosterWidth(basePosterWidthDp)
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.posterCardClickable(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    zoomImageUrl: String? = null,
    zoomCornerRadius: Dp = NuvioTokens.Radius.poster,
): Modifier {
    if (onClick == null && onLongClick == null) return this
    val bounds = remember { mutableStateOf<Rect?>(null) }
    val handleLongClick = onLongClick?.let { longClick ->
        {
            bounds.value?.takeIf { zoomImageUrl != null }?.let { cardBounds ->
                PosterZoomAnchorHolder.stash(
                    PosterZoomAnchor(cardBounds, zoomImageUrl, zoomCornerRadius),
                )
            }
            longClick()
        }
    }
    return onGloballyPositioned { coordinates ->
        val position = coordinates.positionInRoot()
        bounds.value = Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
    }.combinedClickable(
        onClick = { onClick?.invoke() },
        onLongClick = handleLongClick,
    ).secondaryClick(handleLongClick)
}
