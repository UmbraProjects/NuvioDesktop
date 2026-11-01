package com.nuvio.app.features.music

import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.features.music.library.musicKeyFocusRing
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.app.features.music.library.LibraryView
import com.nuvio.app.features.music.library.constellationGaps
import com.nuvio.app.features.music.library.musicGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.arrowFocusDirection
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.LadderAlbumRef // fork:music
import com.nuvio.app.features.music.library.LadderRequest // fork:music
import com.nuvio.app.features.music.library.MusicLadderScreen // fork:music
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.ladderOffers // fork:music
import com.nuvio.app.features.music.library.MusicLibraryShelf
import com.nuvio.app.features.music.library.SearchStartView // fork:music
import com.nuvio.app.features.music.library.LadderProgressStore // fork:music
import com.nuvio.app.features.music.library.continueCards // fork:music
import com.nuvio.app.features.music.library.genreTiles // fork:music
import com.nuvio.app.features.music.library.ladderStarters // fork:music
import com.nuvio.app.features.music.library.startIsEmpty // fork:music
import com.nuvio.app.features.music.library.startArt // fork:music
import com.nuvio.app.features.music.library.MusicSearchHistory
import androidx.compose.runtime.collectAsState // fork:music
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.features.music.library.enlargeYtThumbnail
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.ViewModule
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.features.discover.BrowseFilterIconAction
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.core.ui.ditheredBackground
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.features.settings.settingsSkyBackground
import kotlinx.coroutines.CancellationException
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SmartDisplay
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.secondaryClick
import androidx.compose.material.icons.rounded.QueueMusic
import kotlinx.coroutines.delay

/** fork:music-youtube - [YouTube] searches plain YouTube for the query as typed, for songs YouTube Music has no album for. */
private enum class MusicSearchKind { Albums, Artists, YouTube }

private enum class MusicSection { Search, Library }

/**
 * fork:music-navbar - the Music tab's search box. One object rather than the tab's own state, because
 * on the floating top bar the box lives in the bar itself (see [MusicTopBarContent]) and the tab only
 * shows what it finds; without the floating bar the tab draws the box as before.
 */
private object MusicTabState {
    var query by mutableStateOf("")

    // Opens on the shelf when the person has saved artists, and on Search when there is nothing to show
    // yet. Loading is synchronous and cheap, so the first frame is already the right one.
    var section by mutableStateOf(
        MusicLibraryRepository.ensureLoaded().let {
            if (MusicLibraryRepository.state.value.artists.isEmpty()) MusicSection.Search else MusicSection.Library
        },
    )

    /** The Library's name / genre filter: the one search box types into it while [filtering] is on. */
    var libraryFilter by mutableStateOf("")
    var filtering by mutableStateOf(false)

    /** True while the top bar holds the box, so the tab leaves its own out. */
    var barHosted by mutableStateOf(false)

    /** The page under the box (the Library's view, the start screen or the results): Escape and Enter go there. */
    val bodyFocus = FocusRequester()

    /** The row of icons under the box (the Library's views, or Search's kinds): Down from the box and its buttons. */
    val pillsFocus = FocusRequester()

    /** Down from the bar: onto that row. */
    fun focusPills(): Boolean = runCatching { pillsFocus.requestFocus() }.getOrDefault(false)

    /** Enter was pressed before the results came in: they take focus when they arrive. */
    var focusResultsWhenReady by mutableStateOf(false)
}

/** fork:music-navbar - the recent searches the bar-hosted box offers, highlighted row [picked] (-1 for none). */
class MusicBarSuggestions(
    val items: List<String>,
    val picked: Int,
    val onPick: (String) -> Unit,
)

/**
 * fork:music-navbar - what the app's top bar needs from the Music search box it hosts: the recent searches to hang
 * under the bar (drawn by the bar, like its own search history), and the search shortcut, which focuses this box.
 */
object MusicTopBarSearch {
    var suggestions by mutableStateOf<MusicBarSuggestions?>(null)
        internal set

    internal var focusRequests by mutableIntStateOf(0)
        private set

    /** The search key on the Music tab: into the Music box rather than the video search. */
    fun requestFocus() {
        focusRequests++
    }
}

/**
 * fork:music-navbar - what the app's floating top bar shows while the Music tab is open: a button back
 * to the regular bar (it returns to the last tab, through [onLeave]) and the Music search box, so the
 * tab's own header no longer takes the space under the bar.
 */
