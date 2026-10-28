package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAddCheckCircle
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioActionBottomSheet
import com.nuvio.app.core.ui.NuvioContextMenu
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.features.details.MetaVideo
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun EpisodeWatchedActionSheet(
    episode: MetaVideo,
    seasonLabel: String,
    isEpisodeWatched: Boolean,
    canMarkPreviousEpisodes: Boolean,
    arePreviousEpisodesWatched: Boolean,
    isSeasonWatched: Boolean,
    onDismiss: () -> Unit,
    onToggleWatched: () -> Unit,
    onTogglePreviousWatched: () -> Unit,
    onToggleSeasonWatched: () -> Unit,
    alternatePlayLabel: String? = null,
    onAlternatePlay: (() -> Unit)? = null,
    // Separate from [onAlternatePlay] on purpose: the source picker is not the "other" of the two
    // local-library routes, it is the one that overrides stream auto-play, so it is offered
    // whether or not this episode has a local file to alternate to.
    onChooseSource: (() -> Unit)? = null,
    /**
     * Null when there is no earlier story with synopses behind this episode, or the feature is off.
     * This is the mid-season half of the recap: the boundary is the episode about to be played,
     * which is more precise than "before season N" and is what someone resuming actually wants.
     */
    onRecap: (() -> Unit)? = null,
    /** Queues this episode in a playlist. Null hides the row. */
    onAddToPlaylist: (() -> Unit)? = null,
    /** Queues the episode's whole season in a playlist. Null hides the row. */
    onAddSeasonToPlaylist: (() -> Unit)? = null,
    /** Where the opening right-click landed; set, the actions open as a context menu there. */
    contextMenuPosition: IntOffset? = null,
) {
    val actions = buildList {
        add(
            PosterZoomOverlayAction(
                icon = Icons.Default.CheckCircle,
                label = if (isEpisodeWatched) {
                    stringResource(Res.string.episode_mark_unwatched)
                } else {
                    stringResource(Res.string.episode_mark_watched)
                },
                onSelected = onToggleWatched,
            ),
        )
        if (canMarkPreviousEpisodes) {
            add(
                PosterZoomOverlayAction(
                    icon = Icons.Default.DoneAll,
                    label = if (arePreviousEpisodesWatched) {
                        stringResource(Res.string.episode_mark_previous_unwatched)
                    } else {
                        stringResource(Res.string.episode_mark_previous_watched)
                    },
                    onSelected = onTogglePreviousWatched,
                ),
            )
        }
        add(
            PosterZoomOverlayAction(
                icon = Icons.Default.PlaylistAddCheckCircle,
                label = if (isSeasonWatched) {
                    stringResource(Res.string.episode_mark_season_unwatched, seasonLabel)
                } else {
                    stringResource(Res.string.episode_mark_season_watched, seasonLabel)
                },
                onSelected = onToggleSeasonWatched,
            ),
        )
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
        if (onRecap != null) {
            add(
                PosterZoomOverlayAction(
                    icon = Icons.Default.HistoryEdu,
                    label = stringResource(Res.string.recap_action),
                    onSelected = onRecap,
                    group = 1,
                ),
            )
        }
        if (onAddToPlaylist != null) {
            add(
                PosterZoomOverlayAction(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = stringResource(Res.string.playlist_add_episode_to_playlist),
                    onSelected = onAddToPlaylist,
                    group = 2,
                ),
            )
        }
        if (onAddSeasonToPlaylist != null) {
            add(
                PosterZoomOverlayAction(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = stringResource(Res.string.playlist_add_season_to_playlist, seasonLabel),
                    onSelected = onAddSeasonToPlaylist,
                    group = 2,
                ),
            )
        }
    }

    if (contextMenuPosition != null) {
        NuvioContextMenu(windowPosition = contextMenuPosition, actions = actions, onDismiss = onDismiss)
        return
    }
    NuvioActionBottomSheet(actions = actions, onDismiss = onDismiss) {
        EpisodeActionSheetHeader(
            episode = episode,
            seasonLabel = seasonLabel,
        )
    }
}

@Composable
fun SeasonWatchedActionSheet(
    seasonLabel: String,
    isSeasonWatched: Boolean,
    canMarkPreviousSeasons: Boolean,
    onDismiss: () -> Unit,
    onToggleSeasonWatched: () -> Unit,
    onMarkPreviousSeasonsWatched: () -> Unit,
    /**
     * Null when there is nothing to recap or the feature is off — an absent row rather than a
     * disabled one, because a season 1 long-press has no earlier story by definition and a row
     * that is always greyed out there reads as broken.
     */
    onRecap: (() -> Unit)? = null,
    /** Queues the season's released episodes in a playlist. Null hides the row. */
    onAddSeasonToPlaylist: (() -> Unit)? = null,
    /** Where the opening right-click landed; set, the actions open as a context menu there. */
    contextMenuPosition: IntOffset? = null,
) {
    val actions = buildList {
        add(
            PosterZoomOverlayAction(
                icon = Icons.Default.PlaylistAddCheckCircle,
                label = if (isSeasonWatched) {
                    stringResource(Res.string.episode_mark_season_unwatched, seasonLabel)
                } else {
                    stringResource(Res.string.episode_mark_season_watched, seasonLabel)
                },
                onSelected = onToggleSeasonWatched,
            ),
        )
        if (canMarkPreviousSeasons) {
            add(
                PosterZoomOverlayAction(
                    icon = Icons.Default.DoneAll,
                    label = stringResource(Res.string.episode_mark_previous_seasons_watched),
                    onSelected = onMarkPreviousSeasonsWatched,
                ),
            )
        }
        if (onRecap != null) {
            add(
                PosterZoomOverlayAction(
                    icon = Icons.Default.HistoryEdu,
                    label = stringResource(Res.string.recap_action),
                    onSelected = onRecap,
                    group = 1,
                ),
            )
        }
        if (onAddSeasonToPlaylist != null) {
            add(
                PosterZoomOverlayAction(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = stringResource(Res.string.playlist_add_season_to_playlist, seasonLabel),
                    onSelected = onAddSeasonToPlaylist,
                    group = 2,
                ),
            )
        }
    }

    if (contextMenuPosition != null) {
        NuvioContextMenu(windowPosition = contextMenuPosition, actions = actions, onDismiss = onDismiss)
        return
    }
    NuvioActionBottomSheet(actions = actions, onDismiss = onDismiss) {
        Text(
            text = seasonLabel,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun EpisodeActionSheetHeader(
    episode: MetaVideo,
    seasonLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = episode.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                localizedSeasonEpisodeCode(
                    seasonNumber = episode.season,
                    episodeNumber = episode.episode,
                )?.let {
                    append(it)
                    append(" • ")
                }
                append(seasonLabel)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
