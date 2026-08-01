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

    /** Windows drive prefix ("A:") when the path has one; null for UNC and POSIX paths. */
    val driveLabel: String?
        get() = path.takeIf { it.length >= 2 && it[1] == ':' }?.take(2)?.uppercase()

    /**
     * [displayName] qualified by its drive, for pickers that would otherwise show a bare folder
     * name. Two drives each holding an "Anime" folder are indistinguishable without this.
     */
    val displayNameWithDrive: String
        get() = driveLabel?.let { "$it  $displayName" } ?: displayName
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
/**
 * The type bucket a default catalog stands in for. Set on the four always-present default catalogs
 * (Movies / Shows / Anime Movies / Anime Series) so newly scanned items can auto-file into the
 * matching one even after the user renames it, and so those four can be protected from deletion;
 * null on hand-created catalogs, which never auto-collect new items and can be freely deleted.
 */
@Serializable
enum class LocalLibraryBucket {
    MOVIES,
    SHOWS,
    ANIME_MOVIES,
    ANIME_SERIES,
}

/** What a normal Play/episode/Continue Watching click should do when a local file is available. */
@Serializable
enum class LocalLibraryPlaybackPreference {
    SOURCE_PICKER,
    LOCAL_LIBRARY,
    ;

    fun alternate(): LocalLibraryPlaybackPreference = when (this) {
        SOURCE_PICKER -> LOCAL_LIBRARY
        LOCAL_LIBRARY -> SOURCE_PICKER
    }

    fun behaviorFor(useAlternate: Boolean): LocalLibraryPlaybackPreference =
        if (useAlternate) alternate() else this

    /**
     * The source picker is always a valid alternate. Local playback is only offered when the
     * selected movie/episode actually resolves to a file.
     */
    fun canOfferAlternate(hasLocalFile: Boolean): Boolean =
        this == LOCAL_LIBRARY || hasLocalFile
}

/**
 * A user-created catalog (Advanced mode). [order] controls its position in the Library.
 * [color] is a packed ARGB value used to tint the catalog's icon on posters; null = default.
 * [defaultBucket] is set only on catalogs seeded from the Basic type buckets; a new scanned item
 * auto-files into the catalog whose bucket matches its type/anime-ness (see [LocalLibraryBucket]).
 */
@Serializable
data class LocalCatalog(
    val id: String,
    val name: String,
    val order: Int = 0,
    val color: Long? = null,
    val defaultBucket: LocalLibraryBucket? = null,
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
    /**
     * Optional entry-relative episode assigned by the user. The scanner-derived [season]/[episode]
     * remain intact so the mapping can be reviewed or reset without touching the file on disk.
     */
    val mappedEpisode: Int? = null,
    /** The file stays visible in the library but does not back an episode while excluded. */
    val excludedFromEpisodeMapping: Boolean = false,
) {
    val fileName: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')

    val effectiveSeason: Int?
        get() = if (mappedEpisode != null) null else season

    val effectiveEpisode: Int?
        get() = mappedEpisode ?: episode

    val isEpisodePlayable: Boolean
        get() = !excludedFromEpisodeMapping
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
     * Where this title lives on disk, for telling two same-named entries apart. A single-file item
     * (a movie) reports the file itself, since that is the more useful answer; a multi-file one
     * reports the deepest folder all its episodes share, which is the show folder even when the
     * episodes sit in per-season subfolders.
     */
    val sourceLocation: String?
        get() {
            val paths = files.map { it.path }.filter { it.isNotBlank() }
            paths.singleOrNull()?.let { return it }
            if (paths.isEmpty()) return null
            return paths
                .map { it.parentDirectoryPath() }
                .reduce(::commonDirectoryPath)
                .takeIf { it.isNotBlank() }
        }

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
    /**
     * Null keeps automatic filename parsing active. A non-null list is the complete internal
     * per-file episode map for this item.
     */
    val episodeMappings: List<LocalEpisodeMapping>? = null,
    val matchState: LocalMatchState = LocalMatchState.MANUAL,
)

@Serializable
data class LocalEpisodeMapping(
    val path: String,
    val episode: Int? = null,
    val included: Boolean = true,
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
    val catalogs: List<LocalCatalog> = emptyList(),
    val playbackPreference: LocalLibraryPlaybackPreference =
        LocalLibraryPlaybackPreference.SOURCE_PICKER,
    // When true, catalogs (and the Unsorted row) with no items are hidden from the management list.
    // Default false: the four defaults are visible even when empty so the user knows they exist and
    // hides them by a deliberate choice (the eye toggle).
    val hideEmptyCatalogs: Boolean = false,
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

/** The path with its last segment dropped, or "" when there is nothing above it. */
private fun String.parentDirectoryPath(): String {
    val cut = trimEnd('/', '\\').lastIndexOfAny(charArrayOf('/', '\\'))
    return if (cut <= 0) "" else substring(0, cut)
}

/**
 * The deepest directory shared by two paths. Segments compare case-insensitively because this is a
 * Windows-only build, where the same folder is routinely spelled with different casing.
 */
private fun commonDirectoryPath(first: String, second: String): String {
    val separator = if ('\\' in first) "\\" else "/"
    val shared = first.split('/', '\\')
        .zip(second.split('/', '\\'))
        .takeWhile { (a, b) -> a.equals(b, ignoreCase = true) }
        .map { it.first }
    return shared.joinToString(separator)
}

internal const val LOCAL_ID_PREFIX = "local:"

fun String.isLocalLibraryId(): Boolean = startsWith(LOCAL_ID_PREFIX)