@Composable
fun MusicTopBarContent(onLeave: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        MusicTabState.barHosted = true
        onDispose { MusicTabState.barHosted = false }
    }
    Row(
        modifier.fillMaxWidth().padding(end = 6.dp).musicKeyboardRoot(takeFocus = false),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The swap button is centred on the pill's rounded end, not on the box from the edge to the divider: the curve
        // takes area off the left of that box, so a box-centred icon read as sitting too far left.
        NuvioPosterHoverTooltip(title = "Back to the main bar") {
            MusicFieldIconButton(
                icon = Icons.Rounded.SwapHoriz,
                description = "Back to the main bar",
                active = false,
                onClick = onLeave,
                modifier = Modifier
                    .padding(start = 10.dp, end = 4.dp)
                    .onKeyEvent { arrowFocusDirection(it) == FocusDirection.Down && MusicTabState.focusPills() },
            )
        }
        MusicFieldDivider()
        Spacer(Modifier.width(6.dp))
        MusicSearchBox(chromeless = true, modifier = Modifier.weight(1f))
    }
}

/** The search box with its Library buttons (filter, refresh, the Library toggle), on the bar or in the tab. */
@Composable
private fun MusicSearchBox(chromeless: Boolean, modifier: Modifier = Modifier) {
    val state = MusicTabState
    val library = state.section == MusicSection.Library
    // In the Library the filter icon turns the box into the Library's filter, the same box on every view.
    val filterMode = library && state.filtering
    val fieldFocus = remember { FocusRequester() }
    // The buttons at the box's right end, in order. They sit inside the text field, which takes the arrow keys bubbling
    // up from them as cursor moves, so each button moves focus along the row itself: Left from the first goes back to
    // the box, Right from the last goes nowhere.
    val buttonFocus = remember { List(3) { FocusRequester() } }
    val trailingFocus = buttonFocus[0]
    val buttonCount = if (library) 3 else 1
    fun trailingButton(index: Int) = Modifier
        .focusRequester(buttonFocus[index])
        .onKeyEvent { event ->
            when (arrowFocusDirection(event)) {
                // A disabled button (Refresh while nothing can refresh) takes no focus: the move goes on past it.
                FocusDirection.Left -> {
                    val targets = (index - 1 downTo 0).map { buttonFocus[it] } + fieldFocus
                    targets.firstOrNull { runCatching { it.requestFocus() }.getOrDefault(false) }
                    true
                }
                FocusDirection.Right -> {
                    (index + 1 until buttonCount).map { buttonFocus[it] }
                        .firstOrNull { runCatching { it.requestFocus() }.getOrDefault(false) }
                    true
                }
                FocusDirection.Down -> MusicTabState.focusPills()
                else -> false
            }
        }
    MusicGlassField(
        value = if (filterMode) state.libraryFilter else state.query,
        onValueChange = {
            if (filterMode) {
                state.libraryFilter = it
            } else {
                state.query = it
                // Typing from the Library goes back to Search: outside filter mode the box searches.
                if (it.isNotBlank()) state.section = MusicSection.Search
            }
        },
        placeholder = if (filterMode) "Filter by name or genre" else "Search albums and artists",
        leadingIcon = if (filterMode) Icons.Rounded.FilterList else Icons.Rounded.Search,
        modifier = modifier,
        chromeless = chromeless,
        suggestions = if (filterMode) emptyList() else MusicSearchHistory.recent.collectAsState().value.take(MUSIC_RECENT_SHOWN),
        onSuggestion = {
            state.query = it
            state.section = MusicSection.Search
        },
        fieldFocus = fieldFocus,
        trailingFocus = trailingFocus,
    ) {
        MusicFieldDivider()
        if (library) {
            val libraryState by MusicLibraryRepository.state.collectAsState()
            MusicFieldIconButton(
                icon = Icons.Rounded.FilterList,
                description = if (state.filtering) "Back to search" else "Filter your library",
                active = state.filtering,
                onClick = {
                    state.filtering = !state.filtering
                    if (!state.filtering) state.libraryFilter = ""
                },
                modifier = trailingButton(0),
            )
            MusicFieldIconButton(
                icon = Icons.Rounded.Refresh,
                description = "Refresh all artists",
                active = false,
                enabled = libraryState.artists.isNotEmpty() && libraryState.working.isEmpty(),
                onClick = { MusicLibraryRepository.refreshAll() },
                modifier = trailingButton(1),
            )
        }
        MusicFieldIconButton(
            icon = Icons.Rounded.LibraryMusic,
            description = if (library) "Back to search" else "Your library",
            active = library,
            onClick = { state.section = if (library) MusicSection.Search else MusicSection.Library },
            modifier = trailingButton(if (library) 2 else 0),
        )
    }
}

