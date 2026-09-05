package com.nuvio.app.features.librarypvr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalOpenMetaDetails
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.settings.SettingsChoiceOption
import com.nuvio.app.features.settings.SettingsChoiceRow
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_add_to_library
import nuvio.composeapp.generated.resources.library_downloads_activity_empty
import nuvio.composeapp.generated.resources.library_downloads_bandwidth_desc
import nuvio.composeapp.generated.resources.library_downloads_bandwidth_title
import nuvio.composeapp.generated.resources.library_downloads_bandwidth_unlimited
import nuvio.composeapp.generated.resources.library_downloads_check_now
import nuvio.composeapp.generated.resources.library_downloads_check_on_start_desc
import nuvio.composeapp.generated.resources.library_downloads_check_on_start_title
import nuvio.composeapp.generated.resources.library_downloads_checking
import nuvio.composeapp.generated.resources.library_downloads_cancel_active
import nuvio.composeapp.generated.resources.library_downloads_clear_activity
import nuvio.composeapp.generated.resources.library_downloads_concurrency_desc
import nuvio.composeapp.generated.resources.library_downloads_concurrency_title
import nuvio.composeapp.generated.resources.library_downloads_enabled_desc
import nuvio.composeapp.generated.resources.library_downloads_enabled_title
import nuvio.composeapp.generated.resources.library_downloads_episode_size_desc
import nuvio.composeapp.generated.resources.library_downloads_episode_size_title
import nuvio.composeapp.generated.resources.library_downloads_force_start
import nuvio.composeapp.generated.resources.library_downloads_force_warning_body
import nuvio.composeapp.generated.resources.library_downloads_force_warning_cancel
import nuvio.composeapp.generated.resources.library_downloads_force_warning_confirm
import nuvio.composeapp.generated.resources.library_downloads_force_warning_title
import nuvio.composeapp.generated.resources.library_downloads_interval_12h
import nuvio.composeapp.generated.resources.library_downloads_interval_24h
import nuvio.composeapp.generated.resources.library_downloads_interval_3h
import nuvio.composeapp.generated.resources.library_downloads_interval_6h
import nuvio.composeapp.generated.resources.library_downloads_interval_desc
import nuvio.composeapp.generated.resources.library_downloads_interval_title
import nuvio.composeapp.generated.resources.library_downloads_mode_all
import nuvio.composeapp.generated.resources.library_downloads_mode_movie
import nuvio.composeapp.generated.resources.library_downloads_mode_selected_future
import nuvio.composeapp.generated.resources.library_downloads_mode_selected_only
import nuvio.composeapp.generated.resources.library_downloads_movie_size_desc
import nuvio.composeapp.generated.resources.library_downloads_movie_size_title
import nuvio.composeapp.generated.resources.library_downloads_monitored_empty
import nuvio.composeapp.generated.resources.library_downloads_pause
import nuvio.composeapp.generated.resources.library_downloads_pause_active
import nuvio.composeapp.generated.resources.library_downloads_expand_packs_desc
import nuvio.composeapp.generated.resources.library_downloads_expand_packs_title
import nuvio.composeapp.generated.resources.library_downloads_pause_playback_desc
import nuvio.composeapp.generated.resources.library_downloads_pause_playback_title
import nuvio.composeapp.generated.resources.library_downloads_paused_badge
import nuvio.composeapp.generated.resources.library_manual_add
import nuvio.composeapp.generated.resources.library_downloads_open_details
import nuvio.composeapp.generated.resources.library_downloads_remove
import nuvio.composeapp.generated.resources.library_downloads_resume
import nuvio.composeapp.generated.resources.library_downloads_resume_active
import nuvio.composeapp.generated.resources.library_downloads_release_delay_desc
import nuvio.composeapp.generated.resources.library_downloads_release_delay_none
import nuvio.composeapp.generated.resources.library_downloads_release_delay_title
import nuvio.composeapp.generated.resources.library_downloads_section_activity
import nuvio.composeapp.generated.resources.library_downloads_section_monitored
import nuvio.composeapp.generated.resources.library_downloads_section_settings
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_description
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.accentBrush

