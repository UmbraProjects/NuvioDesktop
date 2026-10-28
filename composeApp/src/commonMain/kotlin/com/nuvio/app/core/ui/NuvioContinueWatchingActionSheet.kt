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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.cloudLibraryDisplayArtworkUrl
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import dev.chrisbanes.haze.HazeState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cw_action_go_to_details
import nuvio.composeapp.generated.resources.cw_action_remove
import nuvio.composeapp.generated.resources.cw_action_resync
import nuvio.composeapp.generated.resources.cw_action_start_from_beginning
import nuvio.composeapp.generated.resources.play_choose_source
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.*

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
    /**
     * This title's own plain-click override ("Always play local file for this title" and so on),
     * built by the caller since which entries apply depends on the configured preference. Shown as
     * their own group after the play entries; empty for a title with no local copy.
     */
    titlePlaybackActions: List<PosterZoomOverlayAction> = emptyList(),
    onResync: () -> Unit,
    onRemove: () -> Unit,
    /** Queues this card in a playlist. Null hides the row. */
    onAddToPlaylist: (() -> Unit)? = null,
    /** Queues every card in this card's row, e.g. all of Next Up. Null hides the row. */
    onAddRowToPlaylist: (() -> Unit)? = null,
    addRowToPlaylistLabel: String = "",
    zoomAnchor: PosterZoomAnchor? = null,
    zoomHazeState: HazeState? = null,
    /** See [NuvioPosterActionSheet]'s parameter of the same name. */
    contextMenuPosition: IntOffset? = null,
) {
    if (item == null) return
    val actions = buildList {
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
            add(PosterZoomOverlayAction(Icons.Default.PlayArrow, primaryPlayLabel, onSelected = onPrimaryPlay, group = 1))
        }
        if (alternatePlayLabel != null && onAlternatePlay != null) {
            add(PosterZoomOverlayAction(Icons.Default.PlayArrow, alternatePlayLabel, onSelected = onAlternatePlay, group = 1))
        }
        if (onChooseSource != null) {
            add(
                PosterZoomOverlayAction(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    label = stringResource(Res.string.play_choose_source),
                    onSelected = onChooseSource,
                    group = 1,
                ),
            )
        }
        if (!item.isNextUp && onStartFromBeginning != null) {
            add(
                PosterZoomOverlayAction(
                    icon = Icons.Default.Replay,
                    label = stringResource(Res.string.cw_action_start_from_beginning),
                    onSelected = onStartFromBeginning,
                    group = 1,
                ),
            )
        }
        addAll(titlePlaybackActions)
        onAddToPlaylist?.let { addToPlaylist ->
            add(
                PosterZoomOverlayAction(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = stringResource(Res.string.playlist_add_to_playlist),
                    onSelected = addToPlaylist,
                    group = 2,
                ),
            )
        }
        onAddRowToPlaylist?.let { addRow ->
            add(
                PosterZoomOverlayAction(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = addRowToPlaylistLabel,
                    onSelected = addRow,
                    group = 2,
                ),
            )
        }
        add(
            PosterZoomOverlayAction(
                icon = Icons.Default.Refresh,
                label = stringResource(Res.string.cw_action_resync),
                onSelected = onResync,
                group = 3,
            ),
        )
        add(
            PosterZoomOverlayAction(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(Res.string.cw_action_remove),
                isDestructive = true,
                onSelected = onRemove,
                group = 4,
            ),
        )
    }

    if (contextMenuPosition != null) {
        NuvioContextMenu(windowPosition = contextMenuPosition, actions = actions, onDismiss = onDismiss)
        return
    }
    val posterCardStyle = rememberPosterCardStyleUiState()
    if (posterCardStyle.zoomActionPreviewEnabled && zoomHazeState != null) {
        // Long-press zoom preview, the same as catalog posters, so the setting applies to every
        // shelf rather than just catalog posters.
        NuvioPosterZoomActionOverlay(
            imageUrl = zoomAnchor?.imageUrl
                ?: (item.poster ?: item.imageUrl)?.let(::cloudLibraryDisplayArtworkUrl),
            title = item.title,
            subtitle = localizedContinueWatchingSubtitle(item),
            anchor = zoomAnchor,
            actions = actions,
            hazeState = zoomHazeState,
            onDismissed = onDismiss,
        )
        return
    }
    NuvioActionBottomSheet(
        actions = actions,
        onDismiss = onDismiss,
        bottomPadding = MaterialTheme.nuvio.spacing.screenHorizontal,
    ) {
        ContinueWatchingSheetHeader(item = item)
    }
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
