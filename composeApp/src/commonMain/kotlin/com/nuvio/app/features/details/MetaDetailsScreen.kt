package com.nuvio.app.features.details

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.nuvio.app.core.ui.navigationKey
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.TrailerPlaybackMode
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.LocalNuvioDesktopCompactWindow
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.TraktListPickerDialog
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.rememberMouseActivityState
import com.nuvio.app.features.details.components.DetailActionButtons
import com.nuvio.app.features.details.components.DetailSecondaryAction
import com.nuvio.app.features.librarypvr.AddToLibraryDialog
import com.nuvio.app.features.librarypvr.LibraryPvrRepository
import com.nuvio.app.features.librarypvr.MonitorTarget
import com.nuvio.app.features.details.components.CommentDetailSheet
import com.nuvio.app.features.details.components.DetailAdditionalInfoSection
import com.nuvio.app.features.details.components.DetailCastSection
import com.nuvio.app.features.details.components.DetailCommentsSection
import com.nuvio.app.features.details.components.DetailCompactMediaSelector
import com.nuvio.app.features.details.components.DetailFloatingHeader
import com.nuvio.app.features.details.components.DetailHero
import com.nuvio.app.features.details.components.DetailHeroPeoplePanelToggleTrigger
import com.nuvio.app.features.details.components.DetailMetaInfo
import com.nuvio.app.features.details.components.DetailPosterRailSection
import com.nuvio.app.features.details.components.DetailProductionSection
import com.nuvio.app.features.details.components.DetailSeriesContent
import com.nuvio.app.features.details.components.DetailTrailersSection
import com.nuvio.app.features.details.components.DetailTvKey
import com.nuvio.app.features.details.components.DetailTvKeyboardBridge
import com.nuvio.app.features.details.components.EpisodeWatchedActionSheet
import com.nuvio.app.features.details.components.MetaDetailsTvFocusState
import com.nuvio.app.features.details.components.SeasonWatchedActionSheet
import com.nuvio.app.features.details.components.TrailerPlayerPopup
import com.nuvio.app.features.details.components.TrackingRatingDialog
import com.nuvio.app.features.details.components.rememberMetaDetailsTvFocusState
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.HeroDiscoveryFact
import com.nuvio.app.features.home.HeroDiscoveryMetadataService
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.isDesktop
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.locallibrary.LocalLibraryPlaybackPreference
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.player.AnimeContentCache
import com.nuvio.app.features.player.OriginalLanguageCache
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.appShortcutMatches
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktCommentReview
import com.nuvio.app.features.trakt.TraktCommentsRepository
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktListTab
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.trailer.TrailerResolution
import com.nuvio.app.features.trailer.TrailerUnavailableReason
import com.nuvio.app.features.trailer.sourceOrNull
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.previousReleasedEpisodesBefore
import com.nuvio.app.features.watched.releasedPlayableEpisodes
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.tracking.trackingRatingProviderDisplayName
import com.nuvio.app.features.tracking.trackingRatingProviderFor
import com.nuvio.app.features.tracking.trackingProvider
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.kmpalette.rememberDominantColorState
import com.kmpalette.rememberPainterDominantColorState
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

private fun dominantBackdropBlendColor(dominantColor: Color, backgroundColor: Color): Color =
    backgroundColor.blendTowards(dominantColor, fraction = 0.42f)

