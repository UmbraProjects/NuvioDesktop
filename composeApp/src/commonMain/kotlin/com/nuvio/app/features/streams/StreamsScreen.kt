package com.nuvio.app.features.streams

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.desktopHorizontalListNavigation
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.secondaryClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridSourceInspector
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.isSafeVideoDownloadCandidate
import com.nuvio.app.features.librarypvr.ManualGrabSession
import com.nuvio.app.features.librarypvr.ManualInspectResult
import com.nuvio.app.features.librarypvr.SeasonPackGrabService
import com.nuvio.app.features.librarypvr.SeasonPackInspectResult
import com.nuvio.app.features.librarypvr.SeasonPackSelectionDialog
import com.nuvio.app.features.librarypvr.StreamPackGrabService
import com.nuvio.app.features.librarypvr.LibraryDestinationFolders
import com.nuvio.app.features.librarypvr.LibraryFileNaming
import com.nuvio.app.features.librarypvr.LibraryPvrRepository
import com.nuvio.app.features.librarypvr.ReleaseYearResolver
import com.nuvio.app.features.librarypvr.videoExtension
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.locallibrary.LocalFolderType
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import kotlinx.coroutines.CoroutineScope
import com.nuvio.app.features.player.PlaybackStartTrace
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.isDesktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.rounded.Refresh
import com.nuvio.app.core.ui.accentBrush

// ---------------------------------------------------------------------------
// Streams Screen
// ---------------------------------------------------------------------------

internal fun manualLibraryRelativePath(
    title: String,
    releaseYear: Int?,
    isEpisode: Boolean,
    isAnimeFolder: Boolean,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    extension: String,
    existingFolderNames: List<String> = emptyList(),
): String? = when {
    !isEpisode -> LibraryFileNaming.movieRelativePath(
        title,
        releaseYear,
        extension,
        existingFolderNames,
    )
    episodeNumber == null -> null
    isAnimeFolder -> LibraryFileNaming.animeEpisodeRelativePath(
        title,
        releaseYear,
        episodeNumber,
        extension,
        existingFolderNames,
    )
    seasonNumber == null -> null
    else -> LibraryFileNaming.episodeRelativePath(
        title = title,
        year = releaseYear,
        season = seasonNumber,
        episode = episodeNumber,
        episodeTitle = episodeTitle,
        extension = extension,
        existingFolderNames = existingFolderNames,
    )
}

