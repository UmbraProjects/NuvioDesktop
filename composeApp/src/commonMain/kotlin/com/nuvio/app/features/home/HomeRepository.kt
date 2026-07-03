package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.catalog.mergeCatalogItems
import com.nuvio.app.features.catalog.nextCatalogPaginationState
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.catalogRouteKey
import com.nuvio.app.features.collection.findCollectionCatalog
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.random.Random

object HomeRepository {
    private val log = Logger.withTag("HomeRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequestKey: String? = null
    private var lastRequestKey: String? = null
    private var currentDefinitions: List<HomeCatalogDefinition> = emptyList()
    private var cachedSections: Map<String, HomeCatalogSection> = emptyMap()
    // Per-row infinite-scroll bookkeeping (keyed by section key).
    private val loadMoreJobs = mutableMapOf<String, Job>()
    private val sectionDuplicatePageCounts = mutableMapOf<String, Int>()
    private var cachedCollectionHeroItems: List<MetaPreview> = emptyList()
    private var collectionHeroJob: Job? = null
    private var collectionHeroRequestKey: String? = null
    private var lastPublishedCatalogHeroEmpty: Boolean = true
    private var lastErrorMessage: String? = null

    fun refresh(addons: List<ManagedAddon>, force: Boolean = false) {
        val activeAddons = addons.enabledAddons()
        val requests = buildHomeCatalogDefinitions(activeAddons)
        currentDefinitions = requests
        val requestKeys = requests.mapTo(mutableSetOf(), HomeCatalogDefinition::key)
        cachedSections = cachedSections.filterKeys(requestKeys::contains)
        // Drop infinite-scroll state for catalogs that are no longer present.
        (loadMoreJobs.keys - requestKeys).toList().forEach { key ->
            loadMoreJobs.remove(key)?.cancel()
            sectionDuplicatePageCounts.remove(key)
        }
        if (force) sectionDuplicatePageCounts.clear()
        val requestKey = requests.joinToString(separator = "|") { request ->
            "${request.manifestUrl}:${request.type}:${request.catalogId}:${request.genre.orEmpty()}"
        }

        if (!force && activeRequestKey == requestKey && _uiState.value.isLoading) return

        val snapshot = HomeCatalogSettingsRepository.snapshot()
        fun HomeCatalogDefinition.isActiveInHome(): Boolean {
            val preference = snapshot.preferences[key] ?: return true
            return preference.enabled || (snapshot.heroEnabled && preference.heroSourceEnabled)
        }
        val activeRequestKeys = requests
            .filter(HomeCatalogDefinition::isActiveInHome)
            .mapTo(mutableSetOf(), HomeCatalogDefinition::key)
        val allRenderableRowsCached = activeRequestKeys.all { key ->
            cachedSections[key]?.items?.isNotEmpty() == true
        }
        if (!force && requestKey == lastRequestKey && allRenderableRowsCached) {
            if (_uiState.value.sections.isEmpty() || _uiState.value.heroItems.isEmpty()) {
                applyCurrentSettings()
            }
            return
        }
        lastRequestKey = requestKey
        activeRequestKey = requestKey

        if (requests.isEmpty()) {
            activeJob?.cancel()
            activeJob = null
            activeRequestKey = null
            cachedSections = emptyMap()
            lastErrorMessage = null
            publishCurrentState(
                isLoading = false,
                requestKey = requestKey,
            )
            ensureCollectionHeroFallback(
                addons = activeAddons,
                force = force,
                requestKey = requestKey,
            )
            return
        }

        activeJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        activeJob = scope.launch {
            val prioritizedRequests = prioritizeDefinitions(
                definitions = requests.filter(HomeCatalogDefinition::isActiveInHome),
                snapshot = snapshot,
            )
            val pendingRequests = prioritizedRequests.filter { definition ->
                force || cachedSections[definition.key]?.items?.isNotEmpty() != true
            }
            log.i {
                "Home refresh: definitions=${requests.size}, enabled=${prioritizedRequests.size}, " +
                    "pending=${pendingRequests.size}, " +
                    "cached=${cachedSections.size}, force=$force"
            }
            if (pendingRequests.isEmpty()) {
                publishCurrentState(
                    isLoading = false,
                    requestKey = requestKey,
                )
                return@launch
            }
            val loadedSections = linkedMapOf<String, HomeCatalogSection>().apply {
                putAll(cachedSections)
            }
            var firstErrorMessage: String? = null
            var batchIndex = 0

            pendingRequests.chunked(HOME_CATALOG_FETCH_BATCH_SIZE).forEach { batch ->
                if (activeRequestKey != requestKey) return@launch
                val results = batch.map { request ->
                    async { runCatching { request.toSection() } }
                }.awaitAll()

                if (activeRequestKey != requestKey) return@launch

                results.mapNotNull { it.getOrNull() }.forEach { section ->
                    loadedSections[section.key] = section
                }
                results.forEachIndexed { i, result ->
                    result.exceptionOrNull()?.let { error ->
                        log.w(error) {
                            "Catalog fetch failed: ${batch.getOrNull(i)?.let { "${it.addonName} / ${it.catalogId}" } ?: "unknown"}"
                        }
                    }
                }
                if (firstErrorMessage == null) {
                    firstErrorMessage = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                }
                cachedSections = loadedSections.toMap()
                lastErrorMessage = firstErrorMessage
                if (batchIndex == 0 || (batchIndex + 1) % HOME_CATALOG_PUBLISH_INTERVAL == 0) {
                    publishCurrentState(
                        isLoading = true,
                        requestKey = requestKey,
                    )
                }
                batchIndex++
            }

            if (activeRequestKey != requestKey) return@launch

            cachedSections = loadedSections.toMap()
            lastErrorMessage = firstErrorMessage
            publishCurrentState(
                isLoading = false,
                requestKey = requestKey,
            )
            ensureCollectionHeroFallback(
                addons = activeAddons,
                force = force,
                requestKey = requestKey,
            )
        }
    }

    fun applyCurrentSettings() {
        publishCurrentState(
            isLoading = _uiState.value.isLoading,
            requestKey = activeRequestKey ?: lastRequestKey,
        )
        ensureCollectionHeroFallback(
            addons = AddonRepository.uiState.value.addons.enabledAddons(),
            force = false,
            requestKey = activeRequestKey ?: lastRequestKey,
        )
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        activeRequestKey = null
        lastRequestKey = null
        currentDefinitions = emptyList()
        cachedSections = emptyMap()
        cachedCollectionHeroItems = emptyList()
        collectionHeroJob?.cancel()
        collectionHeroJob = null
        collectionHeroRequestKey = null
        lastPublishedCatalogHeroEmpty = true
        lastErrorMessage = null
        _uiState.value = HomeUiState()
    }

    private fun publishCurrentState(
        isLoading: Boolean,
        requestKey: String?,
    ) {
        val snapshot = HomeCatalogSettingsRepository.snapshot()
        val preferences = snapshot.preferences
        val todayIsoDate = if (snapshot.hideUnreleasedContent) CurrentDateProvider.todayIsoDate() else null
        fun HomeCatalogSection.withReleaseFilter(): HomeCatalogSection =
            if (todayIsoDate == null) this else filterReleasedItems(todayIsoDate)

        val skippedEnabledSections = mutableListOf<String>()
        val sections = currentDefinitions
            .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .mapNotNull { definition ->
                val preference = preferences[definition.key]
                if (preference?.enabled == false) return@mapNotNull null

                val cachedSection = cachedSections[definition.key]
                if (cachedSection == null) {
                    skippedEnabledSections += "${definition.key}:not-fetched"
                    return@mapNotNull null
                }
                val section = cachedSection.withReleaseFilter()
                if (section.items.isEmpty()) {
                    val reason = if (cachedSection.items.isEmpty()) "empty" else "filtered-empty"
                    skippedEnabledSections += "${definition.key}:$reason"
                    return@mapNotNull null
                }
                val customTitle = preference?.customTitle.orEmpty()
                section.copy(
                    title = customTitle.ifBlank { section.title },
                )
            }

        val catalogHeroItems = if (snapshot.heroEnabled) {
            val heroRandom = Random((requestKey?.hashCode() ?: 0).absoluteValue + 1)
            currentDefinitions
                .filter { definition -> preferences[definition.key]?.heroSourceEnabled != false }
                .mapNotNull { definition -> cachedSections[definition.key] }
                .map { section -> section.withReleaseFilter() }
                .flatMap { section -> section.items }
                .distinctBy { item -> "${item.type}:${item.id}" }
                .shuffled(heroRandom)
                .take(HOME_HERO_ITEM_LIMIT)
        } else {
            emptyList()
        }
        lastPublishedCatalogHeroEmpty = snapshot.heroEnabled && catalogHeroItems.isEmpty()
        val heroItems = if (snapshot.heroEnabled) {
            catalogHeroItems.ifEmpty { cachedCollectionHeroItems }
        } else {
            emptyList()
        }
        log.d {
            val heroSource = when {
                !snapshot.heroEnabled -> "disabled"
                heroItems.isEmpty() -> "empty — no catalog or collection items"
                catalogHeroItems.isNotEmpty() -> "catalogs (${heroItems.size} items)"
                else -> "collection fallback (${heroItems.size} items)"
            }
            val skipped = skippedEnabledSections.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = ", skipped=", limit = 12)
                .orEmpty()
            "Home state: ${sections.size} sections visible, hero=$heroSource$skipped"
        }

        _uiState.value = HomeUiState(
            isLoading = isLoading,
            heroItems = heroItems,
            sections = sections,
            errorMessage = if (sections.isEmpty()) lastErrorMessage else null,
        )
    }

