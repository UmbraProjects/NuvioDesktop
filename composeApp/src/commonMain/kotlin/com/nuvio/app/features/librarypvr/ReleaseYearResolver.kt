package com.nuvio.app.features.librarypvr

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.locallibrary.LocalLibraryRepository

/**
 * The release year of a title, for the `<Title> (<Year>)` folder a download is filed into.
 *
 * The year used to come from one cache read — `MetaDetailsRepository.peek` — which answers only for
 * a title whose details screen has been opened this session. Playback started from Home or Continue
 * Watching, or anything at all after a restart, therefore produced a yearless folder, and the same
 * show ended up split between `Mushoku Tensei (2021)` and `Mushoku Tensei` depending on where the
 * user happened to click. [LibraryDestinationFolders] keeps later episodes together once a folder
 * exists; this is what stops the *first* one landing in the wrong-shaped name to begin with.
 *
 * Two entry points, deliberately:
 *
 * - [peek] is synchronous and free. It is what a click handler can afford, and it now reads every
 *   in-memory store that knows a year rather than only the details-screen cache.
 * - [resolve] adds a metadata fetch behind it, for callers that can suspend. The stream screen uses
 *   it: playback starts on whatever [peek] returned, and the year is filled in behind that, long
 *   before the user opens the download menu.
 */
object ReleaseYearResolver {

    /**
     * The year from whatever is already in memory, or null.
     *
     * Order is by how specific the source is to *this* title. The metadata record is the title's
     * own; the saved-library and local-library entries are records of it that a scan or a sync
     * wrote, and are just as trustworthy but likelier to be a franchise-level row.
     */
    fun peek(type: String, id: String): Int? {
        if (id.isBlank()) return null
        parseReleaseYear(MetaDetailsRepository.peekAny(type, id)?.releaseInfo)?.let { return it }
        parseReleaseYear(LibraryRepository.savedItem(id)?.releaseInfo)?.let { return it }
        return LocalLibraryRepository.itemsForContentId(id)
            .firstNotNullOfOrNull { item -> item.year?.takeIf { it in MIN_YEAR..MAX_YEAR } }
    }

    /**
     * [peek], then a metadata fetch if it came up empty.
     *
     * `fetchLightweightMeta` rather than the full `fetch`: only `releaseInfo` is wanted, the
     * lightweight path does not require a video list, and it is single-flighted and cached, so a
     * title the home rows or hero already warmed costs nothing. Every failure returns null and the
     * caller names the folder without a year exactly as before.
     */
    suspend fun resolve(type: String, id: String): Int? {
        peek(type, id)?.let { return it }
        if (id.isBlank() || type.isBlank()) return null
        val meta = runCatching { MetaDetailsRepository.fetchLightweightMeta(type = type, id = id) }
            .getOrNull()
        return parseReleaseYear(meta?.releaseInfo)
    }

    /**
     * The first four-digit year in a Stremio `releaseInfo`, which is either `"2021"` or a range
     * like `"2021-2023"` / `"2019–"`. The first is the one that names the folder, matching what
     * every catalog and the local scanner display for the title.
     */
    fun parseReleaseYear(releaseInfo: String?): Int? {
        val text = releaseInfo?.trim().orEmpty()
        if (text.isEmpty()) return null
        return yearRegex.find(text)?.value?.toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR }
    }

    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")

    // Matches the range LibraryFileNaming will accept; a year outside it never reaches a folder
    // name, so reporting one here would only look like the lookup had succeeded.
    private const val MIN_YEAR = 1870
    private const val MAX_YEAR = 2100
}
