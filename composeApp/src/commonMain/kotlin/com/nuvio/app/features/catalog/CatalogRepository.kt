package com.nuvio.app.features.catalog

import com.nuvio.app.features.posterservice.withCachedCustomPosters
import com.nuvio.app.features.posterservice.withCustomPosterOverlay
import com.nuvio.app.features.posterservice.CustomPosterScreen
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.catalogRouteKey
import com.nuvio.app.features.discover.DiscoverRecommendationsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.WATCHED_FILTER_RESOLVE_BUDGET_MS
import com.nuvio.app.features.home.WatchedContentFilter
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.home.filterUnwatchedItems
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object CatalogRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(CatalogUiState())
    // See All is a Home row opened out, so it follows the Home screen toggle as upstream's does.
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()
        .withCustomPosterOverlay(CustomPosterScreen.Home) { settings, keys ->
            copy(items = items.withCachedCustomPosters(settings, keys))
        }

    private var activeJob: Job? = null
    private var activeRequest: CatalogRequest? = null
    // Keyed by back-stack entry as well as request: a return from details resumes the same entry
    // and gets its position back, while opening the catalog again is a new entry and starts at the
    // top. clear() only runs on the screen's own back button, so request-only keys let a visit that
    // left any other way (mouse Back, the sidebar) hand its scroll position to the next one.
    private val scrollPositions = linkedMapOf<Pair<String, CatalogRequest>, CatalogScrollPosition>()

    fun load(
        target: CatalogTarget,
        force: Boolean = false,
    ) {
        val request = catalogRequest(target)
        if (!force && activeRequest == request && (_uiState.value.items.isNotEmpty() || _uiState.value.isLoading)) {
            return
        }
        activeRequest = request
        when (target) {
            is CatalogTarget.Library -> fetchInternalLibrary(request)
            // Already built and sitting in memory; there is no page to fetch, and the addon
            // failure path below would only turn "See all" on a generated row into an error.
            is CatalogTarget.DiscoverRow -> publishInMemoryItems(request) {
                DiscoverRecommendationsRepository.uiState.value.rows
                    .firstOrNull { it.key == target.rowKey }
                    ?.items
                    .orEmpty()
            }
            else -> fetchPage(request = request, reset = true)
        }
    }

    fun loadMore() {
        val request = activeRequest ?: return
        val current = _uiState.value
        if (current.isLoading || current.nextSkip == null) return
        fetchPage(request = request, reset = false)
    }

    fun clear() {
        activeJob?.cancel()
        activeRequest = null
        scrollPositions.clear()
        _uiState.value = CatalogUiState()
    }

    fun scrollPosition(
        entryKey: String,
        target: CatalogTarget,
    ): CatalogScrollPosition =
        scrollPositions[entryKey to catalogRequest(target)]
            ?: CatalogScrollPosition()

    fun saveScrollPosition(
        entryKey: String,
        target: CatalogTarget,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        val request = catalogRequest(target)
        scrollPositions[entryKey to request] = CatalogScrollPosition(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        )
    }

    private fun fetchInternalLibrary(request: CatalogRequest) = publishInMemoryItems(request) {
        val target = request.target as CatalogTarget.Library
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState.value.sections
            .firstOrNull { it.type == target.sectionType }
            ?.items
            .orEmpty()
            .map { it.toMetaPreview() }
    }

    /** Serves a target whose items already exist locally as a single, non-paginating page. */
    private fun publishInMemoryItems(
        request: CatalogRequest,
        items: suspend () -> List<MetaPreview>,
    ) {
        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
        )

        activeJob = scope.launch {
            runCatching {
                dedupeCatalogItems(items())
            }.fold(
                onSuccess = { items ->
                    if (activeRequest != request) return@fold
                    _uiState.value = CatalogUiState(
                        items = items,
                        isLoading = false,
                        nextSkip = null,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    if (activeRequest != request) return@fold
                    _uiState.value = CatalogUiState(
                        items = emptyList(),
                        isLoading = false,
                        nextSkip = null,
                        errorMessage = error.message ?: getString(Res.string.catalog_load_failed),
                    )
                },
            )
        }
    }

    private fun fetchPage(
        request: CatalogRequest,
        reset: Boolean,
    ) {
        activeJob?.cancel()
        val current = _uiState.value
        val requestedSkip = if (reset) 0 else current.nextSkip ?: return

        _uiState.value = current.copy(
            items = if (reset) emptyList() else current.items,
            isLoading = true,
            nextSkip = if (reset) null else current.nextSkip,
            errorMessage = null,
        )

        activeJob = scope.launch {
            runCatching {
                when (val target = request.target) {
                    is CatalogTarget.Addon -> fetchCatalogPage(
                        manifestUrl = target.manifestUrl,
                        type = target.contentType,
                        catalogId = target.catalogId,
                        genre = target.genre,
                        search = target.searchQuery,
                        skip = requestedSkip.takeIf { it > 0 },
                    )

                    is CatalogTarget.CollectionSource -> fetchCollectionSourcePage(
                        target = target,
                        page = requestedSkip.takeIf { it > 0 } ?: 1,
                    )

                    is CatalogTarget.Library,
                    is CatalogTarget.DiscoverRow,
                    -> error(getString(Res.string.catalog_load_failed))
                }.withUnreleasedFilter(request.hideUnreleasedContent)
                    .withWatchedFilter(request.hideWatchedContent)
            }.fold(
                onSuccess = { page ->
                    if (activeRequest != request) return@fold

                    val mergedItems = if (reset) {
                        dedupeCatalogItems(page.items)
                    } else {
                        mergeCatalogItems(_uiState.value.items, page.items)
                    }
                    val supportsPagination = request.target.supportsPagination || page.rawItemCount >= CATALOG_PAGE_SIZE
                    val loadedNewItems = reset || mergedItems.size > current.items.size
                    val paginationState = nextCatalogPaginationState(
                        supportsPagination = supportsPagination,
                        requestedSkip = requestedSkip,
                        page = page,
                        loadedNewItems = loadedNewItems,
                        consecutiveDuplicatePages = if (reset) 0 else current.consecutiveDuplicatePages,
                    )
                    _uiState.value = CatalogUiState(
                        items = mergedItems,
                        isLoading = false,
                        nextSkip = paginationState.nextSkip,
                        consecutiveDuplicatePages = paginationState.consecutiveDuplicatePages,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    if (activeRequest != request) return@fold

                    _uiState.value = current.copy(
                        items = if (reset) emptyList() else current.items,
                        isLoading = false,
                        nextSkip = null,
                        errorMessage = error.message ?: getString(Res.string.catalog_load_failed),
                    )
                },
            )
        }
    }

    private fun catalogRequest(target: CatalogTarget): CatalogRequest =
        CatalogRequest(
            target = target,
            hideUnreleasedContent = HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent,
            hideWatchedContent = HomeCatalogSettingsRepository.snapshot().hideWatchedContent,
        )
}

