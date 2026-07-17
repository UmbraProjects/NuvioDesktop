package com.nuvio.app.features.locallibrary

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.kitsu.KitsuService
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class LocalConfigPayload(
    val folders: List<LocalFolder> = emptyList(),
    val mode: LocalLibraryMode = LocalLibraryMode.BASIC,
    val catalogs: List<LocalCatalog> = emptyList(),
    val assignments: Map<String, String> = emptyMap(),
)

@Serializable
private data class LocalOverridesPayload(val overrides: List<LocalMatchOverride> = emptyList())

@Serializable
private data class LocalCachePayload(val items: List<LocalMediaItem> = emptyList())

/**
 * Owns the local library: configured folders, the scanned/matched item list, and manual id
 * corrections. Mirrors the CloudLibraryRepository shape — it exposes a [uiState] that
 * LibraryRepository folds into Library sections, plus lookups the details screen uses to overlay
 * local files as embedded streams.
 */
object LocalLibraryRepository {
    private val log = Logger.withTag("LocalLibraryRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(LocalLibraryUiState())
    val uiState = _uiState.asStateFlow()

    private var profileId: Int = 1
    private var hasLoaded = false
    private var folders: List<LocalFolder> = emptyList()
    private var mode: LocalLibraryMode = LocalLibraryMode.BASIC
    private var catalogs: List<LocalCatalog> = emptyList()
    private var assignmentsByKey: Map<String, String> = emptyMap()
    private var overridesByKey: Map<String, LocalMatchOverride> = emptyMap()
    private var itemsByKey: Map<String, LocalMediaItem> = emptyMap()
    private var scanJob: Job? = null

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk(ProfileRepository.activeProfileId)
        if (folders.isNotEmpty()) rescan()
    }

    fun onProfileChanged(newProfileId: Int) {
        if (newProfileId == profileId && hasLoaded) return
        scanJob?.cancel()
        loadFromDisk(newProfileId)
        if (folders.isNotEmpty()) rescan()
    }

    private fun loadFromDisk(profileId: Int) {
        this.profileId = profileId
        hasLoaded = true

        val config = LocalLibraryStorage.loadConfig(profileId)
            ?.let { runCatching { json.decodeFromString<LocalConfigPayload>(it) }.getOrNull() }
        folders = config?.folders.orEmpty()
        mode = config?.mode ?: LocalLibraryMode.BASIC
        catalogs = config?.catalogs.orEmpty()
        assignmentsByKey = config?.assignments.orEmpty()
        overridesByKey = LocalLibraryStorage.loadOverrides(profileId)
            ?.let { runCatching { json.decodeFromString<LocalOverridesPayload>(it).overrides }.getOrNull() }
            .orEmpty()
            .associateBy { it.key }
        itemsByKey = LocalLibraryStorage.loadCache(profileId)
            ?.let { runCatching { json.decodeFromString<LocalCachePayload>(it).items }.getOrNull() }
            .orEmpty()
            .filter { it.folderId in folders.map(LocalFolder::id) }
            .associateBy { it.key }

        publish(isScanning = false)
    }

    fun addFolder(path: String, type: LocalFolderType, isAnime: Boolean = false, label: String? = null) {
        ensureLoaded()
        val normalizedPath = path.trim().trimEnd('/', '\\')
        if (normalizedPath.isBlank()) return
        if (folders.any { it.path.equals(normalizedPath, ignoreCase = true) && it.type == type && it.isAnime == isAnime }) return

        val folder = LocalFolder(
            id = "folder-${TraktPlatformClock.nowEpochMs()}-${Random.nextInt(0, 1_000_000)}",
            path = normalizedPath,
            type = type,
            isAnime = isAnime,
            label = label?.takeIf { it.isNotBlank() },
            addedAtEpochMs = TraktPlatformClock.nowEpochMs(),
        )
        folders = folders + folder
        persistConfig()
        publish(isScanning = true)
        rescan()
    }

