package com.nuvio.app.features.collection

import com.nuvio.app.features.posterservice.withCachedCustomPosters
import com.nuvio.app.features.posterservice.withCustomPosterOverlay
import com.nuvio.app.features.posterservice.CustomPosterScreen
import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.applyCatalogPaginationProbe
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.catalog.mergeCatalogItems
import com.nuvio.app.features.catalog.nextCatalogPaginationState
import com.nuvio.app.features.catalog.supportsPagination
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.RowShuffleController
import com.nuvio.app.features.home.ensureUniqueKeys
import com.nuvio.app.features.home.WATCHED_FILTER_RESOLVE_BUDGET_MS
import com.nuvio.app.features.home.WatchedContentFilter
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.home.filterUnwatchedItems
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.nuvio.app.features.tmdb.TmdbBackgroundLane
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_folder_addon_not_found
import nuvio.composeapp.generated.resources.collections_folder_trakt_movie_list
import nuvio.composeapp.generated.resources.collections_folder_trakt_series_list
import nuvio.composeapp.generated.resources.collections_tab_all
import org.jetbrains.compose.resources.getString

data class FolderTab(
    val label: String,
    val typeLabel: String = "",
    val source: CollectionSource? = null,
    val sourceKey: String? = null,
    val manifestUrl: String? = null,
    val type: String = "",
    val catalogId: String = "",
    /** The matched catalog's own id when it differs from [catalogId]; see fetchCollectionCatalogPage. */
    val fallbackCatalogId: String? = null,
    val genre: String? = null,
    val supportsPagination: Boolean = false,
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val nextSkip: Int? = null,
    val consecutiveDuplicatePages: Int = 0,
    val error: String? = null,
    val isAllTab: Boolean = false,
) {
    val canLoadMore: Boolean
        get() = supportsPagination && nextSkip != null
}

data class FolderDetailUiState(
    val folder: CollectionFolder? = null,
    val collectionTitle: String = "",
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    val tabs: List<FolderTab> = emptyList(),
    val selectedTabIndex: Int = 0,
    val isLoading: Boolean = true,
    val showAllTab: Boolean = true,
    /** Set [FOLDER_ROW_ORDER_HOLD_MS] after opening; see [FolderDetailRepository.getCatalogSectionsForRows]. */
    val rowOrderHoldExpired: Boolean = false,
) {
    val selectedTab: FolderTab?
        get() = tabs.getOrNull(selectedTabIndex)

    /**
     * Sources that finished loading with an error and nothing to show. The row layouts drop empty
     * rows, so without a notice these simply vanished (a missing TMDB key emptied whole folders
     * with no hint why).
     */
    val failedSources: List<FolderTab>
        get() = tabs.filter { !it.isAllTab && !it.isLoading && it.items.isEmpty() && it.error != null }

    val selectedTabCanLoadMore: Boolean
        get() {
            val currentTab = selectedTab ?: return false
            return if (currentTab.isAllTab) {
                tabs.any { !it.isAllTab && it.canLoadMore }
            } else {
                currentTab.canLoadMore
            }
        }

    val selectedTabIsLoadingMore: Boolean
        get() {
            val currentTab = selectedTab ?: return false
            return if (currentTab.isAllTab) {
                tabs.any { !it.isAllTab && it.isLoadingMore }
            } else {
                currentTab.isLoadingMore
            }
        }
}

object FolderDetailRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("FolderDetailRepository")

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState.asStateFlow()
        .withCustomPosterOverlay(CustomPosterScreen.Collections) { settings, keys ->
            copy(tabs = tabs.map { tab ->
                val items = tab.items.withCachedCustomPosters(settings, keys)
                if (items === tab.items) tab else tab.copy(items = items)
            })
        }

    private val loadJobs = mutableMapOf<Int, Job>()
    private var rowOrderHoldJob: Job? = null
    private var addonRetryJob: Job? = null
    private var openedAt: TimeMark? = null
    private var activeCollectionId: String? = null
    private var activeFolderId: String? = null

    fun initialize(collectionId: String, folderId: String) {
        val current = _uiState.value
        if (
            activeCollectionId == collectionId &&
            activeFolderId == folderId &&
            current.folder?.id == folderId &&
            current.tabs.isNotEmpty()
        ) {
            return
        }

        clear()
        activeCollectionId = collectionId
        activeFolderId = folderId

        val collection = CollectionRepository.getCollection(collectionId)
        if (collection == null) {
            _uiState.value = FolderDetailUiState(isLoading = false)
            return
        }

        val folder = collection.folders.find { it.id == folderId }
        if (folder == null) {
            _uiState.value = FolderDetailUiState(isLoading = false)
            return
        }

        val sources = folder.resolvedSources
        val showAll = collection.showAllTab && sources.size > 1
        val addons = AddonRepository.uiState.value.addons

        val tabs = buildList {
            if (showAll) {
                add(
                    FolderTab(
                        label = runBlocking { getString(Res.string.collections_tab_all) },
                        isAllTab = true,
                        isLoading = true,
                    ),
                )
            }
            sources.forEachIndexed { sourceIndex, source ->
                if (source.isTmdb) {
                    val mediaType = TmdbCollectionMediaType.fromString(source.mediaType)
                    val type = if (mediaType == TmdbCollectionMediaType.TV) "series" else "movie"
                    add(
                        FolderTab(
                            label = source.title?.takeIf { it.isNotBlank() } ?: "TMDB",
                            typeLabel = "TMDB",
                            source = source,
                            sourceKey = source.catalogRouteKey(),
                            type = type,
                            catalogId = tmdbCatalogId(source),
                            supportsPagination = source.tmdbSourceType !in setOf(
                                TmdbCollectionSourceType.COLLECTION.name,
                                TmdbCollectionSourceType.PERSON.name,
                                TmdbCollectionSourceType.DIRECTOR.name,
                            ),
                            isLoading = true,
                        ),
                    )
                } else if (source.isTrakt) {
                    val mediaType = TmdbCollectionMediaType.fromString(source.mediaType)
                    val type = if (mediaType == TmdbCollectionMediaType.TV) "series" else "movie"
                    val typeLabel = runBlocking {
                        getString(
                            if (mediaType == TmdbCollectionMediaType.TV) {
                                Res.string.collections_folder_trakt_series_list
                            } else {
                                Res.string.collections_folder_trakt_movie_list
                            },
                        )
                    }
                    add(
                        FolderTab(
                            label = source.title?.takeIf { it.isNotBlank() } ?: "Trakt",
                            typeLabel = typeLabel,
                            source = source,
                            sourceKey = source.catalogRouteKey(),
                            type = type,
                            catalogId = traktCatalogId(source),
                            supportsPagination = true,
                            isLoading = true,
                        ),
                    )
                } else {
                    val catalogSource = source.addonCatalogSource() ?: return@forEachIndexed
                    val resolvedCatalog = addons.findCollectionCatalog(catalogSource)
                    val addon = resolvedCatalog?.addon
                    val catalog = resolvedCatalog?.catalog
                    val typeLabel = localizedMediaTypeLabel(catalogSource.type)
                    add(
                        FolderTab(
                            label = addonTabLabel(catalogSource, catalog?.name),
                            typeLabel = typeLabel,
                            source = source,
                            sourceKey = source.catalogRouteKey(),
                            manifestUrl = addon?.manifestUrl,
                            type = catalogSource.type,
                            catalogId = catalogSource.catalogId,
                            fallbackCatalogId = resolvedCatalog?.fallbackCatalogId(catalogSource),
                            genre = catalogSource.genre,
                            supportsPagination = catalog?.supportsPagination() == true,
                            isLoading = true,
                        ),
                    )
                }
            }
        }

        _uiState.value = FolderDetailUiState(
            folder = folder,
            collectionTitle = collection.title,
            viewMode = collection.folderViewMode,
            tabs = tabs,
            selectedTabIndex = 0,
            isLoading = true,
            showAllTab = showAll,
        )
        openedAt = TimeSource.Monotonic.markNow()
        rowOrderHoldJob = scope.launch {
            delay(FOLDER_ROW_ORDER_HOLD_MS)
            // A state change so the rows are rebuilt even if no source finishes after this point.
            if (activeFolderId == folderId) _uiState.update { it.copy(rowOrderHoldExpired = true) }
        }

        // Load catalog data for each source
        val awaitingAddon = mutableListOf<Int>()
        sources.forEach { source ->
            // Looked up rather than derived from the source's position: the tab builder above skips
            // sources it cannot address, and a derived index then pointed every later source at its
            // neighbour's tab. That left a tab stuck loading, which now also holds back every row
            // after it (see getCatalogSectionsForRows).
            val tabIndex = tabs.indexOfFirst { it.source === source }
            if (tabIndex < 0) return@forEach
            val catalogSource = source.addonCatalogSource()
            val resolvedCatalog = catalogSource?.let { addons.findCollectionCatalog(it) }
            if (!source.isTmdb && !source.isTrakt && resolvedCatalog == null) {
                awaitingAddon += tabIndex
                updateTab(tabIndex) {
                    it.copy(
                        isLoading = false,
                        error = runBlocking {
                            getString(Res.string.collections_folder_addon_not_found, catalogSource?.addonId.orEmpty())
                        },
                    )
                }
                return@forEach
            }

            loadTabPage(tabIndex, reset = true)
        }

        if (awaitingAddon.isNotEmpty() && addons.any(ManagedAddon::isStillLoading)) {
            retryWhenAddonsLoad(folderId, awaitingAddon)
        }

        // If no sources, mark as done
        if (sources.isEmpty()) {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * A folder opened straight after launch can beat its addons' manifests, and its sources then
     * read "addon not found" until the folder was reopened. Once every addon has finished loading
     * (or [ADDON_LOAD_WAIT_MS] has passed), the sources that are now resolvable load in place.
     */
    private fun retryWhenAddonsLoad(folderId: String, tabIndices: List<Int>) {
        addonRetryJob?.cancel()
        addonRetryJob = scope.launch {
            val loaded = withTimeoutOrNull(ADDON_LOAD_WAIT_MS) {
                AddonRepository.uiState.first { state -> state.addons.none(ManagedAddon::isStillLoading) }
            } ?: return@launch
            if (activeFolderId != folderId) return@launch
            tabIndices.forEach { index ->
                val tab = _uiState.value.tabs.getOrNull(index) ?: return@forEach
                if (tab.manifestUrl != null) return@forEach
                val catalogSource = tab.source?.addonCatalogSource() ?: return@forEach
                val resolved = loaded.addons.findCollectionCatalog(catalogSource) ?: return@forEach
                log.i { "Addon for folder source ${index + 1} loaded after the folder opened; loading it now" }
                updateTab(index) {
                    it.copy(
                        label = addonTabLabel(catalogSource, resolved.catalog.name),
                        manifestUrl = resolved.addon.manifestUrl,
                        fallbackCatalogId = resolved.fallbackCatalogId(catalogSource),
                        supportsPagination = resolved.catalog.supportsPagination(),
                        error = null,
                    )
                }
                loadTabPage(index, reset = true)
            }
        }
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTabIndex = index)
    }

    fun clear() {
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        rowOrderHoldJob?.cancel()
        rowOrderHoldJob = null
        addonRetryJob?.cancel()
        addonRetryJob = null
        activeCollectionId = null
        activeFolderId = null
        FolderRowShuffleState.clearAll()
        _uiState.value = FolderDetailUiState()
    }

    fun loadMoreSelectedTab() {
        val current = _uiState.value
        val selectedTab = current.selectedTab ?: return
        if (selectedTab.isAllTab) {
            current.tabs.forEachIndexed { index, tab ->
                if (!tab.isAllTab && tab.canLoadMore && !tab.isLoading && !tab.isLoadingMore) {
                    loadTabPage(index, reset = false)
                }
            }
            return
        }

        if (selectedTab.canLoadMore && !selectedTab.isLoading && !selectedTab.isLoadingMore) {
            loadTabPage(current.selectedTabIndex, reset = false)
        }
    }

    /**
     * Appends the next page to the source behind [section]. Returns the job doing the fetch, the
     * job already in flight for that source, or null when the source cannot page further — so
     * [deepenRowForShuffle] can wait on it while scroll-driven callers ignore it.
     */
    fun loadMoreCatalogRow(section: HomeCatalogSection): Job? {
        val target = section.target
        val current = _uiState.value
        val tabIndex = current.tabs.indexOfFirst { tab ->
            !tab.isAllTab &&
                when (target) {
                    is CatalogTarget.CollectionSource -> {
                        tab.sourceKey == target.sourceKey &&
                            tab.type == target.contentType
                    }

                    is CatalogTarget.Addon -> {
                        tab.manifestUrl == target.manifestUrl &&
                            tab.type == target.contentType &&
                            tab.catalogId == target.catalogId &&
                            tab.genre == target.genre
                    }

                    is CatalogTarget.Library -> false
                    is CatalogTarget.DiscoverRow -> false
                    null -> false
                }
        }
        val tab = current.tabs.getOrNull(tabIndex) ?: return null
        if (tab.isLoading || tab.isLoadingMore) return loadJobs[tabIndex]?.takeIf(Job::isActive)
        if (!tab.canLoadMore) return null
        return loadTabPage(tabIndex, reset = false)
    }

    /**
     * Pages a folder row until it holds [targetItems] entries, so a shuffle deals from more than
     * the first page. Same bounds as HomeRepository.deepenRowForShuffle: at most [maxPages]
     * requests, and it stops the moment a page adds nothing. Returns the item count reached.
     */
    suspend fun deepenRowForShuffle(sectionKey: String, targetItems: Int, maxPages: Int): Int {
        fun section(): HomeCatalogSection? = getCatalogSectionsForRows().firstOrNull { it.key == sectionKey }

        var pages = 0
        while (pages < maxPages) {
            val section = section() ?: break
            if (section.items.size >= targetItems || section.nextSkip == null) break
            val before = section.items.size
            val job = loadMoreCatalogRow(section) ?: break
            job.join()
            pages++
            if ((section()?.items?.size ?: 0) <= before) {
                log.d { "deepen stopped ($sectionKey): page $pages added nothing" }
                break
            }
        }
        val reached = section()?.items?.size ?: 0
        log.i { "deepen done ($sectionKey) pages=$pages items=$reached target=$targetItems" }
        return reached
    }

    private fun updateTab(index: Int, transform: (FolderTab) -> FolderTab) {
        // Tabs load in parallel; update atomically so one tab's result can't overwrite another's.
        _uiState.update { current ->
            val updatedTabs = current.tabs.toMutableList()
            if (index !in updatedTabs.indices) return@update current
            updatedTabs[index] = transform(updatedTabs[index])

            val allDone = updatedTabs.none { !it.isAllTab && it.isLoading }
            current.copy(
                tabs = updatedTabs,
                isLoading = !allDone,
            )
        }
    }

    private fun loadTabPage(index: Int, reset: Boolean): Job? {
        val currentTab = _uiState.value.tabs.getOrNull(index) ?: return null
        val requestedSkip = if (reset) 0 else currentTab.nextSkip ?: return null
        val currentSource = currentTab.source
        if (
            currentSource?.isTmdb != true &&
            currentSource?.isTrakt != true &&
            currentTab.manifestUrl == null
        ) return null

        updateTab(index) { tab ->
            if (reset) {
                tab.copy(
                    items = emptyList(),
                    isLoading = true,
                    isLoadingMore = false,
                    nextSkip = null,
                    consecutiveDuplicatePages = 0,
                    error = null,
                )
            } else {
                tab.copy(
                    isLoadingMore = true,
                    error = null,
                )
            }
        }

        loadJobs.remove(index)?.cancel()
        val job = scope.launch {
            val startedAt = TimeSource.Monotonic.markNow()
            // The id this load ended up using; differs from the tab's only when the first page
            // fell back to the matched catalog's own id (see fetchCollectionCatalogPage).
            var loadedCatalogId = currentTab.catalogId
            runCatching {
                val source = currentTab.source
                val initialPage = when {
                    source?.isTmdb == true -> TmdbCollectionSourceResolver.resolve(
                        source = source,
                        page = if (reset) 1 else requestedSkip,
                    )

                    source?.isTrakt == true -> TraktPublicListSourceResolver.resolve(
                        source = source,
                        page = if (reset) 1 else requestedSkip,
                    )

                    else -> fetchCollectionCatalogPage(
                        manifestUrl = requireNotNull(currentTab.manifestUrl),
                        type = currentTab.type,
                        catalogId = currentTab.catalogId,
                        fallbackCatalogId = currentTab.fallbackCatalogId.takeIf { reset },
                        genre = currentTab.genre,
                        skip = requestedSkip.takeIf { it > 0 },
                    ).also { loadedCatalogId = it.catalogId }.page
                }

                if (
                    reset &&
                    source?.isTmdb != true &&
                    source?.isTrakt != true &&
                    !currentTab.supportsPagination &&
                    initialPage.rawItemCount > 0 &&
                    initialPage.nextSkip != null
                ) {
                    val probePage = runCatching {
                        fetchCatalogPage(
                            manifestUrl = requireNotNull(currentTab.manifestUrl),
                            type = currentTab.type,
                            catalogId = loadedCatalogId,
                            genre = currentTab.genre,
                            skip = initialPage.nextSkip,
                        )
                    }.getOrNull()
                    if (probePage != null) {
                        applyCatalogPaginationProbe(initialPage, probePage).let { probeResult ->
                            CatalogLoadResult(
                                page = probeResult.page,
                                supportsPagination = probeResult.supportsPagination,
                            )
                        }
                    } else {
                        CatalogLoadResult(page = initialPage)
                    }
                } else {
                    CatalogLoadResult(
                        page = initialPage,
                        supportsPagination = currentTab.supportsPagination,
                    )
                }
            }.onSuccess { loadResult ->
                val page = loadResult.page.withUnreleasedFilter()
                // Info when a row ends up empty or slow, the two cases that make a folder look
                // broken; with debug lines off, a user's log otherwise says nothing about a source
                // that loaded fine and showed nothing.
                val elapsed = startedAt.elapsedNow()
                val summary = "Loaded source ${index + 1} \"${currentTab.label}\": ${page.items.size} items " +
                    "(${loadResult.page.items.size} before unreleased/watched filters) in ${elapsed.inWholeMilliseconds}ms"
                if (page.items.isEmpty() || elapsed >= FOLDER_SLOW_SOURCE_LOG.milliseconds) log.i { summary } else log.d { summary }
                updateTab(index) { tab ->
                    val mergedItems = if (reset) {
                        page.items
                    } else {
                        mergeCatalogItems(tab.items, page.items)
                    }
                    val supportsPagination = tab.supportsPagination || loadResult.supportsPagination
                    val loadedNewItems = reset || mergedItems.size > tab.items.size
                    val paginationState = nextCatalogPaginationState(
                        supportsPagination = supportsPagination,
                        requestedSkip = requestedSkip,
                        page = page,
                        loadedNewItems = loadedNewItems,
                        consecutiveDuplicatePages = if (reset) 0 else tab.consecutiveDuplicatePages,
                    )
                    tab.copy(
                        items = mergedItems,
                        catalogId = loadedCatalogId,
                        supportsPagination = supportsPagination,
                        isLoading = false,
                        isLoadingMore = false,
                        nextSkip = paginationState.nextSkip,
                        consecutiveDuplicatePages = paginationState.consecutiveDuplicatePages,
                        error = null,
                    )
                }
                rebuildAllTab()
            }.onFailure { error ->
                // A load cancelled because a newer one replaced it (or the folder closed) says
                // nothing about the source; the newer load owns the tab now.
                if (error is CancellationException) return@onFailure
                log.e(error) { "Failed to load source ${currentTab.catalogId}" }
                updateTab(index) { tab ->
                    tab.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        nextSkip = if (reset) null else tab.nextSkip,
                        error = error.message,
                    )
                }
                rebuildAllTab()
            }
        }
        loadJobs[index] = job
        return job
    }

    private fun rebuildAllTab() {
        // Atomic for the same reason as updateTab: a stale copy would undo a tab that just loaded.
        _uiState.update { current -> current.withRebuiltAllTab() }
    }

    private fun FolderDetailUiState.withRebuiltAllTab(): FolderDetailUiState {
        val current = this
        if (!current.showAllTab) return current
        val sourceTabs = current.tabs.filter { !it.isAllTab }

        // Round-robin merge
        val merged = mutableListOf<MetaPreview>()
        val seenKeys = mutableSetOf<String>()
        val iterators = sourceTabs.map { it.items.iterator() }
        var hasMore = true
        while (hasMore) {
            hasMore = false
            for (iterator in iterators) {
                if (iterator.hasNext()) {
                    val item = iterator.next()
                    if (seenKeys.add(item.stableKey())) {
                        merged.add(item)
                    }
                    hasMore = true
                }
            }
        }

        val updatedTabs = current.tabs.toMutableList()
        val allTabIndex = updatedTabs.indexOfFirst { it.isAllTab }
        if (allTabIndex >= 0) {
            val hasInitialLoads = sourceTabs.any { it.isLoading }
            val hasLoadMore = sourceTabs.any { it.isLoadingMore }
            val errorMessage = sourceTabs.firstOrNull { it.error != null }?.error
            updatedTabs[allTabIndex] = updatedTabs[allTabIndex].copy(
                items = merged,
                isLoading = hasInitialLoads,
                isLoadingMore = hasLoadMore,
                error = errorMessage.takeIf { merged.isEmpty() },
            )
        }
        return current.copy(tabs = updatedTabs)
    }

    fun getCatalogSectionsForRows(): List<HomeCatalogSection> {
        // The public view, not _uiState: these rows are what TV Mode and Adaptive collections
        // render, so they must carry the poster-service overlay like the grid does.
        val current = uiState.value
        val folder = current.folder ?: return emptyList()
        val collectionId = activeCollectionId ?: return emptyList()

        // Rows are published in order: a row appears only once every row before it has finished its
        // first load (with items, empty, or failed). The sources load in parallel, so publishing
        // whatever had answered put the fastest catalog in row 1 and then slid the real row 1 in
        // ahead of it a moment later — TV Mode, which shows row 1 by position, swapped the whole
        // shelf under the user, and the scrolled layouts jumped.
        //
        // Only for the first moments, though. Held indefinitely, one slow source hid everything
        // after it: a 51-source TMDB folder showed rows 1-5 and nothing else while row 6 sat in a
        // TMDB queue running 9s deep. After the hold, finished rows show in order and a late one
        // takes its place when it lands. The clock is checked as well as the flag, so a flag write
        // lost to a concurrent tab update cannot bring the indefinite hold back.
        val holdExpired = current.rowOrderHoldExpired ||
            (openedAt?.elapsedNow()?.let { it >= FOLDER_ROW_ORDER_HOLD_MS.milliseconds } ?: false)
        val stillLoading = { tab: FolderTab -> tab.isLoading && tab.items.isEmpty() }
        val sourceTabs = current.tabs.filter { !it.isAllTab }
        val resolvedInOrder = if (holdExpired) {
            sourceTabs.filterNot(stillLoading)
        } else {
            sourceTabs.takeWhile { tab -> !stillLoading(tab) }
        }
        return resolvedInOrder.filter { it.items.isNotEmpty() }.mapNotNull { tab ->
            val directSource = tab.source?.let { it.isTmdb || it.isTrakt } == true
            val target = if (directSource) {
                val sourceKey = tab.sourceKey ?: return@mapNotNull null
                CatalogTarget.CollectionSource(
                    collectionId = collectionId,
                    folderId = folder.id,
                    sourceKey = sourceKey,
                    contentType = tab.type,
                    supportsPagination = tab.supportsPagination,
                )
            } else {
                val manifestUrl = tab.manifestUrl ?: return@mapNotNull null
                CatalogTarget.Addon(
                    manifestUrl = manifestUrl,
                    contentType = tab.type,
                    catalogId = tab.catalogId,
                    genre = tab.genre,
                    supportsPagination = tab.supportsPagination,
                )
            }
            HomeCatalogSection(
                key = "folder_${folder.id}_${tab.label}",
                title = tab.label,
                subtitle = tab.typeLabel,
                addonName = "",
                target = target,
                items = tab.items,
                availableItemCount = tab.items.size,
                hasMore = tab.canLoadMore,
                paginates = tab.supportsPagination,
                nextSkip = tab.nextSkip,
                isLoadingMore = tab.isLoadingMore,
            )
        }.ensureUniqueKeys()
    }
}

