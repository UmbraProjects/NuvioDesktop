package com.nuvio.app.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_cd_watched
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.hero_mark_unwatched
import nuvio.composeapp.generated.resources.hero_mark_watched
import nuvio.composeapp.generated.resources.hero_remove_from_library
import nuvio.composeapp.generated.resources.poster_go_to_local_library
import org.jetbrains.compose.resources.stringResource
import dev.chrisbanes.haze.HazeState

@Composable
fun NuvioPosterZoomActionSheet(
    item: MetaPreview?,
    isSaved: Boolean,
    isWatched: Boolean,
    anchor: PosterZoomAnchor?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit,
    onStartRewatch: (() -> Unit)? = null,
    rewatchLabel: String = "",
    onOpenInLocalLibrary: (() -> Unit)? = null,
) {
    if (item == null) return
    NuvioPosterZoomActionOverlay(
        imageUrl = anchor?.imageUrl ?: item.poster,
        // Without an anchor (keyboard/TV invocation) `item.poster` is the unresolved first choice,
        // so it needs the same fallback the card would have used.
        fallbackImageUrl = anchor?.fallbackImageUrl ?: item.posterFallback,
        title = item.name,
        subtitle = item.releaseInfo?.takeIf { it.isNotBlank() }?.let(::formatReleaseDateForDisplay)
            ?: item.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
        isWatched = isWatched,
        anchor = anchor,
        actions = buildList {
            add(
                PosterZoomOverlayAction(
                    icon = if (isSaved) Icons.Default.Check else Icons.Default.Add,
                    label = stringResource(if (isSaved) Res.string.hero_remove_from_library else Res.string.hero_add_to_library),
                    onSelected = onToggleLibrary,
                ),
            )
            onStartRewatch?.let { startRewatch ->
                add(
                    PosterZoomOverlayAction(
                        icon = Icons.Default.Replay,
                        label = rewatchLabel,
                        onSelected = startRewatch,
                    ),
                )
            }
            add(
                PosterZoomOverlayAction(
                    icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                    label = stringResource(if (isWatched) Res.string.hero_mark_unwatched else Res.string.hero_mark_watched),
                    onSelected = onToggleWatched,
                ),
            )
            onOpenInLocalLibrary?.let { openLocal ->
                add(
                    PosterZoomOverlayAction(
                        icon = Icons.Default.FolderOpen,
                        label = stringResource(Res.string.poster_go_to_local_library),
                        onSelected = openLocal,
                    ),
                )
            }
        },
        hazeState = hazeState,
        onDismissed = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioPosterActionSheet(
    item: MetaPreview?,
    isSaved: Boolean,
    isWatched: Boolean,
    onDismiss: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit,
    onStartRewatch: (() -> Unit)? = null,
    rewatchLabel: String = "",
    /**
     * Opens this title in the local library settings page. Null when it has no local copy, so the
     * row is only offered for titles that are actually on disk.
     */
    onOpenInLocalLibrary: (() -> Unit)? = null,
    zoomAnchor: PosterZoomAnchor? = null,
    zoomHazeState: HazeState? = null,
) {
    if (item == null) return
    val posterCardStyle = rememberPosterCardStyleUiState()
    if (posterCardStyle.zoomActionPreviewEnabled && zoomHazeState != null) {
        NuvioPosterZoomActionSheet(
            item = item,
            isSaved = isSaved,
            isWatched = isWatched,
            anchor = zoomAnchor,
            hazeState = zoomHazeState,
            onDismiss = onDismiss,
            onToggleLibrary = onToggleLibrary,
            onToggleWatched = onToggleWatched,
            onStartRewatch = onStartRewatch,
            rewatchLabel = rewatchLabel,
            onOpenInLocalLibrary = onOpenInLocalLibrary,
        )
        return
    }
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(
                    sheetState = sheetState,
                    onDismiss = onDismiss,
                )
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.screenHorizontal)),
        ) {
            PosterSheetHeader(item = item)
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = if (isSaved) Icons.Default.Check else Icons.Default.Add,
                title = if (isSaved) {
                    stringResource(Res.string.hero_remove_from_library)
                } else {
                    stringResource(Res.string.hero_add_to_library)
                },
                onClick = {
                    onToggleLibrary()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(
                            sheetState = sheetState,
                            onDismiss = onDismiss,
                        )
                    }
                },
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                title = if (isWatched) {
                    stringResource(Res.string.hero_mark_unwatched)
                } else {
                    stringResource(Res.string.hero_mark_watched)
                },
                onClick = {
                    onToggleWatched()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(
                            sheetState = sheetState,
                            onDismiss = onDismiss,
                        )
                    }
                },
            )
            onStartRewatch?.let { startRewatch ->
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.Replay,
                    title = rewatchLabel,
                    onClick = {
                        startRewatch()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
            onOpenInLocalLibrary?.let { openLocalLibrary ->
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.FolderOpen,
                    title = stringResource(Res.string.poster_go_to_local_library),
                    onClick = {
                        openLocalLibrary()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(
                                sheetState = sheetState,
                                onDismiss = onDismiss,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun NuvioWatchedBadge(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .size(NuvioTokens.Icon.md)
            .clip(tokens.shapes.avatar)
            .background(tokens.colors.accentFill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = stringResource(Res.string.episodes_cd_watched),
            tint = tokens.colors.onAccent,
            modifier = Modifier.size(NuvioTokens.Icon.xs),
        )
    }
}

@Composable
fun NuvioAnimatedWatchedBadge(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        NuvioWatchedBadge()
    }
}

@Composable
fun BoxScope.NuvioPosterWatchedOverlay(
    isWatched: Boolean,
    modifier: Modifier = Modifier,
    padding: Dp = NuvioTokens.Space.s6,
) {
    NuvioAnimatedWatchedBadge(
        isVisible = isWatched,
        modifier = modifier
            .align(Alignment.TopEnd)
            .padding(padding),
    )
}

@Composable
private fun PosterSheetHeader(
    item: MetaPreview,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val tokens = MaterialTheme.nuvio
    // Mirrors the shelf card's poster-service fallback — see PosterZoomAnchor.fallbackImageUrl.
    var posterUrl by remember(item.poster, item.posterFallback) { mutableStateOf(item.poster) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s14),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = NuvioTokens.Space.s64, height = NuvioTokens.Space.s80 + NuvioTokens.Space.s12)
                .clip(RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp))
                .background(tokens.colors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            if (posterUrl != null) {
                NuvioAsyncImage(
                    model = posterUrl,
                    contentDescription = item.name,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (item.posterFallback != null && posterUrl != item.posterFallback) {
                            posterUrl = item.posterFallback
                        }
                    },
                )
            } else {
                Text(
                    text = item.name,
                    modifier = Modifier.padding(tokens.spacing.listGap),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.releaseInfo?.takeIf { it.isNotBlank() }?.let { formatReleaseDateForDisplay(it) }
                    ?: item.type.replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase() else char.toString()
                },
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
