package com.nuvio.app.features.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import com.nuvio.app.core.ui.nuvio
import java.io.File
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * A folder scan in progress or awaiting confirmation. [result] is null until the scan finishes.
 */
internal data class GameFolderImport(
    val folder: File,
    val result: GameFolderScanResult? = null,
)

/**
 * Confirms a folder scan before anything is added.
 *
 * The scan guesses which executable is the game, so every row can be unticked: a launcher that
 * was really a tool, a mod folder, a game already tracked under another name. What is imported
 * gets Steam's artwork by default, and the subtitle says so, because the pickers in the editor
 * are where that changes.
 */
@Composable
internal fun GameFolderImportDialog(
    import: GameFolderImport,
    onImport: (List<GameFolderCandidate>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val result = import.result
    // Keyed on the result so a fresh scan starts with everything ticked.
    var excluded by remember(result) { mutableStateOf(emptySet<String>()) }
    val selected = result?.candidates?.filterNot { it.executablePath in excluded }.orEmpty()

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.games_import_import_games_from_folder),
        subtitle = stringResource(Res.string.games_import_subtitle),
        maxWidth = 760.dp,
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
            Button(
                enabled = selected.isNotEmpty(),
                onClick = { onImport(selected) },
            ) {
                Text(
                    when (selected.size) {
                        0 -> stringResource(Res.string.action_import)
                        1 -> stringResource(Res.string.games_import_import_1_game)
                        else -> stringResource(Res.string.games_import_n_games, selected.size)
                    },
                )
            }
        },
    ) {
        Text(
            text = import.folder.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            result == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            result.candidates.isEmpty() -> Text(
                text = stringResource(Res.string.games_import_nothing_new_to_import_here),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(vertical = 20.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(result.candidates, key = { it.executablePath }) { candidate ->
                    val included = candidate.executablePath !in excluded
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                excluded = if (included) excluded + candidate.executablePath
                                else excluded - candidate.executablePath
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = included,
                            onCheckedChange = { checked ->
                                excluded = if (checked) excluded - candidate.executablePath
                                else excluded + candidate.executablePath
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = candidate.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (included) colors.textPrimary else colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                // The path relative to the scanned folder: the part that says
                                // which executable was picked, without the prefix every row shares.
                                text = candidate.executablePath
                                    .removePrefix(import.folder.absolutePath)
                                    .trimStart(File.separatorChar),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (candidate.steamAppId != null) {
                            Text(
                                text = stringResource(Res.string.games_import_steam),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            }
        }
        result?.let { scan ->
            val notes = buildList {
                if (scan.skippedExisting > 0) {
                    add("${scan.skippedExisting} already in your library")
                }
                if (scan.foldersWithoutExecutable > 0) {
                    add("${scan.foldersWithoutExecutable} with no executable")
                }
            }
            if (notes.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.games_import_skipped, notes.joinToString("  ·  ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}