/** Whether these results answer a search of [kind], so they can stay on screen while the next one loads. */
private fun MusicSearchResults.answers(kind: MusicSearchKind): Boolean = when (this) {
    is MusicSearchResults.Albums -> kind == MusicSearchKind.Albums
    is MusicSearchResults.Artists -> kind == MusicSearchKind.Artists
    is MusicSearchResults.Videos -> kind == MusicSearchKind.YouTube
    else -> false
}

private sealed interface MusicSearchResults {
    data object Idle : MusicSearchResults
    data object Loading : MusicSearchResults
    data object Empty : MusicSearchResults
    data object Failed : MusicSearchResults // the search itself could not run (offline, YouTube Music unreachable), as opposed to Empty
    data class Albums(val items: List<MusicAlbumSummary>) : MusicSearchResults
    data class Artists(val items: List<MusicArtistSummary>) : MusicSearchResults
    data class Videos(val items: List<MusicTrack>) : MusicSearchResults // fork:music-youtube
}

private const val MIN_QUERY_LENGTH = 2
private const val TYPING_PAUSE_MS = 350L

/**
 * The Music tab: one centred glass search box, with Albums and Artists as the two kinds of result
 * and a toggle at its right end that swaps the page to the Library. Searching runs a moment after
 * the last keystroke, and results are a centred grid of covers. Tapping a result opens its screen through [onAlbumClick]
 * or [onArtistClick]; this screen starts no playback itself. A library artist opens through the
 * same [onArtistClick] as a search result: both doors lead to one artist page.
 *
 * [topChromePadding] keeps the search box clear of the floating top bar, like the other tabs.
 */
