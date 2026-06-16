package com.nuvio.app.features.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.isDesktop
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioNetworkOfflineCard
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.rememberMouseActivityState
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.cloud.CloudLibraryUiState
import com.nuvio.app.features.cloud.findPlaybackTargetForProgress
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.SeriesPrimaryAction
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeHeroReservedSpace
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomeSkeletonHero
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.normalizeTraktContinueWatchingDaysCap
import com.nuvio.app.features.trakt.shouldUseTraktProgress
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CachedInProgressItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSortMode
import com.nuvio.app.features.watchprogress.isMalformedNextUpSeedContentId
import com.nuvio.app.features.watchprogress.isSeriesTypeForContinueWatching
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import com.nuvio.app.features.watchprogress.shouldTreatAsInProgressForContinueWatching
import com.nuvio.app.features.watchprogress.shouldUseAsCompletedSeedForContinueWatching
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktPlayback
import com.nuvio.app.features.watchprogress.buildContinueWatchingEpisodeSubtitle
import com.nuvio.app.features.watchprogress.continueWatchingEntries
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import com.nuvio.app.features.watchprogress.toUpNextContinueWatchingItem
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.isReleasedBy
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.home.components.HomeCollectionRowSection
import com.nuvio.app.features.home.components.HomeTvFocusState
import com.nuvio.app.features.home.components.HomeTvRow
import com.nuvio.app.features.home.components.homeHeroLayout
import androidx.compose.foundation.lazy.LazyListState
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.nuvio.app.features.trakt.TraktEpisodeMappingService
import com.nuvio.app.features.home.components.ContinueWatchingLayout
import com.nuvio.app.features.home.components.continueWatchingLandscapeCardHeight
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.home.components.rememberContinueWatchingLayout
import kotlinx.coroutines.CancellationException
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    animateCollectionGifs: Boolean = true,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    onCastClick: ((HeroCastMember) -> Unit)? = null,
    onFirstCatalogRendered: (() -> Unit)? = null,
    onNavigateToSearch: (() -> Unit)? = null,
    onNavigateToLibrary: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            AddonRepository.initialize()
            CollectionRepository.initialize()
            ContinueWatchingPreferencesRepository.ensureLoaded()
            HomeCatalogSettingsRepository.snapshot()
            TraktSettingsRepository.ensureLoaded()
            TraktAuthRepository.ensureLoaded()
            WatchedRepository.ensureLoaded()
            WatchProgressRepository.ensureLoaded()
        }
    }

    val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val homeUiState by HomeRepository.uiState.collectAsStateWithLifecycle()
    val homeSettingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val homeListState = rememberLazyListState()
    val collections by CollectionRepository.collections.collectAsStateWithLifecycle()
    val continueWatchingPreferences by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
    val watchedUiState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val watchProgressUiState by WatchProgressRepository.uiState.collectAsStateWithLifecycle()
    val cloudLibraryUiState by CloudLibraryRepository.uiState.collectAsStateWithLifecycle()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val traktSettingsUiState by TraktSettingsRepository.uiState.collectAsStateWithLifecycle()
    val isTraktAuthenticated by TraktAuthRepository.isAuthenticated.collectAsStateWithLifecycle()
    var observedOfflineState by remember { mutableStateOf(false) }

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect {
            homeListState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(networkStatusUiState.condition) {
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                observedOfflineState = true
            }

            NetworkCondition.Online -> {
                if (observedOfflineState) {
                    observedOfflineState = false
                    HomeRepository.refresh(addonsUiState.addons.enabledAddons(), force = true)
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    val isTraktProgressActive = remember(
        isTraktAuthenticated,
        traktSettingsUiState.watchProgressSource,
    ) {
        shouldUseTraktProgress(
            isAuthenticated = isTraktAuthenticated,
            source = traktSettingsUiState.watchProgressSource,
        )
    }

    val effectiveWatchProgressEntries = remember(
        watchProgressUiState.entries,
        isTraktProgressActive,
        traktSettingsUiState.continueWatchingDaysCap,
    ) {
        val filtered = if (isTraktProgressActive) {
            watchProgressUiState.entries.filter { !WatchProgressRepository.isDroppedShow(it.parentMetaId) }
        } else {
            watchProgressUiState.entries
        }
        filterEntriesForTraktContinueWatchingWindow(
            entries = filtered,
            isTraktProgressActive = isTraktProgressActive,
            daysCap = traktSettingsUiState.continueWatchingDaysCap,
            nowEpochMs = WatchProgressClock.nowEpochMs(),
        )
    }

    val allNextUpSeedCandidates = remember(
        watchProgressUiState.entries,
        watchedUiState.items,
        isTraktProgressActive,
        continueWatchingPreferences.upNextFromFurthestEpisode,
    ) {
        val filteredEntries = if (isTraktProgressActive) {
            watchProgressUiState.entries.filter { !WatchProgressRepository.isDroppedShow(it.parentMetaId) }
        } else {
            watchProgressUiState.entries
        }
        val filteredWatchedItems = if (isTraktProgressActive) {
            watchedUiState.items.filter { !WatchProgressRepository.isDroppedShow(it.id) }
        } else {
            watchedUiState.items
        }
        buildHomeNextUpSeedCandidates(
            progressEntries = filteredEntries,
            watchedItems = filteredWatchedItems,
            isTraktProgressActive = isTraktProgressActive,
            preferFurthestEpisode = continueWatchingPreferences.upNextFromFurthestEpisode,
            nowEpochMs = WatchProgressClock.nowEpochMs(),
        )
    }

    val recentNextUpSeedCandidates = remember(
        allNextUpSeedCandidates,
        isTraktProgressActive,
        traktSettingsUiState.continueWatchingDaysCap,
    ) {
        filterHomeNextUpCandidatesForTraktContinueWatchingWindow(
            candidates = allNextUpSeedCandidates,
            isTraktProgressActive = isTraktProgressActive,
            daysCap = traktSettingsUiState.continueWatchingDaysCap,
            nowEpochMs = WatchProgressClock.nowEpochMs(),
        )
    }

    val activeNextUpSeedContentIds = remember(allNextUpSeedCandidates) {
        allNextUpSeedCandidates.mapTo(mutableSetOf()) { candidate -> candidate.content.id }
    }

    val currentNextUpSeedByContentId = remember(allNextUpSeedCandidates) {
        allNextUpSeedCandidates.associate { candidate ->
            candidate.content.id to (candidate.seasonNumber to candidate.episodeNumber)
        }.toMap()
    }

    val visibleContinueWatchingEntries = remember(effectiveWatchProgressEntries) {
        effectiveWatchProgressEntries.continueWatchingEntries(limit = HomeContinueWatchingMaxRecentProgressItems)
    }

    val watchProgressSeedKey = remember(watchProgressUiState.entries) {
        watchProgressUiState.entries.map { entry ->
            Triple(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
        }
    }

    LaunchedEffect(visibleContinueWatchingEntries) {
        if (visibleContinueWatchingEntries.any(WatchProgressEntry::isCloudLibraryProgressEntry)) {
            CloudLibraryRepository.ensureLoaded()
        }
    }

    val latestCompletedAtBySeries = remember(allNextUpSeedCandidates) {
        allNextUpSeedCandidates
            .groupBy { candidate -> candidate.content.id }
            .mapValues { (_, candidates) -> candidates.maxOfOrNull { candidate -> candidate.markedAtEpochMs } ?: Long.MIN_VALUE }
    }

    val nextUpSuppressedSeriesIds = remember(visibleContinueWatchingEntries, latestCompletedAtBySeries) {
        visibleContinueWatchingEntries
            .asSequence()
            .filter { entry -> entry.parentMetaType.isSeriesTypeForContinueWatching() }
            .filter { entry ->
                shouldTreatAsActiveInProgressForNextUpSuppression(
                    progress = entry,
                    latestCompletedAt = latestCompletedAtBySeries[entry.parentMetaId],
                )
            }
            .map { entry -> entry.parentMetaId }
            .filter(String::isNotBlank)
            .toSet()
    }

    val completedSeriesCandidates = remember(recentNextUpSeedCandidates, nextUpSuppressedSeriesIds) {
        recentNextUpSeedCandidates.filter { candidate ->
            candidate.content.id !in nextUpSuppressedSeriesIds
        }
    }
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val activeProfileId = profileState.activeProfile?.profileIndex ?: 1
    val cwCacheClearVersion by ContinueWatchingEnrichmentCache.cacheCleared.collectAsStateWithLifecycle()

    var nextUpItemsBySeries by remember(activeProfileId) { mutableStateOf<Map<String, Pair<Long, ContinueWatchingItem>>>(emptyMap()) }
    var processedNextUpContentIds by remember(activeProfileId) { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(activeProfileId, cwCacheClearVersion) {
        if (cwCacheClearVersion == 0) return@LaunchedEffect
        nextUpItemsBySeries = emptyMap()
        processedNextUpContentIds = emptySet()
    }

    val cachedSnapshots = remember(activeProfileId, cwCacheClearVersion) {
        ContinueWatchingEnrichmentCache.getSnapshots()
    }
    val shouldValidateMissingNextUpSeeds = remember(
        isTraktProgressActive,
        watchProgressUiState.hasLoadedRemoteProgress,
        watchedUiState.isLoaded,
    ) {
        if (isTraktProgressActive) {
            watchProgressUiState.hasLoadedRemoteProgress
        } else {
            watchedUiState.isLoaded
        }
    }
    val cachedNextUpItems = remember(
        cachedSnapshots.first,
        continueWatchingPreferences.dismissedNextUpKeys,
        activeNextUpSeedContentIds,
        currentNextUpSeedByContentId,
        isTraktProgressActive,
        watchProgressUiState.hasLoadedRemoteProgress,
        shouldValidateMissingNextUpSeeds,
        processedNextUpContentIds,
        nextUpItemsBySeries,
        continueWatchingPreferences.showUnairedNextUp,
        watchedUiState.isLoaded,
    ) {
        cachedSnapshots.first.mapNotNull { cached ->
            if (
                shouldValidateMissingNextUpSeeds &&
                cached.contentId !in activeNextUpSeedContentIds
            ) {
                return@mapNotNull null
            }
            val currentSeed = currentNextUpSeedByContentId[cached.contentId]
            if (currentSeed != null) {
                val (currentSeason, currentEpisode) = currentSeed
                val seedChanged = currentSeason != cached.seedSeason || currentEpisode != cached.seedEpisode
                if (seedChanged) return@mapNotNull null
            }
            if (
                isTraktProgressActive &&
                watchProgressUiState.hasLoadedRemoteProgress &&
                cached.contentId in processedNextUpContentIds &&
                cached.contentId !in nextUpItemsBySeries.keys
            ) {
                return@mapNotNull null
            }
            if (nextUpDismissKey(cached.contentId, cached.seedSeason, cached.seedEpisode) in continueWatchingPreferences.dismissedNextUpKeys) {
                return@mapNotNull null
            }
            if (!cached.hasAired && !continueWatchingPreferences.showUnairedNextUp) {
                return@mapNotNull null
            }
            if (isTraktProgressActive && WatchProgressRepository.isDroppedShow(cached.contentId)) {
                return@mapNotNull null
            }
            val item = cached.toContinueWatchingItem() ?: return@mapNotNull null
            val sortTimestamp = if (item.isReleaseAlert) {
                com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs(item.released) ?: cached.lastWatched
            } else {
                cached.lastWatched
            }
            cached.contentId to (sortTimestamp to item)
        }.toMap()
    }
    val cachedInProgressItems = remember(cachedSnapshots.second, isTraktProgressActive) {
        cachedSnapshots.second.mapNotNull { cached ->
            if (isTraktProgressActive && WatchProgressRepository.isDroppedShow(cached.contentId)) {
                return@mapNotNull null
            }
            cached.videoId to cached.toContinueWatchingItem()
        }.toMap()
    }

    val effectivNextUpItems = remember(
        nextUpItemsBySeries,
        cachedNextUpItems,
        continueWatchingPreferences.dismissedNextUpKeys,
        activeNextUpSeedContentIds,
        currentNextUpSeedByContentId,
        shouldValidateMissingNextUpSeeds,
    ) {
        val liveNextUpItems = filterNextUpItemsByCurrentSeeds(
            nextUpItemsBySeries = nextUpItemsBySeries,
            activeSeedContentIds = activeNextUpSeedContentIds,
            currentSeedByContentId = currentNextUpSeedByContentId,
            shouldDropItemsWithoutActiveSeed = shouldValidateMissingNextUpSeeds,
        ).filterValues { (_, item) ->
            nextUpDismissKey(
                item.parentMetaId,
                item.nextUpSeedSeasonNumber,
                item.nextUpSeedEpisodeNumber,
            ) !in continueWatchingPreferences.dismissedNextUpKeys
        }
        if (liveNextUpItems.isNotEmpty()) {
            liveNextUpItems.mapValues { (contentId, pair) ->
                val cachedItem = cachedNextUpItems[contentId]?.second
                pair.first to pair.second.withFallbackMetadata(cachedItem)
            }
        } else {
            cachedNextUpItems
        }
    }

    val continueWatchingItems = remember(
        visibleContinueWatchingEntries,
        cachedInProgressItems,
        effectivNextUpItems,
        nextUpSuppressedSeriesIds,
        continueWatchingPreferences.sortMode,
        cloudLibraryUiState,
    ) {
        buildHomeContinueWatchingItems(
            visibleEntries = visibleContinueWatchingEntries,
            cachedInProgressByVideoId = cachedInProgressItems,
            nextUpItemsBySeries = effectivNextUpItems,
            nextUpSuppressedSeriesIds = nextUpSuppressedSeriesIds,
            sortMode = continueWatchingPreferences.sortMode,
            todayIsoDate = CurrentDateProvider.todayIsoDate(),
            cloudLibraryUiState = cloudLibraryUiState,
        )
    }
    val enabledAddons = remember(addonsUiState.addons) {
        addonsUiState.addons.enabledAddons()
    }
    val isRefreshingEnabledAddons = remember(enabledAddons) {
        enabledAddons.any { addon -> addon.isRefreshing }
    }
    val availableManifests = remember(enabledAddons) {
        enabledAddons.mapNotNull { addon -> addon.manifest }
    }

    val metaProviderKey = remember(availableManifests) {
        availableManifests
            .filter { manifest -> manifest.resources.any { resource -> resource.name == "meta" } }
            .map { manifest -> manifest.transportUrl }
            .sorted()
    }

    val catalogRefreshKey = remember(availableManifests) {
        availableManifests
            .map { manifest ->
                buildString {
                    append(manifest.transportUrl)
                    append(':')
                    append(manifest.catalogs.joinToString(separator = ",") { catalog ->
                        "${catalog.type}:${catalog.id}:${catalog.extra.count { it.isRequired }}"
                    })
                }
            }
            .sorted()
    }

    LaunchedEffect(catalogRefreshKey) {
        if (catalogRefreshKey.isEmpty()) return@LaunchedEffect
        HomeCatalogSettingsRepository.syncCatalogs(enabledAddons)
        HomeRepository.refresh(enabledAddons)
    }

    LaunchedEffect(collections) {
        HomeCatalogSettingsRepository.syncCollections(collections)
    }

    LaunchedEffect(
        completedSeriesCandidates,
        metaProviderKey,
        continueWatchingPreferences.showUnairedNextUp,
        continueWatchingPreferences.upNextFromFurthestEpisode,
        isRefreshingEnabledAddons,
        watchProgressSeedKey,
        watchedUiState.items,
        watchedUiState.isLoaded,
    ) {
        if (completedSeriesCandidates.isEmpty()) {
            nextUpItemsBySeries = emptyMap()
            processedNextUpContentIds = emptySet()
            return@LaunchedEffect
        }

        if (!isTraktProgressActive && !watchedUiState.isLoaded) {
            return@LaunchedEffect
        }

        if (isRefreshingEnabledAddons) {
            return@LaunchedEffect
        }

        withContext(Dispatchers.Default) {
            val cachedResolvedNextUpItems = completedSeriesCandidates.mapNotNull { candidate ->
                val cached = cachedNextUpItems[candidate.content.id] ?: return@mapNotNull null
                val item = cached.second
                if (
                    item.nextUpSeedSeasonNumber != candidate.seasonNumber ||
                    item.nextUpSeedEpisodeNumber != candidate.episodeNumber
                ) {
                    return@mapNotNull null
                }
                candidate.content.id to cached
            }.toMap()
            val candidatesToResolve = completedSeriesCandidates.filter { candidate ->
                candidate.content.id !in cachedResolvedNextUpItems
            }
            val resolutionCandidates = candidatesToResolve.take(HomeNextUpInitialResolutionLimit)
            val seedLastWatchedMap = completedSeriesCandidates.associate { it.content.id to it.markedAtEpochMs }
            if (candidatesToResolve.isEmpty()) {
                withContext(Dispatchers.Main) {
                    nextUpItemsBySeries = cachedResolvedNextUpItems
                    processedNextUpContentIds = completedSeriesCandidates.mapTo(mutableSetOf()) { candidate ->
                        candidate.content.id
                    }
                }
                saveContinueWatchingSnapshots(
                    nextUpItemsBySeries = cachedResolvedNextUpItems,
                    visibleContinueWatchingEntries = visibleContinueWatchingEntries,
                    todayIsoDate = CurrentDateProvider.todayIsoDate(),
                    seedLastWatchedMap = seedLastWatchedMap,
                )
                return@withContext
            }

            if (metaProviderKey.isEmpty()) {
                return@withContext
            }

            val todayIsoDate = CurrentDateProvider.todayIsoDate()
            val semaphore = Semaphore(NEXT_UP_RESOLUTION_CONCURRENCY)
            val freshResults = mutableMapOf<String, Pair<Long, ContinueWatchingItem>>()
            val processedFreshContentIds = mutableSetOf<String>()
            val candidateBatches = resolutionCandidates.chunked(NEXT_UP_RESOLUTION_BATCH_SIZE)

            for (batch in candidateBatches) {
                val batchResults = batch.map { completedEntry ->
                    async {
                        semaphore.withPermit {
                            resolveHomeNextUpCandidate(
                                completedEntry = completedEntry,
                                watchProgressEntries = watchProgressUiState.entries,
                                watchedItems = watchedUiState.items,
                                todayIsoDate = todayIsoDate,
                                preferFurthestEpisode = continueWatchingPreferences.upNextFromFurthestEpisode,
                                showUnairedNextUp = continueWatchingPreferences.showUnairedNextUp,
                                dismissedNextUpKeys = continueWatchingPreferences.dismissedNextUpKeys,
                                isTraktProgressActive = isTraktProgressActive,
                            )
                        }
                    }
                }.awaitAll()
                batch.forEach { candidate -> processedFreshContentIds += candidate.content.id }

                val resolvedBeforeBatch = freshResults.size
                batchResults.filterNotNull().forEach { (contentId, item) ->
                    freshResults[contentId] = item
                }
                val batchResolvedCount = freshResults.size - resolvedBeforeBatch
                if (batchResolvedCount > 0) {
                    val progressiveResults = cachedResolvedNextUpItems + freshResults
                    withContext(Dispatchers.Main) {
                        nextUpItemsBySeries = progressiveResults
                        processedNextUpContentIds = (
                            cachedResolvedNextUpItems.keys +
                                processedFreshContentIds
                            ).toSet()
                    }
                }

                if (cachedResolvedNextUpItems.size + freshResults.size >= HomeContinueWatchingMaxRecentProgressItems) {
                    break
                }
            }

            val results = cachedResolvedNextUpItems + freshResults
            withContext(Dispatchers.Main) {
                nextUpItemsBySeries = results
                processedNextUpContentIds = (
                    cachedResolvedNextUpItems.keys +
                        processedFreshContentIds
                    ).toSet()
            }

            saveContinueWatchingSnapshots(
                nextUpItemsBySeries = results,
                visibleContinueWatchingEntries = visibleContinueWatchingEntries,
                todayIsoDate = todayIsoDate,
                seedLastWatchedMap = seedLastWatchedMap,
            )
        }
    }

    val hasActiveAddons = enabledAddons.any { it.manifest != null }
    val showHeroSlot = homeSettingsUiState.heroEnabled
    val isResolvingHeroSources = enabledAddons.any { it.isRefreshing } || homeUiState.isLoading
    val showHeroSkeleton = showHeroSlot &&
        homeUiState.heroItems.isEmpty() &&
        isResolvingHeroSources
    var firstCatalogReported by remember { mutableStateOf(false) }
    var activeHeroBackdrop by remember { mutableStateOf<String?>(null) }
    var activeHeroAccent by remember { mutableStateOf<Color?>(null) }
    val ambientBackdropColorFilter = remember {
        ColorFilter.colorMatrix(
            ColorMatrix().apply { setToSaturation(1.7f) },
        )
    }

    LaunchedEffect(homeUiState.sections.firstOrNull()?.key, onFirstCatalogRendered) {
        if (firstCatalogReported || homeUiState.sections.isEmpty()) return@LaunchedEffect
        firstCatalogReported = true
        onFirstCatalogRendered?.invoke()
    }

    val visibleCollections = remember(collections) {
        collections.filter { it.folders.isNotEmpty() }
    }
    val collectionsMap = remember(visibleCollections) {
        visibleCollections.associateBy { "collection_${it.id}" }
    }
    val sectionsMap = remember(homeUiState.sections) {
        homeUiState.sections.associateBy(HomeCatalogSection::key)
    }
    val enabledHomeItems = remember(homeSettingsUiState.items) {
        homeSettingsUiState.items.filter { it.enabled }
    }
    val hasRenderableCollectionRows = remember(enabledHomeItems, collectionsMap) {
        enabledHomeItems.any { item ->
            item.isCollection && collectionsMap[item.key] != null
        }
    }

    val tvModeEnabled = homeSettingsUiState.tvModeEnabled && isDesktop
    val heroAmbientBackgroundEnabled =
        homeSettingsUiState.heroAmbientBackgroundEnabled && isDesktop && showHeroSlot
    val immersiveCatalogModeEnabled =
        homeSettingsUiState.immersiveCatalogModeEnabled && isDesktop && showHeroSlot
    val heroFocusable = showHeroSlot
    val tvFocus = remember { HomeTvFocusState() }
    val tvFocusRequester = remember { FocusRequester() }
    val tvCoroutineScope = rememberCoroutineScope()
    val mouseActivity = rememberMouseActivityState()
    var immersiveRowIndex by remember { mutableStateOf(0) }
    var immersiveWheelLocked by remember { mutableStateOf(false) }

    val tvRows = remember(
        continueWatchingPreferences.isVisible,
        continueWatchingItems,
        enabledHomeItems,
        collectionsMap,
        sectionsMap,
        onContinueWatchingClick,
        onFolderClick,
        onPosterClick,
    ) {
        buildList {
            if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                add(
                    HomeTvRow(
                        itemCount = continueWatchingItems.size,
                        metaItems = null,
                        onEnter = { index ->
                            continueWatchingItems.getOrNull(index)?.let { onContinueWatchingClick?.invoke(it) }
                        },
                    ),
                )
            }
            enabledHomeItems.forEach { settingsItem ->
                if (settingsItem.isCollection) {
                    val collection = collectionsMap[settingsItem.key]
                    if (collection != null) {
                        val collectionHeroItem = collection.homeHeroPreview()
                        add(
                            HomeTvRow(
                                itemCount = collection.folders.size,
                                metaItems = collectionHeroItem?.let { heroItem ->
                                    List(collection.folders.size) { heroItem }
                                },
                                onEnter = { index ->
                                    collection.folders.getOrNull(index)?.let {
                                        onFolderClick?.invoke(collection.id, it.id)
                                    }
                                },
                            ),
                        )
                    }
                } else {
                    val section = sectionsMap[settingsItem.key]
                    if (section != null && section.items.isNotEmpty()) {
                        val entries = section.items.take(HOME_CATALOG_PREVIEW_LIMIT)
                        add(
                            HomeTvRow(
                                itemCount = entries.size,
                                metaItems = entries,
                                onEnter = { index ->
                                    entries.getOrNull(index)?.let { onPosterClick?.invoke(it) }
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    val tvSectionCount = (if (heroFocusable) 1 else 0) + tvRows.size
    LaunchedEffect(tvRows.size) {
        immersiveRowIndex = immersiveRowIndex.coerceIn(0, (tvRows.size - 1).coerceAtLeast(0))
    }

    fun tvRowIndexForSection(sectionIndex: Int): Int =
        if (heroFocusable) sectionIndex - 1 else sectionIndex

    fun tvLazyItemIndexForSection(sectionIndex: Int): Int {
        if (heroFocusable && sectionIndex == 0) return 0
        return tvRowIndexForSection(sectionIndex)
    }

    fun tvItemCountForSection(sectionIndex: Int): Int {
        if (heroFocusable && sectionIndex == 0) return homeUiState.heroItems.size
        return tvRows.getOrNull(tvRowIndexForSection(sectionIndex))?.itemCount ?: 0
    }

    LaunchedEffect(tvSectionCount) {
        if (tvSectionCount <= 0) {
            tvFocus.sectionIndex = 0
            tvFocus.itemIndex = 0
        } else if (tvFocus.sectionIndex > tvSectionCount - 1) {
            tvFocus.sectionIndex = tvSectionCount - 1
            tvFocus.itemIndex = 0
        }
    }

    LaunchedEffect(tvModeEnabled, immersiveCatalogModeEnabled) {
        delay(50)
        try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
    }
    val immersiveRows = remember(
        continueWatchingPreferences.isVisible,
        continueWatchingItems,
        enabledHomeItems,
        collectionsMap,
        sectionsMap,
    ) {
        buildList<HomeCatalogSettingsItem?> {
            if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                add(null)
            }
            enabledHomeItems.forEach { settingsItem ->
                val isRenderable = if (settingsItem.isCollection) {
                    collectionsMap[settingsItem.key]?.folders?.isNotEmpty() == true
                } else {
                    sectionsMap[settingsItem.key]?.items?.isNotEmpty() == true
                }
                if (isRenderable) add(settingsItem)
            }
        }
    }

    val tvFocusedRowIndex = when {
        immersiveCatalogModeEnabled -> immersiveRowIndex
        isDesktop -> tvRowIndexForSection(tvFocus.sectionIndex)
        else -> -1
    }
    val tvFocusedHeroItem = if (tvFocusedRowIndex >= 0) {
        tvRows.getOrNull(tvFocusedRowIndex)?.metaItems?.getOrNull(tvFocus.itemIndex)
    } else {
        null
    }
    val immersiveMetadataPrefetchItems = if (immersiveCatalogModeEnabled) {
        buildList {
            tvRows.getOrNull(immersiveRowIndex)?.metaItems?.let(::addAll)
            tvRows.getOrNull(immersiveRowIndex + 1)?.metaItems?.let(::addAll)
        }
    } else {
        emptyList()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(tvFocusRequester)
            .focusable()
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { _ ->
                try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
            }
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.S -> {
                        if (event.type == KeyEventType.KeyUp) {
                            onNavigateToSearch?.invoke()
                        }
                        true
                    }
                    Key.L -> {
                        if (event.type == KeyEventType.KeyUp) {
                            onNavigateToLibrary?.invoke()
                        }
                        true
                    }
                    else -> false
                }
            }
            .then(
                if (isDesktop) {
                    Modifier
                        .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { event ->
                            mouseActivity.onMouseMoved(event.changes.first().position)
                        }
                        // Enter fires before Move when the cursor first crosses into a child;
                        // use Initial pass so isMouseActive is set before child Enter handlers run.
                        .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { event ->
                            mouseActivity.onMouseMoved(event.changes.first().position)
                        }
                        .then(
                            if (immersiveCatalogModeEnabled) {
                                Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
                                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                                    val direction = change.scrollDelta.y.compareTo(0f)
                                    if (direction != 0) {
                                        change.consume()
                                        if (!immersiveWheelLocked && tvRows.isNotEmpty()) {
                                            immersiveWheelLocked = true
                                            immersiveRowIndex = (immersiveRowIndex + direction)
                                                .coerceIn(0, tvRows.lastIndex)
                                            tvFocus.sectionIndex =
                                                immersiveRowIndex + if (heroFocusable) 1 else 0
                                            tvFocus.itemIndex = tvFocus.itemIndex.coerceIn(
                                                0,
                                                (tvRows[immersiveRowIndex].itemCount - 1).coerceAtLeast(0),
                                            )
                                            tvCoroutineScope.launch {
                                                delay(220)
                                                immersiveWheelLocked = false
                                            }
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            },
                        )
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    mouseActivity.onKeyboardNavigation()
                                    if (immersiveCatalogModeEnabled) {
                                        immersiveRowIndex = (immersiveRowIndex + 1)
                                            .coerceAtMost((tvRows.size - 1).coerceAtLeast(0))
                                        tvFocus.sectionIndex = immersiveRowIndex + if (heroFocusable) 1 else 0
                                    } else {
                                        tvFocus.moveSection(1, tvSectionCount)
                                    }
                                    tvFocus.itemIndex = tvFocus.itemIndex
                                        .coerceIn(0, (tvItemCountForSection(tvFocus.sectionIndex) - 1).coerceAtLeast(0))
                                    if (!immersiveCatalogModeEnabled) tvCoroutineScope.launch {
                                        homeListState.animateScrollToItem(tvLazyItemIndexForSection(tvFocus.sectionIndex))
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    mouseActivity.onKeyboardNavigation()
                                    if (immersiveCatalogModeEnabled) {
                                        immersiveRowIndex = (immersiveRowIndex - 1).coerceAtLeast(0)
                                        tvFocus.sectionIndex = immersiveRowIndex + if (heroFocusable) 1 else 0
                                    } else {
                                        tvFocus.moveSection(-1, tvSectionCount)
                                    }
                                    tvFocus.itemIndex = tvFocus.itemIndex
                                        .coerceIn(0, (tvItemCountForSection(tvFocus.sectionIndex) - 1).coerceAtLeast(0))
                                    if (!immersiveCatalogModeEnabled) tvCoroutineScope.launch {
                                        homeListState.animateScrollToItem(tvLazyItemIndexForSection(tvFocus.sectionIndex))
                                    }
                                    true
                                }
                                Key.DirectionRight -> {
                                    mouseActivity.onKeyboardNavigation()
                                    tvFocus.moveItem(1, tvItemCountForSection(tvFocus.sectionIndex))
                                    true
                                }
                                Key.DirectionLeft -> {
                                    mouseActivity.onKeyboardNavigation()
                                    tvFocus.moveItem(-1, tvItemCountForSection(tvFocus.sectionIndex))
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    if (heroFocusable && tvFocus.sectionIndex == 0) {
                                        homeUiState.heroItems.getOrNull(tvFocus.itemIndex)?.let { onPosterClick?.invoke(it) }
                                    } else {
                                        tvRows.getOrNull(tvRowIndexForSection(tvFocus.sectionIndex))
                                            ?.onEnter
                                            ?.invoke(tvFocus.itemIndex)
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        if (heroAmbientBackgroundEnabled) {
            val ambientAccent by animateColorAsState(
                targetValue = activeHeroAccent ?: MaterialTheme.colorScheme.background,
                animationSpec = tween(durationMillis = 650),
                label = "home_hero_ambient_accent",
            )
            Crossfade(
                targetState = activeHeroBackdrop,
                animationSpec = tween(durationMillis = 650),
                label = "home_hero_ambient_background",
                modifier = Modifier.fillMaxSize(),
            ) { backdrop ->
                Box(modifier = Modifier.fillMaxSize()) {
                    if (!backdrop.isNullOrBlank()) {
                        NuvioAsyncImage(
                            model = backdrop,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = 0.58f
                                    scaleX = 1.18f
                                    scaleY = 1.18f
                                }
                                .blur(72.dp),
                            contentScale = ContentScale.Crop,
                            colorFilter = ambientBackdropColorFilter,
                            onSuccess = { state ->
                                if (activeHeroBackdrop == backdrop) {
                                    activeHeroAccent = extractHeroAccentColor(state.result.image)
                                }
                            },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to ambientAccent.copy(alpha = 0.46f),
                                        0.42f to ambientAccent.copy(alpha = 0.32f),
                                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                    ),
                                ),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.38f)),
                    )
                }
            }
        }

        val homeSectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidth.value)
        val posterCardStyle = rememberPosterCardStyleUiState()
        val continueWatchingCardHeight = remember(posterCardStyle.widthDp) {
            continueWatchingLandscapeCardHeight(posterCardStyle.widthDp)
        }
        val nativeBottomNavigationOverlayHeight =
            if (LocalNuvioBottomNavigationOverlayPadding.current > 0.dp) {
                nuvioSafeBottomPadding()
            } else {
                0.dp
            }
        val mobileHeroBelowSectionHeightHint = remember(
            maxWidth.value,
            continueWatchingPreferences.isVisible,
            continueWatchingPreferences.style,
            continueWatchingItems.isNotEmpty(),
            continueWatchingLayout,
            continueWatchingCardHeight,
            nativeBottomNavigationOverlayHeight,
        ) {
            heroMobileBelowSectionHeightHint(
                maxWidthDp = maxWidth.value,
                continueWatchingVisible = continueWatchingPreferences.isVisible,
                hasContinueWatchingItems = continueWatchingItems.isNotEmpty(),
                continueWatchingStyle = continueWatchingPreferences.style,
                continueWatchingLayout = continueWatchingLayout,
                continueWatchingCardHeight = continueWatchingCardHeight,
                bottomNavigationOverlayHeight = nativeBottomNavigationOverlayHeight,
            )
        }
        val immersiveShelfHeight = (maxHeight * 0.43f).coerceIn(300.dp, 440.dp)

        val renderHero: @Composable (LazyListState?) -> Unit = { heroListState ->
            when {
                showHeroSkeleton -> HomeSkeletonHero(
                    modifier = Modifier,
                    viewportHeight = maxHeight,
                    mobileBelowSectionHeightHint = mobileHeroBelowSectionHeightHint,
                    sectionPadding = if (isDesktop) homeSectionPadding else null,
                )

                homeUiState.heroItems.isNotEmpty() -> HomeHeroSection(
                    items = homeUiState.heroItems,
                    modifier = Modifier,
                    viewportHeight = maxHeight,
                    mobileBelowSectionHeightHint = mobileHeroBelowSectionHeightHint,
                    sectionPadding = if (isDesktop && !immersiveCatalogModeEnabled) homeSectionPadding else null,
                    listState = heroListState,
                    focusedItem = tvFocusedHeroItem,
                    metadataPrefetchItems = immersiveMetadataPrefetchItems,
                    heightOverride = if (immersiveCatalogModeEnabled) maxHeight else null,
                    roundedBottomCorners =
                        !heroAmbientBackgroundEnabled && !immersiveCatalogModeEnabled,
                    immersiveMode = immersiveCatalogModeEnabled,
                    tvMode = tvModeEnabled,
                    immersiveContentBottomPadding = immersiveShelfHeight - 20.dp,
                    onActiveItemChanged = { item ->
                        activeHeroBackdrop = item.banner ?: item.poster
                    },
                    onCastClick = onCastClick,
                    onItemClick = { item ->
                        if (item.type != COLLECTION_HERO_TYPE) {
                            onPosterClick?.invoke(item)
                        }
                    },
                )

                else -> HomeHeroReservedSpace(
                    modifier = Modifier,
                    viewportHeight = maxHeight,
                    mobileBelowSectionHeightHint = mobileHeroBelowSectionHeightHint,
                    heightOverride = if (immersiveCatalogModeEnabled) maxHeight else null,
                    roundedBottomCorners =
                        !heroAmbientBackgroundEnabled && !immersiveCatalogModeEnabled,
                )
            }
        }

        val rowsContent: LazyListScope.() -> Unit = {
            when {
                !hasActiveAddons && !hasRenderableCollectionRows -> {
                    if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                        item {
                            HomeContinueWatchingSection(
                                items = continueWatchingItems,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                modifier = Modifier.padding(bottom = 12.dp),
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                onItemClick = onContinueWatchingClick,
                                onItemLongPress = onContinueWatchingLongPress,
                            )
                        }
                    }
                    item {
                        HomeEmptyStateCard(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            title = stringResource(Res.string.compose_search_empty_no_active_addons_title),
                            message = stringResource(Res.string.home_empty_no_active_addons_message),
                        )
                    }
                }

                homeUiState.isLoading && homeUiState.sections.isEmpty() && !hasRenderableCollectionRows -> {
                    if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                        item {
                            HomeContinueWatchingSection(
                                items = continueWatchingItems,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                modifier = Modifier.padding(bottom = 12.dp),
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                onItemClick = onContinueWatchingClick,
                                onItemLongPress = onContinueWatchingLongPress,
                            )
                        }
                    }
                    items(3) {
                        HomeSkeletonRow(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            showHeaderAccent = !homeSettingsUiState.hideCatalogUnderline,
                        )
                    }
                }

                homeUiState.sections.isEmpty() && homeUiState.heroItems.isEmpty() &&
                    (!continueWatchingPreferences.isVisible || continueWatchingItems.isEmpty()) &&
                    !hasRenderableCollectionRows -> {
                    item {
                        if (networkStatusUiState.isOfflineLike) {
                            NuvioNetworkOfflineCard(
                                condition = networkStatusUiState.condition,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                onRetry = {
                                    NetworkStatusRepository.requestRefresh(force = true)
                                    HomeRepository.refresh(addonsUiState.addons.enabledAddons(), force = true)
                                },
                            )
                        } else {
                            HomeEmptyStateCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                title = stringResource(Res.string.home_empty_no_rows_title),
                                message = homeUiState.errorMessage
                                    ?: stringResource(Res.string.home_empty_no_rows_message),
                            )
                        }
                    }
                }

                else -> {
                    var tvRowCursor = 0

                    if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                        val rowIndex = tvRowCursor++
                        val sectionIndex = if (heroFocusable) rowIndex + 1 else rowIndex
                        item {
                            HomeContinueWatchingSection(
                                items = continueWatchingItems,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                modifier = Modifier.padding(bottom = 12.dp),
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                focusedItemIndex = if (tvFocusedRowIndex == rowIndex) tvFocus.itemIndex else null,
                                onHoverItem = if (isDesktop) {
                                    { itemIndex ->
                                        if (mouseActivity.isMouseActive) {
                                            tvFocus.sectionIndex = sectionIndex
                                            tvFocus.itemIndex = itemIndex
                                        }
                                    }
                                } else {
                                    null
                                },
                                onItemClick = onContinueWatchingClick,
                                onItemLongPress = onContinueWatchingLongPress,
                            )
                        }
                    }

                    enabledHomeItems.forEach { settingsItem ->
                        if (settingsItem.isCollection) {
                            val collection = collectionsMap[settingsItem.key]
                            if (collection != null) {
                                val rowIndex = tvRowCursor++
                                val sectionIndex = if (heroFocusable) rowIndex + 1 else rowIndex
                                item(key = settingsItem.key) {
                                    HomeCollectionRowSection(
                                        collection = collection,
                                        modifier = Modifier.padding(bottom = 12.dp),
                                        sectionPadding = homeSectionPadding,
                                        animateGifs = animateCollectionGifs,
                                        focusedItemIndex = if (tvFocusedRowIndex == rowIndex) tvFocus.itemIndex else null,
                                        onHoverItem = if (isDesktop) {
                                            { itemIndex ->
                                                if (mouseActivity.isMouseActive) {
                                                    tvFocus.sectionIndex = sectionIndex
                                                    tvFocus.itemIndex = itemIndex
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                        onFolderClick = onFolderClick,
                                    )
                                }
                            }
                        } else {
                            val section = sectionsMap[settingsItem.key]
                            if (section != null && section.items.isNotEmpty()) {
                                val rowIndex = tvRowCursor++
                                val sectionIndex = if (heroFocusable) rowIndex + 1 else rowIndex
                                item(key = settingsItem.key) {
                                    HomeCatalogRowSection(
                                        section = section,
                                        entries = section.items.take(HOME_CATALOG_PREVIEW_LIMIT),
                                        modifier = Modifier.padding(bottom = 12.dp),
                                        sectionPadding = homeSectionPadding,
                                        focusedItemIndex = if (tvFocusedRowIndex == rowIndex) tvFocus.itemIndex else null,
                                        onHoverItem = if (isDesktop) {
                                            { itemIndex ->
                                                if (mouseActivity.isMouseActive) {
                                                    tvFocus.sectionIndex = sectionIndex
                                                    tvFocus.itemIndex = itemIndex
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                        onViewAllClick = if (section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)) {
                                            onCatalogClick?.let { { it(section) } }
                                        } else {
                                            null
                                        },
                                        watchedKeys = watchedUiState.watchedKeys,
                                        onPosterClick = onPosterClick,
                                        onPosterLongClick = onPosterLongClick,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (immersiveCatalogModeEnabled && tvRows.isNotEmpty()) {
            val activeSettingsItem = immersiveRows.getOrNull(immersiveRowIndex)
            Box(modifier = Modifier.fillMaxSize()) {
                renderHero(null)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(immersiveShelfHeight)
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.30f to MaterialTheme.colorScheme.background.copy(alpha = 0.28f),
                                    0.62f to MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                                    1f to MaterialTheme.colorScheme.background,
                                ),
                            ),
                        )
                        .padding(top = 68.dp, bottom = 12.dp),
                ) {
                    when {
                        activeSettingsItem == null -> HomeContinueWatchingSection(
                            items = continueWatchingItems,
                            style = continueWatchingPreferences.style,
                            useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                            blurNextUp = continueWatchingPreferences.blurNextUp,
                            sectionPadding = homeSectionPadding,
                            layout = continueWatchingLayout,
                            focusedItemIndex = tvFocus.itemIndex,
                            onHoverItem = { itemIndex -> tvFocus.itemIndex = itemIndex },
                            onItemClick = onContinueWatchingClick,
                            onItemLongPress = onContinueWatchingLongPress,
                        )

                        activeSettingsItem.isCollection -> {
                            collectionsMap[activeSettingsItem.key]?.let { collection ->
                                HomeCollectionRowSection(
                                    collection = collection,
                                    sectionPadding = homeSectionPadding,
                                    animateGifs = animateCollectionGifs,
                                    focusedItemIndex = tvFocus.itemIndex,
                                    onHoverItem = { itemIndex -> tvFocus.itemIndex = itemIndex },
                                    onFolderClick = onFolderClick,
                                )
                            }
                        }

                        else -> {
                            sectionsMap[activeSettingsItem.key]?.let { section ->
                                HomeCatalogRowSection(
                                    section = section,
                                    entries = section.items.take(HOME_CATALOG_PREVIEW_LIMIT),
                                    sectionPadding = homeSectionPadding,
                                    focusedItemIndex = tvFocus.itemIndex,
                                    onHoverItem = { itemIndex -> tvFocus.itemIndex = itemIndex },
                                    onViewAllClick = if (section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)) {
                                        onCatalogClick?.let { { it(section) } }
                                    } else {
                                        null
                                    },
                                    watchedKeys = watchedUiState.watchedKeys,
                                    onPosterClick = onPosterClick,
                                    onPosterLongClick = onPosterLongClick,
                                )
                            }
                        }
                    }
                }
            }
        } else if (tvModeEnabled && showHeroSlot) {
            val heroLayout = homeHeroLayout(
                maxWidthDp = maxWidth.value,
                viewportHeightDp = maxHeight.value,
                mobileBelowSectionHeightHintDp = mobileHeroBelowSectionHeightHint?.value,
                preferDesktopLayout = true,
                heightMultiplier = 1.25f,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                NuvioScreen(
                    modifier = Modifier.fillMaxSize(),
                    horizontalPadding = 0.dp,
                    topPadding = heroLayout.heroHeight,
                    backgroundColor = if (heroAmbientBackgroundEnabled) Color.Transparent else null,
                    listState = homeListState,
                    content = rowsContent,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heroLayout.heroHeight)
                        .align(Alignment.TopStart),
                ) {
                    renderHero(null)
                }
            }
        } else {
            NuvioScreen(
                modifier = Modifier.fillMaxSize(),
                horizontalPadding = 0.dp,
                topPadding = if (showHeroSlot) 0.dp else null,
                backgroundColor = if (heroAmbientBackgroundEnabled) Color.Transparent else null,
                listState = homeListState,
            ) {
                if (showHeroSlot) {
                    item { renderHero(homeListState) }
                }
                rowsContent()
            }
        }
    }
}

private const val HOME_CATALOG_PREVIEW_LIMIT = 18
private const val COLLECTION_HERO_TYPE = "collection"
internal const val HomeContinueWatchingMaxRecentProgressItems = 300
internal const val HomeNextUpInitialResolutionLimit = 32
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
private const val OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS = 3L * 60L * 1000L
private const val NEXT_UP_RESOLUTION_CONCURRENCY = 4
private const val NEXT_UP_RESOLUTION_BATCH_SIZE = NEXT_UP_RESOLUTION_CONCURRENCY

private fun com.nuvio.app.features.collection.Collection.homeHeroPreview(): MetaPreview? {
    val backdrop = backdropImageUrl?.trim()?.takeIf(String::isNotBlank) ?: return null
    return MetaPreview(
        id = "collection:$id",
        type = COLLECTION_HERO_TYPE,
        name = title,
        banner = backdrop,
        posterShape = PosterShape.Landscape,
    )
}

internal fun filterEntriesForTraktContinueWatchingWindow(
    entries: List<WatchProgressEntry>,
    isTraktProgressActive: Boolean,
    daysCap: Int,
    nowEpochMs: Long,
): List<WatchProgressEntry> {
    if (!isTraktProgressActive) return entries
    val normalizedDaysCap = normalizeTraktContinueWatchingDaysCap(daysCap)
    if (normalizedDaysCap == TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL) return entries

    val cutoffMs = nowEpochMs - (normalizedDaysCap.toLong() * MILLIS_PER_DAY)
    return entries.filter { entry -> entry.lastUpdatedEpochMs >= cutoffMs }
}

internal fun filterHomeNextUpCandidatesForTraktContinueWatchingWindow(
    candidates: List<CompletedSeriesCandidate>,
    isTraktProgressActive: Boolean,
    daysCap: Int,
    nowEpochMs: Long,
): List<CompletedSeriesCandidate> {
    if (!isTraktProgressActive) return candidates
    val normalizedDaysCap = normalizeTraktContinueWatchingDaysCap(daysCap)
    if (normalizedDaysCap == TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL) return candidates

    val cutoffMs = nowEpochMs - (normalizedDaysCap.toLong() * MILLIS_PER_DAY)
    return candidates.filter { candidate -> candidate.markedAtEpochMs >= cutoffMs }
}

internal fun buildHomeNextUpSeedCandidates(
    progressEntries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
    isTraktProgressActive: Boolean,
    preferFurthestEpisode: Boolean,
    nowEpochMs: Long,
): List<CompletedSeriesCandidate> {
    val progressSeeds = progressEntries
        .asSequence()
        .filter { entry -> entry.parentMetaType.isSeriesTypeForContinueWatching() }
        .filter { entry -> entry.seasonNumber != null && entry.episodeNumber != null && entry.seasonNumber != 0 }
        .filter { entry -> !isMalformedNextUpSeedContentId(entry.parentMetaId) }
        .filter { entry ->
            if (isTraktProgressActive) {
                shouldUseAsTraktNextUpSeed(entry = entry, nowEpochMs = nowEpochMs)
            } else {
                entry.shouldUseAsCompletedSeedForContinueWatching()
            }
        }
        .toList()
    val watchedSeeds = watchedItems.filter { item ->
        item.type.isSeriesTypeForContinueWatching() &&
            item.season != null &&
            item.episode != null &&
            item.season != 0 &&
            !isMalformedNextUpSeedContentId(item.id)
    }

    return WatchingState.latestCompletedBySeries(
        progressEntries = progressSeeds,
        watchedItems = watchedSeeds,
        preferFurthestEpisode = preferFurthestEpisode,
    ).mapNotNull { (content, completed) ->
        if (!content.type.isSeriesTypeForContinueWatching()) return@mapNotNull null
        if (completed.seasonNumber == 0) return@mapNotNull null
        if (isMalformedNextUpSeedContentId(content.id)) return@mapNotNull null
        CompletedSeriesCandidate(
            content = content,
            seasonNumber = completed.seasonNumber,
            episodeNumber = completed.episodeNumber,
            markedAtEpochMs = completed.markedAtEpochMs,
        )
    }.sortedWith(
        compareByDescending<CompletedSeriesCandidate> { candidate -> candidate.markedAtEpochMs }
            .thenByDescending { candidate -> candidate.seasonNumber }
            .thenByDescending { candidate -> candidate.episodeNumber },
    )
}

internal fun filterNextUpItemsByCurrentSeeds(
    nextUpItemsBySeries: Map<String, Pair<Long, ContinueWatchingItem>>,
    activeSeedContentIds: Set<String>,
    currentSeedByContentId: Map<String, Pair<Int, Int>>,
    shouldDropItemsWithoutActiveSeed: Boolean,
): Map<String, Pair<Long, ContinueWatchingItem>> =
    nextUpItemsBySeries.filter { (contentId, pair) ->
        if (shouldDropItemsWithoutActiveSeed && contentId !in activeSeedContentIds) {
            return@filter false
        }
        val item = pair.second
        val currentSeed = currentSeedByContentId[contentId] ?: return@filter true
        item.nextUpSeedSeasonNumber == currentSeed.first &&
            item.nextUpSeedEpisodeNumber == currentSeed.second
    }

private suspend fun resolveHomeNextUpCandidate(
    completedEntry: CompletedSeriesCandidate,
    watchProgressEntries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
    todayIsoDate: String,
    preferFurthestEpisode: Boolean,
    showUnairedNextUp: Boolean,
    dismissedNextUpKeys: Set<String>,
    isTraktProgressActive: Boolean,
): Pair<String, Pair<Long, ContinueWatchingItem>>? {
    val contentId = completedEntry.content.id
    val meta = try {
        MetaDetailsRepository.fetch(
            type = completedEntry.content.type,
            id = contentId,
        )
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
    }
    if (meta == null) return null

    val resolvedProgressEntries = if (isTraktProgressActive) {
        remapTraktProgressEntries(watchProgressEntries, contentId)
    } else {
        watchProgressEntries
    }
    val resolvedWatchedItems = if (isTraktProgressActive) {
        remapTraktWatchedItems(watchedItems, contentId)
    } else {
        watchedItems
    }

    val action = meta.seriesPrimaryAction(
        content = completedEntry.content,
        entries = resolvedProgressEntries,
        watchedItems = resolvedWatchedItems,
        todayIsoDate = todayIsoDate,
        preferFurthestEpisode = preferFurthestEpisode,
        showUnairedNextUp = showUnairedNextUp,
    )
    if (action == null) return null
    if (action.resumePositionMs != null) return null

    val nextEpisode = meta.videoForSeriesAction(action)
    if (nextEpisode == null) return null
    val item = completedEntry.toContinueWatchingSeed(meta)
        .toUpNextContinueWatchingItem(nextEpisode)
    if (nextUpDismissKey(item.parentMetaId, item.nextUpSeedSeasonNumber, item.nextUpSeedEpisodeNumber) in dismissedNextUpKeys) {
        return null
    }

    val sortTimestamp = if (item.isReleaseAlert) {
        com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs(item.released) ?: completedEntry.markedAtEpochMs
    } else {
        completedEntry.markedAtEpochMs
    }
    return contentId to (sortTimestamp to item)
}

private fun MetaDetails.videoForSeriesAction(action: SeriesPrimaryAction): MetaVideo? {
    if (action.seasonNumber != null && action.episodeNumber != null) {
        videos.firstOrNull { video ->
            video.season == action.seasonNumber &&
                video.episode == action.episodeNumber
        }?.let { return it }
    }
    return videos.firstOrNull { video ->
        com.nuvio.app.features.watchprogress.buildPlaybackVideoId(
            parentMetaId = id,
            seasonNumber = video.season,
            episodeNumber = video.episode,
            fallbackVideoId = video.id,
        ) == action.videoId || video.id == action.videoId
    }
}

private fun shouldUseAsTraktNextUpSeed(
    entry: WatchProgressEntry,
    nowEpochMs: Long,
): Boolean {
    if (!entry.shouldUseAsCompletedSeedForContinueWatching()) return false
    if (entry.source != WatchProgressSourceTraktPlayback) return true

    val ageMs = nowEpochMs - entry.lastUpdatedEpochMs
    return ageMs in 0..OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS
}

private fun shouldTreatAsActiveInProgressForNextUpSuppression(
    progress: WatchProgressEntry,
    latestCompletedAt: Long?,
): Boolean {
    if (!progress.shouldTreatAsInProgressForContinueWatching()) return false
    if (latestCompletedAt == null || latestCompletedAt == Long.MIN_VALUE) return true
    return progress.lastUpdatedEpochMs >= latestCompletedAt
}

private fun heroMobileBelowSectionHeightHint(
    maxWidthDp: Float,
    continueWatchingVisible: Boolean,
    hasContinueWatchingItems: Boolean,
    continueWatchingStyle: ContinueWatchingSectionStyle,
    continueWatchingLayout: ContinueWatchingLayout,
    continueWatchingCardHeight: Dp,
    bottomNavigationOverlayHeight: Dp,
): Dp? {
    if (maxWidthDp >= 600f || !continueWatchingVisible || !hasContinueWatchingItems) return null

    val sectionHeight = when (continueWatchingStyle) {
        ContinueWatchingSectionStyle.Card -> continueWatchingCardHeight + 56.dp
        ContinueWatchingSectionStyle.Wide -> continueWatchingLayout.wideCardHeight + 56.dp
        ContinueWatchingSectionStyle.Poster ->
            continueWatchingLayout.posterCardHeight + continueWatchingLayout.posterTitleBlockHeight + 70.dp
    }
    return sectionHeight + bottomNavigationOverlayHeight
}

internal fun buildHomeContinueWatchingItems(
    visibleEntries: List<WatchProgressEntry>,
    cachedInProgressByVideoId: Map<String, ContinueWatchingItem> = emptyMap(),
    nextUpItemsBySeries: Map<String, Pair<Long, ContinueWatchingItem>>,
    nextUpSuppressedSeriesIds: Set<String>? = null,
    sortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT,
    todayIsoDate: String = "",
    cloudLibraryUiState: CloudLibraryUiState? = null,
): List<ContinueWatchingItem> {
    val suppressedSeriesIds = nextUpSuppressedSeriesIds
        ?: visibleEntries
            .asSequence()
            .filter { entry -> entry.parentMetaType.isSeriesTypeForContinueWatching() }
            .map { entry -> entry.parentMetaId }
            .filter(String::isNotBlank)
            .toSet()

    val candidates = buildList {
        addAll(
            visibleEntries.map { entry ->
                val liveItem = entry.toContinueWatchingItem()
                HomeContinueWatchingCandidate(
                    lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
                    item = liveItem
                        .withFallbackMetadata(cachedInProgressByVideoId[entry.videoId])
                        .withCloudLibraryMetadata(cloudLibraryUiState),
                    isProgressEntry = true,
                )
            },
        )
        addAll(
            nextUpItemsBySeries.values.mapNotNull { (lastUpdatedEpochMs, item) ->
                if (item.parentMetaId in suppressedSeriesIds) return@mapNotNull null
                HomeContinueWatchingCandidate(
                    lastUpdatedEpochMs = lastUpdatedEpochMs,
                    item = item,
                    isProgressEntry = false,
                )
            },
        )
    }

    // Deduplicate by series/content id first (order-stable)
    val seen = mutableSetOf<String>()
    val deduplicated = candidates
        .sortedWith(
            compareByDescending<HomeContinueWatchingCandidate> { it.lastUpdatedEpochMs }
                .thenByDescending { it.isProgressEntry },
        )
        .filter { candidate -> candidate.item.shouldDisplayInContinueWatching() }
        .filter { candidate ->
            val key = candidate.item.parentMetaId.ifBlank { candidate.item.videoId }
            seen.add(key)
        }

    return when (sortMode) {
        ContinueWatchingSortMode.DEFAULT -> deduplicated.map(HomeContinueWatchingCandidate::item)
        ContinueWatchingSortMode.STREAMING_STYLE -> applyStreamingStyleSort(deduplicated, todayIsoDate)
    }
}

private fun applyStreamingStyleSort(
    candidates: List<HomeContinueWatchingCandidate>,
    todayIsoDate: String,
): List<ContinueWatchingItem> {
    val (released, unreleased) = candidates.partition { candidate ->
        val item = candidate.item
        if (!item.isNextUp) {
            true // in-progress items are always "released"
        } else {
            val itemReleased = item.released
            if (itemReleased.isNullOrBlank() || todayIsoDate.isBlank()) {
                true // no date info → treat as released
            } else {
                isReleasedBy(todayIsoDate = todayIsoDate, releasedDate = itemReleased)
            }
        }
    }

    // Released: most recently watched first (already sorted by dedup pass)
    val sortedReleased = released.map(HomeContinueWatchingCandidate::item)

    // Unaired: soonest air date first; unknown dates go to the end
    val sortedUnreleased = unreleased
        .sortedWith { a, b ->
            val dateA = a.item.released?.takeIf { it.isNotBlank() }
            val dateB = b.item.released?.takeIf { it.isNotBlank() }
            when {
                dateA == null && dateB == null -> 0
                dateA == null -> 1
                dateB == null -> -1
                else -> dateA.compareTo(dateB)
            }
        }
        .map(HomeContinueWatchingCandidate::item)

    return sortedReleased + sortedUnreleased
}

internal data class CompletedSeriesCandidate(
    val content: WatchingContentRef,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val markedAtEpochMs: Long,
)

private data class HomeContinueWatchingCandidate(
    val lastUpdatedEpochMs: Long,
    val item: ContinueWatchingItem,
    val isProgressEntry: Boolean,
)

private fun saveContinueWatchingSnapshots(
    nextUpItemsBySeries: Map<String, Pair<Long, ContinueWatchingItem>>,
    visibleContinueWatchingEntries: List<WatchProgressEntry>,
    todayIsoDate: String,
    seedLastWatchedMap: Map<String, Long>,
) {
    val nextUpCache = nextUpItemsBySeries.mapNotNull { (contentId, pair) ->
        val item = pair.second
        CachedNextUpItem(
            contentId = contentId,
            contentType = item.parentMetaType,
            name = item.title,
            poster = item.poster,
            backdrop = item.background,
            logo = item.logo,
            videoId = item.videoId,
            season = item.seasonNumber,
            episode = item.episodeNumber,
            episodeTitle = item.episodeTitle,
            episodeThumbnail = item.episodeThumbnail,
            pauseDescription = item.pauseDescription,
            released = item.released,
            hasAired = item.released?.let { released ->
                isReleasedBy(todayIsoDate = todayIsoDate, releasedDate = released)
            } ?: true,
            lastWatched = seedLastWatchedMap[contentId] ?: pair.first,
            sortTimestamp = pair.first,
            seedSeason = item.nextUpSeedSeasonNumber,
            seedEpisode = item.nextUpSeedEpisodeNumber,
            isReleaseAlert = item.isReleaseAlert,
            isNewSeasonRelease = item.isNewSeasonRelease,
        )
    }
    val inProgressCache = visibleContinueWatchingEntries.map { entry ->
        CachedInProgressItem(
            contentId = entry.parentMetaId,
            contentType = entry.contentType,
            name = entry.title,
            poster = entry.poster,
            backdrop = entry.background,
            logo = entry.logo,
            videoId = entry.videoId,
            season = entry.seasonNumber,
            episode = entry.episodeNumber,
            episodeTitle = entry.episodeTitle,
            episodeThumbnail = entry.episodeThumbnail,
            pauseDescription = entry.pauseDescription,
            position = entry.lastPositionMs,
            duration = entry.durationMs,
            lastWatched = entry.lastUpdatedEpochMs,
            progressPercent = entry.progressPercent,
        )
    }
    ContinueWatchingEnrichmentCache.saveSnapshots(
        nextUp = nextUpCache,
        inProgress = inProgressCache,
    )
}

private fun CompletedSeriesCandidate.toContinueWatchingSeed(meta: com.nuvio.app.features.details.MetaDetails) =
    WatchProgressEntry(
        contentType = content.type,
        parentMetaId = content.id,
        parentMetaType = content.type,
        videoId = "${content.id}:${seasonNumber}:${episodeNumber}",
        title = meta.name,
        logo = meta.logo,
        poster = meta.poster,
        background = meta.background,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        lastPositionMs = 0L,
        durationMs = 0L,
        lastUpdatedEpochMs = markedAtEpochMs,
        isCompleted = true,
    )

private fun ContinueWatchingItem.shouldDisplayInContinueWatching(): Boolean =
    isNextUp || progressFraction < 0.995f

private fun CachedNextUpItem.toContinueWatchingItem(): ContinueWatchingItem? {
    val alertState = com.nuvio.app.features.watchprogress.calculateReleaseAlertState(
        seedLastUpdatedEpochMs = lastWatched,
        seedSeasonNumber = seedSeason,
        nextSeasonNumber = season,
        releasedIso = released,
    )
    return ContinueWatchingItem(
        parentMetaId = contentId,
        parentMetaType = contentType,
        videoId = videoId,
        title = name,
        subtitle = buildContinueWatchingEpisodeSubtitle(
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = episodeTitle,
        ),
        imageUrl = episodeThumbnail ?: backdrop ?: poster,
        logo = logo,
        poster = poster,
        background = backdrop,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
        pauseDescription = pauseDescription,
        released = released,
        isNextUp = true,
        nextUpSeedSeasonNumber = seedSeason,
        nextUpSeedEpisodeNumber = seedEpisode,
        resumePositionMs = 0L,
        resumeProgressFraction = null,
        durationMs = 0L,
        progressFraction = 0f,
        isReleaseAlert = alertState.isReleaseAlert,
        isNewSeasonRelease = alertState.isNewSeasonRelease,
    )
}

private fun CachedInProgressItem.toContinueWatchingItem(): ContinueWatchingItem {
    val explicitResumeProgressFraction = progressPercent
        ?.takeIf { duration <= 0L && it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val normalizedProgressFraction = progressPercent
        ?.let { (it / 100f).coerceIn(0f, 1f) }
        ?: if (duration > 0L) {
            (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    return ContinueWatchingItem(
        parentMetaId = contentId,
        parentMetaType = contentType,
        videoId = videoId,
        title = name,
        subtitle = buildContinueWatchingEpisodeSubtitle(
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = episodeTitle,
        ),
        imageUrl = episodeThumbnail ?: backdrop ?: poster,
        logo = logo,
        poster = poster,
        background = backdrop,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
        pauseDescription = pauseDescription,
        isNextUp = false,
        nextUpSeedSeasonNumber = null,
        nextUpSeedEpisodeNumber = null,
        resumePositionMs = if (explicitResumeProgressFraction != null) 0L else position,
        resumeProgressFraction = explicitResumeProgressFraction,
        durationMs = duration,
        progressFraction = normalizedProgressFraction,
    )
}

private fun ContinueWatchingItem.withFallbackMetadata(
    fallback: ContinueWatchingItem?,
): ContinueWatchingItem {
    if (fallback == null) return this
    val fallbackTitle = fallback.title
        .takeIf { it.isNotBlank() }
        ?.takeUnless { fallback.hasPlaceholderCloudTitle() }

    return copy(
        title = when {
            title.isBlank() -> fallback.title
            hasPlaceholderCloudTitle() && fallbackTitle != null -> fallbackTitle
            else -> title
        },
        subtitle = subtitle.ifBlank { fallback.subtitle },
        imageUrl = imageUrl ?: fallback.imageUrl,
        logo = logo ?: fallback.logo,
        poster = poster ?: fallback.poster,
        background = background ?: fallback.background,
        episodeTitle = episodeTitle ?: fallback.episodeTitle,
        episodeThumbnail = episodeThumbnail ?: fallback.episodeThumbnail,
        pauseDescription = pauseDescription ?: fallback.pauseDescription,
        released = released ?: fallback.released,
    )
}

private fun ContinueWatchingItem.withCloudLibraryMetadata(
    cloudLibraryUiState: CloudLibraryUiState?,
): ContinueWatchingItem {
    if (!isCloudLibraryContinueWatchingItem() || cloudLibraryUiState == null) return this
    val target = cloudLibraryUiState.findPlaybackTargetForProgress(
        contentId = parentMetaId,
        videoId = videoId,
    ) ?: return this
    val fileName = target.file.name.trim().takeIf { it.isNotBlank() }
        ?: target.item.name.trim().takeIf { it.isNotBlank() }
        ?: return this
    return copy(
        title = fileName,
        pauseDescription = pauseDescription
            ?: target.item.name.takeIf { itemName -> itemName.isNotBlank() && itemName != fileName },
    )
}

private fun ContinueWatchingItem.hasPlaceholderCloudTitle(): Boolean {
    if (!isCloudLibraryContinueWatchingItem()) return false
    val normalizedTitle = title.trim()
    return normalizedTitle.equals(parentMetaId, ignoreCase = true) ||
        normalizedTitle.equals(videoId, ignoreCase = true)
}

private fun ContinueWatchingItem.isCloudLibraryContinueWatchingItem(): Boolean =
    parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

private fun WatchProgressEntry.isCloudLibraryProgressEntry(): Boolean =
    contentType.equals(CloudLibraryContentType, ignoreCase = true) ||
        parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)

private suspend fun remapTraktProgressEntries(
    entries: List<WatchProgressEntry>,
    contentId: String,
): List<WatchProgressEntry> {
    return entries.map { entry ->
        if (entry.parentMetaId != contentId) {
            entry
        } else {
            val mapping = TraktEpisodeMappingService.resolveAddonEpisodeMapping(
                contentId = entry.parentMetaId,
                contentType = entry.contentType ?: "series",
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
                episodeTitle = entry.episodeTitle,
            )
            if (mapping != null) {
                entry.copy(
                    seasonNumber = mapping.season,
                    episodeNumber = mapping.episode,
                    videoId = com.nuvio.app.features.watchprogress.buildPlaybackVideoId(
                        parentMetaId = entry.parentMetaId,
                        seasonNumber = mapping.season,
                        episodeNumber = mapping.episode,
                        fallbackVideoId = entry.videoId,
                    ),
                    episodeTitle = mapping.title ?: entry.episodeTitle,
                )
            } else {
                entry
            }
        }
    }
}

private suspend fun remapTraktWatchedItems(
    items: List<WatchedItem>,
    contentId: String,
): List<WatchedItem> {
    return items.map { item ->
        if (item.id != contentId) {
            item
        } else {
            val mapping = TraktEpisodeMappingService.resolveAddonEpisodeMapping(
                contentId = item.id,
                contentType = item.type ?: "series",
                season = item.season,
                episode = item.episode,
            )
            if (mapping != null) {
                item.copy(
                    season = mapping.season,
                    episode = mapping.episode,
                )
            } else {
                item
            }
        }
    }
}