    private suspend fun HomeCatalogDefinition.toSection(): HomeCatalogSection {
        val page = fetchCatalogPage(
            manifestUrl = manifestUrl,
            type = type,
            catalogId = catalogId,
            genre = genre,
            // Paginating rows are horizontal infinite-scroll, so fetch the full first page (skip stays
            // page-aligned for subsequent loads). Non-paginating rows only need the preview + pill.
            maxItems = if (supportsPagination) null else HOME_CATALOG_PREVIEW_FETCH_LIMIT,
        )
        val items = page.items
        val nextSkip = if (supportsPagination) page.nextSkip else null
        log.d { "Catalog fetch: $addonName / $catalogId ($type) — ${if (items.isEmpty()) "empty" else "${items.size} items"}" }
        if (items.isEmpty()) {
            return HomeCatalogSection(
                key = key,
                title = defaultTitle,
                subtitle = addonName,
                addonName = addonName,
                target = CatalogTarget.Addon(
                    manifestUrl = manifestUrl,
                    contentType = type,
                    catalogId = catalogId,
                    genre = genre,
                    supportsPagination = supportsPagination,
                ),
                items = emptyList(),
                availableItemCount = 0,
                hasMore = false,
            )
        }

        return HomeCatalogSection(
            key = key,
            title = defaultTitle,
            subtitle = addonName,
            addonName = addonName,
            target = CatalogTarget.Addon(
                manifestUrl = manifestUrl,
                contentType = type,
                catalogId = catalogId,
                genre = genre,
                supportsPagination = supportsPagination,
            ),
            items = items,
            availableItemCount = page.rawItemCount,
            hasMore = nextSkip != null,
            paginates = supportsPagination,
            nextSkip = nextSkip,
        )
    }

