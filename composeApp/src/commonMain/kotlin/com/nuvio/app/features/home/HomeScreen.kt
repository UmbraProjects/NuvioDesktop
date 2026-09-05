package com.nuvio.app.features.home

import coil3.compose.LocalPlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.nuvio.app.core.ui.nuvioArtworkRequestSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.nuvio.app.core.ui.WasdNavigation
import com.nuvio.app.core.ui.navigationKey
import androidx.compose.ui.input.key.type
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.nuvio.app.isDesktop
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.HeroAmbientBackdrop
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioNetworkOfflineCard
import com.nuvio.app.core.ui.smoothVerticalWheelScroll
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.rememberMouseActivityState
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryDisplaySettingsRepository
import com.nuvio.app.features.library.sortLibrarySections
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.search.DiscoverEmptyStateReason
import com.nuvio.app.features.discover.DiscoverPickerSegment
import com.nuvio.app.features.discover.DISCOVER_PLACEHOLDER_KEY_PREFIX
import com.nuvio.app.features.discover.DiscoverRecommendationsRepository
import com.nuvio.app.features.discover.DiscoverRowBody
import com.nuvio.app.features.discover.DiscoverRowHeader
import com.nuvio.app.features.discover.discoverRowProvenance
import com.nuvio.app.features.discover.rememberDiscoverPostersAlpha
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.cloud.CloudLibraryUiState
import com.nuvio.app.features.cloud.findPlaybackTargetForProgress
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.MetahubService
import com.nuvio.app.features.details.HeroTrailerAudioState
import com.nuvio.app.features.metadata.isAnimeSeasonArtUrl
import com.nuvio.app.features.details.SeriesPrimaryAction
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.streams.StreamPrefetchService
import com.nuvio.app.features.home.components.immersiveShelfScrimStops
import com.nuvio.app.features.home.components.PAGE_ITEM_STEP
import com.nuvio.app.features.home.components.PAGE_SECTION_STEP
import com.nuvio.app.features.home.components.DiscoverRowTitleWithProvenance
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeHeroReservedSpace
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomeBasicTrailerOverlay
import com.nuvio.app.features.home.components.HomeHeroTrailerGate
import com.nuvio.app.features.home.components.HomeHeroPeoplePanelToggleTrigger
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.appShortcutMatches
import com.nuvio.app.features.home.components.HomeHeroTrailerManualTrigger
import com.nuvio.app.features.home.components.HomeTvKey
import com.nuvio.app.features.home.components.HomeTvKeyboardBridge
import com.nuvio.app.features.home.components.HomeSkeletonHero
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.tmdb.HeroImageSource
import com.nuvio.app.features.tmdb.TmdbHeroImageService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.simkl.SIMKL_NO_CW_CUTOFF
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.simklContinueWatchingCutoffMs
import com.nuvio.app.features.simkl.SimklLibraryRepository
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TRAKT_CONTINUE_WATCHING_DAYS_CAP_ALL
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.normalizeTraktContinueWatchingDaysCap
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CachedInProgressItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingArtworkDiagnostics
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
import com.nuvio.app.features.tracking.ContinueWatchingSource
import com.nuvio.app.features.tracking.ContinueWatchingSourceRepository
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
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
import com.nuvio.app.features.home.components.HomeTvRowDot
import com.nuvio.app.features.home.components.HomeTvRowDotStrip
import com.nuvio.app.features.home.components.HomeTvContinueWatchingRowKey
import com.nuvio.app.features.home.components.homeHeroLayout
import com.nuvio.app.features.home.components.homeTvLazyScrollTarget
import androidx.compose.foundation.lazy.LazyListState
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlin.math.roundToInt

/**
 * Session-scoped memory of the Home tab's vertical scroll position. Lets the user
 * return to where they left off after navigating into (and back out of) other
 * screens such as the details view. Intentionally in-memory only: a fresh app
 * launch should start at the top.
 */
private object HomeScrollMemory {
    // Non-immersive (LazyColumn) home layout.
    var firstVisibleItemIndex: Int = 0
    var firstVisibleItemScrollOffset: Int = 0
    // Immersive home layout: the selected catalog row (its vertical "scroll" position).
    var immersiveRowIndex: Int = 0
    // Immersive home layout: the focused item within that row (its horizontal position), so returning
    // from the details screen restores both the row and the position in it.
    var immersiveItemIndex: Int = 0
    // Distinguishes a real selection of row 0/item 0 from the untouched fresh-launch defaults.
    var hasImmersivePosition: Boolean = false
    val continueWatchingRowState = LazyListState()
    var continueWatchingStartupResetApplied: Boolean = false
    val nextUpRowState = LazyListState()
    var nextUpStartupResetApplied: Boolean = false
    val immersiveRowStates = mutableMapOf<String, LazyListState>()
}

/**
 * Session-scoped scroll memory for the Library tab, mirroring [HomeScrollMemory]. The Library tab
 * shares the HomeScreen composable (in Library display mode) but not Home's remembered position,
 * so it needs its own holder to return the user to the row / within-row position they left off at
 * after opening and closing the details screen. In-memory only.
 */
private object LibraryScrollMemory {
    var firstVisibleItemIndex: Int = 0
    var firstVisibleItemScrollOffset: Int = 0
    var immersiveRowIndex: Int = 0
    var immersiveItemIndex: Int = 0
    var hasImmersivePosition: Boolean = false
}

/**
 * Scroll memory for the Discover tab, mirroring [LibraryScrollMemory].
 *
 * Every mode that shares HomeScreen needs its own holder — the position is per tab, not per
 * composable — so adding a mode without adding one of these silently gives that tab no position
 * memory at all, which is how Discover shipped.
 */
private object DiscoverScrollMemory {
    var firstVisibleItemIndex: Int = 0
    var firstVisibleItemScrollOffset: Int = 0
    var immersiveRowIndex: Int = 0
    var immersiveItemIndex: Int = 0
    var hasImmersivePosition: Boolean = false
}


