package com.nuvio.app.features.music

import com.nuvio.app.core.ui.requestFocusLogged
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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.app.features.music.library.LibraryView
import com.nuvio.app.features.music.library.shownLibraryView
import com.nuvio.app.features.music.library.visibleLibraryViews
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
import com.nuvio.app.features.music.library.LADDER_VISIBLE // fork:music
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

/**
 * fork:navbar-search - the Music tab's search. The tab is the Library; the app bar's Search, on this tab, types into
 * [query], and while it holds something the tab shows the search's rows in the Library's place.
 */
internal object MusicTabState {
    private var typed by mutableStateOf("")

    /** What is in the app bar's box. Clearing it clears the search too. */
    var query: String
        get() = typed
        set(value) {
            typed = value
            if (value.isBlank()) submitted = ""
        }

    /**
     * fork:navbar-search - the search being shown: set by Enter (or a voice search, or a genre chip), like the films and
     * shows search, which also waits for Enter. Typing alone only changes [query] and its predictions.
     */
    var submitted by mutableStateOf("")

    /** fork:music-home - true while the music home is the Music tab's first screen: it has its own search line. */
    fun homeOwnsSearch(): Boolean = MusicLibraryRepository.state.value.settings.musicHome

    /** Puts [text] in the box and searches it now. */
    fun search(text: String) {
        typed = text
        submitted = text.trim()
    }

    /**
     * The tab draws no search box: the app bar's Search searches the open tab (Music here), so the page always starts
     * right under the bar. Kept as a flag for the screens that trim their top by it.
     */
    val barHosted: Boolean = true

    /** The page under the box (the Library's view, the start screen or the results): Escape and Enter go there. */
    val bodyFocus = FocusRequester()

    /** The row of icons under the box (the Library's views, or Search's kinds): Down from the box and its buttons. */
    val pillsFocus = FocusRequester()

    /** Down from the bar: onto that row. */
    fun focusPills(): Boolean = pillsFocus.requestFocusLogged()

    /** Crates' first stop on its left panel (the open shelf, or All shelves): Down from the view pills. */
    val cratesEntry = FocusRequester()

    /** Enter was pressed before the results came in: they take focus when they arrive. */
    var focusResultsWhenReady by mutableStateOf(false)
}