    /**
     * Appends the next page to a horizontally infinite-scrolling catalog row. No-op for non-paginating
     * rows, exhausted rows, or while a page is already loading. New items carry their own metadata from
     * the addon response, so nothing extra needs enriching here.
     */
    fun loadMoreCatalogRow(sectionKey: String) {
        val section = _uiState.value.sections.firstOrNull { it.key == sectionKey } ?: return
        val target = section.target as? CatalogTarget.Addon ?: return
        val skip = section.nextSkip ?: return
        if (section.isLoadingMore || loadMoreJobs[sectionKey]?.isActive == true) return

        setSection(section.copy(isLoadingMore = true))
        loadMoreJobs[sectionKey] = scope.launch {
            runCatching {
                fetchCatalogPage(
                    manifestUrl = target.manifestUrl,
                    type = target.contentType,
                    catalogId = target.catalogId,
                    genre = target.genre,
                    skip = skip,
                )
            }.fold(
                onSuccess = { page ->
                    val current = _uiState.value.sections.firstOrNull { it.key == sectionKey }
                        ?: return@launch
                    val merged = mergeCatalogItems(current.items, page.items)
                    val pagination = nextCatalogPaginationState(
                        supportsPagination = true,
                        requestedSkip = skip,
                        page = page,
                        loadedNewItems = merged.size > current.items.size,
                        consecutiveDuplicatePages = sectionDuplicatePageCounts[sectionKey] ?: 0,
                    )
                    sectionDuplicatePageCounts[sectionKey] = pagination.consecutiveDuplicatePages
                    setSection(
                        current.copy(
                            items = merged,
                            availableItemCount = maxOf(current.availableItemCount, merged.size),
                            nextSkip = pagination.nextSkip,
                            hasMore = pagination.nextSkip != null,
                            isLoadingMore = false,
                        ),
                    )
                },
                onFailure = {
                    _uiState.value.sections.firstOrNull { it.key == sectionKey }
                        ?.let { setSection(it.copy(isLoadingMore = false)) }
                },
            )
        }
    }