private fun Color.blendTowards(target: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (target.red - red) * clamped,
        green = green + (target.green - green) * clamped,
        blue = blue + (target.blue - blue) * clamped,
        alpha = alpha + (target.alpha - alpha) * clamped,
    )
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
fun MetaDetailsScreen(
    type: String,
    id: String,
    onBack: () -> Unit,
    onPlay: ((type: String, videoId: String, parentMetaId: String, parentMetaType: String, title: String, logo: String?, poster: String?, background: String?, seasonNumber: Int?, episodeNumber: Int?, episodeTitle: String?, episodeThumbnail: String?, pauseDescription: String?, resumePositionMs: Long?) -> Unit)? = null,
    onPlayAlternate: ((type: String, videoId: String, parentMetaId: String, parentMetaType: String, title: String, logo: String?, poster: String?, background: String?, seasonNumber: Int?, episodeNumber: Int?, episodeTitle: String?, episodeThumbnail: String?, pauseDescription: String?, resumePositionMs: Long?) -> Unit)? = null,
    onPlayRandomEpisode: ((type: String, videoId: String, parentMetaId: String, parentMetaType: String, title: String, logo: String?, poster: String?, background: String?, seasonNumber: Int?, episodeNumber: Int?, episodeTitle: String?, episodeThumbnail: String?, pauseDescription: String?) -> Unit)? = null,
    onOpenMeta: ((MetaPreview) -> Unit)? = null,
    onPlayTrailer: ((PlayerLaunch) -> Unit)? = null,
    onCastClick: ((MetaPerson, String?) -> Unit)? = null,
    onCompanyClick: ((MetaCompany, String) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val displayedMeta = uiState.meta?.takeIf { it.type == type && it.id == id }
        ?: MetaDetailsRepository.peek(type, id)
    val metaScreenSettingsUiState by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val homeSettingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val traktAuthUiState by remember {
        TraktAuthRepository.ensureLoaded()
        TraktAuthRepository.uiState
    }.collectAsStateWithLifecycle()
    val traktSettingsUiState by remember {
        TraktSettingsRepository.ensureLoaded()
        TraktSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val tmdbSettingsUiState by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val libraryUiState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val connectedTrackingProviders by TrackingProviderRegistry.connectedProviderIds.collectAsStateWithLifecycle()
    val localLibraryUiState by remember {
        LocalLibraryRepository.ensureLoaded()
        LocalLibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val downloadsUiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    var autoLoadAttempted by remember(type, id) { mutableStateOf(false) }
    var observedOfflineState by remember(type, id) { mutableStateOf(false) }
    var selectedEpisodeForActions by remember(type, id) { mutableStateOf<MetaVideo?>(null) }
    var selectedSeasonForActions by remember(type, id) { mutableStateOf<Int?>(null) }
    val commentsEnabled by remember {
        TraktCommentsSettings.ensureLoaded()
        TraktCommentsSettings.enabled
    }.collectAsStateWithLifecycle()
    var comments by remember(type, id) { mutableStateOf<List<TraktCommentReview>>(emptyList()) }
    var commentsCurrentPage by remember(type, id) { mutableIntStateOf(0) }
    var commentsPageCount by remember(type, id) { mutableIntStateOf(0) }
    var isCommentsLoading by remember(type, id) { mutableStateOf(false) }
    var isCommentsLoadingMore by remember(type, id) { mutableStateOf(false) }
    var commentsError by remember(type, id) { mutableStateOf<String?>(null) }
    var selectedComment by remember(type, id) { mutableStateOf<TraktCommentReview?>(null) }
    val detailsScope = rememberCoroutineScope()
    var showLibraryListPicker by remember(type, id) { mutableStateOf(false) }
    var showRatingDialog by remember(type, id) { mutableStateOf(false) }
    var ratingPending by remember(type, id) { mutableStateOf(false) }
    var ratingError by remember(type, id) { mutableStateOf<String?>(null) }
    var pickerTabs by remember(type, id) { mutableStateOf<List<TraktListTab>>(emptyList()) }
    var pickerMembership by remember(type, id) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var pickerPending by remember(type, id) { mutableStateOf(false) }
    var pickerError by remember(type, id) { mutableStateOf<String?>(null) }
    var episodeImdbRatings by remember(type, id) { mutableStateOf<Map<Pair<Int, Int>, Double>>(emptyMap()) }
    var deferredMetaWorkAllowed by remember(type, id) { mutableStateOf(false) }
    var heroDiscoveryFacts by remember(type, id) { mutableStateOf<List<HeroDiscoveryFact>>(emptyList()) }

    val shouldShowComments = commentsEnabled &&
        traktAuthUiState.mode == TraktConnectionMode.CONNECTED &&
        displayedMeta != null &&
        displayedMeta.type.lowercase().let { it == "movie" || it == "series" || it == "show" || it == "tv" }

    LaunchedEffect(displayedMeta?.id) {
        deferredMetaWorkAllowed = false
        if (displayedMeta != null) {
            delay(250)
            deferredMetaWorkAllowed = true
        }
    }

    LaunchedEffect(
        displayedMeta?.id,
        deferredMetaWorkAllowed,
        metaScreenSettingsUiState.discoveryBadgesEnabled,
        homeSettingsUiState.heroInfoLines,
        homeSettingsUiState.heroInfoPriority,
        homeSettingsUiState.heroReleaseStatusUnavailableOnly,
        HeroDiscoveryMetadataService.CACHE_VERSION,
    ) {
        heroDiscoveryFacts = emptyList()
        val meta = displayedMeta ?: return@LaunchedEffect
        if (!deferredMetaWorkAllowed ||
            !metaScreenSettingsUiState.discoveryBadgesEnabled ||
            homeSettingsUiState.heroInfoLines <= 0
        ) {
            return@LaunchedEffect
        }
        heroDiscoveryFacts = HeroDiscoveryMetadataService.fetch(
            type = meta.type,
            id = meta.id,
            priority = HeroDiscoveryMetadataService.normalizePriority(
                homeSettingsUiState.heroInfoPriority,
            ),
            releaseStatusUnavailableOnly = homeSettingsUiState.heroReleaseStatusUnavailableOnly,
        )
    }

    LaunchedEffect(displayedMeta?.id, shouldShowComments, deferredMetaWorkAllowed) {
        if (displayedMeta == null || !shouldShowComments) {
            comments = emptyList()
            commentsCurrentPage = 0
            commentsPageCount = 0
            commentsError = null
            return@LaunchedEffect
        }
        if (!deferredMetaWorkAllowed) return@LaunchedEffect
        isCommentsLoading = true
        commentsError = null
        try {
            val result = TraktCommentsRepository.getCommentsPage(displayedMeta, page = 1)
            comments = result.items
            commentsCurrentPage = result.currentPage
            commentsPageCount = result.pageCount
        } catch (e: Exception) {
            commentsError = e.message ?: getString(Res.string.details_comments_load_failed)
        }
        isCommentsLoading = false
    }

    LaunchedEffect(
        displayedMeta?.id,
        displayedMeta?.videos,
        deferredMetaWorkAllowed,
        metaScreenSettingsUiState.episodeRatingsEnabled,
    ) {
        val metaForRatings = displayedMeta
        if (!metaScreenSettingsUiState.episodeRatingsEnabled) {
            episodeImdbRatings = emptyMap()
            return@LaunchedEffect
        }
        if (!deferredMetaWorkAllowed) return@LaunchedEffect
        if (metaForRatings == null || !metaForRatings.isSeriesLikeForEpisodeRatings()) {
            episodeImdbRatings = emptyMap()
            return@LaunchedEffect
        }

        val imdbId = extractImdbId(metaForRatings.id) ?: extractImdbId(id)
        val tmdbId = extractTmdbId(metaForRatings.id)
            ?: extractTmdbId(id)
            ?: TmdbService.ensureTmdbId(metaForRatings.id, metaForRatings.type)?.toIntOrNull()
            ?: TmdbService.ensureTmdbId(id, type)?.toIntOrNull()

        if (imdbId == null && tmdbId == null) {
            episodeImdbRatings = emptyMap()
            return@LaunchedEffect
        }

        episodeImdbRatings = ImdbEpisodeRatingsRepository.getEpisodeRatings(
            imdbId = imdbId,
            tmdbId = tmdbId,
        )
    }

    LaunchedEffect(type, id, displayedMeta, uiState.isLoading, autoLoadAttempted) {
        if (!autoLoadAttempted && displayedMeta == null && !uiState.isLoading) {
            autoLoadAttempted = true
            MetaDetailsRepository.load(type, id)
        }
    }

    LaunchedEffect(
        type,
        id,
        displayedMeta?.id,
        uiState.isLoading,
        traktSettingsUiState.moreLikeThisSource,
        traktAuthUiState.mode,
        tmdbSettingsUiState.enabled,
        tmdbSettingsUiState.useMoreLikeThis,
        tmdbSettingsUiState.language,
    ) {
        if (displayedMeta != null && !uiState.isLoading) {
            MetaDetailsRepository.load(type, id)
        }
    }

    LaunchedEffect(networkStatusUiState.condition, displayedMeta, uiState.isLoading, type, id) {
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> {
                observedOfflineState = true
            }

            NetworkCondition.Online -> {
                if (!observedOfflineState) return@LaunchedEffect
                observedOfflineState = false
                if (displayedMeta == null && !uiState.isLoading) {
                    MetaDetailsRepository.load(type, id)
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            displayedMeta == null && uiState.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            displayedMeta == null && uiState.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.details_failed_to_load),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = when (networkStatusUiState.condition) {
                            NetworkCondition.NoInternet -> stringResource(Res.string.details_check_connection)
                            NetworkCondition.ServersUnreachable -> stringResource(Res.string.details_servers_unreachable)
                            else -> uiState.errorMessage.orEmpty()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            NetworkStatusRepository.requestRefresh(force = true)
                            MetaDetailsRepository.load(type, id)
                        },
                    ) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }

            displayedMeta != null -> {
                val meta = displayedMeta
                val metaPreview = remember(meta) { meta.toMetaPreview() }
                val todayIsoDate = CurrentDateProvider.todayIsoDate()
                fun hasLocalPlayback(
                    videoId: String,
                    seasonNumber: Int?,
                    episodeNumber: Int?,
                ): Boolean {
                    if (!AppFeaturePolicy.downloadsEnabled) return false
                    val playableDownload = DownloadsRepository.findPlayableDownload(
                        parentMetaId = meta.id,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        videoId = videoId,
                    )?.let(DownloadsRepository::playableLocalFileUri)
                    return !playableDownload.isNullOrBlank() ||
                        LocalLibraryRepository.localStreamsFor(meta.id, videoId).isNotEmpty()
                }
                val isSaved = remember(
                    libraryUiState.items,
                    libraryUiState.sections,
                    libraryUiState.sourceMode,
                    meta.id,
                    meta.type,
                ) {
                    LibraryRepository.isSaved(meta.id, meta.type)
                }
                val isWatched = remember(watchedUiState.watchedKeys, metaPreview) {
                    WatchingState.isPosterWatched(
                        watchedKeys = watchedUiState.watchedKeys,
                        item = metaPreview,
                    )
                }
                val openLibraryListPicker = remember(meta) {
                    {
                        val libraryItem = meta.toLibraryItem(savedAtEpochMs = 0L)
                        pickerTabs = LibraryRepository.libraryListTabs()
                        pickerMembership = pickerTabs.associate { it.key to false }
                        pickerPending = true
                        pickerError = null
                        showLibraryListPicker = true
                        detailsScope.launch {
                            runCatching {
                                val snapshot = LibraryRepository.getMembershipSnapshot(libraryItem)
                                val tabs = LibraryRepository.libraryListTabs()
                                pickerTabs = tabs
                                pickerMembership = tabs.associate { tab ->
                                    tab.key to (snapshot[tab.key] == true)
                                }
                            }.onFailure { error ->
                                pickerError = error.message ?: getString(Res.string.trakt_lists_load_failed)
                            }
                            pickerPending = false
                        }
                        Unit
                    }
                }
                val toggleSaved = remember(meta) {
                    {
                        LibraryRepository.toggleSaved(meta.toLibraryItem(savedAtEpochMs = 0L))
                    }
                }
                val toggleWatched = remember(metaPreview) {
                    {
                        detailsScope.launch {
                            WatchingActions.togglePosterWatched(metaPreview)
                        }
                        Unit
                    }
                }
                val ratingProviderId = remember(libraryUiState.sourceMode) {
                    trackingRatingProviderFor(libraryUiState.sourceMode)
                }
                val ratingWriter = ratingProviderId?.let(TrackingProviderRegistry::ratingWriter)
                val canRate = ratingProviderId != null &&
                    ratingWriter != null &&
                    ratingProviderId in connectedTrackingProviders
                val ratingProviderName = trackingRatingProviderDisplayName(ratingProviderId)
                val activeLibraryName = when (libraryUiState.sourceMode) {
                    LibrarySourceMode.LOCAL -> stringResource(Res.string.settings_library_source_nuvio)
                    LibrarySourceMode.TRAKT -> stringResource(Res.string.settings_library_source_trakt)
                    LibrarySourceMode.SIMKL -> stringResource(Res.string.settings_library_source_simkl)
                    LibrarySourceMode.MDBLIST -> stringResource(Res.string.settings_library_source_mdblist)
                    LibrarySourceMode.YAMTRACK -> stringResource(Res.string.settings_library_source_floppy)
                }
                val ratingMedia = remember(meta) {
                    buildTrackingMediaReference(
                        contentType = meta.type,
                        parentMetaId = meta.id,
                        title = meta.name,
                        releaseInfo = meta.releaseInfo,
                    )
                }
                val openRatingDialog: (() -> Unit)? = if (canRate) {
                    {
                        ratingError = null
                        showRatingDialog = true
                    }
                } else {
                    null
                }
                val submitRating: (Int?) -> Unit = { rating ->
                    val writer = ratingWriter
                    if (writer != null) {
                        detailsScope.launch {
                            ratingPending = true
                            ratingError = null
                            runCatching {
                                writer.setRating(
                                    profileId = ProfileRepository.activeProfileId,
                                    media = ratingMedia,
                                    rating = rating,
                                )
                            }.onSuccess {
                                showRatingDialog = false
                                NuvioToastController.show(
                                    getString(Res.string.details_rating_saved, ratingProviderName),
                                )
                            }.onFailure { error ->
                                ratingError = error.message ?: getString(Res.string.details_rating_failed)
                            }
                            ratingPending = false
                        }
                    }
                }
                val libraryPvrUiState by LibraryPvrRepository.uiState.collectAsStateWithLifecycle()
                var showMonitorDialog by remember(meta.id) { mutableStateOf(false) }
                val isMonitored = remember(libraryPvrUiState.monitoredItems, meta.id) {
                    libraryPvrUiState.isMonitored(meta.id)
                }
                val monitorTarget = remember(meta.id, meta.type, meta.name) { meta.toMonitorTarget() }
                val canMonitor = AppFeaturePolicy.downloadsEnabled &&
                    meta.type.lowercase() in setOf("series", "movie", "anime", "tv", "show", "tvshow")
                val onMonitorClick: (() -> Unit)? = if (canMonitor) {
                    {
                        if (isMonitored) {
                            LibraryPvrRepository.removeMonitoredForContent(meta.id)
                        } else {
                            showMonitorDialog = true
                        }
                    }
                } else {
                    null
                }
                val progressByVideoId = remember(watchProgressUiState.entries) {
                    watchProgressUiState.byVideoId
                }
                LaunchedEffect(meta.id, meta.type, watchProgressUiState.hasLoadedRemoteProgress) {
                    if (meta.type.lowercase() in setOf("series", "show", "tv", "tvshow")) {
                        WatchProgressRepository.refreshEpisodeProgress(meta.id)
                    }
                }
                // Record genre-based anime detection so the desktop player can auto-apply the
                // anime enhancement preset for this title (see AnimeContentCache).
                LaunchedEffect(meta.id, meta.genres) {
                    AnimeContentCache.record(meta.id, meta.genres)
                }
                // The title's own language, for the "Original" audio/subtitle preference. Recorded
                // here because this is where a full (TMDB-backed) meta is already in hand — the
                // player's own fetch is a fallback, not the primary source.
                LaunchedEffect(meta.id, meta.language) {
                    OriginalLanguageCache.record(meta.id, meta.language)
                }
                LaunchedEffect(
                    meta.id,
                    meta.type,
                    todayIsoDate,
                    watchedUiState.isLoaded,
                    watchProgressUiState.hasLoadedRemoteProgress,
                    watchedUiState.watchedKeys,
                    watchProgressUiState.entries,
                ) {
                    if (watchedUiState.isLoaded && watchProgressUiState.hasLoadedRemoteProgress) {
                        WatchingActions.reconcileSeriesWatchedState(
                            meta = meta,
                            todayIsoDate = todayIsoDate,
                        )
                    }
                }
                val movieProgress = progressByVideoId[meta.id]
                    ?.takeUnless { it.isCompleted }
                val cwPrefs by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
                val seriesAction = remember(watchProgressUiState.entries, watchedUiState.items, meta, todayIsoDate, cwPrefs.upNextFromFurthestEpisode) {
                    meta.seriesPrimaryAction(
                        entries = watchProgressUiState.entries,
                        watchedItems = watchedUiState.items,
                        todayIsoDate = todayIsoDate,
                        preferFurthestEpisode = cwPrefs.upNextFromFurthestEpisode,
                    )
                }
                val seriesActionVideo = remember(seriesAction, meta.id, meta.videos) {
                    meta.resolveSeriesActionVideo(seriesAction)
                }
                val preferredSeriesEpisode = remember(
                    seriesAction,
                    watchProgressUiState.entries,
                    meta.id,
                    meta.videos,
                ) {
                    meta.preferredSeriesEpisode(seriesAction, watchProgressUiState.entries)
                }
                val preferredSeriesSeasonNumber = preferredSeriesEpisode?.effectiveSeasonNumber()
                val preferredSeriesEpisodeNumber = preferredSeriesEpisode?.effectiveEpisodeNumber()
                val seriesPauseDescription = remember(seriesActionVideo) {
                    seriesActionVideo?.overview
                }
                val seriesStreamVideoId = remember(meta.id, seriesAction, seriesActionVideo) {
                    val action = seriesAction ?: return@remember null
                    val video = seriesActionVideo ?: return@remember action.videoId
                    val playbackVideoId = buildPlaybackVideoId(
                        parentMetaId = meta.id,
                        seasonNumber = video.effectiveSeasonNumber(),
                        episodeNumber = video.effectiveEpisodeNumber(),
                        fallbackVideoId = video.id,
                    )
                    video.streamVideoIdForPlayback(meta.id, playbackVideoId)
                }
                val hasEpisodes = meta.videos.any {
                    it.effectiveSeasonNumber() != null || it.effectiveEpisodeNumber() != null
                }
                val hasProductionSection = remember(meta) {
                    meta.productionCompanies.isNotEmpty() ||
                        meta.networks.isNotEmpty() ||
                        meta.creator.isNotEmpty() ||
                        meta.director.isNotEmpty() ||
                        meta.writer.isNotEmpty() ||
                        meta.producer.isNotEmpty()
                }
                val hasAdditionalInfoSection = remember(meta) {
                    meta.status != null ||
                        meta.releaseInfo != null ||
                        meta.runtime != null ||
                        meta.ageRating != null ||
                        meta.country != null ||
                        meta.language != null
                }
                val hasCollectionSection = remember(meta) {
                    meta.collectionName != null && meta.collectionItems.isNotEmpty()
                }
                val hasMoreLikeThisSection = remember(meta) {
                    meta.moreLikeThis.isNotEmpty()
                }
                val hasTrailersSection = remember(meta) {
                    meta.trailers.isNotEmpty()
                }
                val uriHandler = LocalUriHandler.current
                val inAppTrailerPlaybackEnabled = AppFeaturePolicy.trailerPlaybackMode == TrailerPlaybackMode.IN_APP
                val trailerScope = rememberCoroutineScope()
                var selectedTrailer by remember(meta.id) { mutableStateOf<MetaTrailer?>(null) }
                var trailerPlaybackSource by remember(meta.id) { mutableStateOf<TrailerPlaybackSource?>(null) }
                var trailerLoading by remember(meta.id) { mutableStateOf(false) }
                var trailerErrorMessage by remember(meta.id) { mutableStateOf<String?>(null) }
                // False when the failure is a definitive YouTube verdict (region block, age
                // gate, removed) that a retry can never change; the popup then hides Retry.
                var trailerErrorRetryable by remember(meta.id) { mutableStateOf(true) }
                var trailerRequestToken by remember(meta.id) { mutableIntStateOf(0) }
                var isLeavingDetails by remember(meta.id) { mutableStateOf(false) }
                val heroTrailerCandidate = remember(meta.trailers) {
                    selectHeroTrailer(meta.trailers)
                }
                val heroTrailerPlaybackEnabled = AppFeaturePolicy.heroTrailerPlaybackSupported &&
                    inAppTrailerPlaybackEnabled &&
                    metaScreenSettingsUiState.heroTrailerPlayback
                // "Manual" delay: the hero trailer never auto-plays (no auto candidate mounts),
                // but a trailer click still plays it in the hero.
                val heroTrailerManualOnly = metaScreenSettingsUiState.heroTrailerDelaySeconds <= 0
                var heroTrailerPlaybackSource by remember(meta.id, heroTrailerCandidate?.id) { mutableStateOf<TrailerPlaybackSource?>(null) }
                var heroTrailerReady by remember(meta.id, heroTrailerCandidate?.id) { mutableStateOf(false) }
                var heroTrailerFinished by remember(meta.id, heroTrailerCandidate?.id) { mutableStateOf(false) }
                var heroTrailerAutoplayReady by remember(meta.id, heroTrailerCandidate?.id) { mutableStateOf(false) }
                // True when the current hero trailer source was picked by an explicit
                // trailer click (rather than the auto-selected hero candidate). Such
                // playback starts immediately (no autoplay delay) and with audio on.
                var heroTrailerManualPlayback by remember(meta.id, heroTrailerCandidate?.id) { mutableStateOf(false) }
                // True after the user dismisses (or the trailer ends): the player surface
                // stays mounted but paused and hidden, so a later trailer click can re-attach
                // in place (mpv loadfile) instead of rebuilding the native surface — a rebuild
                // comes up on a permanent black frame.
                var heroTrailerDismissed by remember(meta.id, heroTrailerCandidate?.id) { mutableStateOf(false) }
                val heroTrailerMuted by HeroTrailerAudioState.muted.collectAsStateWithLifecycle()
                val heroTrailerVolume by HeroTrailerAudioState.volume.collectAsStateWithLifecycle()
                LaunchedEffect(
                    heroTrailerPlaybackEnabled,
                    heroTrailerManualOnly,
                    heroTrailerCandidate?.id,
                    heroTrailerCandidate?.key,
                    deferredMetaWorkAllowed,
                ) {
                    heroTrailerPlaybackSource = null
                    heroTrailerReady = false
                    heroTrailerFinished = false
                    heroTrailerAutoplayReady = false
                    heroTrailerManualPlayback = false
                    heroTrailerDismissed = false
                    // Seed this new trailer session's starting mute state from the "play with
                    // sound by default" setting (mirrors the home page's own toggle). The user
                    // can still interactively mute/adjust volume via the slider afterward.
                    HeroTrailerAudioState.setMuted(!metaScreenSettingsUiState.heroTrailerSoundEnabled)
                    // In Manual mode we don't resolve/mount the auto candidate — a trailer click
                    // resolves its own source and mounts the surface fresh.
                    if (!deferredMetaWorkAllowed || !heroTrailerPlaybackEnabled ||
                        heroTrailerCandidate == null || heroTrailerManualOnly
                    ) {
                        return@LaunchedEffect
                    }
                    val resolvedSource = runCatching {
                        TrailerPlaybackResolver.resolveFromYouTubeUrl(heroTrailerCandidate.youtubePlaybackUrl())
                    }.getOrNull()?.sourceOrNull()
                    if (resolvedSource == null) {
                        heroTrailerFinished = true
                    } else {
                        heroTrailerPlaybackSource = resolvedSource
                    }
                }
                val onBackFromDetails: () -> Unit = {
                    isLeavingDetails = true
                    heroTrailerReady = false
                    heroTrailerFinished = true
                    onBack()
                }
                // When hero trailer playback is on, an explicit trailer click plays that
                // trailer through the same lightweight in-place autoplay surface the hero
                // candidate uses, instead of handing off to the full MPV player. This applies
                // in both Hero and Fullscreen playback-area modes — DetailHero already renders
                // that surface bounded to the hero region or stretched to fill the screen based
                // on the area setting, so this only decides *how* playback starts (fast, no
                // full player-engine bootstrap), not the resulting size. Trailers are meant to
                // be quick in-and-out and are already low quality, so the full player's extra
                // capabilities buy nothing here.
                val heroTrailerClickPlaybackEnabled = isDesktop && heroTrailerPlaybackEnabled
                val currentHeroTrailerClickEnabled = rememberUpdatedState(heroTrailerClickPlaybackEnabled)
                val playTrailerInHero = rememberUpdatedState<(MetaTrailer) -> Unit> { trailer ->
                    val youtubeUrl = trailer.youtubePlaybackUrl()
                    // Cancel any pending trailer resolution, then resolve the clicked
                    // trailer and start it immediately in the hero. We deliberately do
                    // NOT null out heroTrailerPlaybackSource first: assigning the new
                    // source directly lets the hero player surface re-attach in place
                    // (its attach is keyed on the source URL). Tearing the surface down
                    // and rebuilding it leaves the native player on a black frame.
                    trailerRequestToken += 1
                    val currentRequestToken = trailerRequestToken
                    selectedTrailer = null
                    trailerPlaybackSource = null
                    trailerErrorMessage = null
                    trailerLoading = false
                    // Hide + pause the current trailer (show the backdrop) while we resolve,
                    // so the previously-loaded source doesn't resume for a beat and cause a
                    // double-attach flash. The surface stays mounted, so once the new source is
                    // set it re-attaches in place (attach is keyed on the source URL) and the
                    // backdrop covers the buffering gap until the new frame is on screen.
                    heroTrailerReady = false
                    heroTrailerAutoplayReady = false
                    heroTrailerDismissed = true
                    heroTrailerFinished = false
                    heroTrailerManualPlayback = true
                    HeroTrailerAudioState.setMuted(false)
                    trailerScope.launch {
                        val resolution = runCatching {
                            TrailerPlaybackResolver.resolveFromYouTubeUrl(youtubeUrl)
                        }.getOrNull()
                        if (currentRequestToken != trailerRequestToken) {
                            return@launch
                        }
                        val resolvedSource = resolution?.sourceOrNull()
                        if (resolvedSource != null) {
                            heroTrailerFinished = false
                            heroTrailerPlaybackSource = resolvedSource
                            heroTrailerDismissed = false
                        } else {
                            // Resolution failed — fall back to surfacing the error.
                            heroTrailerManualPlayback = false
                            heroTrailerFinished = true
                            selectedTrailer = trailer
                            val reason = (resolution as? TrailerResolution.Unavailable)?.reason
                                ?: TrailerUnavailableReason.UNKNOWN
                            trailerErrorRetryable = reason.isRetryable()
                            trailerErrorMessage = trailerUnavailableMessage(reason)
                        }
                    }
                }
                val resolveTrailer: (MetaTrailer) -> Unit = remember(meta.id, inAppTrailerPlaybackEnabled, uriHandler) {
                    { trailer ->
                        val youtubeUrl = trailer.youtubePlaybackUrl()
                        if (!inAppTrailerPlaybackEnabled) {
                            runCatching { uriHandler.openUri(youtubeUrl) }
                        } else if (currentHeroTrailerClickEnabled.value) {
                            playTrailerInHero.value(trailer)
                        } else {
                            selectedTrailer = trailer
                            trailerPlaybackSource = null
                            trailerErrorMessage = null
                            trailerErrorRetryable = true
                            trailerLoading = true
                            trailerRequestToken += 1
                            val currentRequestToken = trailerRequestToken
                            trailerScope.launch {
                                val resolution = runCatching {
                                    TrailerPlaybackResolver.resolveFromYouTubeUrl(youtubeUrl)
                                }.getOrNull()
                                if (currentRequestToken != trailerRequestToken) {
                                    return@launch
                                }
                                val resolvedSource = resolution?.sourceOrNull()
                                if (resolvedSource != null && isDesktop && onPlayTrailer != null) {
                                    // Desktop: hand the resolved trailer to the full-screen
                                    // player instead of the embedded popup (which can't be
                                    // resized and is capped to a small surface). Closing the
                                    // player pops back to this details screen.
                                    val trailerLabel = trailer.displayName ?: trailer.name
                                    onPlayTrailer.invoke(
                                        PlayerLaunch(
                                            title = trailerLabel,
                                            sourceUrl = resolvedSource.videoUrl,
                                            sourceAudioUrl = resolvedSource.audioUrl,
                                            streamTitle = trailerLabel,
                                            streamSubtitle = meta.name,
                                            providerName = "YouTube",
                                            logo = meta.logo,
                                            poster = meta.poster,
                                            background = meta.background,
                                            contentType = meta.type,
                                            parentMetaId = meta.id,
                                            parentMetaType = meta.type,
                                            disableProgressTracking = true,
                                        ),
                                    )
                                    trailerLoading = false
                                    trailerPlaybackSource = null
                                    trailerErrorMessage = null
                                    selectedTrailer = null
                                } else {
                                    trailerPlaybackSource = resolvedSource
                                    if (resolvedSource == null) {
                                        val reason = (resolution as? TrailerResolution.Unavailable)?.reason
                                            ?: TrailerUnavailableReason.UNKNOWN
                                        trailerErrorRetryable = reason.isRetryable()
                                        trailerErrorMessage = trailerUnavailableMessage(reason)
                                    } else {
                                        trailerErrorMessage = null
                                    }
                                    trailerLoading = false
                                }
                            }
                        }
                    }
                }
                val playText = stringResource(Res.string.action_play)
                val resumeText = stringResource(Res.string.action_resume)
                val playButtonLabel = remember(movieProgress, seriesAction, meta.type, hasEpisodes, playText, resumeText) {
                    when {
                        (meta.type == "series" || hasEpisodes) && seriesAction != null ->
                            seriesAction.label
                        meta.type != "series" && !hasEpisodes && movieProgress != null ->
                            resumeText
                        else -> playText
                    }
                }
                val dropTrailerForPlaybackNavigation: () -> Unit = {
                    // Native video surfaces can remain visually above Compose content for the
                    // frame in which navigation begins. Remove the trailer source first so the
                    // MPV host is disposed before the streams/player destination is presented.
                    isLeavingDetails = true
                    trailerRequestToken += 1
                    selectedTrailer = null
                    trailerPlaybackSource = null
                    trailerLoading = false
                    trailerErrorMessage = null
                    heroTrailerReady = false
                    heroTrailerAutoplayReady = false
                    heroTrailerManualPlayback = false
                    heroTrailerDismissed = true
                    heroTrailerFinished = true
                    heroTrailerPlaybackSource = null
                }
                val onPrimaryPlayClick: () -> Unit = {
                    if (onPlay != null) {
                        dropTrailerForPlaybackNavigation()
                    }
                    when {
                        (meta.type == "series" || hasEpisodes) && seriesAction != null -> {
                            onPlay?.invoke(
                                meta.type,
                                seriesStreamVideoId ?: seriesAction.videoId,
                                meta.id,
                                meta.type,
                                meta.name,
                                meta.logo,
                                meta.poster,
                                meta.background,
                                seriesAction.seasonNumber,
                                seriesAction.episodeNumber,
                                seriesAction.episodeTitle,
                                seriesAction.episodeThumbnail,
                                seriesPauseDescription,
                                seriesAction.resumePositionMs,
                            )
                        }

                        else -> {
                            onPlay?.invoke(
                                meta.type,
                                meta.id,
                                meta.id,
                                meta.type,
                                meta.name,
                                meta.logo,
                                meta.poster,
                                meta.background,
                                null,
                                null,
                                null,
                                null,
                                meta.description,
                                movieProgress?.lastPositionMs,
                            )
                        }
                    }
                }
                val alternatePlayHandler = onPlayAlternate
                val primaryPlaybackVideoId =
                    if ((meta.type == "series" || hasEpisodes) && seriesAction != null) {
                        seriesStreamVideoId ?: seriesAction.videoId
                    } else {
                        meta.id
                    }
                val primarySeasonNumber =
                    if ((meta.type == "series" || hasEpisodes) && seriesAction != null) {
                        seriesAction.seasonNumber
                    } else {
                        null
                    }
                val primaryEpisodeNumber =
                    if ((meta.type == "series" || hasEpisodes) && seriesAction != null) {
                        seriesAction.episodeNumber
                    } else {
                        null
                    }
                val primaryHasLocalPlayback = remember(
                    meta.id,
                    primaryPlaybackVideoId,
                    primarySeasonNumber,
                    primaryEpisodeNumber,
                    localLibraryUiState.items,
                    downloadsUiState.items,
                ) {
                    hasLocalPlayback(
                        videoId = primaryPlaybackVideoId,
                        seasonNumber = primarySeasonNumber,
                        episodeNumber = primaryEpisodeNumber,
                    )
                }
                val canOfferPrimaryAlternate =
                    localLibraryUiState.playbackPreference.canOfferAlternate(primaryHasLocalPlayback)
                val showManualPlayOption =
                    alternatePlayHandler != null && canOfferPrimaryAlternate
                val onPrimaryPlayLongClick: (() -> Unit)? = alternatePlayHandler
                    ?.takeIf { canOfferPrimaryAlternate }
                    ?.let { alternatePlay ->
                        {
                            dropTrailerForPlaybackNavigation()
                            when {
                                (meta.type == "series" || hasEpisodes) && seriesAction != null -> {
                                    alternatePlay(
                                        meta.type,
                                        seriesStreamVideoId ?: seriesAction.videoId,
                                        meta.id,
                                        meta.type,
                                        meta.name,
                                        meta.logo,
                                        meta.poster,
                                        meta.background,
                                        seriesAction.seasonNumber,
                                        seriesAction.episodeNumber,
                                        seriesAction.episodeTitle,
                                        seriesAction.episodeThumbnail,
                                        seriesPauseDescription,
                                        seriesAction.resumePositionMs,
                                    )
                                }

                                else -> {
                                    alternatePlay(
                                        meta.type,
                                        meta.id,
                                        meta.id,
                                        meta.type,
                                        meta.name,
                                        meta.logo,
                                        meta.poster,
                                        meta.background,
                                        null,
                                        null,
                                        null,
                                        null,
                                        meta.description,
                                        movieProgress?.lastPositionMs,
                                    )
                                }
                            }
                        }
                    }
                val onEpisodePlayClick: (MetaVideo) -> Unit = { video ->
                    if (onPlay != null) {
                        dropTrailerForPlaybackNavigation()
                    }
                    val season = video.effectiveSeasonNumber()
                    val episode = video.effectiveEpisodeNumber()
                    val playbackVideoId = buildPlaybackVideoId(
                        parentMetaId = meta.id,
                        seasonNumber = season,
                        episodeNumber = episode,
                        fallbackVideoId = video.id,
                    )
                    val streamVideoId = video.streamVideoIdForPlayback(meta.id, playbackVideoId)
                    val savedProgress = watchProgressUiState.byVideoId[streamVideoId]
                        ?.takeUnless { it.isCompleted }
                    onPlay?.invoke(
                        meta.type,
                        streamVideoId,
                        meta.id,
                        meta.type,
                        meta.name,
                        meta.logo,
                        meta.poster,
                        meta.background,
                        season,
                        episode,
                        video.title,
                        video.thumbnail,
                        video.overview,
                        savedProgress?.lastPositionMs,
                    )
                }
                val onEpisodeAlternatePlayClick: (MetaVideo) -> Unit = { video ->
                    if (onPlayAlternate != null) {
                        dropTrailerForPlaybackNavigation()
                    }
                    val season = video.effectiveSeasonNumber()
                    val episode = video.effectiveEpisodeNumber()
                    val playbackVideoId = buildPlaybackVideoId(
                        parentMetaId = meta.id,
                        seasonNumber = season,
                        episodeNumber = episode,
                        fallbackVideoId = video.id,
                    )
                    val streamVideoId = video.streamVideoIdForPlayback(meta.id, playbackVideoId)
                    val savedProgress = watchProgressUiState.byVideoId[streamVideoId]
                        ?.takeUnless { it.isCompleted }
                    onPlayAlternate?.invoke(
                        meta.type,
                        streamVideoId,
                        meta.id,
                        meta.type,
                        meta.name,
                        meta.logo,
                        meta.poster,
                        meta.background,
                        season,
                        episode,
                        video.title,
                        video.thumbnail,
                        video.overview,
                        savedProgress?.lastPositionMs,
                    )
                }
                val onRandomEpisodeClick: (() -> Unit)? = onPlayRandomEpisode
                    ?.takeIf { meta.type == "series" || hasEpisodes }
                    ?.let { playRandomEpisode ->
                        randomClick@{
                            val airedEpisodes = meta.sortedPlayableEpisodes()
                                .filter { video ->
                                    video.effectiveEpisodeNumber()?.let { it > 0 } == true &&
                                        PlayerNextEpisodeRules.hasEpisodeAired(video.released)
                                }
                            val randomEpisode = airedEpisodes
                                .filter { video -> video.effectiveSeasonNumber()?.let { it > 0 } == true }
                                .ifEmpty { airedEpisodes }
                                .randomOrNull(Random.Default)
                                ?: return@randomClick
                            dropTrailerForPlaybackNavigation()
                            val season = randomEpisode.effectiveSeasonNumber()
                            val episode = randomEpisode.effectiveEpisodeNumber()
                            val playbackVideoId = buildPlaybackVideoId(
                                parentMetaId = meta.id,
                                seasonNumber = season,
                                episodeNumber = episode,
                                fallbackVideoId = randomEpisode.id,
                            )
                            val streamVideoId = randomEpisode.streamVideoIdForPlayback(meta.id, playbackVideoId)
                            playRandomEpisode(
                                meta.type,
                                streamVideoId,
                                meta.id,
                                meta.type,
                                meta.name,
                                meta.logo,
                                meta.poster,
                                meta.background,
                                season,
                                episode,
                                randomEpisode.title,
                                randomEpisode.thumbnail,
                                randomEpisode.overview,
                            )
                        }
                    }
                val listState = rememberLazyListState()

                val adaptiveHeroEnabled = homeSettingsUiState.adaptiveHeroEnabled && isDesktop && !metaScreenSettingsUiState.tabLayout
                val detailsKeyboardNavigationEnabled = isDesktop && !metaScreenSettingsUiState.tabLayout
                val tvFocus = rememberMetaDetailsTvFocusState()
                val tvFocusRequester = remember { FocusRequester() }
                val tvCoroutineScope = rememberCoroutineScope()
                val mouseActivity = rememberMouseActivityState()

                val groupedEpisodesForTv = remember(meta.videos, meta.type) {
                    val withSeasonOrEp = meta.videos.filter {
                        it.effectiveSeasonNumber() != null || it.effectiveEpisodeNumber() != null
                    }
                    if (withSeasonOrEp.isNotEmpty()) {
                        withSeasonOrEp
                            .sortedWith(metaVideoSeasonEpisodeComparator)
                            .groupBy { normalizeSeasonNumber(it.effectiveSeasonNumber()) }
                    } else if (meta.type != "series" && meta.videos.isNotEmpty()) {
                        mapOf(normalizeSeasonNumber(null) to meta.videos)
                    } else {
                        emptyMap()
                    }
                }
                val seasonsForTv = remember(groupedEpisodesForTv) {
                    groupedEpisodesForTv.keys.sortedBy(::seasonSortKey)
                }
                val defaultSeasonForTv = remember(seasonsForTv, preferredSeriesSeasonNumber) {
                    preferredSeriesSeasonNumber
                        ?.let(::normalizeSeasonNumber)
                        ?.takeIf { it in groupedEpisodesForTv }
                        ?: seasonsForTv.firstOrNull()
                        ?: SPECIALS_SEASON_NUMBER
                }
                var selectedSeasonForTv by rememberSaveable(meta.id) { mutableStateOf<Int?>(null) }
                val currentSeasonForTv = selectedSeasonForTv
                    ?.takeIf { it in groupedEpisodesForTv }
                    ?: defaultSeasonForTv

                LaunchedEffect(meta.id) {
                    listState.scrollToItem(0)
                    tvFocus.sectionIndex = 0
                    tvFocus.itemIndex = 0
                }

                val visibleSectionKeys = remember(
                    metaScreenSettingsUiState.items,
                    meta,
                    hasProductionSection,
                    hasTrailersSection,
                    hasEpisodes,
                    hasAdditionalInfoSection,
                    hasCollectionSection,
                    hasMoreLikeThisSection,
                    shouldShowComments,
                    comments,
                    isCommentsLoading,
                    commentsError,
                ) {
                    metaScreenSettingsUiState.items
                        .filter { it.enabled }
                        .filter { item ->
                            metaSectionHasContent(
                                key = item.key,
                                meta = meta,
                                hasProductionSection = hasProductionSection,
                                hasTrailersSection = hasTrailersSection,
                                hasEpisodes = hasEpisodes,
                                hasAdditionalInfoSection = hasAdditionalInfoSection,
                                hasCollectionSection = hasCollectionSection,
                                hasMoreLikeThisSection = hasMoreLikeThisSection,
                                shouldShowComments = shouldShowComments,
                                comments = comments,
                                isCommentsLoading = isCommentsLoading,
                                commentsError = commentsError,
                            )
                        }
                        .map { it.key }
                }

                val mergedDetailKeyboardNavigation = isDesktop && !metaScreenSettingsUiState.tabLayout
                val mergedSelectorTabs = remember(
                    visibleSectionKeys,
                    hasEpisodes,
                    seasonsForTv,
                    meta.trailers,
                    meta.collectionItems,
                    meta.moreLikeThis,
                ) {
                    buildList {
                        if (MetaScreenSectionKey.TRAILERS in visibleSectionKeys && meta.trailers.isNotEmpty()) {
                            add(DETAIL_TRAILER_SELECTOR)
                        }
                        if (hasEpisodes) {
                            addAll(seasonsForTv)
                        }
                        if (MetaScreenSectionKey.COLLECTION in visibleSectionKeys && meta.collectionItems.isNotEmpty()) {
                            add(DETAIL_COLLECTION_SELECTOR)
                        }
                        if (MetaScreenSectionKey.MORE_LIKE_THIS in visibleSectionKeys && meta.moreLikeThis.isNotEmpty()) {
                            add(DETAIL_MORE_LIKE_THIS_SELECTOR)
                        }
                    }
                }
                val currentMergedSelector = selectedSeasonForTv
                    ?.takeIf { it in mergedSelectorTabs }
                    ?: currentSeasonForTv.takeIf { hasEpisodes && it in mergedSelectorTabs }
                    ?: mergedSelectorTabs.firstOrNull()

                // The actions row as the keyboard sees it. Order must stay in lockstep with what
                // DetailActionButtons renders — Play, then the secondary actions in list order —
                // because the focus index is what maps a keypress onto a button.
                val actionRowHandlers: List<() -> Unit> = remember(
                    onPrimaryPlayClick,
                    onRandomEpisodeClick,
                    toggleWatched,
                    toggleSaved,
                    onMonitorClick,
                    openRatingDialog,
                ) {
                    buildList {
                        add(onPrimaryPlayClick)
                        onRandomEpisodeClick?.let { add(it) }
                        add(toggleWatched)
                        add(toggleSaved)
                        onMonitorClick?.let { add(it) }
                        openRatingDialog?.let { add(it) }
                    }
                }

                val tvSections = remember(
                    visibleSectionKeys,
                    actionRowHandlers,
                    comments,
                    meta.trailers,
                    seasonsForTv,
                    groupedEpisodesForTv,
                    currentSeasonForTv,
                    currentMergedSelector,
                    mergedSelectorTabs,
                    meta.collectionItems,
                    meta.moreLikeThis,
                    hasEpisodes,
                    mergedDetailKeyboardNavigation,
                ) {
                    buildList {
                        if (mergedDetailKeyboardNavigation) {
                            if (MetaScreenSectionKey.ACTIONS in visibleSectionKeys) {
                                add(
                                    MetaTvSection(
                                        kind = MetaTvSectionKind.ACTIONS,
                                        lazyItemIndex = 0,
                                        itemCount = actionRowHandlers.size,
                                        onEnter = { idx -> actionRowHandlers.getOrNull(idx)?.invoke() },
                                    ),
                                )
                            }
                            if (mergedSelectorTabs.isNotEmpty()) {
                                add(
                                    MetaTvSection(
                                        kind = MetaTvSectionKind.SEASONS,
                                        lazyItemIndex = 0,
                                        itemCount = mergedSelectorTabs.size,
                                        onEnter = { idx ->
                                            mergedSelectorTabs.getOrNull(idx)?.let { selectedSeasonForTv = it }
                                        },
                                    ),
                                )
                            }
                            when (currentMergedSelector) {
                                DETAIL_TRAILER_SELECTOR -> if (meta.trailers.isNotEmpty()) {
                                    add(
                                        MetaTvSection(
                                            kind = MetaTvSectionKind.TRAILERS,
                                            lazyItemIndex = 0,
                                            itemCount = meta.trailers.size,
                                            onEnter = { idx -> meta.trailers.getOrNull(idx)?.let { resolveTrailer(it) } },
                                        ),
                                    )
                                }
                                DETAIL_COLLECTION_SELECTOR -> if (meta.collectionItems.isNotEmpty()) {
                                    add(
                                        MetaTvSection(
                                            kind = MetaTvSectionKind.COLLECTION,
                                            lazyItemIndex = 0,
                                            itemCount = meta.collectionItems.size,
                                            onEnter = { idx -> meta.collectionItems.getOrNull(idx)?.let { onOpenMeta?.invoke(it) } },
                                        ),
                                    )
                                }
                                DETAIL_MORE_LIKE_THIS_SELECTOR -> if (meta.moreLikeThis.isNotEmpty()) {
                                    add(
                                        MetaTvSection(
                                            kind = MetaTvSectionKind.MORE_LIKE_THIS,
                                            lazyItemIndex = 0,
                                            itemCount = meta.moreLikeThis.size,
                                            onEnter = { idx -> meta.moreLikeThis.getOrNull(idx)?.let { onOpenMeta?.invoke(it) } },
                                        ),
                                    )
                                }
                                null -> Unit
                                else -> {
                                    val episodes = groupedEpisodesForTv[currentMergedSelector].orEmpty()
                                    if (episodes.isNotEmpty()) {
                                        add(
                                            MetaTvSection(
                                                kind = MetaTvSectionKind.EPISODES,
                                                lazyItemIndex = 0,
                                                itemCount = episodes.size,
                                                isVerticalEpisodeList = false,
                                                onEnter = { idx -> episodes.getOrNull(idx)?.let { onEpisodePlayClick(it) } },
                                            ),
                                        )
                                    }
                                }
                            }
                            return@buildList
                        }
                        visibleSectionKeys.forEachIndexed { index, key ->
                            val lazyItemIndex = index + 1
                            when (key) {
                                MetaScreenSectionKey.ACTIONS -> add(
                                    MetaTvSection(
                                        kind = MetaTvSectionKind.ACTIONS,
                                        lazyItemIndex = lazyItemIndex,
                                        itemCount = actionRowHandlers.size,
                                        onEnter = { idx -> actionRowHandlers.getOrNull(idx)?.invoke() },
                                    ),
                                )
                                MetaScreenSectionKey.COMMENTS -> if (comments.isNotEmpty()) {
                                    add(
                                        MetaTvSection(
                                            kind = MetaTvSectionKind.COMMENTS,
                                            lazyItemIndex = lazyItemIndex,
                                            itemCount = comments.size,
                                            onEnter = { idx -> comments.getOrNull(idx)?.let { selectedComment = it } },
                                        ),
                                    )
                                }
                                MetaScreenSectionKey.TRAILERS -> if (meta.trailers.isNotEmpty()) {
                                    add(
                                        MetaTvSection(
                                            kind = MetaTvSectionKind.TRAILERS,
                                            lazyItemIndex = lazyItemIndex,
                                            itemCount = meta.trailers.size,
                                            onEnter = { idx -> meta.trailers.getOrNull(idx)?.let { resolveTrailer(it) } },
                                        ),
                                    )
                                }
                                MetaScreenSectionKey.EPISODES -> {
                                    if (seasonsForTv.size > 1) {
                                        add(
                                            MetaTvSection(
                                                kind = MetaTvSectionKind.SEASONS,
                                                lazyItemIndex = lazyItemIndex,
                                                itemCount = seasonsForTv.size,
                                                onEnter = { idx -> seasonsForTv.getOrNull(idx)?.let { selectedSeasonForTv = it } },
                                            ),
                                        )
                                    }
                                    val episodes = groupedEpisodesForTv[currentSeasonForTv].orEmpty()
                                    if (episodes.isNotEmpty()) {
                                        add(
                                            MetaTvSection(
                                                kind = MetaTvSectionKind.EPISODES,
                                                lazyItemIndex = lazyItemIndex,
                                                itemCount = episodes.size,
                                                isVerticalEpisodeList = false,
                                                onEnter = { idx -> episodes.getOrNull(idx)?.let { onEpisodePlayClick(it) } },
                                            ),
                                        )
                                    }
                                }
                                MetaScreenSectionKey.COLLECTION -> if (!hasEpisodes && meta.collectionItems.isNotEmpty()) {
                                    add(
                                        MetaTvSection(
                                            kind = MetaTvSectionKind.COLLECTION,
                                            lazyItemIndex = lazyItemIndex,
                                            itemCount = meta.collectionItems.size,
                                            onEnter = { idx -> meta.collectionItems.getOrNull(idx)?.let { onOpenMeta?.invoke(it) } },
                                        ),
                                    )
                                }
                                MetaScreenSectionKey.MORE_LIKE_THIS -> if (meta.moreLikeThis.isNotEmpty()) {
                                    add(
                                        MetaTvSection(
                                            kind = MetaTvSectionKind.MORE_LIKE_THIS,
                                            lazyItemIndex = lazyItemIndex,
                                            itemCount = meta.moreLikeThis.size,
                                            onEnter = { idx -> meta.moreLikeThis.getOrNull(idx)?.let { onOpenMeta?.invoke(it) } },
                                        ),
                                    )
                                }
                                else -> Unit
                            }
                        }
                    }
                }

                LaunchedEffect(tvSections.size) {
                    if (tvSections.isEmpty()) {
                        tvFocus.sectionIndex = 0
                        tvFocus.itemIndex = 0
                    } else if (tvFocus.sectionIndex > tvSections.size - 1) {
                        tvFocus.sectionIndex = tvSections.size - 1
                        tvFocus.itemIndex = 0
                    }
                }

                LaunchedEffect(detailsKeyboardNavigationEnabled) {
                    if (detailsKeyboardNavigationEnabled) {
                        tvFocusRequester.requestFocus()
                    }
                }

                val tvFocusedSection = if (detailsKeyboardNavigationEnabled) tvSections.getOrNull(tvFocus.sectionIndex) else null
                val tvFocusInfo = MetaTvFocusInfo(
                    focusedActionIndex = tvFocus.itemIndex.takeIf { tvFocusedSection?.kind == MetaTvSectionKind.ACTIONS },
                    focusedCommentsIndex = tvFocus.itemIndex.takeIf { tvFocusedSection?.kind == MetaTvSectionKind.COMMENTS },
                    focusedTrailersIndex = tvFocus.itemIndex.takeIf { tvFocusedSection?.kind == MetaTvSectionKind.TRAILERS },
                    focusedSeasonIndex = tvFocus.itemIndex.takeIf { tvFocusedSection?.kind == MetaTvSectionKind.SEASONS },
                    focusedEpisodeIndex = tvFocus.itemIndex.takeIf { tvFocusedSection?.kind == MetaTvSectionKind.EPISODES },
                    focusedCollectionIndex = tvFocus.itemIndex.takeIf { tvFocusedSection?.kind == MetaTvSectionKind.COLLECTION },
                    focusedMoreLikeThisIndex = tvFocus.itemIndex.takeIf { tvFocusedSection?.kind == MetaTvSectionKind.MORE_LIKE_THIS },
                )

                LaunchedEffect(tvFocusInfo.focusedSeasonIndex, seasonsForTv, mergedSelectorTabs, mergedDetailKeyboardNavigation) {
                    val selectorItems = if (mergedDetailKeyboardNavigation) mergedSelectorTabs else seasonsForTv
                    tvFocusInfo.focusedSeasonIndex
                        ?.let(selectorItems::getOrNull)
                        ?.let { focusedSeason -> selectedSeasonForTv = focusedSeason }
                }

                val density = LocalDensity.current
                val safeAreaTopPx = with(density) {
                    WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding()
                        .toPx()
                }
                var heroHeightPx by remember(meta.id) { mutableIntStateOf(0) }
                val thresholdPx = (heroHeightPx - safeAreaTopPx).coerceAtLeast(0f)
                val detailScrollOffsetPx = if (listState.firstVisibleItemIndex == 0) {
                    listState.firstVisibleItemScrollOffset.toFloat()
                } else {
                    heroHeightPx.toFloat() + listState.firstVisibleItemScrollOffset
                }
                val heroScrollOffset = detailScrollOffsetPx.toInt()
                val headerTarget = if (
                    heroHeightPx > 0 &&
                    (listState.firstVisibleItemIndex > 0 || detailScrollOffsetPx > thresholdPx)
                ) {
                    1f
                } else {
                    0f
                }
                val heroTrailerSourceUrl = heroTrailerPlaybackSource
                    ?.videoUrl
                    ?.takeIf { it.isNotBlank() && heroTrailerPlaybackEnabled && !heroTrailerFinished && !isLeavingDetails }
                val heroTrailerSourceAudioUrl = heroTrailerPlaybackSource
                    ?.audioUrl
                    ?.takeIf { heroTrailerSourceUrl != null && it.isNotBlank() }
                val dismissHeroTrailerPlayback: () -> Unit = {
                    // Pause and hide, but keep the source (and the mounted player surface)
                    // so a later trailer click re-attaches in place instead of rebuilding a
                    // fresh native surface — the rebuild comes up on a black frame.
                    heroTrailerReady = false
                    heroTrailerAutoplayReady = false
                    heroTrailerManualPlayback = false
                    heroTrailerDismissed = true
                }
                LaunchedEffect(
                    heroTrailerSourceUrl,
                    metaScreenSettingsUiState.heroTrailerDelaySeconds,
                    isLeavingDetails,
                    heroTrailerManualPlayback,
                    heroTrailerDismissed,
                ) {
                    heroTrailerAutoplayReady = false
                    if (heroTrailerSourceUrl == null || isLeavingDetails || heroTrailerDismissed) return@LaunchedEffect
                    // Both paths keep playWhenReady false during this wait, so the surface attaches
                    // paused and decodes its first frame before playback starts. The automatic path
                    // waits the configured delay; a user-clicked trailer only needs a brief buffer.
                    // Starting a fresh attach playing immediately never reveals (the frame isn't
                    // ready when the reveal poll checks), which left manual playback black.
                    if (heroTrailerManualPlayback) {
                        delay(HERO_TRAILER_MANUAL_PREBUFFER_MS)
                    } else {
                        delay(metaScreenSettingsUiState.heroTrailerDelaySeconds.coerceAtLeast(1) * 1000L)
                    }
                    if (!isLeavingDetails) {
                        heroTrailerAutoplayReady = true
                    }
                }
                val heroTrailerPlayWhenReady = heroTrailerSourceUrl != null &&
                    heroTrailerAutoplayReady &&
                    !isLeavingDetails &&
                    !heroTrailerDismissed &&
                    // The bounded trailer occupies the same right-hand space as the selector
                    // once the scaled hero scrolls. Park it and reveal the backdrop until the
                    // viewport returns to the top.
                    detailScrollOffsetPx <= 0f
                val headerProgress by animateFloatAsState(
                    targetValue = headerTarget,
                    animationSpec = tween(
                        durationMillis = if (headerTarget > 0f) 150 else 100,
                        easing = LinearOutSlowInEasing,
                    ),
                    label = "detail_floating_header_progress",
                )

                // Shared details keyboard-navigation handler. Driven by Compose key events, and
                // — while the native hero-trailer surface holds OS focus — by DetailTvKeyboardBridge
                // (see the surface's global key dispatcher). Both feed identical navigation.
                val handleDetailTvKey: (DetailTvKey) -> Boolean = handleKey@{ key ->
                    if ((key == DetailTvKey.Dismiss || key == DetailTvKey.Back) &&
                        heroTrailerSourceUrl != null && !heroTrailerDismissed
                    ) {
                        dismissHeroTrailerPlayback()
                        return@handleKey true
                    }
                    if (key == DetailTvKey.Back) {
                        onBack()
                        return@handleKey true
                    }
                    if (key == DetailTvKey.TogglePeoplePanel) {
                        DetailHeroPeoplePanelToggleTrigger.trigger()
                        return@handleKey true
                    }
                    if (key == DetailTvKey.ToggleMute) {
                        // Mirror the home hero: M toggles trailer audio while one is playing.
                        return@handleKey if (heroTrailerSourceUrl != null && !heroTrailerDismissed) {
                            HeroTrailerAudioState.toggleMuted()
                            true
                        } else {
                            false
                        }
                    }
                    if (key == DetailTvKey.VolumeDown || key == DetailTvKey.VolumeUp) {
                        // [ / ] step trailer volume as a keyboard alternative to the overlay slider.
                        return@handleKey if (heroTrailerSourceUrl != null && !heroTrailerDismissed) {
                            HeroTrailerAudioState.nudgeVolume(if (key == DetailTvKey.VolumeUp) 5 else -5)
                            true
                        } else {
                            false
                        }
                    }
                    if (tvSections.isEmpty()) return@handleKey false
                    val current = tvSections.getOrNull(tvFocus.sectionIndex) ?: return@handleKey false
                    val isVerticalEpisodes = current.kind == MetaTvSectionKind.EPISODES &&
                        current.isVerticalEpisodeList
                    when (key) {
                        DetailTvKey.Down -> {
                            mouseActivity.onKeyboardNavigation()
                            if (isVerticalEpisodes && tvFocus.itemIndex < current.itemCount - 1) {
                                tvFocus.moveItem(1, current.itemCount)
                            } else {
                                tvFocus.moveSection(1, tvSections.size)
                                val next = tvSections[tvFocus.sectionIndex]
                                val crossesSeasonEpisodeBoundary =
                                    current.kind == MetaTvSectionKind.SEASONS && next.kind == MetaTvSectionKind.EPISODES ||
                                        current.kind == MetaTvSectionKind.EPISODES && next.kind == MetaTvSectionKind.SEASONS
                                if (mergedDetailKeyboardNavigation && next.kind == MetaTvSectionKind.SEASONS) {
                                    // Land on the currently-selected tab (e.g. Season 1), not the
                                    // first tab (Trailers) — focusing a tab also selects it.
                                    tvFocus.itemIndex =
                                        mergedSelectorTabs.indexOf(currentMergedSelector).coerceAtLeast(0)
                                } else if (
                                    mergedDetailKeyboardNavigation ||
                                    isVerticalEpisodes ||
                                    crossesSeasonEpisodeBoundary ||
                                    // The actions row is now multi-item, so a carried-over index would
                                    // land on a secondary button. Arriving there always means Play.
                                    next.kind == MetaTvSectionKind.ACTIONS
                                ) {
                                    tvFocus.itemIndex = 0
                                } else {
                                    tvFocus.coerceItemIndex(next.itemCount)
                                }
                                tvCoroutineScope.launch {
                                    if (mergedDetailKeyboardNavigation) {
                                        // All desktop overlay lanes live inside lazy item 0. Pan
                                        // that item itself: actions belong at the top, while the
                                        // merged selector/episode lanes belong at the bottom.
                                        listState.animateScrollToItem(
                                            index = 0,
                                            scrollOffset = if (next.kind == MetaTvSectionKind.ACTIONS) 0 else heroHeightPx,
                                        )
                                    } else {
                                        val targetIndex = if (next.kind == MetaTvSectionKind.ACTIONS) 0 else next.lazyItemIndex
                                        listState.animateScrollToItem(
                                            index = targetIndex,
                                            scrollOffset = -with(density) { 96.dp.roundToPx() },
                                        )
                                    }
                                }
                            }
                            true
                        }
                        DetailTvKey.Up -> {
                            mouseActivity.onKeyboardNavigation()
                            if (isVerticalEpisodes && tvFocus.itemIndex > 0) {
                                tvFocus.moveItem(-1, current.itemCount)
                            } else {
                                tvFocus.moveSection(-1, tvSections.size)
                                val next = tvSections[tvFocus.sectionIndex]
                                val crossesSeasonEpisodeBoundary =
                                    current.kind == MetaTvSectionKind.SEASONS && next.kind == MetaTvSectionKind.EPISODES ||
                                        current.kind == MetaTvSectionKind.EPISODES && next.kind == MetaTvSectionKind.SEASONS
                                if (mergedDetailKeyboardNavigation && next.kind == MetaTvSectionKind.SEASONS) {
                                    // Land on the currently-selected tab (e.g. Season 1), not the
                                    // first tab (Trailers) — focusing a tab also selects it.
                                    tvFocus.itemIndex =
                                        mergedSelectorTabs.indexOf(currentMergedSelector).coerceAtLeast(0)
                                } else if (
                                    mergedDetailKeyboardNavigation ||
                                    // The actions row is now multi-item, so a carried-over index
                                    // would land on a secondary button. Arriving there means Play.
                                    next.kind == MetaTvSectionKind.ACTIONS
                                ) {
                                    tvFocus.itemIndex = 0
                                } else if (crossesSeasonEpisodeBoundary) {
                                    tvFocus.itemIndex = if (next.kind == MetaTvSectionKind.SEASONS) {
                                        seasonsForTv.indexOf(currentSeasonForTv).coerceAtLeast(0)
                                    } else {
                                        0
                                    }
                                } else if (isVerticalEpisodes) {
                                    tvFocus.itemIndex = (next.itemCount - 1).coerceAtLeast(0)
                                } else {
                                    tvFocus.coerceItemIndex(next.itemCount)
                                }
                                tvCoroutineScope.launch {
                                    if (mergedDetailKeyboardNavigation) {
                                        listState.animateScrollToItem(
                                            index = 0,
                                            scrollOffset = if (next.kind == MetaTvSectionKind.ACTIONS) 0 else heroHeightPx,
                                        )
                                    } else {
                                        val targetIndex = if (next.kind == MetaTvSectionKind.ACTIONS) 0 else next.lazyItemIndex
                                        listState.animateScrollToItem(
                                            index = targetIndex,
                                            scrollOffset = -with(density) { 96.dp.roundToPx() },
                                        )
                                    }
                                }
                            }
                            true
                        }
                        DetailTvKey.Right -> {
                            if (!isVerticalEpisodes) {
                                mouseActivity.onKeyboardNavigation()
                                tvFocus.moveItem(1, current.itemCount)
                            }
                            true
                        }
                        DetailTvKey.Left -> {
                            if (!isVerticalEpisodes) {
                                mouseActivity.onKeyboardNavigation()
                                tvFocus.moveItem(-1, current.itemCount)
                            }
                            true
                        }
                        DetailTvKey.Select -> {
                            current.onEnter(tvFocus.itemIndex)
                            true
                        }
                        else -> false
                    }
                }
                val currentHandleDetailTvKey = rememberUpdatedState(handleDetailTvKey)
                LaunchedEffect(Unit) {
                    DetailTvKeyboardBridge.keys.collect { key ->
                        if (detailsKeyboardNavigationEnabled) {
                            currentHandleDetailTvKey.value(key)
                        }
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (detailsKeyboardNavigationEnabled) {
                                Modifier
                                    .focusRequester(tvFocusRequester)
                                    .focusable()
                                    .onPointerEvent(PointerEventType.Move) { event ->
                                        mouseActivity.onMouseMoved(event.changes.first().position)
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        if (appShortcutMatches(AppShortcutAction.TogglePeoplePanel, event)) {
                                            return@onPreviewKeyEvent handleDetailTvKey(DetailTvKey.TogglePeoplePanel)
                                        }
                                        if (appShortcutMatches(AppShortcutAction.ToggleTrailerMute, event)) {
                                            return@onPreviewKeyEvent handleDetailTvKey(DetailTvKey.ToggleMute)
                                        }
                                        val navKey = when (event.navigationKey()) {
                                            Key.Escape -> DetailTvKey.Dismiss
                                            Key.Backspace -> DetailTvKey.Back
                                            Key.DirectionDown -> DetailTvKey.Down
                                            Key.DirectionUp -> DetailTvKey.Up
                                            Key.DirectionRight -> DetailTvKey.Right
                                            Key.DirectionLeft -> DetailTvKey.Left
                                            Key.Enter, Key.NumPadEnter -> DetailTvKey.Select
                                            Key.LeftBracket -> DetailTvKey.VolumeDown
                                            Key.RightBracket -> DetailTvKey.VolumeUp
                                            else -> return@onPreviewKeyEvent false
                                        }
                                        handleDetailTvKey(navKey)
                                    }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    val isTablet = maxWidth >= 720.dp
                    val viewportHeight = maxHeight
                    val contentMaxWidth = detailTabletContentMaxWidth(maxWidth, isTablet)
                    val useCompactWindowLayout = LocalNuvioDesktopCompactWindow.current
                    val useDesktopDetailLayout =
                        isDesktop &&
                            !useCompactWindowLayout &&
                            isTablet &&
                            maxWidth >= 1120.dp &&
                            !metaScreenSettingsUiState.tabLayout
                    val contentHorizontalPadding = if (useDesktopDetailLayout) {
                        64.dp
                    } else if (isTablet) {
                        32.dp
                    } else {
                        18.dp
                    }
                    val sectionContentMaxWidth = if (useDesktopDetailLayout) {
                        Dp.Unspecified
                    } else if (isTablet) {
                        contentMaxWidth
                    } else {
                        Dp.Unspecified
                    }
                    val backdropUrl = meta.background ?: meta.poster
                    val backgroundMode = metaScreenSettingsUiState.backgroundMode
                    val dominantColorEnabled = backgroundMode == MetaScreenBackgroundMode.DominantColor &&
                        deferredMetaWorkAllowed &&
                        !backdropUrl.isNullOrBlank()
                    val colorScheme = MaterialTheme.colorScheme
                    var dominantBackdropPainter by remember(meta.id, backdropUrl) { mutableStateOf<Painter?>(null) }
                    var dominantBackdropImageBitmap by remember(meta.id, backdropUrl) { mutableStateOf<ImageBitmap?>(null) }
                    var dominantBackdropReady by remember(meta.id, backdropUrl) { mutableStateOf(false) }
                    val dominantImageBitmapColorState = rememberDominantColorState(
                        defaultColor = colorScheme.background,
                        defaultOnColor = colorScheme.onBackground,
                    )
                    val dominantPainterColorState = rememberPainterDominantColorState(
                        defaultColor = colorScheme.background,
                        defaultOnColor = colorScheme.onBackground,
                    )
                    LaunchedEffect(dominantColorEnabled, dominantBackdropImageBitmap, dominantBackdropPainter) {
                        val imageBitmap = dominantBackdropImageBitmap
                        val painter = dominantBackdropPainter
                        if (dominantColorEnabled) {
                            when {
                                imageBitmap != null -> runCatching { dominantImageBitmapColorState.updateFrom(imageBitmap) }
                                    .onSuccess { dominantBackdropReady = true }
                                painter != null -> runCatching { dominantPainterColorState.updateFrom(painter) }
                                    .onSuccess { dominantBackdropReady = true }
                            }
                        }
                    }
                    val extractedDominantColor = if (dominantBackdropImageBitmap != null) {
                        dominantImageBitmapColorState.color
                    } else {
                        dominantPainterColorState.color
                    }
                    val dominantBackdropColor = if (dominantColorEnabled && dominantBackdropReady) {
                        dominantBackdropBlendColor(extractedDominantColor, colorScheme.background)
                    } else {
                        colorScheme.background
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (backgroundMode) {
                            MetaScreenBackgroundMode.Normal -> Unit
                            MetaScreenBackgroundMode.Cinematic -> if (deferredMetaWorkAllowed && backdropUrl != null) {
                                AsyncImage(
                                    model = backdropUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().blur(30.dp),
                                    contentScale = ContentScale.Crop,
                                )
                                Box(Modifier.fillMaxSize().background(colorScheme.background.copy(alpha = 0.92f)))
                            }
                            MetaScreenBackgroundMode.DominantColor -> if (deferredMetaWorkAllowed) {
                                Box(Modifier.fillMaxSize().background(dominantBackdropColor))
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(1f),
                            // App UI scaling reduces the logical viewport. Keep the desktop hero's
                            // designed 1080dp canvas scrollable when it no longer fits instead of
                            // forcing its fixed-position hero sections to overlap one another.
                            userScrollEnabled = !useDesktopDetailLayout || viewportHeight < 1080.dp,
                        ) {
                            item(key = "detail-hero") {
                                val enabledHeroSections = metaScreenSettingsUiState.items
                                    .filter { it.enabled }
                                    .map { it.key }
                                    .toSet()
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    DetailHero(
                                        meta = meta,
                                        isTablet = isTablet,
                                        contentMaxWidth = if (useDesktopDetailLayout) 760.dp else contentMaxWidth,
                                        viewportHeight = viewportHeight,
                                        scrollOffset = heroScrollOffset,
                                        onHeightChanged = { heroHeightPx = it },
                                        heroTrailerSourceUrl = heroTrailerSourceUrl,
                                        heroTrailerSourceAudioUrl = heroTrailerSourceAudioUrl,
                                        heroTrailerReady = heroTrailerReady && heroTrailerPlayWhenReady,
                                        heroTrailerPlayWhenReady = heroTrailerPlayWhenReady,
                                        heroTrailerMuted = heroTrailerMuted,
                                        heroTrailerVolume = heroTrailerVolume,
                                        heroTrailerKeyboardNavigation = detailsKeyboardNavigationEnabled,
                                        heroTrailerPlaybackMode = metaScreenSettingsUiState.heroTrailerPlaybackMode,
                                        heroTrailerBackgroundMode = metaScreenSettingsUiState.heroTrailerBackgroundMode,
                                        heroGradientColor = if (dominantColorEnabled) dominantBackdropColor else null,
                                        onBackdropLoaded = { painter, imageBitmap ->
                                            dominantBackdropPainter = painter
                                            dominantBackdropImageBitmap = imageBitmap
                                        },
                                        desktopOverlay = useDesktopDetailLayout,
                                        discoveryFacts = if (metaScreenSettingsUiState.discoveryBadgesEnabled) {
                                            heroDiscoveryFacts
                                        } else {
                                            emptyList()
                                        },
                                        maxDiscoveryBadges = if (metaScreenSettingsUiState.discoveryBadgesEnabled) {
                                            homeSettingsUiState.heroInfoLines
                                        } else {
                                            0
                                        },
                                        playButtonLabel = playButtonLabel,
                                        isSaved = isSaved,
                                        libraryName = activeLibraryName,
                                        isWatched = isWatched,
                                        showActions = useDesktopDetailLayout && MetaScreenSectionKey.ACTIONS in enabledHeroSections,
                                        showOverview = useDesktopDetailLayout && MetaScreenSectionKey.OVERVIEW in enabledHeroSections,
                                        showCast = useDesktopDetailLayout &&
                                            MetaScreenSectionKey.CAST in enabledHeroSections &&
                                            meta.cast.isNotEmpty(),
                                        showProduction = useDesktopDetailLayout &&
                                            MetaScreenSectionKey.PRODUCTION in enabledHeroSections &&
                                            hasProductionSection,
                                        showDetails = useDesktopDetailLayout &&
                                            MetaScreenSectionKey.DETAILS in enabledHeroSections &&
                                            hasAdditionalInfoSection,
                                        showManualPlayOption = showManualPlayOption,
                                        focusedActionIndex = tvFocusInfo.focusedActionIndex,
                                        onPrimaryPlayClick = onPrimaryPlayClick,
                                        onPrimaryPlayLongClick = onPrimaryPlayLongClick,
                                        onRandomEpisodeClick = onRandomEpisodeClick,
                                        onSaveClick = toggleSaved,
                                        onSaveLongClick = openLibraryListPicker,
                                        isMonitored = isMonitored,
                                        onMonitorClick = onMonitorClick,
                                        onWatchedClick = toggleWatched,
                                        onRateClick = openRatingDialog,
                                        ratingProviderName = ratingProviderName.takeIf { openRatingDialog != null },
                                        onCastClick = onCastClick,
                                        onCompanyClick = onCompanyClick,
                                        onHeroTrailerMuteToggle = {
                                            HeroTrailerAudioState.toggleMuted()
                                        },
                                        onHeroTrailerVolumeChange = { newVolume ->
                                            HeroTrailerAudioState.setVolume(newVolume)
                                        },
                                        onHeroTrailerReclaimFocus = {
                                            try { tvFocusRequester.requestFocus() } catch (_: Exception) {}
                                        },
                                        onHeroTrailerDismiss = dismissHeroTrailerPlayback,
                                        onHeroTrailerReady = {
                                            if (!heroTrailerFinished && !heroTrailerDismissed) {
                                                heroTrailerReady = true
                                            }
                                        },
                                        onHeroTrailerEnded = {
                                            // Keep the surface mounted (dismissed, not finished)
                                            // so clicking a trailer afterwards re-attaches in
                                            // place instead of rebuilding a black native surface.
                                            heroTrailerReady = false
                                            heroTrailerDismissed = true
                                            heroTrailerManualPlayback = false
                                        },
                                        onHeroTrailerError = {
                                            heroTrailerReady = false
                                            heroTrailerDismissed = true
                                            heroTrailerManualPlayback = false
                                        },
                                    )
                                    val showDesktopEpisodesOverlay = useDesktopDetailLayout &&
                                        hasEpisodes &&
                                        MetaScreenSectionKey.EPISODES in enabledHeroSections
                                    val showDesktopRelatedOverlay = useDesktopDetailLayout &&
                                        !hasEpisodes &&
                                        (
                                            MetaScreenSectionKey.TRAILERS in enabledHeroSections && meta.trailers.isNotEmpty() ||
                                                MetaScreenSectionKey.COLLECTION in enabledHeroSections && meta.collectionItems.isNotEmpty() ||
                                                MetaScreenSectionKey.MORE_LIKE_THIS in enabledHeroSections && meta.moreLikeThis.isNotEmpty()
                                            )
                                    if (showDesktopEpisodesOverlay) {
                                        DetailSeriesContent(
                                            meta = meta,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .fillMaxWidth()
                                                // The compact selector's measured content is about
                                                // 263dp tall. Keep a little focus/bottom breathing
                                                // room without exposing a large empty tail when the
                                                // scaled 1080dp hero canvas is scrolled to its end.
                                                .padding(start = 64.dp, end = 64.dp, bottom = 30.dp)
                                                .height(276.dp)
                                                .zIndex(2f),
                                            showHeader = false,
                                            preferredSeasonNumber = preferredSeriesSeasonNumber,
                                            preferredEpisodeNumber = preferredSeriesEpisodeNumber,
                                            episodeCardStyle = MetaEpisodeCardStyle.Horizontal,
                                            progressByVideoId = progressByVideoId,
                                            watchedKeys = watchedUiState.watchedKeys,
                                            episodeRatings = episodeImdbRatings,
                                            blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
                                            onEpisodeClick = onEpisodePlayClick,
                                            onEpisodeLongPress = { video -> selectedEpisodeForActions = video },
                                            onSeasonLongPress = { season -> selectedSeasonForActions = season },
                                            externalSelectedSeason = selectedSeasonForTv,
                                            onSeasonSelected = { season -> selectedSeasonForTv = season },
                                            focusedSeasonIndex = tvFocusInfo.focusedSeasonIndex,
                                            focusedEpisodeIndex = tvFocusInfo.focusedEpisodeIndex,
                                            focusedTrailerIndex = tvFocusInfo.focusedTrailersIndex,
                                            focusedCollectionIndex = tvFocusInfo.focusedCollectionIndex,
                                            focusedMoreLikeThisIndex = tvFocusInfo.focusedMoreLikeThisIndex,
                                            compactDesktopLayout = true,
                                            trailers = meta.trailers.takeIf {
                                                MetaScreenSectionKey.TRAILERS in enabledHeroSections
                                            }.orEmpty(),
                                            onTrailerClick = resolveTrailer,
                                            collectionTitle = meta.collectionName,
                                            collectionItems = meta.collectionItems.takeIf {
                                                MetaScreenSectionKey.COLLECTION in enabledHeroSections
                                            }.orEmpty(),
                                            onCollectionItemClick = { preview -> onOpenMeta?.invoke(preview) },
                                            moreLikeThis = meta.moreLikeThis.takeIf {
                                                MetaScreenSectionKey.MORE_LIKE_THIS in enabledHeroSections
                                            }.orEmpty(),
                                            onMoreLikeThisClick = { preview -> onOpenMeta?.invoke(preview) },
                                        )
                                    } else if (showDesktopRelatedOverlay) {
                                        DetailCompactMediaSelector(
                                            trailers = meta.trailers.takeIf {
                                                MetaScreenSectionKey.TRAILERS in enabledHeroSections
                                            }.orEmpty(),
                                            collectionTitle = meta.collectionName,
                                            collectionItems = meta.collectionItems.takeIf {
                                                MetaScreenSectionKey.COLLECTION in enabledHeroSections
                                            }.orEmpty(),
                                            moreLikeThis = meta.moreLikeThis.takeIf {
                                                MetaScreenSectionKey.MORE_LIKE_THIS in enabledHeroSections
                                            }.orEmpty(),
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .fillMaxWidth()
                                                .padding(start = 64.dp, end = 64.dp, bottom = 30.dp)
                                                .height(276.dp)
                                                .zIndex(2f),
                                            selectedTabKey = currentMergedSelector,
                                            focusedTabIndex = tvFocusInfo.focusedSeasonIndex,
                                            onTabSelected = { selectedSeasonForTv = it },
                                            onTrailerClick = resolveTrailer,
                                            onCollectionItemClick = { preview -> onOpenMeta?.invoke(preview) },
                                            onMoreLikeThisClick = { preview -> onOpenMeta?.invoke(preview) },
                                            focusedTrailerIndex = tvFocusInfo.focusedTrailersIndex,
                                            focusedCollectionIndex = tvFocusInfo.focusedCollectionIndex,
                                            focusedMoreLikeThisIndex = tvFocusInfo.focusedMoreLikeThisIndex,
                                        )
                                    }
                                }
                            }

                            // The desktop overlay is the complete details design: its enabled
                            // sections are already composed inside the hero. The legacy
                            // comments-only tail used to be unreachable while vertical scrolling
                            // was disabled; do not append it (or its spacer) now that scaled
                            // viewports may scroll the hero canvas.
                            if (!useDesktopDetailLayout) {
                                configuredMetaSectionItems(
                                settings = metaScreenSettingsUiState,
                                meta = meta,
                                isTablet = isTablet,
                                contentHorizontalPadding = contentHorizontalPadding,
                                contentMaxWidth = sectionContentMaxWidth,
                                desktopTwoColumnLayout = useDesktopDetailLayout,
                                playButtonLabel = playButtonLabel,
                                isSaved = isSaved,
                                libraryName = activeLibraryName,
                                isWatched = isWatched,
                                onPrimaryPlayClick = onPrimaryPlayClick,
                                onPrimaryPlayLongClick = onPrimaryPlayLongClick,
                                onRandomEpisodeClick = onRandomEpisodeClick,
                                onSaveClick = toggleSaved,
                                onSaveLongClick = openLibraryListPicker,
                                isMonitored = isMonitored,
                                onMonitorClick = onMonitorClick,
                                onWatchedClick = toggleWatched,
                                onRateClick = openRatingDialog,
                                ratingProviderName = ratingProviderName.takeIf { openRatingDialog != null },
                                showManualPlayOption = showManualPlayOption,
                                preferredEpisodeSeasonNumber = preferredSeriesSeasonNumber,
                                preferredEpisodeNumber = preferredSeriesEpisodeNumber,
                                hasProductionSection = hasProductionSection,
                                hasTrailersSection = hasTrailersSection,
                                hasEpisodes = hasEpisodes,
                                hasAdditionalInfoSection = hasAdditionalInfoSection,
                                hasCollectionSection = hasCollectionSection,
                                hasMoreLikeThisSection = hasMoreLikeThisSection,
                                shouldShowComments = shouldShowComments,
                                comments = comments,
                                isCommentsLoading = isCommentsLoading,
                                isCommentsLoadingMore = isCommentsLoadingMore,
                                commentsCurrentPage = commentsCurrentPage,
                                commentsPageCount = commentsPageCount,
                                commentsError = commentsError,
                                episodeImdbRatings = episodeImdbRatings,
                                onRetryComments = {
                                    detailsScope.launch {
                                        isCommentsLoading = true
                                        commentsError = null
                                        try {
                                            val result = TraktCommentsRepository.getCommentsPage(meta, page = 1, forceRefresh = true)
                                            comments = result.items
                                            commentsCurrentPage = result.currentPage
                                            commentsPageCount = result.pageCount
                                        } catch (e: Exception) {
                                            commentsError = e.message ?: getString(Res.string.details_comments_load_failed)
                                        }
                                        isCommentsLoading = false
                                    }
                                },
                                onLoadMoreComments = {
                                    detailsScope.launch {
                                        isCommentsLoadingMore = true
                                        try {
                                            val nextPage = commentsCurrentPage + 1
                                            val result = TraktCommentsRepository.getCommentsPage(meta, page = nextPage)
                                            val existingIds = comments.map { it.id }.toSet()
                                            val newComments = result.items.filter { it.id !in existingIds }
                                            comments = comments + newComments
                                            commentsCurrentPage = result.currentPage
                                            commentsPageCount = result.pageCount
                                        } catch (_: Exception) { }
                                        isCommentsLoadingMore = false
                                    }
                                },
                                onCommentClick = { review -> selectedComment = review },
                                onTrailerClick = resolveTrailer,
                                progressByVideoId = progressByVideoId,
                                watchedKeys = watchedUiState.watchedKeys,
                                blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
                                onEpisodeClick = onEpisodePlayClick,
                                onEpisodeLongPress = { video -> selectedEpisodeForActions = video },
                                onSeasonLongPress = { season -> selectedSeasonForActions = season },
                                onOpenMeta = onOpenMeta,
                                onCastClick = onCastClick,
                                onCompanyClick = onCompanyClick,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                tvFocus = tvFocusInfo,
                                externalSelectedSeason = currentSeasonForTv,
                                onSeasonSelected = { season -> selectedSeasonForTv = season },
                                )

                                item(key = "detail-bottom-spacer") {
                                    Spacer(modifier = Modifier.height(nuvioSafeBottomPadding(32.dp)))
                                }
                            }
                        }

                        if (backgroundMode == MetaScreenBackgroundMode.Cinematic && heroHeightPx > 0) {
                            val blendColor = MaterialTheme.colorScheme.background
                            Box(
                                modifier = Modifier
                                    .zIndex(0.5f)
                                    .fillMaxWidth()
                                    .height(132.dp)
                                    .graphicsLayer {
                                        translationY = heroHeightPx.toFloat() - detailScrollOffsetPx
                                    }
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                blendColor.copy(alpha = 0.98f),
                                                blendColor.copy(alpha = 0.84f),
                                                blendColor.copy(alpha = 0.52f),
                                                Color.Transparent,
                                            ),
                                        ),
                                    ),
                            )
                        }

                        if (headerProgress <= 0.05f) {
                            NuvioBackButton(
                                onClick = onBackFromDetails,
                                modifier = Modifier.padding(
                                    start = 12.dp,
                                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                                ).zIndex(2f),
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onBackground,
                            )
                        }

                        DetailFloatingHeader(
                            meta = meta,
                            isSaved = isSaved,
                            progress = headerProgress,
                            onBack = onBackFromDetails,
                            onToggleSaved = toggleSaved,
                            modifier = Modifier.zIndex(2f),
                        )

                        selectedEpisodeForActions?.let { selectedEpisode ->
                            val selectedSeasonNumber = selectedEpisode.effectiveSeasonNumber()
                            val selectedEpisodeNumber = selectedEpisode.effectiveEpisodeNumber()
                            val selectedPlaybackVideoId = remember(meta.id, selectedEpisode) {
                                val playbackVideoId = buildPlaybackVideoId(
                                    parentMetaId = meta.id,
                                    seasonNumber = selectedSeasonNumber,
                                    episodeNumber = selectedEpisodeNumber,
                                    fallbackVideoId = selectedEpisode.id,
                                )
                                selectedEpisode.streamVideoIdForPlayback(meta.id, playbackVideoId)
                            }
                            val selectedHasLocalPlayback = remember(
                                meta.id,
                                selectedPlaybackVideoId,
                                selectedSeasonNumber,
                                selectedEpisodeNumber,
                                localLibraryUiState.items,
                                downloadsUiState.items,
                            ) {
                                hasLocalPlayback(
                                    videoId = selectedPlaybackVideoId,
                                    seasonNumber = selectedSeasonNumber,
                                    episodeNumber = selectedEpisodeNumber,
                                )
                            }
                            val alternatePlayLabel =
                                if (
                                    localLibraryUiState.playbackPreference.canOfferAlternate(
                                        selectedHasLocalPlayback,
                                    )
                                ) {
                                    stringResource(
                                        if (
                                            localLibraryUiState.playbackPreference ==
                                                LocalLibraryPlaybackPreference.LOCAL_LIBRARY
                                        ) {
                                            Res.string.play_choose_source
                                        } else {
                                            Res.string.play_local_file
                                        },
                                    )
                                } else {
                                    null
                                }
                            val isSelectedEpisodeWatched = remember(meta, selectedEpisode, watchedUiState.watchedKeys, progressByVideoId) {
                                isEpisodeWatchedForActions(
                                    meta = meta,
                                    episode = selectedEpisode,
                                    watchedKeys = watchedUiState.watchedKeys,
                                    progressByVideoId = progressByVideoId,
                                )
                            }
                            val previousEpisodes = remember(meta, selectedEpisode, todayIsoDate) {
                                meta.previousReleasedEpisodesBefore(
                                    target = selectedEpisode,
                                    todayIsoDate = todayIsoDate,
                                )
                            }
                            val seasonEpisodes = remember(meta, selectedEpisode, todayIsoDate) {
                                meta.releasedEpisodesForSeason(
                                    seasonNumber = selectedEpisode.season,
                                    todayIsoDate = todayIsoDate,
                                )
                            }
                            val arePreviousEpisodesWatched = remember(previousEpisodes, watchedUiState.watchedKeys, progressByVideoId) {
                                areEpisodesWatchedForActions(
                                    meta = meta,
                                    episodes = previousEpisodes,
                                    watchedKeys = watchedUiState.watchedKeys,
                                    progressByVideoId = progressByVideoId,
                                )
                            }
                            val isSeasonWatched = remember(seasonEpisodes, watchedUiState.watchedKeys, progressByVideoId) {
                                areEpisodesWatchedForActions(
                                    meta = meta,
                                    episodes = seasonEpisodes,
                                    watchedKeys = watchedUiState.watchedKeys,
                                    progressByVideoId = progressByVideoId,
                                )
                            }
                            EpisodeWatchedActionSheet(
                                episode = selectedEpisode,
                                seasonLabel = selectedEpisode.season?.let {
                                    stringResource(Res.string.episodes_season, it)
                                } ?: stringResource(Res.string.episodes_specials),
                                isEpisodeWatched = isSelectedEpisodeWatched,
                                canMarkPreviousEpisodes = previousEpisodes.isNotEmpty(),
                                arePreviousEpisodesWatched = arePreviousEpisodesWatched,
                                isSeasonWatched = isSeasonWatched,
                                onDismiss = { selectedEpisodeForActions = null },
                                onToggleWatched = {
                                    WatchingActions.toggleEpisodeWatched(
                                        meta = meta,
                                        episode = selectedEpisode,
                                        isCurrentlyWatched = isSelectedEpisodeWatched,
                                    )
                                },
                                onTogglePreviousWatched = {
                                    WatchingActions.togglePreviousEpisodesWatched(
                                        meta = meta,
                                        episodes = previousEpisodes,
                                        areCurrentlyWatched = arePreviousEpisodesWatched,
                                    )
                                },
                                onToggleSeasonWatched = {
                                    WatchingActions.toggleSeasonWatched(
                                        meta = meta,
                                        episodes = seasonEpisodes,
                                        areCurrentlyWatched = isSeasonWatched,
                                    )
                                },
                                alternatePlayLabel = alternatePlayLabel,
                                onAlternatePlay = alternatePlayLabel?.let {
                                    { onEpisodeAlternatePlayClick(selectedEpisode) }
                                },
                            )
                        }

                        selectedSeasonForActions?.let { selectedSeason ->
                            val seasonLabel = selectedSeasonLabel(selectedSeason)
                            val seasonEpisodes = remember(meta, selectedSeason, todayIsoDate) {
                                meta.releasedEpisodesForSeason(
                                    seasonNumber = selectedSeason,
                                    todayIsoDate = todayIsoDate,
                                )
                            }
                            val previousSeasonEpisodes = remember(meta, selectedSeason, todayIsoDate) {
                                val normalizedSelectedSeason = selectedSeason.coerceAtLeast(0)
                                meta.releasedPlayableEpisodes(todayIsoDate)
                                    .filter { episode ->
                                        val season = episode.effectiveSeasonNumber()?.coerceAtLeast(0) ?: 0
                                        season > 0 && season < normalizedSelectedSeason
                                    }
                            }
                            val isSeasonWatched = remember(seasonEpisodes, watchedUiState.watchedKeys, progressByVideoId) {
                                areEpisodesWatchedForActions(
                                    meta = meta,
                                    episodes = seasonEpisodes,
                                    watchedKeys = watchedUiState.watchedKeys,
                                    progressByVideoId = progressByVideoId,
                                )
                            }
                            val canMarkPreviousSeasons = remember(previousSeasonEpisodes, watchedUiState.watchedKeys, progressByVideoId) {
                                previousSeasonEpisodes.any { episode ->
                                    !isEpisodeWatchedForActions(
                                        meta = meta,
                                        episode = episode,
                                        watchedKeys = watchedUiState.watchedKeys,
                                        progressByVideoId = progressByVideoId,
                                    )
                                }
                            }
                            SeasonWatchedActionSheet(
                                seasonLabel = seasonLabel,
                                isSeasonWatched = isSeasonWatched,
                                canMarkPreviousSeasons = canMarkPreviousSeasons,
                                onDismiss = { selectedSeasonForActions = null },
                                onToggleSeasonWatched = {
                                    WatchingActions.toggleSeasonWatched(
                                        meta = meta,
                                        episodes = seasonEpisodes,
                                        areCurrentlyWatched = isSeasonWatched,
                                    )
                                },
                                onMarkPreviousSeasonsWatched = {
                                    WatchingActions.togglePreviousEpisodesWatched(
                                        meta = meta,
                                        episodes = previousSeasonEpisodes,
                                        areCurrentlyWatched = false,
                                    )
                                },
                            )
                        }

                        if (inAppTrailerPlaybackEnabled) {
                            TrailerPlayerPopup(
                                // On desktop the trailer plays in the full-screen player, so
                                // the embedded popup is only used to surface a resolution
                                // error — never the loading spinner or an embedded surface.
                                visible = selectedTrailer != null &&
                                    (!isDesktop || trailerErrorMessage != null),
                                trailerTitle = selectedTrailer?.displayName ?: selectedTrailer?.name.orEmpty(),
                                trailerType = selectedTrailer?.type.orEmpty(),
                                contentTitle = meta.name,
                                playbackSource = trailerPlaybackSource,
                                isLoading = trailerLoading,
                                errorMessage = trailerErrorMessage,
                                onDismiss = {
                                    trailerRequestToken += 1
                                    trailerLoading = false
                                    trailerPlaybackSource = null
                                    trailerErrorMessage = null
                                    selectedTrailer = null
                                },
                                onRetry = selectedTrailer?.takeIf { trailerErrorRetryable }?.let { trailer ->
                                    { resolveTrailer(trailer) }
                                },
                            )
                        }

                        if (showMonitorDialog) {
                            AddToLibraryDialog(
                                target = monitorTarget,
                                onDismiss = { showMonitorDialog = false },
                            )
                        }

                        TraktListPickerDialog(
                            visible = showLibraryListPicker,
                            title = meta.name,
                            tabs = pickerTabs,
                            membership = pickerMembership,
                            isPending = pickerPending,
                            errorMessage = pickerError,
                            onToggle = { listKey ->
                                pickerMembership = pickerMembership.toMutableMap().apply {
                                    this[listKey] = !(this[listKey] == true)
                                }
                            },
                            onDismiss = {
                                if (!pickerPending) {
                                    showLibraryListPicker = false
                                }
                            },
                            onSave = {
                                detailsScope.launch {
                                    pickerPending = true
                                    pickerError = null
                                    runCatching {
                                        LibraryRepository.applyMembershipChanges(
                                            item = meta.toLibraryItem(savedAtEpochMs = 0L),
                                            desiredMembership = pickerMembership,
                                        )
                                    }.onSuccess {
                                        showLibraryListPicker = false
                                    }.onFailure { error ->
                                        pickerError = error.message ?: getString(Res.string.trakt_lists_update_failed)
                                    }
                                    pickerPending = false
                                }
                            },
                        )

                        TrackingRatingDialog(
                            visible = showRatingDialog,
                            kicker = stringResource(Res.string.details_rating_kicker),
                            headline = stringResource(Res.string.details_rating_title, meta.name),
                            body = stringResource(Res.string.details_rating_choose, ratingProviderName),
                            isPending = ratingPending,
                            errorMessage = ratingError,
                            onRate = { score -> submitRating(score) },
                            onClear = { submitRating(null) },
                            onDismiss = {
                                if (!ratingPending) {
                                    showRatingDialog = false
                                }
                            },
                        )

                        selectedComment?.let { comment ->
                            val commentIndex = comments.indexOfFirst { it.id == comment.id }.coerceAtLeast(0)
                            CommentDetailSheet(
                                comment = comment,
                                currentIndex = commentIndex,
                                totalCount = comments.size,
                                canGoBack = commentIndex > 0,
                                canGoForward = commentIndex < comments.size - 1,
                                onPrevious = {
                                    if (commentIndex > 0) {
                                        selectedComment = comments[commentIndex - 1]
                                    }
                                },
                                onNext = {
                                    val nextIndex = commentIndex + 1
                                    if (nextIndex < comments.size) {
                                        selectedComment = comments[nextIndex]
                                    }
                                    if (nextIndex >= comments.size - 3 && commentsCurrentPage < commentsPageCount) {
                                        detailsScope.launch {
                                            isCommentsLoadingMore = true
                                            try {
                                                val nextPage = commentsCurrentPage + 1
                                                val result = TraktCommentsRepository.getCommentsPage(meta, page = nextPage)
                                                val existingIds = comments.map { it.id }.toSet()
                                                val newComments = result.items.filter { it.id !in existingIds }
                                                comments = comments + newComments
                                                commentsCurrentPage = result.currentPage
                                                commentsPageCount = result.pageCount
                                            } catch (_: Exception) { }
                                            isCommentsLoadingMore = false
                                        }
                                    }
                                },
                                onDismiss = { selectedComment = null },
                            )
                        }
                    }
                }
            }
        }

        if (displayedMeta == null) {
            NuvioBackButton(
                onClick = onBack,
                modifier = Modifier.padding(
                    start = 12.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                ),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

private fun MetaDetails.isSeriesLikeForEpisodeRatings(): Boolean {
    val normalizedType = type.trim().lowercase()
    val hasNumberedEpisodes = videos.any {
        it.effectiveSeasonNumber() != null && it.effectiveEpisodeNumber() != null
    }
    return hasNumberedEpisodes && normalizedType in setOf("series", "show", "tv", "tvshow")
}

@Composable
private fun selectedSeasonLabel(season: Int): String =
    if (season == 0) {
        stringResource(Res.string.episodes_specials)
    } else {
        stringResource(Res.string.episodes_season, season)
    }

private fun isEpisodeWatchedForActions(
    meta: MetaDetails,
    episode: MetaVideo,
    watchedKeys: Set<String>,
    progressByVideoId: Map<String, WatchProgressEntry>,
): Boolean {
    val episodeVideoId = buildPlaybackVideoId(
        parentMetaId = meta.id,
        seasonNumber = episode.effectiveSeasonNumber(),
        episodeNumber = episode.effectiveEpisodeNumber(),
        fallbackVideoId = episode.id,
    )
    return progressByVideoId.progressForEpisodeVideo(episodeVideoId, episode.id)?.isEffectivelyCompleted == true ||
        WatchingState.isEpisodeWatched(
            watchedKeys = watchedKeys,
            metaType = meta.type,
            metaId = meta.id,
            episode = episode,
        )
}

private fun areEpisodesWatchedForActions(
    meta: MetaDetails,
    episodes: Collection<MetaVideo>,
    watchedKeys: Set<String>,
    progressByVideoId: Map<String, WatchProgressEntry>,
): Boolean = episodes.isNotEmpty() && episodes.all { episode ->
    isEpisodeWatchedForActions(
        meta = meta,
        episode = episode,
        watchedKeys = watchedKeys,
        progressByVideoId = progressByVideoId,
    )
}

private fun MetaVideo.streamVideoIdForPlayback(parentMetaId: String, playbackVideoId: String): String {
    val rawId = id.trim()
    return if (parentMetaId.isNativeAnimeMetaId() && rawId.isBareNumericId()) {
        playbackVideoId
    } else {
        rawId.takeIf { it.isNotBlank() } ?: playbackVideoId
    }
}

/**
 * User-facing explanation for a trailer that YouTube itself refuses to serve (region block,
 * age gate, removed video). Distinct from the generic [Res.string.trailer_no_playable_stream]
 * message so users understand it isn't a bug in the app.
 */
private suspend fun trailerUnavailableMessage(reason: TrailerUnavailableReason): String = when (reason) {
    TrailerUnavailableReason.REGION_BLOCKED -> getString(Res.string.trailer_unavailable_region)
    TrailerUnavailableReason.AGE_RESTRICTED -> getString(Res.string.trailer_unavailable_age_restricted)
    TrailerUnavailableReason.REMOVED_OR_PRIVATE -> getString(Res.string.trailer_unavailable_removed)
    TrailerUnavailableReason.UNKNOWN -> getString(Res.string.trailer_no_playable_stream)
}

/** Only an unrecognized failure might succeed on retry; YouTube's own verdicts never change. */
private fun TrailerUnavailableReason.isRetryable(): Boolean = this == TrailerUnavailableReason.UNKNOWN

private fun String.isNativeAnimeMetaId(): Boolean =
    startsWith("kitsu:", ignoreCase = true) ||
        startsWith("mal:", ignoreCase = true) ||
        startsWith("myanimelist:", ignoreCase = true) ||
        startsWith("al:", ignoreCase = true) ||
        startsWith("anilist:", ignoreCase = true) ||
        startsWith("anidb:", ignoreCase = true) ||
        startsWith("simkl:", ignoreCase = true)

private fun String.isBareNumericId(): Boolean =
    isNotBlank() && all(Char::isDigit)

private fun extractImdbId(value: String?): String? =
    value
        ?.trim()
        ?.split(':', '/', '?', '&')
        ?.firstOrNull { part -> part.startsWith("tt", ignoreCase = true) }
        ?.takeIf { it.length > 2 }

private fun extractTmdbId(value: String?): Int? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    return trimmed
        .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.substringBefore(':')
        ?.substringBefore('/')
        ?.toIntOrNull()
}

private fun MetaDetails.toMetaPreview(): MetaPreview =
    MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        ageRating = ageRating,
        runtime = runtime,
        genres = genres,
    )

/** Builds a library-monitor target from the meta's stremio id + type (year parsed from releaseInfo). */
private fun MetaDetails.toMonitorTarget(): MonitorTarget {
    val imdb = id.takeIf { it.startsWith("tt") }
    val tmdb = tmdbId ?: id.removePrefix("tmdb:").toIntOrNull().takeIf { id.startsWith("tmdb:") }
    val kitsu = id.removePrefix("kitsu:").toIntOrNull().takeIf { id.startsWith("kitsu:") }
    val mal = id.removePrefix("mal:").toIntOrNull().takeIf { id.startsWith("mal:") }
    val parsedYear = Regex("(19|20)\\d{2}").find(releaseInfo.orEmpty())?.value?.toIntOrNull()
    return MonitorTarget(
        contentId = id,
        contentType = if (type.equals("movie", ignoreCase = true)) "movie" else "series",
        tmdbId = tmdb,
        imdbId = imdb,
        kitsuId = kitsu,
        malId = mal,
        isAnime = type.equals("anime", ignoreCase = true) || kitsu != null || mal != null,
        title = name,
        year = parsedYear,
        poster = poster,
        background = background,
    )
}

private fun LazyListScope.configuredMetaSectionItems(
    settings: MetaScreenSettingsUiState,
    meta: MetaDetails,
    isTablet: Boolean,
    contentHorizontalPadding: Dp,
    contentMaxWidth: Dp,
    desktopTwoColumnLayout: Boolean = false,
    playButtonLabel: String,
    isSaved: Boolean,
    libraryName: String,
    isWatched: Boolean,
    onPrimaryPlayClick: () -> Unit,
    onPrimaryPlayLongClick: (() -> Unit)?,
    onRandomEpisodeClick: (() -> Unit)?,
    onSaveClick: () -> Unit,
    onSaveLongClick: (() -> Unit)?,
    isMonitored: Boolean = false,
    onMonitorClick: (() -> Unit)? = null,
    onWatchedClick: () -> Unit,
    onRateClick: (() -> Unit)? = null,
    ratingProviderName: String? = null,
    showManualPlayOption: Boolean,
    preferredEpisodeSeasonNumber: Int?,
    preferredEpisodeNumber: Int?,
    hasProductionSection: Boolean,
    hasTrailersSection: Boolean,
    hasEpisodes: Boolean,
    hasAdditionalInfoSection: Boolean,
    hasCollectionSection: Boolean,
    hasMoreLikeThisSection: Boolean,
    shouldShowComments: Boolean,
    comments: List<TraktCommentReview>,
    isCommentsLoading: Boolean,
    isCommentsLoadingMore: Boolean,
    commentsCurrentPage: Int,
    commentsPageCount: Int,
    commentsError: String?,
    episodeImdbRatings: Map<Pair<Int, Int>, Double>,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentClick: (TraktCommentReview) -> Unit,
    onTrailerClick: (MetaTrailer) -> Unit,
    progressByVideoId: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    blurUnwatchedEpisodes: Boolean,
    onEpisodeClick: (MetaVideo) -> Unit,
    onEpisodeLongPress: (MetaVideo) -> Unit,
    onSeasonLongPress: (Int) -> Unit,
    onOpenMeta: ((MetaPreview) -> Unit)?,
    onCastClick: ((MetaPerson, String?) -> Unit)?,
    onCompanyClick: ((MetaCompany, String) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    tvFocus: MetaTvFocusInfo = MetaTvFocusInfo(),
    externalSelectedSeason: Int? = null,
    onSeasonSelected: ((Int) -> Unit)? = null,
) {
    val enabledItems = settings.items.filter { it.enabled }
    fun sectionHasContent(key: MetaScreenSectionKey): Boolean =
        metaSectionHasContent(
            key = key,
            meta = meta,
            hasProductionSection = hasProductionSection,
            hasTrailersSection = hasTrailersSection,
            hasEpisodes = hasEpisodes,
            hasAdditionalInfoSection = hasAdditionalInfoSection,
            hasCollectionSection = hasCollectionSection,
            hasMoreLikeThisSection = hasMoreLikeThisSection,
            shouldShowComments = shouldShowComments,
            comments = comments,
            isCommentsLoading = isCommentsLoading,
            commentsError = commentsError,
        )

    fun addSectionItem(
        key: String,
        sectionItems: List<MetaScreenSectionItem>,
        forceTabLayout: Boolean = settings.tabLayout,
        desktopTwoColumn: Boolean = false,
    ) {
        item(key = key) {
            DetailSectionContainer(
                horizontalPadding = contentHorizontalPadding,
                contentMaxWidth = contentMaxWidth,
            ) {
                ConfiguredMetaSections(
                    settings = settings.copy(
                        items = sectionItems,
                        tabLayout = forceTabLayout,
                    ),
                    meta = meta,
                    isTablet = isTablet,
                    desktopTwoColumnLayout = desktopTwoColumn,
                    playButtonLabel = playButtonLabel,
                    isSaved = isSaved,
                    libraryName = libraryName,
                    isWatched = isWatched,
                    onPrimaryPlayClick = onPrimaryPlayClick,
                    onPrimaryPlayLongClick = onPrimaryPlayLongClick,
                    onRandomEpisodeClick = onRandomEpisodeClick,
                    onSaveClick = onSaveClick,
                    onSaveLongClick = onSaveLongClick,
                    isMonitored = isMonitored,
                    onMonitorClick = onMonitorClick,
                    onWatchedClick = onWatchedClick,
                    onRateClick = onRateClick,
                    ratingProviderName = ratingProviderName,
                    showManualPlayOption = showManualPlayOption,
                    preferredEpisodeSeasonNumber = preferredEpisodeSeasonNumber,
                    preferredEpisodeNumber = preferredEpisodeNumber,
                    hasProductionSection = hasProductionSection,
                    hasTrailersSection = hasTrailersSection,
                    hasEpisodes = hasEpisodes,
                    hasAdditionalInfoSection = hasAdditionalInfoSection,
                    hasCollectionSection = hasCollectionSection,
                    hasMoreLikeThisSection = hasMoreLikeThisSection,
                    shouldShowComments = shouldShowComments,
                    comments = comments,
                    isCommentsLoading = isCommentsLoading,
                    isCommentsLoadingMore = isCommentsLoadingMore,
                    commentsCurrentPage = commentsCurrentPage,
                    commentsPageCount = commentsPageCount,
                    commentsError = commentsError,
                    episodeImdbRatings = episodeImdbRatings,
                    onRetryComments = onRetryComments,
                    onLoadMoreComments = onLoadMoreComments,
                    onCommentClick = onCommentClick,
                    onTrailerClick = onTrailerClick,
                    progressByVideoId = progressByVideoId,
                    watchedKeys = watchedKeys,
                    blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                    onEpisodeClick = onEpisodeClick,
                    onEpisodeLongPress = onEpisodeLongPress,
                    onSeasonLongPress = onSeasonLongPress,
                    onOpenMeta = onOpenMeta,
                    onCastClick = onCastClick,
                    onCompanyClick = onCompanyClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    tvFocus = tvFocus,
                    externalSelectedSeason = externalSelectedSeason,
                    onSeasonSelected = onSeasonSelected,
                )
            }
        }
    }

    if (!settings.tabLayout) {
        val visibleItems = enabledItems
            .filter { sectionHasContent(it.key) }
        if (desktopTwoColumnLayout && visibleItems.isNotEmpty()) {
            addSectionItem(
                key = "detail-desktop-sections",
                sectionItems = visibleItems,
                forceTabLayout = false,
                desktopTwoColumn = true,
            )
        } else {
            visibleItems.forEach { section ->
                addSectionItem(
                    key = "detail-section-${section.key.name}",
                    sectionItems = listOf(section),
                    forceTabLayout = false,
                )
            }
        }
        return
    }

    val processedGroups = mutableSetOf<Int>()
    enabledItems.forEach { section ->
        val groupId = section.tabGroup
        if (groupId == null) {
            if (sectionHasContent(section.key)) {
                addSectionItem(
                    key = "detail-section-${section.key.name}",
                    sectionItems = listOf(section),
                    forceTabLayout = true,
                )
            }
        } else if (groupId !in processedGroups) {
            processedGroups.add(groupId)
            val groupMembers = enabledItems.filter { item ->
                item.tabGroup == groupId && sectionHasContent(item.key)
            }
            if (groupMembers.isNotEmpty()) {
                addSectionItem(
                    key = "detail-section-group-$groupId",
                    sectionItems = groupMembers,
                    forceTabLayout = groupMembers.size > 1,
                )
            }
        }
    }
}

@Composable
private fun DetailSectionContainer(
    horizontalPadding: Dp,
    contentMaxWidth: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .padding(bottom = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (contentMaxWidth == Dp.Unspecified) {
                        Modifier
                    } else {
                        Modifier.widthIn(max = contentMaxWidth)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private fun metaSectionHasContent(
    key: MetaScreenSectionKey,
    meta: MetaDetails,
    hasProductionSection: Boolean,
    hasTrailersSection: Boolean,
    hasEpisodes: Boolean,
    hasAdditionalInfoSection: Boolean,
    hasCollectionSection: Boolean,
    hasMoreLikeThisSection: Boolean,
    shouldShowComments: Boolean,
    comments: List<TraktCommentReview>,
    isCommentsLoading: Boolean,
    commentsError: String?,
): Boolean =
    when (key) {
        MetaScreenSectionKey.ACTIONS -> true
        MetaScreenSectionKey.OVERVIEW -> true
        MetaScreenSectionKey.PRODUCTION -> hasProductionSection
        MetaScreenSectionKey.CAST -> meta.cast.isNotEmpty()
        MetaScreenSectionKey.COMMENTS -> shouldShowComments && (isCommentsLoading || comments.isNotEmpty() || !commentsError.isNullOrBlank())
        MetaScreenSectionKey.TRAILERS -> hasTrailersSection
        MetaScreenSectionKey.EPISODES -> hasEpisodes
        MetaScreenSectionKey.DETAILS -> hasAdditionalInfoSection
        MetaScreenSectionKey.COLLECTION -> !hasEpisodes && hasCollectionSection
        MetaScreenSectionKey.MORE_LIKE_THIS -> hasMoreLikeThisSection
    }

private const val DETAIL_TRAILER_SELECTOR = Int.MIN_VALUE
private const val DETAIL_COLLECTION_SELECTOR = Int.MAX_VALUE - 1
private const val DETAIL_MORE_LIKE_THIS_SELECTOR = Int.MAX_VALUE

// Brief pause before a clicked hero trailer starts, so the native surface can attach and
// decode its first frame while paused (mirrors the automatic path's longer wait). Without
// it a fresh attach starts playing before it can paint and never reveals.
private const val HERO_TRAILER_MANUAL_PREBUFFER_MS = 750L

private enum class MetaTvSectionKind {
    ACTIONS, COMMENTS, TRAILERS, SEASONS, EPISODES, COLLECTION, MORE_LIKE_THIS
}

private data class MetaTvSection(
    val kind: MetaTvSectionKind,
    val lazyItemIndex: Int,
    val itemCount: Int,
    val isVerticalEpisodeList: Boolean = false,
    val onEnter: (Int) -> Unit,
)

private data class MetaTvFocusInfo(
    /** 0 = Play, 1+ = the secondary action at that offset. Null when the row is unfocused. */
    val focusedActionIndex: Int? = null,
    val focusedCommentsIndex: Int? = null,
    val focusedTrailersIndex: Int? = null,
    val focusedSeasonIndex: Int? = null,
    val focusedEpisodeIndex: Int? = null,
    val focusedCollectionIndex: Int? = null,
    val focusedMoreLikeThisIndex: Int? = null,
)

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun ConfiguredMetaSections(
    settings: MetaScreenSettingsUiState,
    meta: MetaDetails,
    isTablet: Boolean,
    desktopTwoColumnLayout: Boolean = false,
    playButtonLabel: String,
    isSaved: Boolean,
    libraryName: String,
    isWatched: Boolean,
    onPrimaryPlayClick: () -> Unit,
    onPrimaryPlayLongClick: (() -> Unit)?,
    onRandomEpisodeClick: (() -> Unit)?,
    onSaveClick: () -> Unit,
    onSaveLongClick: (() -> Unit)?,
    isMonitored: Boolean = false,
    onMonitorClick: (() -> Unit)? = null,
    onWatchedClick: () -> Unit,
    onRateClick: (() -> Unit)? = null,
    ratingProviderName: String? = null,
    showManualPlayOption: Boolean,
    preferredEpisodeSeasonNumber: Int?,
    preferredEpisodeNumber: Int?,
    hasProductionSection: Boolean,
    hasTrailersSection: Boolean,
    hasEpisodes: Boolean,
    hasAdditionalInfoSection: Boolean,
    hasCollectionSection: Boolean,
    hasMoreLikeThisSection: Boolean,
    shouldShowComments: Boolean,
    comments: List<TraktCommentReview>,
    isCommentsLoading: Boolean,
    isCommentsLoadingMore: Boolean,
    commentsCurrentPage: Int,
    commentsPageCount: Int,
    commentsError: String?,
    episodeImdbRatings: Map<Pair<Int, Int>, Double>,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onCommentClick: (TraktCommentReview) -> Unit,
    onTrailerClick: (MetaTrailer) -> Unit,
    progressByVideoId: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    blurUnwatchedEpisodes: Boolean,
    onEpisodeClick: (MetaVideo) -> Unit,
    onEpisodeLongPress: (MetaVideo) -> Unit,
    onSeasonLongPress: (Int) -> Unit,
    onOpenMeta: ((MetaPreview) -> Unit)?,
    onCastClick: ((MetaPerson, String?) -> Unit)?,
    onCompanyClick: ((MetaCompany, String) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    tvFocus: MetaTvFocusInfo = MetaTvFocusInfo(),
    externalSelectedSeason: Int? = null,
    onSeasonSelected: ((Int) -> Unit)? = null,
) {
    val enabledItems = settings.items.filter { it.enabled }

    // Helper to check if a section actually has content to show
    val sectionHasContent: (MetaScreenSectionKey) -> Boolean = { key ->
        when (key) {
            MetaScreenSectionKey.ACTIONS -> true
            MetaScreenSectionKey.OVERVIEW -> true
            MetaScreenSectionKey.PRODUCTION -> hasProductionSection
            MetaScreenSectionKey.CAST -> meta.cast.isNotEmpty()
            MetaScreenSectionKey.COMMENTS -> shouldShowComments && (isCommentsLoading || comments.isNotEmpty() || !commentsError.isNullOrBlank())
            MetaScreenSectionKey.TRAILERS -> hasTrailersSection
            MetaScreenSectionKey.EPISODES -> hasEpisodes
            MetaScreenSectionKey.DETAILS -> hasAdditionalInfoSection
            MetaScreenSectionKey.COLLECTION -> !hasEpisodes && hasCollectionSection
            MetaScreenSectionKey.MORE_LIKE_THIS -> hasMoreLikeThisSection
        }
    }

    @Composable
    fun RenderSection(
        key: MetaScreenSectionKey,
        showHeader: Boolean = true,
        compactDetails: Boolean = false,
    ) {
        when (key) {
            MetaScreenSectionKey.ACTIONS -> {
                val isSeriesMeta = meta.type.trim().lowercase() in setOf("series", "show", "tv", "tvshow")
                DetailActionButtons(
                    playLabel = playButtonLabel,
                    secondaryActions = listOfNotNull(
                        onRandomEpisodeClick?.let { playRandom ->
                            DetailSecondaryAction(
                                label = stringResource(Res.string.hero_play_random_episode),
                                icon = Icons.Default.PlayArrow,
                                onClick = playRandom,
                            )
                        },
                        DetailSecondaryAction(
                            label = if (isSeriesMeta) {
                                stringResource(
                                    if (isWatched) {
                                        Res.string.hero_mark_series_unwatched
                                    } else {
                                        Res.string.hero_mark_series_watched
                                    },
                                )
                            } else if (isWatched) {
                                stringResource(Res.string.hero_mark_unwatched)
                            } else {
                                stringResource(Res.string.hero_mark_watched)
                            },
                            icon = if (isWatched) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.CheckCircleOutline
                            },
                            isActive = isWatched,
                            onClick = onWatchedClick,
                        ),
                        DetailSecondaryAction(
                            label = stringResource(
                                if (isSaved) {
                                    Res.string.hero_remove_from_named_library
                                } else {
                                    Res.string.hero_add_to_named_library
                                },
                                libraryName,
                            ),
                            icon = if (isSaved) {
                                Icons.Default.Check
                            } else {
                                Icons.Default.Add
                            },
                            isActive = isSaved,
                            onClick = onSaveClick,
                            onLongClick = onSaveLongClick,
                        ),
                        onMonitorClick?.let { monitor ->
                            DetailSecondaryAction(
                                label = stringResource(
                                    if (isMonitored) {
                                        Res.string.hero_remove_from_local_library
                                    } else {
                                        Res.string.hero_add_to_local_library
                                    },
                                ),
                                icon = Icons.Default.CloudDownload,
                                isActive = isMonitored,
                                onClick = monitor,
                            )
                        },
                        onRateClick?.let { rate ->
                            DetailSecondaryAction(
                                label = stringResource(
                                    Res.string.hero_rate_on_provider,
                                    ratingProviderName.orEmpty(),
                                ),
                                icon = Icons.Default.Star,
                                onClick = rate,
                            )
                        },
                    ),
                    isTablet = isTablet,
                    onPlayClick = onPrimaryPlayClick,
                    onPlayLongClick = if (showManualPlayOption) onPrimaryPlayLongClick else null,
                    focusedActionIndex = tvFocus.focusedActionIndex,
                )
            }
            MetaScreenSectionKey.OVERVIEW -> {
                DetailMetaInfo(meta = meta)
            }
            MetaScreenSectionKey.PRODUCTION -> {
                if (hasProductionSection) {
                    DetailProductionSection(meta = meta, showHeader = showHeader, onCompanyClick = onCompanyClick)
                }
            }
            MetaScreenSectionKey.CAST -> {
                DetailCastSection(
                    cast = meta.cast,
                    showHeader = showHeader,
                    onCastClick = onCastClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
            MetaScreenSectionKey.COMMENTS -> {
                if (shouldShowComments && (isCommentsLoading || comments.isNotEmpty() || !commentsError.isNullOrBlank())) {
                    DetailCommentsSection(
                        comments = comments,
                        isLoading = isCommentsLoading,
                        isLoadingMore = isCommentsLoadingMore,
                        canLoadMore = commentsCurrentPage < commentsPageCount,
                        error = commentsError,
                        onRetry = onRetryComments,
                        onLoadMore = onLoadMoreComments,
                        onCommentClick = onCommentClick,
                        showHeader = showHeader,
                        focusedItemIndex = tvFocus.focusedCommentsIndex,
                    )
                }
            }
            MetaScreenSectionKey.TRAILERS -> {
                if (hasTrailersSection) {
                    DetailTrailersSection(
                        trailers = meta.trailers,
                        onTrailerClick = onTrailerClick,
                        showHeader = showHeader,
                        focusedItemIndex = tvFocus.focusedTrailersIndex,
                    )
                }
            }
            MetaScreenSectionKey.EPISODES -> {
                if (hasEpisodes) {
                    DetailSeriesContent(
                        meta = meta,
                        showHeader = showHeader,
                        preferredSeasonNumber = preferredEpisodeSeasonNumber,
                        preferredEpisodeNumber = preferredEpisodeNumber,
                        episodeCardStyle = MetaEpisodeCardStyle.Horizontal,
                        progressByVideoId = progressByVideoId,
                        watchedKeys = watchedKeys,
                        episodeRatings = episodeImdbRatings,
                        blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                        onEpisodeClick = onEpisodeClick,
                        onEpisodeLongPress = onEpisodeLongPress,
                        onSeasonLongPress = onSeasonLongPress,
                        externalSelectedSeason = externalSelectedSeason,
                        onSeasonSelected = onSeasonSelected,
                        focusedSeasonIndex = tvFocus.focusedSeasonIndex,
                        focusedEpisodeIndex = tvFocus.focusedEpisodeIndex,
                        compactDesktopLayout = desktopTwoColumnLayout,
                    )
                }
            }
            MetaScreenSectionKey.DETAILS -> {
                if (hasAdditionalInfoSection) {
                    DetailAdditionalInfoSection(
                        meta = meta,
                        showHeader = showHeader,
                        compact = compactDetails,
                    )
                }
            }
            MetaScreenSectionKey.COLLECTION -> {
                if (!hasEpisodes && hasCollectionSection) {
                    DetailPosterRailSection(
                        title = meta.collectionName.orEmpty(),
                        items = meta.collectionItems,
                        watchedKeys = watchedKeys,
                        showHeader = showHeader,
                        onPosterClick = onOpenMeta,
                        focusedItemIndex = tvFocus.focusedCollectionIndex,
                    )
                }
            }
            MetaScreenSectionKey.MORE_LIKE_THIS -> {
                if (hasMoreLikeThisSection) {
                    val sourceLabel = when (meta.moreLikeThisSource) {
                        MoreLikeThisSource.TMDB -> stringResource(Res.string.detail_more_like_this_powered_by_tmdb)
                        MoreLikeThisSource.TRAKT -> stringResource(Res.string.detail_more_like_this_powered_by_trakt)
                        null -> null
                    }
                    DetailPosterRailSection(
                        title = stringResource(Res.string.details_more_like_this),
                        items = meta.moreLikeThis,
                        watchedKeys = watchedKeys,
                        showHeader = showHeader,
                        sourceLabel = sourceLabel,
                        onPosterClick = onOpenMeta,
                        focusedItemIndex = tvFocus.focusedMoreLikeThisIndex,
                    )
                }
            }
        }
    }

    if (!settings.tabLayout && desktopTwoColumnLayout) {
        val visibleKeys = enabledItems
            .map { it.key }
            .filter(sectionHasContent)
            .toSet()
        val desktopMainKeys = listOf(
            MetaScreenSectionKey.COMMENTS,
        ).filter { it in visibleKeys }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            desktopMainKeys.forEach { key ->
                RenderSection(key)
            }
        }
    } else if (!settings.tabLayout) {
        // Standard mode: render sections individually in order
        enabledItems.forEach { section -> RenderSection(section.key) }
    } else {
        // Tab layout mode: group sections by tabGroup, render grouped ones as tabs
        val processedGroups = mutableSetOf<Int>()

        enabledItems.forEach { section ->
            val groupId = section.tabGroup
            if (groupId == null) {
                // Standalone section
                RenderSection(section.key)
            } else if (groupId !in processedGroups) {
                // First encounter of this group — render the whole tabbed group
                processedGroups.add(groupId)
                val groupMembers = enabledItems
                    .filter { it.tabGroup == groupId && sectionHasContent(it.key) }
                if (groupMembers.isEmpty()) return@forEach
                if (groupMembers.size == 1) {
                    // Only one member with content — render standalone
                    RenderSection(groupMembers.first().key)
                } else {
                    TabbedSectionGroup(
                        tabs = groupMembers.map { it.key to it.title },
                    ) { activeKey ->
                        RenderSection(activeKey, showHeader = false)
                    }
                }
            }
            // else: already processed as part of group, skip
        }
    }
}

@Composable
private fun TabbedSectionGroup(
    tabs: List<Pair<MetaScreenSectionKey, String>>,
    content: @Composable (MetaScreenSectionKey) -> Unit,
) {
    if (tabs.isEmpty()) return

    var selectedIndex by remember { mutableIntStateOf(0) }
    val clampedIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    if (clampedIndex != selectedIndex) selectedIndex = clampedIndex

    val headerColor = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Tab row using the same style as DetailSectionTitle
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val titleSize = if (maxWidth >= 720.dp) 22.sp else 20.sp
            val headerStyle = MaterialTheme.typography.titleLarge.copy(
                fontSize = titleSize,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, (_, title) ->
                    if (index > 0) {
                        Text(
                            text = "|",
                            style = headerStyle,
                            color = headerColor.copy(alpha = 0.45f),
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }

                    Text(
                        text = title,
                        style = headerStyle,
                        color = if (index == selectedIndex) {
                            headerColor
                        } else {
                            headerColor.copy(alpha = 0.55f)
                        },
                        maxLines = 1,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { selectedIndex = index },
                    )
                }
            }
        }

        // Content with crossfade
        Crossfade(
            targetState = tabs[selectedIndex].first,
            animationSpec = tween(durationMillis = 200),
            label = "tabbedSectionCrossfade",
        ) { activeKey ->
            content(activeKey)
        }
    }
}

private fun detailTabletContentMaxWidth(maxWidth: Dp, isTablet: Boolean): Dp =
    if (!isTablet) {
        maxWidth
    } else {
        (maxWidth * 0.6f).coerceIn(520.dp, 680.dp)
    }