private val INTERVAL_OPTION_HOURS = listOf(3, 6, 12, 24)
private val CONCURRENCY_OPTIONS = listOf(1, 2, 3, 4)
private val RELEASE_DELAY_OPTIONS_HOURS = listOf(0, 1, 2, 4, 6, 12, 24, 48)
private val BANDWIDTH_OPTIONS_MBPS = listOf(0, 10, 25, 50, 100, 250, 500, 1_000)
private val SIZE_LIMIT_OPTIONS_BYTES: List<Long?> =
    listOf(null) + listOf(1, 2, 5, 10, 20, 30, 50, 75, 100).map { it * 1_000_000_000L }

// Animation and older TV routinely ship episodes well under a gigabyte, where the smallest movie
// step (1 GB) is effectively "unlimited". Episodes therefore get sub-GB steps; the movie cap keeps
// the GB-only list, since a 100 MB movie limit would never match anything worth grabbing.
private val EPISODE_SIZE_LIMIT_OPTIONS_BYTES: List<Long?> =
    listOf<Long?>(null) +
        listOf(100L, 250L, 500L).map { it * 1_000_000L } +
        listOf(1L, 2L, 5L, 10L, 20L, 30L, 50L, 75L, 100L).map { it * 1_000_000_000L }

// Four posters fit the space the old two-per-row list rows took; narrow layouts drop to two.
private const val MONITORED_COLUMNS_WIDE = 4
private const val MONITORED_COLUMNS_NARROW = 2

/**
 * Content for the dedicated Auto Downloads settings page: enable/interval/concurrency,
 * monitored titles, and grab activity.
 */
internal fun LazyListScope.libraryDownloadsSection(
    isTablet: Boolean,
    onDownloadsClick: () -> Unit,
) {
    item {
        SettingsSection(title = stringResource(Res.string.compose_settings_root_downloads_title), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_root_downloads_title),
                    description = stringResource(Res.string.compose_settings_root_downloads_description),
                    isTablet = isTablet,
                    onClick = onDownloadsClick,
                )
            }
        }
    }
    item { LibraryDownloadsSettingsGroup(isTablet) }
    // Deliberately one item, unlike the local-library poster grid that splits rows across items:
    // monitored titles number in the tens, and keeping the section in one composition is what keeps
    // the add/picker dialogs alive when the list scrolls.
    item { LibraryDownloadsMonitoredSection(isTablet) }
    item { LibraryDownloadsActivitySection(isTablet) }
}