/**
 * Shuffle state for the rows of the open collection folder. Cleared with the folder, so an order
 * never outlives the screen it was rolled on.
 */
object FolderRowShuffleState : RowShuffleController(
    tag = "FolderRowShuffle",
    publishedSections = FolderDetailRepository::getCatalogSectionsForRows,
    deepenRow = FolderDetailRepository::deepenRowForShuffle,
)

/**
 * The first page of each of [folder]'s sources, at most [perSourceLimit] items from each, for the
 * artwork prewarm. Independent of [FolderDetailRepository]'s state, which belongs to the folder
 * the user has open. Raw catalog items: no poster-service overlay, and no unreleased or watched
 * filtering, since warming a card the screen later hides costs one image and nothing else. A
 * source that fails is skipped.
 */
internal suspend fun loadFolderPrewarmItems(
    folder: CollectionFolder,
    perSourceLimit: Int,
    maxSources: Int = Int.MAX_VALUE,
): List<MetaPreview> = withContext(TmdbBackgroundLane) {
    // The background TMDB lane: a pass over a large collection is thousands of requests, and in the
    // default lane they queued in front of the folder the user had open (waits of 9s, its rows
    // stuck loading behind them).
    val addons = AddonRepository.uiState.value.addons
    folder.resolvedSources.take(maxSources).flatMap { source ->
        runCatching {
            val page = when {
                source.isTmdb -> TmdbCollectionSourceResolver.resolve(source = source, page = 1)
                source.isTrakt -> TraktPublicListSourceResolver.resolve(source = source, page = 1)
                else -> {
                    val catalogSource = source.addonCatalogSource() ?: return@runCatching emptyList<MetaPreview>()
                    val resolved = addons.findCollectionCatalog(catalogSource)
                        ?: return@runCatching emptyList<MetaPreview>()
                    fetchCollectionCatalogPage(
                        manifestUrl = resolved.addon.manifestUrl,
                        type = catalogSource.type,
                        catalogId = catalogSource.catalogId,
                        fallbackCatalogId = resolved.fallbackCatalogId(catalogSource),
                        genre = catalogSource.genre,
                    ).page
                }
            }
            page.items.take(perSourceLimit)
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Logger.withTag("FolderDetailRepository").d { "prewarm load failed for ${folder.id}: ${error.message}" }
            emptyList()
        }
    }
}

