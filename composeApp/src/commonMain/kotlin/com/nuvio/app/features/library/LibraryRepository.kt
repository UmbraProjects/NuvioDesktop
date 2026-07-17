package com.nuvio.app.features.library

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.locallibrary.LocalFolderType
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.locallibrary.LocalMediaItem
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklLibraryRepository
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.tmdb.HeroImageSource
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktLibraryRepository
import com.nuvio.app.features.trakt.TraktListTab
import com.nuvio.app.features.trakt.TraktListType
import com.nuvio.app.features.trakt.TraktMembershipChanges
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.effectiveLibrarySourceMode as resolveEffectiveLibrarySourceMode
import com.nuvio.app.features.trakt.shouldUseTraktLibrary
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_local_tab_title
import nuvio.composeapp.generated.resources.library_other
import nuvio.composeapp.generated.resources.trakt_lists_update_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

@Serializable
private data class StoredLibraryPayload(
    val items: List<LibraryItem> = emptyList(),
)

@Serializable
private data class LibrarySyncItem(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val name: String = "",
    val poster: String? = null,
    @SerialName("poster_shape") val posterShape: String = "POSTER",
    val background: String? = null,
    val description: String? = null,
    @SerialName("release_info") val releaseInfo: String? = null,
    @SerialName("imdb_rating") val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    @SerialName("addon_base_url") val addonBaseUrl: String? = null,
    @SerialName("added_at") val addedAt: Long = 0,
)

object LibraryRepository {
    private const val PULL_PAGE_SIZE = 500

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prefetchJob: Job? = null
    private val log = Logger.withTag("LibraryRepository")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var currentProfileId: Int = 1
    private var itemsById: MutableMap<String, LibraryItem> = mutableMapOf()
    private var isPullingNuvioSyncFromServer = false
    private var hasCompletedInitialNuvioSyncPull = false
    private var pushJob: Job? = null

    init {
        syncScope.launch {
            TraktAuthRepository.isAuthenticated.collectLatest { authenticated ->
                if (authenticated) {
                    TraktLibraryRepository.preloadListTabsAsync()
                    if (shouldUseTraktLibrary(authenticated, selectedLibrarySourceMode())) {
                        runCatching { TraktLibraryRepository.refreshNow() }
                            .onFailure { log.e(it) { "Failed to refresh Trakt library after auth change" } }
                    }
                }
                publish()
            }
        }
        syncScope.launch {
            TraktSettingsRepository.uiState
                .map { it.librarySourceMode }
                .distinctUntilChanged()
                .collectLatest { source ->
                    if (shouldUseTraktLibrary(TraktAuthRepository.isAuthenticated.value, source)) {
                        TraktLibraryRepository.preloadListTabsAsync()
                        publish()
                        refreshTraktLibraryAsync()
                    } else {
                        publish()
                    }
                }
        }
        syncScope.launch {
            TraktLibraryRepository.uiState.collectLatest {
                if (TraktAuthRepository.isAuthenticated.value) {
                    publish()
                }
            }
        }

        syncScope.launch {
            SimklAuthRepository.isAuthenticated.collectLatest { authenticated ->
                if (authenticated && isSimklLibrarySourceActive()) {
                    SimklLibraryRepository.refreshAsync()
                }
                publish()
            }
        }

        syncScope.launch {
            SimklLibraryRepository.uiState.collectLatest {
                if (isSimklLibrarySourceActive()) publish()
            }
        }

        syncScope.launch {
            SimklSettingsRepository.uiState.collectLatest {
                if (isSimklLibrarySourceActive()) SimklLibraryRepository.refreshAsync()
                publish()
            }
        }
        syncScope.launch {
            CloudLibraryRepository.uiState.collectLatest {
                publish()
            }
        }
        syncScope.launch {
            LocalLibraryRepository.uiState.collectLatest {
                publish()
            }
        }
    }

