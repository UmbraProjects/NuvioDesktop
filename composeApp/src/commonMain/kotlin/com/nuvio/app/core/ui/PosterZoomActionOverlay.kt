package com.nuvio.app.core.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Geometry of the shelf card that was long-pressed, captured in root coordinates
 * right before the long-press callback fires. Used as the start frame of the
 * shared-element zoom in [NuvioPosterZoomActionOverlay].
 */
data class PosterZoomAnchor(
    val boundsInRoot: Rect,
    val imageUrl: String?,
    val cornerRadius: Dp,
    /**
     * The card's own second choice, carried so the preview degrades exactly as the card does.
     * A poster-service URL (PostersPlus/RPDB) 404s for any title the service has no art for, and
     * without this the preview showed an empty card for a poster that is visibly on screen.
     */
    val fallbackImageUrl: String? = null,
)

/**
 * Hand-off slot between the pressed card and the overlay host. The card stashes
 * its anchor synchronously inside the long-press callback; the host consumes it
 * in the same call stack, so the value can never go stale.
 */
object PosterZoomAnchorHolder {
    private var pending: PosterZoomAnchor? = null

    fun stash(anchor: PosterZoomAnchor) {
        pending = anchor
    }

    fun consume(): PosterZoomAnchor? = pending.also { pending = null }
}

/**
 * Whether the zoom action overlay is on screen, published so surfaces that also react to the
 * pointer can stand down while it is. Today that is the poster hover preview: the overlay is
 * launched from a right-click on the very card the preview is anchored to, and without this the
 * preview card would sit on top of the zoomed poster it was invoked from.
 *
 * Set by the overlay's own composition rather than by whatever opens it, so a path that opens
 * the bottom sheet instead cannot leave this stuck on.
 */
object PosterZoomOverlayCoordinator {
    var isVisible by mutableStateOf(false)
        private set

    internal fun show() {
        isVisible = true
    }

    internal fun hide() {
        isVisible = false
    }

    /**
     * The open sheet's own close (its animation, then its onDismissed). Desktop has no platform back
     * handler (PlatformBackHandler is a no-op there), so the app's Back (Escape, a pad's B, the mouse's
     * back button) asks here first; without it Back went to the page behind the sheet (Nov 20 2026).
     */
    private var closeHandler: (() -> Unit)? = null

    internal fun setCloseHandler(handler: (() -> Unit)?) {
        closeHandler = handler
    }

    /** Closes the open sheet, if there is one. True when Back was used up by it. */
    fun requestClose(): Boolean {
        val close = closeHandler ?: return false
        if (!isVisible) return false
        close()
        return true
    }
}

class PosterZoomOverlayAction(
    val icon: ImageVector,
    val label: String,
    val isDestructive: Boolean = false,
    val onSelected: () -> Unit,
    /** Right-click (desktop) alternative to [onSelected]; the overlay closes the same way. */
    val onSecondarySelected: (() -> Unit)? = null,
    /**
     * [NuvioContextMenu] draws a separator wherever this changes from one row to the next; the other
     * presentations ignore it.
     */
    val group: Int = 0,
    /**
     * [NuvioContextMenu] dims a disabled row and skips it for clicks and focus (the text fields'
     * Cut/Copy with nothing selected); the other presentations ignore it.
     */
    val enabled: Boolean = true,
)

private enum class PosterZoomPhase {
    Open,
    Closing,
    Disintegrating,
}

private const val PosterZoomVisibilityThreshold = 0.0005f
private val PosterZoomExpandSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 340f,
    visibilityThreshold = PosterZoomVisibilityThreshold,
)
private val PosterZoomCollapseSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 460f,
    visibilityThreshold = PosterZoomVisibilityThreshold,
)
private val PosterZoomMenuSpring = spring<Float>(
    dampingRatio = 0.8f,
    stiffness = 420f,
    visibilityThreshold = Spring.DefaultDisplacementThreshold,
)

/**
 * Apple-style long-press preview: the pressed poster lifts off the shelf and
 * springs to the centre of the screen, the app content behind is blurred and
 * dimmed, and an action menu cascades in underneath. Destructive actions burn
 * the centred poster away with [DisintegratingContainer] instead of zooming back.
 */
