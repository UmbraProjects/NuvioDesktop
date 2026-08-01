package com.nuvio.app.features.librarypvr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.desktopHorizontalListNavigation
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.effectiveEpisodeNumber
import com.nuvio.app.features.details.effectiveSeasonNumber
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_downloads_edit_monitoring
import nuvio.composeapp.generated.resources.library_downloads_episode_monitored
import nuvio.composeapp.generated.resources.library_downloads_episode_owned
import nuvio.composeapp.generated.resources.library_downloads_picker_close
import nuvio.composeapp.generated.resources.library_downloads_picker_empty
import nuvio.composeapp.generated.resources.library_downloads_picker_hint
import nuvio.composeapp.generated.resources.library_downloads_picker_loading
import nuvio.composeapp.generated.resources.library_downloads_season_label
import nuvio.composeapp.generated.resources.library_downloads_select_all
import nuvio.composeapp.generated.resources.library_downloads_select_none
import nuvio.composeapp.generated.resources.library_downloads_selected_count
import nuvio.composeapp.generated.resources.library_downloads_specials_label
import org.jetbrains.compose.resources.stringResource

/**
 * Per-season / per-episode monitoring editor, styled after the details-page episode selector.
 *
 * Interaction (chosen deliberately so one chip does not have to mean two things):
 *  - **Left-click a season chip** switches the grid to that season.
 *  - **Right-click a season chip** toggles monitoring for the whole season.
 *  - **Click an episode** toggles just that episode; monitored episodes are tinted with the accent.
 *
 * Episodes already on disk (or already downloaded) show a check badge so you can tell at a glance
 * what is left to fetch.
 */