    fun ensureLoaded() {
        TraktAuthRepository.ensureLoaded()
        TraktSettingsRepository.ensureLoaded()
        TraktLibraryRepository.ensureLoaded()
        SimklSettingsRepository.ensureLoaded()
        SimklAuthRepository.ensureLoaded()
        SimklLibraryRepository.ensureLoaded()
        CloudLibraryRepository.ensureLoaded()
        LocalLibraryRepository.ensureLoaded()
        if (hasLoaded) return
        loadFromDisk(ProfileRepository.activeProfileId)
        if (isSimklLibrarySourceActive()) {
            SimklLibraryRepository.refreshAsync()
        } else if (TraktAuthRepository.isAuthenticated.value) {
            TraktLibraryRepository.preloadListTabsAsync()
            if (isTraktLibrarySourceActive()) {
                refreshTraktLibraryAsync()
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        if (profileId == currentProfileId && hasLoaded) return
        pushJob?.cancel()
        isPullingNuvioSyncFromServer = false
        hasCompletedInitialNuvioSyncPull = false
        TraktSettingsRepository.onProfileChanged()
        LocalLibraryRepository.onProfileChanged(profileId)
        loadFromDisk(profileId)
        TraktAuthRepository.onProfileChanged()
        TraktLibraryRepository.onProfileChanged()
        SimklLibraryRepository.clearLocalState()
        if (isSimklLibrarySourceActive()) {
            SimklLibraryRepository.refreshAsync()
        } else if (TraktAuthRepository.isAuthenticated.value) {
            TraktLibraryRepository.preloadListTabsAsync()
            if (isTraktLibrarySourceActive()) {
                refreshTraktLibraryAsync()
            }
        }
    }

    fun clearLocalState() {
        hasLoaded = false
        currentProfileId = 1
        itemsById.clear()
        pushJob?.cancel()
        isPullingNuvioSyncFromServer = false
        hasCompletedInitialNuvioSyncPull = false
        TraktAuthRepository.clearLocalState()
        TraktLibraryRepository.clearLocalState()
        _uiState.value = LibraryUiState()
    }

    private fun loadFromDisk(profileId: Int) {
        currentProfileId = profileId
        hasLoaded = true
        itemsById.clear()

        val payload = LibraryStorage.loadPayload(profileId).orEmpty().trim()
        if (payload.isNotEmpty()) {
            val items = runCatching {
                json.decodeFromString<StoredLibraryPayload>(payload).items
            }.getOrDefault(emptyList())
            itemsById = items.associateBy { libraryItemKey(it.id, it.type) }.toMutableMap()
        }

        publish()
    }

    suspend fun pullFromServer(profileId: Int) {
        currentProfileId = profileId

        if (isSimklLibrarySourceActive()) {
            runCatching { SimklLibraryRepository.refreshNow() }
                .onFailure { e -> log.e(e) { "Failed to pull SIMKL library" } }
            hasCompletedInitialNuvioSyncPull = true
            publish()
            return
        }

        if (isTraktLibrarySourceActive()) {
            runCatching { TraktLibraryRepository.refreshNow() }
                .onFailure { e -> log.e(e) { "Failed to pull Trakt library" } }
            hasCompletedInitialNuvioSyncPull = true
            publish()
            return
        }

        isPullingNuvioSyncFromServer = true
        runCatching {
            val serverItems = pullAllLibrarySyncItems(profileId)
            if (serverItems.isEmpty() && itemsById.isNotEmpty()) {
                log.w { "Remote library is empty while local has ${itemsById.size} entries; preserving local library" }
            } else {
                itemsById = serverItems
                    .map { it.toLibraryItem() }
                    .associateBy { libraryItemKey(it.id, it.type) }
                    .toMutableMap()
                persist()
            }
            hasLoaded = true
            publish()
        }.onFailure { e ->
            log.e(e) { "Failed to pull library from server" }
        }.also {
            hasCompletedInitialNuvioSyncPull = true
            isPullingNuvioSyncFromServer = false
        }
    }

    fun toggleSaved(item: LibraryItem) {
        ensureLoaded()

        if (isTraktLibrarySourceActive()) {
            syncScope.launch {
                runCatching { TraktLibraryRepository.toggleWatchlist(item) }
                    .onFailure { e ->
                        log.e(e) { "Failed to toggle Trakt watchlist" }
                        NuvioToastController.show(
                            e.message?.takeIf { it.isNotBlank() }
                                ?: getString(Res.string.trakt_lists_update_failed),
                        )
                    }
                publish()
            }
            return
        }

        if (itemsById.containsKey(libraryItemKey(item.id, item.type))) {
            remove(item.id, item.type)
        } else {
            save(item)
        }
    }

    fun save(item: LibraryItem) {
        ensureLoaded()
        itemsById[libraryItemKey(item.id, item.type)] = item.copy(savedAtEpochMs = LibraryClock.nowEpochMs())
        publish()
        persist()
        pushToServer()
    }

    fun remove(id: String) {
        ensureLoaded()
        val before = itemsById.size
        itemsById.entries.removeAll { (_, item) -> item.id == id }
        if (itemsById.size != before) {
            publish()
            persist()
            pushToServer()
        }
    }

    private fun remove(id: String, type: String) {
        ensureLoaded()
        if (itemsById.remove(libraryItemKey(id, type)) != null) {
            publish()
            persist()
            pushToServer()
        }
    }

    fun isSaved(id: String, type: String? = null): Boolean {
        ensureLoaded()

        if (isTraktLibrarySourceActive()) {
            if (type != null) {
                return TraktLibraryRepository.isInAnyList(id, type)
            }
            val entry = TraktLibraryRepository.uiState.value.allItems.firstOrNull { it.id == id }
            if (entry != null) {
                return TraktLibraryRepository.isInAnyList(entry.id, entry.type)
            }
            return false
        }

        return if (type != null) {
            itemsById.containsKey(libraryItemKey(id, type))
        } else {
            itemsById.values.any { it.id == id }
        }
    }

    fun savedItem(id: String): LibraryItem? {
        ensureLoaded()

        if (isTraktLibrarySourceActive()) {
            return TraktLibraryRepository.uiState.value.allItems.firstOrNull { it.id == id }
        }

        return itemsById.values.firstOrNull { it.id == id }
    }

    fun libraryListTabs(): List<TraktListTab> {
        val traktTabs = if (TraktAuthRepository.isAuthenticated.value) {
            TraktLibraryRepository.currentListTabs()
        } else {
            emptyList()
        }
        return libraryTabsWithLocal(traktTabs)
    }

    fun traktListTabs(): List<TraktListTab> = libraryListTabs()

    suspend fun getMembershipSnapshot(item: LibraryItem): Map<String, Boolean> {
        ensureLoaded()
        val inLocal = itemsById.containsKey(libraryItemKey(item.id, item.type))
        if (TraktAuthRepository.isAuthenticated.value) {
            val traktMembership = TraktLibraryRepository.getMembershipSnapshot(item).listMembership
            return libraryMembershipWithLocal(
                inLocal = inLocal,
                traktMembership = traktMembership,
            )
        }
        return libraryMembershipWithLocal(inLocal = inLocal)
    }

    suspend fun applyMembershipChanges(item: LibraryItem, desiredMembership: Map<String, Boolean>) {
        ensureLoaded()
        val localDesired = desiredMembership[LOCAL_LIBRARY_LIST_KEY] == true
        val currentlyInLocal = itemsById.containsKey(libraryItemKey(item.id, item.type))
        if (localDesired != currentlyInLocal) {
            if (localDesired) {
                save(item)
            } else {
                remove(item.id, item.type)
            }
        }

        if (TraktAuthRepository.isAuthenticated.value) {
            val traktMembership = desiredMembership.filterKeys { it != LOCAL_LIBRARY_LIST_KEY }
            if (traktMembership.isNotEmpty()) {
                TraktLibraryRepository.applyMembershipChanges(
                    item = item,
                    changes = TraktMembershipChanges(desiredMembership = traktMembership),
                )
            }
            publish()
        } else {
            publish()
        }
    }

    suspend fun removeFromList(item: LibraryItem, listKey: String) {
        val desiredMembership = libraryMembershipWithRemovedList(
            currentMembership = getMembershipSnapshot(item),
            listKey = listKey,
        )
        applyMembershipChanges(item, desiredMembership)
    }

    private fun pushToServer() {
        val authState = AuthRepository.state.value
        if (authState !is AuthState.Authenticated || authState.isAnonymous) return
        if (isPullingNuvioSyncFromServer || !hasCompletedInitialNuvioSyncPull) return

        pushJob?.cancel()
        pushJob = syncScope.launch {
            delay(500)
            runCatching {
                val profileId = ProfileRepository.activeProfileId
                val syncItems = itemsById.values.map { it.toSyncItem() }
                if (syncItems.isEmpty()) return@runCatching
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_items", json.encodeToJsonElement(syncItems))
                }
                SupabaseProvider.client.postgrest.rpc("sync_push_library", params)
            }.onFailure { e ->
                log.e(e) { "Failed to push library to server" }
            }
        }
    }

    private suspend fun pullAllLibrarySyncItems(profileId: Int): List<LibrarySyncItem> {
        val allItems = mutableListOf<LibrarySyncItem>()
        var offset = 0

        while (true) {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_limit", PULL_PAGE_SIZE)
                put("p_offset", offset)
            }
            val result = SupabaseProvider.client.postgrest.rpc("sync_pull_library", params)
            val page = result.decodeList<LibrarySyncItem>()
            allItems.addAll(page)

            if (page.size < PULL_PAGE_SIZE) break
            offset += PULL_PAGE_SIZE
        }

        return allItems
    }