@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    contentMode: HomeContentMode = HomeContentMode.Normal,
    searchQuery: String = "",
    searchSubmitRequests: Flow<String> = emptyFlow(),
    navigateToContentCount: Int = 0,
    animateCollectionGifs: Boolean = true,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onLoadMoreCatalog: ((HomeCatalogSection) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onLocalLibraryPosterClick: ((MetaPreview) -> Unit)? = null,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingPlay: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    onCastClick: ((HeroCastMember) -> Unit)? = null,
    onFirstCatalogRendered: (() -> Unit)? = null,
    onNavigateToSearch: (() -> Unit)? = null,
    onNavigateToLibrary: (() -> Unit)? = null,
    onNavigateToDiscover: (() -> Unit)? = null,
    onNavigateToCalendar: (() -> Unit)? = null,
    onNavigateToHome: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    resumePromptItem: ContinueWatchingItem? = null,
    resumePromptLabel: String = "",
    onResumePromptAction: (() -> Unit)? = null,
    onResumePromptDismiss: (() -> Unit)? = null,
    continueWatchingHeroDismissed: Boolean = false,
    onContinueWatchingHeroDismiss: () -> Unit = {},
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
    val rowShuffleOrders by HomeRowShuffleState.orders.collectAsStateWithLifecycle()
    val rowShufflingKeys by HomeRowShuffleState.shuffling.collectAsStateWithLifecycle()
    val watchedUiState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    // Extra Random Play candidates from the Collections catalogs (opt-in; empty otherwise).
    val randomPlayCollectionSections by RandomPlayCollectionPool.sections.collectAsStateWithLifecycle()
    val randomPlayPool by RandomPlayCandidatePool.state.collectAsStateWithLifecycle()
    val posterCardStyle = rememberPosterCardStyleUiState()

    // Search mode state — query persists while on the Search tab (rememberSaveable).
    // searchQuery is owned by App.kt / the nav bar and passed in directly.
    // When blank in Search mode, fall back to home content so the screen isn't empty.
    val searchUiState by remember {
        SearchRepository.uiState
    }.collectAsStateWithLifecycle()
    val discoverUiState by remember {
        SearchRepository.discoverUiState
    }.collectAsStateWithLifecycle()

    // Which header segment (if any) currently owns the row body. Reset when leaving the tab so the
    // picker is never left hanging open behind another mode.
    var discoverPickerSegment by remember(contentMode is HomeContentMode.Discover) {
        mutableStateOf<DiscoverPickerSegment?>(null)
    }
    // Left edge of each header segment within the header row, reported by layout. The picker panel
    // hangs off the segment that opened it rather than off the row edge.
    var discoverSegmentOffsets by remember { mutableStateOf(emptyMap<DiscoverPickerSegment, Float>()) }
    // Discover catalogs are derived from the installed addons, so this is lazy — nothing here runs
    // until the user actually opens the tab.
    LaunchedEffect(contentMode, addonsUiState.addons) {
        if (contentMode is HomeContentMode.Discover) {
            SearchRepository.refreshDiscover(addonsUiState.addons)
        }
    }
    val discoverRecommendations by DiscoverRecommendationsRepository.uiState.collectAsStateWithLifecycle()
    // Seeds come from watch history and cost a TMDB request each, so this is lazy too and cached
    // for an hour by the repository. Keyed on the Discover settings as well as the mode: the
    // repository drops its cache when they change, and without the key nothing would ask it to,
    // because HomeScreen stays composed the whole time the user is in Settings.
    val discoverRowSettingsKey = remember(homeSettingsUiState) {
        listOf(
            homeSettingsUiState.discoverBecauseYouWatchedRows,
            homeSettingsUiState.discoverHideWatched,
            homeSettingsUiState.discoverFinishWhatYouStartedEnabled,
            homeSettingsUiState.discoverFinishIdleDays,
            homeSettingsUiState.discoverMoreLikeFavouritesEnabled,
            homeSettingsUiState.discoverHiddenGemsEnabled,
            homeSettingsUiState.discoverTrendingGenreRows,
            homeSettingsUiState.discoverExcludedGenres.sorted().joinToString(","),
            homeSettingsUiState.hideUnreleasedContent,
            // Both halves of row management: the definitions decide what gets built, the order
            // decides what it looks like. The repository treats them differently — a reorder
            // re-sorts what it already has — but either one has to bring us back here first.
            homeSettingsUiState.discoverCustomRows.joinToString(";"),
            homeSettingsUiState.discoverRowOrder.joinToString(","),
        ).joinToString("|")
    }
    LaunchedEffect(contentMode, discoverRowSettingsKey) {
        if (contentMode is HomeContentMode.Discover) {
            DiscoverRecommendationsRepository.refresh()
        }
    }

    // Library mode state. Load it off the composition path so Home startup doesn't pay
    // for library/provider cache work before the user opens Library.
    LaunchedEffect(contentMode) {
        if (contentMode is HomeContentMode.Library) {
            withContext(Dispatchers.Default) {
                LibraryRepository.ensureLoaded()
            }
        }
    }
    val libraryUiState by LibraryRepository.uiState.collectAsStateWithLifecycle()
    val libraryDisplaySettings by remember {
        LibraryDisplaySettingsRepository.ensureLoaded()
        LibraryDisplaySettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val posterClickHandler: ((MetaPreview) -> Unit)? = if (contentMode is HomeContentMode.Library) {
        { preview ->
            if (preview.preferLocalStreams) {
                onLocalLibraryPosterClick?.invoke(preview) ?: onPosterClick?.invoke(preview)
            } else {
                onPosterClick?.invoke(preview)
            }
        }
    } else {
        onPosterClick
    }

    val platformContext = LocalPlatformContext.current
    val imageLoader = SingletonImageLoader.get(platformContext)

    LaunchedEffect(contentMode, libraryUiState.sections) {
        if (contentMode !is HomeContentMode.Library) return@LaunchedEffect
        val itemsToPrefetch = libraryUiState.sections.take(2).flatMap { it.items.take(8) }
        itemsToPrefetch.forEach { item ->
            item.poster?.takeIf { it.isNotBlank() }?.let { url ->
                val request = ImageRequest.Builder(platformContext)
                    .data(url)
                    // Without this the prefetch decoded at full source resolution and parked that
                    // in the memory cache, which the card then reused — see
                    // [nuvioArtworkRequestSize].
                    .nuvioArtworkRequestSize()
                    .build()
                imageLoader.enqueue(request)
            }
        }
    }

    // Trigger SIMKL library enrichment once addons AND library items are both ready.
    // Using all three as keys handles the race where either loads after the other.
    val enabledAddonCount = remember(addonsUiState.addons) {
        addonsUiState.addons.enabledAddons().size
    }
    val simklLibraryHasLoaded by SimklLibraryRepository.uiState.collectAsStateWithLifecycle()
    val tmdbSettingsUiState by TmdbSettingsRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(contentMode, enabledAddonCount, simklLibraryHasLoaded.hasLoaded) {
        if (contentMode is HomeContentMode.Library &&
            enabledAddonCount > 0 &&
            simklLibraryHasLoaded.hasLoaded &&
            simklLibraryHasLoaded.allItems.isNotEmpty()
        ) {
            delay(HOME_STARTUP_METADATA_GRACE_MS)
            SimklLibraryRepository.triggerEnrichment()
        }
    }

    // Clear the search repository when leaving the search screen entirely.
    // We intentionally do NOT clear it when the query is blank while inside the Search mode
    // so that the previous results remain visible until the user submits a new query.
    LaunchedEffect(searchQuery, contentMode) {
        val q = searchQuery.trim()
        if (contentMode !is HomeContentMode.Search && q.isBlank()) {
            SearchRepository.clear()
        }
    }

    // Track the last non-search mode so blank-query search shows whatever was visible before.
    // Without this, switching to Search from Home or Library immediately clears the UI.
    var previousNonSearchMode by remember { mutableStateOf<HomeContentMode>(HomeContentMode.Normal) }
    LaunchedEffect(contentMode) {
        if (contentMode !is HomeContentMode.Search) previousNonSearchMode = contentMode
    }
    // What to actually display: when search is blank, fall back to the previous mode.
    val isSearchPristine = searchUiState.sections.isEmpty() &&
        !searchUiState.isLoading &&
        searchUiState.emptyStateReason == null &&
        searchUiState.errorMessage == null
    val displayMode: HomeContentMode = if (contentMode is HomeContentMode.Search && isSearchPristine)
        previousNonSearchMode else contentMode

    // Computed at composable scope so both the main enrichment and the focused-item
    // enrichment LaunchedEffects can reference it without duplicating the snapshot call.
    val tmdbImageModeOn = remember(tmdbSettingsUiState) {
        tmdbSettingsUiState.hasApiKey && (tmdbSettingsUiState.heroImageSource == HeroImageSource.TmdbOnly ||
            tmdbSettingsUiState.heroImageSource == HeroImageSource.TmdbMoviesTvdbShows)
    }
    val searchLibraryBackdropEnrichmentEnabled =
        tmdbImageModeOn || posterCardStyle.catalogLandscapeModeEnabled

    val discoverAllFiltersLabel = stringResource(Res.string.discover_all_filters)
    val randomPlayLabels = RandomPlayLabels(
        sectionTitle = stringResource(Res.string.random_play_title),
        sectionSubtitle = stringResource(Res.string.random_play_catalog_subtitle),
        movie = stringResource(Res.string.random_play_movie),
        series = stringResource(Res.string.random_play_series),
        animeMovie = stringResource(Res.string.random_play_anime_movie),
        animeSeries = stringResource(Res.string.random_play_anime_series),
    )
    // Subtitle for the catalog screen a Library row's "See more" arrow opens.
    val librarySectionSubtitle = if (libraryUiState.sourceMode == LibrarySourceMode.TRAKT) {
        stringResource(Res.string.compose_catalog_subtitle_trakt_library)
    } else {
        stringResource(Res.string.compose_catalog_subtitle_library)
    }

    // Compute effective sections and hero items based on content mode.
    val effectiveSections: List<HomeCatalogSection> = remember(
        displayMode, contentMode, searchQuery, discoverUiState, discoverRecommendations,
        discoverAllFiltersLabel, librarySectionSubtitle, randomPlayLabels,
        homeSettingsUiState.randomPlayEnabled,
        homeSettingsUiState.randomPlayIncludeCollections,
        homeSettingsUiState.randomPlayCategories,
        homeSettingsUiState.randomPlayGenres,
        homeSettingsUiState.randomPlayMinimumImdbRating,
        randomPlayCollectionSections,
        randomPlayPool,
        watchedUiState.watchedKeys,
        homeUiState.sections, searchUiState.sections, libraryUiState.sections, libraryDisplaySettings.sortOption,
    ) {
        when (displayMode) {
            is HomeContentMode.Normal -> buildList {
                buildRandomPlaySection(
                    sourceSections = randomPlaySourceSections(
                        homeSections = homeUiState.sections,
                        collectionSections = randomPlayCollectionSections,
                        settings = homeSettingsUiState,
                        pool = randomPlayPool,
                    ),
                    settings = homeSettingsUiState,
                    labels = randomPlayLabels,
                    watchedKeys = watchedUiState.watchedKeys,
                )?.let(::add)
                addAll(homeUiState.sections)
            }
            is HomeContentMode.Search -> {
                // Normalize genres for all search result items at the section level so
                // every item — whether it needs further enrichment or not — gets capitalised
                // genres. (Addon catalog responses return lowercase genre strings.)
                searchUiState.sections.map { section ->
                    section.copy(items = section.items.map { item ->
                        val current = if (item.genres.isEmpty()) item
                        else item.copy(genres = item.genres.map(::normalizeSearchGenre))
                        if (tmdbImageModeOn && !current.banner.isMetadataProviderArtUrl()) {
                            current.copy(banner = null, logo = null)
                        } else {
                            current
                        }
                    })
                }
            }
            is HomeContentMode.Library -> sortLibrarySections(
                libraryUiState.sections,
                libraryDisplaySettings.sortOption,
                libraryUiState.sourceMode,
            ).map { section ->
                HomeCatalogSection(
                    key = "library_${section.type}",
                    title = section.displayTitle,
                    subtitle = librarySectionSubtitle,
                    addonName = "",
                    target = com.nuvio.app.features.catalog.CatalogTarget.Library(
                        contentType = section.items.firstOrNull()?.type ?: "movie",
                        sectionType = section.type,
                    ),
                    items = section.items.map { it.toMetaPreview().let { preview ->
                        val sourcedPreview = preview.copy(
                            preferLocalStreams = section.type.startsWith("locallibrary_"),
                        )
                        // Dropping the row's own banner is how TMDB image mode makes room for the
                        // hero pass to fetch original-quality art by id. Art that already came from
                        // a metadata provider is the best this row will ever get — the cloud and
                        // local library rows have no addon metadata behind their ids, so nulling it
                        // there discards the TMDB backdrop for good and leaves landscape cards blank.
                        if (tmdbImageModeOn && !sourcedPreview.banner.isMetadataProviderArtUrl()) {
                            sourcedPreview.copy(banner = null, logo = null)
                        } else {
                            sourcedPreview
                        }
                    } },
                    availableItemCount = section.items.size,
                    hasMore = false,
                    paginates = false,
                )
            }.ensureUniqueKeys()
            is HomeContentMode.Catalogs -> displayMode.sections.ensureUniqueKeys()
            // Row 1 is the addon catalog browser lifted out of Search; the generated
            // "Because you watched …" rows follow it.
            is HomeContentMode.Discover -> buildList {
                val catalog = discoverUiState.selectedCatalog
                if (catalog != null) {
                    add(
                        HomeCatalogSection(
                            key = DISCOVER_BROWSER_ROW_KEY,
                            title = "${catalog.catalogName} • ${discoverUiState.selectedGenre ?: discoverAllFiltersLabel}",
                            subtitle = catalog.addonName,
                            addonName = catalog.addonName,
                            target = com.nuvio.app.features.catalog.CatalogTarget.Addon(
                                manifestUrl = catalog.manifestUrl,
                                contentType = catalog.type,
                                catalogId = catalog.catalogId,
                                genre = discoverUiState.selectedGenre,
                                supportsPagination = catalog.supportsPagination,
                            ),
                            items = discoverUiState.items,
                            // Unlike the old Search-hosted row, this one paginates in place: it is
                            // the tab's primary browsing surface, not a preview of one.
                            paginates = catalog.supportsPagination,
                            hasMore = discoverUiState.canLoadMore,
                            nextSkip = discoverUiState.nextSkip,
                            isLoadingMore = discoverUiState.isLoading && discoverUiState.items.isNotEmpty(),
                            inlineOnly = true,
                        ),
                    )
                }
                discoverRecommendations.rows.forEach { row ->
                    add(
                        HomeCatalogSection(
                            // A placeholder keeps its DISCOVER_PLACEHOLDER_KEY_PREFIX key, which is
                            // how the row renderer knows to draw a skeleton instead of a shelf.
                            key = row.key,
                            title = row.title,
                            subtitle = "",
                            addonName = "",
                            // No CatalogTarget: these rows are generated, not addon-backed, so
                            // there is no catalog for "see all" to open.
                            target = null,
                            items = row.items,
                            inlineOnly = true,
                        ),
                    )
                }
            }.ensureUniqueKeys()
        }
    }

    // Base hero items from the mode — no genre/description/releaseInfo for library/search items yet.
    val resumeHeroPreview = remember(resumePromptItem) {
        resumePromptItem?.let { item ->
            MetaPreview(
                id = item.parentMetaId,
                type = item.parentMetaType,
                name = item.title,
                poster = item.poster ?: item.imageUrl,
                banner = item.background ?: item.episodeThumbnail ?: item.imageUrl,
                logo = item.logo,
                description = item.pauseDescription,
                releaseInfo = item.subtitle,
            )
        }
    }
    val resumeHeroKey = resumeHeroPreview?.let { "${it.type}:${it.id}" }

    val baseHeroItems: List<MetaPreview> = remember(
        displayMode,
        contentMode,
        searchQuery,
        homeUiState.heroItems,
        effectiveSections,
        tmdbImageModeOn,
        searchLibraryBackdropEnrichmentEnabled,
        homeSettingsUiState.adaptiveHeroEnabled,
        homeSettingsUiState.tvModeEnabled,
        homeSettingsUiState.heroAmbientBackgroundEnabled,
        resumeHeroPreview,
    ) {
        resumeHeroPreview?.let { return@remember listOf(it) }
        // In non-Addon hero-image modes, suppress the catalog's art on hero items so TMDB/TVDB
        // enrichment doesn't visibly replace it ~1s later. Hero-only: baseHeroItems is a separate
        // list from the results grid (which reads effectiveSections).
        //
        // [keepText] exists because only Search/Library refetch the text: their enrichment pass
        // pulls genres/description/release info wholesale, so dropping the catalog's copy is free.
        // Normal mode's pass deliberately fills backdrop/logo/age rating *only*, trusting the hero
        // catalog for everything else — so dropping its text there deletes metadata that nothing
        // ever puts back, and the hero renders with no genres, plot, year or runtime at all.
        fun List<MetaPreview>.suppressingCatalogHero(keepText: Boolean = false) = when {
            displayMode !is HomeContentMode.Normal && !searchLibraryBackdropEnrichmentEnabled -> this
            displayMode is HomeContentMode.Normal && !tmdbImageModeOn -> this
            keepText -> map(MetaPreview::asPendingHeroArtPreview)
            else -> map(MetaPreview::asPendingHeroPreview)
        }
        // Seed the hero from the visible rows (what you're currently browsing), used by Search,
        // Library, and — as a fallback — Normal mode's content-following desktop backdrop modes.
        fun currentlyViewingHeroSeed(keepText: Boolean = false) =
            effectiveSections.take(2).flatMap { it.items.take(8) }.distinctBy { "${it.type}:${it.id}" }
                .suppressingCatalogHero(keepText)
        when (displayMode) {
            is HomeContentMode.Normal -> homeUiState.heroItems.ifEmpty {
                // Adaptive Hero, TV Mode, and the ambient backdrop follow the item you're browsing,
                // so they must keep working even when no catalog is opted into the hero carousel
                // (hero catalogs = 0). Seed from the visible rows so the backdrop still has content
                // and can adapt to the focused item. The plain fixed hero is left intentionally
                // empty in that case — with no hero catalogs selected there's nothing to rotate.
                val desktopBackdropModeActive = isDesktop && (
                    homeSettingsUiState.adaptiveHeroEnabled ||
                        homeSettingsUiState.tvModeEnabled ||
                        homeSettingsUiState.heroAmbientBackgroundEnabled
                    )
                if (desktopBackdropModeActive) currentlyViewingHeroSeed(keepText = true) else emptyList()
            }
            is HomeContentMode.Search ->
                // Seed the search hero with only the first couple of results per catalog. This is the
                // set the hero eagerly enriches (incl. rate-limited MDBList ratings), so keeping it to
                // "near the start of each catalog" is what bounds search enrichment to ~2/catalog.
                effectiveSections.flatMap { it.items.take(SEARCH_HERO_METADATA_PREFETCH_PER_CATALOG) }
                    .distinctBy { "${it.type}:${it.id}" }
                    .suppressingCatalogHero()
            else -> currentlyViewingHeroSeed()
        }
    }

    // Stable enrichment map — keyed by "type:id", survives baseHeroItems reference changes.
    // HomeRepository emits new heroItems list objects on each catalog tick, which would
    // otherwise reset effectiveHeroItems (and all TMDB-fetched backdrops) every few seconds.
    val heroEnrichmentMap = remember { mutableStateMapOf<String, MetaPreview>() }
    // Keys with a metadata lookup actually in flight. Landscape cards hide their own artwork only
    // while their replacement is being fetched — an item nobody is fetching for (or whose fetch
    // came back empty, as every cloud/local library id does) keeps whatever art it already has
    // instead of staying a blank tile forever.
    val pendingHeroEnrichments = remember { mutableStateMapOf<String, Unit>() }
    val landscapePendingEnrichmentKeys: Set<String> =
        if (posterCardStyle.catalogLandscapeModeEnabled) pendingHeroEnrichments.keys else emptySet()
    var heroMetadataStartupGraceUsed by remember { mutableStateOf(false) }
    var continueWatchingMetadataStartupGraceUsed by remember { mutableStateOf(false) }
    var heroBatchEnrichmentStartupGraceUsed by remember { mutableStateOf(false) }

    // Initialise from base items, applying any already-fetched enrichment from the map.
    // When baseHeroItems changes (new reference, same content) we preserve enrichment.
    val effectiveHeroItems = remember(baseHeroItems) {
        mutableStateListOf(*baseHeroItems.map { base ->
            heroEnrichmentMap[canonicalHeroKey(base.type, base.id)] ?: base
        }.toTypedArray())
    }


    LaunchedEffect(baseHeroItems, displayMode, tmdbSettingsUiState.heroImageSource) {
        val tmdbSnap = TmdbSettingsRepository.snapshot()
        co.touchlab.kermit.Logger.withTag("HomeEnrichment").d {
            "LaunchedEffect fired: mode=$displayMode heroSrc=${tmdbSnap.heroImageSource} tmdbKey=${tmdbSnap.hasApiKey} tmdbOn=$tmdbImageModeOn items=${baseHeroItems.size}"
        }
        val normalHomeMode = displayMode is HomeContentMode.Normal
        val normalHomeNeedsMetadata = normalHomeMode &&
            baseHeroItems.any(MetaPreview::needsHomeHeroMetadataEnrichment)
        // Home page: rich metadata addons already provide good real-time metadata. Fill only
        // fields the catalog omitted (currently backdrop/logo and age rating), preserving its
        // artwork and text whenever they are already present.
        if (normalHomeMode && !normalHomeNeedsMetadata) return@LaunchedEffect
        // Search/Library normally only enrich for the non-Addon image sources. Rows with no text of
        // their own (local library, cloud library) still have to be fetched whatever the source is,
        // or their hero shows a title and nothing else. The fetch pass below skips anything already
        // complete, so this widening costs nothing on catalogs that carry their own metadata.
        val searchLibraryNeedsMetadata = !normalHomeMode &&
            baseHeroItems.any(MetaPreview::needsHeroTextEnrichment)
        if (!normalHomeMode && !searchLibraryBackdropEnrichmentEnabled && !searchLibraryNeedsMetadata) {
            return@LaunchedEffect
        }
        // Yield the network to the focused card's own enrichment for a moment on the first pass of
        // a session. This batch fans out across every hero-rotation item at once (the semaphore is
        // deliberately as wide as the list), and at launch it was finishing all of them before the
        // focused card's single request came back — which is what left the first Continue Watching
        // card unenriched for the whole hold window. Startup only: later passes run immediately, so
        // scrolling is not slowed.
        if (!heroBatchEnrichmentStartupGraceUsed) {
            heroBatchEnrichmentStartupGraceUsed = true
            co.touchlab.kermit.Logger.withTag("HeroLogoRace").i {
                "t=${heroProbeMs()} BATCH grace start (${HERO_BATCH_ENRICHMENT_STARTUP_GRACE_MS}ms, ${baseHeroItems.size} items)"
            }
            delay(HERO_BATCH_ENRICHMENT_STARTUP_GRACE_MS)
        }
        co.touchlab.kermit.Logger.withTag("HeroLogoRace").i {
            "t=${heroProbeMs()} BATCH dispatch ${baseHeroItems.size} items"
        }
        if (!heroMetadataStartupGraceUsed) {
            heroMetadataStartupGraceUsed = true
            delay(HOME_STARTUP_METADATA_GRACE_MS)
        }

        // Only replace items when the set of IDs actually changed — an unconditional
        // clear() + addAll() resets the hero carousel page to 0 even when switching
        // Home→Search with a blank query shows the exact same hero items.
        val newKeys = baseHeroItems.map { canonicalHeroKey(it.type, it.id) }
        val currentKeys = effectiveHeroItems.map { canonicalHeroKey(it.type, it.id) }
        if (newKeys != currentKeys) {
            effectiveHeroItems.clear()
            effectiveHeroItems.addAll(baseHeroItems.map { item ->
                heroEnrichmentMap[canonicalHeroKey(item.type, item.id)] ?: item
            })
        }

        // Peek pass — instantly apply any metadata already in cache.
        val peekedHeroItems = baseHeroItems.mapIndexed { idx, item ->
            val current = effectiveHeroItems.getOrNull(idx) ?: item
            val shouldEnrichHomeMetadata = normalHomeMode && current.needsHomeHeroMetadataEnrichment()
            if (normalHomeMode && !shouldEnrichHomeMetadata) return@mapIndexed current
            val cached = MetaDetailsRepository.peek(item.metadataType, item.metadataId)
                ?: return@mapIndexed current
            val enriched = if (normalHomeMode) {
                current.copy(
                    banner = bestBackdrop(cached.background, current.banner),
                    logo = current.logo ?: cached.logo,
                    ageRating = current.ageRating ?: cached.ageRating,
                    // Catalog rows almost never carry a runtime, so the hero's year • runtime line
                    // reads as year alone without this. Fill-only, like the age rating beside it.
                    runtime = current.runtime ?: cached.runtime,
                ).withFetchedHeroText(cached)
            } else {
                current.copy(
                    genres = cached.genres.ifEmpty { current.genres }.map(::normalizeSearchGenre),
                    description = cached.description ?: current.description,
                    releaseInfo = cached.releaseInfo ?: current.releaseInfo,
                    runtime = current.runtime ?: cached.runtime,
                    ageRating = current.ageRating ?: cached.ageRating,
                    banner = bestBackdrop(cached.background, current.banner),
                    logo = current.logo ?: cached.logo,
                )
            }
            enriched.also {
                publishHeroEnrichment(heroEnrichmentMap, enriched, "baseHeroEnrich")
            }
        }
        if (effectiveHeroItems.map { canonicalHeroKey(it.type, it.id) } == newKeys &&
            effectiveHeroItems.toList() != peekedHeroItems
        ) {
            effectiveHeroItems.clear()
            effectiveHeroItems.addAll(peekedHeroItems)
        }

        // Fetch pass — for items still missing genres or description, fetch from addons.
        // Uses fetchLightweightMeta which takes the first non-null result from any addon
        // without requiring an episode list for series (avoiding the series video check in
        // the full fetch() that would block until TMDB fallback or return null).
        val heroImageSource = tmdbSettingsUiState.heroImageSource
        // Use a semaphore large enough to run all hero items concurrently. A small limit
        // (e.g. 3) causes items to queue; when homeUiState ticks and restarts the
        // LaunchedEffect, queued coroutines are cancelled before they run — so only the
        // first batch ever gets enriched. 8 concurrent TVDB/TMDB requests is fine.
        val sem = Semaphore(baseHeroItems.size.coerceAtLeast(1))
        val fetchedHeroItems = baseHeroItems.mapIndexed { idx, _ ->
            async {
                val current = peekedHeroItems.getOrNull(idx) ?: return@async null
                val shouldEnrichHomeMetadata = normalHomeMode && current.needsHomeHeroMetadataEnrichment()
                if (normalHomeMode && !shouldEnrichHomeMetadata) return@async idx to current
            val hasFullMetadata = current.genres.isNotEmpty() && current.description != null &&
                current.runtime != null

            // Determine if the current banner is already the ideal quality for this mode.
            // For TmdbMoviesTvdbShows TV items: ideal = artworks.thetvdb.com (TVDB CDN).
            // For TmdbOnly / anything else: ideal = image.tmdb.org/t/p/original.
            val isTvItemForTvdb = (heroImageSource == HeroImageSource.TmdbMoviesTvdbShows) &&
                (current.type.equals("series", ignoreCase = true) ||
                    current.type.equals("anime", ignoreCase = true))
            val hasIdealBanner = when {
                // Per-season anime art (AniList/Kitsu) outranks franchise-wide TMDB/TVDB art.
                current.banner.isAnimeSeasonArtUrl() -> true
                isTvItemForTvdb -> current.banner?.contains("artworks.thetvdb.com") == true
                else -> current.banner?.contains("image.tmdb.org/t/p/original") == true
            }
            when {
                // Skip only when we already have the ideal-quality banner from the right source.
                    searchLibraryBackdropEnrichmentEnabled && hasFullMetadata && hasIdealBanner ->
                        return@async idx to current
                // Addon mode: skip when all text + any banner present.
                    !searchLibraryBackdropEnrichmentEnabled && hasFullMetadata && current.banner != null ->
                        return@async idx to current
            }
                sem.withPermit {
                    // When TMDB mode is on, fetchLightweightMeta collects addon text metadata
                    // then calls tryFetchTmdbFallbackMeta for high-quality TMDB images,
                    // merging the two. No separate TmdbHeroImageService call needed.
                    val meta = runCatching {
                        MetaDetailsRepository.fetchLightweightMeta(
                            type = current.metadataType,
                            id = current.metadataId,
                            preferTmdbImages = tmdbImageModeOn || normalHomeMode ||
                                posterCardStyle.catalogLandscapeModeEnabled,
                        )
                    }.getOrNull()
                    val now = peekedHeroItems.getOrNull(idx) ?: current
                    val enriched = if (normalHomeMode) {
                        val imdbId = now.homeHeroFallbackImdbId()
                        val metahubBackdrop = if (meta?.background.isNullOrBlank() && imdbId != null) {
                            MetahubService.getValidBackgroundUrl(imdbId)
                        } else {
                            null
                        }
                        val metahubLogo = if (now.logo.isNullOrBlank() && meta?.logo.isNullOrBlank() && imdbId != null) {
                            MetahubService.getValidLogoUrl(imdbId)
                        } else {
                            null
                        }
                        now.copy(
                            banner = bestBackdrop(meta?.background, metahubBackdrop, now.banner),
                            logo = now.logo ?: meta?.logo ?: metahubLogo,
                            ageRating = now.ageRating ?: meta?.ageRating,
                            runtime = now.runtime ?: meta?.runtime,
                        ).withFetchedHeroText(meta)
                    } else {
                        val fetchedMeta = meta ?: return@withPermit idx to current
                        now.copy(
                            genres = fetchedMeta.genres.ifEmpty { now.genres }.map(::normalizeSearchGenre),
                            description = fetchedMeta.description ?: now.description,
                            releaseInfo = fetchedMeta.releaseInfo ?: now.releaseInfo,
                            runtime = now.runtime ?: fetchedMeta.runtime,
                            ageRating = now.ageRating ?: fetchedMeta.ageRating,
                            // When TVDB or the per-season anime providers supplied the backdrop,
                            // use it directly — bestBackdrop prefers image.tmdb.org URLs and
                            // would override it with franchise-wide TMDB art.
                            banner = if (fetchedMeta.background?.contains("artworks.thetvdb.com") == true ||
                                fetchedMeta.background.isAnimeSeasonArtUrl()
                            )
                                fetchedMeta.background
                            else
                                bestBackdrop(fetchedMeta.background, now.banner),
                            logo = if (fetchedMeta.logo?.contains("artworks.thetvdb.com") == true)
                                fetchedMeta.logo
                            else
                                now.logo ?: fetchedMeta.logo,
                        )
                    }
                    publishHeroEnrichment(heroEnrichmentMap, enriched, "batchHeroEnrich")
                    idx to enriched
                }
            }
        }.awaitAll()

        if (effectiveHeroItems.map { canonicalHeroKey(it.type, it.id) } == newKeys) {
            val nextItems = peekedHeroItems.toMutableList()
            fetchedHeroItems.filterNotNull().forEach { (idx, item) ->
                if (idx in nextItems.indices) nextItems[idx] = item
            }
            if (effectiveHeroItems.toList() != nextItems) {
                effectiveHeroItems.clear()
                effectiveHeroItems.addAll(nextItems)
            }
        }
    }
    val homeListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    val searchListState = remember(searchQuery) { LazyListState() }
    val catalogListState = rememberLazyListState()
    val discoverListState = rememberLazyListState()

    val currentListState = when (displayMode) {
        is HomeContentMode.Normal -> homeListState
        is HomeContentMode.Library -> libraryListState
        is HomeContentMode.Search -> searchListState
        is HomeContentMode.Catalogs -> catalogListState
        is HomeContentMode.Discover -> discoverListState
    }
    // Remember the Home tab's scroll position across navigation (e.g. opening the
    // details screen and coming back) so the user returns to where they were rather
    // than the top. The details screen disposes Home, and on return the catalog list
    // is populated progressively — so we can't just seed an initial index (it would be
    // clamped before the rows exist). Instead we wait for content, restore, then keep
    // a session-scoped holder in sync while the user scrolls. Only the real Home view
    // (Normal mode) participates; Search/Library share this composable but not its
    // remembered position.
    if (displayMode is HomeContentMode.Normal) {
        LaunchedEffect(Unit) {
            val savedIndex = HomeScrollMemory.firstVisibleItemIndex
            val savedOffset = HomeScrollMemory.firstVisibleItemScrollOffset
            if (savedIndex > 0 || savedOffset > 0) {
                withTimeoutOrNull(4000) {
                    snapshotFlow { homeListState.layoutInfo.totalItemsCount }
                        .first { it > savedIndex }
                }
                homeListState.scrollToItem(savedIndex, savedOffset)
            }
            // From here on, mirror the live scroll position into the holder.
            snapshotFlow {
                homeListState.firstVisibleItemIndex to homeListState.firstVisibleItemScrollOffset
            }.collect { (index, offset) ->
                HomeScrollMemory.firstVisibleItemIndex = index
                HomeScrollMemory.firstVisibleItemScrollOffset = offset
            }
        }
    }
    // Same treatment for the Library tab (non-immersive layout), backed by its own holder.
    if (displayMode is HomeContentMode.Library) {
        LaunchedEffect(Unit) {
            val savedIndex = LibraryScrollMemory.firstVisibleItemIndex
            val savedOffset = LibraryScrollMemory.firstVisibleItemScrollOffset
            if (savedIndex > 0 || savedOffset > 0) {
                withTimeoutOrNull(4000) {
                    snapshotFlow { libraryListState.layoutInfo.totalItemsCount }
                        .first { it > savedIndex }
                }
                libraryListState.scrollToItem(savedIndex, savedOffset)
            }
            snapshotFlow {
                libraryListState.firstVisibleItemIndex to libraryListState.firstVisibleItemScrollOffset
            }.collect { (index, offset) ->
                LibraryScrollMemory.firstVisibleItemIndex = index
                LibraryScrollMemory.firstVisibleItemScrollOffset = offset
            }
        }
    }
    // And the same again for Discover. Its row 1 is the catalog browser, whose contents change as
    // the user picks catalogs, but the *row* count is stable — so the wait-for-content guard below
    // behaves exactly as it does for Home.
    if (displayMode is HomeContentMode.Discover) {
        LaunchedEffect(Unit) {
            val savedIndex = DiscoverScrollMemory.firstVisibleItemIndex
            val savedOffset = DiscoverScrollMemory.firstVisibleItemScrollOffset
            if (savedIndex > 0 || savedOffset > 0) {
                withTimeoutOrNull(4000) {
                    snapshotFlow { discoverListState.layoutInfo.totalItemsCount }
                        .first { it > savedIndex }
                }
                discoverListState.scrollToItem(savedIndex, savedOffset)
            }
            snapshotFlow {
                discoverListState.firstVisibleItemIndex to discoverListState.firstVisibleItemScrollOffset
            }.collect { (index, offset) ->
                DiscoverScrollMemory.firstVisibleItemIndex = index
                DiscoverScrollMemory.firstVisibleItemScrollOffset = offset
            }
        }
    }
    // Search gets the same treatment, but scoped to the query the position was taken from.
    // searchListState is itself remember(searchQuery), so a new query already yields a fresh list
    // state; this keeps the holder in step so a stale position can never be replayed onto it.
    if (displayMode is HomeContentMode.Search) {
        LaunchedEffect(searchQuery) {
            SearchScrollMemory.resetIfQueryChanged(searchQuery)
            val savedIndex = SearchScrollMemory.firstVisibleItemIndex
            val savedOffset = SearchScrollMemory.firstVisibleItemScrollOffset
            if (savedIndex > 0 || savedOffset > 0) {
                withTimeoutOrNull(4000) {
                    snapshotFlow { searchListState.layoutInfo.totalItemsCount }
                        .first { it > savedIndex }
                }
                searchListState.scrollToItem(savedIndex, savedOffset)
            }
            snapshotFlow {
                searchListState.firstVisibleItemIndex to searchListState.firstVisibleItemScrollOffset
            }.collect { (index, offset) ->
                SearchScrollMemory.firstVisibleItemIndex = index
                SearchScrollMemory.firstVisibleItemScrollOffset = offset
            }
        }
    }
    val collections by CollectionRepository.collections.collectAsStateWithLifecycle()
    val continueWatchingPreferences by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
    val watchProgressUiState by WatchProgressRepository.uiState.collectAsStateWithLifecycle()
    val continueWatchingSource by ContinueWatchingSourceRepository.uiState.collectAsStateWithLifecycle()
    val connectedTrackingProviders by TrackingProviderRegistry.connectedProviderIds.collectAsStateWithLifecycle()
    val continueWatchingRemoteSourceActive = isContinueWatchingRemoteSourceActive(
        source = continueWatchingSource,
        connectedProviderIds = connectedTrackingProviders,
    )
    val cloudLibraryUiState by CloudLibraryRepository.uiState.collectAsStateWithLifecycle()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val traktSettingsUiState by TraktSettingsRepository.uiState.collectAsStateWithLifecycle()
    val isTraktAuthenticated by TraktAuthRepository.isAuthenticated.collectAsStateWithLifecycle()
    var observedOfflineState by remember { mutableStateOf(false) }

    LaunchedEffect(scrollToTopRequests) {
        scrollToTopRequests.collect {
            currentListState.animateScrollToItem(0)
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

    val simklIsAuthenticated by SimklAuthRepository.isAuthenticated.collectAsStateWithLifecycle()
    val simklSettingsUiState by SimklSettingsRepository.uiState.collectAsStateWithLifecycle()

    // Trakt-specific CW behaviours — day-cap window, dropped-show exclusion, entry remapping —
    // apply only when Trakt is the selected source.
    //
    // This used to derive the answer from `traktSettings.watchProgressSource` and SIMKL's
    // `asCwSource`, the per-provider flags that `ContinueWatchingSourceRepository` replaced. Both
    // are now write-free leftovers — nothing has set either since the single-selection migration —
    // so this read was stuck on whatever those values happened to be, and for anyone who chose
    // their source after the migration that means "no source is active" and the day-cap filters
    // below silently did nothing.
    val isTraktProgressActive = remember(isTraktAuthenticated, continueWatchingSource) {
        continueWatchingSource == ContinueWatchingSource.TRAKT && isTraktAuthenticated
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
        continueWatchingPreferences.seedNextUpFromNuvioSync,
        continueWatchingRemoteSourceActive,
    ) {
        val filteredEntries = if (isTraktProgressActive) {
            watchProgressUiState.entries.filter { !WatchProgressRepository.isDroppedShow(it.parentMetaId) }
        } else {
            watchProgressUiState.entries
        }
        // The watched store is its own subsystem, not scoped to the Continue Watching source, so
        // seeding Up Next from it while a remote source is selected mixes that source's rows with
        // the whole local history. Opt-in, because for a source that supplies no completed seeds of
        // its own it is the difference between a next-up row and none.
        val watchedSeedItems = when {
            !continueWatchingRemoteSourceActive -> watchedUiState.items
            continueWatchingPreferences.seedNextUpFromNuvioSync -> watchedUiState.items
            else -> emptyList()
        }
        val filteredWatchedItems = if (isTraktProgressActive) {
            watchedSeedItems.filter { !WatchProgressRepository.isDroppedShow(it.id) }
        } else {
            watchedSeedItems
        }
        com.nuvio.app.features.watchprogress.NextUpDiagnostics.logSourceGate(
            continueWatchingSource = continueWatchingSource.name,
            remoteSourceActive = continueWatchingRemoteSourceActive,
            seedFromNuvioSyncEnabled = continueWatchingPreferences.seedNextUpFromNuvioSync,
            progressEntryCount = filteredEntries.size,
            watchedItemCount = watchedUiState.items.size,
            watchedSeedCount = filteredWatchedItems.size,
        )
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
        simklIsAuthenticated,
        continueWatchingSource,
        simklSettingsUiState.simklContinueWatchingDaysCap,
    ) {
        val simklCwActive = simklIsAuthenticated &&
            continueWatchingSource == ContinueWatchingSource.SIMKL
        val now = WatchProgressClock.nowEpochMs()
        var candidates = filterHomeNextUpCandidatesForTraktContinueWatchingWindow(
            candidates = allNextUpSeedCandidates,
            isTraktProgressActive = isTraktProgressActive,
            daysCap = traktSettingsUiState.continueWatchingDaysCap,
            nowEpochMs = now,
        )
        if (simklCwActive) {
            val cutoffMs = simklContinueWatchingCutoffMs(
                daysCap = simklSettingsUiState.simklContinueWatchingDaysCap,
                nowEpochMs = now,
            )
            if (cutoffMs != SIMKL_NO_CW_CUTOFF) {
                candidates = candidates.filter { it.markedAtEpochMs >= cutoffMs }
            }
        }
        candidates
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
        }.also { survivors ->
            com.nuvio.app.features.watchprogress.NextUpDiagnostics.logSeedFunnel(
                allSeeds = allNextUpSeedCandidates,
                afterDayCap = recentNextUpSeedCandidates,
                afterSuppression = survivors,
                traktActive = isTraktProgressActive,
                traktDaysCap = traktSettingsUiState.continueWatchingDaysCap,
                simklActive = simklIsAuthenticated &&
                    continueWatchingSource == ContinueWatchingSource.SIMKL,
                simklDaysCap = simklSettingsUiState.simklContinueWatchingDaysCap,
                nowEpochMs = WatchProgressClock.nowEpochMs(),
            )
        }
    }
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val activeProfileId = profileState.activeProfile?.profileIndex ?: 1
    val cwCacheClearVersion by ContinueWatchingEnrichmentCache.cacheCleared.collectAsStateWithLifecycle()
    val cwArtworkRefreshVersion by ContinueWatchingEnrichmentCache.artworkRefreshRequests
        .collectAsStateWithLifecycle()

    var nextUpItemsBySeries by remember(activeProfileId) { mutableStateOf<Map<String, Pair<Long, ContinueWatchingItem>>>(emptyMap()) }
    var processedNextUpContentIds by remember(activeProfileId) { mutableStateOf<Set<String>>(emptySet()) }
    // Lets the resolution effect tell a manual-resync run apart from the many other reasons it
    // re-runs, so only the resync pays for a meta refetch.
    // -1 makes the first settled Home pass a real artwork refresh. The persisted CW snapshot is
    // specifically what survives an app update/restart, so treating launch as already applied
    // leaves placeholder episode art untouched until the user happens to finish another episode.
    var appliedArtworkRefreshVersion by remember(activeProfileId) { mutableStateOf(-1) }

    LaunchedEffect(activeProfileId, cwCacheClearVersion) {
        com.nuvio.app.features.watchprogress.NextUpDiagnostics.reset()
        ContinueWatchingArtworkDiagnostics.reset()
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
    // Deliberately built from the raw snapshot rather than from cachedNextUpItems: that map drops
    // an entry the moment its seed moves on, which is exactly when a re-resolve happens and
    // exactly when the show's poster and backdrop are still needed as a floor.
    val cachedNextUpArtwork = remember(cachedSnapshots.first) {
        cachedSnapshots.first.associate { cached ->
            cached.contentId to CachedNextUpArtwork(
                poster = cached.poster,
                background = cached.backdrop,
                logo = cached.logo,
                seasonNumber = cached.season,
                episodeNumber = cached.episode,
                episodeThumbnail = cached.episodeThumbnail,
            )
        }
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
    val (continueWatchingRowItems, nextUpRowItems) = remember(
        continueWatchingItems,
        continueWatchingPreferences.separateNextUpRow,
    ) {
        splitContinueWatchingRows(
            items = continueWatchingItems,
            separateNextUpRow = continueWatchingPreferences.separateNextUpRow,
        )
    }

    LaunchedEffect(continueWatchingItems, tmdbImageModeOn) {
        val metadataTargets = continueWatchingItems
            .filter { it.parentMetaType.equals("series", ignoreCase = true) || it.parentMetaType.equals("anime", ignoreCase = true) }
        if (metadataTargets.isEmpty()) return@LaunchedEffect
        if (!continueWatchingMetadataStartupGraceUsed) {
            continueWatchingMetadataStartupGraceUsed = true
            delay(HOME_STARTUP_METADATA_GRACE_MS)
        }
        val sem = kotlinx.coroutines.sync.Semaphore(4)
        metadataTargets
            .forEach { item ->
                launch {
                    sem.withPermit {
                        runCatching {
                            val meta = com.nuvio.app.features.details.MetaDetailsRepository.fetchLightweightMeta(
                                type = item.parentMetaType,
                                id = item.parentMetaId,
                                preferTmdbImages = tmdbImageModeOn
                            )
                            if (meta != null && !meta.genres.isNullOrEmpty()) {
                                com.nuvio.app.features.player.AnimeContentCache.record(
                                    metaId = item.parentMetaId,
                                    genres = meta.genres,
                                    originalLanguage = meta.language,
                                    originCountries = listOfNotNull(meta.country),
                                )
                            }
                        }
                    }
                }
            }
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
                        val extrasKey = catalog.extra.joinToString(separator = "|") { extra ->
                            "${extra.name}:${extra.isRequired}:${extra.options.firstOrNull().orEmpty()}"
                        }
                        "${catalog.type}:${catalog.id}:$extrasKey"
                    })
                }
            }
            .sorted()
    }

    LaunchedEffect(catalogRefreshKey) {
        if (catalogRefreshKey.isEmpty()) return@LaunchedEffect
        HomeCatalogSettingsRepository.syncCatalogs(enabledAddons)
    }

    LaunchedEffect(collections) {
        HomeCatalogSettingsRepository.syncCollections(collections)
    }

    // Collection catalogs are otherwise only fetched when a folder is opened, so keep a page of
    // each one warm while Random Play is set to include them.
    LaunchedEffect(
        collections,
        enabledAddons,
        homeSettingsUiState.randomPlayEnabled,
        homeSettingsUiState.randomPlayIncludeCollections,
    ) {
        if (homeSettingsUiState.randomPlayEnabled && homeSettingsUiState.randomPlayIncludeCollections) {
            RandomPlayCollectionPool.ensureLoaded()
        }
    }

    // Tops the candidate pool up to RANDOM_PLAY_TARGET_CANDIDATES per card: resolves the genres and
    // release info catalog rows leave out, then pages the rows that can give more. A no-op once
    // every enabled card has enough, so this rides Home's own state changes.
    //
    // Deliberately waits for Home to finish loading. Rows are published in batches as they arrive,
    // so a pool started early would measure its shortfall against a fraction of the catalogs, spend
    // its request budget paging for titles the next batch was about to deliver, and throw the work
    // away when the row set changed under it.
    LaunchedEffect(
        homeUiState.isLoading,
        homeUiState.sections,
        randomPlayCollectionSections,
        homeSettingsUiState.randomPlayEnabled,
        homeSettingsUiState.randomPlayIncludeCollections,
        homeSettingsUiState.randomPlayCategories,
        homeSettingsUiState.randomPlayGenres,
        homeSettingsUiState.randomPlayMinimumImdbRating,
        watchedUiState.watchedKeys,
    ) {
        if (homeUiState.isLoading) return@LaunchedEffect
        RandomPlayCandidatePool.ensureFilled(
            sourceSections = randomPlaySourceSections(
                homeSections = homeUiState.sections,
                collectionSections = randomPlayCollectionSections,
                settings = homeSettingsUiState,
            ),
            settings = homeSettingsUiState,
            watchedKeys = watchedUiState.watchedKeys,
        )
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
        cwArtworkRefreshVersion,
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
            val plan = planNextUpResolution(
                completedSeriesCandidates = completedSeriesCandidates,
                cachedNextUpItems = cachedNextUpItems,
            )
            val cachedMatchingSeed = plan.cachedBySeries
            val cachedStaleArtworkItems = plan.staleArtworkContentIds
            val candidatesToResolve = plan.candidatesToResolve
            val resolutionCandidates = candidatesToResolve.take(HomeNextUpInitialResolutionLimit)
            // Only a resync or a just-completed episode bypasses the meta LRU. Every other
            // re-resolve rides the cache, so the retries cost nothing beyond the first fetch of
            // each app run.
            //
            // The version is consumed at the end of the pass, not here: the events that request a
            // refresh also churn watch progress, so this effect is often cancelled and re-keyed
            // moments later. Marking it applied up front would burn the request on a pass that
            // never got to fetch anything.
            val forceArtworkMetaRefresh = shouldForceNextUpArtworkMetaRefresh(
                requestVersion = cwArtworkRefreshVersion,
                appliedVersion = appliedArtworkRefreshVersion,
            )
            com.nuvio.app.features.watchprogress.NextUpDiagnostics.logReleasePrecisionRetry(
                contentIds = plan.staleReleasePrecisionContentIds,
            )
            com.nuvio.app.features.watchprogress.NextUpDiagnostics.logArtworkRetry(
                contentIds = cachedStaleArtworkItems,
                forcedMetaRefresh = forceArtworkMetaRefresh,
                withinBudget = resolutionCandidates.count { it.content.id in cachedStaleArtworkItems },
            )
            val seedLastWatchedMap = completedSeriesCandidates.associate { it.content.id to it.markedAtEpochMs }
            if (candidatesToResolve.isEmpty()) {
                appliedArtworkRefreshVersion = cwArtworkRefreshVersion
                withContext(Dispatchers.Main) {
                    nextUpItemsBySeries = cachedMatchingSeed
                    processedNextUpContentIds = completedSeriesCandidates.mapTo(mutableSetOf()) { candidate ->
                        candidate.content.id
                    }
                }
                saveContinueWatchingSnapshots(
                    nextUpItemsBySeries = cachedMatchingSeed,
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
                                forceMetaRefresh = forceArtworkMetaRefresh &&
                                    completedEntry.content.id in cachedStaleArtworkItems,
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
                    val progressiveResults = cachedMatchingSeed + mergeNextUpResultsWithCachedArtwork(
                        results = freshResults,
                        cachedArtwork = cachedNextUpArtwork,
                        reason = "progressive-resolve",
                    )
                    withContext(Dispatchers.Main) {
                        nextUpItemsBySeries = progressiveResults
                        processedNextUpContentIds = (
                            cachedMatchingSeed.keys +
                                processedFreshContentIds
                            ).toSet()
                    }
                }

                if (cachedMatchingSeed.size + freshResults.size >= HomeContinueWatchingMaxRecentProgressItems) {
                    break
                }
            }

            appliedArtworkRefreshVersion = cwArtworkRefreshVersion
            // The merged map is what gets saved below. A fresh result that came back without
            // artwork must not be allowed to overwrite the snapshot that still has it.
            val results = cachedMatchingSeed + mergeNextUpResultsWithCachedArtwork(
                results = freshResults,
                cachedArtwork = cachedNextUpArtwork,
                reason = if (forceArtworkMetaRefresh) "forced-artwork-refresh" else "resolve",
            )
            withContext(Dispatchers.Main) {
                nextUpItemsBySeries = results
                processedNextUpContentIds = (
                    cachedMatchingSeed.keys +
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
    val desktopHeroModeEnabled =
        isDesktop && (homeSettingsUiState.adaptiveHeroEnabled || homeSettingsUiState.tvModeEnabled)
    val showHeroSlot = homeSettingsUiState.heroEnabled &&
        (displayMode is HomeContentMode.Normal || desktopHeroModeEnabled)
    val isResolvingHeroSources = enabledAddons.any { it.isRefreshing } || homeUiState.isLoading
    val showHeroSkeleton = showHeroSlot &&
        effectiveHeroItems.isEmpty() &&
        isResolvingHeroSources
    var firstCatalogReported by remember { mutableStateOf(false) }
    var activeHeroBackdrop by remember { mutableStateOf<String?>(null) }
    // What Basic's hero is currently paging on, for a trailer request that names nothing and for
    // auto-play. Only meaningful in Basic; the other modes' heroes host their own trailers.
    var basicHeroActiveItem by remember { mutableStateOf<MetaPreview?>(null) }
    var activeHeroAccent by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(effectiveSections.firstOrNull()?.key, onFirstCatalogRendered) {
        if (firstCatalogReported || effectiveSections.isEmpty()) return@LaunchedEffect
        firstCatalogReported = true
        onFirstCatalogRendered?.invoke()
    }

    val visibleCollections = remember(collections) {
        collections.filter { it.folders.isNotEmpty() }
    }
    val collectionsMap = remember(visibleCollections) {
        visibleCollections.associateBy { "collection_${it.id}" }
    }
    val sectionsMap = remember(effectiveSections) {
        effectiveSections.associateBy(HomeCatalogSection::key)
    }
    val enabledHomeItems = remember(homeSettingsUiState.items, effectiveSections) {
        buildEnabledHomeItems(
            settingsItems = homeSettingsUiState.items,
            effectiveSections = effectiveSections,
        )
    }
    // True when the screen should look and behave exactly like Normal home mode.
    // In Search mode this stays true until the user has typed something so that CW,
    // collections, and catalog rows remain visible — seamless A-to-B transition.
    // True when search has live results to show; drives the home→search content switch.
    val searchHasResults = contentMode is HomeContentMode.Search && searchUiState.sections.isNotEmpty()
    // Show home content (CW, collections, hero, rows) until search results actually arrive.
    // Switching on searchQuery.isBlank() caused a black screen the moment the user typed.
    // Only Normal mode shows home content. Search shows nothing until results arrive
    // (blank query = empty hero + rows, not home content) so switching to Search from
    // Library doesn't pull the user to the home page.
    val isShowingHomeContent = displayMode is HomeContentMode.Normal

    // Row shuffle. The order is session state (HomeRowShuffleState), never the saved catalog order,
    // so it is applied per render rather than folded into the section itself — and the whole
    // feature collapses to null when the setting is off, leaving the header untouched.
    //
    // Home content rows only. Search, Library and Discover rows are published by their own
    // repositories, so HomeRepository cannot find them to deepen the pool — shuffling would deal
    // from a single page and, on Search, would be reordering relevance-ranked results anyway.
    //
    // Declared here rather than beside the row rendering because tvRows needs it too: that list is
    // what the immersive hero and the keyboard Enter handler index into, and it has to agree with
    // what the row actually draws.
    val rowShuffleEnabled = homeSettingsUiState.catalogRowShuffleEnabled && isShowingHomeContent
    fun shuffleOrderFor(section: HomeCatalogSection): HomeRowShuffleOrder? =
        if (rowShuffleEnabled) rowShuffleOrders[section.key] else null

    fun shuffleClickFor(section: HomeCatalogSection): (() -> Unit)? =
        if (rowShuffleEnabled && section.canShuffleRow()) {
            { HomeRowShuffleState.shuffle(section.key) }
        } else {
            null
        }
    val continueWatchingRowState = remember { HomeScrollMemory.continueWatchingRowState }
    val nextUpRowState = remember { HomeScrollMemory.nextUpRowState }
    LaunchedEffect(isShowingHomeContent, continueWatchingRowItems.isNotEmpty()) {
        if (
            isShowingHomeContent &&
            continueWatchingRowItems.isNotEmpty() &&
            !HomeScrollMemory.continueWatchingStartupResetApplied
        ) {
            continueWatchingRowState.scrollToItem(0, scrollOffset = 0)
            HomeScrollMemory.continueWatchingStartupResetApplied = true
        }
    }
    LaunchedEffect(isShowingHomeContent, nextUpRowItems.isNotEmpty()) {
        if (
            isShowingHomeContent &&
            nextUpRowItems.isNotEmpty() &&
            !HomeScrollMemory.nextUpStartupResetApplied
        ) {
            nextUpRowState.scrollToItem(0, scrollOffset = 0)
            HomeScrollMemory.nextUpStartupResetApplied = true
        }
    }
    // Applies to every catalog-row surface, not just home: Search and Library rows honour it too
    // (they never paginate, so it only decides preview-cap + arrow vs. the full loaded list).
    val catalogSeeMoreEnabled = homeSettingsUiState.catalogSeeMoreEnabled
    val defaultChromeSpacerHeight =
        if (isDesktop && !showHeroSlot && !isShowingHomeContent) topChromePadding ?: 72.dp else 0.dp
    val hasRenderableCollectionRows = remember(isShowingHomeContent, enabledHomeItems, collectionsMap) {
        isShowingHomeContent && enabledHomeItems.any { item ->
            item.isCollection && collectionsMap[item.key] != null
        }
    }

    val adaptiveHeroEnabled = homeSettingsUiState.adaptiveHeroEnabled && isDesktop
    val heroTrailerShowing by HomeHeroTrailerManualTrigger.active.collectAsStateWithLifecycle()
    val playerTrailerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val trailersEnabledForCurrentMode =
        displayMode !is HomeContentMode.Search || playerTrailerSettings.heroTvTrailerSearchEnabled
    // Basic's hero is a static rotation the user pages themselves, so it can host a trailer
    // too. Full screen only: it lives inside the rows list, and a viewport-tall list item is
    // as close to a full-screen surface as it gets without reparenting the hero mid-playback
    // — which would dispose the native video surface and stop the trailer dead.
    val basicHomeHeroTrailersEnabled = basicHeroTrailersAllowed(
        mode = homeDisplayModeOf(homeSettingsUiState),
        isDesktop = isDesktop,
        heroVisible = showHeroSlot,
        isNormalHomeMode = displayMode is HomeContentMode.Normal,
    )
    // In Adaptive Hero mode the hero is only a strip, so a full-screen trailer needs its
    // container expanded to the whole screen (TV Mode's hero already fills the viewport).
    val heroTrailerFullscreenActive =
        heroTrailerShowing && trailersEnabledForCurrentMode &&
            playerTrailerSettings.heroTvTrailerFullscreen && !basicHomeHeroTrailersEnabled
    val heroAmbientBackgroundEnabled =
        homeSettingsUiState.heroAmbientBackgroundEnabled && isDesktop && showHeroSlot
    val tvModeEnabled =
        homeSettingsUiState.tvModeEnabled && isDesktop && showHeroSlot
    val heroFocusable = showHeroSlot
    val homeTvFocus = remember(heroFocusable) {
        HomeTvFocusState { sectionIndex, itemIndex ->
            val rowIndex = sectionIndex - if (heroFocusable) 1 else 0
            if (rowIndex >= 0) {
                HomeScrollMemory.hasImmersivePosition = true
                HomeScrollMemory.immersiveRowIndex = rowIndex
                HomeScrollMemory.immersiveItemIndex = itemIndex
            }
        }.apply {
            // Restore the within-row (horizontal) position for the row we'll return to, so leaving
            // and coming back from the details screen doesn't move anything. Seeded under the
            // restored row's section key without touching sectionIndex, so fresh-launch focus (the
            // hero) is unchanged.
            val restoredSection = HomeScrollMemory.immersiveRowIndex + if (heroFocusable) 1 else 0
            restoreItemIndex(restoredSection, HomeScrollMemory.immersiveItemIndex)
            // On return, bind focus bookkeeping to the restored shelf immediately. Previously the
            // first TV-key sync read section 0 (the hero) and overwrote the restored shelf index.
            if (HomeScrollMemory.hasImmersivePosition) {
                sectionIndex = restoredSection
            }
        }
    }
    val libraryTvFocus = remember(heroFocusable) {
        HomeTvFocusState { sectionIndex, itemIndex ->
            val rowIndex = sectionIndex - if (heroFocusable) 1 else 0
            if (rowIndex >= 0) {
                LibraryScrollMemory.hasImmersivePosition = true
                LibraryScrollMemory.immersiveRowIndex = rowIndex
                LibraryScrollMemory.immersiveItemIndex = itemIndex
            }
        }.apply {
            val restoredSection = LibraryScrollMemory.immersiveRowIndex + if (heroFocusable) 1 else 0
            restoreItemIndex(restoredSection, LibraryScrollMemory.immersiveItemIndex)
            if (LibraryScrollMemory.hasImmersivePosition) {
                sectionIndex = restoredSection
            }
        }
    }
    val searchTvFocus = remember(heroFocusable, searchQuery) {
        SearchScrollMemory.resetIfQueryChanged(searchQuery)
        HomeTvFocusState { sectionIndex, itemIndex ->
            val rowIndex = sectionIndex - if (heroFocusable) 1 else 0
            if (rowIndex >= 0) {
                SearchScrollMemory.hasImmersivePosition = true
                SearchScrollMemory.immersiveRowIndex = rowIndex
                SearchScrollMemory.immersiveItemIndex = itemIndex
            }
        }.apply {
            val restoredSection = SearchScrollMemory.immersiveRowIndex + if (heroFocusable) 1 else 0
            restoreItemIndex(restoredSection, SearchScrollMemory.immersiveItemIndex)
            if (SearchScrollMemory.hasImmersivePosition) {
                sectionIndex = restoredSection
            }
        }
    }
    val catalogModeKey = (displayMode as? HomeContentMode.Catalogs)?.key
    val catalogTvFocus = remember(heroFocusable, catalogModeKey) { HomeTvFocusState() }
    val discoverTvFocus = remember(heroFocusable) {
        HomeTvFocusState { sectionIndex, itemIndex ->
            val rowIndex = sectionIndex - if (heroFocusable) 1 else 0
            if (rowIndex >= 0) {
                DiscoverScrollMemory.hasImmersivePosition = true
                DiscoverScrollMemory.immersiveRowIndex = rowIndex
                DiscoverScrollMemory.immersiveItemIndex = itemIndex
            }
        }.apply {
            val restoredSection = DiscoverScrollMemory.immersiveRowIndex + if (heroFocusable) 1 else 0
            restoreItemIndex(restoredSection, DiscoverScrollMemory.immersiveItemIndex)
            if (DiscoverScrollMemory.hasImmersivePosition) {
                sectionIndex = restoredSection
            }
        }
    }
    val tvFocus = when (displayMode) {
        is HomeContentMode.Normal -> homeTvFocus
        is HomeContentMode.Library -> libraryTvFocus
        is HomeContentMode.Search -> searchTvFocus
        is HomeContentMode.Catalogs -> catalogTvFocus
        is HomeContentMode.Discover -> discoverTvFocus
    }

    // --- Continue-watching hero preview ---
    // The Continue Watching TV row normally carries no metaItems, so the hero never follows it.
    // Giving it hero previews lets the Adaptive / Ambient / TV hero retarget to the focused CW card
    // while TV mode additionally offers a Resume action. Basic mode never follows focus (see
    // heroFollowsFocusedItem), so it is unaffected.
    val continueWatchingHeroPreviews = remember(continueWatchingRowItems) {
        continueWatchingRowItems.map { it.toHomeHeroPreview() }
    }
    val nextUpHeroPreviews = remember(nextUpRowItems) {
        nextUpRowItems.map { it.toHomeHeroPreview() }
    }
    val nextUpRowTitle = stringResource(Res.string.continue_watching_up_next)
    val nextUpImmersiveSettingsItem = remember(nextUpRowTitle) {
        HomeCatalogSettingsItem(
            key = HOME_NEXT_UP_SECTION_KEY,
            defaultTitle = nextUpRowTitle,
            addonName = "",
        )
    }
    // TV Mode auto-focuses the CW row on entry, so it gets a dismiss control to fall back to the
    // two-catalog hero. The dismissal is session-scoped state owned by the app root (so it survives
    // leaving Home) and is only cleared when playback starts or the session ends. Adaptive modes
    // need no dismiss: moving the mouse off the card reverts the hero on its own.
    val continueWatchingHeroFollowActive = !(tvModeEnabled && continueWatchingHeroDismissed)
    // Mirrors "a CW resume hero is currently focused" for the keyboard Dismiss/Escape handler, which
    // is declared before the focus is resolved. Written from a SideEffect so it always reflects the
    // latest composition without triggering one.
    val continueWatchingHeroFocused = remember { mutableStateOf(false) }
    // The poster the pointer (or TV focus) is on, for the same reason and by the same trick: the
    // T handler is declared above where the focused item is resolved. Only Basic reads it — the
    // other modes' heroes already show the focused item, so their trailer needs no target.
    val focusedTrailerTarget = remember { mutableStateOf<MetaPreview?>(null) }
    // Restart the hero-trailer dwell timer whenever TV focus moves (any input method).
    LaunchedEffect(tvFocus.sectionIndex, tvFocus.itemIndex) {
        HomeHeroTrailerGate.notifyFocusChanged()
    }
    val tvFocusRequester = remember { FocusRequester() }
    var homeRootHasFocus by remember { mutableStateOf(false) }
    var lastStateDrivenSearchQuery by remember { mutableStateOf<String?>(null) }
    // Move content-area focus when the user presses Down from the search bar.
    LaunchedEffect(navigateToContentCount) {
        if (navigateToContentCount > 0) {
            // A returning route can still own focus for part of its exit animation. Retry for a
            // short bounded window instead of relying on a request that may land too early.
            repeat(12) { attempt ->
                delay(if (attempt == 0) 50 else 48)
                runCatching { tvFocusRequester.requestFocus() }
                if (homeRootHasFocus) return@LaunchedEffect
            }
        }
    }

    // Immediate search on Enter — skips the debounce delay.
    val currentSearchQuery by rememberUpdatedState(searchQuery)
    val currentEnabledAddons by rememberUpdatedState(addonsUiState.addons.enabledAddons())
    LaunchedEffect(contentMode, searchQuery) {
        if (contentMode !is HomeContentMode.Search) return@LaunchedEffect
        val trimmedQ = searchQuery.trim()
        if (trimmedQ.isBlank()) return@LaunchedEffect
        if (lastStateDrivenSearchQuery == trimmedQ && searchUiState.query == trimmedQ) {
            return@LaunchedEffect
        }
        lastStateDrivenSearchQuery = trimmedQ
        com.nuvio.app.features.search.SearchHistoryRepository.recordSearch(trimmedQ)
        if (trimmedQ != searchUiState.query) {
            tvFocus.sectionIndex = 0
            tvFocus.itemIndex = 0
            try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
        }
        SearchRepository.search(trimmedQ, currentEnabledAddons)
    }
    LaunchedEffect(contentMode) {
        if (contentMode is HomeContentMode.Search) {
            searchSubmitRequests.collect { q ->
                val trimmedQ = q.trim()
                if (trimmedQ.isNotBlank()) {
                    lastStateDrivenSearchQuery = trimmedQ
                    com.nuvio.app.features.search.SearchHistoryRepository.recordSearch(trimmedQ)
                    if (trimmedQ != searchUiState.query) {
                        tvFocus.sectionIndex = 0
                        tvFocus.itemIndex = 0
                        try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
                    }
                    SearchRepository.search(trimmedQ, currentEnabledAddons)
                } else {
                    SearchRepository.clear()
                }
            }
        }
    }
    val tvCoroutineScope = rememberCoroutineScope()
    // Updated from BoxWithConstraints once the adaptive layout is known. Keyboard handling lives
    // outside that scope, so retain the current configured hero height here for scroll targets.
    var adaptiveHeroScrollClearancePx by remember { mutableStateOf(0) }
    val mouseActivity = rememberMouseActivityState()
    // Seed from the session-scoped holder so the immersive home layout returns to the
    // catalog row the user was on (e.g. after visiting the details screen) instead of
    // resetting to the top. Saved back whenever it changes (see below).
    var homeImmersiveRowIndex by remember { mutableStateOf(HomeScrollMemory.immersiveRowIndex) }
    var libraryImmersiveRowIndex by remember { mutableStateOf(LibraryScrollMemory.immersiveRowIndex) }
    var searchImmersiveRowIndex by remember(searchQuery) {
        SearchScrollMemory.resetIfQueryChanged(searchQuery)
        mutableStateOf(SearchScrollMemory.immersiveRowIndex)
    }
    var catalogImmersiveRowIndex by remember(catalogModeKey) { mutableStateOf(0) }
    var discoverImmersiveRowIndex by remember { mutableStateOf(DiscoverScrollMemory.immersiveRowIndex) }
    val getImmersiveRowIndex = {
        when (displayMode) {
            is HomeContentMode.Normal -> homeImmersiveRowIndex
            is HomeContentMode.Library -> libraryImmersiveRowIndex
            is HomeContentMode.Search -> searchImmersiveRowIndex
            is HomeContentMode.Catalogs -> catalogImmersiveRowIndex
            is HomeContentMode.Discover -> discoverImmersiveRowIndex
        }
    }
    val setImmersiveRowIndex = { value: Int ->
        when (displayMode) {
            is HomeContentMode.Normal -> homeImmersiveRowIndex = value
            is HomeContentMode.Library -> libraryImmersiveRowIndex = value
            is HomeContentMode.Search -> searchImmersiveRowIndex = value
            is HomeContentMode.Catalogs -> catalogImmersiveRowIndex = value
            is HomeContentMode.Discover -> discoverImmersiveRowIndex = value
        }
    }
    // Persist the immersive home row + the focused position within it so both survive leaving and
    // returning to this screen (e.g. the details view).
    if (displayMode is HomeContentMode.Normal) {
        LaunchedEffect(homeImmersiveRowIndex) {
            HomeScrollMemory.immersiveRowIndex = homeImmersiveRowIndex
        }
        LaunchedEffect(homeTvFocus.itemIndexForSection(homeImmersiveRowIndex + if (heroFocusable) 1 else 0)) {
            HomeScrollMemory.immersiveItemIndex =
                homeTvFocus.itemIndexForSection(homeImmersiveRowIndex + if (heroFocusable) 1 else 0)
        }
    }
    if (displayMode is HomeContentMode.Library) {
        LaunchedEffect(libraryImmersiveRowIndex) {
            LibraryScrollMemory.immersiveRowIndex = libraryImmersiveRowIndex
        }
        LaunchedEffect(libraryTvFocus.itemIndexForSection(libraryImmersiveRowIndex + if (heroFocusable) 1 else 0)) {
            LibraryScrollMemory.immersiveItemIndex =
                libraryTvFocus.itemIndexForSection(libraryImmersiveRowIndex + if (heroFocusable) 1 else 0)
        }
    }
    if (displayMode is HomeContentMode.Search) {
        LaunchedEffect(searchImmersiveRowIndex) {
            SearchScrollMemory.immersiveRowIndex = searchImmersiveRowIndex
        }
        LaunchedEffect(searchTvFocus.itemIndexForSection(searchImmersiveRowIndex + if (heroFocusable) 1 else 0)) {
            SearchScrollMemory.immersiveItemIndex =
                searchTvFocus.itemIndexForSection(searchImmersiveRowIndex + if (heroFocusable) 1 else 0)
        }
    }
    if (displayMode is HomeContentMode.Discover) {
        LaunchedEffect(discoverImmersiveRowIndex) {
            DiscoverScrollMemory.immersiveRowIndex = discoverImmersiveRowIndex
        }
        LaunchedEffect(discoverTvFocus.itemIndexForSection(discoverImmersiveRowIndex + if (heroFocusable) 1 else 0)) {
            DiscoverScrollMemory.immersiveItemIndex =
                discoverTvFocus.itemIndexForSection(discoverImmersiveRowIndex + if (heroFocusable) 1 else 0)
        }
    }

    var immersiveWheelLocked by remember { mutableStateOf(false) }

    val tvRows = remember(
        contentMode,
        continueWatchingPreferences.isVisible,
        continueWatchingRowItems,
        nextUpRowItems,
        continueWatchingHeroPreviews,
        nextUpHeroPreviews,
        continueWatchingHeroFollowActive,
        enabledHomeItems,
        collectionsMap,
        sectionsMap,
        effectiveSections,
        catalogSeeMoreEnabled,
        onContinueWatchingClick,
        onFolderClick,
        posterClickHandler,
        // A re-deal changes what sits at each index, and tvRows is indexed by the focused column.
        rowShuffleOrders,
        rowShuffleEnabled,
    ) {
        buildList {
            if (isShowingHomeContent && continueWatchingPreferences.isVisible && continueWatchingRowItems.isNotEmpty()) {
                add(
                    HomeTvRow(
                        itemCount = continueWatchingRowItems.size,
                        // Hero previews let the adaptive/TV hero follow the focused CW card. Nulled
                        // out while the TV dismiss is active so the hero reverts to the catalog rows.
                        metaItems = if (continueWatchingHeroFollowActive) continueWatchingHeroPreviews else null,
                        onEnter = { index ->
                            continueWatchingRowItems.getOrNull(index)?.let { onContinueWatchingClick?.invoke(it) }
                        },
                    ),
                )
            }
            if (isShowingHomeContent && continueWatchingPreferences.isVisible && nextUpRowItems.isNotEmpty()) {
                add(
                    HomeTvRow(
                        itemCount = nextUpRowItems.size,
                        metaItems = if (continueWatchingHeroFollowActive) nextUpHeroPreviews else null,
                        onEnter = { index ->
                            nextUpRowItems.getOrNull(index)?.let { onContinueWatchingClick?.invoke(it) }
                        },
                    ),
                )
            }
            if (isShowingHomeContent) enabledHomeItems.forEach { settingsItem ->
                if (settingsItem.isCollection) {
                    val collection = collectionsMap[settingsItem.key]
                    if (collection != null) {
                        val collectionHeroItems = collection.folders.map { folder ->
                            folder.homeHeroPreview(collection)
                        }
                        add(
                            HomeTvRow(
                                itemCount = collection.folders.size,
                                metaItems = collectionHeroItems.takeIf { heroItems ->
                                    heroItems.any { it != null }
                                }?.mapIndexed { index, heroItem ->
                                    // Never borrow a sibling folder's hero to fill a gap here. Folder
                                    // hero backdrops resolve asynchronously, so a folder focused before
                                    // its own art has arrived would render a different tile's backdrop
                                    // and then swap to the right one — a visible flash of the wrong
                                    // title (whichever sibling happened to resolve first). Falling back
                                    // to the folder's own cover keeps the hero on the focused folder and
                                    // simply upgrades in place once its backdrop lands.
                                    heroItem ?: MetaPreview(
                                        id = "collection:${collection.id}:${collection.folders[index].id}",
                                        type = COLLECTION_HERO_TYPE,
                                        name = collection.folders[index].title,
                                        poster = collection.folders[index].coverImageUrl,
                                        posterShape = PosterShape.Landscape,
                                    )
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
                        val usesInfiniteScroll = section.usesInfiniteHomeRow(catalogSeeMoreEnabled)
                        // Must match the row's rendered entries exactly. tvFocus.itemIndex is a
                        // position in the drawn row, and metaItems[index] is what the immersive
                        // hero shows for it — build this from section.items and a shuffled row
                        // renders one title while the hero describes a different one.
                        val shuffledItems = section.shuffled(shuffleOrderFor(section))
                        val entries = if (usesInfiniteScroll) {
                            shuffledItems
                        } else {
                            shuffledItems.take(HOME_CATALOG_PREVIEW_LIMIT)
                        }
                        add(
                            HomeTvRow(
                                itemCount = entries.size,
                                metaItems = entries,
                                onEnter = { index ->
                                    entries.getOrNull(index)?.let { posterClickHandler?.invoke(it) }
                                },
                                onLoadMore = if (usesInfiniteScroll) {
                                    { HomeRepository.loadMoreCatalogRow(section.key) }
                                } else {
                                    null
                                },
                                onRightAtEnd = if (
                                    !usesInfiniteScroll &&
                                    section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)
                                ) {
                                    onCatalogClick?.let { { it(section) } }
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                }
            }

            // Search / Library / Discover mode: add effective sections as TV rows for keyboard
            // navigation. The Discover browser row is kept even when empty — see the note on
            // rowSections; if it drops out here, tvRows goes empty and TV mode falls back to the
            // plain list, which is how switching to TV mode on an empty catalog blanked the tab.
            if (displayMode !is HomeContentMode.Normal) {
                effectiveSections.filter {
                    it.items.isNotEmpty() || it.key == DISCOVER_BROWSER_ROW_KEY ||
                        it.key.startsWith(DISCOVER_PLACEHOLDER_KEY_PREFIX)
                }.forEach { section ->
                    val usesInfiniteScroll = section.usesInfiniteHomeRow(catalogSeeMoreEnabled)
                    val entries = if (usesInfiniteScroll) {
                        section.items
                    } else {
                        section.resultRowEntries(catalogSeeMoreEnabled)
                    }
                    add(
                        HomeTvRow(
                            itemCount = entries.size,
                            metaItems = entries,
                            onEnter = { index ->
                                entries.getOrNull(index)?.let { posterClickHandler?.invoke(it) }
                            },
                            onLoadMore = if (usesInfiniteScroll && section.hasMore) {
                                onLoadMoreCatalog?.let { callback -> { callback(section) } }
                            } else {
                                null
                            },
                            onRightAtEnd = if (
                                !usesInfiniteScroll && section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)
                            ) {
                                onCatalogClick?.let { callback -> { callback(section) } }
                            } else {
                                null
                            },
                        ),
                    )
                }
            }
        }
    }

    val tvSectionCount = (if (heroFocusable) 1 else 0) + tvRows.size
    LaunchedEffect(tvRows.size) {
        setImmersiveRowIndex(getImmersiveRowIndex().coerceIn(0, (tvRows.size - 1).coerceAtLeast(0)))
    }

    fun tvRowIndexForSection(sectionIndex: Int): Int =
        if (heroFocusable) sectionIndex - 1 else sectionIndex

    fun tvItemCountForSection(sectionIndex: Int): Int {
        if (heroFocusable && sectionIndex == 0) return effectiveHeroItems.size
        return tvRows.getOrNull(tvRowIndexForSection(sectionIndex))?.itemCount ?: 0
    }

    fun scrollToFocusedTvSection() {
        val target = homeTvLazyScrollTarget(
            sectionIndex = tvFocus.sectionIndex,
            heroFocusable = heroFocusable,
            adaptiveHeroHeightPx = adaptiveHeroScrollClearancePx,
        )
        tvCoroutineScope.launch {
            currentListState.animateScrollToItem(
                index = target.itemIndex,
                scrollOffset = target.scrollOffset,
            )
        }
    }

    fun syncImmersiveTvFocusSection() {
        if (!tvModeEnabled) return
        val expectedSection = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
        if (tvFocus.sectionIndex == expectedSection) return

        // Immersive rendering follows getImmersiveRowIndex() even if sectionIndex still points at
        // the hero. Preserve the visibly highlighted poster while moving focus bookkeeping to
        // the row that is actually on screen.
        val visibleItemIndex = tvFocus.itemIndex
        tvFocus.sectionIndex = expectedSection
        tvFocus.itemIndex = visibleItemIndex.coerceIn(
            0,
            ((tvRows.getOrNull(getImmersiveRowIndex())?.itemCount ?: 0) - 1).coerceAtLeast(0),
        )
    }

    fun selectHoveredImmersiveItem(itemIndex: Int) {
        // Keyboard paging scrolls posters beneath a stationary cursor, which emits Enter events
        // for the posters passing under it. Only a real mouse move may take focus back from the
        // keyboard target; MouseActivityState distinguishes that from synthetic scroll movement.
        if (!mouseActivity.isMouseActive) return
        syncImmersiveTvFocusSection()
        tvFocus.itemIndex = itemIndex
    }

    /**
     * Jumps TV Mode straight to a row, for the row-jump dots beside the shelf title.
     *
     * Unlike the wheel/arrow steps this does *not* carry the column position across: a dot jump
     * skips arbitrarily far, where landing on whatever column the previous row happened to be on
     * is meaningless. The destination keeps its own remembered position instead (clamped, since a
     * row can have shrunk since it was last visited).
     */
    fun jumpToImmersiveRow(rowIndex: Int) {
        if (!tvModeEnabled || tvRows.isEmpty()) return
        val target = rowIndex.coerceIn(0, tvRows.lastIndex)
        if (target == getImmersiveRowIndex()) return
        setImmersiveRowIndex(target)
        tvFocus.sectionIndex = target + if (heroFocusable) 1 else 0
        tvFocus.itemIndex = tvFocus.itemIndex
            .coerceIn(0, (tvItemCountForSection(tvFocus.sectionIndex) - 1).coerceAtLeast(0))
    }

    /**
     * Runs a row change while carrying the user's column position onto the destination row.
     *
     * The index has to be snapshotted *before* the section moves: [HomeTvFocusState.itemIndex] is
     * stored per section, so the moment sectionIndex changes its getter returns the destination
     * row's own remembered index instead. Reading it afterwards wrote that remembered value back
     * onto itself, and the hero composed against it — rendering whatever item was last focused in
     * that row until the real column position arrived ~50ms later from the shelf's focus callback.
     * With an already-cached backdrop that intermediate frame is plainly visible as a flash of the
     * wrong title, which is why it never showed up on a row's first visit. Mirrors the snapshotting
     * [syncImmersiveTvFocusSection] already does.
     */
    fun withCarriedTvItemIndex(changeSection: () -> Unit) {
        val carriedItemIndex = tvFocus.itemIndex
        changeSection()
        tvFocus.itemIndex = carriedItemIndex
            .coerceIn(0, (tvItemCountForSection(tvFocus.sectionIndex) - 1).coerceAtLeast(0))
    }

    fun handleHomeTvKey(key: HomeTvKey): Boolean {
        // Snapshotted before syncImmersiveTvFocusSection() below so navigation keys pick up
        // wherever the row shelf/mouse actually left focus (including mouse-wheel or hover
        // drift that happened while the trailer was playing) instead of teleporting back to
        // a stale pre-trailer position.
        val leavingNativeTrailer = heroTrailerShowing
        syncImmersiveTvFocusSection()
        return when (key) {
        HomeTvKey.Down -> {
            mouseActivity.onKeyboardNavigation(ignoreNextMouseMove = leavingNativeTrailer)
            withCarriedTvItemIndex {
                if (tvModeEnabled) {
                    setImmersiveRowIndex((getImmersiveRowIndex() + 1)
                        .coerceAtMost((tvRows.size - 1).coerceAtLeast(0)))
                    tvFocus.sectionIndex = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
                } else {
                    tvFocus.moveSection(1, tvSectionCount)
                }
            }
            if (!tvModeEnabled) scrollToFocusedTvSection()
            true
        }
        HomeTvKey.Up -> {
            mouseActivity.onKeyboardNavigation(ignoreNextMouseMove = leavingNativeTrailer)
            withCarriedTvItemIndex {
                if (tvModeEnabled) {
                    setImmersiveRowIndex((getImmersiveRowIndex() - 1).coerceAtLeast(0))
                    tvFocus.sectionIndex = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
                } else {
                    tvFocus.moveSection(-1, tvSectionCount)
                }
            }
            if (!tvModeEnabled) scrollToFocusedTvSection()
            true
        }
        HomeTvKey.PageDown, HomeTvKey.PageUp -> {
            mouseActivity.onKeyboardNavigation(ignoreNextMouseMove = leavingNativeTrailer)
            val forward = key == HomeTvKey.PageDown
            if (tvModeEnabled) {
                // TV mode shows one row at a time, so a page is a run of posters along it.
                tvFocus.moveItem(
                    if (forward) PAGE_ITEM_STEP else -PAGE_ITEM_STEP,
                    tvItemCountForSection(tvFocus.sectionIndex),
                )
            } else {
                withCarriedTvItemIndex {
                    tvFocus.moveSection(
                        if (forward) PAGE_SECTION_STEP else -PAGE_SECTION_STEP,
                        tvSectionCount,
                    )
                }
                // A page is a physical viewport jump, independent of which visible poster the
                // mouse most recently hovered. Scrolling to the logical section instead made the
                // distance shorter whenever that poster happened to sit low in the viewport.
                val pageDistance = currentListState.layoutInfo.viewportSize.height.toFloat()
                tvCoroutineScope.launch {
                    currentListState.animateScrollBy(if (forward) pageDistance else -pageDistance)
                }
            }
            true
        }
        HomeTvKey.Home, HomeTvKey.End -> {
            mouseActivity.onKeyboardNavigation(ignoreNextMouseMove = leavingNativeTrailer)
            val toStart = key == HomeTvKey.Home
            if (tvModeEnabled) {
                setImmersiveRowIndex(if (toStart) 0 else (tvRows.size - 1).coerceAtLeast(0))
                tvFocus.sectionIndex = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
            } else {
                tvFocus.sectionIndex = if (toStart) 0 else (tvSectionCount - 1).coerceAtLeast(0)
            }
            // Both ends land on the first item of the row: carrying a deep column position into
            // a jump to the top reads as landing somewhere arbitrary.
            tvFocus.itemIndex = 0
            if (!tvModeEnabled) scrollToFocusedTvSection()
            true
        }
        HomeTvKey.Right -> {
            mouseActivity.onKeyboardNavigation(ignoreNextMouseMove = leavingNativeTrailer)
            val itemCount = tvItemCountForSection(tvFocus.sectionIndex)
            val row = tvRows.getOrNull(tvRowIndexForSection(tvFocus.sectionIndex))
            if (tvFocus.itemIndex >= itemCount - 1) {
                // At the end of the row: paginating rows load the next page; non-paginating rows
                // open the full grid (the View-all destination). Otherwise focus simply stays put.
                when {
                    row?.onLoadMore != null -> row.onLoadMore.invoke()
                    row?.onRightAtEnd != null -> row.onRightAtEnd.invoke()
                }
            } else {
                tvFocus.moveItem(1, itemCount)
            }
            true
        }
        HomeTvKey.Left -> {
            mouseActivity.onKeyboardNavigation(ignoreNextMouseMove = leavingNativeTrailer)
            tvFocus.moveItem(-1, tvItemCountForSection(tvFocus.sectionIndex))
            true
        }
        HomeTvKey.Select -> {
            if (resumeHeroPreview != null && onResumePromptAction != null) {
                onResumePromptAction()
            } else if (heroFocusable && tvFocus.sectionIndex == 0) {
                effectiveHeroItems.getOrNull(tvFocus.itemIndex)?.let { posterClickHandler?.invoke(it) }
            } else {
                tvRows.getOrNull(tvRowIndexForSection(tvFocus.sectionIndex))
                    ?.onEnter
                    ?.invoke(tvFocus.itemIndex)
            }
            true
        }
        HomeTvKey.ToggleTrailer -> {
            // Logged before the gate: the absence of this line means the key never reached
            // the handler at all (the home root did not hold Compose focus), which is a
            // different fault from the gate refusing it.
            co.touchlab.kermit.Logger.withTag("HomeHeroTrailer").i {
                "ToggleTrailer key: modeOk=$trailersEnabledForCurrentMode " +
                    "adaptive=$adaptiveHeroEnabled tv=$tvModeEnabled " +
                    "basic=$basicHomeHeroTrailersEnabled"
            }
            if (trailersEnabledForCurrentMode &&
                (adaptiveHeroEnabled || tvModeEnabled || basicHomeHeroTrailersEnabled)
            ) {
                // Basic's hero shows trending titles the user did not choose, so T there means
                // "play the trailer for what I am pointing at", falling back to the hero when the
                // pointer is not on a poster. Adaptive/TV pass nothing: their hero is already it.
                HomeHeroTrailerManualTrigger.trigger(
                    if (basicHomeHeroTrailersEnabled) focusedTrailerTarget.value else null,
                )
                true
            } else false
        }
        HomeTvKey.ToggleMute -> {
            // Always toggle + consume. heroTrailerShowing is the global manual-trigger flag and can
            // be stomped when two catalog heroes mount at startup (one instance's setActive(false)
            // races the other's true), so gating on it let M fall through during autoplay — the
            // unconsumed key then shifted focus and stopped the trailer. Toggling when nothing is
            // playing is harmless: the mute flag is re-derived from the sound setting on focus change.
            HeroTrailerAudioState.toggleMuted()
            true
        }
        HomeTvKey.VolumeDown -> {
            // [ / ] step trailer volume as a keyboard alternative to the overlay slider.
            HeroTrailerAudioState.nudgeVolume(-5)
            true
        }
        HomeTvKey.VolumeUp -> {
            HeroTrailerAudioState.nudgeVolume(5)
            true
        }
        HomeTvKey.TogglePeoplePanel -> {
            if (adaptiveHeroEnabled || tvModeEnabled) {
                HomeHeroPeoplePanelToggleTrigger.trigger()
                true
            } else {
                false
            }
        }
        HomeTvKey.Dismiss -> {
            if (resumeHeroPreview != null && onResumePromptDismiss != null) {
                onResumePromptDismiss()
                true
            } else if (heroTrailerShowing) {
                HomeHeroTrailerManualTrigger.trigger()
                true
            } else if (tvModeEnabled && continueWatchingHeroFocused.value) {
                // Escape closes the continue-watching resume hero (its only keyboard exit) and
                // reverts to the catalog hero for the rest of the session.
                onContinueWatchingHeroDismiss()
                true
            } else if (contentMode is HomeContentMode.Search) {
                onNavigateToHome?.invoke()
                true
            } else if (contentMode is HomeContentMode.Catalogs && onBack != null) {
                onBack()
                true
            } else false
        }
        HomeTvKey.Search -> {
            onNavigateToSearch?.invoke()
            true
        }
        HomeTvKey.Library -> {
            onNavigateToLibrary?.invoke()
            true
        }
        }
    }

    val latestHomeTvKeyHandler = rememberUpdatedState<(HomeTvKey) -> Boolean>(::handleHomeTvKey)
    LaunchedEffect(Unit) {
        HomeTvKeyboardBridge.keys.collect { key -> latestHomeTvKeyHandler.value(key) }
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

    LaunchedEffect(adaptiveHeroEnabled, tvModeEnabled, contentMode) {
        // Search: nav bar's text field holds focus — don't steal it.
        // Normal + Library: content area must hold focus for hotkeys to work.
        if (contentMode is HomeContentMode.Search) return@LaunchedEffect
        delay(50)
        try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
    }
    val immersiveRows = remember(
        contentMode,
        continueWatchingPreferences.isVisible,
        continueWatchingRowItems,
        nextUpRowItems,
        nextUpImmersiveSettingsItem,
        enabledHomeItems,
        collectionsMap,
        sectionsMap,
        effectiveSections,
    ) {
        buildList<HomeCatalogSettingsItem?> {
            if (isShowingHomeContent) {
                if (continueWatchingPreferences.isVisible && continueWatchingRowItems.isNotEmpty()) {
                    add(null)
                }
                if (continueWatchingPreferences.isVisible && nextUpRowItems.isNotEmpty()) {
                    add(nextUpImmersiveSettingsItem)
                }
                enabledHomeItems.forEach { settingsItem ->
                    val isRenderable = if (settingsItem.isCollection) {
                        collectionsMap[settingsItem.key]?.folders?.isNotEmpty() == true
                    } else {
                        sectionsMap[settingsItem.key]?.items?.isNotEmpty() == true
                    }
                    if (isRenderable) add(settingsItem)
                }
            } else {
                // Search / Library / Discover: one immersive row per effective section. Same
                // empty-row exemption as tvRows — these two lists are index-aligned, so a section
                // present in one and absent from the other desynchronises the row-jump dots.
                effectiveSections.filter {
                    it.items.isNotEmpty() || it.key == DISCOVER_BROWSER_ROW_KEY ||
                        it.key.startsWith(DISCOVER_PLACEHOLDER_KEY_PREFIX)
                }.forEach { section ->
                    add(HomeCatalogSettingsItem(
                        key = section.key,
                        defaultTitle = section.title,
                        addonName = section.addonName,
                    ))
                }
            }
        }
    }

    // 1-based position of each rendered row, for the optional "Trending • 3" header suffix.
    // Collection rows (Discover, Streaming Platforms, …) are numbered alongside catalog rows since
    // they occupy a slot in the same vertical order; Continue Watching is deliberately not counted,
    // so numbering starts at the first real content row. A row that renders nothing takes no number.
    val catalogRowNumbers = remember(isShowingHomeContent, enabledHomeItems, sectionsMap, collectionsMap) {
        if (!isShowingHomeContent) {
            emptyMap()
        } else {
            buildMap {
                var nextRowNumber = 1
                enabledHomeItems.forEach { settingsItem ->
                    val isRenderable = if (settingsItem.isCollection) {
                        collectionsMap[settingsItem.key]?.folders?.isNotEmpty() == true
                    } else {
                        sectionsMap[settingsItem.key]?.items?.isNotEmpty() == true
                    }
                    if (isRenderable) put(settingsItem.key, nextRowNumber++)
                }
            }
        }
    }

    // TV Mode row-jump dots: one dot per immersive row, index-aligned with immersiveRows/tvRows so a
    // dot's position is the row index to jump to. Built even when the strip is off (cheap, and it
    // keeps the toggle from restructuring composition), but only rendered by tvRowDotsContent.
    val continueWatchingRowLabel = stringResource(Res.string.compose_settings_page_continue_watching)
    val tvRowDots = remember(
        immersiveRows,
        catalogRowNumbers,
        continueWatchingRowLabel,
        homeSettingsUiState.catalogRowNumbersEnabled,
    ) {
        immersiveRows.map { settingsItem ->
            val rowNumber = if (homeSettingsUiState.catalogRowNumbersEnabled) {
                settingsItem?.key?.let { catalogRowNumbers[it] }
            } else {
                null
            }
            HomeTvRowDot(
                rowKey = settingsItem?.key ?: HomeTvContinueWatchingRowKey,
                label = when {
                    settingsItem == null -> continueWatchingRowLabel
                    rowNumber != null -> "${settingsItem.displayTitle} • $rowNumber"
                    else -> settingsItem.displayTitle
                },
                markerColor = settingsItem?.markerColor?.composeColor,
            )
        }
    }
    // Owned here, not inside the strip: the shelf (header included) is rebuilt on every row change.
    val tvRowDotsListState = remember { LazyListState() }
    val tvRowDotsContent: (@Composable () -> Unit)? =
        if (tvModeEnabled && homeSettingsUiState.tvRowDotsEnabled && tvRowDots.size > 1) {
            {
                HomeTvRowDotStrip(
                    dots = tvRowDots,
                    activeIndex = getImmersiveRowIndex(),
                    onDotClick = { rowIndex -> jumpToImmersiveRow(rowIndex) },
                    listState = tvRowDotsListState,
                    anchor = homeSettingsUiState.tvRowDotsAnchor,
                )
            }
        } else {
            null
        }

    val tvFocusedRowIndex = when {
        tvModeEnabled -> getImmersiveRowIndex()
        isDesktop -> tvRowIndexForSection(tvFocus.sectionIndex)
        else -> -1
    }
    val tvFocusedHeroItemRaw = if (tvFocusedRowIndex >= 0) {
        tvRows.getOrNull(tvFocusedRowIndex)?.metaItems?.getOrNull(tvFocus.itemIndex)
    } else {
        null
    }
    SideEffect {
        focusedTrailerTarget.value = tvFocusedHeroItemRaw
    }
    // For Search/Library: hold hero on the previous item while the new one enriches.
    // For Normal (home): pass through directly — addon already provides good images.
    var displayedFocusedItem by remember { mutableStateOf<MetaPreview?>(null) }
    LaunchedEffect(tvFocusedHeroItemRaw, searchLibraryBackdropEnrichmentEnabled, displayMode) {
        val normalHomeFocusedFallback = displayMode is HomeContentMode.Normal &&
            tvFocusedHeroItemRaw?.needsHomeHeroMetadataEnrichment() == true
        // A row with no backdrop of its own is held too, whatever the image-source setting: the
        // local library's rows are poster-only, and rendering one straight through means the hero
        // stretches a portrait poster across the backdrop instead of waiting for real art.
        val focusedNeedsBackdrop = displayMode !is HomeContentMode.Normal &&
            tvFocusedHeroItemRaw?.banner.isNullOrBlank()
        val isEnrichedMode = (searchLibraryBackdropEnrichmentEnabled &&
            displayMode !is HomeContentMode.Normal) ||
            normalHomeFocusedFallback ||
            focusedNeedsBackdrop
        // A row missing only its *text* (the local library, cloud-library filename rows) is still
        // perfectly presentable — it has its title and its art — so it is never held back. It is
        // shown at once and upgraded in place when its genres/synopsis land, which is what stops
        // the hero reading as a bare title over "Movie" / "Library" for the rest of the session.
        suspend fun upgradeWhenTextArrives(raw: MetaPreview) {
            if (!raw.needsHeroTextEnrichment()) return
            val key = canonicalHeroKey(raw.type, raw.id)
            val enriched = withTimeoutOrNull(FOCUSED_HERO_ENRICHMENT_HOLD_MS) {
                snapshotFlow { heroEnrichmentMap[key] }
                    .filterNotNull()
                    .first()
            }
            if (enriched == null) {
                // The window expired, so release the held slot and let the title text render as
                // the current answer. Do NOT stop listening: this is an upgrade, not a hold, and
                // nothing is blocked by waiting longer. The card focused at launch routinely loses
                // this race because the batch pass publishes every other hero key first, and
                // giving up here left it unenriched until it was re-focused, which re-ran this
                // effect and read the by-then-populated map.
                logHeroPick("upgradeTimedOut", raw)
                if (raw.heroMetadataPending) {
                    displayedFocusedItem = raw.copy(heroMetadataPending = false)
                }
                val late = snapshotFlow { heroEnrichmentMap[key] }
                    .filterNotNull()
                    .first()
                logHeroPick("upgradeArrivedLate", late)
                displayedFocusedItem = late
                return
            }
            logHeroPick("upgradeWhenTextArrives", enriched)
            displayedFocusedItem = enriched
        }
        if (!isEnrichedMode) {
            logHeroPick("notEnrichedMode", tvFocusedHeroItemRaw)
            displayedFocusedItem = tvFocusedHeroItemRaw
            tvFocusedHeroItemRaw?.let { upgradeWhenTextArrives(it) }
            return@LaunchedEffect
        }
        val raw = tvFocusedHeroItemRaw
        if (raw == null) {
            logHeroPick("rawNull", null)
            displayedFocusedItem = null
            return@LaunchedEffect
        }
        // A row already carrying provider-quality *backdrop* art has nothing to wait for — the pass
        // would only reproduce it. Deliberately not a poster check: a poster is not a backdrop, and
        // treating one as "already resolved" is what put a stretched poster behind the hero.
        if (raw.banner.isMetadataProviderArtUrl()) {
            // The backdrop is already provider-quality so this copy shows at once, but a row with
            // no logo of its own is about to receive one from the enrichment below. Mark the logo
            // unresolved so the slot stays empty rather than painting the title text and replacing
            // it a moment later; upgradeWhenTextArrives clears the marker either way.
            val pending = raw.withPendingLogoSlot()
            logHeroPick("providerArtRaw", pending)
            displayedFocusedItem = pending
            upgradeWhenTextArrives(pending)
            return@LaunchedEffect
        }
        val key = canonicalHeroKey(raw.type, raw.id)
        if (normalHomeFocusedFallback) {
            heroEnrichmentMap[key]?.let { enriched ->
                logHeroPick("normalFallbackCached", enriched)
                displayedFocusedItem = enriched
                return@LaunchedEffect
            }
        }
        // Bounded hold: an item no provider can enrich (an unresolved cloud filename, a failed
        // lookup) would otherwise leave the hero on the previously focused card indefinitely.
        val enriched = withTimeoutOrNull(FOCUSED_HERO_ENRICHMENT_HOLD_MS) {
            snapshotFlow { heroEnrichmentMap[key] }
                .filterNotNull()
                .first()
        }
        logHeroPick(if (enriched != null) "boundedHoldEnriched" else "boundedHoldTimedOut", enriched ?: raw)
        displayedFocusedItem = enriched ?: raw
    }
    // displayedFocusedItem is only ever written from the effect above, which runs after the
    // composition that already saw the new focus — so it lags every focus change by at least a
    // frame, and in the enrichment branch for as long as the enrichment takes. On Home that means
    // moving between rows renders the previously focused title in the hero before snapping to the
    // right one. Fall back to the focused item whenever the held one has drifted off it: the hero
    // then shows the correct title immediately and the enriched copy (same key) upgrades it in
    // place. Search/Library keep the hold — their raw results routinely have no usable backdrop,
    // and holding the previous hero is deliberate there.
    val tvFocusedHeroItem = if (displayMode is HomeContentMode.Normal) {
        displayedFocusedItem?.takeIf { held ->
            val raw = tvFocusedHeroItemRaw
            raw != null && canonicalHeroKey(held.type, held.id) == canonicalHeroKey(raw.type, raw.id)
        }
        // The same pending-logo marker the effect applies. This branch renders a frame or more
        // before the effect catches up, so without it the slot would paint the title text here and
        // then blank it once the effect's marked copy lands - turning one swap into two.
            ?: tvFocusedHeroItemRaw?.withPendingLogoSlot()
    } else {
        displayedFocusedItem
    }
    // The Basic home hero is a static rotation through the user's 1–2 chosen hero catalogs; it
    // must NOT retarget to whatever poster is focused — that "follow the focused object" behaviour
    // belongs to Adaptive/Adaptive Ambient and TV Mode. Search and Library always preview the
    // focused result (their whole point), so they keep following focus regardless of hero mode.
    val heroFollowsFocusedItem =
        displayMode !is HomeContentMode.Normal || adaptiveHeroEnabled || tvModeEnabled
    val heroFocusedItem = if (heroFollowsFocusedItem) tvFocusedHeroItem else null

    // Resolve which resume affordance (if any) the hero should show. Launch crash-recovery takes
    // priority and replaces the whole hero. A focused Continue Watching card gets the Resume action
    // only in TV mode; Adaptive and Adaptive Ambient keep the preview but use the normal hero click
    // to open details, avoiding a clipped action in their shorter hero.
    val continueWatchingDisplayRows = remember(continueWatchingRowItems, nextUpRowItems) {
        buildList {
            if (continueWatchingRowItems.isNotEmpty()) add(continueWatchingRowItems)
            if (nextUpRowItems.isNotEmpty()) add(nextUpRowItems)
        }
    }
    val continueWatchingRowPresent = isShowingHomeContent &&
        continueWatchingPreferences.isVisible && continueWatchingDisplayRows.isNotEmpty()
    val focusedContinueWatchingItem: ContinueWatchingItem? = if (
        resumeHeroPreview == null &&
        heroFollowsFocusedItem &&
        continueWatchingHeroFollowActive &&
        continueWatchingRowPresent &&
        tvFocusedRowIndex in continueWatchingDisplayRows.indices
    ) {
        continueWatchingDisplayRows[tvFocusedRowIndex].getOrNull(tvFocus.itemIndex)
    } else {
        null
    }
    val effectiveResumeItemKey: String?
    val effectiveOnResumeAction: (() -> Unit)?
    val effectiveOnResumeDismiss: (() -> Unit)?
    when {
        resumeHeroPreview != null -> {
            effectiveResumeItemKey = resumeHeroKey
            effectiveOnResumeAction = onResumePromptAction
            effectiveOnResumeDismiss = onResumePromptDismiss
        }
        focusedContinueWatchingItem != null && tvModeEnabled -> {
            effectiveResumeItemKey =
                "${focusedContinueWatchingItem.parentMetaType}:${focusedContinueWatchingItem.parentMetaId}"
            effectiveOnResumeAction = {
                (onContinueWatchingPlay ?: onContinueWatchingClick)?.invoke(focusedContinueWatchingItem)
            }
            effectiveOnResumeDismiss = onContinueWatchingHeroDismiss
        }
        else -> {
            effectiveResumeItemKey = null
            effectiveOnResumeAction = null
            effectiveOnResumeDismiss = null
        }
    }
    // Expose to the keyboard Dismiss/Escape handler (declared earlier) whether the CW resume hero is
    // currently the focused hero — only then should Escape close it (TV Mode only).
    SideEffect {
        continueWatchingHeroFocused.value = focusedContinueWatchingItem != null
    }

    // Search ahead for the focused Continue Watching / Up Next card, so pressing play on it skips
    // the scrape. Deliberately *not* `focusedContinueWatchingItem`: that one is gated on the hero
    // following focus and so is null in Basic hero mode, while the row is worth preparing whichever
    // hero the user runs.
    val prefetchContinueWatchingItem: ContinueWatchingItem? = if (
        continueWatchingRowPresent &&
        tvFocusedRowIndex in continueWatchingDisplayRows.indices
    ) {
        continueWatchingDisplayRows[tvFocusedRowIndex].getOrNull(tvFocus.itemIndex)
    } else {
        null
    }
    // Focus starts on the first card of the row, so the first item this sees is the app opening
    // rather than the user choosing — and that entry is deliberately prepared too. "Open the app and
    // press play" is the path that otherwise always pays full scrape latency, and the top of
    // Continue Watching is the single best guess anyone can make about what is about to be played.
    LaunchedEffect(
        prefetchContinueWatchingItem?.parentMetaType,
        prefetchContinueWatchingItem?.parentMetaId,
        prefetchContinueWatchingItem?.videoId,
        prefetchContinueWatchingItem?.seasonNumber,
        prefetchContinueWatchingItem?.episodeNumber,
    ) {
        val item = prefetchContinueWatchingItem
        if (item == null) {
            // Focus moved off the row entirely; whatever was pending is for a card the user left.
            StreamPrefetchService.cancel()
            return@LaunchedEffect
        }
        // Cloud-library entries play through the provider's own file listing, never the addon
        // stream pipeline, so there is nothing here for a stream search to prepare.
        if (item.isCloudLibraryContinueWatchingItem()) return@LaunchedEffect
        StreamPrefetchService.request(
            StreamPrefetchService.Target(
                trigger = StreamPrefetchService.Trigger.ContinueWatching,
                type = item.parentMetaType,
                parentMetaId = item.parentMetaId,
                videoId = item.videoId,
                title = item.title,
                season = item.seasonNumber,
                episode = item.episodeNumber,
            ),
        )
    }
    // Prefetch for Search and Library: warm only the first few metadata targets per row.
    // next section in full. Search/library sets are small (20–50 items) so this is cheap.
    // Home is excluded — the addon handles it in real-time.
    // Identity of the row this effect actually enriches. Keying the effect on the focus coordinates
    // alone is not enough: the Continue Watching row finishes loading and reorders *after* the first
    // composition, so the item at focus (0,0) changes from one title to another while the
    // coordinates stay put. The effect therefore never re-ran, and the item that ended up displayed
    // never had an enrichment dispatched for it at all — it sat blank until the hold expired and
    // then kept its bare title for the rest of the session. Moving the mouse changed itemIndex,
    // which is why it only ever populated after focusing something else and coming back.
    //
    // The canonical key, not the item: the enriched copy is fed back into this composable, so
    // keying on the object itself would restart the effect on its own result.
    val focusedHeroEnrichmentKey = tvFocusedHeroItemRaw?.let { canonicalHeroKey(it.type, it.id) }
    LaunchedEffect(
        tvFocus.sectionIndex,
        tvFocus.itemIndex,
        focusedHeroEnrichmentKey,
        // Re-run once the addon list settles. Now that the focused enrichment fires as soon as the
        // hero has an item, it can land before the addons are loaded — the lookup then has no meta
        // provider to ask, comes back with nothing but a Metahub logo, and (being cached) never
        // tries again. Mirrors the retry WatchProgressRepository already does for the same reason.
        enabledAddonCount,
        displayMode,
        searchLibraryBackdropEnrichmentEnabled,
    ) {
        val normalHomeMode = displayMode is HomeContentMode.Normal
        val focusedNeedsHomeFallback = normalHomeMode &&
            tvFocusedHeroItemRaw?.needsHomeHeroMetadataEnrichment() == true
        // Poster-only rows (the local library, whose items come from disk with a TMDB poster and
        // nothing else) always earn a lookup for the focused item, even with the image source left
        // on Addon: `fetchLightweightMeta` is the shared path Trakt/SIMKL rows already take, and it
        // resolves the backdrop and logo through whatever "Hero backdrop & logo" is set to.
        // The same holds for their *text*: a row with no genres or synopsis of its own has to be
        // fetched or the hero reads as a bare title over "Movie" / "Library".
        val focusedNeedsBackdrop = !normalHomeMode &&
            tvFocusedHeroItemRaw?.let { raw ->
                raw.banner.isNullOrBlank() || raw.needsHeroTextEnrichment()
            } == true
        if (!searchLibraryBackdropEnrichmentEnabled && !focusedNeedsHomeFallback && !focusedNeedsBackdrop) {
            return@LaunchedEffect
        }
        if (normalHomeMode && !focusedNeedsHomeFallback) return@LaunchedEffect

        val isTvForTvdb = tmdbSettingsUiState.heroImageSource == HeroImageSource.TmdbMoviesTvdbShows

        suspend fun fetchEnrichment(raw: MetaPreview) {
            val meta = runCatching {
                MetaDetailsRepository.fetchLightweightMeta(
                    raw.metadataType,
                    raw.metadataId,
                    preferTmdbImages = true,
                )
            }.getOrNull()
            if (normalHomeMode) {
                val imdbId = raw.homeHeroFallbackImdbId()
                val metahubBackdrop = if (meta?.background.isNullOrBlank() && imdbId != null) {
                    MetahubService.getValidBackgroundUrl(imdbId)
                } else {
                    null
                }
                val metahubLogo = if (raw.logo.isNullOrBlank() && meta?.logo.isNullOrBlank() && imdbId != null) {
                    MetahubService.getValidLogoUrl(imdbId)
                } else {
                    null
                }
                val enriched = raw.copy(
                    banner = bestBackdrop(meta?.background, metahubBackdrop, raw.banner),
                    logo = raw.logo ?: meta?.logo ?: metahubLogo,
                    ageRating = raw.ageRating ?: meta?.ageRating,
                    runtime = raw.runtime ?: meta?.runtime,
                ).withFetchedHeroText(meta)
                publishHeroEnrichment(heroEnrichmentMap, enriched, "normalHomeEnrich")
                return
            }
            // Publish the row as-is when no provider could add anything, so whoever is waiting on
            // this key (the hero hold, a suppressed landscape card) stops waiting immediately
            // instead of sitting on the timeout.
            val fetchedMeta = meta ?: run {
                publishHeroEnrichment(heroEnrichmentMap, raw, "noProviderData")
                return
            }
            val tvdb = isTvForTvdb && (raw.type.equals("series", ignoreCase = true) ||
                raw.type.equals("anime", ignoreCase = true))
            val enriched = raw.copy(
                genres = fetchedMeta.genres.ifEmpty { raw.genres }.map(::normalizeSearchGenre),
                description = fetchedMeta.description ?: raw.description,
                releaseInfo = fetchedMeta.releaseInfo ?: raw.releaseInfo,
                runtime = raw.runtime ?: fetchedMeta.runtime,
                ageRating = raw.ageRating ?: fetchedMeta.ageRating,
                banner = if ((tvdb && fetchedMeta.background?.contains("artworks.thetvdb.com") == true) ||
                    fetchedMeta.background.isAnimeSeasonArtUrl()
                )
                    fetchedMeta.background else bestBackdrop(fetchedMeta.background, raw.banner),
                logo = when {
                    tvdb && fetchedMeta.logo?.contains("artworks.thetvdb.com") == true -> fetchedMeta.logo
                    // Prefer TMDB-direct logo over existing (e.g. Trakt sets Fanart.tv logos).
                    fetchedMeta.logo?.contains("image.tmdb.org") == true -> fetchedMeta.logo
                    else -> raw.logo ?: fetchedMeta.logo
                },
            )
            publishHeroEnrichment(heroEnrichmentMap, enriched, "searchLibraryEnrich")
        }

        fun enrich(raw: MetaPreview) = launch {
            val mapKey = canonicalHeroKey(raw.type, raw.id)
            // A cached result only counts as final if it actually resolved the text. The pass
            // publishes whatever it got even when a provider added nothing, so that anyone waiting
            // on this key stops waiting — but treating that as done cached the failure for the rest
            // of the session, and the row kept a bare title no matter how often it was focused.
            // Retrying costs one lookup per focus for a title that genuinely has no metadata, which
            // is the cheaper side of the trade.
            val cached = heroEnrichmentMap[mapKey]
            if (cached != null && !cached.needsHeroTextEnrichment()) return@launch
            pendingHeroEnrichments[mapKey] = Unit
            co.touchlab.kermit.Logger.withTag("HeroLogoRace").i {
                "t=${heroProbeMs()} FOCUSED enrich start key=$mapKey"
            }
            try {
                fetchEnrichment(raw)
            } finally {
                pendingHeroEnrichments.remove(mapKey)
                co.touchlab.kermit.Logger.withTag("HeroLogoRace").i {
                    "t=${heroProbeMs()} FOCUSED enrich done key=$mapKey"
                }
            }
        }

        // Current item — immediately, highest priority.
        tvFocusedHeroItemRaw?.let { enrich(it) }
        // Warming whole rows is the backdrop-mode behaviour; a lookup earned only by the focused
        // item's missing backdrop stops there rather than fanning out across the row.
        if (normalHomeMode || !searchLibraryBackdropEnrichmentEnabled) return@LaunchedEffect

        val rowPrefetchLimit = if (posterCardStyle.catalogLandscapeModeEnabled) {
            SEARCH_LIBRARY_LANDSCAPE_METADATA_PREFETCH_LIMIT
        } else {
            SEARCH_LIBRARY_METADATA_PREFETCH_LIMIT
        }

        // Warm the rendered row without waiting for hover. tvFocus.sectionIndex includes the hero
        // slot when it is focusable, whereas tvFocusedRowIndex is normalized to tvRows.
        tvRows.getOrNull(tvFocusedRowIndex)?.metaItems?.let { items ->
            items.drop(tvFocus.itemIndex + 1)
                .take(rowPrefetchLimit)
                .forEach { enrich(it) }
        }

        // Entire next row — ready before the user even gets there.
        tvRows.getOrNull(tvFocusedRowIndex + 1)?.metaItems
            ?.take(rowPrefetchLimit)
            ?.forEach { enrich(it) }
    }
    val immersiveMetadataPrefetchItems = if (tvModeEnabled) {
        // Home and Library warm ahead of the focused row: both are stable, user-owned catalogs whose
        // metadata is worth caching. Bounded rather than unlimited, though - this used to pass
        // Int.MAX_VALUE, handing two entire rows to a consumer that caps concurrency but not the
        // number of targets, so a large library queued thousands of speculative enrichments for a
        // hero that shows one title at a time. Search is different every time and most results are
        // noise (ranked, so what matters is the top of each catalog), so there we cap the hero's
        // eager (rate-limited MDBList) enrichment to the first couple per catalog; deeper results
        // still enrich on demand once focused.
        val perRowLimit = if (displayMode is HomeContentMode.Search) {
            SEARCH_HERO_METADATA_PREFETCH_PER_CATALOG
        } else {
            IMMERSIVE_HERO_METADATA_PREFETCH_PER_ROW
        }
        buildList {
            tvRows.getOrNull(getImmersiveRowIndex())?.metaItems?.take(perRowLimit)?.let(::addAll)
            tvRows.getOrNull(getImmersiveRowIndex() + 1)?.metaItems?.take(perRowLimit)?.let(::addAll)
        }
    } else {
        emptyList()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(tvFocusRequester)
            .onFocusChanged { homeRootHasFocus = it.hasFocus }
            .focusable()
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { _ ->
                try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
            }
            .onPreviewKeyEvent { event ->
                when {
                    // Escape closes an open Discover picker before anything else can act on it,
                    // so it never falls through to dismissing the whole screen.
                    discoverPickerSegment != null && event.key == Key.Escape -> {
                        if (event.type == KeyEventType.KeyUp) discoverPickerSegment = null
                        true
                    }
                    appShortcutMatches(AppShortcutAction.OpenSearch, event) -> {
                        if (event.type == KeyEventType.KeyUp) {
                            onNavigateToSearch?.invoke()
                        }
                        true
                    }
                    appShortcutMatches(AppShortcutAction.GoHome, event) -> {
                        if (event.type == KeyEventType.KeyUp) {
                            onNavigateToHome?.invoke()
                        }
                        true
                    }
                    appShortcutMatches(AppShortcutAction.OpenLibrary, event) -> {
                        if (event.type == KeyEventType.KeyUp) {
                            // Toggle: L from Library returns Home; from anywhere else goes to Library.
                            if (contentMode is HomeContentMode.Library) {
                                onNavigateToHome?.invoke()
                            } else {
                                onNavigateToLibrary?.invoke()
                            }
                        }
                        true
                    }
                    appShortcutMatches(AppShortcutAction.OpenDiscover, event) -> {
                        if (event.type == KeyEventType.KeyUp) {
                            // Same toggle shape as Library: from Discover it returns Home.
                            if (contentMode is HomeContentMode.Discover) {
                                onNavigateToHome?.invoke()
                            } else {
                                onNavigateToDiscover?.invoke()
                            }
                        }
                        true
                    }
                    appShortcutMatches(AppShortcutAction.OpenCalendar, event) -> {
                        if (event.type == KeyEventType.KeyUp) {
                            onNavigateToCalendar?.invoke()
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
                            if (tvModeEnabled) {
                                Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
                                    // An open Discover picker owns the wheel: without this the shelf
                                    // jumps to another row under the panel, and the panel's own list
                                    // never scrolls.
                                    if (discoverPickerSegment != null) return@onPointerEvent
                                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                                    val direction = change.scrollDelta.y.compareTo(0f)
                                    if (direction != 0) {
                                        change.consume()
                                        if (!immersiveWheelLocked && tvRows.isNotEmpty()) {
                                            immersiveWheelLocked = true
                                            withCarriedTvItemIndex {
                                                setImmersiveRowIndex((getImmersiveRowIndex() + direction)
                                                    .coerceIn(0, tvRows.lastIndex))
                                                tvFocus.sectionIndex =
                                                    getImmersiveRowIndex() + if (heroFocusable) 1 else 0
                                            }
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
                            if (appShortcutMatches(AppShortcutAction.ToggleTrailer, event)) {
                                return@onPreviewKeyEvent handleHomeTvKey(HomeTvKey.ToggleTrailer)
                            }
                            if (appShortcutMatches(AppShortcutAction.ToggleTrailerMute, event)) {
                                return@onPreviewKeyEvent handleHomeTvKey(HomeTvKey.ToggleMute)
                            }
                            if (appShortcutMatches(AppShortcutAction.TogglePeoplePanel, event)) {
                                return@onPreviewKeyEvent handleHomeTvKey(HomeTvKey.TogglePeoplePanel)
                            }
                            when (event.navigationKey()) {
                                Key.DirectionDown -> {
                                    mouseActivity.onKeyboardNavigation()
                                    withCarriedTvItemIndex {
                                        if (tvModeEnabled) {
                                            setImmersiveRowIndex((getImmersiveRowIndex() + 1)
                                                .coerceAtMost((tvRows.size - 1).coerceAtLeast(0)))
                                            tvFocus.sectionIndex = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
                                        } else {
                                            tvFocus.moveSection(1, tvSectionCount)
                                        }
                                    }
                                    if (!tvModeEnabled) scrollToFocusedTvSection()
                                    true
                                }
                                Key.DirectionUp -> {
                                    mouseActivity.onKeyboardNavigation()
                                    withCarriedTvItemIndex {
                                        if (tvModeEnabled) {
                                            setImmersiveRowIndex((getImmersiveRowIndex() - 1).coerceAtLeast(0))
                                            tvFocus.sectionIndex = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
                                        } else {
                                            tvFocus.moveSection(-1, tvSectionCount)
                                        }
                                    }
                                    if (!tvModeEnabled) scrollToFocusedTvSection()
                                    true
                                }
                                Key.PageDown -> handleHomeTvKey(HomeTvKey.PageDown)
                                Key.PageUp -> handleHomeTvKey(HomeTvKey.PageUp)
                                Key.MoveHome -> handleHomeTvKey(HomeTvKey.Home)
                                Key.MoveEnd -> handleHomeTvKey(HomeTvKey.End)
                                Key.DirectionRight -> handleHomeTvKey(HomeTvKey.Right)
                                Key.DirectionLeft -> {
                                    mouseActivity.onKeyboardNavigation()
                                    tvFocus.moveItem(-1, tvItemCountForSection(tvFocus.sectionIndex))
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    if (heroFocusable && tvFocus.sectionIndex == 0) {
                                        effectiveHeroItems.getOrNull(tvFocus.itemIndex)?.let { posterClickHandler?.invoke(it) }
                                    } else {
                                        tvRows.getOrNull(tvRowIndexForSection(tvFocus.sectionIndex))
                                            ?.onEnter
                                            ?.invoke(tvFocus.itemIndex)
                                    }
                                    true
                                }
                                Key.LeftBracket -> {
                                    handleHomeTvKey(HomeTvKey.VolumeDown)
                                }
                                Key.RightBracket -> {
                                    handleHomeTvKey(HomeTvKey.VolumeUp)
                                }
                                Key.Escape, Key.Back -> {
                                    // If a hero trailer is showing, Escape dismisses it first
                                    // (a clear way out of full-screen playback).
                                    handleHomeTvKey(HomeTvKey.Dismiss)
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
            HeroAmbientBackdrop(
                backdrop = activeHeroBackdrop,
                accent = activeHeroAccent,
                onAccentChanged = { activeHeroAccent = it },
                label = "home_hero_ambient_background",
            )
        }

        val homeSectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        // Sized off the viewport, not a fixed dp: the Discover row's position on screen moves with
        // TV mode, adaptive hero, and hero height, so a constant tall enough on a large window
        // would run off a small one.
        val discoverPickerMaxHeight = (maxHeight * 0.45f).coerceIn(180.dp, 360.dp)
        // Ceiling only — the panel sizes itself to its longest label and rarely reaches this.
        val discoverPickerMaxWidth = (maxWidth - homeSectionPadding * 2).coerceAtLeast(200.dp)
        val discoverPostersAlpha = rememberDiscoverPostersAlpha(discoverPickerSegment != null)
        val discoverRowEmptyText = stringResource(Res.string.discover_row_empty)
        val discoverRowLoadingText = stringResource(Res.string.discover_row_loading)
        // Defined once and handed to both the plain list and the TV immersive shelf, which render
        // the Discover row through separate call sites.
        val discoverRowTitleContent: @Composable () -> Unit = {
            DiscoverRowHeader(
                catalogLabel = discoverUiState.selectedCatalog?.catalogName.orEmpty(),
                filterLabel = discoverUiState.selectedCatalog?.let {
                    discoverUiState.selectedGenre ?: discoverAllFiltersLabel
                },
                activeSegment = discoverPickerSegment,
                onSegmentClick = { segment ->
                    discoverPickerSegment = if (discoverPickerSegment == segment) null else segment
                },
                onSegmentPositioned = { segment, x ->
                    if (discoverSegmentOffsets[segment] != x) {
                        discoverSegmentOffsets = discoverSegmentOffsets + (segment to x)
                    }
                },
            )
        }

        // The header a Discover row draws: the catalog/genre segments for row 1, a title-plus-badge
        // for a row whose origin is not obvious from its name (§7 provenance badges), and null —
        // meaning the shelf's own plain title — for the built-in generated families.
        val discoverRowTitleContentFor: (HomeCatalogSection) -> (@Composable () -> Unit)? = { section ->
            when {
                displayMode !is HomeContentMode.Discover -> null
                section.key == DISCOVER_BROWSER_ROW_KEY -> discoverRowTitleContent
                else -> discoverRowProvenance(section.key)?.let { provenance ->
                    {
                        DiscoverRowTitleWithProvenance(
                            title = section.title,
                            provenance = provenance,
                        )
                    }
                }
            }
        }

        @Composable
        fun BoxScope.DiscoverRowBodySlot(section: HomeCatalogSection, pickerMaxHeight: Dp) {
            val anchorPx = discoverPickerSegment?.let { discoverSegmentOffsets[it] } ?: 0f
            DiscoverRowBody(
                state = discoverUiState,
                itemsEmpty = section.items.isEmpty(),
                segment = discoverPickerSegment,
                pickerMaxHeight = pickerMaxHeight,
                pickerMaxWidth = discoverPickerMaxWidth,
                horizontalPadding = homeSectionPadding,
                anchorX = with(LocalDensity.current) { anchorPx.toDp() },
                onSegmentChange = { discoverPickerSegment = it },
                emptyText = discoverRowEmptyText,
                loadingText = discoverRowLoadingText,
            )
        }
        val continueWatchingLayout = rememberContinueWatchingLayout(maxWidth.value)
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
        val immersiveLandscapeMode = posterCardStyle.catalogLandscapeModeEnabled
        val immersiveShelfHeight = immersiveShelfHeightDp(
            viewportHeightDp = maxHeight.value,
            landscapeMode = immersiveLandscapeMode,
        ).dp
        val immersivePosterBaseWidthDp = remember(
            maxWidth.value,
            immersiveShelfHeight,
            homeSectionPadding,
            immersiveLandscapeMode,
        ) {
            immersiveCatalogPosterBaseWidthDp(
                maxWidthDp = maxWidth.value,
                shelfHeightDp = immersiveShelfHeight.value,
                sectionPaddingDp = homeSectionPadding.value,
                hideLabels = true,
                landscapeMode = immersiveLandscapeMode,
            )
        }
        val adaptiveHeroLayout = if (adaptiveHeroEnabled && showHeroSlot && !tvModeEnabled) {
            homeHeroLayout(
                maxWidthDp = maxWidth.value,
                viewportHeightDp = maxHeight.value,
                mobileBelowSectionHeightHintDp = mobileHeroBelowSectionHeightHint?.value,
                preferDesktopLayout = true,
                heightMultiplier = homeSettingsUiState.adaptiveHeroHeightMultiplier,
            )
        } else {
            null
        }
        val density = LocalDensity.current
        val adaptiveHeroHeightPx = adaptiveHeroLayout?.let { layout ->
            with(density) { layout.heroHeight.roundToPx() }
        } ?: 0
        SideEffect {
            adaptiveHeroScrollClearancePx = adaptiveHeroHeightPx
        }
        val leadingOverlaySpacerHeight = adaptiveHeroLayout?.heroHeight ?: defaultChromeSpacerHeight

        val renderHero: @Composable (LazyListState?) -> Unit = { heroListState ->
            when {
                showHeroSkeleton -> HomeSkeletonHero(
                    modifier = Modifier,
                    viewportHeight = maxHeight,
                    mobileBelowSectionHeightHint = mobileHeroBelowSectionHeightHint,
                    sectionPadding = if (isDesktop) homeSectionPadding else null,
                    heightOverride = adaptiveHeroLayout?.heroHeight,
                )

                effectiveHeroItems.isNotEmpty() -> HomeHeroSection(
                    items = effectiveHeroItems,
                    modifier = Modifier,
                    viewportHeight = maxHeight,
                    mobileBelowSectionHeightHint = mobileHeroBelowSectionHeightHint,
                    sectionPadding = if (isDesktop && !tvModeEnabled) homeSectionPadding else null,
                    listState = heroListState,
                    // A launch-time resume prompt owns the hero until it is resumed or dismissed;
                    // don't let TV/adaptive row focus immediately replace it with another title.
                    focusedItem = if (resumeHeroPreview != null) null else heroFocusedItem,
                    metadataPrefetchItems = immersiveMetadataPrefetchItems,
                    // Fill the viewport for a full-screen trailer (TV Mode already does this);
                    // pairs with the expanded hero container in the Adaptive Hero branch.
                    heightOverride = when {
                        tvModeEnabled || heroTrailerFullscreenActive -> maxHeight
                        adaptiveHeroLayout != null -> adaptiveHeroLayout.heroHeight
                        else -> null
                    },
                    roundedBottomCorners =
                        !heroAmbientBackgroundEnabled && !tvModeEnabled,
                    immersiveMode = tvModeEnabled,
                    immersiveFullBackdrop = homeSettingsUiState.tvFullBackdropEnabled,
                    adaptiveHeroMode = adaptiveHeroEnabled,
                    heroInfoLines = homeSettingsUiState.heroInfoLines,
                    heroInfoPriority = homeSettingsUiState.heroInfoPriority,
                    heroBadgePlacement = homeSettingsUiState.heroBadgePlacement,
                    heroReleaseStatusUnavailableOnly = homeSettingsUiState.heroReleaseStatusUnavailableOnly,
                    trailersEnabledInCurrentMode = trailersEnabledForCurrentMode,
                    immersiveContentBottomPadding = immersiveShelfHeight - 20.dp,
                    resumePromptItemKey = effectiveResumeItemKey,
                    resumePromptLabel = resumePromptLabel,
                    onResumePromptAction = effectiveOnResumeAction,
                    onResumePromptDismiss = effectiveOnResumeDismiss,
                    onActiveItemChanged = { item ->
                        activeHeroBackdrop = item.banner ?: item.poster
                        basicHeroActiveItem = item
                    },
                    onCastClick = onCastClick,
                    onItemClick = { item ->
                        if ("${item.type}:${item.id}" == effectiveResumeItemKey && effectiveOnResumeAction != null) {
                            effectiveOnResumeAction()
                        } else if (item.type != COLLECTION_HERO_TYPE) {
                            posterClickHandler?.invoke(item)
                        }
                    },
                    onHeroTrailerSurfaceDisposed = {
                        try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
                        // Re-arm the "ignore next mouse move" guard right here, at the moment
                        // the native surface actually goes away and its synthetic re-entry
                        // event is imminent. The guard set at key-press time (see
                        // handleHomeTvKey's onKeyboardNavigation(ignoreNextMouseMove = ...))
                        // only survives until the very next onMouseMoved call — if Down
                        // triggers this disposal indirectly via an async scroll animation, a
                        // genuine mouse move can land first and consume that guard, leaving
                        // the real synthetic event unguarded. That reactivates hover-driven
                        // focus, which then snaps tvFocus back to whatever's under the
                        // cursor — silently undoing the keyboard navigation that caused the
                        // scroll in the first place.
                        mouseActivity.onKeyboardNavigation(ignoreNextMouseMove = true)
                    },
                )

                else -> HomeHeroReservedSpace(
                    modifier = Modifier,
                    viewportHeight = maxHeight,
                    mobileBelowSectionHeightHint = mobileHeroBelowSectionHeightHint,
                    heightOverride = if (tvModeEnabled) maxHeight else null,
                    roundedBottomCorners =
                        !heroAmbientBackgroundEnabled && !tvModeEnabled,
                )
            }
        }

        val rowsContent: LazyListScope.() -> Unit = {
            if (leadingOverlaySpacerHeight > 0.dp) {
                item(key = "leading_overlay_spacer") {
                    Spacer(modifier = Modifier.height(leadingOverlaySpacerHeight))
                }
            }

            when {
                !hasActiveAddons && !hasRenderableCollectionRows -> {
                    if (isShowingHomeContent && continueWatchingPreferences.isVisible && continueWatchingRowItems.isNotEmpty()) {
                        item(key = HOME_CONTINUE_WATCHING_SECTION_KEY) {
                            HomeContinueWatchingSection(
                                items = continueWatchingRowItems,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                modifier = Modifier.padding(bottom = 12.dp),
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                rowState = continueWatchingRowState,
                                onItemClick = onContinueWatchingClick,
                                onItemLongPress = onContinueWatchingLongPress,
                            )
                        }
                    }
                    if (isShowingHomeContent && continueWatchingPreferences.isVisible && nextUpRowItems.isNotEmpty()) {
                        item(key = HOME_NEXT_UP_SECTION_KEY) {
                            HomeContinueWatchingSection(
                                items = nextUpRowItems,
                                title = nextUpRowTitle,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                modifier = Modifier.padding(bottom = 12.dp),
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                rowState = nextUpRowState,
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

                homeUiState.isLoading && effectiveSections.isEmpty() && !hasRenderableCollectionRows -> {
                    if (isShowingHomeContent && continueWatchingPreferences.isVisible && continueWatchingRowItems.isNotEmpty()) {
                        item(key = HOME_CONTINUE_WATCHING_SECTION_KEY) {
                            HomeContinueWatchingSection(
                                items = continueWatchingRowItems,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                modifier = Modifier.padding(bottom = 12.dp),
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                rowState = continueWatchingRowState,
                                onItemClick = onContinueWatchingClick,
                                onItemLongPress = onContinueWatchingLongPress,
                            )
                        }
                    }
                    if (isShowingHomeContent && continueWatchingPreferences.isVisible && nextUpRowItems.isNotEmpty()) {
                        item(key = HOME_NEXT_UP_SECTION_KEY) {
                            HomeContinueWatchingSection(
                                items = nextUpRowItems,
                                title = nextUpRowTitle,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                modifier = Modifier.padding(bottom = 12.dp),
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                rowState = nextUpRowState,
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

                effectiveSections.isEmpty() && effectiveHeroItems.isEmpty() &&
                    (!continueWatchingPreferences.isVisible || continueWatchingItems.isEmpty()) &&
                    !hasRenderableCollectionRows -> {
                    // In Search mode the input field is the only UI needed when there are
                    // no results — no query means "type to search", query means "no matches".
                    // In Library mode an empty library just shows nothing.
                    // Only Normal mode gets the home-specific empty state cards.
                    if (displayMode is HomeContentMode.Discover) {
                        if (discoverUiState.isLoading) {
                            items(3) {
                                HomeSkeletonRow(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    showHeaderAccent = !homeSettingsUiState.hideCatalogUnderline,
                                )
                            }
                        } else {
                            item {
                                val title = when (discoverUiState.emptyStateReason) {
                                    DiscoverEmptyStateReason.NoActiveAddons -> stringResource(Res.string.compose_search_empty_no_active_addons_title)
                                    DiscoverEmptyStateReason.NoDiscoverCatalogs -> stringResource(Res.string.discover_empty_no_catalogs_title)
                                    DiscoverEmptyStateReason.RequestFailed -> stringResource(Res.string.discover_empty_load_failed_title)
                                    DiscoverEmptyStateReason.NoResults, null -> stringResource(Res.string.discover_empty_no_results_title)
                                }
                                val message = discoverUiState.errorMessage ?: when (discoverUiState.emptyStateReason) {
                                    DiscoverEmptyStateReason.NoActiveAddons -> stringResource(Res.string.discover_empty_no_active_addons_message)
                                    DiscoverEmptyStateReason.NoDiscoverCatalogs -> stringResource(Res.string.discover_empty_no_catalogs_message)
                                    DiscoverEmptyStateReason.RequestFailed -> stringResource(Res.string.discover_empty_load_failed_message)
                                    DiscoverEmptyStateReason.NoResults, null -> stringResource(Res.string.discover_empty_no_results_message)
                                }
                                HomeEmptyStateCard(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    title = title,
                                    message = message,
                                )
                            }
                        }
                    } else if (isShowingHomeContent) {
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
                    } else if (contentMode is HomeContentMode.Search && searchQuery.isNotBlank()) {
                        item {
                            HomeEmptyStateCard(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                title = stringResource(Res.string.compose_search_empty_no_results_title),
                                message = stringResource(Res.string.compose_search_empty_no_results_message),
                            )
                        }
                    }
                }

                else -> {
                    var tvRowCursor = 0

                    if (isShowingHomeContent &&
                        continueWatchingPreferences.isVisible && continueWatchingRowItems.isNotEmpty()) {
                        val rowIndex = tvRowCursor++
                        val sectionIndex = if (heroFocusable) rowIndex + 1 else rowIndex
                        item(key = HOME_CONTINUE_WATCHING_SECTION_KEY) {
                            HomeContinueWatchingSection(
                                items = continueWatchingRowItems,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                modifier = Modifier.padding(bottom = 12.dp),
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                rowState = continueWatchingRowState,
                                focusedItemIndex = if (tvFocusedRowIndex == rowIndex) tvFocus.itemIndex else null,
                                isKeyboardNavigation = !mouseActivity.isMouseActive,
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
                    if (isShowingHomeContent &&
                        continueWatchingPreferences.isVisible && nextUpRowItems.isNotEmpty()) {
                        val rowIndex = tvRowCursor++
                        val sectionIndex = if (heroFocusable) rowIndex + 1 else rowIndex
                        item(key = HOME_NEXT_UP_SECTION_KEY) {
                            HomeContinueWatchingSection(
                                items = nextUpRowItems,
                                title = nextUpRowTitle,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                modifier = Modifier.padding(bottom = 12.dp),
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                rowState = nextUpRowState,
                                focusedItemIndex = if (tvFocusedRowIndex == rowIndex) tvFocus.itemIndex else null,
                                isKeyboardNavigation = !mouseActivity.isMouseActive,
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

                    // In Search/Library mode: render effectiveSections directly (home settings
                    // don't contain search result or library section keys, so the normal
                    // enabledHomeItems loop would render nothing).
                    val rowSections: List<HomeCatalogSection> = if (isShowingHomeContent) {
                        enabledHomeItems.mapNotNull { item ->
                            if (!item.isCollection) sectionsMap[item.key]?.takeIf { it.items.isNotEmpty() }
                            else null
                        }
                    } else {
                        // The Discover browser row survives an empty result on purpose: its header
                        // carries the catalog/genre pickers, so dropping it would strand the user on
                        // an empty catalog with no way to choose a different one.
                        effectiveSections.filter {
                            it.items.isNotEmpty() || it.key == DISCOVER_BROWSER_ROW_KEY ||
                        it.key.startsWith(DISCOVER_PLACEHOLDER_KEY_PREFIX)
                        }
                    }

                    if (isShowingHomeContent) enabledHomeItems.forEach { settingsItem ->
                        if (isShowingHomeContent && settingsItem.isCollection) {
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
                                        isKeyboardNavigation = !mouseActivity.isMouseActive,
                                        rowNumber = catalogRowNumbers[settingsItem.key],
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
                                val usesInfiniteScroll =
                                    section.usesInfiniteHomeRow(catalogSeeMoreEnabled)
                                val rowIndex = tvRowCursor++
                                val sectionIndex = if (heroFocusable) rowIndex + 1 else rowIndex
                                val shuffleOrder = shuffleOrderFor(section)
                                // Shuffle first, slice second: on a row that shows a preview and a
                                // View All pill, dealing the whole pool and then taking the first
                                // 18 is what swaps in titles from pages the user has never scrolled
                                // to. Slicing first would only ever reorder the same 18 posters.
                                val shuffledItems = section.shuffled(shuffleOrder)
                                item(key = settingsItem.key) {
                                    HomeCatalogRowSection(
                                        section = section,
                                        entries = if (usesInfiniteScroll) {
                                            shuffledItems
                                        } else {
                                            shuffledItems.take(HOME_CATALOG_PREVIEW_LIMIT)
                                        },
                                        onShuffleClick = shuffleClickFor(section),
                                        isShuffling = section.key in rowShufflingKeys,
                                        shuffleGeneration = shuffleOrder?.generation ?: 0,
                                        modifier = Modifier.padding(bottom = 12.dp),
                                        sectionPadding = homeSectionPadding,
                                        focusedItemIndex = if (tvFocusedRowIndex == rowIndex) tvFocus.itemIndex else null,
                                        isKeyboardNavigation = !mouseActivity.isMouseActive,
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
                                        onViewAllClick = if (
                                            !usesInfiniteScroll &&
                                            section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)
                                        ) {
                                            onCatalogClick?.let { { it(section) } }
                                        } else {
                                            null
                                        },
                                        onLoadMore = if (usesInfiniteScroll && section.hasMore) {
                                            { HomeRepository.loadMoreCatalogRow(section.key) }
                                        } else {
                                            null
                                        },
                                        isLoadingMore = section.isLoadingMore,
                                        watchedKeys = watchedUiState.watchedKeys,
                                        onPosterClick = posterClickHandler,
                                        onPosterLongClick = onPosterLongClick,
                                        rowNumber = catalogRowNumbers[settingsItem.key],
                                    )
                                }
                            }
                        }
                    }

                    // Search / Library / Discover mode: render result sections directly.
                    if (!isShowingHomeContent) {
                        rowSections.forEach { section ->
                            // A reserved slot for a Discover row still being built. Drawn as a
                            // skeleton and, crucially, NOT given a tvRowCursor index — it cannot be
                            // focused or clicked, and when the real row replaces it the cursor
                            // numbering is unchanged.
                            if (section.key.startsWith(DISCOVER_PLACEHOLDER_KEY_PREFIX)) {
                                item(key = section.key) {
                                    HomeSkeletonRow(
                                        modifier = Modifier.padding(bottom = 12.dp),
                                    )
                                }
                                return@forEach
                            }
                            val usesInfiniteScroll = section.usesInfiniteHomeRow(catalogSeeMoreEnabled)
                            val rowIndex = tvRowCursor++
                            val sectionIndex = if (heroFocusable) rowIndex + 1 else rowIndex
                            val isDiscoverBrowserRow = section.key == DISCOVER_BROWSER_ROW_KEY
                            item(key = section.key) {
                                HomeCatalogRowSection(
                                    section = section,
                                    entries = if (usesInfiniteScroll) {
                                        section.items
                                    } else {
                                        section.resultRowEntries(
                                            catalogSeeMoreEnabled = catalogSeeMoreEnabled,
                                            cardEnrichments = heroEnrichmentMap,
                                            pendingEnrichmentKeys = landscapePendingEnrichmentKeys,
                                        )
                                    },
                                    modifier = Modifier.padding(bottom = 12.dp),
                                    sectionPadding = homeSectionPadding,
                                    focusedItemIndex = if (tvFocusedRowIndex == rowIndex) tvFocus.itemIndex else null,
                                    isKeyboardNavigation = !mouseActivity.isMouseActive,
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
                                    onViewAllClick = if (
                                        catalogSeeMoreEnabled &&
                                        section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)
                                    ) {
                                        onCatalogClick?.let { { it(section) } }
                                    } else {
                                        null
                                    },
                                    onLoadMore = when {
                                        isDiscoverBrowserRow && section.hasMore ->
                                            { { SearchRepository.loadMoreDiscover() } }
                                        usesInfiniteScroll && section.hasMore ->
                                            onLoadMoreCatalog?.let { callback -> { callback(section) } }
                                        else -> null
                                    },
                                    isLoadingMore = section.isLoadingMore,
                                    watchedKeys = watchedUiState.watchedKeys,
                                    onPosterClick = posterClickHandler,
                                    onPosterLongClick = onPosterLongClick,
                                    titleContent = discoverRowTitleContentFor(section),
                                    bodyAlpha = if (isDiscoverBrowserRow) discoverPostersAlpha else 1f,
                                    bodyOverlay = if (isDiscoverBrowserRow) {
                                        { DiscoverRowBodySlot(section, discoverPickerMaxHeight) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (tvModeEnabled && tvRows.isNotEmpty()) {
            val activeSettingsItem = immersiveRows.getOrNull(getImmersiveRowIndex())
            Box(modifier = Modifier.fillMaxSize()) {
                renderHero(null)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(immersiveShelfHeight)
                        .align(Alignment.BottomStart)
                        // The shelf's own scrim, and the thing that actually makes TV Mode's
                        // bottom band black: it reaches the opaque background colour whatever the
                        // hero behind it is doing. Under full backdrop it stops short instead, so
                        // the artwork the hero now paints down here stays visible through it.
                        .background(
                            Brush.verticalGradient(
                                colorStops = immersiveShelfScrimStops(
                                    backgroundColor = MaterialTheme.colorScheme.background,
                                    fullBackdrop = homeSettingsUiState.tvFullBackdropEnabled,
                                ),
                            ),
                        )
                        .padding(
                            top = IMMERSIVE_SHELF_TOP_PADDING_DP.dp,
                            bottom = IMMERSIVE_SHELF_BOTTOM_PADDING_DP.dp,
                        ),
                    contentAlignment = if (immersiveLandscapeMode) {
                        Alignment.BottomStart
                    } else {
                        Alignment.TopStart
                    },
                ) {
                    when {
                        activeSettingsItem == null && isShowingHomeContent -> HomeContinueWatchingSection(
                            items = continueWatchingRowItems,
                            style = continueWatchingPreferences.style,
                            useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                            blurNextUp = continueWatchingPreferences.blurNextUp,
                            sectionPadding = homeSectionPadding,
                            layout = continueWatchingLayout,
                            basePosterWidthDpOverride = immersivePosterBaseWidthDp.takeIf {
                                immersiveLandscapeMode
                            },
                            focusedItemIndex = tvFocus.itemIndex,
                            rowState = continueWatchingRowState,
                            onHoverItem = ::selectHoveredImmersiveItem,
                            isKeyboardNavigation = !mouseActivity.isMouseActive,
                            headerTrailingContent = tvRowDotsContent,
                            onItemClick = onContinueWatchingClick,
                            onItemLongPress = onContinueWatchingLongPress,
                        )

                        isShowingHomeContent && activeSettingsItem?.key == HOME_NEXT_UP_SECTION_KEY ->
                            HomeContinueWatchingSection(
                                items = nextUpRowItems,
                                title = nextUpRowTitle,
                                style = continueWatchingPreferences.style,
                                useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                                blurNextUp = continueWatchingPreferences.blurNextUp,
                                sectionPadding = homeSectionPadding,
                                layout = continueWatchingLayout,
                                basePosterWidthDpOverride = immersivePosterBaseWidthDp.takeIf {
                                    immersiveLandscapeMode
                                },
                                focusedItemIndex = tvFocus.itemIndex,
                                rowState = nextUpRowState,
                                onHoverItem = ::selectHoveredImmersiveItem,
                                isKeyboardNavigation = !mouseActivity.isMouseActive,
                                headerTrailingContent = tvRowDotsContent,
                                onItemClick = onContinueWatchingClick,
                                onItemLongPress = onContinueWatchingLongPress,
                            )

                        isShowingHomeContent && activeSettingsItem?.isCollection == true -> {
                            collectionsMap[activeSettingsItem?.key ?: ""]?.let { collection ->
                                HomeCollectionRowSection(
                                    collection = collection,
                                    sectionPadding = homeSectionPadding,
                                    basePosterWidthDpOverride = immersivePosterBaseWidthDp,
                                    animateGifs = animateCollectionGifs,
                                    focusedItemIndex = tvFocus.itemIndex,
                                    rowState = remember(collection.id) {
                                        HomeScrollMemory.immersiveRowStates.getOrPut("collection:${collection.id}") { LazyListState() }
                                    },
                                    isKeyboardNavigation = !mouseActivity.isMouseActive,
                                    rowNumber = catalogRowNumbers[activeSettingsItem?.key],
                                    headerTrailingContent = tvRowDotsContent,
                                    onHoverItem = ::selectHoveredImmersiveItem,
                                    onFolderClick = onFolderClick,
                                )
                            }
                        }

                        else -> {
                            val immSection = if (isShowingHomeContent) {
                                sectionsMap[activeSettingsItem?.key ?: ""]
                            } else {
                                effectiveSections.firstOrNull { it.key == activeSettingsItem?.key }
                            }
                            immSection?.let { section ->
                                val usesInfiniteScroll =
                                    section.usesInfiniteHomeRow(catalogSeeMoreEnabled)
                                val shuffleOrder = shuffleOrderFor(section)
                                val shuffledItems = section.shuffled(shuffleOrder)
                                androidx.compose.runtime.key(section.key) {
                                    HomeCatalogRowSection(
                                        section = section,
                                        entries = when {
                                            !isShowingHomeContent ->
                                                section.resultRowEntries(
                                                    catalogSeeMoreEnabled = catalogSeeMoreEnabled,
                                                    cardEnrichments = heroEnrichmentMap,
                                                    pendingEnrichmentKeys = landscapePendingEnrichmentKeys,
                                                )
                                            usesInfiniteScroll -> shuffledItems
                                            else -> shuffledItems.take(HOME_CATALOG_PREVIEW_LIMIT)
                                        },
                                        onShuffleClick = shuffleClickFor(section),
                                        isShuffling = section.key in rowShufflingKeys,
                                        shuffleGeneration = shuffleOrder?.generation ?: 0,
                                        sectionPadding = homeSectionPadding,
                                        basePosterWidthDpOverride = immersivePosterBaseWidthDp,
                                        focusedItemIndex = tvFocus.itemIndex,
                                        rowState = remember(section.key) {
                                            HomeScrollMemory.immersiveRowStates.getOrPut("catalog:${section.key}") { LazyListState() }
                                        },
                                        isKeyboardNavigation = !mouseActivity.isMouseActive,
                                        onHoverItem = ::selectHoveredImmersiveItem,
                                        onLoadMore = if (usesInfiniteScroll) {
                                            if (isShowingHomeContent) {
                                                { HomeRepository.loadMoreCatalogRow(section.key) }
                                            } else {
                                                onLoadMoreCatalog?.let { callback -> { callback(section) } }
                                            }
                                        } else {
                                            null
                                        },
                                        isLoadingMore = section.isLoadingMore,
                                        watchedKeys = watchedUiState.watchedKeys,
                                        onPosterClick = posterClickHandler,
                                        onPosterLongClick = onPosterLongClick,
                                        rowNumber = catalogRowNumbers[section.key],
                                        headerTrailingContent = tvRowDotsContent,
                                        titleContent = discoverRowTitleContentFor(section),
                                        bodyAlpha = if (section.key == DISCOVER_BROWSER_ROW_KEY) {
                                            discoverPostersAlpha
                                        } else {
                                            1f
                                        },
                                        bodyOverlay = if (section.key == DISCOVER_BROWSER_ROW_KEY) {
                                            {
                                                // The immersive shelf is a fixed-height box pinned to
                                                // the bottom of the window, so the picker is capped to
                                                // it rather than to the viewport.
                                                DiscoverRowBodySlot(
                                                    section = section,
                                                    pickerMaxHeight = minOf(
                                                        discoverPickerMaxHeight,
                                                        immersiveShelfHeight,
                                                    ),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                        onViewAllClick = if (
                                            (isShowingHomeContent || catalogSeeMoreEnabled) &&
                                            !usesInfiniteScroll &&
                                            section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)
                                        ) {
                                            onCatalogClick?.let { { it(section) } }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (adaptiveHeroLayout != null) {
            val heroLayout = adaptiveHeroLayout
            Box(modifier = Modifier.fillMaxSize()) {
                NuvioScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .smoothVerticalWheelScroll(
                            state = currentListState,
                            enabled = isDesktop && homeSettingsUiState.smoothScrollingEnabled,
                        ),
                    horizontalPadding = 0.dp,
                    topPadding = 0.dp,
                    backgroundColor = if (heroAmbientBackgroundEnabled) Color.Transparent else null,
                    listState = currentListState,
                    showDesktopScrollbar = false,
                    content = rowsContent,
                )
                Box(
                    modifier = if (heroTrailerFullscreenActive) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(heroLayout.heroHeight)
                            .align(Alignment.TopStart)
                    },
                ) {
                    renderHero(null)
                }
            }
        } else {
            NuvioScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .smoothVerticalWheelScroll(
                        state = currentListState,
                        // This interceptor consumes every vertical wheel delta on the Initial pass,
                        // so it starves any nested vertical scroller — the Discover picker's list
                        // could not be scrolled at all while it was on. Stand down while a picker
                        // owns the row body; the page should not move under an open panel anyway.
                        enabled = isDesktop &&
                            homeSettingsUiState.smoothScrollingEnabled &&
                            discoverPickerSegment == null,
                    ),
                horizontalPadding = 0.dp,
                topPadding = when {
                    showHeroSlot -> 0.dp
                    defaultChromeSpacerHeight > 0.dp -> 0.dp
                    isDesktop && !isShowingHomeContent -> topChromePadding ?: 72.dp
                    else -> null
                },
                backgroundColor = if (heroAmbientBackgroundEnabled) Color.Transparent else null,
                listState = currentListState,
                showDesktopScrollbar = false,
            ) {
                if (showHeroSlot) {
                    item { renderHero(currentListState) }
                }
                rowsContent()
            }
        }
        NuvioDesktopVerticalScrollbar(
            state = currentListState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .zIndex(100f)
                .padding(end = 3.dp),
        )
        // Outside the rows list on purpose: Basic's hero is a recycled list item, so a trailer
        // parented to it dies the moment the user scrolls to the rows they wanted to play from.
        // Derived, not read straight: firstVisibleItemIndex changes every frame of a scroll, and
        // reading it here would recompose the whole home screen along with it.
        val basicHeroOnScreen by remember(currentListState) {
            derivedStateOf { currentListState.firstVisibleItemIndex == 0 }
        }
        HomeBasicTrailerOverlay(
            enabled = basicHomeHeroTrailersEnabled,
            heroItem = basicHeroActiveItem,
            heroOnScreen = basicHeroOnScreen,
            onDismissed = {
                try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
            },
            modifier = Modifier.zIndex(200f),
        )
    }
}

private const val HOME_CATALOG_PREVIEW_LIMIT = 18
private const val HOME_CONTINUE_WATCHING_SECTION_KEY = "home:continue-watching"

/**
 * Row 1 of the Discover tab. Stable across catalog/genre changes on purpose: keying it by the
 * selection would tear the LazyRow down on every pick, losing scroll position and re-running the
 * poster fade from scratch.
 */
internal const val DISCOVER_BROWSER_ROW_KEY = "discover:browser"
private const val HOME_NEXT_UP_SECTION_KEY = "home:next-up"

/**
 * Entries a Search or Library row renders. Unlike home catalogs these sections never paginate —
 * everything the mode found is already loaded — so "See more arrows" only picks between a capped
 * preview shelf (plus an arrow into the full catalog screen) and the whole loaded list inline.
 */
private fun HomeCatalogSection.resultRowEntries(
    catalogSeeMoreEnabled: Boolean,
    cardEnrichments: Map<String, MetaPreview> = emptyMap(),
    // Keys whose replacement artwork is actually being fetched right now. Only those cards hide
    // what they already have; anything else keeps its own art rather than waiting on a pass that
    // was never scheduled for it, or that has already come back empty.
    pendingEnrichmentKeys: Set<String> = emptySet(),
): List<MetaPreview> {
    val entries = if (catalogSeeMoreEnabled && target != null && !inlineOnly) {
        items.take(HOME_CATALOG_PREVIEW_LIMIT)
    } else {
        items
    }
    if (cardEnrichments.isEmpty() && pendingEnrichmentKeys.isEmpty()) return entries
    return entries.map { raw ->
        val key = canonicalHeroKey(raw.type, raw.id)
        mergeSearchLibraryCardEnrichment(
            raw = raw,
            enriched = cardEnrichments[key],
            suppressPendingArtwork = key in pendingEnrichmentKeys,
        )
    }
}

/** Keeps row-specific identity/navigation data while upgrading the artwork fetched for its hero. */
internal fun mergeSearchLibraryCardEnrichment(
    raw: MetaPreview,
    enriched: MetaPreview?,
    suppressPendingArtwork: Boolean = false,
): MetaPreview {
    if (enriched == null) {
        return if (suppressPendingArtwork && !raw.hasResolvedProviderArtwork()) {
            raw.copy(
                poster = null,
                posterFallback = null,
                banner = null,
                logo = null,
            )
        } else {
            raw
        }
    }
    return raw.copy(
        banner = enriched.banner ?: raw.banner,
        logo = enriched.logo ?: raw.logo,
    )
}

/**
 * Artwork produced by filename resolution (or a direct metadata provider) needs no hero pass.
 *
 * Every image slot counts, not just the backdrop: a filename-resolved row whose TMDB match has no
 * backdrop still carries a real poster, and treating that as "pending" both blanked the card and
 * left the hero waiting on an enrichment that can never arrive — these rows have no addon metadata
 * behind their id.
 */
private fun MetaPreview.hasResolvedProviderArtwork(): Boolean =
    listOf(banner, poster, posterFallback).any { it.isMetadataProviderArtUrl() }

private fun String?.isMetadataProviderArtUrl(): Boolean {
    val url = this ?: return false
    return url.contains("image.tmdb.org", ignoreCase = true) ||
        url.contains("artworks.thetvdb.com", ignoreCase = true) ||
        url.isAnimeSeasonArtUrl()
}

// How long the Search/Library hero holds the previously focused item while the new one enriches.
// Long enough to cover a normal metadata round-trip, short enough that an item nothing can enrich
// still reaches the hero.
private const val FOCUSED_HERO_ENRICHMENT_HOLD_MS = 4_000L
private const val SEARCH_LIBRARY_METADATA_PREFETCH_LIMIT = 3
private const val SEARCH_LIBRARY_LANDSCAPE_METADATA_PREFETCH_LIMIT = 8
// How many results per catalog the search hero eagerly enriches (incl. rate-limited MDBList). Kept
// small on purpose: search results fan out across catalogs and users care about the start of each.
private const val SEARCH_HERO_METADATA_PREFETCH_PER_CATALOG = 2
// How far ahead the TV-mode hero warms metadata in Home/Library, per row. Comfortably more than a
// shelf shows at once, so the titles the user can reach next are already warm, without queueing work
// for a row they may never scroll to.
private const val IMMERSIVE_HERO_METADATA_PREFETCH_PER_ROW = 12
private const val HOME_STARTUP_METADATA_GRACE_MS = 900L
// Long enough for the focused card's own enrichment to get its request away before the hero-
// rotation batch saturates the connection, short enough that the rotation is still warm before the
// hero advances. Startup only.
private const val HERO_BATCH_ENRICHMENT_STARTUP_GRACE_MS = 350L
// COLLECTION_HERO_TYPE now lives in HomeModels.kt, shared with HomeRepository's own
// collection-backdrop hero items.
private const val IMMERSIVE_SHELF_TOP_PADDING_DP = 68f
private const val IMMERSIVE_SHELF_BOTTOM_PADDING_DP = 12f
private const val IMMERSIVE_SHELF_HEADER_ESTIMATE_DP = 54f
private const val IMMERSIVE_POSTER_LABEL_RESERVE_DP = 42f
private const val IMMERSIVE_POSTER_ASPECT_RATIO = 0.675f
private const val IMMERSIVE_LANDSCAPE_WIDTH_SCALE = 180f / 110f
private const val IMMERSIVE_LANDSCAPE_ASPECT_RATIO = 1.77f
private const val IMMERSIVE_POSTER_MIN_BASE_WIDTH_DP = 104
private const val IMMERSIVE_POSTER_MAX_BASE_WIDTH_DP = 210
private const val IMMERSIVE_POSTER_ITEM_SPACING_DP = 10f
private const val IMMERSIVE_POSTER_MIN_VISIBLE_WIDE = 8
private const val IMMERSIVE_POSTER_MIN_VISIBLE_NARROW = 7
private const val IMMERSIVE_LANDSCAPE_MIN_VISIBLE_WIDE = 5
private const val IMMERSIVE_LANDSCAPE_MIN_VISIBLE_NARROW = 4

internal fun immersiveShelfHeightDp(
    viewportHeightDp: Float,
    landscapeMode: Boolean,
): Float = if (landscapeMode) {
    (viewportHeightDp * 0.34f).coerceIn(260f, 340f)
} else {
    (viewportHeightDp * 0.43f).coerceIn(300f, 440f)
}
internal const val HomeContinueWatchingMaxRecentProgressItems = 300
internal const val HomeNextUpInitialResolutionLimit = 32

// Hero preview for a Continue Watching item, so the adaptive/TV hero can follow the focused CW
// card. Mirrors the launch resume-recovery preview mapping (see resumeHeroPreview).
private fun ContinueWatchingItem.toHomeHeroPreview(): MetaPreview = MetaPreview(
    id = parentMetaId,
    type = parentMetaType,
    name = title,
    poster = poster ?: imageUrl,
    banner = background ?: episodeThumbnail ?: imageUrl,
    logo = logo,
    description = pauseDescription,
    releaseInfo = subtitle,
)
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
private const val OPTIMISTIC_NEXT_UP_SEED_WINDOW_MS = 3L * 60L * 1000L
private const val NEXT_UP_RESOLUTION_CONCURRENCY = 4
private const val NEXT_UP_RESOLUTION_BATCH_SIZE = NEXT_UP_RESOLUTION_CONCURRENCY

// Also used by the Collections immersive folder view (FolderDetailScreen), which renders the
// same TV Mode shelf and must size posters the same shelf-filling way.
internal fun immersiveCatalogPosterBaseWidthDp(
    maxWidthDp: Float,
    shelfHeightDp: Float,
    sectionPaddingDp: Float,
    hideLabels: Boolean,
    landscapeMode: Boolean = false,
): Int {
    val labelReserve = if (hideLabels || landscapeMode) 0f else IMMERSIVE_POSTER_LABEL_RESERVE_DP
    val availablePosterHeight = shelfHeightDp -
        IMMERSIVE_SHELF_TOP_PADDING_DP -
        IMMERSIVE_SHELF_BOTTOM_PADDING_DP -
        IMMERSIVE_SHELF_HEADER_ESTIMATE_DP -
        labelReserve
    val heightDrivenWidth = if (landscapeMode) {
        (availablePosterHeight * IMMERSIVE_LANDSCAPE_ASPECT_RATIO /
            IMMERSIVE_LANDSCAPE_WIDTH_SCALE).roundToInt()
    } else {
        (availablePosterHeight * IMMERSIVE_POSTER_ASPECT_RATIO).roundToInt()
    }

    val rowWidth = maxWidthDp - (sectionPaddingDp * 2f)
    val minVisibleItems = if (landscapeMode) {
        if (maxWidthDp >= 1800f) {
            IMMERSIVE_LANDSCAPE_MIN_VISIBLE_WIDE
        } else {
            IMMERSIVE_LANDSCAPE_MIN_VISIBLE_NARROW
        }
    } else if (maxWidthDp >= 1800f) {
        IMMERSIVE_POSTER_MIN_VISIBLE_WIDE
    } else {
        IMMERSIVE_POSTER_MIN_VISIBLE_NARROW
    }
    val widthDrivenCardMax = (
        (rowWidth - IMMERSIVE_POSTER_ITEM_SPACING_DP * (minVisibleItems - 1)) / minVisibleItems
        ).roundToInt()
    val widthDrivenMax = if (landscapeMode) {
        (widthDrivenCardMax / IMMERSIVE_LANDSCAPE_WIDTH_SCALE).roundToInt()
    } else {
        widthDrivenCardMax
    }

    val maxBaseWidth = maxOf(
        IMMERSIVE_POSTER_MIN_BASE_WIDTH_DP,
        minOf(IMMERSIVE_POSTER_MAX_BASE_WIDTH_DP, widthDrivenMax),
    )
    return heightDrivenWidth.coerceIn(
        minimumValue = IMMERSIVE_POSTER_MIN_BASE_WIDTH_DP,
        maximumValue = maxBaseWidth,
    )
}

// homeHeroPreview() now lives in HomeRepository.kt, shared with the hero-source pool.

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

/**
 * Whether a tracking service — not Nuvio Sync — is really supplying Continue Watching.
 *
 * A selected provider that is not connected is not the active source: `WatchProgressRepository`
 * resolves it back to Nuvio Sync. Reading the stored selection alone left this screen believing a
 * remote source was active, so the local rows it was actually showing lost their Up Next seeds.
 */
internal fun isContinueWatchingRemoteSourceActive(
    source: ContinueWatchingSource,
    connectedProviderIds: Set<TrackingProviderId>,
): Boolean = source.providerId?.let { providerId -> providerId in connectedProviderIds } == true

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
    forceMetaRefresh: Boolean = false,
): Pair<String, Pair<Long, ContinueWatchingItem>>? {
    val contentId = completedEntry.content.id
    val meta = try {
        MetaDetailsRepository.fetch(
            type = completedEntry.content.type,
            id = contentId,
            // Set only for a manual resync retrying a card whose episode still had no thumbnail;
            // the LRU would otherwise hand back the same artwork-less video list all session.
            forceRefresh = forceMetaRefresh,
        )
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
    }
    if (meta == null) {
        logNextUpResolutionRejected(completedEntry, "meta-fetch-failed")
        return null
    }

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
    if (action == null) {
        // Covers the showUnairedNextUp preference: with it off, an unaired next episode yields no
        // action at all, so the whole card disappears rather than losing only its badge.
        logNextUpResolutionRejected(completedEntry, "no-primary-action (series finished, or unaired and showUnairedNextUp=off)")
        return null
    }
    if (action.resumePositionMs != null) {
        logNextUpResolutionRejected(completedEntry, "resumable-episode (renders as in-progress, not Up Next)")
        return null
    }

    val nextEpisode = meta.videoForSeriesAction(action)
    if (nextEpisode == null) {
        logNextUpResolutionRejected(completedEntry, "next-episode-missing-from-meta")
        return null
    }
    val item = completedEntry.toContinueWatchingSeed(meta)
        .toUpNextContinueWatchingItem(nextEpisode)
    if (nextUpDismissKey(item.parentMetaId, item.nextUpSeedSeasonNumber, item.nextUpSeedEpisodeNumber) in dismissedNextUpKeys) {
        logNextUpResolutionRejected(completedEntry, "dismissed-by-user")
        return null
    }

    com.nuvio.app.features.watchprogress.NextUpDiagnostics.logResolvedCard(
        contentId = contentId,
        title = item.title,
        seedSeasonNumber = completedEntry.seasonNumber,
        seedEpisodeNumber = completedEntry.episodeNumber,
        seedMarkedAtEpochMs = completedEntry.markedAtEpochMs,
        nextSeasonNumber = item.seasonNumber,
        nextEpisodeNumber = item.episodeNumber,
        releasedIso = item.released,
        todayIsoDate = todayIsoDate,
        alertState = com.nuvio.app.features.watchprogress.calculateReleaseAlertState(
            seedLastUpdatedEpochMs = completedEntry.markedAtEpochMs,
            seedSeasonNumber = completedEntry.seasonNumber,
            nextSeasonNumber = item.seasonNumber,
            releasedIso = item.released,
        ),
        origin = "live-resolve",
    )

    val sortTimestamp = if (item.isReleaseAlert) {
        com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs(item.released) ?: completedEntry.markedAtEpochMs
    } else {
        completedEntry.markedAtEpochMs
    }
    return contentId to (sortTimestamp to item)
}

private fun logNextUpResolutionRejected(
    completedEntry: CompletedSeriesCandidate,
    stage: String,
) {
    com.nuvio.app.features.watchprogress.NextUpDiagnostics.logResolutionRejected(
        contentId = completedEntry.content.id,
        seedSeasonNumber = completedEntry.seasonNumber,
        seedEpisodeNumber = completedEntry.episodeNumber,
        stage = stage,
    )
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

internal fun splitContinueWatchingRows(
    items: List<ContinueWatchingItem>,
    separateNextUpRow: Boolean,
): Pair<List<ContinueWatchingItem>, List<ContinueWatchingItem>> {
    if (!separateNextUpRow) return items to emptyList()
    return items.filterNot(ContinueWatchingItem::isNextUp) to
        items.filter(ContinueWatchingItem::isNextUp)
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

/**
 * What the Up Next pass has to do this run, decided purely from the candidates and the persisted
 * cache.
 *
 * @param cachedBySeries every cached card whose seed still matches, including the stale-artwork
 *   ones. This is the base the results merge onto, so a retry that fails — or that succeeds and
 *   still finds no still — leaves the card on screen rather than deleting it.
 * @param staleArtworkContentIds cached cards that are missing their episode thumbnail. Only these
 *   may bypass the meta LRU, and only on a manual resync.
 */
internal data class NextUpResolutionPlan(
    val cachedBySeries: Map<String, Pair<Long, ContinueWatchingItem>>,
    val staleArtworkContentIds: Set<String>,
    val candidatesToResolve: List<CompletedSeriesCandidate>,
    val staleReleasePrecisionContentIds: Set<String> = emptySet(),
)

/** Launch/profile load starts at -1, so request version 0 still receives one real metadata read. */
internal fun shouldForceNextUpArtworkMetaRefresh(
    requestVersion: Int,
    appliedVersion: Int,
): Boolean = requestVersion > appliedVersion

/**
 * A non-empty thumbnail is not necessarily an episode still. Some metadata providers fill an
 * unavailable episode thumbnail with the show's backdrop (occasionally at a different TMDB image
 * size). Persisting that value made the old planner declare the card complete forever.
 */
internal fun ContinueWatchingItem.needsEpisodeThumbnailRefresh(): Boolean {
    val thumbnail = episodeThumbnail?.trim()?.takeIf(String::isNotBlank) ?: return true
    val thumbnailIdentity = thumbnail.artworkResourceIdentity() ?: return true
    val copiesSeriesArtwork = sequenceOf(background, poster)
        .mapNotNull { seriesArtwork -> seriesArtwork.artworkResourceIdentity() }
        .any { seriesArtworkIdentity -> seriesArtworkIdentity == thumbnailIdentity }
    if (copiesSeriesArtwork) return true

    val genericArtworkRule = seriesLevelArtworkRule(thumbnail) ?: return false
    ContinueWatchingArtworkDiagnostics.logGenericArtworkDetected(
        contentId = parentMetaId,
        episodeThumbnail = thumbnail,
        matchedRule = genericArtworkRule,
    )
    return true
}

/**
 * Comparing the thumbnail against the card's own poster and backdrop only catches a provider that
 * copies *Nuvio's* series artwork into the still field. AIOMetadata does something the comparison
 * cannot see: it serves a TVDB series background as `episodeThumbnail` while Nuvio's backdrop came
 * from TMDB. The two URLs are unrelated, so the card was declared complete and never retried, and
 * the show backdrop stuck for the life of the seed.
 *
 * These providers name the artwork *kind* in the path, so the URL itself says it is show-level.
 * Only show- and season-level directories are listed — TVDB episode stills live under `/episodes/`
 * and `/episode/`, which deliberately do not match, and TMDB paths (`/t/p/<size>/`) carry no kind
 * at all and so never match either.
 */
private fun seriesLevelArtworkRule(url: String): String? = SeriesLevelArtworkRules
    .firstOrNull { (_, pattern) -> pattern.containsMatchIn(url) }
    ?.first

private val SeriesLevelArtworkRules: List<Pair<String, Regex>> = listOf(
    "thetvdb series artwork" to Regex(
        "/series/[^/]+/(?:backgrounds|posters|banners|icons|clearlogo|clearart|fanart)/",
        RegexOption.IGNORE_CASE,
    ),
    "fanart.tv show artwork" to Regex(
        "/(?:showbackground|tvposter|tvbanner|tvthumb|hdtvlogo|clearlogo|clearart|hdclearart" +
            "|characterart|seasonposter|seasonthumb)/",
        RegexOption.IGNORE_CASE,
    ),
)

private fun String?.artworkResourceIdentity(): String? = this
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.substringBefore('#')
    ?.substringBefore('?')
    // TMDB serves the same file below size-specific paths such as /w500/ and /original/.
    ?.replace(Regex("/t/p/(?:original|w\\d+)/", RegexOption.IGNORE_CASE), "/t/p/")
    ?.lowercase()

/**
 * A seed match alone does not mean a cached card is finished: an episode that had no still when it
 * first resolved can gain one days later, and the card would otherwise stay blank for the whole
 * life of the seed. Cards missing a thumbnail are therefore queued for re-resolution — but behind
 * the never-resolved ones, which have nothing on screen at all and so get first claim on the
 * capped resolution budget.
 */
/**
 * A cached card whose air date is close enough that a day of imprecision would show on screen, and
 * whose stored `released` has no time of day.
 *
 * The date-only form comes from TMDB's `air_date`, which is the network's local date — Ted Lasso
 * S4E5 was stored as `2026-09-01` while the addon knew it as `2026-09-02T04:00:00.000Z`, so the
 * card claimed "New Episode" a full day before the episode existed. The snapshot is only rewritten
 * when a card is re-resolved, and a seed match otherwise means never, so the stale value would
 * outlive the episode. Re-resolving rides the meta LRU, so this costs one fetch per app run.
 */
internal fun ContinueWatchingItem.needsReleasePrecisionRefresh(todayIsoDate: String): Boolean {
    val release = com.nuvio.app.features.watchprogress.resolveReleaseInstant(released) ?: return false
    if (release.hasTimeOfDay) return false
    val daysUntil = com.nuvio.app.features.watchprogress.isoDaysBetween(
        from = todayIsoDate,
        to = release.localIsoDate,
    ) ?: return false
    // From the day after the nominal date (where a premature "New Episode" is showing) out to two
    // days before it. Outside that window nobody can tell the difference.
    return daysUntil in -1..2
}

internal fun planNextUpResolution(
    completedSeriesCandidates: List<CompletedSeriesCandidate>,
    cachedNextUpItems: Map<String, Pair<Long, ContinueWatchingItem>>,
    todayIsoDate: String = CurrentDateProvider.todayIsoDate(),
): NextUpResolutionPlan {
    val cachedBySeries = completedSeriesCandidates.mapNotNull { candidate ->
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
    val staleArtworkContentIds = cachedBySeries
        .filterValues { (_, item) -> item.needsEpisodeThumbnailRefresh() }
        .keys
    val staleReleasePrecisionContentIds = cachedBySeries
        .filterValues { (_, item) -> item.needsReleasePrecisionRefresh(todayIsoDate) }
        .keys
    val staleContentIds = staleArtworkContentIds + staleReleasePrecisionContentIds
    val candidatesToResolve = completedSeriesCandidates.filter { candidate ->
        candidate.content.id !in cachedBySeries
    } + completedSeriesCandidates.filter { candidate ->
        candidate.content.id in staleContentIds
    }
    return NextUpResolutionPlan(
        cachedBySeries = cachedBySeries,
        staleArtworkContentIds = staleArtworkContentIds,
        candidatesToResolve = candidatesToResolve,
        staleReleasePrecisionContentIds = staleReleasePrecisionContentIds,
    )
}

/**
 * Artwork a previous pass already proved good for a series, kept out of the seed-matching cache so
 * it survives the user finishing an episode.
 *
 * Split deliberately by scope. Poster, backdrop and logo belong to the *show* and stay correct no
 * matter which episode is up next. The still belongs to one episode, so it may only be reused when
 * the card resolved to that same episode — carrying it across would put the previous episode's
 * still on the new card, which is worse than a backdrop.
 */
internal data class CachedNextUpArtwork(
    val poster: String?,
    val background: String?,
    val logo: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeThumbnail: String?,
)

/**
 * Fills in whatever the fresh metadata read did not return.
 *
 * A metadata fetch is not all-or-nothing: it can succeed and still come back without a poster, a
 * backdrop or an episode still, either because the provider is degraded or because a fallback
 * provider answered. The resulting card has every artwork field null and renders as an empty tile.
 * Merging the previous pass's artwork underneath means a partial read can only ever add artwork,
 * never take it away.
 */
internal fun ContinueWatchingItem.withCachedArtworkFallback(
    cached: CachedNextUpArtwork?,
    reason: String,
): ContinueWatchingItem {
    if (cached == null) return this
    val isSameEpisode = seasonNumber == cached.seasonNumber && episodeNumber == cached.episodeNumber
    val mergedPoster = poster.orIfBlank(cached.poster)
    val mergedBackground = background.orIfBlank(cached.background)
    val mergedLogo = logo.orIfBlank(cached.logo)
    val mergedEpisodeThumbnail = if (isSameEpisode) {
        episodeThumbnail.orIfBlank(cached.episodeThumbnail)
    } else {
        episodeThumbnail
    }

    ContinueWatchingArtworkDiagnostics.logArtworkRefresh(
        contentId = parentMetaId,
        title = title,
        reason = reason,
        cachedEpisodeThumbnail = cached.episodeThumbnail.takeIf { isSameEpisode },
        freshEpisodeThumbnail = episodeThumbnail,
        mergedEpisodeThumbnail = mergedEpisodeThumbnail,
        mergedPoster = mergedPoster,
        mergedBackground = mergedBackground,
    )

    if (
        mergedPoster == poster &&
        mergedBackground == background &&
        mergedLogo == logo &&
        mergedEpisodeThumbnail == episodeThumbnail &&
        !imageUrl.isNullOrBlank()
    ) {
        return this
    }

    return copy(
        poster = mergedPoster,
        background = mergedBackground,
        logo = mergedLogo,
        episodeThumbnail = mergedEpisodeThumbnail,
        // Recomputed rather than merged: imageUrl is a derived "best available" field, and leaving
        // the fresh read's null here would keep the card blank despite the artwork just restored.
        imageUrl = imageUrl.orIfBlank(mergedEpisodeThumbnail)
            .orIfBlank(mergedBackground)
            .orIfBlank(mergedPoster),
    )
}

/**
 * The map that gets both published to the UI and persisted.
 *
 * The persisted snapshot is what paints on the next launch, so it — not just the rendered item —
 * has to be the merged one. Merging only on the way to the screen let a degraded refresh quietly
 * overwrite a good snapshot: the card looked right until Home was recreated, then came back blank
 * with no way to tell what had happened to it.
 */
internal fun mergeNextUpResultsWithCachedArtwork(
    results: Map<String, Pair<Long, ContinueWatchingItem>>,
    cachedArtwork: Map<String, CachedNextUpArtwork>,
    reason: String,
): Map<String, Pair<Long, ContinueWatchingItem>> = results.mapValues { (contentId, pair) ->
    pair.first to pair.second.withCachedArtworkFallback(
        cached = cachedArtwork[contentId],
        reason = reason,
    )
}

private fun String?.orIfBlank(fallback: String?): String? =
    this?.trim()?.takeIf(String::isNotBlank) ?: fallback?.trim()?.takeIf(String::isNotBlank)

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
    // The cached snapshot is what paints on launch, so a badge that never appears usually fails
    // here rather than in the live resolve.
    com.nuvio.app.features.watchprogress.NextUpDiagnostics.logResolvedCard(
        contentId = contentId,
        title = name,
        seedSeasonNumber = seedSeason ?: -1,
        seedEpisodeNumber = seedEpisode ?: -1,
        seedMarkedAtEpochMs = lastWatched,
        nextSeasonNumber = season,
        nextEpisodeNumber = episode,
        releasedIso = released,
        todayIsoDate = CurrentDateProvider.todayIsoDate(),
        alertState = alertState,
        origin = "cache",
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

// Cinemeta's meta endpoint returns /background/medium/ (1280px); /background/large/ is full HD.
private fun upgradeMetahubBackdrop(url: String?): String? =
    url?.replace("/background/medium/", "/background/large/")

// Pick the highest-quality backdrop from any number of candidate URLs.
// Direct TMDB URLs (image.tmdb.org) are full-resolution originals.
// metahub.space CDN mirrors are lower quality regardless of size path.
private fun bestBackdrop(vararg urls: String?): String? {
    for (url in urls) if (url?.contains("image.tmdb.org") == true) return url
    for (url in urls) if (url != null && !url.contains("images.metahub.space")) return url
    return upgradeMetahubBackdrop(urls.firstOrNull { it != null })
}

private fun canonicalHeroKey(type: String, id: String): String {
    val canonicalId = id.split("_").firstOrNull { it.startsWith("tt", ignoreCase = true) } ?: id
    return "$type:$canonicalId"
}

private fun MetaPreview.needsHomeHeroBackdropFallback(): Boolean =
    type != COLLECTION_HERO_TYPE &&
        banner.isNullOrBlank() &&
        homeHeroFallbackImdbId() != null

private fun MetaPreview.needsHomeHeroMetadataEnrichment(): Boolean =
    type != COLLECTION_HERO_TYPE &&
        (needsHomeHeroBackdropFallback() || ageRating.isNullOrBlank() || needsHeroTextEnrichment())

/**
 * True for a row that arrived carrying no real text metadata — the local library (a folder scan
 * plus a TMDB poster) and the cloud-library catalogs (which list files, so their "description" is a
 * size/quality line and they have no genres at all). Such a hero renders as a bare title over the
 * type name ("Movie", "Library") unless its text is fetched like every other field.
 *
 * An ordinary catalog row from a metadata addon always has genres, so this costs it nothing.
 */
private fun MetaPreview.needsHeroTextEnrichment(): Boolean =
    type != COLLECTION_HERO_TYPE &&
        randomPlayCategoryOrNull() == null &&
        (genres.isEmpty() || description.isNullOrBlank())

/**
 * Marks a hero copy whose logo slot must stay empty because a logo is expected imminently.
 *
 * A row carrying no logo of its own, that still needs text enrichment, always has an enrichment in
 * flight - and that enrichment is where the logo comes from. Rendering the title text meanwhile
 * only to replace it with a logo is the Continue Watching flash; see
 * [MetaPreview.heroMetadataPending]. A row that already has a logo, or needs nothing, is returned
 * untouched and renders immediately as before.
 */
private fun MetaPreview.withPendingLogoSlot(): MetaPreview =
    if (logo.isNullOrBlank() && needsHeroTextEnrichment()) {
        copy(heroMetadataPending = true)
    } else {
        this
    }

/**
 * Anything published to the hero enrichment map is a resolved answer by definition, so it must
 * never carry the pending marker - a copy that did would hold the slot empty forever.
 */
private fun MetaPreview.asResolvedHeroEnrichment(): MetaPreview =
    if (heroMetadataPending) copy(heroMetadataPending = false) else this

// TEMPORARY (hero race diagnosis) - elapsed milliseconds since the first probe call. The log shows
// ordering but carries no timestamps of its own, so without this the one thing that actually
// matters here - how long the hero sits blank, and where that time goes - is unmeasurable.
private val heroProbeClock = kotlin.time.TimeSource.Monotonic.markNow()

internal fun heroProbeMs(): Long = heroProbeClock.elapsedNow().inWholeMilliseconds

// TEMPORARY (hero race diagnosis) - remove with the other probes. Records every write to
// displayedFocusedItem so a repro log shows which writer set the hero item and how complete it was.
internal fun logHeroPick(site: String, item: MetaPreview?) {
    co.touchlab.kermit.Logger.withTag("HeroLogoRace").i {
        if (item == null) {
            "t=${heroProbeMs()} site=$site item=null"
        } else {
            "t=${heroProbeMs()} site=$site key=${canonicalHeroKey(item.type, item.id)} " +
                "logo=${item.logo?.takeLast(26) ?: "NONE"} pending=${item.heroMetadataPending} " +
                "genres=${item.genres.size} hasDescr=${!item.description.isNullOrBlank()} " +
                "needsText=${item.needsHeroTextEnrichment()}"
        }
    }
}

// TEMPORARY (hero race diagnosis) - records every publish into the hero enrichment map with its
// completeness, so a partial entry landing first and a fuller one landing after it shows up as two
// PUBLISH lines for one key.
internal fun publishHeroEnrichment(
    map: MutableMap<String, MetaPreview>,
    item: MetaPreview,
    site: String,
) {
    val key = canonicalHeroKey(item.type, item.id)
    val previous = map[key]
    co.touchlab.kermit.Logger.withTag("HeroLogoRace").i {
        "t=${heroProbeMs()} PUBLISH site=$site key=$key logo=${item.logo?.takeLast(24) ?: "NONE"} " +
            "genres=${item.genres.size} hasDescr=${!item.description.isNullOrBlank()} " +
            "replaces=${previous?.let { "genres=" + it.genres.size + ",logo=" + (it.logo != null) } ?: "nothing"}"
    }
    map[key] = item.asResolvedHeroEnrichment()
}

/**
 * Strips the addon catalog's art and metadata from a Search/Library hero item so the hero shows
 * nothing but the (correct) title until TMDB/TVDB enrichment resolves. Used only when the hero
 * image source isn't Addon — otherwise the catalog's poster/backdrop/text flash for ~1s and then
 * get replaced, which reads as a metadata race. Identity (id/type/name) is preserved so
 * enrichment can still fetch by it; the grid is unaffected (it reads effectiveSections).
 *
 * Only safe where the enrichment pass refetches all of this. Normal mode's does not — see
 * [asPendingHeroArtPreview].
 */
private fun MetaPreview.asPendingHeroPreview(): MetaPreview = asPendingHeroArtPreview().copy(
    description = null,
    releaseInfo = null,
    genres = emptyList(),
)

/**
 * The art half of [asPendingHeroPreview], for Normal mode's row-seeded hero: the enrichment pass
 * there restores backdrop and logo but never the text, so the text has to survive.
 */
private fun MetaPreview.asPendingHeroArtPreview(): MetaPreview = copy(
    poster = null,
    posterFallback = null,
    banner = null,
    logo = null,
)

/**
 * Fills the hero's text — genres, synopsis, release info — from a fetched meta, for rows that
 * arrived without any of their own. The local library is a folder scan plus a TMDB poster; the
 * cloud-library catalogs list account contents. Both render as a bare title over the type name
 * ("Movie", "Library") until this runs.
 *
 * Fill-only for an ordinary catalog row: a metadata addon's own text is at least as good, and
 * overwriting it would fight the addon (and reintroduce the flash this pass exists to avoid).
 * A row with a resolved metadata identity is the exception — its "description" and "releaseInfo"
 * describe the *file* the provider listed ("📦 36.3 GB • 🖥️ 1080p", the debrid service's name), so
 * there the identified title's text has to win outright.
 */
private fun MetaPreview.withFetchedHeroText(meta: MetaDetails?): MetaPreview {
    if (meta == null) return this
    val describesAFile = metaLookupId != null
    fun pick(own: String?, fetched: String?): String? = when {
        describesAFile -> fetched?.takeIf { it.isNotBlank() } ?: own
        else -> own?.takeIf { it.isNotBlank() } ?: fetched
    }
    return copy(
        genres = genres.ifEmpty { meta.genres.map(::normalizeSearchGenre) },
        description = pick(description, meta.description),
        releaseInfo = pick(releaseInfo, meta.releaseInfo),
    )
}

private fun MetaPreview.homeHeroFallbackImdbId(): String? =
    metadataId.split("_").firstOrNull { segment -> segment.startsWith("tt", ignoreCase = true) }

private fun normalizeSearchGenre(genre: String): String =
    genre.split("-", " ").joinToString(" ") { word ->
        word.replaceFirstChar { it.uppercaseChar() }
    }

private fun isSearchActivationInsertion(previous: String, next: String): Boolean {
    if (previous.isBlank() && next.equals("s", ignoreCase = true)) return true
    if (next.length != previous.length + 1) return false
    return next.indices.any { index ->
        next[index].equals('s', ignoreCase = true) &&
            next.removeRange(index, index + 1) == previous
    }
}
