package com.nuvio.app.features.cloud

import com.nuvio.app.features.locallibrary.FilenameParser

/**
 * One playable file inside a cloud item, with whatever episode coordinates its name carries.
 *
 * A TorBox season folder is a single item holding twenty release filenames in whatever order the
 * provider felt like. Without this, choosing an episode means reading twenty near-identical names.
 */
internal data class CloudLibraryFileEntry(
    val file: CloudLibraryFile,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
) {
    val hasEpisodeCoordinates: Boolean
        get() = episode != null

    /** `S01E04`, or null when the name carries no marker — then the filename is all we have. */
    val episodeLabel: String?
        get() {
            val episodeNumber = episode ?: return null
            val seasonNumber = season ?: 1
            return "S${seasonNumber.pad2()}E${episodeNumber.pad2()}"
        }
}

/**
 * Playable files in the order a viewer expects them.
 *
 * Episode order, not provider order, whenever the names carry markers — a pack whose files come
 * back as `...E10`, `...E01`, `...E02` is otherwise unusable. Files with no marker keep a stable
 * name order and sort after the numbered ones so a pack's extras do not interleave with episodes.
 */
internal fun CloudLibraryItem.playableFileEntries(): List<CloudLibraryFileEntry> =
    playableFiles
        .map { file ->
            val parsed = FilenameParser.parseEpisode(file.name)
            CloudLibraryFileEntry(
                file = file,
                season = parsed.season,
                episode = parsed.episode,
                episodeTitle = parsed.episodeTitle?.takeIf { it.isNotBlank() },
            )
        }
        .sortedWith(
            compareBy<CloudLibraryFileEntry> { !it.hasEpisodeCoordinates }
                .thenBy { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.file.name.lowercase() },
        )

/**
 * Whether this item is a folder of episodes rather than a single title.
 *
 * Two or more files that actually parse as distinct episodes — one episode plus a sample file is
 * still just a movie folder, and would only get a confusing episode list.
 */
internal fun List<CloudLibraryFileEntry>.looksLikeEpisodeFolder(): Boolean =
    count { it.hasEpisodeCoordinates } >= 2

private fun Int.pad2(): String = toString().padStart(2, '0')