    private fun publish() {
        if (isSimklLibrarySourceActive()) {
            val s = SimklLibraryRepository.uiState.value
            val sections = buildList {
                if (s.shows.isNotEmpty()) add(LibrarySection("simkl_shows", "My Shows", s.shows))
                if (s.movies.isNotEmpty()) add(LibrarySection("simkl_movies", "My Movies", s.movies))
                if (s.anime.isNotEmpty()) add(LibrarySection("simkl_anime", "My Anime", s.anime))
                addAll(cloudLibrarySections())
                addAll(localLibrarySections())
            }
            _uiState.value = LibraryUiState(
                sourceMode = LibrarySourceMode.SIMKL,
                items = s.allItems,
                sections = sections,
                isLoaded = s.hasLoaded,
                isLoading = s.isLoading,
                errorMessage = s.errorMessage,
            )

            startPrefetch(sections)
            return
        }

        if (isTraktLibrarySourceActive()) {
            val traktState = TraktLibraryRepository.uiState.value
            val sections = traktState.listTabs.mapNotNull { tab ->
                val listItems = traktState.entriesByList[tab.key].orEmpty()
                if (listItems.isEmpty()) {
                    null
                } else {
                    LibrarySection(
                        type = tab.key,
                        displayTitle = tab.title,
                        items = listItems,
                    )
                }
            }

            val sectionsWithCloud = sections + cloudLibrarySections() + localLibrarySections()

            _uiState.value = LibraryUiState(
                sourceMode = LibrarySourceMode.TRAKT,
                items = traktState.allItems,
                sections = sectionsWithCloud,
                isLoaded = traktState.hasLoaded,
                isLoading = traktState.isLoading,
                errorMessage = traktState.errorMessage,
            )

            startPrefetch(sectionsWithCloud)
            return
        }

        val items = itemsById.values
            .sortedByDescending { it.savedAtEpochMs }
        val sections = items
            .groupBy { it.type }
            .map { (type, typeItems) ->
                LibrarySection(
                    type = type,
                    displayTitle = type.toLibraryDisplayTitle(),
                    items = typeItems.sortedByDescending { it.savedAtEpochMs },
                )
            }
            .sortedBy { it.displayTitle }

        val sectionsWithCloud = sections + cloudLibrarySections() + localLibrarySections()

        _uiState.value = LibraryUiState(
            sourceMode = LibrarySourceMode.LOCAL,
            items = items,
            sections = sectionsWithCloud,
            isLoaded = true,
            isLoading = false,
            errorMessage = null,
        )

        startPrefetch(sectionsWithCloud)
    }