@Composable
internal fun MonitoredEpisodePickerDialog(
    item: MonitoredItem,
    onDismiss: () -> Unit,
) {
    val pvr by LibraryPvrRepository.uiState.collectAsStateWithLifecycle()
    // Re-read from the repository so edits made in this dialog are reflected immediately.
    val current = remember(pvr.monitoredItems, item.id) {
        pvr.monitoredItems.firstOrNull { it.id == item.id } ?: item
    }

    var videos by remember(item.id) { mutableStateOf<List<MetaVideo>?>(null) }
    LaunchedEffect(item.id) {
        videos = runCatching { MetaDetailsRepository.fetch(item.contentType, item.contentId) }
            .getOrNull()
            ?.videos
            ?.filter { it.season != null && it.episode != null && (it.season ?: 0) > 0 }
            ?.sortedWith(compareBy({ it.season }, { it.episode }))
            ?: emptyList()
    }

    val bySeason = remember(videos) {
        videos.orEmpty().groupBy { it.season!! }.toSortedMap()
    }
    val seasons = remember(bySeason) { bySeason.keys.toList() }
    var viewedSeason by remember(item.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(seasons) {
        if (viewedSeason == null || viewedSeason !in seasons) viewedSeason = seasons.firstOrNull()
    }

    val owned = rememberOwnedEpisodes(current, videos.orEmpty())
    val selectedTotal = remember(current, videos) {
        videos.orEmpty().count { current.selectsEpisode(it.season!!, it.episode!!) }
    }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = current.year?.let { "${current.title} ($it)" } ?: current.title,
        subtitle = stringResource(Res.string.library_downloads_edit_monitoring),
        modifier = Modifier.width(PICKER_WIDTH),
        maxWidth = PICKER_WIDTH,
        actions = {
            TextButton(
                onClick = {
                    onDismiss()
                    LibraryPvrScheduler.checkNow()
                },
            ) { Text(stringResource(Res.string.library_downloads_picker_close)) }
        },
    ) {
        ModePicker(
            mode = current.mode,
            onSelect = { mode ->
                LibraryPvrRepository.mutateMonitored(current.id) { it.copy(mode = mode) }
            },
            showLabelAndHint = false,
        )

        when {
            videos == null -> PickerStatusRow(stringResource(Res.string.library_downloads_picker_loading), busy = true)
            seasons.isEmpty() -> PickerStatusRow(stringResource(Res.string.library_downloads_picker_empty), busy = false)
            else -> {
                SeasonChipRow(
                    seasons = seasons,
                    bySeason = bySeason,
                    item = current,
                    viewedSeason = viewedSeason,
                    onView = { viewedSeason = it },
                    onToggleSeason = { season ->
                        val episodes = bySeason[season]?.mapNotNull { it.episode }.orEmpty()
                        val turnOn = !current.selectsWholeSeason(season, episodes)
                        LibraryPvrRepository.mutateMonitored(current.id) {
                            it.withSeasonMonitored(season, turnOn)
                        }
                    },
                )

                SelectionToolbar(
                    selectedCount = selectedTotal,
                    onAll = {
                        LibraryPvrRepository.mutateMonitored(current.id) {
                            it.withAllMonitored(seasons, monitored = true)
                        }
                    },
                    onNone = {
                        LibraryPvrRepository.mutateMonitored(current.id) {
                            it.withAllMonitored(seasons, monitored = false)
                        }
                    },
                )

                val shown = viewedSeason?.let { bySeason[it] }.orEmpty()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(EPISODE_GRID_COLUMNS),
                    modifier = Modifier.fillMaxWidth().heightIn(max = EPISODE_GRID_MAX_HEIGHT),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { "${it.season}:${it.episode}" }) { video ->
                        val season = video.season ?: return@items
                        val episode = video.episode ?: return@items
                        EpisodeSelectCard(
                            video = video,
                            monitored = current.selectsEpisode(season, episode),
                            owned = (season to episode) in owned,
                            onToggle = {
                                val next = !current.selectsEpisode(season, episode)
                                LibraryPvrRepository.mutateMonitored(current.id) {
                                    it.withEpisodeMonitored(season, episode, next)
                                }
                            },
                        )
                    }
                }

                Text(
                    text = stringResource(Res.string.library_downloads_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Episodes already satisfied for this title, keyed by (season, episode).
 *
 * On-disk detection goes through the exact same id-aware path the details page uses
 * ([LocalLibraryRepository.hasLocalFileForEpisode]) rather than a plain (folder, season, episode)
 * match, so the badge here can never disagree with the details episode grid. The old match required
 * the file to live in this item's own target folder and matched the content id verbatim, so an
 * episode downloaded into a different library folder — or anime bare-numbering / Kitsu-MAL franchise
 * mappings — showed no badge even though the episode was clearly on disk. Completed transfers are
 * folded in too, so a just-finished download reads as owned before the library rescan files it.
 */
@Composable
private fun rememberOwnedEpisodes(
    item: MonitoredItem,
    videos: List<MetaVideo>,
): Set<Pair<Int, Int>> {
    val local by LocalLibraryRepository.uiState.collectAsStateWithLifecycle()
    val downloads by DownloadsRepository.uiState.collectAsStateWithLifecycle()
    return remember(local.items, downloads.items, item.contentId, videos) {
        buildSet {
            videos.forEach { video ->
                val season = video.season ?: return@forEach
                val episode = video.episode ?: return@forEach
                val onDisk = LocalLibraryRepository.hasLocalFileForEpisode(
                    metaId = item.contentId,
                    videoId = video.id,
                    season = video.effectiveSeasonNumber(),
                    episode = video.effectiveEpisodeNumber(),
                )
                if (onDisk) add(season to episode)
            }
            downloads.items.forEach { download ->
                if (
                    download.parentMetaId == item.contentId &&
                    download.status == DownloadStatus.Completed &&
                    download.seasonNumber != null &&
                    download.episodeNumber != null
                ) {
                    add(download.seasonNumber!! to download.episodeNumber!!)
                }
            }
        }
    }
}

@Composable
private fun SeasonChipRow(
    seasons: List<Int>,
    bySeason: Map<Int, List<MetaVideo>>,
    item: MonitoredItem,
    viewedSeason: Int?,
    onView: (Int) -> Unit,
    onToggleSeason: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            // Without this the row is unreachable past whatever fits on screen — the dialog has no
            // vertical scroller of its own at this point, so a plain wheel (not Shift+wheel) scrolls
            // it horizontally, and drag/arrow-key navigation come along too.
            .desktopHorizontalListNavigation(listState, treatPlainScrollAsHorizontal = true),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(seasons, key = { _, season -> season }) { _, season ->
            val episodes = bySeason[season]?.mapNotNull { it.episode }.orEmpty()
            val whole = item.selectsWholeSeason(season, episodes)
            val partial = item.selectsPartOfSeason(season, episodes)
            val isViewed = season == viewedSeason
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    // The viewed season gets a subtle plate so "what am I looking at" stays
                    // readable independently of the accent, which means "monitored".
                    .background(
                        if (isViewed) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onView(season) }
                    .secondaryClick { onToggleSeason(season) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (season == 0) {
                        stringResource(Res.string.library_downloads_specials_label)
                    } else {
                        stringResource(Res.string.library_downloads_season_label, season)
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = if (isViewed) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    color = when {
                        whole -> MaterialTheme.nuvio.colors.accent
                        // Half-strength accent reads as "some of this season", without needing a
                        // third colour that would compete with the viewed-season plate.
                        partial -> MaterialTheme.nuvio.colors.accent.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SelectionToolbar(selectedCount: Int, onAll: () -> Unit, onNone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.library_downloads_selected_count, selectedCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAll) { Text(stringResource(Res.string.library_downloads_select_all)) }
        TextButton(onClick = onNone) { Text(stringResource(Res.string.library_downloads_select_none)) }
    }
}

@Composable
private fun EpisodeSelectCard(
    video: MetaVideo,
    monitored: Boolean,
    owned: Boolean,
    onToggle: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(EPISODE_CARD_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                width = if (monitored) 2.dp else 1.dp,
                color = if (monitored) accent else Color.White.copy(alpha = 0.12f),
                shape = shape,
            )
            .clickable(onClick = onToggle),
    ) {
        video.thumbnail?.let { thumbnail ->
            NuvioAsyncImage(
                model = thumbnail,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Darken for text legibility, then wash the whole tile in the accent when monitored so the
        // selected set is scannable from across the grid rather than needing per-tile inspection.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.15f),
                            Color.Black.copy(alpha = 0.75f),
                        ),
                    ),
                ),
        )
        if (monitored) {
            Box(modifier = Modifier.fillMaxSize().background(accent.copy(alpha = 0.28f)))
        }

        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = video.episodeCode(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            if (owned) {
                // Matches the details page EpisodeDownloadedBadge exactly (same circle, alpha, icon,
                // sizes) so the two grids read as one system.
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(Res.string.library_downloads_episode_owned),
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        if (monitored) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(Res.string.library_downloads_episode_monitored),
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        Text(
            text = video.title,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 6.dp, end = 28.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun PickerStatusRow(text: String, busy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun MetaVideo.episodeCode(): String {
    val s = season?.toString()?.padStart(2, '0') ?: "??"
    val e = episode?.toString()?.padStart(2, '0') ?: "??"
    return "S${s}E$e"
}

// Sized so four episode cards land near 16:9 rather than the cramped ~1.4:1 a narrower dialog gave.
private val PICKER_WIDTH = 660.dp
private val EPISODE_GRID_MAX_HEIGHT = 360.dp
private val EPISODE_CARD_HEIGHT = 92.dp
private const val EPISODE_GRID_COLUMNS = 4
