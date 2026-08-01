package com.nuvio.app.features.locallibrary

import com.nuvio.app.features.librarypvr.ManualGrabRow

/**
 * Kept for compatibility with libraries repaired by the older file-moving implementation. The
 * scanner must continue ignoring folders that version created, but the current repair flow never
 * creates, moves, or renames files.
 */
const val LOCAL_UNMATCHED_FOLDER = "unmatchedhtpc"

object LocalAnimeFixPlanner {

    /**
     * Projects scanner coordinates and any existing internal mappings into editable rows.
     * Excluded files stay present so reopening the dialog is always a complete, reversible edit.
     */
    fun rows(item: LocalMediaItem): List<ManualGrabRow> = item.files
        .filterNot { it.fileName.contains("sample", ignoreCase = true) }
        .map { file ->
            ManualGrabRow(
                fileId = file.path,
                fileName = file.fileName,
                sizeBytes = null,
                // A Kitsu entry is entry-relative and therefore has no destination season.
                season = null,
                episode = file.effectiveEpisode,
                episodeTitle = null,
                included = file.isEpisodePlayable,
                // Retain what the filename/folder originally claimed for comparison in the UI.
                sourceSeason = file.season,
            )
        }
        .sortedWith(
            compareBy(
                { it.sourceSeason ?: Int.MAX_VALUE },
                { it.episode ?: Int.MAX_VALUE },
                { it.fileName },
            ),
        )

    /**
     * Builds a complete non-destructive map. Null refuses an empty plan, matching the dialog's
     * confirmation guard and preventing an accidental "hide every episode" operation.
     */
    fun mappings(rows: List<ManualGrabRow>): List<LocalEpisodeMapping>? {
        if (rows.none { it.included && it.episode != null }) return null
        return rows.map { row ->
            LocalEpisodeMapping(
                path = row.fileId,
                episode = row.episode,
                included = row.included && row.episode != null,
            )
        }
    }
}
