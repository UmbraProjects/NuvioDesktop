package com.nuvio.app.features.music.home

import com.nuvio.app.core.ui.requestFocusLogged
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.focusable
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.mutableStateMapOf
import com.nuvio.app.core.ui.HdrFadeCorrected
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.PosterLandscapeWidthScale
import com.nuvio.app.core.ui.nuvioDitheredVerticalGradient
import com.nuvio.app.features.home.HeroCastMember
import com.nuvio.app.features.home.HomeTvRowTransition
import com.nuvio.app.features.home.IMMERSIVE_SHELF_TOP_PADDING_DP
import com.nuvio.app.features.home.MUSIC_HERO_TYPE_PREFIX
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.immersiveShelfCardHeightDp
import com.nuvio.app.features.home.immersiveShelfHeightDp
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomeTvRowDot
import com.nuvio.app.features.home.components.HomeTvRowDotStrip
import com.nuvio.app.features.home.components.ImmersiveRowDirection
import com.nuvio.app.features.home.components.homeHeroLayout
import com.nuvio.app.features.home.components.immersiveRowBodyEnter
import com.nuvio.app.features.home.components.immersiveRowBodyExit
import com.nuvio.app.features.home.components.immersiveRowFadeBounds
import com.nuvio.app.features.home.components.immersiveRowTransition
import com.nuvio.app.features.home.components.immersiveShelfScrimStops
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import com.nuvio.app.isDesktop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.music.library.ReleaseKind
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.PublishKeyboardSuggestions
import com.nuvio.app.core.ui.VoiceSearch
import com.nuvio.app.core.ui.VoiceSearchCommands
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.rememberHomePosterCardStyleUiState
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicItemMenu
import com.nuvio.app.features.music.MusicTabState
import com.nuvio.app.features.music.MusicTopBarSearch
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.MusicSearchHistory
import com.nuvio.app.features.music.library.enlargeYtThumbnail
import com.nuvio.app.features.music.rememberMusicMenu
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The music search, laid out like the media search: the search field at the top, recent searches under it while it is
 * empty, and the answer as rows (songs, albums, EPs, singles, artists, YouTube videos) drawn with the same shelves and
 * cards as Home, so they follow the person's own card settings (width, corners, labels, row headers). Covers are square
 * and YouTube videos 16:9. Predictions for the on-screen keyboard come from YouTube Music's completions.
 */

/** One result: what a card shows and what it plays or opens. */
private sealed interface MusicHit {
    val key: String
    val title: String
    val detail: String?
    val art: String?
    val shape: NuvioPosterShape

    data class Song(val track: MusicTrack) : MusicHit {
        override val key get() = "song:${track.videoId}"
        override val title get() = track.title
        override val detail get() = track.artists.joinToString(", ") { it.name }.ifBlank { null }
        override val art get() = track.coverUrl?.let { enlargeYtThumbnail(it, size = COVER_SIZE) ?: it }
        override val shape get() = NuvioPosterShape.Square
    }

    data class Release(val album: MusicAlbumSummary) : MusicHit {
        override val key get() = "album:${album.browseId}"
        override val title get() = album.title
        override val detail get() = listOfNotNull(album.artists.firstOrNull()?.name, album.year).joinToString(" · ").ifBlank { null }
        override val art get() = album.thumbnailUrl?.let { enlargeYtThumbnail(it, size = COVER_SIZE) ?: it }
        override val shape get() = NuvioPosterShape.Square
    }

    data class Artist(val artist: MusicArtistSummary) : MusicHit {
        override val key get() = "artist:${artist.browseId}"
        override val title get() = artist.name
        override val detail get() = null
        override val art get() = artist.thumbnailUrl?.let { enlargeYtThumbnail(it, size = COVER_SIZE) ?: it }
        override val shape get() = NuvioPosterShape.Square
    }

    data class Video(val track: MusicTrack) : MusicHit {
        override val key get() = "video:${track.videoId}"
        override val title get() = track.title
        override val detail get() = track.artists.joinToString(", ") { it.name }.ifBlank { null }
        override val art get() = track.coverUrl
        override val shape get() = NuvioPosterShape.Landscape
    }
}

private data class MusicHitRow(val key: String, val title: String, val hits: List<MusicHit>)

/** The rows, in screen order, from what each search answered (null = still waiting). Empty rows are left out. */
private fun buildRows(
    songs: List<MusicTrack>?,
    releases: List<MusicAlbumSummary>?,
    artists: List<MusicArtistSummary>?,
    videos: List<MusicTrack>?,
    /** What was searched: the row it seems to be after comes first (MusicSearchIntent.kt). */
    query: String = "",
    /**
     * Releases YouTube Music calls singles that are really EPs: three or more songs on them (looked up for the ones the
     * search names), or an EP in the library's own discography. "Gravity (The Acoustic Sessions Volume II)" is one.
     */
    epIds: Set<String> = emptySet(),
): List<MusicHitRow> {
    fun kindOf(album: MusicAlbumSummary) = if (album.browseId in epIds) "ep" else album.kind?.trim()?.lowercase()
    val all = releases.orEmpty()
    val eps = all.filter { kindOf(it) == "ep" }
    val singles = all.filter { kindOf(it) == "single" }
    val albums = all.filter { it !in eps && it !in singles }
    return listOf(
        MusicHitRow("songs", "Songs", songs.orEmpty().filter { it.isAvailable }.map(MusicHit::Song)),
        MusicHitRow("albums", "Albums", albums.map(MusicHit::Release)),
        MusicHitRow("eps", "EPs", eps.map(MusicHit::Release)),
        MusicHitRow("singles", "Singles", singles.map(MusicHit::Release)),
        MusicHitRow("artists", "Artists", artists.orEmpty().map(MusicHit::Artist)),
        MusicHitRow("youtube", "YouTube", videos.orEmpty().map(MusicHit::Video)),
    ).filter { it.hits.isNotEmpty() }
        .map { row -> row.copy(hits = row.hits.distinctBy { it.key }) }
        .let { rows -> byIntent(query, rows) }
}