    private fun setSection(section: HomeCatalogSection) {
        _uiState.update { state ->
            state.copy(sections = state.sections.map { if (it.key == section.key) section else it })
        }
        if (cachedSections.containsKey(section.key)) {
            cachedSections = cachedSections + (section.key to section)
        }
    }

    private fun ensureCollectionHeroFallback(
        addons: List<ManagedAddon>,
        force: Boolean,
        requestKey: String?,
    ) {
        if (!lastPublishedCatalogHeroEmpty) return
        val snapshot = HomeCatalogSettingsRepository.snapshot()
        if (!snapshot.heroEnabled) return
        val collections = enabledCollectionsForHero(snapshot)
        if (collections.isEmpty()) {
            cachedCollectionHeroItems = emptyList()
            collectionHeroRequestKey = null
            return
        }

        val nextRequestKey = collectionHeroRequestKey(
            collections = collections,
            addons = addons,
            snapshot = snapshot,
            requestKey = requestKey,
        )
        if (!force && collectionHeroRequestKey == nextRequestKey) return

        collectionHeroJob?.cancel()
        collectionHeroRequestKey = nextRequestKey
        cachedCollectionHeroItems = emptyList()
        publishCurrentState(
            isLoading = _uiState.value.isLoading,
            requestKey = requestKey,
        )

        collectionHeroJob = scope.launch {
            val sources = collectionHeroSources(collections)
            val sourceResults = sources.map { source ->
                async {
                    runCatching {
                        source.resolveCollectionHeroItems(addons)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
            val random = Random((nextRequestKey.hashCode()).absoluteValue + 7)
            cachedCollectionHeroItems = roundRobinCollectionHeroItems(sourceResults)
                .distinctBy { item -> item.stableKey() }
                .shuffled(random)
                .take(HOME_HERO_ITEM_LIMIT)
            publishCurrentState(
                isLoading = _uiState.value.isLoading,
                requestKey = requestKey,
            )
        }
    }

    private fun enabledCollectionsForHero(snapshot: HomeCatalogSettingsSnapshot): List<Collection> {
        val preferences = snapshot.preferences
        return CollectionRepository.collections.value
            .filter { collection ->
                collection.folders.isNotEmpty() &&
                    preferences["collection_${collection.id}"]?.enabled != false
            }
            .sortedBy { collection ->
                preferences["collection_${collection.id}"]?.order ?: Int.MAX_VALUE
            }
    }

    private fun collectionHeroSources(collections: List<Collection>): List<CollectionSource> =
        collections
            .flatMap { collection -> collection.folders }
            .flatMap { folder -> folder.resolvedSources }
            .take(HOME_COLLECTION_HERO_SOURCE_LIMIT)

    private suspend fun CollectionSource.resolveCollectionHeroItems(addons: List<ManagedAddon>): List<MetaPreview> {
        val page = when {
            isTmdb -> TmdbCollectionSourceResolver.resolve(source = this, page = 1)
            isTrakt -> TraktPublicListSourceResolver.resolve(source = this, page = 1)
            else -> {
                val catalogSource = addonCatalogSource() ?: return emptyList()
                val resolvedCatalog = addons.findCollectionCatalog(catalogSource) ?: return emptyList()
                fetchCatalogPage(
                    manifestUrl = resolvedCatalog.addon.manifestUrl,
                    type = catalogSource.type,
                    catalogId = catalogSource.catalogId,
                    genre = catalogSource.genre,
                    maxItems = HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT,
                )
            }
        }
        val items = page.items
        return if (HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) {
            items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
        } else {
            items
        }
    }

    private fun roundRobinCollectionHeroItems(sourceResults: List<List<MetaPreview>>): List<MetaPreview> {
        val iterators = sourceResults.filter { it.isNotEmpty() }.map { it.iterator() }
        if (iterators.isEmpty()) return emptyList()
        val merged = mutableListOf<MetaPreview>()
        var hasMore = true
        while (hasMore && merged.size < HOME_COLLECTION_HERO_SOURCE_LIMIT * HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT) {
            hasMore = false
            iterators.forEach { iterator ->
                if (iterator.hasNext()) {
                    merged.add(iterator.next())
                    hasMore = true
                }
            }
        }
        return merged
    }

    private fun collectionHeroRequestKey(
        collections: List<Collection>,
        addons: List<ManagedAddon>,
        snapshot: HomeCatalogSettingsSnapshot,
        requestKey: String?,
    ): String = buildString {
        append(requestKey.orEmpty())
        append("|hideUnreleased=")
        append(snapshot.hideUnreleasedContent)
        append("|collections=")
        collections.forEach { collection ->
            val preference = snapshot.preferences["collection_${collection.id}"]
            append(collection.id)
            append(":")
            append(preference?.order ?: Int.MAX_VALUE)
            append(":")
            collection.folders.forEach { folder ->
                append(folder.id)
                append("[")
                folder.resolvedSources.forEach { source ->
                    append(collectionSourceKey(source))
                    append(",")
                }
                append("]")
            }
            append(";")
        }
        append("|addons=")
        addons.forEach { addon ->
            append(addon.manifest?.id.orEmpty())
            append(":")
            append(addon.manifestUrl)
            append(":")
            append(addon.manifest?.catalogs?.size ?: 0)
            append(";")
        }
    }

    private fun collectionSourceKey(source: CollectionSource): String =
        source.catalogRouteKey()
}

private const val HOME_HERO_ITEM_LIMIT = 8
private const val HOME_COLLECTION_HERO_SOURCE_LIMIT = 6
private const val HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT = 8
private const val HOME_CATALOG_FETCH_BATCH_SIZE = 4
private const val HOME_CATALOG_PREVIEW_FETCH_LIMIT = 18
private const val HOME_CATALOG_PUBLISH_INTERVAL = 2

private fun prioritizeDefinitions(
    definitions: List<HomeCatalogDefinition>,
    snapshot: HomeCatalogSettingsSnapshot,
): List<HomeCatalogDefinition> {
    val orderedDefinitions = definitions.sortedBy { definition ->
        snapshot.preferences[definition.key]?.order ?: Int.MAX_VALUE
    }
    val (priority, remainder) = orderedDefinitions.partition { definition ->
        val preference = snapshot.preferences[definition.key]
        if (preference == null) {
            true
        } else {
            preference.enabled || (snapshot.heroEnabled && preference.heroSourceEnabled)
        }
    }
    return priority + remainder
}
