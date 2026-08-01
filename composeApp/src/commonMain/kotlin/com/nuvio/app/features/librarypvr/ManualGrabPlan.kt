package com.nuvio.app.features.librarypvr

import com.nuvio.app.features.downloads.SAFE_VIDEO_DOWNLOAD_EXTENSIONS
import com.nuvio.app.features.locallibrary.FilenameParser
import com.nuvio.app.features.metadata.isAnimeNativeId

/** One file inside a pasted source, before the user has confirmed anything. */
data class ManualSourceFile(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
)

/**
 * A planned download: a file from the pasted source plus the coordinates it will be filed under.
 * Seeded from [FilenameParser] and then freely editable — the point of the manual flow is that the
 * user gets the last word on which file is which episode.
 */
data class ManualGrabRow(
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long?,
    /**
     * The season this row is filed under, or null when the grab is numbered entry-relative (anime).
     * This is the *coordinate*: it builds the destination path and the episode id, so under an
     * anime-native id it is deliberately absent — see [isAnimeNativeId].
     */
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val included: Boolean,
    /**
     * The season the *filename* claims, kept even when [season] is null. Purely informational: it
     * tells the user which block of a multi-season pack a row came from, and lets the planner spot
     * a pack that spans several anime entries. Never used to build a path.
     */
    val sourceSeason: Int? = null,
) {
    /** Whether [LibraryFileNaming] has everything it needs to build this row's destination path. */
    fun hasRequiredCoordinates(item: MonitoredItem): Boolean =
        hasRequiredCoordinates(isMovie = item.isMovie, entryRelative = item.usesEntryRelativeNumbering)

    /**
     * Coordinate form taken apart from [MonitoredItem] so callers without one — the season-pack grab
     * started from a stream row — can plan against the same rules.
     */
    fun hasRequiredCoordinates(isMovie: Boolean, entryRelative: Boolean): Boolean = when {
        isMovie -> true
        entryRelative -> episode != null
        else -> season != null && episode != null
    }
}

/**
 * Whether this grab is numbered relative to a single anime entry (episodes from 1, no season)
 * rather than in franchise season/episode coordinates.
 *
 * True for anime destination folders — which have always been laid out absolute-numbered — and,
 * newly, for anything addressed by an anime-native id regardless of the folder, because such an id
 * has no season to file under in the first place.
 */
internal val MonitoredItem.usesEntryRelativeNumbering: Boolean
    get() = isAnime || contentId.isAnimeNativeId()

/**
 * The slot a row will occupy on disk, and therefore what "two files claiming the same episode"
 * means. Entry-relative paths are absolute-numbered with no season component
 * ([LibraryFileNaming.animeEpisodeRelativePath]), so two rows differing only by season still land
 * on one filename — keying the check on the season would let them silently overwrite each other.
 */
internal fun ManualGrabRow.destinationSlot(entryRelative: Boolean): Pair<Int?, Int?> =
    if (entryRelative) null to episode else season to episode

/**
 * Takes [count] rows starting at [anchorIndex] in list order and renumbers them 1..count, leaving
 * every other row deselected.
 *
 * The renumbering is the whole point rather than a side effect. A release group's numbering rarely
 * starts where the anime entry does — a pack's "Season 15 - Ep25 - BW075" may well be episode 1 of
 * its own kitsu entry — and it is the entry's coordinates that build the destination path and the
 * `kitsu:<id>:<ep>` video id. Anchoring on the row the user identified and counting forward is the
 * only mapping that needs no agreement between the release group and TMDB/TVDB.
 */
internal fun List<ManualGrabRow>.autoMatchedFromAnchor(anchorIndex: Int, count: Int): List<ManualGrabRow> {
    if (anchorIndex !in indices || count <= 0) return this
    return mapIndexed { index, row ->
        val offset = index - anchorIndex
        if (offset in 0 until count) row.copy(included = true, episode = offset + 1) else row.copy(included = false)
    }
}

/**
 * Renumbers the currently-included rows [startEpisode]..N in franchise (sourceSeason, episode) order,
 * leaving unticked rows untouched. This is the multi-season answer to [autoMatchedFromAnchor]: when a
 * single anime entry's files span a franchise season boundary (a kitsu entry that runs from, say,
 * S14E20 into S15), the user ticks that whole run across seasons and this collapses it onto one
 * continuous absolute numbering — the coordinate the destination path and the `kitsu:<id>:<ep>` id
 * are built from. Without it two seasons' "E1" would both land on entry-relative episode 1 and collide.
 *
 * [startEpisode] exists because the run being renumbered is not always the start of the entry: a TMDB
 * season can begin partway into a kitsu entry (its "S3E1" is the entry's episode 27), and a single
 * season's files then need shifting onto that offset rather than collapsing onto 1.
 */