@Composable
fun StreamsScreen(
    type: String,
    videoId: String,
    parentMetaId: String,
    parentMetaType: String,
    title: String,
    logo: String? = null,
    poster: String? = null,
    background: String? = null,
    releaseYear: Int? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeTitle: String? = null,
    episodeThumbnail: String? = null,
    resumePositionMs: Long? = null,
    resumeProgressFraction: Float? = null,
    manualSelection: Boolean = false,
    preferLocalStreams: Boolean = false,
    startFromBeginning: Boolean = false,
    onStreamSelected: (stream: StreamItem, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit = { _, _, _ -> },
    onStreamActionOpen: (
        stream: StreamItem,
        openExternally: Boolean,
        resumePositionMs: Long?,
        resumeProgressFraction: Float?,
    ) -> Unit = { _, _, _, _ -> },
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Splits the click → visible-screen gap into "work before composition finished" vs "work
    // between composition and the first painted frame", so a stall that leaves the user staring
    // at the previous screen points at a culprit instead of a range. markPending no-ops once a
    // scrape trace is live, so these cost nothing on re-entry.
    remember(videoId) { PlaybackStartTrace.markPendingOrActive("streamsScreen:composeStart"); Unit }
    LaunchedEffect(videoId) {
        withFrameNanos { }
        PlaybackStartTrace.markPendingOrActive("streamsScreen:firstFrame")
    }

    val tracedStreamSelection: (StreamItem, Long?, Float?) -> Unit = { stream, position, fraction ->
        PlaybackStartTrace.markPendingOrActive("sourceSelected")
        onStreamSelected(stream, position, fraction)
    }
    val uiState by StreamsRepository.uiState.collectAsStateWithLifecycle()
    val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val debridSettings by DebridSettingsRepository.uiState.collectAsStateWithLifecycle()
    val watchProgressUiState by WatchProgressRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            PlayerSettingsRepository.ensureLoaded()
            DebridSettingsRepository.ensureLoaded()
            WatchProgressRepository.ensureLoaded()
        }
    }
    val isEpisode = seasonNumber != null && episodeNumber != null
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val downloadScope = rememberCoroutineScope()
    val streamLinkCopiedText = stringResource(Res.string.streams_link_copied)
    val noDirectStreamLinkText = stringResource(Res.string.streams_no_direct_link)
    val browserDownloadFailedText = stringResource(Res.string.streams_browser_download_failed)
    val unsupportedDownloadFormatText = stringResource(Res.string.downloads_enqueue_unsupported_format)
    var streamActionsTarget by remember(videoId) { mutableStateOf<StreamItem?>(null) }
    // A stream waiting on the user to choose between multiple compatible library roots.
    var libraryFolderPrompt by remember(videoId) { mutableStateOf<PendingLibraryDownload?>(null) }
    // An inspected season pack waiting on the user to confirm which files to download.
    var packSelectionPrompt by remember(videoId) { mutableStateOf<PackSelectionPrompt?>(null) }
    var preferredFilterApplied by remember(videoId) { mutableStateOf(false) }
    var autoPlayOverlayLogoLoadError by remember(logo) { mutableStateOf(false) }
    val keyboardFocusRequester = remember { FocusRequester() }
    val providerListState = rememberLazyListState()
    val streamListState = rememberLazyListState()
    var focusedStreamIndex by remember(videoId) { mutableIntStateOf(0) }
    // Bumped by every navigation key press, and by nothing else. See the scroll-to-focus effect.
    var keyboardNavTick by remember(videoId) { mutableIntStateOf(0) }
    val autoPlayOverlayLogoUrl = logo?.takeIf { it.isNotBlank() }
    val storedProgress = if (startFromBeginning) {
        null
    } else {
        watchProgressUiState.byVideoId[videoId]
    }
    val storedProgressFraction = storedProgress
        ?.takeIf { it.isResumable }
        ?.progressPercent
        ?.takeIf { it > 0f }
        ?.let { explicitPercent -> (explicitPercent / 100f).coerceIn(0f, 1f) }
    val effectiveResumeProgressFraction = if (startFromBeginning) {
        null
    } else {
        resumeProgressFraction
        ?.takeIf { it > 0f && it < 0.90f }
        ?.coerceIn(0f, 1f)
        ?: storedProgressFraction
    }
    val effectiveResumePositionMs = if (effectiveResumeProgressFraction != null) {
        null
    } else {
        if (startFromBeginning) {
            null
        } else {
            (resumePositionMs ?: storedProgress?.takeIf { it.isResumable }?.lastPositionMs)
                ?.takeIf { it > 0L }
                ?.takeUnless { positionMs ->
                    val knownDurationMs = storedProgress?.durationMs ?: 0L
                    knownDurationMs > 0L &&
                        positionMs.toDouble() / knownDurationMs.toDouble() >= 0.90
                }
        }
    }
    val screenScoreProfile by StreamScoreRepository.uiState.collectAsStateWithLifecycle()
    val htpcGroup = rememberHtpcSourceGroup(
        groups = uiState.groups,
        profile = screenScoreProfile,
        isEpisode = isEpisode,
        contentId = parentMetaId,
        contentType = type,
    )
    val providerIds = remember(uiState.groups, htpcGroup) {
        listOf<String?>(null) +
            listOfNotNull(htpcGroup?.addonId) +
            uiState.groups
                .filter { it.streams.isNotEmpty() || it.isLoading }
                .map { it.addonId }
    }
    val scoreSortedGroups = rememberScoreSortedGroups(
        groups = uiState.filteredGroups,
        profile = screenScoreProfile,
        isEpisode = isEpisode,
        contentId = parentMetaId,
        contentType = type,
    )
    // The HTPC tab is already ranked and deduped, so it bypasses the per-addon sort entirely.
    // uiState.filteredGroups cannot resolve its synthetic id and would come back empty.
    val displayGroups = if (uiState.selectedFilter == StreamSourceMerge.HTPC_ADDON_ID) {
        listOfNotNull(htpcGroup)
    } else {
        scoreSortedGroups
    }
    val selectableStreams = remember(displayGroups, debridSettings.canResolvePlayableLinks) {
        orderedStreams(displayGroups)
            .filter { it.isSelectableForPlayback(debridSettings.canResolvePlayableLinks) }
    }
    val focusedStream = selectableStreams.getOrNull(focusedStreamIndex)

    LaunchedEffect(type, videoId, seasonNumber, episodeNumber, manualSelection) {
        StreamsRepository.load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            title = title,
            season = seasonNumber,
            episode = episodeNumber,
            manualSelection = manualSelection,
            preferLocalStreams = preferLocalStreams,
        )
    }

    LaunchedEffect(uiState.groups, storedProgress?.providerAddonId, preferredFilterApplied) {
        if (preferredFilterApplied) return@LaunchedEffect
        val preferredAddonId = storedProgress?.providerAddonId ?: return@LaunchedEffect
        if (uiState.groups.any { it.addonId == preferredAddonId }) {
            StreamsRepository.selectFilter(preferredAddonId)
            preferredFilterApplied = true
        }
    }

    // Turning the tab off (or moving to a title with no streams at all) while it is the active
    // filter would otherwise strand the list on a chip that no longer exists.
    LaunchedEffect(htpcGroup == null, uiState.selectedFilter) {
        if (htpcGroup == null && uiState.selectedFilter == StreamSourceMerge.HTPC_ADDON_ID) {
            StreamsRepository.selectFilter(null)
        }
    }

    LaunchedEffect(isDesktop, videoId) {
        if (isDesktop) {
            keyboardFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(selectableStreams.size) {
        focusedStreamIndex = focusedStreamIndex.coerceIn(
            minimumValue = 0,
            maximumValue = (selectableStreams.size - 1).coerceAtLeast(0),
        )
    }

    // Keyed on the selected chip, plus whether that chip exists yet — not on providerIds itself,
    // which changes every time an addon starts or finishes loading and dragged the chip row back
    // under the user mid-scroll for as long as sources kept arriving.
    val selectedFilterIndex = providerIds.indexOf(uiState.selectedFilter)
    LaunchedEffect(uiState.selectedFilter, selectedFilterIndex >= 0) {
        val filterIndex = selectedFilterIndex
        if (filterIndex < 0) return@LaunchedEffect
        val layoutInfo = providerListState.layoutInfo
        val isFullyVisible = layoutInfo.visibleItemsInfo.any { item ->
            item.index == filterIndex &&
                item.offset >= layoutInfo.viewportStartOffset &&
                item.offset + item.size <= layoutInfo.viewportEndOffset
        }
        if (!isFullyVisible) {
            providerListState.animateScrollToItem(filterIndex)
        }
    }

    // Switching chips replaces the whole list, so the old offset means nothing against the new one.
    LaunchedEffect(uiState.selectedFilter) {
        streamListState.scrollToItem(0)
    }

    // Follow the keyboard cursor, and *only* the keyboard cursor.
    //
    // This used to key on the focused stream itself, which made every late-arriving source scroll
    // the list: a new group re-sorts the list, a different stream lands at focusedStreamIndex, and
    // the effect read that as the user having moved. Nothing but a nav key bumps this counter, so a
    // mouse user's scroll position is now left alone for the whole scrape. focusedStream and
    // displayGroups are read inside the effect on purpose — the key press writes both the index and
    // the counter in one pass, so the restarted effect already sees the new focus.
    LaunchedEffect(keyboardNavTick) {
        if (keyboardNavTick == 0) return@LaunchedEffect
        val target = focusedStream ?: return@LaunchedEffect
        val lazyIndex = streamLazyListIndex(
            groups = displayGroups,
            showGroupHeaders = uiState.selectedFilter == null,
            target = target,
        )
        if (lazyIndex >= 0) {
            val layoutInfo = streamListState.layoutInfo
            val isFullyVisible = layoutInfo.visibleItemsInfo.any { item ->
                item.index == lazyIndex &&
                    item.offset >= layoutInfo.viewportStartOffset &&
                    item.offset + item.size <= layoutInfo.viewportEndOffset
            }
            if (!isFullyVisible) {
                streamListState.animateScrollToItem(lazyIndex)
            }
        }
    }

    // --- Season-pack grab ---
    // Anime shows are keyed by an anime-native id, which is also what decides whether the season
    // belongs in an anime folder and how its episodes are numbered on disk.
    val isAnimeContent = remember(parentMetaId, type) {
        type.equals("anime", ignoreCase = true) ||
            listOf("kitsu:", "mal:", "anilist:", "anidb:").any {
                parentMetaId.startsWith(it, ignoreCase = true)
            }
    }
    val localLibraryState by LocalLibraryRepository.uiState.collectAsStateWithLifecycle()
    val downloadFolderCandidates = remember(localLibraryState.folders, isAnimeContent, isEpisode) {
        val typeForMedia = if (isEpisode) LocalFolderType.SERIES else LocalFolderType.MOVIES
        localLibraryState.folders
            .filter { it.type == typeForMedia }
            .sortedByDescending { it.isAnime == isAnimeContent }
    }
    // The year the launching screen could see, topped up in the background.
    //
    // It arrives here from a synchronous cache read at click time, which is null for any title whose
    // details screen has not been opened this session — after a restart, that is every title. The
    // year only matters when the user reaches for Download, which is several seconds and at least
    // one deliberate menu away, so resolving it properly behind the already-running stream search
    // costs nothing and stops the first episode of a show landing in a yearless folder that every
    // later one then has to be filed into. See ReleaseYearResolver.
    var resolvedReleaseYear by remember(parentMetaId, releaseYear) { mutableStateOf(releaseYear) }
    LaunchedEffect(parentMetaId, parentMetaType, type, releaseYear) {
        if (releaseYear != null || !AppFeaturePolicy.downloadsEnabled) return@LaunchedEffect
        val metaType = parentMetaType.takeIf { it.isNotBlank() } ?: type
        ReleaseYearResolver.resolve(type = metaType, id = parentMetaId)
            ?.let { resolved -> resolvedReleaseYear = resolved }
    }

    val libraryTarget = remember(parentMetaId, parentMetaType, type, title, resolvedReleaseYear, poster, background) {
        SeasonPackGrabService.Target(
            contentId = parentMetaId,
            contentType = parentMetaType.takeIf { it.isNotBlank() } ?: type,
            title = title,
            year = resolvedReleaseYear,
            poster = poster,
            background = background,
        )
    }
    fun startSeasonDownload(stream: StreamItem, folder: LocalFolder) {
        downloadScope.launch {
            NuvioToastController.show(getString(Res.string.streams_download_season_inspecting))
            val message = when (
                val result = SeasonPackGrabService.inspect(
                    stream = stream,
                    folder = folder,
                    season = seasonNumber,
                    currentEpisode = episodeNumber,
                    target = libraryTarget,
                )
            ) {
                is SeasonPackInspectResult.Success -> {
                    val queued = SeasonPackGrabService.enqueue(
                        session = result.session,
                        target = libraryTarget,
                        folder = folder,
                    )
                    if (queued > 0) {
                        getString(Res.string.streams_download_season_queued, queued, folder.displayName)
                    } else {
                        getString(Res.string.streams_download_season_no_files)
                    }
                }
                SeasonPackInspectResult.NoVideoFiles ->
                    getString(Res.string.streams_download_season_no_files)
                SeasonPackInspectResult.InvalidLink ->
                    getString(Res.string.streams_download_season_invalid_link)
                // Carry the provider's own wording through — a bare "failed" gave no way to tell a
                // wrong provider from an expired link from a rejected request.
                is SeasonPackInspectResult.Failed -> result.message
                    ?.takeIf { it.isNotBlank() }
                    ?.let { getString(Res.string.streams_download_season_failed_reason, it) }
                    ?: getString(Res.string.streams_download_season_failed)
            }
            NuvioToastController.show(message)
        }
    }

    // Same season, different route: one magnet upload to the debrid provider instead of one addon
    // request per episode, with a confirmation dialog over the pack's real file listing.
    //
    // Direct-debrid rows (AIOStreams/torz) don't carry the pack infohash until they're resolved —
    // it only appears in the resolved URL's path — so resolve first, exactly like play/copy do, then
    // inspect the resolved stream. Without this the pack's identity is invisible in the list stream.
    /**
     * @param fallbackToSeasonSearch when this ran as a "download season" request rather than an
     * explicit pack pick. The listing is the better answer when it works, but it can fail for
     * reasons that say nothing about the season being unavailable — an uncached Premiumize source,
     * a hash that would not resolve — and dead-ending there would be worse than the slower route
     * this replaced. So a failure falls through to the per-episode search instead of just toasting.
     */
    fun startPackInspect(
        stream: StreamItem,
        folder: LocalFolder,
        fallbackToSeasonSearch: Boolean = false,
    ) {
        // Keep every phase of this asynchronous inspect -> review -> enqueue flow tied to the
        // entry that launched it, even if the surrounding streams destination is recomposed.
        val inspectedTarget = libraryTarget
        resolvePlayableStreamThen(
            stream = stream,
            season = seasonNumber,
            episode = episodeNumber,
            scope = downloadScope,
        ) { resolved ->
        downloadScope.launch {
            NuvioToastController.show(getString(Res.string.streams_download_pack_inspecting))
            suspend fun failed(message: String) {
                if (fallbackToSeasonSearch) {
                    NuvioToastController.show(getString(Res.string.streams_download_season_pack_fallback))
                    startSeasonDownload(stream, folder)
                } else {
                    NuvioToastController.show(message)
                }
            }
            when (val result = StreamPackGrabService.inspect(resolved, folder, inspectedTarget)) {
                is ManualInspectResult.Success ->
                    packSelectionPrompt = PackSelectionPrompt(result.session, folder, inspectedTarget)
                ManualInspectResult.MissingProvider ->
                    failed(getString(Res.string.streams_download_season_missing_provider))
                ManualInspectResult.NotCached ->
                    failed(getString(Res.string.streams_download_season_not_cached))
                ManualInspectResult.InvalidLink ->
                    failed(getString(Res.string.streams_download_season_invalid_link))
                ManualInspectResult.NoVideoFiles ->
                    failed(getString(Res.string.streams_download_season_no_files))
                is ManualInspectResult.Failed -> failed(
                    result.message
                        ?.takeIf { it.isNotBlank() }
                        ?.let { getString(Res.string.streams_download_season_failed_reason, it) }
                        ?: getString(Res.string.streams_download_season_failed),
                )
            }
        }
        }
    }

    fun startSingleLibraryDownload(stream: StreamItem, folder: LocalFolder) {
        resolvePlayableStreamThen(
            stream = stream,
            season = seasonNumber,
            episode = episodeNumber,
            scope = downloadScope,
        ) { playable ->
            val extension = playable.behaviorHints.filename?.videoExtension()
                ?: playable.playableDirectUrl
                    ?.substringBefore('?')
                    ?.substringBefore('#')
                    ?.substringAfterLast('/')
                    ?.videoExtension()
                ?: "mkv"
            val relativePath = manualLibraryRelativePath(
                title = title,
                releaseYear = resolvedReleaseYear,
                isEpisode = isEpisode,
                isAnimeFolder = folder.isAnime,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                extension = extension,
                // Reuse whatever folder this show already occupies here. Even with the year
                // resolved, an older download of the same show may sit under a differently shaped
                // name, and that is the folder a new episode belongs in.
                existingFolderNames = LibraryDestinationFolders.existingFolderNames(
                    folder = folder,
                    contentId = parentMetaId,
                ),
            ) ?: return@resolvePlayableStreamThen
            SeasonPackGrabService.prepareLibraryMatch(folder, libraryTarget)
            val result = DownloadsRepository.enqueueFromStream(
                contentType = type,
                videoId = videoId,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                episodeThumbnail = episodeThumbnail,
                stream = playable,
                destinationDirOverride = folder.path,
                destinationRelativePath = relativePath,
                expectedSizeBytes = playable.behaviorHints.videoSize,
                bandwidthLimitMbps = LibraryPvrRepository.currentSettings().bandwidthLimitMbps,
                isAutomaticDownload = true,
                preserveExistingFileUntilSuccess = true,
            )
            NuvioToastController.show(result.toastMessage())
        }
    }

    fun openDownloadInBrowser(stream: StreamItem) {
        resolvePlayableStreamThen(
            stream = stream,
            season = seasonNumber,
            episode = episodeNumber,
            scope = downloadScope,
        ) { playable ->
            openStreamInBrowser(
                playable,
                uriHandler,
                noDirectStreamLinkText,
                browserDownloadFailedText,
                unsupportedDownloadFormatText,
            )
        }
    }

    /** Cheap, request-free test of whether the pack listing is worth attempting for [stream]. */
    fun canListSourceContents(stream: StreamItem): Boolean =
        stream.canInspectSource && DebridSourceInspector.canInspect()

    fun startDownloadForFolder(stream: StreamItem, mode: LibraryDownloadMode, folder: LocalFolder) {
        when (mode) {
            // Both routes end with the season's episodes in the library, but the pack listing gets
            // there in one provider call where the per-episode search costs one addon query per
            // episode at 3s pacing — and cannot silently drop the episodes that release did not come
            // back for. So prefer it whenever the source can actually be listed, and keep the search
            // for everything else: usenet, sources with no torrent identity, and Real-Debrid, which
            // has no listing capability at all.
            LibraryDownloadMode.Season ->
                if (canListSourceContents(stream)) {
                    startPackInspect(stream, folder, fallbackToSeasonSearch = true)
                } else {
                    startSeasonDownload(stream, folder)
                }
            LibraryDownloadMode.Single -> startSingleLibraryDownload(stream, folder)
        }
    }

    fun routeDownload(stream: StreamItem, mode: LibraryDownloadMode) {
        if (!AppFeaturePolicy.downloadsEnabled || downloadFolderCandidates.isEmpty()) {
            // A season grab exists to file episodes into the library; handing one episode's URL to
            // the browser is not a substitute for it the way it is for a single-file download.
            if (mode == LibraryDownloadMode.Season) {
                downloadScope.launch {
                    NuvioToastController.show(getString(Res.string.streams_download_pack_no_folder))
                }
            } else {
                openDownloadInBrowser(stream)
            }
            return
        }
        val only = downloadFolderCandidates.singleOrNull()
        if (only != null) {
            startDownloadForFolder(stream, mode, only)
        } else {
            libraryFolderPrompt = PendingLibraryDownload(stream, mode)
        }
    }

    val heroArtwork = if (isEpisode) {
        episodeThumbnail ?: background ?: poster
    } else {
        background ?: poster
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (isDesktop) {
                    Modifier
                        .focusRequester(keyboardFocusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (streamActionsTarget != null || uiState.showDirectAutoPlayOverlay) {
                                return@onPreviewKeyEvent false
                            }
                            val navKey = event.navigationKey()
                            when (navKey) {
                                Key.Backspace -> {
                                    onBack()
                                    true
                                }
                                Key.DirectionUp -> {
                                    focusedStreamIndex = (focusedStreamIndex - 1).coerceAtLeast(0)
                                    keyboardNavTick++
                                    true
                                }
                                Key.DirectionDown -> {
                                    focusedStreamIndex = (focusedStreamIndex + 1)
                                        .coerceAtMost((selectableStreams.size - 1).coerceAtLeast(0))
                                    keyboardNavTick++
                                    true
                                }
                                Key.DirectionLeft, Key.DirectionRight -> {
                                    val currentFilterIndex = providerIds.indexOf(uiState.selectedFilter)
                                        .coerceAtLeast(0)
                                    val delta = if (navKey == Key.DirectionRight) 1 else -1
                                    val nextFilterIndex = (currentFilterIndex + delta)
                                        .coerceIn(0, (providerIds.size - 1).coerceAtLeast(0))
                                    if (nextFilterIndex != currentFilterIndex) {
                                        StreamsRepository.selectFilter(providerIds[nextFilterIndex])
                                        focusedStreamIndex = 0
                                    }
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    focusedStream?.let {
                                        tracedStreamSelection(
                                            it,
                                            effectiveResumePositionMs,
                                            effectiveResumeProgressFraction,
                                        )
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
        val isTabletLayout = maxWidth >= 768.dp
        val streamRowActions = StreamRowActions(
            onCopyUrl = { stream ->
                val directUrl = stream.playableDirectUrl
                if (!directUrl.isNullOrBlank()) {
                    clipboardManager.setText(AnnotatedString(directUrl))
                    NuvioToastController.show(streamLinkCopiedText)
                } else if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                    downloadScope.launch {
                        when (val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                            stream = stream,
                            season = seasonNumber,
                            episode = episodeNumber,
                        )) {
                            is DirectDebridPlayableResult.Success -> {
                                val resolvedUrl = resolved.stream.playableDirectUrl
                                if (!resolvedUrl.isNullOrBlank()) {
                                    clipboardManager.setText(AnnotatedString(resolvedUrl))
                                    NuvioToastController.show(streamLinkCopiedText)
                                } else {
                                    NuvioToastController.show(noDirectStreamLinkText)
                                }
                            }
                            else -> {
                                resolved.toastMessage()?.let { NuvioToastController.show(it) }
                                    ?: NuvioToastController.show(noDirectStreamLinkText)
                            }
                        }
                    }
                } else {
                    NuvioToastController.show(noDirectStreamLinkText)
                }
            },
            onDownload = { stream ->
                routeDownload(stream, LibraryDownloadMode.Single)
            },
            onOpenInBrowser = if (AppFeaturePolicy.downloadsEnabled) {
                { stream ->
                    resolvePlayableStreamThen(
                        stream = stream,
                        season = seasonNumber,
                        episode = episodeNumber,
                        scope = downloadScope,
                    ) { playable ->
                        openStreamInBrowser(
                            playable,
                            uriHandler,
                            noDirectStreamLinkText,
                            browserDownloadFailedText,
                            unsupportedDownloadFormatText,
                        )
                    }
                }
            } else {
                null
            },
            onOpenExternal = { stream ->
                onStreamActionOpen(
                    stream,
                    true,
                    effectiveResumePositionMs,
                    effectiveResumeProgressFraction,
                )
            },
            // Offered on an episode row only when a matching-type local-library folder exists (and
            // downloads are enabled): the season grab exists to file episodes INTO the library, so
            // with no library set up there is nothing for it to do and it is hidden (2026-07-24,
            // Jordan — a narrowing of the 2026-07-22 "never hide" rule, which was about not hiding a
            // real season-pack row on the finer per-source gates). Those finer gates — a listable
            // source or a resolvable release identity — are still checked at click time with a
            // specific toast, so once a library exists a genuine pack row never silently disappears.
            onDownloadSeason = if (isEpisode && AppFeaturePolicy.downloadsEnabled && downloadFolderCandidates.isNotEmpty()) {
                { stream -> routeDownload(stream, LibraryDownloadMode.Season) }
            } else {
                null
            },
            browsedSeason = seasonNumber.takeIf { isEpisode },
            isEpisodeView = isEpisode,
        )

        CompositionLocalProvider(LocalStreamRowActions provides streamRowActions) {
        if (isTabletLayout) {
            TabletStreamsLayout(
                isEpisode = isEpisode,
                contentId = parentMetaId,
                contentType = type,
                displayGroups = displayGroups,
                showHtpcChip = htpcGroup != null,
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                episodeThumbnail = episodeThumbnail,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                uiState = uiState,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                appendInstantServiceToDefaultName = debridSettings.canResolvePlayableLinks && !debridSettings.hasCustomStreamFormatting,
                resumePositionMs = effectiveResumePositionMs,
                resumeProgressFraction = effectiveResumeProgressFraction,
                providerListState = providerListState,
                streamListState = streamListState,
                focusedStream = focusedStream,
                onStreamSelected = { stream, positionMs, progressFraction ->
                    tracedStreamSelection(stream, positionMs, progressFraction)
                },
                onStreamLongPress = { stream -> streamActionsTarget = stream },
            )
        } else {
            MobileStreamsLayout(
                isEpisode = isEpisode,
                contentId = parentMetaId,
                contentType = type,
                displayGroups = displayGroups,
                showHtpcChip = htpcGroup != null,
                title = title,
                logo = logo,
                heroArtwork = heroArtwork,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                uiState = uiState,
                debridEnabled = debridSettings.canResolvePlayableLinks,
                appendInstantServiceToDefaultName = debridSettings.canResolvePlayableLinks && !debridSettings.hasCustomStreamFormatting,
                resumePositionMs = effectiveResumePositionMs,
                resumeProgressFraction = effectiveResumeProgressFraction,
                providerListState = providerListState,
                streamListState = streamListState,
                focusedStream = focusedStream,
                onStreamSelected = { stream, positionMs, progressFraction ->
                    tracedStreamSelection(stream, positionMs, progressFraction)
                },
                onStreamLongPress = { stream -> streamActionsTarget = stream },
            )
        }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 12.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NuvioBackButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp),
                containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                contentColor = MaterialTheme.colorScheme.onBackground,
            )

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                        shape = CircleShape,
                    )
                    .clickable(
                        onClick = {
                            StreamsRepository.reload(
                                type = type,
                                videoId = videoId,
                                parentMetaId = parentMetaId,
                                title = title,
                                season = seasonNumber,
                                episode = episodeNumber,
                                manualSelection = manualSelection,
                                preferLocalStreams = preferLocalStreams,
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(Res.string.streams_refresh),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = uiState.showDirectAutoPlayOverlay,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (autoPlayOverlayLogoUrl != null && !autoPlayOverlayLogoLoadError) {
                        AsyncImage(
                            model = autoPlayOverlayLogoUrl,
                            contentDescription = title,
                            modifier = Modifier
                                .height(48.dp),
                            contentScale = ContentScale.Fit,
                            onError = { autoPlayOverlayLogoLoadError = true },
                        )
                    } else if (title.isNotBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                    )
                    Text(
                        text = uiState.overlayMessage
                            ?: stringResource(Res.string.streams_finding_source),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }

        libraryFolderPrompt?.let { pending ->
            SeasonFolderPickerDialog(
                folders = downloadFolderCandidates,
                // Which of them this title has been downloaded into before. The picker sorts those
                // to the top and marks them, so filing a newly released episode beside its
                // predecessors does not depend on the user remembering where they went.
                foldersHoldingTitle = remember(downloadFolderCandidates, parentMetaId, localLibraryState.items) {
                    downloadFolderCandidates
                        .filter { candidate ->
                            LibraryDestinationFolders.holdsContent(candidate, parentMetaId)
                        }
                        .map(LocalFolder::id)
                        .toSet()
                },
                onDismiss = { libraryFolderPrompt = null },
                onSelect = { folder ->
                    libraryFolderPrompt = null
                    startDownloadForFolder(pending.stream, pending.mode, folder)
                },
            )
        }

        packSelectionPrompt?.let { prompt ->
            SeasonPackSelectionDialog(
                title = prompt.target.title,
                sourceName = prompt.session.sourceName,
                initialRows = prompt.session.rows,
                // Numbering follows the structure the pack was opened under, not the folder tag: a
                // kitsu/mal/anilist/anidb id has no seasons so it numbers relative to that entry,
                // while a franchise (TMDB/TVDB) meta keeps its real seasons even into an anime folder.
                entryRelative = prompt.target.contentId.isAnimeNativeId(),
                // Only offered where renumbering to 1..N is meaningful: a franchise id already
                // shares the pack's season/episode coordinates, so there is nothing to realign.
                onCountEntryEpisodes = if (prompt.target.contentId.isAnimeNativeId()) {
                    { StreamPackGrabService.entryEpisodeCount(prompt.target) }
                } else {
                    null
                },
                onDismiss = { packSelectionPrompt = null },
                onConfirm = { rows ->
                    packSelectionPrompt = null
                    val queued = StreamPackGrabService.enqueue(
                        session = prompt.session,
                        rows = rows,
                        target = prompt.target,
                        folder = prompt.folder,
                    )
                    downloadScope.launch {
                        NuvioToastController.show(
                            if (queued > 0) {
                                getString(Res.string.streams_download_season_queued, queued, prompt.folder.displayName)
                            } else {
                                getString(Res.string.streams_download_season_no_files)
                            },
                        )
                    }
                },
            )
        }
    }
}

private enum class LibraryDownloadMode { Single, Season }

private data class PendingLibraryDownload(
    val stream: StreamItem,
    val mode: LibraryDownloadMode,
)

/** A pack listing back from the debrid provider, held while its selection dialog is open. */
private data class PackSelectionPrompt(
    val session: ManualGrabSession,
    val folder: LocalFolder,
    val target: SeasonPackGrabService.Target,
)

/**
 * Asked only when more than one local-library series folder could hold the season — with a single
 * candidate the download starts straight from the menu. Both the folder's name and its full path
 * are shown: two drives commonly hold identically named folders.
 */
@Composable
private fun SeasonFolderPickerDialog(
    folders: List<LocalFolder>,
    foldersHoldingTitle: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (LocalFolder) -> Unit,
) {
    // A folder that already holds this title is almost always the intended answer, so it leads the
    // list and says so. Ordering is stable within each group, so the candidate order the caller
    // established (anime folders first for anime) still decides everything else.
    val ordered = remember(folders, foldersHoldingTitle) {
        folders.sortedByDescending { folder -> folder.id in foldersHoldingTitle }
    }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.streams_download_season_folder_title),
        subtitle = stringResource(Res.string.streams_download_season_folder_subtitle),
        maxWidth = 460.dp,
    ) {
        ordered.forEach { folder ->
            val alreadyUsed = folder.id in foldersHoldingTitle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(folder) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (alreadyUsed) Icons.Rounded.FolderSpecial else Icons.Rounded.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.displayNameWithDrive,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (alreadyUsed) {
                            stringResource(Res.string.streams_download_folder_already_used)
                        } else {
                            folder.path
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (alreadyUsed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Resolves [stream] to a directly playable stream when it is a debrid candidate (which requires a
 * suspending network hop on [scope]), then invokes [action] with the playable stream. Non-debrid
 * streams are passed through synchronously. Resolution failures surface a toast.
 */
private fun resolvePlayableStreamThen(
    stream: StreamItem,
    season: Int?,
    episode: Int?,
    scope: CoroutineScope,
    action: (StreamItem) -> Unit,
) {
    if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
        scope.launch {
            when (val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                stream = stream,
                season = season,
                episode = episode,
            )) {
                is DirectDebridPlayableResult.Success -> action(resolved.stream)
                else -> resolved.toastMessage()?.let(NuvioToastController::show)
            }
        }
    } else {
        action(stream)
    }
}

/** Hands [stream]'s direct URL off to the system browser, toasting on missing URL / launch failure. */
private fun openStreamInBrowser(
    stream: StreamItem,
    uriHandler: UriHandler,
    noDirectStreamLinkText: String,
    browserDownloadFailedText: String,
    unsupportedDownloadFormatText: String,
) {
    val url = stream.playableDirectUrl?.takeIf { it.isNotBlank() }
    if (url == null) {
        NuvioToastController.show(noDirectStreamLinkText)
        return
    }
    // Unlike the managed downloader, a browser hand-off cannot inspect the response body before it
    // is saved. Require a positively identified video reference and reject every executable hint.
    if (!stream.isSafeVideoDownloadCandidate(url, requireSafeVideoReference = true)) {
        NuvioToastController.show(unsupportedDownloadFormatText)
        return
    }
    runCatching { uriHandler.openUri(url) }
        .onFailure { NuvioToastController.show(browserDownloadFailedText) }
}

@Composable
private fun MobileStreamsLayout(
    isEpisode: Boolean,
    contentId: String?,
    contentType: String?,
    title: String,
    logo: String?,
    heroArtwork: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    uiState: StreamsUiState,
    debridEnabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    resumePositionMs: Long?,
    resumeProgressFraction: Float?,
    providerListState: LazyListState,
    streamListState: LazyListState,
    focusedStream: StreamItem?,
    onStreamSelected: (stream: StreamItem, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit,
    onStreamLongPress: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
    // See StreamList: the screen owns this so navigation and rendering share one ordering.
    displayGroups: List<AddonStreamGroup>? = null,
    showHtpcChip: Boolean = false,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (heroArtwork != null) {
            AsyncImage(
                model = heroArtwork,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(22.dp),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (isEpisode) 0.9f else 0.82f)),
            )
        }

        val streamBlendColor = MaterialTheme.colorScheme.background

        Column(modifier = Modifier.fillMaxSize()) {
            if (isEpisode && seasonNumber != null && episodeNumber != null) {
                EpisodeHeroBlock(
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle ?: title,
                    thumbnail = heroArtwork,
                    showTitle = title,
                )
            } else {
                MovieHeroBlock(
                    title = title,
                    logo = logo,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (isEpisode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        streamBlendColor.copy(alpha = 0.98f),
                                        streamBlendColor.copy(alpha = 0.84f),
                                        streamBlendColor.copy(alpha = 0.52f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    if ((resumePositionMs != null && resumePositionMs > 0L) || (resumeProgressFraction != null && resumeProgressFraction > 0f)) {
                        ResumeBanner(
                            positionMs = resumePositionMs,
                            progressFraction = resumeProgressFraction,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    ProviderFilterRow(
                        groups = uiState.groups,
                        selectedFilter = uiState.selectedFilter,
                        listState = providerListState,
                        onFilterSelected = { addonId -> StreamsRepository.selectFilter(addonId) },
                        showHtpcChip = showHtpcChip,
                    )

                    StreamList(
                        uiState = uiState,
                        displayGroups = displayGroups,
                        debridEnabled = debridEnabled,
                        appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                        onStreamSelected = onStreamSelected,
                        onStreamLongPress = onStreamLongPress,
                        resumePositionMs = resumePositionMs,
                        resumeProgressFraction = resumeProgressFraction,
                        listState = streamListState,
                        focusedStream = focusedStream,
                        isEpisode = isEpisode,
                        contentId = contentId,
                        contentType = contentType,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ResumeBanner(
    positionMs: Long?,
    progressFraction: Float? = null,
    modifier: Modifier = Modifier,
) {
    val resumeText = when {
        progressFraction != null && progressFraction > 0f -> stringResource(
            Res.string.streams_resume_from_percent,
            (progressFraction * 100f).roundToInt(),
        )
        positionMs != null && positionMs > 0L -> stringResource(
            Res.string.streams_resume_from_time,
            positionMs.toPlaybackClock(),
        )
        else -> null
    } ?: return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = resumeText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// Movie Hero
// ---------------------------------------------------------------------------

@Composable
private fun MovieHeroBlock(
    title: String,
    logo: String?,
    modifier: Modifier = Modifier,
) {
    var logoLoadError by remember(logo) { mutableStateOf(false) }
    val logoUrl = logo?.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        contentAlignment = Alignment.Center,
    ) {
        if (logoUrl != null && !logoLoadError) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                modifier = Modifier
                    .height(80.dp)
                    .fillMaxWidth(0.85f),
                contentScale = ContentScale.Fit,
                onError = { logoLoadError = true },
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Episode Hero
// ---------------------------------------------------------------------------

@Composable
private fun EpisodeHeroBlock(
    seasonNumber: Int,
    episodeNumber: Int,
    episodeTitle: String,
    thumbnail: String?,
    showTitle: String,
    modifier: Modifier = Modifier,
) {
    val heroBlendColor = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
    ) {
        // Thumbnail image
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // Gradient overlay bottom-up
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.58f to Color.Transparent,
                            0.8f to Color.Black.copy(alpha = 0.42f),
                            0.93f to heroBlendColor.copy(alpha = 0.84f),
                            1.0f to heroBlendColor.copy(alpha = 0.97f),
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.1f)),
        )

        // Safe-area push-down for status bar, then content pinned to bottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Episode label
            Text(
                text = stringResource(Res.string.streams_episode_badge, seasonNumber, episodeNumber),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ).accentBrush(),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Episode title
            Text(
                text = episodeTitle,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Show title
            Text(
                text = showTitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Provider Filter Row
// ---------------------------------------------------------------------------

@Composable
internal fun ProviderFilterRow(
    groups: List<AddonStreamGroup>,
    selectedFilter: String?,
    listState: LazyListState = rememberLazyListState(),
    onFilterSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    showHtpcChip: Boolean = false,
) {
    val addonGroups = groups.filter { it.streams.isNotEmpty() || it.isLoading }
    if (addonGroups.isEmpty()) return

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .desktopHorizontalListNavigation(
                state = listState,
                treatPlainScrollAsHorizontal = true,
                handlePageAndEdgeKeys = true,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(
                label = stringResource(Res.string.collections_tab_all),
                isSelected = selectedFilter == null,
                onClick = { onFilterSelected(null) },
            )
        }
        // Pinned ahead of the addons rather than appended: its position then stays put as providers
        // finish loading and their chips appear.
        if (showHtpcChip) {
            item(key = StreamSourceMerge.HTPC_ADDON_ID) {
                FilterChip(
                    label = StreamSourceMerge.HTPC_TAB_LABEL,
                    isSelected = selectedFilter == StreamSourceMerge.HTPC_ADDON_ID,
                    onClick = { onFilterSelected(StreamSourceMerge.HTPC_ADDON_ID) },
                )
            }
        }
        items(
            items = addonGroups,
            key = { it.addonId },
        ) { group ->
            FilterChip(
                label = group.addonName,
                isSelected = selectedFilter == group.addonId,
                onClick = { onFilterSelected(group.addonId) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "filter_chip_scale",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "filter_chip_container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 180),
        label = "filter_chip_content",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = 0.1.sp,
            ),
            color = contentColor,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Stream List
// ---------------------------------------------------------------------------

@Composable
internal fun StreamList(
    uiState: StreamsUiState,
    debridEnabled: Boolean,
    appendInstantServiceToDefaultName: Boolean,
    onStreamSelected: (stream: StreamItem, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit,
    onStreamLongPress: (StreamItem) -> Unit,
    resumePositionMs: Long?,
    resumeProgressFraction: Float?,
    listState: LazyListState = rememberLazyListState(),
    focusedStream: StreamItem? = null,
    modifier: Modifier = Modifier,
    // Only used to pick movie vs episode thresholds for the optional score badge.
    isEpisode: Boolean = false,
    // Same, for the animation-relaxed implausible-size floor.
    contentId: String? = null,
    contentType: String? = null,
    // The already-sorted/merged list. Passed in rather than derived here so keyboard navigation and
    // scroll-to-focused operate on exactly the list that gets drawn — deriving it twice let the nav
    // order drift from the visual order whenever sorting reordered rows, and merging (which drops
    // duplicate rows entirely) would have made that mismatch produce wrong targets.
    displayGroups: List<AddonStreamGroup>? = null,
) {
    val scoreProfile by StreamScoreRepository.uiState.collectAsStateWithLifecycle()
    val filteredGroups = displayGroups ?: rememberScoreSortedGroups(
        groups = uiState.filteredGroups,
        profile = scoreProfile,
        isEpisode = isEpisode,
        contentId = contentId,
        contentType = contentType,
    )
    val hasGroups = filteredGroups.isNotEmpty()
    val hasAnyStreams = filteredGroups.any { it.streams.isNotEmpty() }
    val anyLoading = filteredGroups.any { it.isLoading }
    val streamBadgeSettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val showGroupHeaders = uiState.selectedFilter == null
    val entries = remember(filteredGroups, showGroupHeaders) {
        buildStreamListEntries(groups = filteredGroups, showGroupHeaders = showGroupHeaders)
    }
    // One context for every card's badge and right-click breakdown, so the number on a row and the
    // explanation behind it can never be computed differently — and so a list of two hundred rows
    // builds it once instead of once per row.
    val rowScoreContext = remember(isEpisode, contentId, contentType) {
        StreamScoreContexts.forPlayback(
            isEpisode = isEpisode,
            contentId = contentId,
            contentType = contentType,
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = 12.dp,
            vertical = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        when {
            hasGroups && anyLoading && !hasAnyStreams -> {
                item(key = "state_loading") {
                    LoadingStateBlock()
                }
            }

            !hasAnyStreams && !uiState.isAnyLoading -> {
                item(key = "state_empty") {
                    EmptyStateBlock(reason = uiState.emptyStateReason)
                }
            }

            else -> {
                items(
                    items = entries,
                    key = { it.key },
                    contentType = { entry ->
                        when (entry) {
                            is StreamListEntry.SectionHeader -> "sectionHeader"
                            is StreamListEntry.SourceHeader -> "sourceHeader"
                            is StreamListEntry.Stream -> "stream"
                        }
                    },
                ) { entry ->
                    when (entry) {
                        is StreamListEntry.SectionHeader -> StreamSectionHeader(
                            addonName = entry.addonName,
                            isLoading = entry.isLoading,
                        )

                        is StreamListEntry.SourceHeader -> StreamSourceHeader(sourceName = entry.sourceName)

                        is StreamListEntry.Stream -> {
                            val stream = entry.stream
                            StreamRowContextMenu(
                                stream = stream,
                                enabled = stream.playableDirectUrl != null || stream.isAddonDebridCandidate,
                                scoreContext = rowScoreContext,
                            ) {
                                StreamCard(
                                    stream = stream,
                                    enabled = stream.isSelectableForPlayback(debridEnabled),
                                    appendInstantServiceToDefaultName = appendInstantServiceToDefaultName,
                                    showFileSizeBadges = streamBadgeSettings.showFileSizeBadges,
                                    showAddonLogo = streamBadgeSettings.showAddonLogo,
                                    badgePlacement = streamBadgeSettings.badgePlacement,
                                    focused = stream === focusedStream,
                                    scoreContext = rowScoreContext,
                                    onClick = {
                                        if (stream.isSelectableForPlayback(debridEnabled)) {
                                            onStreamSelected(stream, resumePositionMs, resumeProgressFraction)
                                        }
                                    },
                                    onLongClick = {
                                        if (stream.playableDirectUrl != null || stream.isAddonDebridCandidate) {
                                            onStreamLongPress(stream)
                                        }
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
                if (anyLoading) {
                    item(key = "footer_loading") {
                        FooterLoadingBlock()
                    }
                }
                item(key = "footer_spacer") {
                    Spacer(modifier = Modifier.height(nuvioSafeBottomPadding(80.dp)))
                }
            }
        }
    }
}

/**
 * Orders the source list by score when the profile asks for it.
 *
 * Sorts twice over: streams within each addon section, and the sections themselves by their best
 * stream. That puts the globally best source at the very top while keeping the per-addon headers
 * intact — flattening the list would put the best stream first but throw away the grouping the rest
 * of the screen is built around.
 *
 * Returns the input untouched when sorting is off, so the default presentation is byte-identical.
 */
@Composable
internal fun rememberScoreSortedGroups(
    groups: List<AddonStreamGroup>,
    profile: StreamScoreProfile,
    isEpisode: Boolean,
    contentId: String? = null,
    contentType: String? = null,
): List<AddonStreamGroup> = remember(groups, profile, isEpisode, contentId, contentType) {
    if (!profile.enabled || !profile.sortStreamList) return@remember groups
    // Built the same way as the per-card badge (StreamScoreContexts.forPlayback) rather than
    // constructed directly: a bare StreamScoreContext carries no preferred languages, so the
    // language trait would score in the badge but not in the sort, and the list order would
    // contradict the numbers printed on it.
    val context = StreamScoreContexts.forPlayback(
        isEpisode = isEpisode,
        contentId = contentId,
        contentType = contentType,
    )
    groups
        .map { group ->
            // rank() would drop below-minimum streams; the list must still show everything the
            // providers returned, so score and sort without filtering.
            //
            // Rows that are not streams at all (an addon's diagnostics panel, an age-rating notice,
            // a "notify me" link) are held out of the sort and pinned after the real sources, in the
            // order the addon sent them — sorting them by a score they cannot meaningfully have was
            // scattering them through the results.
            val (rankable, extras) = group.streams.partition { it.isScorableStream }
            val ordered = rankable
                .map { it to StreamScorer.score(it, profile, context).total }
                .sortedByDescending { it.second }
            val streams = ordered.map { it.first } + extras
            group.copy(streams = streams) to (ordered.firstOrNull()?.second ?: Int.MIN_VALUE)
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .let { sorted ->
            if (profile.mergeSources) {
                StreamSourceMerge.merge(sorted) { StreamScorer.score(it, profile, context).total }
            } else {
                sorted
            }
        }
}

/**
 * Builds the optional "HTPC" source tab: every addon's results deduped and ranked by the profile,
 * offered as one more filter chip rather than as a replacement for the addon sections.
 *
 * Takes the *unfiltered* groups on purpose — the tab is the whole catalogue ranked, so it must not
 * narrow to whatever chip happens to be selected. Returns null when the tab is off or there is
 * nothing to rank, which is also the signal not to draw the chip.
 */
@Composable
internal fun rememberHtpcSourceGroup(
    groups: List<AddonStreamGroup>,
    profile: StreamScoreProfile,
    isEpisode: Boolean,
    contentId: String? = null,
    contentType: String? = null,
): AddonStreamGroup? = remember(groups, profile, isEpisode, contentId, contentType) {
    if (!profile.enabled || !profile.htpcSourceTab) return@remember null
    // Same context as the per-card badge, so the tab's order agrees with the numbers on the rows.
    val context = StreamScoreContexts.forPlayback(
        isEpisode = isEpisode,
        contentId = contentId,
        contentType = contentType,
    )
    StreamSourceMerge.htpcTab(groups) { StreamScorer.score(it, profile, context).total }
}


/**
 * One drawable row of the source list: a section header, a source header, or a stream.
 *
 * Flattening the whole list up front is what makes the scroll position stable. The list is rebuilt
 * every time an addon answers, and score sorting can move any row anywhere in it, so [key] has to
 * describe what a row *is* rather than where it currently sits: LazyColumn keeps the viewport in
 * place across a data change by looking the first visible key up in the new list, and a key built
 * from an index never matches once a late source has been slotted in above it.
 */
internal sealed interface StreamListEntry {
    val key: String

    data class SectionHeader(
        override val key: String,
        val addonName: String,
        val isLoading: Boolean,
    ) : StreamListEntry

    data class SourceHeader(
        override val key: String,
        val sourceName: String,
    ) : StreamListEntry

    data class Stream(
        override val key: String,
        val stream: StreamItem,
    ) : StreamListEntry
}

/**
 * Expands [groups] into the exact row sequence [StreamList] draws.
 *
 * Single source of truth for the list's shape: rendering, lazy keys and [streamLazyListIndex] all
 * read it, so keyboard navigation can no longer target a different row than the one on screen.
 */
internal fun buildStreamListEntries(
    groups: List<AddonStreamGroup>,
    showGroupHeaders: Boolean,
): List<StreamListEntry> {
    val entries = mutableListOf<StreamListEntry>()
    val keyOccurrences = mutableMapOf<String, Int>()
    // Identity keys can legitimately repeat — the same release listed twice, rows carrying neither a
    // url nor a hash — and a duplicate key crashes the app on Desktop, so collisions get an
    // occurrence suffix. The first occurrence keeps the bare key, which keeps it stable.
    fun uniqueKey(base: String): String {
        val seen = keyOccurrences.getOrElse(base) { 0 }
        keyOccurrences[base] = seen + 1
        return if (seen == 0) base else "$base#$seen"
    }

    groups.forEach { group ->
        if (group.streams.isEmpty() && !group.isLoading) return@forEach

        // The merged list is a single score-ordered run: sub-grouping it by source and sorting those
        // groups alphabetically (what a normal section does) would throw the score order away, which
        // is the entire point of merging.
        val isMergedSection = StreamSourceMerge.isFlatSection(group.addonId)
        if (showGroupHeaders && !isMergedSection) {
            entries += StreamListEntry.SectionHeader(
                key = uniqueKey("header:${group.addonId}"),
                addonName = group.addonName,
                isLoading = group.isLoading,
            )
        }

        val streamsBySource = if (isMergedSection) {
            mapOf(group.addonName to group.streams)
        } else {
            group.streams.groupBy { stream ->
                stream.sourceName?.takeIf { it.isNotBlank() } ?: stream.addonName
            }
        }
        val sortedSources = if (isMergedSection) {
            streamsBySource.keys.toList()
        } else {
            streamsBySource.keys.sortedBy { it.lowercase() }
        }
        val showSourceHeaders = !isMergedSection && sortedSources.size > 1

        sortedSources.forEach { sourceName ->
            if (showSourceHeaders) {
                entries += StreamListEntry.SourceHeader(
                    key = uniqueKey("source:${group.addonId}:$sourceName"),
                    sourceName = sourceName,
                )
            }
            streamsBySource[sourceName].orEmpty().forEach { stream ->
                entries += StreamListEntry.Stream(
                    key = uniqueKey("stream:${group.addonId}:${stream.lazyRowIdentity()}"),
                    stream = stream,
                )
            }
        }
    }
    return entries
}

/** The most stable thing a row can be recognised by across a re-sorted, re-fetched list. */
private fun StreamItem.lazyRowIdentity(): String =
    url ?: infoHash ?: clientResolve?.infoHash ?: streamLabel

private fun orderedStreams(groups: List<AddonStreamGroup>): List<StreamItem> =
    groups.flatMap { group ->
        // A merged section is already in score order and is drawn as one flat run, so regrouping it
        // by source here would hand keyboard navigation a different order than the eye sees.
        if (StreamSourceMerge.isFlatSection(group.addonId)) {
            group.streams
        } else {
            group.streams
                .groupBy { stream -> stream.sourceName?.takeIf { it.isNotBlank() } ?: stream.addonName }
                .toSortedMap(compareBy(String::lowercase))
                .values
                .flatten()
        }
    }

private fun streamLazyListIndex(
    groups: List<AddonStreamGroup>,
    showGroupHeaders: Boolean,
    target: StreamItem,
): Int = buildStreamListEntries(groups = groups, showGroupHeaders = showGroupHeaders)
    .indexOfFirst { it is StreamListEntry.Stream && it.stream === target }

// ---------------------------------------------------------------------------
// Stream Section Header
// ---------------------------------------------------------------------------

@Composable
private fun StreamSectionHeader(
    addonName: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = addonName,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
        )
        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(Res.string.streams_fetching),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp).accentBrush(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StreamSourceHeader(
    sourceName: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = sourceName,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun Long.toPlaybackClock(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        buildString {
            append(hours)
            append(':')
            append(minutes.toString().padStart(2, '0'))
            append(':')
            append(seconds.toString().padStart(2, '0'))
        }
    } else {
        buildString {
            append(minutes)
            append(':')
            append(seconds.toString().padStart(2, '0'))
        }
    }
}

// ---------------------------------------------------------------------------
// State blocks
// ---------------------------------------------------------------------------

@Composable
private fun LoadingStateBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = stringResource(Res.string.streams_finding_streams),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ).accentBrush(),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyStateBlock(
    reason: StreamsEmptyStateReason?,
    modifier: Modifier = Modifier,
) {
    val title: String
    val message: String

    when (reason) {
        StreamsEmptyStateReason.NoAddonsInstalled -> {
            title = stringResource(Res.string.compose_search_empty_no_active_addons_title)
            message = stringResource(Res.string.streams_empty_no_addons_message)
        }

        StreamsEmptyStateReason.NoCompatibleAddons -> {
            title = stringResource(Res.string.streams_empty_no_stream_addon_title)
            message = stringResource(Res.string.streams_empty_no_stream_addon_message)
        }

        StreamsEmptyStateReason.StreamFetchFailed -> {
            title = stringResource(Res.string.streams_empty_load_failed_title)
            message = stringResource(Res.string.streams_empty_load_failed_message)
        }

        StreamsEmptyStateReason.NoStreamsFound, null -> {
            title = stringResource(Res.string.compose_player_no_streams_found)
            message = stringResource(Res.string.streams_empty_no_streams_message)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FooterLoadingBlock(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.streams_checking_more_addons),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ).accentBrush(),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