@Composable
fun NuvioPosterZoomActionOverlay(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    fallbackImageUrl: String? = null,
    isWatched: Boolean = false,
    depthSurface: NuvioCardDepthSurface = NuvioCardDepthSurface.Posters,
    anchor: PosterZoomAnchor?,
    actions: List<PosterZoomOverlayAction>,
    hazeState: HazeState,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val previewShape = RoundedCornerShape(PosterZoomFinalCornerRadius)
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // A context menu shows a snapshot of the moment it was invoked; don't let
    // repository updates mid-animation relabel or reorder the rows.
    val frozenActions = remember { actions }

    var currentImageUrl by remember(imageUrl, fallbackImageUrl) { mutableStateOf(imageUrl) }

    val zoom = remember { Animatable(0f) }
    val scrim = remember { Animatable(0f) }
    val menu = remember { Animatable(0f) }
    val shadowFade = remember { Animatable(1f) }
    var phase by remember { mutableStateOf(PosterZoomPhase.Open) }

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var slotBounds by remember { mutableStateOf<Rect?>(null) }

    DisposableEffect(Unit) {
        PosterZoomOverlayCoordinator.show()
        onDispose { PosterZoomOverlayCoordinator.hide() }
    }

    LaunchedEffect(Unit) {
        launch { scrim.animateTo(1f, tween(durationMillis = 260, easing = NuvioTokens.Motion.standard)) }
        launch { zoom.animateTo(1f, PosterZoomExpandSpring) }
        launch {
            delay(60)
            menu.animateTo(1f, PosterZoomMenuSpring)
        }
    }

    fun close() {
        if (phase != PosterZoomPhase.Open) return
        phase = PosterZoomPhase.Closing
        scope.launch {
            coroutineScope {
                launch { menu.animateTo(0f, tween(durationMillis = 140)) }
                launch {
                    delay(80)
                    scrim.animateTo(0f, tween(durationMillis = 260, easing = NuvioTokens.Motion.standard))
                }
                launch { zoom.animateTo(0f, PosterZoomCollapseSpring) }
            }
            onDismissed()
        }
    }

    fun select(action: PosterZoomOverlayAction) {
        if (phase != PosterZoomPhase.Open) return
        if (action.isDestructive) {
            phase = PosterZoomPhase.Disintegrating
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            action.onSelected()
            scope.launch {
                launch { menu.animateTo(0f, tween(durationMillis = 160)) }
                launch { shadowFade.animateTo(0f, tween(durationMillis = 350)) }
            }
        } else {
            action.onSelected()
            close()
        }
    }

    fun selectSecondary(action: PosterZoomOverlayAction) {
        val secondary = action.onSecondarySelected ?: return
        if (phase != PosterZoomPhase.Open) return
        secondary()
        close()
    }

    PlatformBackHandler(enabled = true) {
        close()
    }
    DisposableEffect(Unit) {
        val handler: () -> Unit = { close() }
        PosterZoomOverlayCoordinator.setCloseHandler(handler)
        onDispose { PosterZoomOverlayCoordinator.setCloseHandler(null) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates -> rootOrigin = coordinates.positionInRoot() }
            .pointerInput(Unit) {
                detectTapGestures { close() }
            },
    ) {
        val anchorAspect = anchor
            ?.boundsInRoot
            ?.takeIf { it.height > 0f }
            ?.let { it.width / it.height }
            ?: 0.675f
        val aspect = anchorAspect.coerceIn(0.35f, 2.4f)
        // The actions as a grid of tiles under the poster (2026-11-15): a list of ten rows ran off the bottom of
        // the screen. Up to four across, as many rows as that takes; the poster gets whatever height is left, so
        // the whole sheet always fits.
        val fitColumns = ((maxWidth - NuvioTokens.Space.s48 + PosterZoomTileGap) / (PosterZoomTileMinWidth + PosterZoomTileGap))
            .toInt().coerceAtLeast(1)
        val gridColumns = frozenActions.size.coerceIn(1, 4).coerceAtMost(fitColumns)
        val gridRows = (frozenActions.size + gridColumns - 1) / gridColumns
        val tileWidth = min(
            PosterZoomTileMaxWidth,
            (maxWidth - NuvioTokens.Space.s48 - PosterZoomTileGap * (gridColumns - 1)) / gridColumns,
        )
        val menuWidth = tileWidth * gridColumns + PosterZoomTileGap * (gridColumns - 1)
        val gridHeight = PosterZoomTileHeight * gridRows + PosterZoomTileGap * (gridRows - 1).coerceAtLeast(0)
        val maxPosterWidth = if (aspect >= 1f) maxWidth * 0.8f else maxWidth * 0.6f
        val roomForPoster = maxHeight - gridHeight - PosterZoomSheetChrome
        val posterHeight = min(maxPosterWidth / aspect, min(maxHeight * 0.5f, roomForPoster)).coerceAtLeast(96.dp)
        val posterWidth = posterHeight * aspect
        val columnWidth = max(posterWidth, menuWidth)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeEffect(state = hazeState) {
                    blurRadius = 36.dp
                    inputScale = HazeInputScale.Auto
                    noiseFactor = 0f
                    blurredEdgeTreatment = BlurredEdgeTreatment.Rectangle
                    clipToAreasBounds = false
                    expandLayerBounds = true
                    alpha = scrim.value.coerceIn(0f, 1f)
                },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(alpha = 0.45f * scrim.value.coerceIn(0f, 1f)),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(columnWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Invisible slot marking where the zoomed poster comes to rest.
            Box(
                modifier = Modifier
                    .size(width = posterWidth, height = posterHeight)
                    .onGloballyPositioned { coordinates -> slotBounds = coordinates.boundsInRoot() },
            )

            Spacer(modifier = Modifier.height(NuvioTokens.Space.s18))

            Column(
                modifier = Modifier
                    .width(columnWidth)
                    .graphicsLayer {
                        val progress = menu.value.coerceIn(0f, 1f)
                        alpha = progress
                        translationY = (1f - progress) * NuvioTokens.Space.s10.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s12),
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = NuvioTokens.Space.s12),
                    )
                }
            }

            Spacer(modifier = Modifier.height(NuvioTokens.Space.s14))

            val firstTile = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                // The keyboard (and a pad) starts on the first action; the arrows walk the grid.
                delay(120)
                firstTile.requestFocusLogged()
            }
            Column(
                modifier = Modifier
                    .width(menuWidth)
                    .graphicsLayer {
                        val progress = menu.value
                        val clamped = progress.coerceIn(0f, 1f)
                        alpha = clamped
                        val scale = 0.82f + 0.18f * progress
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .arrowKeyFocusNavigation(),
                verticalArrangement = Arrangement.spacedBy(PosterZoomTileGap),
            ) {
                frozenActions.chunked(gridColumns).forEachIndexed { rowIndex, rowActions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(PosterZoomTileGap)) {
                        rowActions.forEachIndexed { columnIndex, action ->
                            val index = rowIndex * gridColumns + columnIndex
                            PosterZoomMenuTile(
                                action = action,
                                enabled = phase == PosterZoomPhase.Open,
                                onSelected = { select(action) },
                                onSecondarySelected = action.onSecondarySelected?.let { { selectSecondary(action) } },
                                modifier = Modifier
                                    .size(width = tileWidth, height = PosterZoomTileHeight)
                                    .then(if (index == 0) Modifier.focusRequester(firstTile) else Modifier)
                                    .graphicsLayer {
                                        val stagger = index * 0.04f
                                        val progress = ((menu.value.coerceIn(0f, 1f) - stagger) / (1f - stagger))
                                            .coerceIn(0f, 1f)
                                        alpha = progress
                                        translationY = (1f - progress) * NuvioTokens.Space.s8.toPx()
                                    },
                            )
                        }
                    }
                }
            }
        }

        // The travelling poster itself, drawn above the slot column.
        Box(
            modifier = Modifier
                .size(width = posterWidth, height = posterHeight)
                .graphicsLayer {
                    val slot = slotBounds
                    if (slot == null || slot.width <= 0f) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val progress = zoom.value
                    val clamped = progress.coerceIn(0f, 1f)
                    val start = posterStartBounds(anchor = anchor, slot = slot)
                    val scale = posterScale(anchor = anchor, slot = slot, progress = progress)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = lerp(start.left, slot.left, progress) - rootOrigin.x
                    translationY = lerp(start.top, slot.top, progress) - rootOrigin.y
                    alpha = if (anchor == null) {
                        clamped
                    } else {
                        (clamped / 0.08f).coerceIn(0f, 1f)
                    }
                    shape = RoundedCornerShape(posterCornerRadiusPx(anchor, clamped, scale))
                    clip = false
                    shadowElevation = NuvioTokens.Space.s24.toPx() * clamped * shadowFade.value
                },
        ) {
            DisintegratingContainer(
                disintegrating = phase == PosterZoomPhase.Disintegrating,
                onDisintegrated = {
                    scope.launch {
                        scrim.animateTo(0f, tween(durationMillis = 300, easing = NuvioTokens.Motion.standard))
                        onDismissed()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val clamped = zoom.value.coerceIn(0f, 1f)
                            val slot = slotBounds
                            val scale = if (slot != null && slot.width > 0f) {
                                posterScale(anchor = anchor, slot = slot, progress = zoom.value)
                            } else {
                                1f
                            }
                            shape = RoundedCornerShape(posterCornerRadiusPx(anchor, clamped, scale))
                            clip = true
                        }
                        .background(tokens.colors.surfaceCard)
                        .nuvioCardDepth(
                            shape = previewShape,
                            surface = depthSurface,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (currentImageUrl != null) {
                        NuvioAsyncImage(
                            model = currentImageUrl,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            // Same swap the shelf card makes, so a poster the card recovered by
                            // falling back is not an empty rectangle once it is centred.
                            onError = {
                                if (fallbackImageUrl != null && currentImageUrl != fallbackImageUrl) {
                                    currentImageUrl = fallbackImageUrl
                                }
                            },
                        )
                    } else {
                        Text(
                            text = title,
                            modifier = Modifier.padding(NuvioTokens.Space.s14),
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.colors.textMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    NuvioPosterWatchedOverlay(
                        isWatched = isWatched,
                        modifier = Modifier.graphicsLayer {
                            val slot = slotBounds
                            if (slot != null && slot.width > 0f) {
                                val scale = posterScale(
                                    anchor = anchor,
                                    slot = slot,
                                    progress = zoom.value,
                                ).coerceAtLeast(0.001f)
                                scaleX = 1f / scale
                                scaleY = 1f / scale
                                transformOrigin = TransformOrigin(1f, 0f)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterZoomMenuTile(
    action: PosterZoomOverlayAction,
    enabled: Boolean,
    onSelected: () -> Unit,
    onSecondarySelected: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val contentColor = if (action.isDestructive) tokens.colors.danger else tokens.colors.textPrimary
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsKeyboardFocusedAsState()
    val shape = RoundedCornerShape(NuvioTokens.Space.s16)
    // The app's glass (night base, frosted fill, hairline edge), lit in the accent when pointed at or focused,
    // rather than the old flat grey card.
    val night = NuvioGlass.night(tokens.colors.background, tokens.colors.accent)
    val lit = hovered || focused
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (lit) androidx.compose.ui.graphics.lerp(night, tokens.colors.accent, 0.26f) else night.copy(alpha = 0.92f))
            .ditheredBackground(NuvioGlass.Fill, shape)
            .border(
                if (focused) 2.dp else 1.dp,
                if (lit) tokens.colors.accent.copy(alpha = 0.85f) else NuvioGlass.EdgeColor,
                shape,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onSelected)
            .secondaryClick(onSecondarySelected?.takeIf { enabled })
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s10),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = if (lit && !action.isDestructive) Color.White else contentColor.copy(alpha = 0.86f),
            modifier = Modifier.size(NuvioTokens.Icon.md),
        )
        Spacer(modifier = Modifier.height(NuvioTokens.Space.s8))
        Text(
            text = action.label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val PosterZoomTileGap = 8.dp
private val PosterZoomTileHeight = 88.dp
private val PosterZoomTileMinWidth = 132.dp
private val PosterZoomTileMaxWidth = 176.dp

/** Everything in the sheet's column besides the poster and the grid: title, subtitle, spacers and a margin. */
private val PosterZoomSheetChrome = 150.dp

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private fun posterStartBounds(anchor: PosterZoomAnchor?, slot: Rect): Rect =
    anchor?.boundsInRoot ?: slot.scaleAboutCenter(0.85f)

private fun posterScale(anchor: PosterZoomAnchor?, slot: Rect, progress: Float): Float =
    lerp(posterStartBounds(anchor = anchor, slot = slot).width / slot.width, 1f, progress)

private fun Rect.scaleAboutCenter(factor: Float): Rect {
    val newWidth = width * factor
    val newHeight = height * factor
    return Rect(
        offset = Offset(center.x - newWidth / 2f, center.y - newHeight / 2f),
        size = androidx.compose.ui.geometry.Size(newWidth, newHeight),
    )
}

private fun androidx.compose.ui.unit.Density.posterCornerRadiusPx(
    anchor: PosterZoomAnchor?,
    clampedProgress: Float,
    scale: Float,
): Float {
    val finalRadius = PosterZoomFinalCornerRadius.toPx()
    val startRadius = anchor?.cornerRadius?.toPx() ?: finalRadius
    val apparent = lerp(startRadius, finalRadius, clampedProgress)
    return if (scale > 0f) apparent / scale else apparent
}

private val PosterZoomFinalCornerRadius = NuvioTokens.Space.s18
