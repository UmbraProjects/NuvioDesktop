package com.nuvio.app.features.locallibrary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.kitsu.KitsuService
import com.nuvio.app.features.librarypvr.ManualEpisodePreview
import com.nuvio.app.features.librarypvr.SeasonPackSelectionDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.local_library_fix_apply
import nuvio.composeapp.generated.resources.local_library_fix_note
import nuvio.composeapp.generated.resources.local_library_fix_saved
import nuvio.composeapp.generated.resources.local_library_fix_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Non-destructive Kitsu episode alignment. Confirmation persists an internal path-to-episode map;
 * files and the scanner coordinates derived from their names remain untouched.
 */
@Composable
internal fun LocalAnimeFixDialog(
    item: LocalMediaItem,
    onDismiss: () -> Unit,
) {
    val rows = remember(item.key, item.files) { LocalAnimeFixPlanner.rows(item) }
    val kitsuId = item.kitsuId
    var episodePreviews by remember(item.contentId) {
        mutableStateOf<Map<Int, ManualEpisodePreview>>(emptyMap())
    }

    LaunchedEffect(item.contentType, item.contentId) {
        val meta = runCatching {
            MetaDetailsRepository.fetch(type = item.contentType, id = item.contentId)
        }.getOrNull()
        episodePreviews = meta?.videos.orEmpty()
            .mapNotNull { video ->
                val number = video.episode
                    ?: video.id.substringAfterLast(':').toIntOrNull()
                    ?: return@mapNotNull null
                number to ManualEpisodePreview(
                    episode = number,
                    title = video.title,
                    thumbnail = video.thumbnail,
                    released = video.released,
                )
            }
            .distinctBy { it.first }
            .toMap()
    }

    SeasonPackSelectionDialog(
        title = item.displayYear?.let { "${item.title} ($it)" } ?: item.title,
        sourceName = item.sourceLocation,
        initialRows = rows,
        entryRelative = true,
        onCountEntryEpisodes = kitsuId?.let { id -> { KitsuService.fetchEpisodeCount(id) } },
        dialogTitleRes = Res.string.local_library_fix_title,
        confirmLabelRes = Res.string.local_library_fix_apply,
        note = stringResource(Res.string.local_library_fix_note),
        episodePreviews = episodePreviews,
        showEpisodePreviews = true,
        onDismiss = onDismiss,
        onConfirm = { confirmed ->
            val mappings = LocalAnimeFixPlanner.mappings(confirmed)
                ?: return@SeasonPackSelectionDialog
            LocalLibraryRepository.applyEpisodeMappings(item, mappings)
            onDismiss()
            LocalAnimeMappingFeedback.showSaved(mappings.count { it.included })
        },
    )
}

private object LocalAnimeMappingFeedback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun showSaved(count: Int) {
        scope.launch {
            NuvioToastController.show(getString(Res.string.local_library_fix_saved, count))
        }
    }
}
