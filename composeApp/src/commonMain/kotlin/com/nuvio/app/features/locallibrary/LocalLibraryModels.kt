package com.nuvio.app.features.locallibrary

import kotlinx.serialization.Serializable

/**
 * A local-library folder the user has pointed Nuvio at. The [type] is chosen by the user when
 * the folder is added (movie folder vs TV folder) and the scanner trusts it — no auto-detection.
 */
@Serializable
data class LocalFolder(
    val id: String,
    val path: String,
    val type: LocalFolderType,
    val label: String? = null,
    val addedAtEpochMs: Long = 0,
) {
    val displayName: String
        get() = label?.takeIf { it.isNotBlank() }
            ?: path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { path }
}

@Serializable
enum class LocalFolderType {
    MOVIES,
    SERIES,
}

/**
 * How local items are grouped in the Library.
 * - BASIC: all movies in one list, all shows in another.
 * - ADVANCED: the user creates named catalogs and assigns items to them (e.g. a "Harry Potter"
 *   catalog holding all the films). Unassigned items fall into an "Unsorted" section.
 */
@Serializable
enum class LocalLibraryMode {
    BASIC,
    ADVANCED,
}

/**
 * A user-created catalog (Advanced mode). [order] controls its position in the Library.
 * [color] is a packed ARGB value used to tint the catalog's icon on posters; null = default.
 */
@Serializable
data class LocalCatalog(
    val id: String,
    val name: String,
    val order: Int = 0,
    val color: Long? = null,
)

/** How an item acquired its external ids — drives whether scrobbling is allowed and the badge shown. */
@Serializable
enum class LocalMatchState {
    /** No IMDb/TMDB id — plays but never scrobbles. */
    UNMATCHED,

    /** Auto-detected by the matcher via TMDB search. */
    AUTO,

    /** User confirmed/pasted an id via the Fix-match dialog. Never overwritten by a rescan. */
    MANUAL,
}

/** A single playable file belonging to a [LocalMediaItem]. Movies have one; series have many. */
@Serializable
data class LocalMediaFile(
    val path: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val fileName: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')
}

/**
 * One logical title in the local library — a movie (single [files] entry) or a series (one file
 * per episode). [key] is stable across rescans (folder id + normalized title) so manual id
 * corrections survive re-scanning.
 */
@Serializable
data class LocalMediaItem(
    val key: String,
    val folderId: String,
    val type: LocalFolderType,
    val title: String,
    val year: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val poster: String? = null,
    val background: String? = null,
    val matchState: LocalMatchState = LocalMatchState.UNMATCHED,
    val catalogId: String? = null,
    val posterRefreshToken: Long? = null,
    val files: List<LocalMediaFile> = emptyList(),
) {
    val isMatched: Boolean
        get() = !imdbId.isNullOrBlank() || tmdbId != null

    /** Stremio-style content id used everywhere else in the app (library, details, scrobble). */
    val contentId: String
        get() = imdbId?.takeIf { it.isNotBlank() }
            ?: tmdbId?.let { "tmdb:$it" }
            ?: "$LOCAL_ID_PREFIX$key"

    val contentType: String
        get() = if (type == LocalFolderType.SERIES) "series" else "movie"

    val displayYear: String?
        get() = year?.takeIf { it in 1870..2100 }?.toString()
}

/** Result of scanning one folder: the discovered items plus any non-fatal problem to surface. */
data class LocalScanResult(
    val items: List<LocalMediaItem> = emptyList(),
    val errorMessage: String? = null,
)

/** A manual/auto id correction persisted per item [key] so it is re-applied after each rescan. */
@Serializable
data class LocalMatchOverride(
    val key: String,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val title: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val posterRefreshToken: Long? = null,
    val matchState: LocalMatchState = LocalMatchState.MANUAL,
)

/** A candidate returned by the TMDB text search used in the Fix-match dialog. */
data class LocalMatchCandidate(
    val tmdbId: Int,
    val type: LocalFolderType,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val overview: String? = null,
)

data class LocalLibraryUiState(
    val folders: List<LocalFolder> = emptyList(),
    val items: List<LocalMediaItem> = emptyList(),
    val mode: LocalLibraryMode = LocalLibraryMode.BASIC,
    val catalogs: List<LocalCatalog> = emptyList(),
    val isLoaded: Boolean = false,
    val isScanning: Boolean = false,
    val errorMessage: String? = null,
) {
    val sortedCatalogs: List<LocalCatalog>
        get() = catalogs.sortedWith(compareBy({ it.order }, { it.name.lowercase() }))

    fun itemsInCatalog(catalogId: String?): List<LocalMediaItem> =
        items.filter { it.catalogId == catalogId }.sortedBy { it.title.lowercase() }
    val movies: List<LocalMediaItem>
        get() = items.filter { it.type == LocalFolderType.MOVIES }.sortedBy { it.title.lowercase() }

    val series: List<LocalMediaItem>
        get() = items.filter { it.type == LocalFolderType.SERIES }.sortedBy { it.title.lowercase() }

    val unmatchedCount: Int
        get() = items.count { !it.isMatched }
}

internal const val LOCAL_ID_PREFIX = "local:"

fun String.isLocalLibraryId(): Boolean = startsWith(LOCAL_ID_PREFIX)
