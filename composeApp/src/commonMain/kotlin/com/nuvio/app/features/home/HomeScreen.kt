package com.nuvio.app.features.home

import coil3.compose.LocalPlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.nuvio.app.core.ui.NuvioNetworkOfflineCard
import com.nuvio.app.core.ui.smoothVerticalWheelScroll
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.rememberMouseActivityState
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.search.DiscoverEmptyStateReason
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
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeHeroReservedSpace
import com.nuvio.app.features.home.components.HomeHeroSection
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
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklLibraryRepository
import com.nuvio.app.features.simkl.SimklSettingsRepository
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    contentMode: HomeContentMode = HomeContentMode.Normal,
    searchQuery: String = "",
    discoverModeActive: Boolean = false,
    searchSubmitRequests: Flow<String> = emptyFlow(),
    navigateToContentCount: Int = 0,
    animateCollectionGifs: Boolean = true,
    scrollToTopRequests: Flow<Unit> = emptyFlow(),
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onLocalLibraryPosterClick: ((MetaPreview) -> Unit)? = null,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
    onCastClick: ((HeroCastMember) -> Unit)? = null,
    onFirstCatalogRendered: (() -> Unit)? = null,
    onNavigateToSearch: (() -> Unit)? = null,
    onNavigateToLibrary: (() -> Unit)? = null,
    onNavigateToCalendar: (() -> Unit)? = null,
    onNavigateToHome: (() -> Unit)? = null,
    resumePromptItem: ContinueWatchingItem? = null,
    resumePromptLabel: String = "",
    resumePromptActionLabel: String = "",
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

    // Search mode state — query persists while on the Search tab (rememberSaveable).
    // searchQuery is owned by App.kt / the nav bar and passed in directly.
    // When blank in Search mode, fall back to home content so the screen isn't empty.
    val searchUiState by remember {
        SearchRepository.uiState
    }.collectAsStateWithLifecycle()
    val discoverUiState by remember {
        SearchRepository.discoverUiState
    }.collectAsStateWithLifecycle()

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
    val isSearchPristine = !discoverModeActive && searchUiState.sections.isEmpty() &&
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

    val discoverAllGenresLabel = stringResource(Res.string.discover_all_genres)

    // Compute effective sections and hero items based on content mode.
    val effectiveSections: List<HomeCatalogSection> = remember(
        displayMode, contentMode, searchQuery, discoverModeActive, discoverUiState,
        discoverAllGenresLabel,
        homeUiState.sections, searchUiState.sections, libraryUiState.sections,
    ) {
        when (displayMode) {
            is HomeContentMode.Normal -> homeUiState.sections
            is HomeContentMode.Search -> {
                if (discoverModeActive) {
                    val catalog = discoverUiState.selectedCatalog
                    if (catalog == null || discoverUiState.items.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            HomeCatalogSection(
                                key = "discover:${catalog.key}:${discoverUiState.selectedGenre.orEmpty()}",
                                title = "${catalog.catalogName} • ${discoverUiState.selectedGenre ?: discoverAllGenresLabel}",
                                subtitle = catalog.addonName,
                                addonName = catalog.addonName,
                                target = com.nuvio.app.features.catalog.CatalogTarget.Addon(
                                    manifestUrl = catalog.manifestUrl,
                                    contentType = catalog.type,
                                    catalogId = catalog.catalogId,
                                    genre = discoverUiState.selectedGenre,
                                    supportsPagination = false,
                                ),
                                items = discoverUiState.items,
                            ),
                        )
                    }
                } else {
                // Normalize genres for all search result items at the section level so
                // every item — whether it needs further enrichment or not — gets capitalised
                // genres. (Addon catalog responses return lowercase genre strings.)
                searchUiState.sections.map { section ->
                    section.copy(items = section.items.map { item ->
                        val current = if (item.genres.isEmpty()) item
                        else item.copy(genres = item.genres.map(::normalizeSearchGenre))
                        if (tmdbImageModeOn) {
                            current.copy(banner = null, logo = null)
                        } else {
                            current
                        }
                    })
                }
                }
            }
            // Library
            else -> libraryUiState.sections.map { section ->
                HomeCatalogSection(
                    key = "library_${section.type}",
                    title = section.displayTitle,
                    subtitle = "",
                    addonName = "",
                    target = com.nuvio.app.features.catalog.CatalogTarget.Library(
                        contentType = "movie",
                        sectionType = section.type,
                    ),
                    items = section.items.map { it.toMetaPreview().let { preview ->
                        val sourcedPreview = preview.copy(
                            preferLocalStreams = section.type.startsWith("locallibrary_"),
                        )
                        if (tmdbImageModeOn) sourcedPreview.copy(banner = null, logo = null)
                        else sourcedPreview
                    } },
                    availableItemCount = section.items.size,
                    hasMore = false,
                    paginates = false,
                )
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
        homeSettingsUiState.adaptiveHeroEnabled,
        homeSettingsUiState.tvModeEnabled,
        homeSettingsUiState.heroAmbientBackgroundEnabled,
        resumeHeroPreview,
    ) {
        resumeHeroPreview?.let { return@remember listOf(it) }
        // In non-Addon hero-image modes, suppress the catalog's art/metadata on Search/Library
        // hero items so TMDB/TVDB enrichment doesn't visibly replace it ~1s later. Hero-only:
        // baseHeroItems is a separate list from the results grid (which reads effectiveSections).
        fun List<MetaPreview>.suppressingCatalogHero() =
            if (tmdbImageModeOn) map(MetaPreview::asPendingHeroPreview) else this
        // Seed the hero from the visible rows (what you're currently browsing), used by Search,
        // Library, and — as a fallback — Normal mode's content-following desktop backdrop modes.
        fun currentlyViewingHeroSeed() =
            effectiveSections.take(2).flatMap { it.items.take(8) }.distinctBy { "${it.type}:${it.id}" }
                .suppressingCatalogHero()
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
                if (desktopBackdropModeActive) currentlyViewingHeroSeed() else emptyList()
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
    var heroMetadataStartupGraceUsed by remember { mutableStateOf(false) }
    var continueWatchingMetadataStartupGraceUsed by remember { mutableStateOf(false) }

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
        val normalHomeNeedsBackdropFallback = normalHomeMode &&
            baseHeroItems.any(MetaPreview::needsHomeHeroBackdropFallback)
        // Home page: rich metadata addons already provide good real-time images. The exception
        // is catalog-only addons that return posters but no banner/background, so fill just
        // those missing hero backdrops from the existing lightweight metadata fallback path.
        if (normalHomeMode && !normalHomeNeedsBackdropFallback) return@LaunchedEffect
        if (!normalHomeMode && !tmdbImageModeOn) return@LaunchedEffect
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
            val shouldUseHomeBackdropFallback = normalHomeMode && current.needsHomeHeroBackdropFallback()
            if (normalHomeMode && !shouldUseHomeBackdropFallback) return@mapIndexed current
            val cached = MetaDetailsRepository.peek(item.type, item.id) ?: return@mapIndexed current
            val enriched = if (normalHomeMode) {
                current.copy(
                    banner = bestBackdrop(cached.background, current.banner),
                    logo = current.logo ?: cached.logo,
                )
            } else {
                current.copy(
                    genres = cached.genres.ifEmpty { current.genres }.map(::normalizeSearchGenre),
                    description = cached.description ?: current.description,
                    releaseInfo = cached.releaseInfo ?: current.releaseInfo,
                    runtime = current.runtime ?: cached.runtime,
                    banner = bestBackdrop(cached.background, current.banner),
                    logo = current.logo ?: cached.logo,
                )
            }
            enriched.also {
                heroEnrichmentMap[canonicalHeroKey(enriched.type, enriched.id)] = enriched
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
                val shouldUseHomeBackdropFallback = normalHomeMode && current.needsHomeHeroBackdropFallback()
                if (normalHomeMode && !shouldUseHomeBackdropFallback) return@async idx to current
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
                    tmdbImageModeOn && hasFullMetadata && hasIdealBanner -> return@async idx to current
                // Addon mode: skip when all text + any banner present.
                    !tmdbImageModeOn && hasFullMetadata && current.banner != null -> return@async idx to current
            }
                sem.withPermit {
                    // When TMDB mode is on, fetchLightweightMeta collects addon text metadata
                    // then calls tryFetchTmdbFallbackMeta for high-quality TMDB images,
                    // merging the two. No separate TmdbHeroImageService call needed.
                    val meta = runCatching {
                        MetaDetailsRepository.fetchLightweightMeta(
                            type = current.type,
                            id = current.id,
                            preferTmdbImages = tmdbImageModeOn || normalHomeMode,
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
                        )
                    } else {
                        val fetchedMeta = meta ?: return@withPermit idx to current
                        now.copy(
                            genres = fetchedMeta.genres.ifEmpty { now.genres }.map(::normalizeSearchGenre),
                            description = fetchedMeta.description ?: now.description,
                            releaseInfo = fetchedMeta.releaseInfo ?: now.releaseInfo,
                            runtime = now.runtime ?: fetchedMeta.runtime,
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
                    heroEnrichmentMap[canonicalHeroKey(enriched.type, enriched.id)] = enriched
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
    
    val currentListState = when (displayMode) {
        is HomeContentMode.Normal -> homeListState
        is HomeContentMode.Library -> libraryListState
        is HomeContentMode.Search -> searchListState
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

    // SIMKL takes priority over Trakt when both are active. Suppress all Trakt-specific CW
    // behaviours (day-cap window, dropped-show exclusion, entry remapping) when SIMKL is the
    // effective source — otherwise the Trakt day-cap silently hides SIMKL history seeds.
    val isTraktProgressActive = remember(
        isTraktAuthenticated,
        traktSettingsUiState.watchProgressSource,
        simklIsAuthenticated,
        simklSettingsUiState.simklAsCwSource,
    ) {
        val simklCwActive = simklIsAuthenticated && simklSettingsUiState.simklAsCwSource
        !simklCwActive && shouldUseTraktProgress(
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
        simklIsAuthenticated,
        simklSettingsUiState.simklAsCwSource,
        simklSettingsUiState.simklContinueWatchingDaysCap,
    ) {
        val simklCwActive = simklIsAuthenticated && simklSettingsUiState.simklAsCwSource
        val now = WatchProgressClock.nowEpochMs()
        var candidates = filterHomeNextUpCandidatesForTraktContinueWatchingWindow(
            candidates = allNextUpSeedCandidates,
            isTraktProgressActive = isTraktProgressActive,
            daysCap = traktSettingsUiState.continueWatchingDaysCap,
            nowEpochMs = now,
        )
        if (simklCwActive) {
            val daysCap = simklSettingsUiState.simklContinueWatchingDaysCap
            if (daysCap > com.nuvio.app.features.simkl.SIMKL_CW_DAYS_CAP_ALL) {
                val cutoffMs = now - daysCap.toLong() * 24L * 60L * 60L * 1000L
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
                                com.nuvio.app.features.player.AnimeContentCache.record(item.parentMetaId, meta.genres)
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
    val enabledHomeItems = remember(homeSettingsUiState.items) {
        homeSettingsUiState.items.filter { it.enabled }
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
    // In Adaptive Hero mode the hero is only a strip, so a full-screen trailer needs its
    // container expanded to the whole screen (TV Mode's hero already fills the viewport).
    val heroTrailerFullscreenActive =
        heroTrailerShowing && trailersEnabledForCurrentMode && playerTrailerSettings.heroTvTrailerFullscreen
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
    val searchTvFocus = remember { HomeTvFocusState() }
    val tvFocus = when (displayMode) {
        is HomeContentMode.Normal -> homeTvFocus
        is HomeContentMode.Library -> libraryTvFocus
        is HomeContentMode.Search -> searchTvFocus
    }

    // --- Continue-watching resume hero (a resume-styled variant of the adaptive hero) ---
    // The Continue Watching TV row normally carries no metaItems, so the hero never follows it.
    // Giving it hero previews lets the Adaptive / Ambient / TV hero retarget to the focused CW card
    // and offer a Resume action. Basic mode never follows focus (see heroFollowsFocusedItem), so it
    // is unaffected — matching "all modes but basic".
    val continueWatchingHeroPreviews = remember(continueWatchingItems) {
        continueWatchingItems.map { it.toHomeHeroPreview() }
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
    // Restart the hero-trailer dwell timer whenever TV focus moves (any input method).
    LaunchedEffect(tvFocus.sectionIndex, tvFocus.itemIndex) {
        HomeHeroTrailerGate.notifyFocusChanged()
    }
    val tvFocusRequester = remember { FocusRequester() }
    var lastStateDrivenSearchQuery by remember { mutableStateOf<String?>(null) }
    // Move content-area focus when the user presses Down from the search bar.
    LaunchedEffect(navigateToContentCount) {
        if (navigateToContentCount > 0) {
            delay(50)
            try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
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
    var searchImmersiveRowIndex by remember { mutableStateOf(0) }
    val getImmersiveRowIndex = {
        when (displayMode) {
            is HomeContentMode.Normal -> homeImmersiveRowIndex
            is HomeContentMode.Library -> libraryImmersiveRowIndex
            is HomeContentMode.Search -> searchImmersiveRowIndex
        }
    }
    val setImmersiveRowIndex = { value: Int ->
        when (displayMode) {
            is HomeContentMode.Normal -> homeImmersiveRowIndex = value
            is HomeContentMode.Library -> libraryImmersiveRowIndex = value
            is HomeContentMode.Search -> searchImmersiveRowIndex = value
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

    var immersiveWheelLocked by remember { mutableStateOf(false) }

    val tvRows = remember(
        contentMode,
        continueWatchingPreferences.isVisible,
        continueWatchingItems,
        continueWatchingHeroPreviews,
        continueWatchingHeroFollowActive,
        enabledHomeItems,
        collectionsMap,
        sectionsMap,
        effectiveSections,
        onContinueWatchingClick,
        onFolderClick,
        posterClickHandler,
    ) {
        buildList {
            if (isShowingHomeContent && continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                add(
                    HomeTvRow(
                        itemCount = continueWatchingItems.size,
                        // Hero previews let the adaptive/TV hero follow the focused CW card. Nulled
                        // out while the TV dismiss is active so the hero reverts to the catalog rows.
                        metaItems = if (continueWatchingHeroFollowActive) continueWatchingHeroPreviews else null,
                        onEnter = { index ->
                            continueWatchingItems.getOrNull(index)?.let { onContinueWatchingClick?.invoke(it) }
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
                                    heroItem ?: collectionHeroItems.firstNotNullOfOrNull { it }
                                        ?: MetaPreview(
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
                        val entries = if (section.paginates) {
                            section.items
                        } else {
                            section.items.take(HOME_CATALOG_PREVIEW_LIMIT)
                        }
                        add(
                            HomeTvRow(
                                itemCount = entries.size,
                                metaItems = entries,
                                onEnter = { index ->
                                    entries.getOrNull(index)?.let { posterClickHandler?.invoke(it) }
                                },
                                onLoadMore = if (section.paginates) {
                                    { HomeRepository.loadMoreCatalogRow(section.key) }
                                } else {
                                    null
                                },
                                onRightAtEnd = if (
                                    !section.paginates &&
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

            // Search / Library mode: add effective sections as TV rows for keyboard navigation.
            if (displayMode !is HomeContentMode.Normal) {
                effectiveSections.filter { it.items.isNotEmpty() }.forEach { section ->
                    val entries = section.items
                    add(
                        HomeTvRow(
                            itemCount = entries.size,
                            metaItems = entries,
                            onEnter = { index ->
                                entries.getOrNull(index)?.let { posterClickHandler?.invoke(it) }
                            },
                            onRightAtEnd = null,
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
            if (tvModeEnabled) {
                setImmersiveRowIndex((getImmersiveRowIndex() + 1)
                    .coerceAtMost((tvRows.size - 1).coerceAtLeast(0)))
                tvFocus.sectionIndex = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
            } else {
                tvFocus.moveSection(1, tvSectionCount)
            }
            tvFocus.itemIndex = tvFocus.itemIndex
                .coerceIn(0, (tvItemCountForSection(tvFocus.sectionIndex) - 1).coerceAtLeast(0))
            if (!tvModeEnabled) scrollToFocusedTvSection()
            true
        }
        HomeTvKey.Up -> {
            mouseActivity.onKeyboardNavigation(ignoreNextMouseMove = leavingNativeTrailer)
            if (tvModeEnabled) {
                setImmersiveRowIndex((getImmersiveRowIndex() - 1).coerceAtLeast(0))
                tvFocus.sectionIndex = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
            } else {
                tvFocus.moveSection(-1, tvSectionCount)
            }
            tvFocus.itemIndex = tvFocus.itemIndex
                .coerceIn(0, (tvItemCountForSection(tvFocus.sectionIndex) - 1).coerceAtLeast(0))
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
            if (trailersEnabledForCurrentMode && (adaptiveHeroEnabled || tvModeEnabled)) {
                HomeHeroTrailerManualTrigger.trigger()
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
        continueWatchingItems,
        enabledHomeItems,
        collectionsMap,
        sectionsMap,
        effectiveSections,
    ) {
        buildList<HomeCatalogSettingsItem?> {
            if (isShowingHomeContent) {
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
            } else {
                // Search / Library: one immersive row per effective section
                effectiveSections.filter { it.items.isNotEmpty() }.forEach { section ->
                    add(HomeCatalogSettingsItem(
                        key = section.key,
                        defaultTitle = section.title,
                        addonName = section.addonName,
                    ))
                }
            }
        }
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
    // For Search/Library: hold hero on the previous item while the new one enriches.
    // For Normal (home): pass through directly — addon already provides good images.
    var displayedFocusedItem by remember { mutableStateOf<MetaPreview?>(null) }
    LaunchedEffect(tvFocusedHeroItemRaw, tmdbImageModeOn, displayMode) {
        val normalHomeFocusedFallback = displayMode is HomeContentMode.Normal &&
            tvFocusedHeroItemRaw?.needsHomeHeroBackdropFallback() == true
        val isEnrichedMode = (tmdbImageModeOn && displayMode !is HomeContentMode.Normal) ||
            normalHomeFocusedFallback
        if (!isEnrichedMode) {
            displayedFocusedItem = tvFocusedHeroItemRaw
            return@LaunchedEffect
        }
        val raw = tvFocusedHeroItemRaw
        if (raw == null) {
            displayedFocusedItem = null
            return@LaunchedEffect
        }
        val key = canonicalHeroKey(raw.type, raw.id)
        if (normalHomeFocusedFallback) {
            heroEnrichmentMap[key]?.let { enriched ->
                displayedFocusedItem = enriched
                return@LaunchedEffect
            }
        }
        snapshotFlow { heroEnrichmentMap[key] }
            .filterNotNull()
            .first()
            .let { enriched -> displayedFocusedItem = enriched }
    }
    val tvFocusedHeroItem = displayedFocusedItem
    // The Basic home hero is a static rotation through the user's 1–2 chosen hero catalogs; it
    // must NOT retarget to whatever poster is focused — that "follow the focused object" behaviour
    // belongs to Adaptive/Adaptive Ambient and TV Mode. Search and Library always preview the
    // focused result (their whole point), so they keep following focus regardless of hero mode.
    val heroFollowsFocusedItem =
        displayMode !is HomeContentMode.Normal || adaptiveHeroEnabled || tvModeEnabled
    val heroFocusedItem = if (heroFollowsFocusedItem) tvFocusedHeroItem else null

    // Resolve which resume affordance (if any) the hero should show. Launch crash-recovery takes
    // priority and replaces the whole hero; otherwise, when a Continue Watching card is the focused
    // hero item, drive a resume prompt for it. The CW row is always tvRows index 0 when present.
    val continueWatchingRowPresent = isShowingHomeContent &&
        continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()
    val focusedContinueWatchingItem: ContinueWatchingItem? = if (
        resumeHeroPreview == null &&
        heroFollowsFocusedItem &&
        continueWatchingHeroFollowActive &&
        continueWatchingRowPresent &&
        tvFocusedRowIndex == 0
    ) {
        continueWatchingItems.getOrNull(tvFocus.itemIndex)
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
        focusedContinueWatchingItem != null -> {
            effectiveResumeItemKey =
                "${focusedContinueWatchingItem.parentMetaType}:${focusedContinueWatchingItem.parentMetaId}"
            effectiveOnResumeAction = { onContinueWatchingClick?.invoke(focusedContinueWatchingItem) }
            // Dismiss only in TV Mode (auto-focused); adaptive modes revert on mouse-out.
            effectiveOnResumeDismiss = if (tvModeEnabled) {
                onContinueWatchingHeroDismiss
            } else {
                null
            }
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
    // Prefetch for Search and Library: warm only the first few metadata targets per row.
    // next section in full. Search/library sets are small (20–50 items) so this is cheap.
    // Home is excluded — the addon handles it in real-time.
    LaunchedEffect(tvFocus.sectionIndex, tvFocus.itemIndex, displayMode) {
        val normalHomeMode = displayMode is HomeContentMode.Normal
        val focusedNeedsHomeFallback = normalHomeMode &&
            tvFocusedHeroItemRaw?.needsHomeHeroBackdropFallback() == true
        if (!tmdbImageModeOn && !focusedNeedsHomeFallback) return@LaunchedEffect
        if (normalHomeMode && !focusedNeedsHomeFallback) return@LaunchedEffect

        val isTvForTvdb = tmdbSettingsUiState.heroImageSource == HeroImageSource.TmdbMoviesTvdbShows

        fun enrich(raw: MetaPreview) = launch {
            val mapKey = canonicalHeroKey(raw.type, raw.id)
            if (heroEnrichmentMap.containsKey(mapKey)) return@launch
            val meta = runCatching {
                MetaDetailsRepository.fetchLightweightMeta(raw.type, raw.id, preferTmdbImages = true)
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
                )
                heroEnrichmentMap[canonicalHeroKey(enriched.type, enriched.id)] = enriched
                return@launch
            }
            val fetchedMeta = meta ?: return@launch
            val tvdb = isTvForTvdb && (raw.type.equals("series", ignoreCase = true) ||
                raw.type.equals("anime", ignoreCase = true))
            val enriched = raw.copy(
                genres = fetchedMeta.genres.ifEmpty { raw.genres }.map(::normalizeSearchGenre),
                description = fetchedMeta.description ?: raw.description,
                releaseInfo = fetchedMeta.releaseInfo ?: raw.releaseInfo,
                runtime = raw.runtime ?: fetchedMeta.runtime,
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
            heroEnrichmentMap[canonicalHeroKey(enriched.type, enriched.id)] = enriched
        }

        // Current item — immediately, highest priority.
        tvFocusedHeroItemRaw?.let { enrich(it) }
        if (normalHomeMode) return@LaunchedEffect

        // Entire current row (all remaining items the user will scroll through).
        tvRows.getOrNull(tvFocus.sectionIndex)?.metaItems?.let { items ->
            items.drop(tvFocus.itemIndex + 1)
                .take(SEARCH_LIBRARY_METADATA_PREFETCH_LIMIT)
                .forEach { enrich(it) }
        }

        // Entire next row — ready before the user even gets there.
        tvRows.getOrNull(tvFocus.sectionIndex + 1)?.metaItems
            ?.take(SEARCH_LIBRARY_METADATA_PREFETCH_LIMIT)
            ?.forEach { enrich(it) }
    }
    val immersiveMetadataPrefetchItems = if (tvModeEnabled) {
        // Home and Library warm the whole focused row: both are stable, user-owned catalogs whose
        // metadata is worth caching, so the one-time cost is fine. Search is different every time and
        // most results are noise (ranked, so what matters is the top of each catalog), so there we cap
        // the hero's eager (rate-limited MDBList) enrichment to the first couple per catalog; deeper
        // results still enrich on demand once focused.
        val perRowLimit = if (displayMode is HomeContentMode.Search) SEARCH_HERO_METADATA_PREFETCH_PER_CATALOG else Int.MAX_VALUE
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
            .focusable()
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { _ ->
                try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
            }
            .onPreviewKeyEvent { event ->
                when {
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
                                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                                    val direction = change.scrollDelta.y.compareTo(0f)
                                    if (direction != 0) {
                                        change.consume()
                                        if (!immersiveWheelLocked && tvRows.isNotEmpty()) {
                                            immersiveWheelLocked = true
                                            setImmersiveRowIndex((getImmersiveRowIndex() + direction)
                                                .coerceIn(0, tvRows.lastIndex))
                                            tvFocus.sectionIndex =
                                                getImmersiveRowIndex() + if (heroFocusable) 1 else 0
                                            tvFocus.itemIndex = tvFocus.itemIndex.coerceIn(
                                                0,
                                                (tvRows[getImmersiveRowIndex()].itemCount - 1).coerceAtLeast(0),
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
                                    if (tvModeEnabled) {
                                        setImmersiveRowIndex((getImmersiveRowIndex() + 1)
                                            .coerceAtMost((tvRows.size - 1).coerceAtLeast(0)))
                                        tvFocus.sectionIndex = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
                                    } else {
                                        tvFocus.moveSection(1, tvSectionCount)
                                    }
                                    tvFocus.itemIndex = tvFocus.itemIndex
                                        .coerceIn(0, (tvItemCountForSection(tvFocus.sectionIndex) - 1).coerceAtLeast(0))
                                    if (!tvModeEnabled) scrollToFocusedTvSection()
                                    true
                                }
                                Key.DirectionUp -> {
                                    mouseActivity.onKeyboardNavigation()
                                    if (tvModeEnabled) {
                                        setImmersiveRowIndex((getImmersiveRowIndex() - 1).coerceAtLeast(0))
                                        tvFocus.sectionIndex = getImmersiveRowIndex() + if (heroFocusable) 1 else 0
                                    } else {
                                        tvFocus.moveSection(-1, tvSectionCount)
                                    }
                                    tvFocus.itemIndex = tvFocus.itemIndex
                                        .coerceIn(0, (tvItemCountForSection(tvFocus.sectionIndex) - 1).coerceAtLeast(0))
                                    if (!tvModeEnabled) scrollToFocusedTvSection()
                                    true
                                }
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
        val immersivePosterBaseWidthDp = remember(
            maxWidth.value,
            immersiveShelfHeight,
            homeSectionPadding,
        ) {
            immersiveCatalogPosterBaseWidthDp(
                maxWidthDp = maxWidth.value,
                shelfHeightDp = immersiveShelfHeight.value,
                sectionPaddingDp = homeSectionPadding.value,
                // This width is only consumed by the TV Mode shelf, whose renderers always
                // hide poster labels (rememberHomePosterCardStyleUiState forces it). Sizing
                // from the raw saved preference reserved label space that never renders —
                // e.g. after account sync pulled a mobile hideLabels=false — shrinking the
                // shelf posters below the fill-the-shelf size.
                hideLabels = true,
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
                    adaptiveHeroMode = adaptiveHeroEnabled,
                    heroInfoLines = homeSettingsUiState.heroInfoLines,
                    heroInfoPriority = homeSettingsUiState.heroInfoPriority,
                    heroBadgePlacement = homeSettingsUiState.heroBadgePlacement,
                    heroReleaseStatusUnavailableOnly = homeSettingsUiState.heroReleaseStatusUnavailableOnly,
                    trailersEnabledInCurrentMode = trailersEnabledForCurrentMode,
                    immersiveContentBottomPadding = immersiveShelfHeight - 20.dp,
                    resumePromptItemKey = effectiveResumeItemKey,
                    resumePromptLabel = resumePromptLabel,
                    resumePromptActionLabel = resumePromptActionLabel,
                    onResumePromptAction = effectiveOnResumeAction,
                    onResumePromptDismiss = effectiveOnResumeDismiss,
                    onActiveItemChanged = { item ->
                        activeHeroBackdrop = item.banner ?: item.poster
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
                    if (isShowingHomeContent && continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
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

                homeUiState.isLoading && effectiveSections.isEmpty() && !hasRenderableCollectionRows -> {
                    if (isShowingHomeContent && continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
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

                effectiveSections.isEmpty() && effectiveHeroItems.isEmpty() &&
                    (!continueWatchingPreferences.isVisible || continueWatchingItems.isEmpty()) &&
                    !hasRenderableCollectionRows -> {
                    // In Search mode the input field is the only UI needed when there are
                    // no results — no query means "type to search", query means "no matches".
                    // In Library mode an empty library just shows nothing.
                    // Only Normal mode gets the home-specific empty state cards.
                    if (discoverModeActive) {
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
                        continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
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

                    // In Search/Library mode: render effectiveSections directly (home settings
                    // don't contain search result or library section keys, so the normal
                    // enabledHomeItems loop would render nothing).
                    val rowSections: List<HomeCatalogSection> = if (isShowingHomeContent) {
                        enabledHomeItems.mapNotNull { item ->
                            if (!item.isCollection) sectionsMap[item.key]?.takeIf { it.items.isNotEmpty() }
                            else null
                        }
                    } else {
                        effectiveSections.filter { it.items.isNotEmpty() }
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
                                        entries = if (section.paginates) {
                                            section.items
                                        } else {
                                            section.items.take(HOME_CATALOG_PREVIEW_LIMIT)
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
                                        onViewAllClick = if (section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)) {
                                            onCatalogClick?.let { { it(section) } }
                                        } else {
                                            null
                                        },
                                        onLoadMore = if (section.paginates) {
                                            { HomeRepository.loadMoreCatalogRow(section.key) }
                                        } else {
                                            null
                                        },
                                        isLoadingMore = section.isLoadingMore,
                                        watchedKeys = watchedUiState.watchedKeys,
                                        onPosterClick = posterClickHandler,
                                        onPosterLongClick = onPosterLongClick,
                                    )
                                }
                            }
                        }
                    }

                    // Search / Library mode: render result sections directly.
                    if (!isShowingHomeContent) {
                        rowSections.forEach { section ->
                            val rowIndex = tvRowCursor++
                            val sectionIndex = if (heroFocusable) rowIndex + 1 else rowIndex
                            item(key = section.key) {
                                HomeCatalogRowSection(
                                    section = section,
                                    entries = section.items,
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
                                    onViewAllClick = null,
                                    onLoadMore = null,
                                    isLoadingMore = false,
                                    watchedKeys = watchedUiState.watchedKeys,
                                    onPosterClick = posterClickHandler,
                                    onPosterLongClick = onPosterLongClick,
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
                        .padding(
                            top = IMMERSIVE_SHELF_TOP_PADDING_DP.dp,
                            bottom = IMMERSIVE_SHELF_BOTTOM_PADDING_DP.dp,
                        ),
                ) {
                    when {
                        activeSettingsItem == null && isShowingHomeContent -> HomeContinueWatchingSection(
                            items = continueWatchingItems,
                            style = continueWatchingPreferences.style,
                            useEpisodeThumbnails = continueWatchingPreferences.useEpisodeThumbnails,
                            blurNextUp = continueWatchingPreferences.blurNextUp,
                            sectionPadding = homeSectionPadding,
                            layout = continueWatchingLayout,
                            focusedItemIndex = tvFocus.itemIndex,
                            rowState = remember {
                                HomeScrollMemory.immersiveRowStates.getOrPut("continue_watching") { LazyListState() }
                            },
                            onHoverItem = { itemIndex ->
                                syncImmersiveTvFocusSection()
                                tvFocus.itemIndex = itemIndex
                            },
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
                                    onHoverItem = { itemIndex ->
                                        syncImmersiveTvFocusSection()
                                        tvFocus.itemIndex = itemIndex
                                    },
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
                                androidx.compose.runtime.key(section.key) {
                                    HomeCatalogRowSection(
                                        section = section,
                                        entries = if (isShowingHomeContent && !section.paginates) {
                                            section.items.take(HOME_CATALOG_PREVIEW_LIMIT)
                                        } else {
                                            section.items
                                        },
                                        sectionPadding = homeSectionPadding,
                                        basePosterWidthDpOverride = immersivePosterBaseWidthDp,
                                        focusedItemIndex = tvFocus.itemIndex,
                                        rowState = remember(section.key) {
                                            HomeScrollMemory.immersiveRowStates.getOrPut("catalog:${section.key}") { LazyListState() }
                                        },
                                        isKeyboardNavigation = !mouseActivity.isMouseActive,
                                        onHoverItem = { itemIndex ->
                                            syncImmersiveTvFocusSection()
                                            tvFocus.itemIndex = itemIndex
                                        },
                                        onLoadMore = if (section.paginates) {
                                            { HomeRepository.loadMoreCatalogRow(section.key) }
                                        } else {
                                            null
                                        },
                                        isLoadingMore = section.isLoadingMore,
                                        watchedKeys = watchedUiState.watchedKeys,
                                        onPosterClick = posterClickHandler,
                                        onPosterLongClick = onPosterLongClick,
                                        onViewAllClick = if (
                                            !section.paginates &&
                                            section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)
                                        ) {
                                            onCatalogClick?.let { { it(section) } }
                                        } else {
                                            null
                                        },
                                        modifier = Modifier.padding(bottom = 12.dp),
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
                        enabled = isDesktop && homeSettingsUiState.smoothScrollingEnabled,
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
            ) {
                if (showHeroSlot) {
                    item { renderHero(currentListState) }
                }
                rowsContent()
            }
        }
    }
}

private const val HOME_CATALOG_PREVIEW_LIMIT = 18
private const val SEARCH_LIBRARY_METADATA_PREFETCH_LIMIT = 3
// How many results per catalog the search hero eagerly enriches (incl. rate-limited MDBList). Kept
// small on purpose: search results fan out across catalogs and users care about the start of each.
private const val SEARCH_HERO_METADATA_PREFETCH_PER_CATALOG = 2
private const val HOME_STARTUP_METADATA_GRACE_MS = 900L
// COLLECTION_HERO_TYPE now lives in HomeModels.kt, shared with HomeRepository's own
// collection-backdrop hero items.
private const val IMMERSIVE_SHELF_TOP_PADDING_DP = 68f
private const val IMMERSIVE_SHELF_BOTTOM_PADDING_DP = 12f
private const val IMMERSIVE_SHELF_HEADER_ESTIMATE_DP = 54f
private const val IMMERSIVE_POSTER_LABEL_RESERVE_DP = 42f
private const val IMMERSIVE_POSTER_ASPECT_RATIO = 0.675f
private const val IMMERSIVE_POSTER_MIN_BASE_WIDTH_DP = 104
private const val IMMERSIVE_POSTER_MAX_BASE_WIDTH_DP = 210
private const val IMMERSIVE_POSTER_ITEM_SPACING_DP = 10f
private const val IMMERSIVE_POSTER_MIN_VISIBLE_WIDE = 8
private const val IMMERSIVE_POSTER_MIN_VISIBLE_NARROW = 7
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
): Int {
    val labelReserve = if (hideLabels) 0f else IMMERSIVE_POSTER_LABEL_RESERVE_DP
    val availablePosterHeight = shelfHeightDp -
        IMMERSIVE_SHELF_TOP_PADDING_DP -
        IMMERSIVE_SHELF_BOTTOM_PADDING_DP -
        IMMERSIVE_SHELF_HEADER_ESTIMATE_DP -
        labelReserve
    val heightDrivenWidth = (availablePosterHeight * IMMERSIVE_POSTER_ASPECT_RATIO).roundToInt()

    val rowWidth = maxWidthDp - (sectionPaddingDp * 2f)
    val minVisibleItems = if (maxWidthDp >= 1800f) {
        IMMERSIVE_POSTER_MIN_VISIBLE_WIDE
    } else {
        IMMERSIVE_POSTER_MIN_VISIBLE_NARROW
    }
    val widthDrivenMax = (
        (rowWidth - IMMERSIVE_POSTER_ITEM_SPACING_DP * (minVisibleItems - 1)) / minVisibleItems
        ).roundToInt()

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

/**
 * Strips the addon catalog's art and metadata from a Search/Library hero item so the hero shows
 * nothing but the (correct) title until TMDB/TVDB enrichment resolves. Used only when the hero
 * image source isn't Addon — otherwise the catalog's poster/backdrop/text flash for ~1s and then
 * get replaced, which reads as a metadata race. Identity (id/type/name) is preserved so
 * enrichment can still fetch by it; the grid is unaffected (it reads effectiveSections).
 */
private fun MetaPreview.asPendingHeroPreview(): MetaPreview = copy(
    poster = null,
    posterFallback = null,
    banner = null,
    logo = null,
    description = null,
    releaseInfo = null,
    genres = emptyList(),
)

private fun MetaPreview.homeHeroFallbackImdbId(): String? =
    id.split("_").firstOrNull { segment -> segment.startsWith("tt", ignoreCase = true) }

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
