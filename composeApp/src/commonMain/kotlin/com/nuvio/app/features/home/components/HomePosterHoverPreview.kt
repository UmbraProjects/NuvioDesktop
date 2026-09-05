package com.nuvio.app.features.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioPosterWatchedOverlay
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PosterZoomOverlayCoordinator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.details.components.DetailIconAction
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.hoverPreviewEnabledFor
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.isDesktop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.hero_mark_unwatched
import nuvio.composeapp.generated.resources.hero_mark_watched
import nuvio.composeapp.generated.resources.hero_play_trailer
import nuvio.composeapp.generated.resources.hero_remove_from_library
import nuvio.composeapp.generated.resources.home_view_details
import nuvio.composeapp.generated.resources.poster_logo_content_description
import org.jetbrains.compose.resources.stringResource

private const val HoverPreviewOpenDelayMillis = 2_000L
private const val HoverPreviewCloseDelayMillis = 120L
private const val HoverPreviewEnterDurationMillis = 260
private const val HoverPreviewExitDurationMillis = 170
private val HoverPreviewWidth = 420.dp

/**
 * Wraps a poster card so that resting the pointer on it opens the preview card: backdrop, logo,
 * metadata line, description, and the three actions the poster's context menu would otherwise be
 * needed for.
 *
 * Availability is a display-mode question rather than a global one — see [hoverPreviewEnabledFor].
 * Wherever it is off, and on every non-desktop target, this is a pass-through that installs no
 * pointer handling at all.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HomePosterHoverPreview(
    item: MetaPreview,
    isWatched: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    if (!isDesktop) {
        content(modifier)
        return
    }

    val homeSettings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    if (!homeSettings.hoverPreviewEnabledFor()) {
        content(modifier)
        return
    }

    // The zoom overlay is invoked by right-clicking the very card this preview anchors to, so
    // without standing down here the preview would float over the zoomed poster it came from.
    if (PosterZoomOverlayCoordinator.isVisible) {
        content(modifier)
        return
    }

    // Same reasoning for a playing hero trailer, which in Basic fills the window: the preview is a
    // Popup and would otherwise sit on top of the video its own button just started.
    val heroTrailerShowing by HomeHeroTrailerManualTrigger.active.collectAsStateWithLifecycle()
    if (heroTrailerShowing) {
        content(modifier)
        return
    }

    val anchorInteractionSource = remember { MutableInteractionSource() }
    val previewInteractionSource = remember { MutableInteractionSource() }
    val anchorHovered by anchorInteractionSource.collectIsHoveredAsState()
    val previewHovered by previewInteractionSource.collectIsHoveredAsState()
    var previewVisible by remember(item.type, item.id) { mutableStateOf(false) }
    var popupMounted by remember(item.type, item.id) { mutableStateOf(false) }
    var previewDismissedByScroll by remember(item.type, item.id) { mutableStateOf(false) }
    val actionScope = rememberCoroutineScope()
    val libraryItem = remember(item) { item.toLibraryItem(savedAtEpochMs = 0L) }

    // Derived from the library's own state rather than latched when the card opens: a remote
    // library provider applies the change asynchronously, so a latched value would leave the
    // button on its pre-toggle label until the card was reopened.
    val libraryState by LibraryRepository.uiState.collectAsStateWithLifecycle()
    val previewIsSaved = remember(libraryState, item.id, item.type) {
        LibraryRepository.isSaved(item.id, item.type)
    }

    val density = LocalDensity.current
    val positionProvider = remember(density) {
        HomePosterPreviewPositionProvider(
            edgeMarginPx = with(density) { NuvioTokens.Space.s16.roundToPx() },
            topOverlapPx = with(density) { NuvioTokens.Space.s24.roundToPx() },
        )
    }

    LaunchedEffect(anchorHovered, previewHovered, previewDismissedByScroll) {
        if (previewDismissedByScroll) {
            if (!anchorHovered && !previewHovered) {
                previewDismissedByScroll = false
            }
            return@LaunchedEffect
        }
        if (anchorHovered || previewHovered) {
            if (!popupMounted) {
                delay(HoverPreviewOpenDelayMillis)
                popupMounted = true
                withFrameNanos { }
            }
            previewVisible = true
        } else if (popupMounted) {
            delay(HoverPreviewCloseDelayMillis)
            previewVisible = false
            delay(HoverPreviewExitDurationMillis.toLong())
            popupMounted = false
        }
    }

    val onWatchedClick = remember(item) {
        {
            actionScope.launch {
                WatchingActions.togglePosterWatched(item)
            }
            Unit
        }
    }
    val onSaveClick = remember(libraryItem) {
        {
            LibraryRepository.toggleSaved(libraryItem)
        }
    }
    // Offered only while a hero has actually announced it will play a trailer for a title it is not
    // showing — today that is Basic's home hero. On a catalog grid, in Collections, or in any mode
    // whose hero already follows focus, nothing would accept the request and the button would be
    // one that visibly does nothing.
    val trailerActionAvailable by HomeHeroTrailerManualTrigger.acceptsTargetedRequests
        .collectAsStateWithLifecycle()
    val onTrailerClick = remember(item) {
        {
            HomeHeroTrailerManualTrigger.trigger(item)
        }
    }

    Box(modifier = modifier) {
        content(Modifier.hoverable(anchorInteractionSource))

        if (popupMounted) {
            Popup(
                popupPositionProvider = positionProvider,
                properties = PopupProperties(focusable = false),
            ) {
                AnimatedVisibility(
                    visible = previewVisible,
                    enter = fadeIn(
                        animationSpec = tween(
                            durationMillis = HoverPreviewEnterDurationMillis,
                            easing = NuvioTokens.Motion.decelerate,
                        ),
                    ) + scaleIn(
                        animationSpec = tween(
                            durationMillis = HoverPreviewEnterDurationMillis,
                            easing = NuvioTokens.Motion.emphasized,
                        ),
                        initialScale = 0.9f,
                    ) + slideInVertically(
                        animationSpec = tween(
                            durationMillis = HoverPreviewEnterDurationMillis,
                            easing = NuvioTokens.Motion.decelerate,
                        ),
                        initialOffsetY = { height -> height / 18 },
                    ),
                    exit = fadeOut(
                        animationSpec = tween(HoverPreviewExitDurationMillis),
                    ) + scaleOut(
                        animationSpec = tween(HoverPreviewExitDurationMillis),
                        targetScale = 0.97f,
                    ) + slideOutVertically(
                        animationSpec = tween(HoverPreviewExitDurationMillis),
                        targetOffsetY = { height -> height / 32 },
                    ),
                ) {
                    HomePosterPreviewCard(
                        item = item,
                        isWatched = isWatched,
                        isSaved = previewIsSaved,
                        onTrailerClick = onTrailerClick.takeIf { trailerActionAvailable },
                        modifier = Modifier
                            .hoverable(previewInteractionSource)
                            // A scroll under the card means the user has moved on: the row slides
                            // out from behind a preview still anchored to a poster that is no
                            // longer where the pointer is.
                            .onPointerEvent(PointerEventType.Scroll) {
                                previewDismissedByScroll = true
                                previewVisible = false
                                popupMounted = false
                            },
                        onClick = onClick,
                        onLongClick = onLongClick,
                        onWatchedClick = onWatchedClick,
                        onSaveClick = onSaveClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePosterPreviewCard(
    item: MetaPreview,
    isWatched: Boolean,
    isSaved: Boolean,
    onTrailerClick: (() -> Unit)?,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onWatchedClick: () -> Unit,
    onSaveClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)
    val previewBackground = tokens.colors.background
    val clickInteractionSource = remember { MutableInteractionSource() }
    var logoLoadError by remember(item.type, item.id, item.logo) { mutableStateOf(false) }
    val logoUrl = item.logo?.takeIf { it.isNotBlank() && !logoLoadError }

    Column(
        modifier = modifier
            .width(HoverPreviewWidth)
            .shadow(tokens.elevation.overlay, shape)
            .clip(shape)
            .background(previewBackground)
            .border(tokens.borders.hairline, tokens.colors.borderStrong, shape)
            .clickable(
                interactionSource = clickInteractionSource,
                indication = null,
                enabled = onClick != null,
            ) {
                onClick?.invoke()
            }
            .secondaryClick(onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(tokens.colors.surface),
        ) {
            val artworkUrl = item.banner ?: item.poster
            if (!artworkUrl.isNullOrBlank()) {
                NuvioAsyncImage(
                    model = artworkUrl,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.58f to previewBackground.copy(alpha = 0.08f),
                                1f to previewBackground.copy(alpha = 0.94f),
                            ),
                        ),
                    ),
            )

            if (logoUrl != null) {
                NuvioAsyncImage(
                    model = logoUrl,
                    contentDescription = stringResource(Res.string.poster_logo_content_description, item.name),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = NuvioTokens.Space.s18, bottom = NuvioTokens.Space.s14)
                        .width(180.dp)
                        .height(52.dp),
                    alignment = Alignment.CenterStart,
                    contentScale = ContentScale.Fit,
                    clipToBounds = false,
                    onError = { logoLoadError = true },
                )
            } else {
                Text(
                    text = item.name,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = NuvioTokens.Space.s18, vertical = NuvioTokens.Space.s16),
                    style = MaterialTheme.typography.headlineLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            NuvioPosterWatchedOverlay(
                isWatched = isWatched,
                padding = NuvioTokens.Space.s10,
            )
        }

        Column(
            modifier = Modifier.padding(
                start = NuvioTokens.Space.s18,
                top = NuvioTokens.Space.s14,
                end = NuvioTokens.Space.s18,
                bottom = NuvioTokens.Space.s18,
            ),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        ) {
            Text(
                text = item.previewMetadataLine(),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            item.genres
                .take(3)
                .joinToString(" • ")
                .takeIf { it.isNotBlank() }
                ?.let { genres ->
                    Text(
                        text = genres,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (onClick != null) {
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s2))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(NuvioTokens.Space.s40),
                        color = tokens.colors.accent,
                        contentColor = tokens.colors.onAccent,
                        shape = tokens.shapes.button,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = onClick)
                                .padding(horizontal = NuvioTokens.Space.s16),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(Res.string.home_view_details),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.width(NuvioTokens.Space.s8))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(NuvioTokens.Icon.sm),
                            )
                        }
                    }

                    onTrailerClick?.let { playTrailer ->
                        DetailIconAction(
                            label = stringResource(Res.string.hero_play_trailer),
                            icon = Icons.Default.Movie,
                            active = false,
                            onClick = playTrailer,
                            size = NuvioTokens.Space.s40,
                        )
                    }

                    DetailIconAction(
                        label = if (isWatched) {
                            stringResource(Res.string.hero_mark_unwatched)
                        } else {
                            stringResource(Res.string.hero_mark_watched)
                        },
                        icon = if (isWatched) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.CheckCircleOutline
                        },
                        active = isWatched,
                        onClick = onWatchedClick,
                        size = NuvioTokens.Space.s40,
                    )

                    DetailIconAction(
                        label = if (isSaved) {
                            stringResource(Res.string.hero_remove_from_library)
                        } else {
                            stringResource(Res.string.hero_add_to_library)
                        },
                        icon = if (isSaved) Icons.Default.Check else Icons.Default.Add,
                        active = isSaved,
                        onClick = onSaveClick,
                        size = NuvioTokens.Space.s40,
                    )
                }
            }
        }
    }
}

private fun MetaPreview.previewMetadataLine(): String =
    buildList {
        add(type.replaceFirstChar(Char::uppercase))
        releaseInfo
            ?.takeIf { it.isNotBlank() }
            ?.let(::formatReleaseDateForDisplay)
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
        imdbRating
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { add("IMDb $it") }
    }.joinToString(" • ")

private class HomePosterPreviewPositionProvider(
    private val edgeMarginPx: Int,
    private val topOverlapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - edgeMarginPx)
            .coerceAtLeast(edgeMarginPx)
        val maxY = (windowSize.height - popupContentSize.height - edgeMarginPx)
            .coerceAtLeast(edgeMarginPx)
        val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val preferredY = anchorBounds.top - topOverlapPx

        return IntOffset(
            x = centeredX.coerceIn(edgeMarginPx, maxX),
            y = preferredY.coerceIn(edgeMarginPx, maxY),
        )
    }
}