@Composable
fun MusicTabScreen(
    onAlbumClick: (browseId: String) -> Unit,
    onArtistClick: (browseId: String) -> Unit,
    /** fork:music-playlists - opens a playlist's page. */
    onPlaylistClick: (playlistId: String) -> Unit = {},
    modifier: Modifier = Modifier,
    topChromePadding: Dp = 0.dp,
    catalog: MusicCatalog = remember { defaultMusicCatalog() },
) {
    val colors = MaterialTheme.nuvio.colors
    // The box's state is shared with the top bar (MusicTabState); coming back to the tab lands where the person left it.
    var query by MusicTabState::query
    var section by MusicTabState::section
    val libraryFilter = MusicTabState.libraryFilter
    val filtering = MusicTabState.filtering
    var kind by rememberSaveable { mutableStateOf(MusicSearchKind.Albums) }
    var results by remember { mutableStateOf<MusicSearchResults>(MusicSearchResults.Idle) }
    var attempt by remember { mutableStateOf(0) } // bumped by "Try again" to rerun the same search
    var ladder by remember { mutableStateOf<LadderRequest?>(null) } // fork:music - the open ladder, if any
    var libraryView by rememberSaveable { mutableStateOf(LibraryView.Shelf) }
    // A ladder asked for from the Stage (a genre chip): open it over a cleared search.
    val pendingLadder by MusicPlayback.pendingLadder.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLadder) {
        val wanted = pendingLadder ?: return@LaunchedEffect
        MusicTabState.query = ""
        ladder = wanted
        MusicPlayback.pendingLadder.value = null
    }
    // A search typed into the top bar while a ladder is open is meant to be seen.
    LaunchedEffect(query) {
        if (query.isNotBlank()) ladder = null else MusicTabState.focusResultsWhenReady = false
    }

    // Restarts on every keystroke or kind change, so the delay is the typing pause.
    LaunchedEffect(query, kind, attempt) {
        val q = query.trim()
        if (q.length < MIN_QUERY_LENGTH) {
            results = MusicSearchResults.Idle
            return@LaunchedEffect
        }
        delay(TYPING_PAUSE_MS)
        // Results of the same kind stay up while the next ones load, so typing does not blank the
        // grid on every keystroke; anything else (first search, a kind change, an error) shows the spinner.
        if (!results.answers(kind)) results = MusicSearchResults.Loading
        results = try {
            when (kind) {
                MusicSearchKind.Albums -> catalog.searchAlbums(q).let {
                    if (it.isEmpty()) MusicSearchResults.Empty else MusicSearchResults.Albums(it)
                }
                MusicSearchKind.Artists -> catalog.searchArtists(q).let {
                    if (it.isEmpty()) MusicSearchResults.Empty else MusicSearchResults.Artists(it)
                }
                MusicSearchKind.YouTube -> catalog.searchYouTube(q).let {
                    if (it.isEmpty()) MusicSearchResults.Empty else MusicSearchResults.Videos(it)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MusicSearchResults.Failed
        }
        if (results !is MusicSearchResults.Empty && results !is MusicSearchResults.Failed) MusicSearchHistory.record(q) // feeds Recent searches
    }

    // fork:music - a ladder replaces the tab body while it is open
    val openLadder = ladder
    if (openLadder != null) {
        // The ladder wants the room under its cards, so the player bar shrinks to a pill while it is open.
        DisposableEffect(Unit) {
            MusicPlayback.slimBar.value = true
            onDispose { MusicPlayback.slimBar.value = false }
        }
        // The same top as the tab's own search and Library, so opening a ladder does not drop the page down.
        MusicLadderScreen(
            openLadder,
            onBack = { ladder = null },
            onRequest = { ladder = it },
            modifier = modifier,
            topChromePadding = if (MusicTabState.barHosted) (topChromePadding - BAR_HOSTED_TRIM).coerceAtLeast(0.dp) else topChromePadding,
        )
        return
    }

    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .fillMaxSize()
            // The settings sky, so the glass header and the cover grid sit on the same night wash.
            .settingsSkyBackground(tokens.colors.background, tokens.colors.accent)
            // fork:music-navbar - with the box on the bar, what is under it starts right below the bar.
            .padding(top = if (MusicTabState.barHosted) (topChromePadding - BAR_HOSTED_TRIM).coerceAtLeast(0.dp) else topChromePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MUSIC_HEADER_MAX_WIDTH)
                .fillMaxWidth()
                // Snug under the top bar, so the page below gets the height.
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // fork:music-navbar - on the floating top bar the box is in the bar itself.
            if (!MusicTabState.barHosted) {
                // A name goes in here, not an address: about the view icons' width with a fifth more on each side.
                MusicSearchBox(chromeless = false, modifier = Modifier.widthIn(max = MUSIC_SEARCH_WIDTH).fillMaxWidth())
            }
            if (section == MusicSection.Library) {
                val libraryState by MusicLibraryRepository.state.collectAsState()
                // The views as icons, the way Discover's row header shows its filters; the one in use is lit.
                Row(
                    modifier = Modifier.focusRequester(MusicTabState.pillsFocus),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LIBRARY_VIEW_ICONS.forEachIndexed { index, (view, icon, label) ->
                        Box(Modifier.rowEndStops(first = index == 0, last = index == LIBRARY_VIEW_ICONS.lastIndex)) {
                            BrowseFilterIconAction(icon, label, on = libraryView == view) { libraryView = view }
                        }
                    }
                }
                // fork:music - the Constellation's thin spots: genres you tag but barely own, each a ladder into it.
                if (libraryView == LibraryView.Constellation) {
                    val gaps = remember(libraryState.artists) { constellationGaps(libraryState.artists) }
                    if (gaps.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Thin spots", style = MaterialTheme.typography.labelLarge, color = tokens.colors.textMuted)
                            gaps.forEach { gap ->
                                MusicGlassPill(gap.genre.replaceFirstChar { it.uppercase() }, selected = false, accent = true) {
                                    ladder = LadderRequest.Genre(gap.genre)
                                }
                            }
                        }
                    }
                }
            }
            if (section == MusicSection.Search) {
                Row(
                    modifier = Modifier.focusRequester(MusicTabState.pillsFocus),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The kinds as icons, like the Library's views above them; the one in use is lit.
                    Box(Modifier.rowEndStops(first = true, last = false)) {
                        BrowseFilterIconAction(Icons.Rounded.Album, "Albums", on = kind == MusicSearchKind.Albums) { kind = MusicSearchKind.Albums }
                    }
                    BrowseFilterIconAction(Icons.Rounded.Person, "Artists", on = kind == MusicSearchKind.Artists) { kind = MusicSearchKind.Artists }
                    BrowseFilterIconAction(Icons.Rounded.SmartDisplay, "YouTube: songs not on an album", on = kind == MusicSearchKind.YouTube) {
                        kind = MusicSearchKind.YouTube
                    }
                    // fork:music - the first result's ladders, as short pills beside the kind
                    val offers = when (val shown = results) {
                        is MusicSearchResults.Albums -> {
                            val first = shown.items.firstOrNull()
                            val ref = first?.let { f -> f.artists.firstOrNull()?.name?.let { LadderAlbumRef(f.title, it, f.year) } }
                            ladderOffers(query, null, ref)
                        }
                        is MusicSearchResults.Artists -> ladderOffers(query, shown.items.firstOrNull()?.name)
                        else -> emptyList()
                    }
                    if (offers.isNotEmpty()) {
                        Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 20.dp).background(NuvioGlass.EdgeColor))
                        offers.forEach { offer ->
                            MusicGlassPill(offer.label, selected = false, accent = true) { ladder = offer.request }
                        }
                    }
                }
            }
        }

        if (section == MusicSection.Library) {
            if (libraryView == LibraryView.Playlists) {
                com.nuvio.app.features.music.playlist.PlaylistsView(onOpenPlaylist = onPlaylistClick, modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(MusicTabState.bodyFocus))
                return@Column
            }
            MusicLibraryShelf(
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(MusicTabState.bodyFocus),
                onOpenLadder = { ladder = it }, // fork:music
                view = libraryView,
                filterQuery = if (filtering) libraryFilter else "",
            )
            return@Column
        }

        // fork:music - before anything is typed, the start screen fills the blank space (it scrolls itself)
        if (results is MusicSearchResults.Idle) {
            val libraryArtists = MusicLibraryRepository.state.collectAsState().value.artists
            // Recent searches live in the search box's own panel now, not as chips on this page.
            val recent = emptyList<String>()
            val starters = remember(libraryArtists) { ladderStarters(libraryArtists) }
            val tiles = remember(libraryArtists) { genreTiles(libraryArtists) }
            val continues = remember(libraryArtists) { continueCards(LadderProgressStore.resumable()) }
            val startArtwork = remember(libraryArtists) { startArt(libraryArtists) }
            if (!startIsEmpty(recent, starters, continues, tiles)) {
                SearchStartView(
                    recent = recent, starters = starters, continues = continues, tiles = tiles,
                    onRecent = { query = it }, onLadder = { ladder = it }, onGenre = { query = it },
                    modifier = Modifier.weight(1f).focusRequester(MusicTabState.bodyFocus), art = startArtwork,
                )
                return@Column
            }
        }

        when (val shown = results) {
            MusicSearchResults.Idle -> MusicMessage("Search for an album, an artist, or a song on YouTube.")
            MusicSearchResults.Loading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            MusicSearchResults.Failed -> MusicMessage(
                "Couldn't reach YouTube Music. Check your connection.",
                actionLabel = "Try again",
                onAction = { attempt++ },
            )
            MusicSearchResults.Empty -> MusicMessage("Nothing found. Check the spelling.")
            is MusicSearchResults.Albums -> MusicResultGrid(Modifier.weight(1f)) {
                items(shown.items, key = { it.browseId }) { album ->
                    MusicAlbumTile(album, onClick = { onAlbumClick(album.browseId) })
                }
            }
            is MusicSearchResults.Artists -> MusicResultGrid(Modifier.weight(1f)) {
                items(shown.items, key = { it.browseId }) { artist ->
                    MusicArtistTile(artist, onClick = { onArtistClick(artist.browseId) })
                }
            }
            is MusicSearchResults.Videos -> MusicResultGrid(Modifier.weight(1f)) {
                items(shown.items, key = { it.videoId }) { track ->
                    MusicVideoTile(track)
                }
            }
        }
    }
}