    private fun localLibrarySections(): List<LibrarySection> {
        val state = LocalLibraryRepository.uiState.value
        if (!state.isLoaded || state.items.isEmpty()) return emptyList()

        if (state.mode == com.nuvio.app.features.locallibrary.LocalLibraryMode.ADVANCED) {
            return buildList {
                // One section per catalog, in the user's order.
                state.sortedCatalogs.forEach { catalog ->
                    val items = state.itemsInCatalog(catalog.id)
                    if (items.isNotEmpty()) {
                        add(LibrarySection("locallibrary_catalog_${catalog.id}", catalog.name, items.map(LocalMediaItem::toLibraryItem)))
                    }
                }
                // Anything the user hasn't filed yet.
                state.itemsInCatalog(null).takeIf { it.isNotEmpty() }?.let {
                    add(LibrarySection("locallibrary_unsorted", "Local (Unsorted)", it.map(LocalMediaItem::toLibraryItem)))
                }
            }
        }

        return buildList {
            state.movies.takeIf { it.isNotEmpty() }?.let {
                add(LibrarySection("locallibrary_movie", "Local Movies", it.map(LocalMediaItem::toLibraryItem)))
            }
            state.series.takeIf { it.isNotEmpty() }?.let {
                add(LibrarySection("locallibrary_series", "Local Shows", it.map(LocalMediaItem::toLibraryItem)))
            }
            state.animeMovies.takeIf { it.isNotEmpty() }?.let {
                add(LibrarySection("locallibrary_anime_movie", "Local Anime Movies", it.map(LocalMediaItem::toLibraryItem)))
            }
            state.animeSeries.takeIf { it.isNotEmpty() }?.let {
                add(LibrarySection("locallibrary_anime_series", "Local Anime Series", it.map(LocalMediaItem::toLibraryItem)))
            }
        }
    }