private fun CatalogPage.withUnreleasedFilter(hideUnreleasedContent: Boolean): CatalogPage {
    if (!hideUnreleasedContent) return this
    val filteredItems = items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
    return if (filteredItems.size == items.size) this else copy(items = filteredItems)
}

private suspend fun CatalogPage.withWatchedFilter(hideWatchedContent: Boolean): CatalogPage {
    if (!hideWatchedContent) return this
    // The verdict made here sticks for the session, so give it the mappings first.
    WatchedContentFilter.prepareForLoad(items, resolveBudgetMs = WATCHED_FILTER_RESOLVE_BUDGET_MS)
    val filteredItems = items.filterUnwatchedItems(WatchedContentFilter.current())
    return if (filteredItems.size == items.size) this else copy(items = filteredItems)
}

private suspend fun fetchCollectionSourcePage(
    target: CatalogTarget.CollectionSource,
    page: Int,
): CatalogPage {
    CollectionRepository.initialize()
    val source = CollectionRepository.getCollection(target.collectionId)
        ?.folders
        ?.firstOrNull { it.id == target.folderId }
        ?.resolvedSources
        ?.firstOrNull { it.catalogRouteKey() == target.sourceKey }
        ?: error(getString(Res.string.catalog_load_failed))

    return when {
        source.isTmdb -> TmdbCollectionSourceResolver.resolve(source = source, page = page)
        source.isTrakt -> TraktPublicListSourceResolver.resolve(source = source, page = page)
        else -> error(getString(Res.string.catalog_load_failed))
    }
}

private data class CatalogRequest(
    val target: CatalogTarget,
    val hideUnreleasedContent: Boolean,
    val hideWatchedContent: Boolean,
)