internal fun List<ManualGrabRow>.renumberSelectionContinuously(startEpisode: Int = 1): List<ManualGrabRow> {
    val orderedIncluded = withIndex()
        .filter { it.value.included }
        .sortedWith(
            compareBy(
                { it.value.sourceSeason ?: Int.MAX_VALUE },
                { it.value.episode ?: Int.MAX_VALUE },
                { it.value.fileName },
            ),
        )
    if (orderedIncluded.isEmpty()) return this
    val first = startEpisode.coerceAtLeast(1)
    val episodeByIndex = orderedIncluded.mapIndexed { position, indexed -> indexed.index to (first + position) }.toMap()
    return mapIndexed { index, row -> episodeByIndex[index]?.let { row.copy(episode = it) } ?: row }
}

object ManualGrabPlanner {

    /** Below this a file is almost certainly a sample/extra rather than the feature. */
    private const val MIN_FEATURE_BYTES = 20L * 1024L * 1024L

    /**
     * Turns a provider file listing into an editable plan. [sourceName] (the torrent name) is used
     * as the season fallback for packs whose files carry only an episode number.
     */
    fun plan(
        files: List<ManualSourceFile>,
        sourceName: String?,
        item: MonitoredItem,
    ): List<ManualGrabRow> = plan(
        files = files,
        sourceName = sourceName,
        isMovie = item.isMovie,
        entryRelative = item.usesEntryRelativeNumbering,
    )

    /**
     * [MonitoredItem]-free overload: the season-pack grab launched from a stream row plans the same
     * way but has only the content type and the resolved numbering scheme to go on.
     */
    fun plan(
        files: List<ManualSourceFile>,
        sourceName: String?,
        isMovie: Boolean,
        entryRelative: Boolean,
    ): List<ManualGrabRow> {
        val videos = files.filter { it.name.videoExtension() != null }
        if (videos.isEmpty()) return emptyList()
        // Keep samples out of the way, unless dropping them would leave nothing.
        val candidates = videos.filterNot { it.isLikelySample() }.ifEmpty { videos }

        if (isMovie) {
            // A movie source is one feature file; the largest is it. The rest stay listed but off,
            // so the user can still pick a different cut without re-pasting.
            return candidates
                .sortedByDescending { it.sizeBytes ?: 0L }
                .mapIndexed { index, file ->
                    ManualGrabRow(
                        fileId = file.id,
                        fileName = file.name,
                        sizeBytes = file.sizeBytes,
                        season = null,
                        episode = null,
                        episodeTitle = null,
                        included = index == 0,
                    )
                }
        }

        val planned = candidates.map { file ->
            val parsed = FilenameParser.parseEpisode(
                fileName = file.name,
                seasonFolderName = sourceName,
                isAnime = entryRelative,
            )
            ManualGrabRow(
                fileId = file.id,
                fileName = file.name,
                sizeBytes = file.sizeBytes,
                // Entry-relative numbering has no season to file under; keep the label only.
                season = parsed.season.takeUnless { entryRelative },
                episode = parsed.episode,
                episodeTitle = parsed.episodeTitle,
                included = true,
                sourceSeason = parsed.season,
            )
        }

        // A pack covering several seasons, addressed by an id that has no seasons, is ambiguous:
        // every season's episode 1 claims the same entry-relative slot, and nothing in the file
        // names says which block belongs to this entry. Start with none selected rather than
        // guessing — the dialog explains which seasons are present and the user picks one.
        val rows = if (entryRelative && planned.spansMultipleSeasons()) {
            planned.map { it.copy(included = false) }
        } else {
            planned
        }

        return rows.sortedWith(
            compareBy(
                { it.sourceSeason ?: Int.MAX_VALUE },
                { it.episode ?: Int.MAX_VALUE },
                { it.fileName },
            ),
        )
    }

    private fun List<ManualGrabRow>.spansMultipleSeasons(): Boolean =
        mapNotNull { it.sourceSeason }.distinct().size > 1

    private fun ManualSourceFile.isLikelySample(): Boolean =
        (sizeBytes != null && sizeBytes < MIN_FEATURE_BYTES) ||
            name.contains("sample", ignoreCase = true)
}

/** The file's extension when it is one we are willing to download, else null. */
internal fun String.videoExtension(): String? =
    substringAfterLast('.', "").lowercase().takeIf { it in SAFE_VIDEO_DOWNLOAD_EXTENSIONS }