    private fun cloudLibrarySections(): List<LibrarySection> {
        val cloudState = CloudLibraryRepository.uiState.value
        if (!cloudState.isEnabled || !cloudState.isLoaded) return emptyList()
        return cloudState.providers.mapNotNull { provider ->
            val items = provider.items
                .filter { it.playableFiles.isNotEmpty() }
                .map { it.toLibraryItem() }
            if (items.isEmpty()) {
                null
            } else {
                LibrarySection(
                    type = "${CloudLibraryContentType}:${provider.providerId}",
                    displayTitle = provider.providerName,
                    items = items,
                )
            }
        }
    }

    private fun startPrefetch(sections: List<LibrarySection>) {
        prefetchJob?.cancel()
        prefetchJob = syncScope.launch {
            val itemsToPrefetch = sections.take(2).flatMap { it.items.take(8) }
            val sem = Semaphore(4)
            TmdbSettingsRepository.ensureLoaded()
            val preferTmdbImages = TmdbSettingsRepository.uiState.value.heroImageSource != HeroImageSource.Addon
            itemsToPrefetch.forEach { item ->
                launch {
                    sem.withPermit {
                        runCatching {
                            MetaDetailsRepository.fetchLightweightMeta(item.type, item.id, preferTmdbImages = preferTmdbImages)
                        }
                    }
                }
            }
        }
    }

    private fun persist() {
        LibraryStorage.savePayload(
            currentProfileId,
            json.encodeToString(
                StoredLibraryPayload(
                    items = itemsById.values.sortedByDescending { it.savedAtEpochMs },
                ),
            ),
        )
    }

    private fun refreshTraktLibraryAsync() {
        syncScope.launch {
            runCatching { TraktLibraryRepository.refreshNow() }
                .onFailure { e -> log.e(e) { "Failed to refresh Trakt library" } }
            publish()
        }
    }

    private fun CloudLibraryItem.toLibraryItem(): LibraryItem =
        LibraryItem(
            id = stableKey,
            type = CloudLibraryContentType,
            name = name,
            poster = null,
            banner = null,
            description = cloudLibraryDescription(),
            releaseInfo = providerName,
            posterShape = PosterShape.Poster,
            savedAtEpochMs = LibraryClock.nowEpochMs(),
        )