/** fork:music-navbar - the recent searches the bar-hosted box offers, highlighted row [picked] (-1 for none). */
class MusicBarSuggestions(
    val items: List<String>,
    val picked: Int,
    val onPick: (String) -> Unit,
    /** The panel's "Clear history"; the YouTube box hands its own. */
    val onClear: () -> Unit = { com.nuvio.app.features.music.library.MusicSearchHistory.clear() },
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

    /**
     * fork:voice-control - a search asked for by voice: into the box, on the Search section, with the
     * keyboard sent to the results when they arrive (as Enter does). The tab searches when the text changes.
     */
    fun searchFor(query: String) {
        if (query.isBlank()) return
        MusicTabState.search(query)
        MusicTabState.focusResultsWhenReady = true
    }
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
    // fork:navbar-search - the tab is the Library; a search typed into the app bar's Search (on this tab it searches
    // music) shows its rows in the Library's place until it is cleared.
    // Results take the Library's place once a search is sent (Enter), not while it is still being typed.
    val searching = MusicTabState.submitted.isNotBlank()
    var ladder by remember { mutableStateOf<LadderRequest?>(null) } // fork:music - the open ladder, if any
    var libraryView by rememberSaveable { mutableStateOf(LibraryView.Shelf) }
    // A ladder asked for from the Stage (a genre chip): open it over a cleared search.
    val pendingLadder by MusicPlayback.pendingLadder.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLadder) {
        val wanted = pendingLadder ?: return@LaunchedEffect
        MusicPlayback.pendingLadder.value = null
        if (!LADDER_VISIBLE) return@LaunchedEffect // hidden: nothing sets it any more, but never open one
        MusicTabState.query = ""
        ladder = wanted
    }
    // A search typed into the top bar while a ladder is open is meant to be seen.
    LaunchedEffect(query) {
        if (query.isNotBlank()) ladder = null else MusicTabState.focusResultsWhenReady = false
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

    // fork:music-home - the quick-play first screen, when chosen in Music settings (upstream removed it on Nov 11 2026;
    // this fork keeps it). It has its own search line and runs its own searches, so with it on the app bar's Search
    // goes to that line (App.kt) and upstream's rows search below is not used. Ladders still open over it (above).
    if (MusicLibraryRepository.state.collectAsState().value.settings.musicHome) {
        com.nuvio.app.features.music.home.MusicHomeScreen(
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onPlaylistClick = onPlaylistClick,
            onOpenLadder = { ladder = it },
            modifier = modifier,
            topChromePadding = topChromePadding,
            catalog = catalog,
        )
        return
    }

    // Escape anywhere in the results leaves the search: the box clears and the Library comes back, with the keyboard on
    // it. Only the box itself did this before, so once the keyboard was in the results Escape did nothing and the
    // search had to be cleared by hand (2026-11-13).
    val leaveSearchOnEscape = Modifier.onPreviewKeyEvent { event ->
        if (event.key != Key.Escape || !searching) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp) {
            MusicTabState.query = ""
            com.nuvio.app.core.ui.KeyLayers.settle("left the music search")
        }
        true
    }

    // TV Mode and Adaptive (Settings > Home): with the box on the floating top bar, the search's hero takes the whole
    // screen and runs under the bar, as Home's does.
    val searchMode = com.nuvio.app.features.music.home.rememberMusicSearchMode()
    if (searching && searchMode != com.nuvio.app.features.music.home.MusicSearchMode.Basic) {
        com.nuvio.app.features.music.home.MusicRowsSearch(
            catalog = catalog,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            ownField = false,
            modifier = modifier.fillMaxSize().then(leaveSearchOnEscape),
        )
        return
    }

    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(leaveSearchOnEscape)
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
            if (!searching) {
                val libraryState by MusicLibraryRepository.state.collectAsState()
                // The views as icons, the way Discover's row header shows its filters; the one in use is lit.
                Row(
                    modifier = Modifier
                        .focusRequester(MusicTabState.pillsFocus)
                        // Down into Crates lands on its shelf list on the left, not on whatever cover is under the pill.
                        .onPreviewKeyEvent { event ->
                            event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown &&
                                libraryState.settings.shownLibraryView(libraryView) == LibraryView.Crates &&
                                MusicTabState.cratesEntry.requestFocusLogged()
                        },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val views = libraryState.settings.visibleLibraryViews()
                    val lit = libraryState.settings.shownLibraryView(libraryView)
                    views.forEachIndexed { index, view ->
                        Box(Modifier.rowEndStops(first = index == 0, last = index == views.lastIndex)) {
                            LibraryViewSwitch(view.icon, view.label, on = lit == view) { libraryView = view }
                        }
                    }
                }
            }
        }

        if (!searching) {
            // A view switched off in Settings while it was open falls back to the first one still on.
            val shownView = MusicLibraryRepository.state.collectAsState().value.settings.shownLibraryView(libraryView)
            if (shownView == LibraryView.Playlists) {
                com.nuvio.app.features.music.playlist.PlaylistsView(onOpenPlaylist = onPlaylistClick, modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(MusicTabState.bodyFocus))
                return@Column
            }
            MusicLibraryShelf(
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(MusicTabState.bodyFocus),
                onOpenLadder = { ladder = it }, // fork:music
                view = shownView,
            )
            return@Column
        }

        // The search's answer as rows, like the media search (typed into the app bar's Search).
        com.nuvio.app.features.music.home.MusicRowsSearch(
            catalog = catalog,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            ownField = false,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** How many recent searches the search box offers. */
private const val MUSIC_RECENT_SHOWN = 6

/** The tabs' top padding leaves room under the bar for a header; with the box on the bar most of it is spare. */
private val BAR_HOSTED_TRIM = 36.dp

/** The search header is a centred column this wide at most; the grid below gets the room. */
private val MUSIC_HEADER_MAX_WIDTH = 720.dp

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
        focusRequester.requestFocusLogged()
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
                            if (trailingFocus != null) trailingFocus.requestFocusLogged()
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
                    if (!MusicTabState.bodyFocus.requestFocusLogged()) focusManager.clearFocus()
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
            if (MusicTabState.bodyFocus.requestFocusLogged()) {
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

/**
 * One of the Library's views as a named pill (icon and name), the one in use lit in the accent: the tab's main switch, so
 * it carries more weight than the bare icons the filters use.
 */
@Composable
private fun LibraryViewSwitch(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    Row(
        Modifier
            .height(44.dp)
            .clip(shape)
            .background(if (on) colors.accent.copy(alpha = 0.20f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (on) colors.accent.copy(alpha = 0.55f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f), shape)
            .musicKeyFocusRing(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (on) colors.accent else colors.textSecondary, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
            color = if (on) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
    }
}