/** How many recent searches the search box offers. */
private const val MUSIC_RECENT_SHOWN = 6

/** The tabs' top padding leaves room under the bar for a header; with the box on the bar most of it is spare. */
private val BAR_HOSTED_TRIM = 36.dp

/** The Library's views, as the icons under the bar. */
private val LIBRARY_VIEW_ICONS = listOf(
    Triple(LibraryView.Shelf, Icons.Rounded.ViewModule, "Shelf"),
    Triple(LibraryView.Constellation, Icons.Rounded.Hub, "Constellation"),
    Triple(LibraryView.Eras, Icons.Rounded.Timeline, "Eras"),
    Triple(LibraryView.Playlists, Icons.Rounded.QueueMusic, "Playlists"),
)

/** The search header is a centred column this wide at most; the grid below gets the room. */
private val MUSIC_HEADER_MAX_WIDTH = 720.dp

/** With the Constellation's filter beside the search, the bar gets this much room for the two halves. */
/** The search box: the Library's view pills (about 260 dp) and a fifth of that again on each side. */
private val MUSIC_SEARCH_WIDTH = 380.dp

/** Results are centred in a column this wide at most, so a wide window doesn't stretch one row across it. */
private val MUSIC_GRID_MAX_WIDTH = 1480.dp

private val MUSIC_TILE_MIN_WIDTH = 168.dp