/** [rows] reordered for what [query] seems to be after: "Eminem" artists first, "Eminem encore" albums first. */
private fun byIntent(query: String, rows: List<MusicHitRow>): List<MusicHitRow> {
    val intentRows = rows.map { row ->
        val kind = when (row.key) {
            "artists" -> IntentKind.Artists
            "albums" -> IntentKind.Albums
            "eps" -> IntentKind.EPs
            "singles" -> IntentKind.Singles
            "youtube" -> IntentKind.Videos
            else -> IntentKind.Songs
        }
        IntentRow(kind, row.hits.map { hit ->
            val credited = if (hit is MusicHit.Artist) emptyList() else hit.creditedArtists()
            // The artist's (or the first credited artist's) fans; videos keep YouTube's own order.
            val who = if (hit is MusicHit.Artist) hit.title else credited.firstOrNull()
            val tier = if (hit is MusicHit.Video || who == null) POPULARITY_UNKNOWN else popularityTier(MusicHeroInfo.peekFans(who))
            IntentHit(hit.title, credited, tier)
        })
    }
    return intentOrder(query, intentRows).map { (i, hitOrder) -> rows[i].copy(hits = hitOrder.map { rows[i].hits[it] }) }
}

@Composable
internal fun MusicRowsSearch(
    catalog: MusicCatalog,
    onAlbumClick: (browseId: String) -> Unit,
    onArtistClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn above the search field (the music home's tab icons). */
    header: @Composable () -> Unit = {},
    /**
     * False when the host has its own box for [MusicTabState.query] (the classic Music tab's, in the top bar or its
     * header): no field is drawn here, and Up from the first row hands focus back to the page above.
     */
    ownField: Boolean = true,
) {
    val tab = MusicTabState
    val query = tab.query
    // What is searched: the box's own text as it is typed, or (the app bar's box) only what Enter sent.
    val searched = if (ownField) query.trim() else tab.submitted
    val history by MusicSearchHistory.recent.collectAsStateWithLifecycle()
    val homeSettings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val cardStyle = rememberHomePosterCardStyleUiState()
    val scope = rememberCoroutineScope()
    val runner = remember(catalog) { HomeRunner(scope, catalog, onAlbumClick, onArtistClick) }
    // A search is kept as a recent one once it led somewhere: something played or opened from it.
    runner.onCommit = { tab.query.trim().takeIf { it.length >= MIN_QUERY_LENGTH }?.let(MusicSearchHistory::record) }

    var songs by remember { mutableStateOf<List<MusicTrack>?>(null) }
    var releases by remember { mutableStateOf<List<MusicAlbumSummary>?>(null) }
    var artists by remember { mutableStateOf<List<MusicArtistSummary>?>(null) }
    var videos by remember { mutableStateOf<List<MusicTrack>?>(null) }
    var answeredQuery by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }

    var isInRowNav by remember { mutableStateOf(false) }
    var focusedRow by remember { mutableIntStateOf(0) }
    var focusedItem by remember { mutableIntStateOf(0) }
    val fieldFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val menu = rememberMusicMenu()
    var menuHit by remember { mutableStateOf<MusicHit?>(null) }
    val cardBounds = remember { HashMap<String, Rect>() }

    fun setQuery(text: String) {
        isInRowNav = false
        tab.query = text
    }

    // YouTube Music's completions after a short pause: the on-screen keyboard offers them above its keys.
    LaunchedEffect(query) {
        val line = query.trim()
        if (line.isEmpty()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SUGGEST_PAUSE_MS)
        suggestions = runCatching { catalog.searchSuggestions(line) }.getOrDefault(emptyList())
            .filter { !it.equals(line, ignoreCase = true) }
    }
    PublishKeyboardSuggestions(suggestions) { picked -> setQuery(picked) }

    // The searches, all at once; each row fills in as its own answer comes back. A box of its own searches after the
    // typing pause; the app bar's box searches when Enter is pressed.
    LaunchedEffect(searched) {
        val line = searched
        if (line.length < MIN_QUERY_LENGTH) {
            songs = null; releases = null; artists = null; videos = null; answeredQuery = null
            return@LaunchedEffect
        }
        if (ownField) delay(SEARCH_PAUSE_MS)
        songs = null; releases = null; artists = null; videos = null
        answeredQuery = line
        // Each row waits (briefly) for its artists' fan counts before it shows, so its order is settled once and nothing
        // moves after: a count that comes later only counts next time.
        coroutineScope {
            launch { songs = runCatching { catalog.searchSongs(line) }.getOrDefault(emptyList()).also { r -> warmFans(r.flatMap { t -> t.artists.map { it.name } }) } }
            launch { releases = runCatching { catalog.searchAlbums(line) }.getOrDefault(emptyList()).also { r -> warmFans(r.flatMap { a -> a.artists.map { it.name } }) } }
            launch { artists = runCatching { catalog.searchArtists(line) }.getOrDefault(emptyList()).also { r -> warmFans(r.map { it.name }) } }
            launch { videos = runCatching { catalog.searchYouTube(line) }.getOrDefault(emptyList()) }
        }
    }
    // Singles that are really EPs. The library's own discography knows for a saved artist's records; for a single the search
    // names (and only those, at most a few), its page is read to count its songs.
    val libraryEps = MusicLibraryRepository.state.collectAsStateWithLifecycle().value.artists.let { saved ->
        remember(saved) { saved.flatMap { a -> a.releases.filter { it.kind == ReleaseKind.EP }.mapNotNull { it.ytBrowseId } }.toSet() }
    }
    var countedEps by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(releases, answeredQuery) {
        val line = answeredQuery ?: return@LaunchedEffect
        val named = releases.orEmpty().filter { r ->
            r.kind.equals("single", ignoreCase = true) && r.browseId !in libraryEps && r.browseId !in countedEps &&
                intentMatch(line, IntentKind.Singles, IntentHit(r.title, r.artists.map { it.name })) > 0
        }.take(EP_CHECKS)
        for (single in named) {
            val songs = runCatching { catalog.album(single.browseId)?.tracks?.size }.getOrNull() ?: continue
            if (songs >= EP_MIN_SONGS) countedEps = countedEps + single.browseId
        }
    }
    val epIds = remember(libraryEps, countedEps) { libraryEps + countedEps }
    val rows = remember(songs, releases, artists, videos, answeredQuery, epIds) {
        buildRows(songs, releases, artists, videos, answeredQuery.orEmpty(), epIds)
    }
    val waiting = searched.length >= MIN_QUERY_LENGTH &&
        (answeredQuery != searched || songs == null || releases == null || artists == null || videos == null)

    // Enter in the box (or a spoken search) sends the keyboard to the rows once they are in.
    LaunchedEffect(rows.isNotEmpty(), tab.focusResultsWhenReady) {
        if (!tab.focusResultsWhenReady || rows.isEmpty()) return@LaunchedEffect
        tab.focusResultsWhenReady = false
        isInRowNav = true
        focusedRow = 0
        focusedItem = 0
        tab.bodyFocus.requestFocusLogged()
    }

    // The app's search shortcut focuses the field (requests made before this screen existed are not replayed).
    var handledFocusRequests by remember { mutableIntStateOf(MusicTopBarSearch.focusRequests) }
    LaunchedEffect(MusicTopBarSearch.focusRequests) {
        if (MusicTopBarSearch.focusRequests == handledFocusRequests) return@LaunchedEffect
        handledFocusRequests = MusicTopBarSearch.focusRequests
        isInRowNav = false
        fieldFocus.requestFocusLogged()
    }
    VoiceSearchCommands(owner = VOICE_OWNER, text = query, onTextChange = ::setQuery)

    fun activate(hit: MusicHit) {
        when (hit) {
            is MusicHit.Song -> runner.track(hit.track, PlayHow.Now)
            is MusicHit.Video -> runner.track(hit.track, PlayHow.Now)
            is MusicHit.Release -> runner.openAlbum(hit.album.browseId)
            is MusicHit.Artist -> runner.openArtist(hit.artist.browseId)
        }
    }

    fun openMenu(hit: MusicHit, fromKeys: Boolean) {
        menuHit = hit
        menu.bounds = cardBounds[hit.key]
        if (fromKeys) menu.openFromKeys() else menu.openAtCursor()
    }

    MusicItemMenu(menu) { menuHit?.let { menuFor(it, runner) }.orEmpty() }

    // Settings > Home's display mode: TV Mode and Adaptive put the result in focus in Home's own hero (the shelf in TV
    // Mode's black band, one row at a time; the rows under the hero in Adaptive); Basic is the media search's list.
    val mode = rememberMusicSearchMode()
    val adaptiveListState = rememberLazyListState()
    val tvDotsState = rememberLazyListState()
    fun scrollToRow(row: Int) {
        scope.launch {
            when (mode) {
                MusicSearchMode.Tv -> Unit // the shelf swaps rows itself
                MusicSearchMode.Adaptive -> adaptiveListState.animateScrollToItem(row)
                MusicSearchMode.Basic -> listState.animateScrollToItem(if (row == 0) 0 else row + 1)
            }
        }
    }
    val shownRow = focusedRow.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    // The result the hero shows: the one in focus, else the first one.
    val heroHit = if (isInRowNav) rows.getOrNull(shownRow)?.hits?.getOrNull(focusedItem) else rows.firstOrNull()?.hits?.firstOrNull()
    // What the hero says about each result: at once what the search gave, then (after a short rest on it, so arrowing
    // along a row asks nothing) its artist's picture, logo and biography, and the album's or song's own description.
    val heroInfo = remember { mutableStateMapOf<String, MetaPreview>() }
    LaunchedEffect(heroHit?.key, mode) {
        val hit = heroHit ?: return@LaunchedEffect
        if (mode == MusicSearchMode.Basic || heroInfo.containsKey(hit.key)) return@LaunchedEffect
        delay(HERO_INFO_PAUSE_MS)
        // Moving on before it is done cancels the lookup: that is not a failure, and nothing is kept, so coming back to the
        // result asks again. (It used to be kept as a failure: the plain cover as the backdrop and no words, for good.)
        heroInfo[hit.key] = try {
            enrichedHeroMeta(hit, catalog)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // A real failure: the result's own cover, drawn as its colours like any other record's, never the picture itself.
            hit.baseHeroMeta().copy(
                heroMetadataPending = false,
                banner = hit.bigArt()?.also { if (hit !is MusicHit.Artist) MusicHeroLogos.markBlurred(it) },
            )
        }
    }
    val heroMeta = heroHit?.let { heroInfo[it.key] ?: it.baseHeroMeta() }
    val heroItems = if (mode == MusicSearchMode.Basic) emptyList() else rows.flatMap { it.hits }.map { heroInfo[it.key] ?: it.baseHeroMeta() }
    val onHeroClick: (MetaPreview) -> Unit = { meta -> rows.firstNotNullOfOrNull { row -> row.hits.firstOrNull { it.key == meta.id } }?.let(::activate) }

    val searchField: @Composable (Modifier) -> Unit = { fieldModifier ->
        NuvioInputField(
            value = query,
            onValueChange = ::setQuery,
            placeholder = "Songs, albums, artists or videos",
            modifier = fieldModifier.focusRequester(fieldFocus).trackTextInputFocus(),
            trailingContent = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { setQuery("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    IconButton(onClick = { VoiceSearch.start(VOICE_OWNER) }) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Search by voice", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        )
    }
    /** One row as Home draws it. [baseWidth] sizes the cards (TV Mode fills its shelf); [trailing] is TV Mode's dots. */
    val shelf: @Composable (Int, MusicHitRow, Dp, ((MusicHit) -> Int?)?, (@Composable () -> Unit)?, Modifier) -> Unit =
        { index, row, sectionPadding, baseWidth, trailing, bodyModifier ->
            NuvioShelfSection(
                title = row.title,
                entries = row.hits,
                modifier = Modifier.padding(bottom = 12.dp),
                headerHorizontalPadding = sectionPadding,
                rowContentPadding = PaddingValues(horizontal = sectionPadding),
                showHeaderAccent = !homeSettings.hideCatalogUnderline,
                viewAllPillSize = NuvioViewAllPillSize.Compact,
                headerTrailingContent = trailing,
                bodyModifier = bodyModifier,
                focusedItemIndex = if (isInRowNav && shownRow == index) focusedItem else null,
                // With a hero, the pointer picks what it shows, as on Home; the list follows only the keys.
                onHoverItem = when {
                    mode != MusicSearchMode.Basic -> { i ->
                        isInRowNav = true
                        focusedRow = index
                        focusedItem = i
                    }
                    isInRowNav && shownRow == index -> { i -> focusedItem = i }
                    else -> null
                },
                isKeyboardNavigation = isInRowNav,
                key = { it.key },
            ) { hit ->
                NuvioPosterCard(
                    title = hit.title,
                    imageUrl = hit.art,
                    modifier = Modifier.onGloballyPositioned { cardBounds[hit.key] = it.boundsInWindow() },
                    shape = hit.shape,
                    basePosterWidthDpOverride = baseWidth?.invoke(hit),
                    detailLine = if (cardStyle.hideLabelsEnabled) null else hit.detail,
                    showTitleBelow = !cardStyle.hideLabelsEnabled,
                    onClick = { activate(hit) },
                    onLongClick = { openMenu(hit, fromKeys = false) },
                )
            }
        }
    val emptyLine: @Composable (Dp) -> Unit = { padding ->
        Text(
            "Nothing found for “$searched”.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.padding(horizontal = padding, vertical = 24.dp),
        )
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .focusRequester(tab.bodyFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val key = event.navigationKey()
                when {
                    !isInRowNav && rows.isNotEmpty() && (key == Key.DirectionDown || key == Key.Enter || key == Key.NumPadEnter) -> {
                        isInRowNav = true
                        focusedRow = 0
                        focusedItem = 0
                        scrollToRow(0)
                        true
                    }
                    !isInRowNav && key == Key.DirectionUp -> ownField && fieldFocus.requestFocusLogged()
                    isInRowNav -> when (key) {
                        Key.DirectionUp -> {
                            if (shownRow > 0) {
                                focusedRow = shownRow - 1
                                focusedItem = 0
                                scrollToRow(focusedRow)
                                true
                            } else {
                                // Off the top: back to this screen's field, or (without one) let focus move up as usual.
                                isInRowNav = false
                                if (ownField) fieldFocus.requestFocusLogged()
                                ownField
                            }
                        }
                        Key.DirectionDown -> {
                            focusedRow = (shownRow + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0))
                            focusedItem = 0
                            scrollToRow(focusedRow)
                            true
                        }
                        Key.DirectionRight -> {
                            focusedItem = (focusedItem + 1).coerceAtMost(((rows.getOrNull(shownRow)?.hits?.size ?: 1) - 1).coerceAtLeast(0))
                            true
                        }
                        Key.DirectionLeft -> {
                            focusedItem = (focusedItem - 1).coerceAtLeast(0)
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            rows.getOrNull(shownRow)?.hits?.getOrNull(focusedItem)?.let(::activate)
                            true
                        }
                        Key.Menu -> {
                            rows.getOrNull(shownRow)?.hits?.getOrNull(focusedItem)?.let { openMenu(it, fromKeys = true) }
                            true
                        }
                        else -> false
                    }
                    else -> false
                }
            },
    ) {
        val sectionPadding = remember(maxWidth) { homeSectionHorizontalPaddingForWidth(maxWidth.value) }
        val viewportHeight = maxHeight
        val viewportWidth = maxWidth
        val notFound = rows.isEmpty() && !waiting && searched.length >= MIN_QUERY_LENGTH

        if (mode == MusicSearchMode.Tv) {
            // TV Mode's shelf: Home's height for landscape cards, which suits square covers better than the portrait
            // one; every card fills its height (a cover square, a video 16:9).
            val shelfHeight = immersiveShelfHeightDp(viewportHeightDp = viewportHeight.value, landscapeMode = true).dp
            val cardHeight = immersiveShelfCardHeightDp(shelfHeight.value)
            val baseWidth: (MusicHit) -> Int = { hit ->
                if (hit.shape == NuvioPosterShape.Landscape) (cardHeight * PosterLandscapeAspectRatio / PosterLandscapeWidthScale).roundToInt()
                else cardHeight.roundToInt()
            }
            val dots = rows.map { HomeTvRowDot(rowKey = it.key, label = it.title) }
            val rowDirection = remember { ImmersiveRowDirection() }
            // The wheel moves between rows, as on Home's TV Mode (a row's own sideways scroll keeps its notches).
            var lastWheelStep by remember { mutableStateOf<kotlin.time.TimeMark?>(null) }
            val wheel = Modifier.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (change.isConsumed || rows.isEmpty()) return@onPointerEvent
                val dy = change.scrollDelta.y
                if (dy == 0f) return@onPointerEvent
                change.consume()
                if (lastWheelStep?.let { it.elapsedNow() < WHEEL_STEP_GAP } == true) return@onPointerEvent
                lastWheelStep = kotlin.time.TimeSource.Monotonic.markNow()
                isInRowNav = true
                focusedRow = (shownRow + if (dy > 0f) 1 else -1).coerceIn(0, rows.lastIndex)
                focusedItem = 0
            }
            HdrFadeCorrected(background = Color.Black, modifier = Modifier.fillMaxSize().background(Color.Black).then(wheel)) {
                // The music hero's words are longer than a film's tagline (a band's story, an album's notes): the column sits
                // higher above the shelf and the plot may run to more lines.
                if (heroMeta != null) androidx.compose.runtime.CompositionLocalProvider(
                    com.nuvio.app.features.home.components.LocalImmersiveHeroTuning provides MUSIC_HERO_TUNING,
                ) {
                    HomeHeroSection(
                        items = heroItems.ifEmpty { listOf(heroMeta) },
                        focusedItem = heroMeta,
                        viewportHeight = viewportHeight,
                        heightOverride = viewportHeight,
                        roundedBottomCorners = false,
                        immersiveMode = true,
                        immersiveFullBackdrop = homeSettings.tvFullBackdropEnabled,
                        ambientBackground = homeSettings.tvAmbientBackgroundEnabled,
                        heroInfoLines = homeSettings.heroInfoLines,
                        heroInfoPriority = homeSettings.heroInfoPriority,
                        heroBadgePlacement = homeSettings.heroBadgePlacement,
                        trailersEnabledInCurrentMode = false,
                        immersiveContentBottomPadding = shelfHeight - 20.dp,
                        onItemClick = onHeroClick,
                    )
                }
                if (ownField) {
                    Column(Modifier.fillMaxWidth()) {
                        header()
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.padding(horizontal = sectionPadding).widthIn(max = 640.dp)) { searchField(Modifier) }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(shelfHeight)
                        .align(Alignment.BottomStart)
                        .background(
                            nuvioDitheredVerticalGradient(
                                immersiveShelfScrimStops(
                                    backgroundColor = if (homeSettings.tvAmbientBackgroundEnabled) MaterialTheme.colorScheme.background else Color.Black,
                                    fullBackdrop = homeSettings.tvFullBackdropEnabled,
                                    ambientBackgroundEnabled = homeSettings.tvAmbientBackgroundEnabled,
                                ),
                            ),
                        )
                        .padding(top = IMMERSIVE_SHELF_TOP_PADDING_DP.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    when {
                        rows.isEmpty() && waiting -> HomeSkeletonRow(Modifier.padding(horizontal = sectionPadding), showHeaderAccent = !homeSettings.hideCatalogUnderline)
                        notFound -> emptyLine(sectionPadding)
                        rows.isEmpty() -> if (ownField && query.isBlank() && history.isNotEmpty()) {
                            RecentSearches(history, sectionPadding, onPick = ::setQuery, onClear = MusicSearchHistory::clear)
                        }
                        else -> {
                            rowDirection.observe(shownRow)
                            AnimatedContent(
                                targetState = shownRow,
                                transitionSpec = { immersiveRowTransition(homeSettings.tvRowTransition) },
                                contentAlignment = Alignment.BottomStart,
                                label = "music_search_row",
                            ) { rowIndex ->
                                val row = rows.getOrNull(rowIndex) ?: return@AnimatedContent
                                val bodyModifier = if (homeSettings.tvRowTransition == HomeTvRowTransition.FadeNudge) {
                                    Modifier.animateEnterExit(
                                        enter = immersiveRowBodyEnter(rowDirection.forward),
                                        exit = immersiveRowBodyExit(rowDirection.forward),
                                        label = "music_search_row_body",
                                    )
                                } else {
                                    Modifier
                                }
                                val trailing: (@Composable () -> Unit)? = if (homeSettings.tvRowDotsEnabled && dots.size > 1) {
                                    {
                                        HomeTvRowDotStrip(
                                            dots = dots,
                                            activeIndex = rowIndex,
                                            onDotClick = { picked ->
                                                isInRowNav = true
                                                focusedRow = picked
                                                focusedItem = 0
                                            },
                                            listState = tvDotsState,
                                            anchor = homeSettings.tvRowDotsAnchor,
                                        )
                                    }
                                } else {
                                    null
                                }
                                Box(Modifier.immersiveRowFadeBounds()) { shelf(rowIndex, row, sectionPadding, baseWidth, trailing, bodyModifier) }
                            }
                        }
                    }
                }
            }
            return@BoxWithConstraints
        }

        if (mode == MusicSearchMode.Adaptive) {
            val layout = homeHeroLayout(
                maxWidthDp = viewportWidth.value,
                viewportHeightDp = viewportHeight.value,
                preferDesktopLayout = true,
                heightMultiplier = homeSettings.adaptiveHeroHeightMultiplier,
            )
            Column(Modifier.fillMaxSize()) {
                if (ownField) {
                    header()
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.padding(horizontal = sectionPadding)) { searchField(Modifier) }
                    Spacer(Modifier.height(14.dp))
                }
                if (heroMeta != null) {
                    HomeHeroSection(
                        items = heroItems.ifEmpty { listOf(heroMeta) },
                        focusedItem = heroMeta,
                        viewportHeight = viewportHeight,
                        sectionPadding = sectionPadding,
                        heightOverride = layout.heroHeight,
                        roundedBottomCorners = !homeSettings.heroAmbientBackgroundEnabled,
                        adaptiveHeroMode = true,
                        ambientBackground = homeSettings.heroAmbientBackgroundEnabled,
                        heroInfoLines = homeSettings.heroInfoLines,
                        heroInfoPriority = homeSettings.heroInfoPriority,
                        heroBadgePlacement = homeSettings.heroBadgePlacement,
                        trailersEnabledInCurrentMode = false,
                        onItemClick = onHeroClick,
                    )
                }
                androidx.compose.foundation.lazy.LazyColumn(state = adaptiveListState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        query.isBlank() -> if (ownField && history.isNotEmpty()) {
                            item(key = "music-search-recent") {
                                RecentSearches(history, sectionPadding, onPick = ::setQuery, onClear = MusicSearchHistory::clear)
                            }
                        }
                        rows.isEmpty() && waiting -> items(2) {
                            HomeSkeletonRow(Modifier.padding(horizontal = sectionPadding), showHeaderAccent = !homeSettings.hideCatalogUnderline)
                        }
                        notFound -> item(key = "music-search-empty") { emptyLine(sectionPadding) }
                        else -> itemsIndexed(rows, key = { _, row -> "music-row:${row.key}" }) { index, row ->
                            shelf(index, row, sectionPadding, null, null, Modifier)
                        }
                    }
                }
            }
            return@BoxWithConstraints
        }

        NuvioScreen(
            horizontalPadding = 0.dp,
            topPadding = 0.dp,
            backgroundColor = Color.Transparent,
            listState = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "music-search-header") {
                Column(Modifier.fillMaxWidth()) {
                    header()
                    Spacer(Modifier.height(12.dp))
                    if (ownField) {
                        Box(Modifier.padding(horizontal = sectionPadding)) { searchField(Modifier) }
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }

            when {
                query.isBlank() -> if (ownField && history.isNotEmpty()) {
                    item(key = "music-search-recent") {
                        RecentSearches(history, sectionPadding, onPick = ::setQuery, onClear = MusicSearchHistory::clear)
                    }
                }
                rows.isEmpty() && waiting -> items(2) {
                    HomeSkeletonRow(
                        modifier = Modifier.padding(horizontal = sectionPadding),
                        showHeaderAccent = !homeSettings.hideCatalogUnderline,
                    )
                }
                notFound -> item(key = "music-search-empty") { emptyLine(sectionPadding) }
                else -> {
                    itemsIndexed(rows, key = { _, row -> "music-row:${row.key}" }) { index, row -> shelf(index, row, sectionPadding, null, null, Modifier) }
                    if (waiting) {
                        item(key = "music-search-loading") {
                            HomeSkeletonRow(
                                modifier = Modifier.padding(horizontal = sectionPadding),
                                showHeaderAccent = !homeSettings.hideCatalogUnderline,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How the music search is laid out: after Home's display mode (Settings > Home). */
internal enum class MusicSearchMode { Basic, Adaptive, Tv }

@Composable
internal fun rememberMusicSearchMode(): MusicSearchMode {
    val settings by remember {
        HomeCatalogSettingsRepository.snapshot()
        HomeCatalogSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    return when {
        !isDesktop -> MusicSearchMode.Basic
        settings.tvModeEnabled -> MusicSearchMode.Tv
        settings.adaptiveHeroEnabled -> MusicSearchMode.Adaptive
        else -> MusicSearchMode.Basic
    }
}

/** The artist a result belongs to (its first credited one). */
private fun MusicHit.artistName(): String? = when (this) {
    is MusicHit.Artist -> artist.name
    is MusicHit.Song -> track.artists.firstOrNull()?.name
    is MusicHit.Video -> track.artists.firstOrNull()?.name
    is MusicHit.Release -> album.artists.firstOrNull()?.name
}

private fun MusicHit.creditedArtists(): List<String> = when (this) {
    is MusicHit.Artist -> emptyList()
    is MusicHit.Song -> track.artists.map { it.name }
    is MusicHit.Video -> track.artists.map { it.name }
    is MusicHit.Release -> album.artists.map { it.name }
}.filter { it.isNotBlank() }.distinct()

/** The result's own picture at backdrop size, for when its artist has no fanart. */
private fun MusicHit.bigArt(): String? = when (this) {
    is MusicHit.Video -> track.coverUrl
    is MusicHit.Song -> track.coverUrl?.let { enlargeYtThumbnail(it, size = 1200) ?: it }
    is MusicHit.Release -> album.thumbnailUrl?.let { enlargeYtThumbnail(it, size = 1200) ?: it }
    is MusicHit.Artist -> artist.thumbnailUrl?.let { enlargeYtThumbnail(it, size = 1200) ?: it }
}

private fun lengthLabel(seconds: Int): String? =
    seconds.takeIf { it > 0 }?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }

/** The hero's item for a result before anything is looked up: what the search itself said. */
private fun MusicHit.baseHeroMeta(): MetaPreview {
    val kind = when (this) {
        is MusicHit.Song -> "song"
        is MusicHit.Release -> "album"
        is MusicHit.Artist -> "artist"
        is MusicHit.Video -> "video"
    }
    return MetaPreview(
        id = key,
        type = MUSIC_HERO_TYPE_PREFIX + kind,
        name = title,
        // No poster: the hero falls back to it for a backdrop, which drew the small artwork blown up before the artist's
        // fanart replaced it. The artwork is carried as the logo instead.
        poster = null,
        // The backdrop is the artist's fanart, known only once looked up: until then nothing (rather than the small
        // artwork blown up, then swapped), and the hero holds its text back too (heroMetadataPending).
        banner = null,
        // The result's own artwork stands where a film's logo would, beside its name (see the hero's music lockup).
        logo = art,
        heroMetadataPending = true,
        posterShape = if (shape == NuvioPosterShape.Landscape) PosterShape.Landscape else PosterShape.Square,
        releaseInfo = (this as? MusicHit.Release)?.album?.year,
        // A song's or video's length sits where a film's age rating would.
        ageRating = when (this) {
            is MusicHit.Song -> lengthLabel(track.durationSeconds)
            is MusicHit.Video -> lengthLabel(track.durationSeconds)
            else -> (this as? MusicHit.Release)?.album?.kind?.takeIf { !it.equals("Album", ignoreCase = true) }
        },
        cast = creditedArtists().map { HeroCastMember(name = it) },
    )
}

/**
 * The hero's item with what TheAudioDB knows: the artist's fanart behind it, an artist's logo for its name, a description
 * (the album's or song's own, else the artist's biography), genres, and the people panel: the credited artists with
 * their photos, or for an artist the ones YouTube Music says its fans also like.
 */
private suspend fun enrichedHeroMeta(hit: MusicHit, catalog: MusicCatalog): MetaPreview = coroutineScope {
    val base = hit.baseHeroMeta()
    val primary = hit.artistName()
    val artistJob = async { primary?.let { MusicHeroInfo.heroArtist(it, (hit as? MusicHit.Artist)?.artist?.browseId) } }
    val releaseJob = async {
        if (primary == null) return@async null
        when (hit) {
            is MusicHit.Release -> MusicHeroInfo.album(primary, hit.album.title)
            is MusicHit.Song -> MusicHeroInfo.track(primary, hit.track.title)?.takeIf { it.description != null }
                ?: hit.track.albumTitle?.let { MusicHeroInfo.album(primary, it) }
            else -> null
        }
    }
    // Each result is described by its own words: an artist by their story, a record by its own (TheAudioDB, else its
    // Wikipedia page), a song or video by the song's own. Nothing borrows the artist's story, so a record with nothing
    // written about it shows no description rather than the artist's.
    val ownText = async {
        if (primary == null) return@async null
        when (hit) {
            is MusicHit.Artist -> null // the artist's story, from artistJob
            is MusicHit.Release -> MusicHeroInfo.albumDescription(primary, hit.album.title)
            is MusicHit.Song -> MusicHeroInfo.songDescription(primary, hit.track.title)
            is MusicHit.Video -> MusicHeroInfo.songDescription(primary, stageSongTitleForVideo(hit.track.title))
        }
    }
    val peopleNames = when (hit) {
        is MusicHit.Artist -> runCatching { catalog.artist(hit.artist.browseId)?.related.orEmpty().map { it.name } }.getOrDefault(emptyList())
        else -> hit.creditedArtists()
    }.take(HERO_PEOPLE_MAX)
    // The people panel's photos are extras: they give way while TheAudioDB's allowance is low (AudioDbGate).
    val people = peopleNames.map { name -> async { HeroCastMember(name = name, photo = MusicHeroInfo.artist(name, essential = false)?.thumb) } }.awaitAll()
    // The facts line where a film's ratings go: a song's year, tempo and chart place; a record's date, label and length.
    val factsJob = async {
        if (primary == null) return@async null
        when (hit) {
            is MusicHit.Song -> songFactsLine(MusicHeroInfo.songFacts(primary, hit.track.title), primary)
            is MusicHit.Video -> songFactsLine(MusicHeroInfo.songFacts(primary, stageSongTitleForVideo(hit.track.title)), primary)
            is MusicHit.Release -> albumFactsLine(MusicHeroInfo.albumFacts(primary, hit.album.title), releaseJob.await()?.score)
            is MusicHit.Artist -> null
        }
    }
    val artist = artistJob.await()
    val release = releaseJob.await()
    // An artist whose name is set as a logo (their "fancy name", as on their own page) shows it like a film's logo;
    // songs and records keep their artwork with the name under it.
    val nameLogo = artist?.logo?.takeIf { hit is MusicHit.Artist }?.also { MusicHeroLogos.mark(it) }
    // Each result its own backdrop: an artist their main photo; a song or video one of the artist's photos picked by the
    // result (so two songs by one artist rarely share it); a record its own cover, blurred, so the hero takes its colours
    // instead of showing a square cover blown up. With no photo at all, the result's cover, blurred the same way.
    val fanarts = artist?.fanarts.orEmpty()
    // YouTube Music's cover first for the backdrop: it is served at up to 1200 px (TheAudioDB's are mostly 700), and a big
    // enough cover is shown as itself (the hero's CoverColourBackdrop); TheAudioDB's stands in when YouTube has none.
    val ownCover = hit.bigArt() ?: release?.cover?.takeIf { hit is MusicHit.Release }
    val banner = when (hit) {
        is MusicHit.Artist -> artist?.fanart ?: fanarts.firstOrNull()
        is MusicHit.Song, is MusicHit.Video -> fanarts.takeIf { it.isNotEmpty() }?.let { it[kotlin.math.abs(hit.key.hashCode()) % it.size] }
        is MusicHit.Release -> null
    } ?: ownCover?.also { MusicHeroLogos.markBlurred(it) }
    // The artwork beside the name at full size (TheAudioDB's HQ cover for a record), and the disc peeking out of it.
    val cover = when (hit) {
        is MusicHit.Artist -> base.logo
        is MusicHit.Release -> release?.cover ?: base.logo
        is MusicHit.Song -> base.logo
        is MusicHit.Video -> "https://i.ytimg.com/vi/${hit.track.videoId}/hqdefault.jpg"
    }
    // The disc of the record the result is on: an album its own, a song or video the album it comes from.
    val discAlbum = when (hit) {
        is MusicHit.Release -> hit.album.title
        is MusicHit.Song -> hit.track.albumTitle
        else -> null
    }
    if (cover != null && primary != null && discAlbum != null) {
        MusicHeroInfo.discArt(primary, discAlbum)?.let { MusicHeroLogos.setDisc(cover, it) }
    }
    factsJob.await()?.let { MusicHeroLogos.setFacts(base.id, it) }
    base.copy(
        banner = banner,
        logo = nameLogo ?: cover,
        heroMetadataPending = false,
        description = (if (hit is MusicHit.Artist) artist?.biography else ownText.await())?.let(::firstParagraph),
        genres = listOfNotNull(release?.genre ?: artist?.genre, artist?.style).distinctBy { it.lowercase() },
        releaseInfo = base.releaseInfo ?: release?.year,
        cast = people,
    )
}

/** A video's title as the song it is: "Song (Official Video)" looked up as "Song". */
private fun stageSongTitleForVideo(title: String): String =
    title.replace(Regex("""\s*[(\[][^)\]]*(official|video|lyric|audio|visuali[sz]er|live)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim().ifEmpty { title }

/** A biography's first paragraph: the hero shows a few lines, and the rest of a long one only runs on past them. */
private fun firstParagraph(text: String): String =
    text.split("\n").map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: text.trim()

/** Recent searches as a short list of lines, like the media search's, with a way to clear them. */
@Composable
private fun RecentSearches(recent: List<String>, padding: Dp, onPick: (String) -> Unit, onClear: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = padding)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Recent searches", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onClear) { Text("Clear", color = colors.textMuted) }
        }
        recent.take(RECENT_SHOWN).forEach { line ->
            androidx.compose.material3.TextButton(onClick = { onPick(line) }) {
                Text(line, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

/** The More menu for one result (right-click, a held OK, the Menu key). */
private fun menuFor(hit: MusicHit, runner: HomeRunner): List<PosterZoomOverlayAction> = when (hit) {
    is MusicHit.Song -> {
        val song = hit.track
        listOfNotNull(
            PosterZoomOverlayAction(Icons.Rounded.PlayArrow, "Play", onSelected = { runner.track(song, PlayHow.Now) }),
            PosterZoomOverlayAction(Icons.Rounded.SkipNext, "Play next", onSelected = { runner.track(song, PlayHow.Next) }),
            PosterZoomOverlayAction(Icons.Rounded.PlaylistAdd, "Add to queue", onSelected = { runner.track(song, PlayHow.Queue) }),
            PosterZoomOverlayAction(Icons.Rounded.Radio, "Start radio", onSelected = { runner.songRadio(song) }),
            song.albumBrowseId?.let { id ->
                PosterZoomOverlayAction(Icons.Rounded.Album, song.albumTitle?.let { "Open $it" } ?: "Open the album", onSelected = { runner.openAlbum(id) }, group = 1)
            },
            song.artists.firstOrNull()?.browseId?.let { id ->
                PosterZoomOverlayAction(Icons.Rounded.Person, "Go to the artist", onSelected = { runner.openArtist(id) }, group = 1)
            },
        )
    }
    is MusicHit.Release -> runner.albumMenu(hit.album)
    is MusicHit.Artist -> runner.artistMenu(hit.artist)
    is MusicHit.Video -> runner.videoMenu(hit.track)
}

private const val VOICE_OWNER = "music-search"
private const val SUGGEST_PAUSE_MS = 150L
private const val SEARCH_PAUSE_MS = 350L
private const val MIN_QUERY_LENGTH = 2
private const val RECENT_SHOWN = 8
private const val COVER_SIZE = 544
private const val HERO_INFO_PAUSE_MS = 250L

/**
 * The fan counts of up to [FAN_LOOKUPS] of [names], asked at once and waited for at most [FAN_WAIT_MS]: a row's order uses
 * what is known by then.
 */
private suspend fun warmFans(names: List<String>) {
    val wanted = names.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(FAN_LOOKUPS)
    if (wanted.isEmpty()) return
    kotlinx.coroutines.withTimeoutOrNull(FAN_WAIT_MS) {
        coroutineScope { wanted.map { name -> async { MusicHeroInfo.artistFans(name) } }.awaitAll() }
    }
}

private const val FAN_LOOKUPS = 12
private const val FAN_WAIT_MS = 1_500L

/** A "single" with this many songs or more is an EP; at most [EP_CHECKS] singles a search names are looked at. */
private const val EP_MIN_SONGS = 3
private const val EP_CHECKS = 3

/** fork:music-search - TV Mode's hero on the music search: lifted 56 dp off the shelf, the plot up to 9 lines. */
private val MUSIC_HERO_TUNING = com.nuvio.app.features.home.components.ImmersiveHeroTuning(lift = 56.dp, synopsisMaxLines = 9)

/** One wheel step per this long in TV Mode: a flick of the wheel sends several notches at once. */
private val WHEEL_STEP_GAP = kotlin.time.Duration.parse("180ms")
private const val HERO_PEOPLE_MAX = 6