@Composable
private fun LibraryDownloadsSettingsGroup(isTablet: Boolean) {
    val pvr by remember {
        LibraryPvrRepository.ensureLoaded()
        LibraryPvrRepository.uiState
    }.collectAsStateWithLifecycle()
    val settings = pvr.settings

    SettingsSection(title = stringResource(Res.string.library_downloads_section_settings), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.library_downloads_enabled_title),
                description = stringResource(Res.string.library_downloads_enabled_desc),
                checked = settings.enabled,
                isTablet = isTablet,
                onCheckedChange = { enabled ->
                    LibraryPvrRepository.updateSettings { it.copy(enabled = enabled) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChoiceRow(
                title = stringResource(Res.string.library_downloads_interval_title),
                description = stringResource(Res.string.library_downloads_interval_desc),
                options = intervalOptions(),
                selectedValue = settings.checkIntervalHours.takeIf { it in INTERVAL_OPTION_HOURS } ?: 6,
                isTablet = isTablet,
                onSelected = { hours ->
                    LibraryPvrRepository.updateSettings { it.copy(checkIntervalHours = hours) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.library_downloads_check_on_start_title),
                description = stringResource(Res.string.library_downloads_check_on_start_desc),
                checked = settings.checkOnAppStart,
                isTablet = isTablet,
                onCheckedChange = { enabled ->
                    LibraryPvrRepository.updateSettings { it.copy(checkOnAppStart = enabled) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.library_downloads_pause_playback_title),
                description = stringResource(Res.string.library_downloads_pause_playback_desc),
                checked = settings.pauseDownloadsWhilePlaying,
                isTablet = isTablet,
                onCheckedChange = { enabled ->
                    LibraryPvrRepository.updateSettings {
                        it.copy(pauseDownloadsWhilePlaying = enabled)
                    }
                    LibraryPvrScheduler.onPauseWhilePlayingSettingChanged()
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.library_downloads_expand_packs_title),
                description = stringResource(Res.string.library_downloads_expand_packs_desc),
                checked = settings.expandSeasonPacks,
                isTablet = isTablet,
                onCheckedChange = { enabled ->
                    LibraryPvrRepository.updateSettings { it.copy(expandSeasonPacks = enabled) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChoiceRow(
                title = stringResource(Res.string.library_downloads_release_delay_title),
                description = stringResource(Res.string.library_downloads_release_delay_desc),
                options = RELEASE_DELAY_OPTIONS_HOURS.map { hours ->
                    SettingsChoiceOption(
                        hours,
                        if (hours == 0) {
                            stringResource(Res.string.library_downloads_release_delay_none)
                        } else {
                            "$hours h"
                        },
                    )
                },
                selectedValue = settings.postReleaseDelayHours
                    .takeIf { it in RELEASE_DELAY_OPTIONS_HOURS }
                    ?: 0,
                isTablet = isTablet,
                onSelected = { hours ->
                    LibraryPvrRepository.updateSettings { it.copy(postReleaseDelayHours = hours) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChoiceRow(
                title = stringResource(Res.string.library_downloads_concurrency_title),
                description = stringResource(Res.string.library_downloads_concurrency_desc),
                options = CONCURRENCY_OPTIONS.map { SettingsChoiceOption(it, it.toString()) },
                selectedValue = settings.maxConcurrentDownloads.coerceIn(1, 4),
                isTablet = isTablet,
                onSelected = { count ->
                    LibraryPvrRepository.updateSettings { it.copy(maxConcurrentDownloads = count) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChoiceRow(
                title = stringResource(Res.string.library_downloads_bandwidth_title),
                description = stringResource(Res.string.library_downloads_bandwidth_desc),
                options = BANDWIDTH_OPTIONS_MBPS.map { megabitsPerSecond ->
                    SettingsChoiceOption(
                        megabitsPerSecond,
                        if (megabitsPerSecond == 0) {
                            stringResource(Res.string.library_downloads_bandwidth_unlimited)
                        } else {
                            "$megabitsPerSecond Mbps"
                        },
                    )
                },
                selectedValue = settings.bandwidthLimitMbps
                    .takeIf { it in BANDWIDTH_OPTIONS_MBPS }
                    ?: 0,
                isTablet = isTablet,
                onSelected = { megabitsPerSecond ->
                    LibraryPvrRepository.updateSettings {
                        it.copy(bandwidthLimitMbps = megabitsPerSecond)
                    }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChoiceRow(
                title = stringResource(Res.string.library_downloads_episode_size_title),
                description = stringResource(Res.string.library_downloads_episode_size_desc),
                options = episodeSizeLimitOptions(),
                selectedValue = settings.maxEpisodeSizeBytes
                    .takeIf { it in EPISODE_SIZE_LIMIT_OPTIONS_BYTES },
                isTablet = isTablet,
                onSelected = { limit ->
                    LibraryPvrRepository.updateSettings { it.copy(maxEpisodeSizeBytes = limit) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChoiceRow(
                title = stringResource(Res.string.library_downloads_movie_size_title),
                description = stringResource(Res.string.library_downloads_movie_size_desc),
                options = sizeLimitOptions(),
                selectedValue = settings.maxMovieSizeBytes
                    .takeIf { it in SIZE_LIMIT_OPTIONS_BYTES },
                isTablet = isTablet,
                onSelected = { limit ->
                    LibraryPvrRepository.updateSettings { it.copy(maxMovieSizeBytes = limit) }
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(
                    if (pvr.isChecking) Res.string.library_downloads_checking else Res.string.library_downloads_check_now,
                ),
                description = "",
                isTablet = isTablet,
                enabled = !pvr.isChecking,
                onClick = { LibraryPvrScheduler.checkNow() },
            )
        }
    }
}

@Composable
private fun LibraryDownloadsMonitoredSection(isTablet: Boolean) {
    val pvr by LibraryPvrRepository.uiState.collectAsStateWithLifecycle()
    val local by LocalLibraryRepository.uiState.collectAsStateWithLifecycle()
    val foldersById = remember(local.folders) { local.folders.associateBy(LocalFolder::id) }
    var showAddDialog by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(Res.string.library_downloads_section_monitored),
        isTablet = isTablet,
        actions = {
            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(Res.string.library_add_to_library),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) {
        if (pvr.monitoredItems.isEmpty()) {
            EmptyStateRow(stringResource(Res.string.library_downloads_monitored_empty))
        } else {
            val columns = if (isTablet) MONITORED_COLUMNS_WIDE else MONITORED_COLUMNS_NARROW
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                pvr.monitoredItems.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { monitored ->
                            Box(modifier = Modifier.weight(1f)) {
                                MonitoredPosterCard(
                                    item = monitored,
                                    folder = foldersById[monitored.targetFolderId],
                                    forceWarningAccepted = pvr.settings.forceStartWarningAccepted,
                                )
                            }
                        }
                        // Keep a short final row's cards the same width as a full row's.
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        SearchAddToLibraryDialog(onDismiss = { showAddDialog = false })
    }
}

@Composable
private fun LibraryDownloadsActivitySection(isTablet: Boolean) {
    val pvr by LibraryPvrRepository.uiState.collectAsStateWithLifecycle()
    val downloads by DownloadsRepository.uiState.collectAsStateWithLifecycle()
    val monitoredById = remember(pvr.monitoredItems) {
        pvr.monitoredItems.associateBy(MonitoredItem::id)
    }
    val grabDownloadIds = remember(pvr.grabHistory) {
        pvr.grabHistory.mapNotNullTo(hashSetOf()) { it.downloadId }
    }
    // Right-click stream/season actions are automatic library transfers but intentionally do not
    // create a monitored title. Keep their active queue visible in this activity section too.
    val directAutomaticDownloads = remember(downloads.items, grabDownloadIds) {
        downloads.items.filter {
            it.isAutomaticDownload &&
                it.status != DownloadStatus.Completed &&
                it.id !in grabDownloadIds
        }
    }

    SettingsSection(title = stringResource(Res.string.library_downloads_section_activity), isTablet = isTablet) {
        if (pvr.grabHistory.isEmpty() && directAutomaticDownloads.isEmpty()) {
            EmptyStateRow(stringResource(Res.string.library_downloads_activity_empty))
        } else {
            SettingsGroup(isTablet = isTablet) {
                pvr.grabHistory.take(30).forEachIndexed { index, grab ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    val monitored = monitoredById[grab.monitoredItemId]
                    val download = if (
                        grab.status == GrabStatus.DOWNLOADING ||
                        grab.status == GrabStatus.QUEUED
                    ) {
                        downloads.items.firstOrNull { candidate ->
                            (grab.downloadId != null && candidate.id == grab.downloadId) ||
                                (
                                    monitored != null &&
                                        candidate.parentMetaId == monitored.contentId &&
                                        candidate.seasonNumber == grab.season &&
                                        candidate.episodeNumber == grab.episode
                                    )
                        }
                    } else {
                        null
                    }
                    GrabRow(grab, download)
                }
                directAutomaticDownloads
                    .take((30 - pvr.grabHistory.size).coerceAtLeast(0))
                    .forEach { download ->
                        if (pvr.grabHistory.isNotEmpty() || download != directAutomaticDownloads.first()) {
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        GrabRow(download.asDirectGrabRecord(), download, directDownloadOnly = true)
                    }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.library_downloads_clear_activity),
                    description = "",
                    isTablet = isTablet,
                    onClick = { LibraryPvrRepository.clearGrabHistory() },
                )
            }
        }
    }
}

@Composable
private fun intervalOptions(): List<SettingsChoiceOption<Int>> = listOf(
    SettingsChoiceOption(3, stringResource(Res.string.library_downloads_interval_3h)),
    SettingsChoiceOption(6, stringResource(Res.string.library_downloads_interval_6h)),
    SettingsChoiceOption(12, stringResource(Res.string.library_downloads_interval_12h)),
    SettingsChoiceOption(24, stringResource(Res.string.library_downloads_interval_24h)),
)

@Composable
private fun sizeLimitOptions(): List<SettingsChoiceOption<Long?>> =
    SIZE_LIMIT_OPTIONS_BYTES.toChoiceOptions()

@Composable
private fun episodeSizeLimitOptions(): List<SettingsChoiceOption<Long?>> =
    EPISODE_SIZE_LIMIT_OPTIONS_BYTES.toChoiceOptions()

@Composable
private fun List<Long?>.toChoiceOptions(): List<SettingsChoiceOption<Long?>> {
    val unlimited = stringResource(Res.string.library_downloads_bandwidth_unlimited)
    return map { bytes ->
        SettingsChoiceOption(
            value = bytes,
            // Sub-GB caps must read as MB — integer-dividing them by a billion renders "0 GB".
            label = when {
                bytes == null -> unlimited
                bytes < 1_000_000_000L -> "${bytes / 1_000_000L} MB"
                else -> "${bytes / 1_000_000_000L} GB"
            },
        )
    }
}

/**
 * One monitored title, styled to match the local-library poster cards. The card body opens the
 * season/episode picker; the four overlay buttons are the actions that used to live on the old list
 * row (manual link + force start top-right, pause + remove bottom-right).
 */
@Composable
private fun MonitoredPosterCard(
    item: MonitoredItem,
    folder: LocalFolder?,
    forceWarningAccepted: Boolean,
) {
    var showPicker by remember(item.id) { mutableStateOf(false) }
    var showForceWarning by remember(item.id) { mutableStateOf(false) }
    var showManualLink by remember(item.id) { mutableStateOf(false) }
    val pausedBadge = stringResource(Res.string.library_downloads_paused_badge)
    val openDetails = LocalOpenMetaDetails.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = item.isSeries) { showPicker = true },
        ) {
            if (item.poster != null) {
                NuvioAsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (item.isSeries) Icons.Rounded.Tv else Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                )
            }

            // A paused title should read as paused at poster size, before any text is scanned.
            if (item.paused) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                Text(
                    text = pausedBadge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MonitoredPosterButton(
                    icon = Icons.Rounded.AddLink,
                    contentDescription = stringResource(Res.string.library_manual_add),
                    onClick = { showManualLink = true },
                )
                MonitoredPosterButton(
                    icon = Icons.Rounded.Bolt,
                    contentDescription = stringResource(Res.string.library_downloads_force_start),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        if (forceWarningAccepted) {
                            LibraryPvrScheduler.forceStart(item.id)
                        } else {
                            showForceWarning = true
                        }
                    },
                )
            }

            // Bottom-left, alone: the poster body is already taken by the episode picker and the
            // other three corners are taken by the existing actions.
            openDetails?.let { open ->
                Box(modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                    MonitoredPosterButton(
                        icon = Icons.Rounded.Info,
                        contentDescription = stringResource(Res.string.library_downloads_open_details),
                        onClick = { open(item.contentType, item.contentId) },
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MonitoredPosterButton(
                    icon = if (item.paused) Icons.Rounded.PlayCircle else Icons.Rounded.PauseCircle,
                    contentDescription = stringResource(
                        if (item.paused) Res.string.library_downloads_resume else Res.string.library_downloads_pause,
                    ),
                    onClick = { LibraryPvrRepository.setPaused(item.id, !item.paused) },
                )
                MonitoredPosterButton(
                    icon = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(Res.string.library_downloads_remove),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { LibraryPvrRepository.removeMonitoredItem(item.id) },
                )
            }
        }

        Spacer(Modifier.size(6.dp))
        Text(
            text = item.year?.let { "${item.title} ($it)" } ?: item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(monitorModeLabel(item), folder?.displayNameWithDrive).joinToString("  •  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (showPicker) {
        MonitoredEpisodePickerDialog(item = item, onDismiss = { showPicker = false })
    }
    if (showManualLink) {
        ManualLinkDialog(item = item, onDismiss = { showManualLink = false })
    }
    if (showForceWarning) {
        NuvioAlertDialog(
            onDismissRequest = { showForceWarning = false },
            title = { Text(stringResource(Res.string.library_downloads_force_warning_title)) },
            text = { Text(stringResource(Res.string.library_downloads_force_warning_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        LibraryPvrRepository.updateSettings { it.copy(forceStartWarningAccepted = true) }
                        showForceWarning = false
                        LibraryPvrScheduler.forceStart(item.id)
                    },
                ) {
                    Text(stringResource(Res.string.library_downloads_force_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceWarning = false }) {
                    Text(stringResource(Res.string.library_downloads_force_warning_cancel))
                }
            },
        )
    }
}

/** Matches the local-library poster overlay buttons so the two grids read as one design. */
@Composable
private fun MonitoredPosterButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun GrabRow(
    grab: GrabRecord,
    download: DownloadItem?,
    directDownloadOnly: Boolean = false,
) {
    val activityLabel = download?.fileName
        ?.takeIf { it.isNotBlank() }
        ?: grab.streamLabel?.takeIf { it.isNotBlank() }
        ?: grab.displayLine()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NuvioPosterHoverTooltip(title = activityLabel) {
                Text(
                    text = activityLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            grab.failReason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            download?.takeIf { it.status == DownloadStatus.Downloading }?.let {
                Text(
                    text = downloadProgressLine(it),
                    style = MaterialTheme.typography.bodySmall.accentBrush(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val transferPaused = download?.status == DownloadStatus.Paused
        val isTransferring = download?.status == DownloadStatus.Downloading || transferPaused
        // A queued grab has no live transfer yet, but it can still be dropped before it starts.
        // Keyed off the transfer state rather than a null download, so an unrelated finished
        // transfer for the same episode cannot strand the row without a cancel control.
        val isQueued = grab.status == GrabStatus.QUEUED && !isTransferring
        Text(
            text = if (transferPaused) "PAUSED" else grab.status.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (transferPaused) MaterialTheme.colorScheme.onSurfaceVariant else grabStatusColor(grab.status),
        )
        // Empty slots keep the pause/delete columns aligned with the monitored-titles rows above,
        // whatever controls a given row actually offers.
        if (isTransferring && download != null) {
            IconButton(
                onClick = {
                    if (download.status == DownloadStatus.Paused) {
                        DownloadsRepository.resumeDownload(download.id)
                    } else {
                        DownloadsRepository.pauseDownload(download.id)
                    }
                },
            ) {
                Icon(
                    imageVector = if (download.status == DownloadStatus.Paused) {
                        Icons.Rounded.PlayCircle
                    } else {
                        Icons.Rounded.PauseCircle
                    },
                    contentDescription = stringResource(
                        if (download.status == DownloadStatus.Paused) {
                            Res.string.library_downloads_resume_active
                        } else {
                            Res.string.library_downloads_pause_active
                        },
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            Spacer(Modifier.size(GRAB_ACTION_SLOT))
        }
        if (isTransferring && download != null) {
            IconButton(onClick = {
                if (directDownloadOnly) DownloadsRepository.cancelDownload(download.id)
                else LibraryPvrScheduler.cancelDownload(grab.id, download.id)
            }) {
                GrabCancelIcon()
            }
        } else if (isQueued) {
            IconButton(onClick = { LibraryPvrScheduler.cancelQueuedGrab(grab.id) }) {
                GrabCancelIcon()
            }
        } else {
            Spacer(Modifier.size(GRAB_ACTION_SLOT))
        }
    }
}

private fun DownloadItem.asDirectGrabRecord(): GrabRecord = GrabRecord(
    id = "direct-$id",
    monitoredItemId = "",
    title = title,
    season = seasonNumber,
    episode = episodeNumber,
    episodeTitle = episodeTitle,
    status = when (status) {
        DownloadStatus.Downloading, DownloadStatus.Paused -> GrabStatus.DOWNLOADING
        DownloadStatus.Completed -> GrabStatus.COMPLETED
        DownloadStatus.Failed -> GrabStatus.FAILED
    },
    downloadId = id,
    streamLabel = streamTitle,
    failReason = errorMessage,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

/** Matches the monitored-title row's remove affordance so the two lists read as one column. */
@Composable
private fun GrabCancelIcon() {
    Icon(
        imageVector = Icons.Rounded.DeleteOutline,
        contentDescription = stringResource(Res.string.library_downloads_cancel_active),
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(24.dp),
    )
}

/** Default IconButton touch target — used for the blank slots that hold the columns in place. */
private val GRAB_ACTION_SLOT = 48.dp

@Composable
private fun EmptyStateRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun grabStatusColor(status: GrabStatus) = when (status) {
    GrabStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    GrabStatus.FAILED -> MaterialTheme.colorScheme.error
    GrabStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    GrabStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

private fun GrabRecord.displayLine(): String = buildString {
    append(title)
    if (season != null && episode != null) {
        append(" S")
        append(season.toString().padStart(2, '0'))
        append('E')
        append(episode.toString().padStart(2, '0'))
    }
    episodeTitle
        ?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        ?.let {
            append(" — ")
            append(it)
        }
}

private fun downloadProgressLine(download: DownloadItem): String {
    val percent = download.totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> (download.downloadedBytes * 100L / total).coerceIn(0L, 100L) }
        ?.let { "$it%" }
        ?: "—%"
    val speed = download.bytesPerSecond?.takeIf { it > 0L }?.let(::formatDownloadSpeed) ?: "—"
    val eta = downloadEta(download)
    return if (eta != null) "$percent  •  $speed  •  $eta" else "$percent  •  $speed"
}

/**
 * Time-remaining estimate from the live speed sample. Null whenever it would be a guess — no known
 * total, no speed sample yet, or the remaining bytes are already covered — so the line just drops
 * back to percent + speed instead of showing a nonsense figure.
 */
private fun downloadEta(download: DownloadItem): String? {
    val total = download.totalBytes?.takeIf { it > 0L } ?: return null
    val speed = download.bytesPerSecond?.takeIf { it > 0L } ?: return null
    val remaining = (total - download.downloadedBytes).takeIf { it > 0L } ?: return null
    return "${formatDuration(remaining / speed)} left"
}

private fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(1L)
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds % 60L}s"
        else -> "${seconds}s"
    }
}

private fun formatDownloadSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 125_000L ->
        "${formatOneDecimal(bytesPerSecond * 8.0 / 1_000_000.0)} Mbps"
    bytesPerSecond >= 125L ->
        "${formatOneDecimal(bytesPerSecond * 8.0 / 1_000.0)} Kbps"
    else -> "${bytesPerSecond * 8L} bps"
}

private fun formatOneDecimal(value: Double): String {
    val roundedTenths = (value * 10.0).toLong()
    return "${roundedTenths / 10}.${roundedTenths % 10}"
}

@Composable
private fun monitorModeLabel(item: MonitoredItem): String = when (item.mode) {
    MonitorMode.SELECTED_PLUS_FUTURE -> stringResource(Res.string.library_downloads_mode_selected_future)
    MonitorMode.SELECTED_ONLY -> stringResource(Res.string.library_downloads_mode_selected_only)
    MonitorMode.ALL_MISSING -> stringResource(Res.string.library_downloads_mode_all)
    MonitorMode.MOVIE_WHEN_AVAILABLE -> stringResource(Res.string.library_downloads_mode_movie)
}
