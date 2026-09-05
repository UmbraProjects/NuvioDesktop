package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsUiState
import com.nuvio.app.features.downloads.sortedForSeriesDownloads
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal sealed interface DownloadsSettingsDeleteTarget {
    val downloadIds: Set<String>

    data class Show(
        val title: String,
        override val downloadIds: Set<String>,
    ) : DownloadsSettingsDeleteTarget

    data class Season(
        val showTitle: String,
        val seasonNumber: Int,
        override val downloadIds: Set<String>,
    ) : DownloadsSettingsDeleteTarget
}

internal fun LazyListScope.downloadsSettingsContent(
    isTablet: Boolean,
    uiState: DownloadsUiState,
    selectedShowId: String?,
    onSelectedShowChange: (String?) -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteTarget: (DownloadsSettingsDeleteTarget) -> Unit,
) {
    val completedEpisodes = uiState.completedItems
        .filter(DownloadItem::isEpisode)
        .sortedForSeriesDownloads()

    if (selectedShowId != null) {
        val showEpisodes = completedEpisodes.filter { it.parentMetaId == selectedShowId }
        val showTitle = showEpisodes.firstOrNull()?.title.orEmpty()
        item {
            SettingsSection(
                title = showTitle.ifBlank { stringResource(Res.string.downloads_show_downloads) },
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.downloads_back_to_all),
                        description = stringResource(Res.string.compose_settings_root_downloads_description),
                        icon = Icons.Rounded.ArrowBack,
                        isTablet = isTablet,
                        onClick = { onSelectedShowChange(null) },
                    )
                }
            }
        }

        val seasons = showEpisodes
            .groupBy { it.seasonNumber ?: 0 }
            .toList()
            .sortedWith(compareBy<Pair<Int, List<DownloadItem>>> { if (it.first == 0) 0 else 1 }.thenBy { it.first })

        seasons.forEach { (seasonNumber, entries) ->
            item(key = "downloads-season-$selectedShowId-$seasonNumber") {
                val seasonTitle = if (seasonNumber == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, seasonNumber)
                }
                SettingsSection(
                    title = seasonTitle,
                    isTablet = isTablet,
                    actions = {
                        IconButton(
                            onClick = {
                                onDeleteTarget(
                                    DownloadsSettingsDeleteTarget.Season(
                                        showTitle = showTitle,
                                        seasonNumber = seasonNumber,
                                        downloadIds = uiState.items
                                            .filter {
                                                it.isEpisode && it.parentMetaId == selectedShowId &&
                                                    (it.seasonNumber ?: 0) == seasonNumber
                                            }
                                            .mapTo(mutableSetOf(), DownloadItem::id),
                                    ),
                                )
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(Res.string.downloads_delete_season),
                                tint = MaterialTheme.nuvio.colors.textMuted,
                            )
                        }
                    },
                ) {
                    DownloadSettingsGroup(
                        items = entries.sortedForSeriesDownloads(),
                        isTablet = isTablet,
                        onOpenDownload = onOpenDownload,
                    )
                }
            }
        }
        return
    }

    val activeItems = uiState.activeItems
    val completedMovies = uiState.completedItems.filterNot(DownloadItem::isEpisode)
    val completedShows = completedEpisodes
        .groupBy { it.parentMetaId }
        .mapNotNull { (_, episodes) -> episodes.firstOrNull()?.let { it to episodes } }
        .sortedBy { it.first.title.lowercase() }

    if (activeItems.isNotEmpty()) {
        item {
            SettingsSection(title = stringResource(Res.string.downloads_section_active), isTablet = isTablet) {
                DownloadSettingsGroup(activeItems, isTablet, onOpenDownload)
            }
        }
    }
    if (completedMovies.isNotEmpty()) {
        item {
            SettingsSection(title = stringResource(Res.string.downloads_section_movies), isTablet = isTablet) {
                DownloadSettingsGroup(completedMovies, isTablet, onOpenDownload)
            }
        }
    }
    if (completedShows.isNotEmpty()) {
        item {
            SettingsSection(title = stringResource(Res.string.downloads_section_shows), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    completedShows.forEachIndexed { index, (item, episodes) ->
                        DownloadShowSettingsRow(
                            title = item.title,
                            episodeCount = episodes.size,
                            isTablet = isTablet,
                            onOpen = { onSelectedShowChange(item.parentMetaId) },
                            onDelete = {
                                onDeleteTarget(
                                    DownloadsSettingsDeleteTarget.Show(
                                        title = item.title,
                                        downloadIds = uiState.items
                                            .filter { it.isEpisode && it.parentMetaId == item.parentMetaId }
                                            .mapTo(mutableSetOf(), DownloadItem::id),
                                    ),
                                )
                            },
                        )
                        if (index != completedShows.lastIndex) SettingsGroupDivider(isTablet)
                    }
                }
            }
        }
    }
    if (uiState.items.isEmpty()) {
        item {
            SettingsSection(title = stringResource(Res.string.compose_settings_root_downloads_title), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.downloads_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadSettingsGroup(
    items: List<DownloadItem>,
    isTablet: Boolean,
    onOpenDownload: (DownloadItem) -> Unit,
) {
    SettingsGroup(isTablet = isTablet) {
        items.forEachIndexed { index, item ->
            DownloadSettingsRow(item = item, isTablet = isTablet, onOpen = { onOpenDownload(item) })
            if (index != items.lastIndex) SettingsGroupDivider(isTablet)
        }
    }
}

@Composable
private fun DownloadShowSettingsRow(
    title: String,
    episodeCount: Int,
    isTablet: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = if (isTablet) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(Res.string.downloads_episode_count, episodeCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
        }
        IconButton(onClick = onOpen) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = stringResource(Res.string.downloads_show_downloads),
                tint = MaterialTheme.nuvio.colors.textPrimary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.downloads_delete_show),
                tint = MaterialTheme.nuvio.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun DownloadSettingsRow(
    item: DownloadItem,
    isTablet: Boolean,
    onOpen: () -> Unit,
) {
    val displayTitle = item.downloadDisplayTitle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isPlayable, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = if (isTablet) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = downloadSettingsSubtitle(item, displayTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = downloadSettingsStatus(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                )
            }
            when (item.status) {
                DownloadStatus.Downloading -> DownloadAction(Icons.Rounded.Pause, Res.string.compose_action_pause) {
                    DownloadsRepository.pauseDownload(item.id)
                }
                DownloadStatus.Paused -> DownloadAction(Icons.Rounded.PlayArrow, Res.string.action_resume) {
                    DownloadsRepository.resumeDownload(item.id)
                }
                DownloadStatus.Failed -> DownloadAction(Icons.Rounded.Refresh, Res.string.action_retry) {
                    DownloadsRepository.retryDownload(item.id)
                }
                DownloadStatus.Completed -> DownloadAction(Icons.Rounded.PlayArrow, Res.string.action_play, onOpen)
            }
            DownloadAction(Icons.Rounded.Delete, Res.string.action_delete) {
                DownloadsRepository.cancelDownload(item.id)
            }
        }
        if (item.status == DownloadStatus.Downloading) {
            if (item.totalBytes != null && item.totalBytes > 0L) {
                LinearProgressIndicator(progress = item.progressFraction, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DownloadAction(
    icon: ImageVector,
    description: StringResource,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = stringResource(description), tint = MaterialTheme.nuvio.colors.textPrimary)
    }
}

@Composable
internal fun DownloadsSettingsDeleteDialog(
    target: DownloadsSettingsDeleteTarget?,
    onDismiss: () -> Unit,
) {
    target ?: return
    val title: String
    val message: String
    when (target) {
        is DownloadsSettingsDeleteTarget.Show -> {
            title = stringResource(Res.string.downloads_delete_show_title)
            message = stringResource(Res.string.downloads_delete_show_message, target.downloadIds.size, target.title)
        }
        is DownloadsSettingsDeleteTarget.Season -> {
            val season = if (target.seasonNumber == 0) {
                stringResource(Res.string.episodes_specials)
            } else {
                stringResource(Res.string.episodes_season, target.seasonNumber)
            }
            title = stringResource(Res.string.downloads_delete_season_title)
            message = stringResource(
                Res.string.downloads_delete_season_message,
                target.downloadIds.size,
                "${target.showTitle} — $season",
            )
        }
    }
    NuvioStatusModal(
        title = title,
        message = message,
        isVisible = true,
        confirmText = stringResource(Res.string.action_delete),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            DownloadsRepository.cancelDownloads(target.downloadIds)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

private fun DownloadItem.downloadDisplayTitle(): String =
    if (isEpisode) episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title else title

@Composable
private fun downloadSettingsSubtitle(item: DownloadItem, displayTitle: String): String {
    val season = item.seasonNumber
    val episode = item.episodeNumber
    if (season == null || episode == null) return item.displaySubtitle
    val episodeCode = stringResource(Res.string.compose_player_episode_code_full, season, episode)
    return listOf(
        episodeCode,
        item.episodeTitle?.trim()?.takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

@Composable
private fun downloadSettingsStatus(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        "${formatDownloadBytes(item.downloadedBytes)} / ${formatDownloadBytes(item.totalBytes)}"
    } else {
        formatDownloadBytes(item.downloadedBytes)
    }
    return when (item.status) {
        DownloadStatus.Downloading -> stringResource(Res.string.downloads_status_downloading, size)
        DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, size)
        DownloadStatus.Completed -> stringResource(
            Res.string.downloads_status_completed,
            formatDownloadBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

private fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}" 
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}" 
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}" 
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}" 
        else -> "$bytes ${localizedByteUnit("B")}" 
    }
}
