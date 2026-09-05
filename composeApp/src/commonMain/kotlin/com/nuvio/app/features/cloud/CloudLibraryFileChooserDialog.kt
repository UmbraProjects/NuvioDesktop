package com.nuvio.app.features.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.accentFill
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.cloud_library_choose_episode
import nuvio.composeapp.generated.resources.cloud_library_file_picker_title
import nuvio.composeapp.generated.resources.cloud_library_play_file
import nuvio.composeapp.generated.resources.episodes_season
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.graphics.SolidColor

private val ChooserWidth = 560.dp
private val ChooserListMaxHeight = 420.dp

/**
 * Picks which file inside a cloud item to play.
 *
 * Exists because a season folder used to start its first file on a poster click — the item
 * addresses a folder, so there is no single video to open, and guessing lands on episode 1.
 */
@Composable
internal fun CloudLibraryFileChooserDialog(
    item: CloudLibraryItem,
    onFileSelected: (CloudLibraryFile) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = remember(item.stableKey, item.files) { item.playableFileEntries() }
    val isEpisodeFolder = remember(entries) { entries.looksLikeEpisodeFolder() }
    val seasons = remember(entries) { entries.mapNotNull { it.season }.distinct().sorted() }
    // Season chips only earn their space on a complete-series pack; a single-season folder is
    // already one flat list, and a lone chip would be chrome with nothing to switch to.
    val showSeasonChips = isEpisodeFolder && seasons.size > 1
    var viewedSeason by remember(item.stableKey) { mutableStateOf(seasons.firstOrNull()) }
    val shown = if (showSeasonChips) {
        // Unnumbered files stay visible in every season: they are the pack's extras, and hiding
        // them behind a season that does not apply to them would make them unreachable.
        entries.filter { it.season == viewedSeason || it.season == null }
    } else {
        entries
    }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = item.displayName,
        subtitle = if (isEpisodeFolder) {
            stringResource(Res.string.cloud_library_choose_episode)
        } else {
            stringResource(Res.string.cloud_library_file_picker_title)
        },
        modifier = Modifier.width(ChooserWidth),
        maxWidth = ChooserWidth,
        actions = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_close))
            }
        },
    ) {
        if (showSeasonChips) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                seasons.forEach { season ->
                    CloudLibrarySeasonChip(
                        label = stringResource(Res.string.episodes_season, season),
                        selected = season == viewedSeason,
                        onClick = { viewedSeason = season },
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = ChooserListMaxHeight),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { entry -> entry.file.stableKey }) { entry ->
                CloudLibraryChooserRow(
                    entry = entry,
                    onClick = { onFileSelected(entry.file) },
                )
            }
        }
    }
}

@Composable
private fun CloudLibrarySeasonChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text = label,
        modifier = Modifier
            .background(
                brush = if (selected) colors.accentFill(0.22f) else SolidColor(colors.surfaceCard),
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) colors.accent else colors.textMuted,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
private fun CloudLibraryChooserRow(
    entry: CloudLibraryFileEntry,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceCard, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        entry.episodeLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                // The episode title when the release name carries one, the filename otherwise —
                // there is nothing else to tell two files of a pack apart by.
                text = entry.episodeTitle ?: entry.file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = listOfNotNull(
                entry.episodeTitle?.let { entry.file.name },
                entry.file.sizeBytes?.let(::formatCloudLibraryBytes),
            ).joinToString(" • ")
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = stringResource(Res.string.cloud_library_play_file),
            tint = colors.accent,
        )
    }
}