    private fun CloudLibraryItem.cloudLibraryDescription(): String =
        listOfNotNull(
            providerName,
            type.name,
            status?.takeIf { it.isNotBlank() },
            playableFiles.firstOrNull()?.name?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")

    private fun selectedLibrarySourceMode(): LibrarySourceMode {
        TraktSettingsRepository.ensureLoaded()
        return TraktSettingsRepository.uiState.value.librarySourceMode
    }

    private fun effectiveLibrarySourceMode(): LibrarySourceMode =
        resolveEffectiveLibrarySourceMode(
            isAuthenticated = TraktAuthRepository.isAuthenticated.value,
            source = selectedLibrarySourceMode(),
        )

    private fun isTraktLibrarySourceActive(): Boolean =
        !isSimklLibrarySourceActive() && effectiveLibrarySourceMode() == LibrarySourceMode.TRAKT

    private fun isSimklLibrarySourceActive(): Boolean =
        SimklAuthRepository.isAuthenticated.value && SimklSettingsRepository.isSimklLibrarySource()
}

internal const val LOCAL_LIBRARY_LIST_KEY = "local"
private const val DEFAULT_LOCAL_LIBRARY_TAB_TITLE = "Nuvio Library"
private const val DEFAULT_LIBRARY_OTHER_TITLE = "Other"

internal fun localLibraryListTab(): TraktListTab =
    TraktListTab(
        key = LOCAL_LIBRARY_LIST_KEY,
        title = localizedStringOrDefault(
            resource = Res.string.library_local_tab_title,
            fallback = DEFAULT_LOCAL_LIBRARY_TAB_TITLE,
        ),
        type = TraktListType.WATCHLIST,
    )

internal fun libraryTabsWithLocal(traktTabs: List<TraktListTab>): List<TraktListTab> =
    listOf(localLibraryListTab()) + traktTabs

internal fun libraryMembershipWithLocal(
    inLocal: Boolean,
    traktMembership: Map<String, Boolean> = emptyMap(),
): Map<String, Boolean> =
    linkedMapOf<String, Boolean>(LOCAL_LIBRARY_LIST_KEY to inLocal).apply {
        putAll(traktMembership)
    }

internal fun libraryMembershipWithRemovedList(
    currentMembership: Map<String, Boolean>,
    listKey: String,
): Map<String, Boolean> =
    currentMembership.toMutableMap().apply {
        this[listKey] = false
    }

private fun LibrarySyncItem.toLibraryItem(): LibraryItem = LibraryItem(
    id = contentId,
    type = contentType,
    name = name,
    poster = poster,
    banner = background,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating?.toString(),
    genres = genres,
    posterShape = posterShape.toPosterShape(),
    addonBaseUrl = addonBaseUrl,
    savedAtEpochMs = addedAt,
)

private fun LibraryItem.toSyncItem(): LibrarySyncItem = LibrarySyncItem(
    contentId = id,
    contentType = type,
    name = name,
    poster = poster,
    posterShape = posterShape.toSyncName(),
    background = banner,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating?.toFloatOrNull(),
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    addedAt = savedAtEpochMs,
)

private fun libraryItemKey(id: String, type: String): String =
    "${type.trim().lowercase()}:${id.trim()}"

private fun String.toPosterShape(): PosterShape =
    when (trim().uppercase()) {
        "LANDSCAPE" -> PosterShape.Landscape
        "SQUARE" -> PosterShape.Square
        else -> PosterShape.Poster
    }

private fun PosterShape.toSyncName(): String =
    when (this) {
        PosterShape.Poster -> "POSTER"
        PosterShape.Square -> "SQUARE"
        PosterShape.Landscape -> "LANDSCAPE"
    }

internal fun String.toLibraryDisplayTitle(): String {
    val normalized = trim()
    if (normalized.isBlank()) return localizedLibraryOtherTitle()

    return normalized
        .split('-', '_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.lowercase().replaceFirstChar { char -> char.uppercase() }
        }
        .ifBlank { localizedLibraryOtherTitle() }
}

private fun localizedLibraryOtherTitle(): String =
    localizedStringOrDefault(
        resource = Res.string.library_other,
        fallback = DEFAULT_LIBRARY_OTHER_TITLE,
    )

private fun localizedStringOrDefault(resource: StringResource, fallback: String): String =
    runCatching { runBlocking { getString(resource) } }
        .getOrDefault(fallback)