    fun removeFolder(folderId: String) {
        ensureLoaded()
        folders = folders.filterNot { it.id == folderId }
        itemsByKey = itemsByKey.filterValues { it.folderId != folderId }
        assignmentsByKey = assignmentsByKey.filterKeys { itemsByKey.containsKey(it) }
        persistConfig()
        persistCache()
        publish(isScanning = false)
    }

    fun rescan() {
        ensureLoaded()
        if (folders.isEmpty()) {
            itemsByKey = emptyMap()
            persistCache()
            publish(isScanning = false)
            return
        }
        scanJob?.cancel()
        publish(isScanning = true)
        scanJob = scope.launch {
            val scanned = mutableListOf<LocalMediaItem>()
            var firstError: String? = null
            for (folder in folders) {
                val result = FolderScanner.scan(folder)
                if (result.errorMessage != null && firstError == null) firstError = result.errorMessage
                scanned += result.items.map { applyOverride(it) }
            }

            // Preserve ids that were already resolved (from cache) before re-matching, so we don't
            // re-hit TMDB for everything on every scan.
            val merged = scanned.map { item ->
                val cached = itemsByKey[item.key]
                if (cached != null && cached.matchState != LocalMatchState.UNMATCHED && overridesByKey[item.key] == null) {
                    item.copy(
                        imdbId = cached.imdbId,
                        tmdbId = cached.tmdbId,
                        kitsuId = cached.kitsuId,
                        malId = cached.malId,
                        poster = cached.poster ?: item.poster,
                        background = cached.background ?: item.background,
                        posterRefreshToken = cached.posterRefreshToken,
                        matchState = cached.matchState,
                    )
                } else {
                    item
                }
            }

            itemsByKey = merged.associateBy { it.key }
            persistCache()
            publish(isScanning = true, errorMessage = firstError)

            // Auto-match the still-unmatched items with limited concurrency.
            val sem = Semaphore(4)
            val toMatch = itemsByKey.values.filter { !it.isMatched && it.matchState != LocalMatchState.MANUAL }
            val jobs = toMatch.map { item ->
                launch {
                    sem.withPermit {
                        val matched = runCatching { LocalMatcher.autoMatch(item) }.getOrDefault(item)
                        if (matched.isMatched) {
                            updateItem(matched)
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
            persistCache()
            publish(isScanning = false, errorMessage = firstError)
        }
    }

    /** Applies a user chosen match (from the Fix-match dialog) and persists it as an override. */
    fun applyManualMatch(item: LocalMediaItem, resolved: LocalMediaItem) {
        val override = LocalMatchOverride(
            key = item.key,
            imdbId = resolved.imdbId,
            tmdbId = resolved.tmdbId,
            kitsuId = resolved.kitsuId,
            malId = resolved.malId,
            title = resolved.title.takeIf { it != item.title },
            poster = resolved.poster,
            background = resolved.background,
            matchState = LocalMatchState.MANUAL,
        )
        overridesByKey = overridesByKey + (item.key to override)
        persistOverrides()
        updateItem(applyOverride(resolved.copy(matchState = LocalMatchState.MANUAL)))
        persistCache()
    }

    /** Clears any id for an item so it will not scrobble; keeps it visible/playable. */
    fun clearMatch(item: LocalMediaItem) {
        val override = LocalMatchOverride(key = item.key, matchState = LocalMatchState.UNMATCHED)
        overridesByKey = overridesByKey + (item.key to override)
        persistOverrides()
        updateItem(item.copy(imdbId = null, tmdbId = null, kitsuId = null, malId = null, matchState = LocalMatchState.UNMATCHED))
        persistCache()
    }

    /**
     * Drops the item's cached poster and re-pulls it from TMDB for the current id, appending a
     * cache-busting token so the image loader (and Library) refresh even when the URL is otherwise
     * unchanged. Fixes stale posters left by an earlier wrong auto-match.
     */
    fun resetPoster(item: LocalMediaItem) {
        ensureLoaded()
        scope.launch {
            // Re-pull a clean TMDB fallback poster, and bump the refresh token. The token is what
            // actually forces a fresh request in the Library — it's appended as a URL fragment to
            // whichever URL the Library displays (the poster-service/PostersPlus URL when one is
            // configured, otherwise this fallback), so a stale cached poster is discarded.
            val fresh = item.tmdbId
                ?.let { id -> runCatching { TmdbService.fetchPosterUrl(id, item.contentType) }.getOrNull() }
                ?: item.kitsuId?.let { id -> runCatching { KitsuService.fetchPosterUrl(id) }.getOrNull() }
            val token = TraktPlatformClock.nowEpochMs()
            overridesByKey[item.key]?.let { existing ->
                overridesByKey = overridesByKey + (item.key to existing.copy(poster = fresh, posterRefreshToken = token))
                persistOverrides()
            }
            updateItem((itemsByKey[item.key] ?: item).copy(poster = fresh, posterRefreshToken = token))
            persistCache()
        }
    }

    // --- Advanced mode: library grouping + catalogs ---

    fun setMode(newMode: LocalLibraryMode) {
        ensureLoaded()
        if (mode == newMode) return
        mode = newMode
        // Inherit the Basic grouping the first time Advanced is enabled: seed Movies/Shows catalogs
        // and file everything into them, so the user customizes from a populated starting point
        // rather than an empty screen.
        if (newMode == LocalLibraryMode.ADVANCED && catalogs.isEmpty() && itemsByKey.isNotEmpty()) {
            seedCatalogsFromBasic()
        }
        persistConfig()
        publish(isScanning = _uiState.value.isScanning)
    }

    private fun seedCatalogsFromBasic() {
        val stamp = TraktPlatformClock.nowEpochMs()
        val moviesCatalog = LocalCatalog(id = "cat-$stamp-movies", name = "Movies", order = 0, color = 0xFF29B6F6)
        val showsCatalog = LocalCatalog(id = "cat-$stamp-shows", name = "Shows", order = 1, color = 0xFFAB47BC)
        val animeMoviesCatalog = LocalCatalog(id = "cat-$stamp-anime-movies", name = "Anime Movies", order = 2, color = 0xFFEC407A)
        val animeSeriesCatalog = LocalCatalog(id = "cat-$stamp-anime-series", name = "Anime Series", order = 3, color = 0xFFFF7043)
        fun catalogFor(item: LocalMediaItem): LocalCatalog = when {
            item.isAnime && item.type == LocalFolderType.SERIES -> animeSeriesCatalog
            item.isAnime -> animeMoviesCatalog
            item.type == LocalFolderType.SERIES -> showsCatalog
            else -> moviesCatalog
        }
        val seeded = listOf(moviesCatalog, showsCatalog, animeMoviesCatalog, animeSeriesCatalog)
        catalogs = seeded.filter { catalog -> itemsByKey.values.any { catalogFor(it).id == catalog.id } }
        assignmentsByKey = itemsByKey.values.associate { item -> item.key to catalogFor(item).id }
    }

    fun setCatalogColor(catalogId: String, color: Long?) {
        ensureLoaded()
        catalogs = catalogs.map { if (it.id == catalogId) it.copy(color = color) else it }
        persistConfig()
        publish(isScanning = _uiState.value.isScanning)
    }

    /** Creates a catalog (or returns the id of an existing one with the same name). */
    fun addCatalog(name: String): String? {
        ensureLoaded()
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        catalogs.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.id }
        val catalog = LocalCatalog(
            id = "cat-${TraktPlatformClock.nowEpochMs()}-${Random.nextInt(0, 1_000_000)}",
            name = trimmed,
            order = catalogs.size,
        )
        catalogs = catalogs + catalog
        persistConfig()
        publish(isScanning = _uiState.value.isScanning)
        return catalog.id
    }

    fun renameCatalog(catalogId: String, name: String) {
        ensureLoaded()
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        catalogs = catalogs.map { if (it.id == catalogId) it.copy(name = trimmed) else it }
        persistConfig()
        publish(isScanning = _uiState.value.isScanning)
    }

    fun removeCatalog(catalogId: String) {
        ensureLoaded()
        catalogs = catalogs.filterNot { it.id == catalogId }
        assignmentsByKey = assignmentsByKey.filterValues { it != catalogId }
        persistConfig()
        publish(isScanning = _uiState.value.isScanning)
    }

    /** Assigns an item to a catalog, or removes its assignment when [catalogId] is null. */
    fun assignToCatalog(itemKey: String, catalogId: String?) {
        ensureLoaded()
        assignmentsByKey = if (catalogId == null) {
            assignmentsByKey - itemKey
        } else {
            assignmentsByKey + (itemKey to catalogId)
        }
        persistConfig()
        publish(isScanning = _uiState.value.isScanning)
    }

    fun itemByKey(key: String): LocalMediaItem? = itemsByKey[key]

    /**
     * All items that could back [metaId], matched against every id shape an item may carry — its
     * content id, its IMDb id, or `tmdb:<id>`. The addon meta for a `tmdb:`-opened item is often
     * normalized to a `tt…` id, so an exact content-id compare alone would miss it.
     */
    fun itemsForContentId(metaId: String): List<LocalMediaItem> =
        itemsByKey.values.filter { item ->
            item.contentId == metaId ||
                (!item.imdbId.isNullOrBlank() && item.imdbId == metaId) ||
                (item.tmdbId != null && metaId == "tmdb:${item.tmdbId}") ||
                // A franchise's other seasons are separate kitsu/mal entries; the anime-list
                // mapping links them, so the item also surfaces on sibling-season anime pages.
                LocalAnimeEpisodeMatcher.matchesFranchiseMeta(item, metaId)
        }

    /**
     * Local file streams for a content id + video id, used to serve local playback through the
     * normal embedded-stream path (so scrobbling/progress just work).
     *
     * With a concrete [videoId] every branch below requires the id to belong to the item (its
     * own ids, or its anime franchise via the mapping), so ALL items are safe candidates. That
     * frees the lookup from needing the right [contentId]: continue-watching from Home, the
     * player's episode panel and next-episode auto-play all ask by video id at moments when no
     * matching details meta is loaded.
     */
    fun localStreamsFor(contentId: String?, videoId: String?): List<StreamItem> {
        val items = when {
            videoId != null -> itemsByKey.values.toList()
            contentId != null -> itemsForContentId(contentId)
            else -> emptyList()
        }
        if (items.isEmpty()) return emptyList()
        return items.flatMap { item ->
            when {
                // A movie's file is only served for the movie's OWN id. It must never answer an
                // `<id>:season:episode` request: movies used to answer any video id, which let a
                // stale meta — or the movie surfacing on its franchise's series page — play the
                // movie file in place of an episode (or of a different show entirely).
                item.type == LocalFolderType.MOVIES ->
                    if (videoId == null || item.ownsVideoId(videoId)) {
                        item.files.map { it.toStreamItem() }
                    } else {
                        emptyList()
                    }
                // Anime video ids mix coordinate spaces (`kitsu:id:ep` is entry-relative,
                // `tt…:s:e` is franchise season/episode) and so do local file names; the matcher
                // converts between them through the anime-list mapping and rejects ids that
                // belong to neither this item nor its franchise.
                item.isAnime -> {
                    val matched = videoId?.let { LocalAnimeEpisodeMatcher.matchFiles(item, it) }
                    when {
                        matched != null -> matched.map { it.toStreamItem() }
                        (videoId == null || item.ownsVideoId(videoId)) && item.files.size == 1 ->
                            item.files.map { it.toStreamItem() }
                        else -> emptyList()
                    }
                }
                else -> {
                    val coords = videoId?.takeIf { item.ownsVideoId(it) }?.let(::parseSeasonEpisode)
                    when {
                        coords != null -> item.files
                            .filter { it.season == coords.first && it.episode == coords.second }
                            .map { it.toStreamItem() }
                        (videoId == null || item.ownsVideoId(videoId)) && item.files.size == 1 ->
                            item.files.map { it.toStreamItem() }
                        else -> emptyList()
                    }
                }
            }
        }
    }

    /**
     * The video id for one episode [file]. Absolute-numbered anime files keep the native
     * `<contentId>:absoluteEpisode` form so they flow through the app's absolute-episode anime
     * handling; anything with a real season (including season-named anime files) keeps the
     * `<contentId>:season:episode` shape.
     */
    private fun LocalMediaItem.episodeVideoId(file: LocalMediaFile): String = when {
        isAnime && file.season == null -> "$contentId:${file.episode ?: 1}"
        else -> "$contentId:${file.season ?: 1}:${file.episode ?: 1}"
    }

    /** A minimal MetaDetails for an unmatched `local:` id so the details page still opens/plays. */
    fun syntheticMetaFor(localId: String): MetaDetails? {
        if (!localId.isLocalLibraryId()) return null
        val key = localId.removePrefix(LOCAL_ID_PREFIX)
        val item = itemsByKey[key] ?: return null
        val videos = when (item.type) {
            LocalFolderType.MOVIES -> listOf(
                MetaVideo(
                    id = item.contentId,
                    title = item.title,
                    season = null,
                    episode = null,
                    streams = item.files.map { it.toStreamItem() },
                ),
            )
            LocalFolderType.SERIES -> item.files.map { file ->
                MetaVideo(
                    id = item.episodeVideoId(file),
                    title = when {
                        file.season != null && file.episode != null -> "S${file.season} E${file.episode}"
                        // Absolute-numbered anime (no season parsed from the file name).
                        item.isAnime && file.episode != null -> "Episode ${file.episode}"
                        else -> file.fileName
                    },
                    season = file.season,
                    episode = file.episode,
                    streams = listOf(file.toStreamItem()),
                )
            }
        }
        return MetaDetails(
            id = item.contentId,
            type = item.contentType,
            name = item.title,
            poster = item.poster,
            background = item.background,
            releaseInfo = item.displayYear,
            videos = videos,
        )
    }

    private fun applyOverride(item: LocalMediaItem): LocalMediaItem {
        val override = overridesByKey[item.key] ?: return item
        return item.copy(
            imdbId = override.imdbId,
            tmdbId = override.tmdbId,
            kitsuId = override.kitsuId,
            malId = override.malId,
            title = override.title ?: item.title,
            poster = override.poster ?: item.poster,
            background = override.background ?: item.background,
            posterRefreshToken = override.posterRefreshToken ?: item.posterRefreshToken,
            matchState = override.matchState,
        )
    }

    private fun updateItem(item: LocalMediaItem) {
        itemsByKey = itemsByKey + (item.key to item)
        publish(isScanning = _uiState.value.isScanning)
    }

    private fun publish(isScanning: Boolean, errorMessage: String? = _uiState.value.errorMessage) {
        _uiState.value = LocalLibraryUiState(
            folders = folders.sortedBy { it.displayName.lowercase() },
            // catalogId is derived from assignments at publish time, not stored on the item.
            items = itemsByKey.values.map { it.copy(catalogId = assignmentsByKey[it.key]) },
            mode = mode,
            catalogs = catalogs,
            isLoaded = true,
            isScanning = isScanning,
            errorMessage = errorMessage,
        )
    }

    private fun persistConfig() =
        LocalLibraryStorage.saveConfig(
            profileId,
            json.encodeToString(
                LocalConfigPayload(
                    folders = folders,
                    mode = mode,
                    catalogs = catalogs,
                    assignments = assignmentsByKey,
                ),
            ),
        )

    private fun persistOverrides() =
        LocalLibraryStorage.saveOverrides(
            profileId,
            json.encodeToString(LocalOverridesPayload(overridesByKey.values.toList())),
        )

    private fun persistCache() =
        LocalLibraryStorage.saveCache(
            profileId,
            json.encodeToString(LocalCachePayload(itemsByKey.values.toList())),
        )

    private fun LocalMediaFile.toStreamItem(): StreamItem =
        StreamItem(
            name = "Local File",
            title = fileName,
            url = path,
            addonName = "Local Library",
            addonId = "locallibrary",
            streamType = "local",
        )

    /** Parses trailing `:season:episode` (e.g. `tt123:2:5`) into (season, episode). */
    private fun parseSeasonEpisode(videoId: String): Pair<Int, Int>? {
        val parts = videoId.split(':')
        if (parts.size < 2) return null
        val episode = parts[parts.lastIndex].toIntOrNull() ?: return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        return season to episode
    }
}
