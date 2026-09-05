package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.cloudLibraryDisplayArtworkUrl
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cw_action_go_to_details
import nuvio.composeapp.generated.resources.cw_action_remove
import nuvio.composeapp.generated.resources.cw_action_resync
import nuvio.composeapp.generated.resources.cw_action_start_from_beginning
import nuvio.composeapp.generated.resources.play_choose_source
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioContinueWatchingActionSheet(
    item: ContinueWatchingItem?,
    primaryPlayLabel: String? = null,
    alternatePlayLabel: String? = null,
    showDetailsOption: Boolean = true,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
    onPrimaryPlay: (() -> Unit)? = null,
    onStartFromBeginning: (() -> Unit)? = null,
    onAlternatePlay: (() -> Unit)? = null,
    // Separate from [onAlternatePlay] on purpose: the source picker is not the "other" of the two
    // local-library routes, it is the one that overrides stream auto-play, so it is offered
    // whether or not this item has a local file to alternate to.
    onChooseSource: (() -> Unit)? = null,
    onResync: () -> Unit,
    onRemove: () -> Unit,
    zoomAnchor: PosterZoomAnchor? = null,
    zoomHazeState: HazeState? = null,
) {
    if (item == null) return
    val posterCardStyle = rememberPosterCardStyleUiState()
    if (posterCardStyle.zoomActionPreviewEnabled && zoomHazeState != null) {
        NuvioContinueWatchingZoomActionSheet(
            item = item,
            primaryPlayLabel = primaryPlayLabel,
            alternatePlayLabel = alternatePlayLabel,
            showDetailsOption = showDetailsOption,
            anchor = zoomAnchor,
            hazeState = zoomHazeState,
            onDismiss = onDismiss,
            onOpenDetails = onOpenDetails,
            onPrimaryPlay = onPrimaryPlay,
            onStartFromBeginning = onStartFromBeginning,
            onAlternatePlay = onAlternatePlay,
            onChooseSource = onChooseSource,
            onResync = onResync,
            onRemove = onRemove,
        )
        return
    }
    val tokens = MaterialTheme.nuvio
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
                .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.screenHorizontal)),
        ) {
            ContinueWatchingSheetHeader(item = item)
            if (showDetailsOption) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.Info,
                    title = stringResource(Res.string.cw_action_go_to_details),
                    onClick = { dismissAfter(onOpenDetails) },
                )
            }
            if (primaryPlayLabel != null && onPrimaryPlay != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.PlayArrow,
                    title = primaryPlayLabel,
                    onClick = { dismissAfter(onPrimaryPlay) },
                )
            }
            if (alternatePlayLabel != null && onAlternatePlay != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.PlayArrow,
                    title = alternatePlayLabel,
                    onClick = { dismissAfter(onAlternatePlay) },
                )
            }
            if (onChooseSource != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    title = stringResource(Res.string.play_choose_source),
                    onClick = { dismissAfter(onChooseSource) },
                )
            }
            if (!item.isNextUp && onStartFromBeginning != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.Replay,
                    title = stringResource(Res.string.cw_action_start_from_beginning),
                    onClick = { dismissAfter(onStartFromBeginning) },
                )
            }
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Default.Refresh,
                title = stringResource(Res.string.cw_action_resync),
                onClick = { dismissAfter(onResync) },
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Default.DeleteOutline,
                title = stringResource(Res.string.cw_action_remove),
                onClick = { dismissAfter(onRemove) },
            )
        }
    }
}

/**
 * Long-press zoom preview for Continue Watching cards, mirroring [NuvioPosterZoomActionSheet] so the
 * setting applies to every shelf rather than just catalog posters.
 */
@Composable
private fun NuvioContinueWatchingZoomActionSheet(
    item: ContinueWatchingItem,
    primaryPlayLabel: String?,
    alternatePlayLabel: String?,
    showDetailsOption: Boolean,
    anchor: PosterZoomAnchor?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
    onPrimaryPlay: (() -> Unit)?,
    onStartFromBeginning: (() -> Unit)?,
    onAlternatePlay: (() -> Unit)?,
    onChooseSource: (() -> Unit)?,
    onResync: () -> Unit,
    onRemove: () -> Unit,
) {
    NuvioPosterZoomActionOverlay(
        imageUrl = anchor?.imageUrl
            ?: (item.poster ?: item.imageUrl)?.let(::cloudLibraryDisplayArtworkUrl),
        title = item.title,
        subtitle = localizedContinueWatchingSubtitle(item),
        anchor = anchor,
        actions = buildList {
            if (showDetailsOption) {
                add(
                    PosterZoomOverlayAction(
                        icon = Icons.Default.Info,
                        label = stringResource(Res.string.cw_action_go_to_details),
                        onSelected = onOpenDetails,
                    ),
                )
            }
            if (primaryPlayLabel != null && onPrimaryPlay != null) {
                add(
                    PosterZoomOverlayAction(
                        icon = Icons.Default.PlayArrow,
                        label = primaryPlayLabel,
                        onSelected = onPrimaryPlay,
                    ),
                )
            }
            if (alternatePlayLabel != null && onAlternatePlay != null) {
                add(
                    PosterZoomOverlayAction(
                        icon = Icons.Default.PlayArrow,
                        label = alternatePlayLabel,
                        onSelected = onAlternatePlay,
                    ),
                )
            }
            if (onChooseSource != null) {
                add(
                    PosterZoomOverlayAction(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        label = stringResource(Res.string.play_choose_source),
                        onSelected = onChooseSource,
                    ),
                )
            }
            if (!item.isNextUp && onStartFromBeginning != null) {
                add(
                    PosterZoomOverlayAction(
                        icon = Icons.Default.Replay,
                        label = stringResource(Res.string.cw_action_start_from_beginning),
                        onSelected = onStartFromBeginning,
                    ),
                )
            }
            add(
                PosterZoomOverlayAction(
                    icon = Icons.Default.Refresh,
                    label = stringResource(Res.string.cw_action_resync),
                    onSelected = onResync,
                ),
            )
            add(
                PosterZoomOverlayAction(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(Res.string.cw_action_remove),
                    isDestructive = true,
                    onSelected = onRemove,
                ),
            )
        },
        hazeState = hazeState,
        onDismissed = onDismiss,
    )
}

@Composable
private fun ContinueWatchingSheetHeader(
    item: ContinueWatchingItem,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val tokens = MaterialTheme.nuvio

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
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp))
                .background(tokens.colors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = item.poster ?: item.imageUrl
            if (artwork != null) {
                NuvioAsyncImage(
                    model = cloudLibraryDisplayArtworkUrl(artwork),
                    contentDescription = item.title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = if (item.isCloudLibraryItem()) ContentScale.Fit else ContentScale.Crop,
                )
            } else {
                Text(
                    text = item.title,
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
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = localizedContinueWatchingSubtitle(item),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ContinueWatchingItem.isCloudLibraryItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)