/** A text box in the settings glass: [leadingIcon], the text, a clear button, then [trailing] (the Library toggle). */
@Composable
private fun MusicGlassField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    /** Drawn without its own glass, for a box that sits inside the top bar's. */
    chromeless: Boolean = false,
    /** Recent searches, offered in a panel under the empty box while it has focus (like the top bar's search). */
    suggestions: List<String> = emptyList(),
    onSuggestion: (String) -> Unit = {},
    /** The box's own focus, and the first button in [trailing] (Right from an empty box goes there). */
    fieldFocus: FocusRequester = remember { FocusRequester() },
    trailingFocus: FocusRequester? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val query = value
    val onQueryChange = onValueChange
    val tokens = MaterialTheme.nuvio
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // A pill, like the top bar's search.
    val shape = RoundedCornerShape(percent = 50)
    var fieldWidthPx by remember { mutableIntStateOf(0) }
    var picked by remember(suggestions) { mutableIntStateOf(-1) }
    val showSuggestions = focused && query.isEmpty() && suggestions.isNotEmpty()
    // On the top bar the bar draws the recent searches itself, attached under it like its own search history.
    if (chromeless) {
        val published = if (showSuggestions) MusicBarSuggestions(suggestions, picked, onSuggestion) else null
        SideEffect { MusicTopBarSearch.suggestions = published }
        DisposableEffect(Unit) { onDispose { MusicTopBarSearch.suggestions = null } }
    }
    // The search shortcut focuses the box. Requests made before this box existed are not replayed, and the key's own
    // "s" is kept out of the empty box it just focused.
    val focusRequester = fieldFocus
    var handledFocusRequests by remember { mutableIntStateOf(MusicTopBarSearch.focusRequests) }
    var swallowActivationKey by remember { mutableStateOf(false) }
    LaunchedEffect(MusicTopBarSearch.focusRequests) {
        if (MusicTopBarSearch.focusRequests == handledFocusRequests) return@LaunchedEffect
        handledFocusRequests = MusicTopBarSearch.focusRequests
        swallowActivationKey = true
        runCatching { focusRequester.requestFocus() }
        delay(150)
        swallowActivationKey = false
    }
    Box(modifier) {
    BasicTextField(
        value = query,
        onValueChange = { next ->
            if (swallowActivationKey && query.isEmpty() && next.length == 1) {
                swallowActivationKey = false
            } else {
                onQueryChange(next)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onSizeChanged { fieldWidthPx = it.width }
            .height(44.dp)
            .then(
                if (chromeless) {
                    Modifier
                } else {
                    Modifier
                        .ditheredBackground(NuvioGlass.Fill, shape)
                        .border(
                            width = tokens.borders.thin,
                            brush = if (focused) SolidColor(tokens.colors.borderFocus) else NuvioGlass.Edge,
                            shape = shape,
                        )
                },
            )
            .trackTextInputFocus()
            .onFocusChanged { focused = it.isFocused }
            // Escape clears the query and lets go of the field; both halves of the key are eaten so
            // the screen's own Escape (back) doesn't fire on the same press.
            .onPreviewKeyEvent { event ->
                // Up and Down walk the recent searches, Enter picks the highlighted one.
                if (showSuggestions && event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionDown -> {
                            picked = (picked + 1).coerceAtMost(suggestions.lastIndex)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> {
                            picked = (picked - 1).coerceAtLeast(-1)
                            return@onPreviewKeyEvent true
                        }
                        Key.Enter, Key.NumPadEnter -> if (picked >= 0) {
                            onSuggestion(suggestions[picked])
                            return@onPreviewKeyEvent true
                        }
                        else -> Unit
                    }
                }
                // The buttons at the box's right end sit inside the field, so their keys pass through here too: what
                // follows is for the box's own text only.
                if (!focused) return@onPreviewKeyEvent false
                // Down leaves the box for the row of icons under it (the field would take it as a cursor move).
                if (event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown && MusicTabState.focusPills()) {
                    return@onPreviewKeyEvent true
                }
                // An empty box has no cursor to move: Left and Right go on to the buttons either side of it.
                if (query.isEmpty() && event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            focusManager.moveFocus(FocusDirection.Left)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionRight -> {
                            if (trailingFocus != null) runCatching { trailingFocus.requestFocus() }
                            return@onPreviewKeyEvent true
                        }
                        else -> Unit
                    }
                }
                // Backspace in an empty box is the app's Back, as everywhere else; there is nothing to delete.
                if (query.isEmpty() && event.key == Key.Backspace) {
                    if (event.type == KeyEventType.KeyDown) com.nuvio.app.core.ui.DesktopNavigationGestureBridge.requestBack()
                    return@onPreviewKeyEvent true
                }
                // Enter takes the keyboard to the results (now, or as soon as they come in).
                if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && query.isNotBlank()) {
                    if (event.type == KeyEventType.KeyDown) MusicTabState.focusResultsWhenReady = true
                    return@onPreviewKeyEvent true
                }
                if (event.key != Key.Escape) return@onPreviewKeyEvent false
                // Escape clears the box and puts the keyboard back on the page under it, rather than nowhere.
                if (event.type == KeyEventType.KeyUp) {
                    onQueryChange("")
                    if (!runCatching { MusicTabState.bodyFocus.requestFocus() }.getOrDefault(false)) focusManager.clearFocus()
                }
                true
            },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
        cursorBrush = SolidColor(tokens.colors.accent),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp).padding(start = if (chromeless) 4.dp else 18.dp, end = if (chromeless) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(leadingIcon, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = tokens.colors.textMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(20.dp).clip(CircleShape).musicKeyFocusRing(CircleShape).clickable { onQueryChange("") },
                    )
                }
                trailing()
            }
        },
    )
    if (showSuggestions && !chromeless) {
        val density = LocalDensity.current
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(0, with(density) { (44.dp + 6.dp).roundToPx() }),
            // The box keeps focus (and the typing) while the panel is open.
            properties = PopupProperties(focusable = false),
        ) {
            MusicRecentPanel(
                items = suggestions,
                picked = picked,
                onPick = onSuggestion,
                modifier = Modifier.width(with(density) { fieldWidthPx.toDp() }),
            )
        }
    }
    }
}

