package com.nuvio.app.features.librarypvr

import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.locallibrary.LocalMediaItem

/**
 * Which top-level folder inside a local-library root a title is *already* filed under.
 *
 * Downloads name their destination folder `<Title> (<Year>)`, but the year is only as good as
 * whatever the calling screen could see, and on the manual route it comes out of the metadata cache
 * — present when the details screen has been opened this session, absent when playback was started
 * from Home or Continue Watching, and absent again after a restart. Left to decide the name on its
 * own, one show downloaded episode by episode over weeks ends up split between
 * `Mushoku Tensei (2021)` and `Mushoku Tensei`, which the scanner then reports as two shows.
 *
 * So before naming anything, ask the library what folder this title already occupies and reuse it
 * verbatim. The answer comes from the scan already held in memory ([LocalLibraryRepository]), which
 * is also the only thing that knows the *actual* spelling on disk — including folders the user
 * created by hand, or that an older version named differently.
 *
 * Everything here degrades to an empty answer, in which case the caller builds a fresh name exactly
 * as it always did.
 */
object LibraryDestinationFolders {

    /**
     * Top-level folder names inside [folder] that already hold [contentId].
     *
     * Matched by content id rather than by title, so a rename, an alternate title, or a franchise
     * sibling id still finds the right folder — [LocalLibraryRepository.itemsForContentId] already
     * covers every id shape an item can carry, anime franchise mapping included.
     */
    fun existingFolderNames(folder: LocalFolder, contentId: String): List<String> {
        if (contentId.isBlank()) return emptyList()
        return LocalLibraryRepository.itemsForContentId(contentId)
            .filter { item -> item.folderId == folder.id }
            .mapNotNull { item -> item.topLevelFolderNameIn(folder) }
            .distinct()
    }

    /**
     * Whether [folder] already holds files for [contentId].
     *
     * Drives the folder picker's hint, so a user choosing where a new episode goes can see which
     * folder the earlier ones went to instead of having to remember.
     */
    fun holdsContent(folder: LocalFolder, contentId: String): Boolean =
        contentId.isNotBlank() &&
            LocalLibraryRepository.itemsForContentId(contentId)
                .any { item -> item.folderId == folder.id }

    /**
     * The first path segment below [folder]'s root that this item's files sit under.
     *
     * A series keeps its episodes at `<root>/<Show folder>/Season 01/...`, a movie at
     * `<root>/<Title (Year)>/<file>`, so the segment immediately after the root is the name a new
     * download has to reuse. Files sitting loose in the root have no such folder and report null.
     */
    private fun LocalMediaItem.topLevelFolderNameIn(folder: LocalFolder): String? {
        val root = folder.path.trimEnd(*PATH_SEPARATORS)
        if (root.isEmpty()) return null
        return files.asSequence()
            .map { file -> file.path }
            .filter { path -> path.isNotBlank() && path.startsWith(root, ignoreCase = true) }
            .mapNotNull { path ->
                path.substring(root.length)
                    .trimStart(*PATH_SEPARATORS)
                    .split(*PATH_SEPARATORS)
                    // The last segment is the file itself; the first of what remains is the folder.
                    .dropLast(1)
                    .firstOrNull()
                    ?.takeIf { segment -> segment.isNotBlank() }
            }
            .firstOrNull()
    }

    private val PATH_SEPARATORS = charArrayOf('/', '\\')
}
