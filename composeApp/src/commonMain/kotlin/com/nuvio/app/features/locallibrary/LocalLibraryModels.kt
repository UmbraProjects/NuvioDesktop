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
    // Anime is an orthogonal flag rather than a LocalFolderType value on purpose: the movie/series
    // axis drives scanning, grouping and TMDB media-type, and dozens of call sites branch on it.
    // isAnime only changes how the title is *matched* (Kitsu first) and how episodes are numbered.
    val isAnime: Boolean = false,
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
    val isAnime: Boolean = false,
    val title: String,
    val year: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    // Native anime ids. When present these take priority in [contentId] so the item flows through
    // the app's existing anime pipeline (absolute-episode stream ids, Kitsu/MAL scrobbling).
    val kitsuId: Int? = null,
    val malId: Int? = null,
    val poster: String? = null,
    val background: String? = null,
    val matchState: LocalMatchState = LocalMatchState.UNMATCHED,
    val catalogId: String? = null,
    val posterRefreshToken: Long? = null,
    val files: List<LocalMediaFile> = emptyList(),
) {
    val isMatched: Boolean
        get() = !imdbId.isNullOrBlank() || tmdbId != null || kitsuId != null || malId != null

    /**
     * The `prefix:id` base of the item's native anime id (kitsu/mal), or null when it has none.
     * Anime episodes are addressed as `kitsu:<id>:<absoluteEpisode>` off this base.
     */
    val animeNativeBase: String?
        get() = when {
            kitsuId != null -> "kitsu:$kitsuId"
            malId != null -> "mal:$malId"
            else -> null
        }

    /** Stremio-style content id used everywhere else in the app (library, details, scrobble). */
    val contentId: String
        get() = animeNativeBase
            ?: imdbId?.takeIf { it.isNotBlank() }
            ?: tmdbId?.let { "tmdb:$it" }
            ?: "$LOCAL_ID_PREFIX$key"

    val contentType: String
        get() = if (type == LocalFolderType.SERIES) "series" else "movie"

    /**
     * Whether [videoId] addresses THIS title — one of its own ids in any namespace it carries,
     * or an episode id derived from one (`<base>:season:episode` / `<base>:episode`). Stream
     * lookups receive video ids from whatever meta screen happens to be active; without this
     * check a stale or franchise-level meta can pull an unrelated title's local file into the
     * stream list (e.g. an anime movie answering a live-action show's episode id).
     */
    fun ownsVideoId(videoId: String): Boolean {
        val bases = buildList {
            add("$LOCAL_ID_PREFIX$key")
            imdbId?.takeIf { it.isNotBlank() }?.let { add(it) }
            tmdbId?.let { add("tmdb:$it") }
            kitsuId?.let { add("kitsu:$it") }
            malId?.let { add("mal:$it") }
        }
        return bases.any { base -> videoId == base || videoId.startsWith("$base:") }
    }

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
    val kitsuId: Int? = null,
    val malId: Int? = null,
    val title: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val posterRefreshToken: Long? = null,
    val matchState: LocalMatchState = LocalMatchState.MANUAL,
)

/**
 * A candidate returned by the Fix-match dialog's text search. Anime items search Kitsu (so
 * [kitsuId] is set and [tmdbId] is null); everything else searches TMDB.
 */
data class LocalMatchCandidate(
    val tmdbId: Int? = null,
    val kitsuId: Int? = null,
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

    // Basic-mode buckets: anime gets its own movie/series sections, mirroring the plain ones.
    val movies: List<LocalMediaItem>
        get() = items.filter { it.type == LocalFolderType.MOVIES && !it.isAnime }.sortedBy { it.title.lowercase() }

    val series: List<LocalMediaItem>
        get() = items.filter { it.type == LocalFolderType.SERIES && !it.isAnime }.sortedBy { it.title.lowercase() }

    val animeMovies: List<LocalMediaItem>
        get() = items.filter { it.type == LocalFolderType.MOVIES && it.isAnime }.sortedBy { it.title.lowercase() }

    val animeSeries: List<LocalMediaItem>
        get() = items.filter { it.type == LocalFolderType.SERIES && it.isAnime }.sortedBy { it.title.lowercase() }

    val unmatchedCount: Int
        get() = items.count { !it.isMatched }
}

internal const val LOCAL_ID_PREFIX = "local:"

fun String.isLocalLibraryId(): Boolean = startsWith(LOCAL_ID_PREFIX)