/** The recent searches under the search box: the settings glass, one row each with a clock. */
@Composable
private fun MusicRecentPanel(items: List<String>, picked: Int, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .background(NuvioGlass.night(tokens.colors.background, tokens.colors.accent))
            .musicGlass(shape)
            .padding(6.dp),
    ) {
        items.forEachIndexed { index, text ->
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (hovered || index == picked) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                    .hoverable(source)
                    .musicKeyFocusRing(RoundedCornerShape(percent = 50)).clickable(interactionSource = source, indication = null) { onPick(text) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MusicFieldDivider() {
    Box(Modifier.size(width = 1.dp, height = 22.dp).background(NuvioGlass.EdgeColor))
}

/** A square icon button inside a [MusicGlassField]; tinted with the accent while [active]. */
@Composable
private fun MusicFieldIconButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (active) tokens.colors.accent.copy(alpha = 0.22f) else Color.Transparent)
            .musicKeyFocusRing(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = when {
                active -> tokens.colors.accent
                enabled -> tokens.colors.textSecondary
                else -> tokens.colors.textDisabled
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A small glass pill: the search kind, or a ladder offer ([accent]) beside it. */
@Composable
private fun MusicGlassPill(label: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val keyFocused by interaction.collectIsKeyboardFocusedAsState()
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            selected -> tokens.colors.onAccent
            accent -> tokens.colors.accent
            else -> tokens.colors.textSecondary
        },
        maxLines = 1,
        modifier = Modifier
            .musicGlass(shape, raised = hovered, selected = selected, keyFocused = keyFocused)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** The Music tab's page body, where the keyboard lands when the tab opens. */
internal fun musicTabBodyFocus(): FocusRequester = MusicTabState.bodyFocus

/** Left out of the first of a row of icons, or Right out of the last, goes nowhere. */
private fun Modifier.rowEndStops(first: Boolean, last: Boolean): Modifier = onKeyEvent { event ->
    val direction = arrowFocusDirection(event)
    (first && direction == FocusDirection.Left) || (last && direction == FocusDirection.Right)
}

/** Search results as a centred grid of covers. */
@Composable
private fun MusicResultGrid(modifier: Modifier, content: LazyGridScope.() -> Unit) {
    LaunchedEffect(MusicTabState.focusResultsWhenReady) {
        if (!MusicTabState.focusResultsWhenReady) return@LaunchedEffect
        repeat(8) {
            delay(40)
            if (runCatching { MusicTabState.bodyFocus.requestFocus() }.getOrDefault(false)) {
                MusicTabState.focusResultsWhenReady = false
                return@LaunchedEffect
            }
        }
    }
    Box(modifier.fillMaxWidth().focusRequester(MusicTabState.bodyFocus), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(MUSIC_TILE_MIN_WIDTH),
            modifier = Modifier.widthIn(max = MUSIC_GRID_MAX_WIDTH).fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            content = content,
        )
    }
}

@Composable
private fun MusicAlbumTile(album: MusicAlbumSummary, onClick: () -> Unit) {
    val subtitle = listOfNotNull(
        album.artists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
        album.year,
    ).joinToString(" · ")
    MusicTile(
        title = album.title,
        subtitle = subtitle,
        // Albums are the norm; only the other kinds (Single, EP) get a corner tag.
        badge = album.kind?.takeIf { !it.equals("Album", ignoreCase = true) },
        onClick = onClick,
    ) { MusicCoverFill(album.thumbnailUrl, album.title, circle = false) }
}

/** fork:music-youtube - a YouTube hit: a click plays it on its own, a right click puts it after what is queued. */
@Composable
private fun MusicVideoTile(track: MusicTrack) {
    MusicTile(
        title = track.title,
        subtitle = track.artists.firstOrNull()?.name,
        badge = youTubeLengthLabel(track.durationSeconds),
        onClick = { MusicPlayback.player.play(youTubeSingleAlbum(track)) },
        onSecondary = {
            if (MusicPlayback.player.enqueue(youTubeSingleAlbum(track))) NuvioToastController.show("Queued ${track.title}")
        },
    ) { MusicCoverFill(track.coverUrl, track.title, circle = false) }
}

@Composable
private fun MusicArtistTile(artist: MusicArtistSummary, onClick: () -> Unit) {
    MusicTile(title = artist.name, subtitle = null, badge = null, centred = true, onClick = onClick) {
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            MusicCoverFill(artist.thumbnailUrl, artist.name, circle = true)
        }
    }
}

/** A square artwork with its caption under it; lifts on hover and rings on keyboard focus. */
@Composable
private fun MusicTile(
    title: String,
    subtitle: String?,
    badge: String?,
    onClick: () -> Unit,
    centred: Boolean = false,
    onSecondary: (() -> Unit)? = null,
    artwork: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val keyFocused by interaction.collectIsKeyboardFocusedAsState()
    val scale by animateFloatAsState(if (hovered || keyFocused) 1.03f else 1f, label = "musicTileScale")
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .secondaryClick(onSecondary)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .ditheredBackground(NuvioGlass.Fill, shape)
                .border(
                    width = if (keyFocused) 2.dp else 1.dp,
                    brush = if (keyFocused) SolidColor(tokens.colors.borderFocus) else NuvioGlass.Edge,
                    shape = shape,
                ),
        ) {
            artwork()
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Cover art filling its box, or the note glyph on the glass when there is none. */
@Composable
private fun MusicCoverFill(url: String?, description: String?, circle: Boolean) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (circle) Modifier.clip(CircleShape).background(colors.surfaceElevated) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            NuvioAsyncImage(
                // Search hands back small thumbnails; a grid tile wants a bigger one.
                model = enlargeYtThumbnail(url, size = 400) ?: url,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(40.dp))
        }
    }
}