/** An addon has not answered yet: enabled, no manifest, and no failure recorded. */
private fun ManagedAddon.isStillLoading(): Boolean = enabled && manifest == null && errorMessage == null

private fun addonTabLabel(catalogSource: CollectionCatalogSource, catalogName: String?): String {
    val genreSuffix = if (catalogSource.genre != null) " · ${catalogSource.genre}" else ""
    return "${catalogName ?: catalogSource.catalogId} (${localizedMediaTypeLabel(catalogSource.type)})$genreSuffix"
}

/** How long a folder that opened before its addons waits for them before giving up. */
private const val ADDON_LOAD_WAIT_MS = 30_000L

/** How long a folder's rows are published strictly in order before a slow one stops holding back the rest. */
private const val FOLDER_ROW_ORDER_HOLD_MS = 2_500L

/** A source slower than this is named in the log at info level. */
private const val FOLDER_SLOW_SOURCE_LOG = 5_000L

private data class CatalogLoadResult(
    val page: CatalogPage,
    val supportsPagination: Boolean = false,
)

private fun Boolean?.orFalse(): Boolean = this == true

private suspend fun CatalogPage.withUnreleasedFilter(): CatalogPage {
    val released = if (HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) {
        items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
    } else {
        items
    }
    // The verdict made here sticks for the session, so give it the mappings first.
    WatchedContentFilter.prepareForLoad(released, resolveBudgetMs = WATCHED_FILTER_RESOLVE_BUDGET_MS)
    val filteredItems = released.filterUnwatchedItems(WatchedContentFilter.current())
    return if (filteredItems.size == items.size) this else copy(items = filteredItems)
}

private fun tmdbCatalogId(source: CollectionSource): String =
    buildString {
        append("tmdb_")
        append(source.tmdbSourceType?.lowercase().orEmpty())
        source.tmdbId?.let {
            append("_")
            append(it)
        }
        append("_")
        append(source.mediaType?.lowercase().orEmpty())
    }

private fun traktCatalogId(source: CollectionSource): String =
    listOf(
        "trakt",
        "list",
        source.traktListId?.toString().orEmpty(),
        source.mediaType?.lowercase().orEmpty(),
        TraktListSort.normalize(source.sortBy),
        TraktSortHow.normalize(source.sortHow),
    ).joinToString("_")
