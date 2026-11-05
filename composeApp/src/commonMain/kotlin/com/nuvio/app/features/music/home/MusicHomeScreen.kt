package com.nuvio.app.features.music.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.HoldToSelectState
import com.nuvio.app.core.ui.LocalNuvioThemeTokens
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.rememberHoldToSelectState
import com.nuvio.app.core.ui.VoiceSearch
import com.nuvio.app.core.ui.VoiceSearchCommands
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.features.discover.BrowseFilterIconAction
import com.nuvio.app.features.music.ArtistFanartRepository
import com.nuvio.app.features.music.LensPanel
import com.nuvio.app.features.music.LensPanelHeader
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicItemMenu
import com.nuvio.app.features.music.MusicTabState
import com.nuvio.app.features.music.MusicTopBarSearch
import com.nuvio.app.features.music.MusicTopResult
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.LadderRequest
import com.nuvio.app.features.music.library.LibraryView
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.MusicLibraryShelf
import com.nuvio.app.features.music.library.MusicSearchHistory
import com.nuvio.app.features.music.library.enlargeYtThumbnail
import com.nuvio.app.features.music.library.musicKeyFocusRing
import com.nuvio.app.features.music.identity.ArtworkPalette
import com.nuvio.app.features.music.identity.ArtworkPaletteMath
import com.nuvio.app.features.music.identity.MusicPaletteResolver
import com.nuvio.app.features.music.identity.withAccent
import com.nuvio.app.features.music.musicMenuTrigger
import com.nuvio.app.features.music.rememberMusicMenu
import com.nuvio.app.features.music.rememberMusicReducedMotion
import com.nuvio.app.features.music.youTubeLengthLabel
import com.nuvio.app.features.settings.settingsSkyBackground
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * fork:music-home
 *
 * The quick-play first screen for Music (Settings > Music > New music home). A fixed header (the Library's views, the
 * greeting, the search line and the letter strip, then recent searches or suggestions) over a body that is the Library
 * while nothing is typed and three result panels once something is: the top match and other artists, YouTube Music's
 * songs (each with its YouTube video when one matched) and albums, and YouTube's videos. Nothing in the header moves or
 * resizes with what is typed; only what sits inside each slot changes.
 *
 * Phase 3 of the plan "Music Home First Screen: Build Plan". Still to come: the fanart morph and the action row (phase 4),
 * the sentence chips drawn in the line and the remix and live panels (phase 5).
 */

/** ISO weekday (1 Monday to 7 Sunday) and hour (0 to 23) in the person's own time zone. */
internal expect fun localWeekdayAndHour(): Pair<Int, Int>

private const val SUGGEST_PAUSE_MS = 150L
private const val SEARCH_PAUSE_MS = 350L
private const val MIN_QUERY_LENGTH = 2
private const val CHIPS_SHOWN = 5

/** Panels sit side by side from this width; narrower, they stack in the same order. */
private val THREE_PANELS_MIN_WIDTH = 1100.dp

private val YouTubeMusicTint = Color(0xFF8FB4F0)
private val YouTubeTint = Color(0xFFB98CE6)

private sealed interface HomeSearchState {
    data object Idle : HomeSearchState
    data object Loading : HomeSearchState
    data object Failed : HomeSearchState
    data class Ready(val results: HomeResults) : HomeSearchState
}

/** The token that stands for the mic key in the strip's list, between the two halves of the alphabet. */
private const val MIC_TOKEN = "mic"

@Composable
internal fun MusicHomeScreen(
    onAlbumClick: (browseId: String) -> Unit,
    onArtistClick: (browseId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    onOpenLadder: (LadderRequest) -> Unit,
    modifier: Modifier = Modifier,
    topChromePadding: Dp = 0.dp,
    catalog: MusicCatalog,
) {
    val tab = MusicTabState
    val query = tab.query
    val libraryState by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val libraryNames = remember(libraryState.artists) { libraryState.artists.map { it.name } }
    val history by MusicSearchHistory.recent.collectAsState()
    var libraryView by rememberSaveable { mutableStateOf(LibraryView.Shelf) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var search by remember { mutableStateOf<HomeSearchState>(HomeSearchState.Idle) }
    var attempt by remember { mutableIntStateOf(0) }
    // The strip's last pressed letter: the likely next letters gather around it, and it keeps the focus.
    var lastPressed by remember { mutableStateOf<Char?>(null) }
    val settler = remember { MatchSettler() }
    var settledTop by remember { mutableStateOf<String?>(null) }
    val lineFocus = remember { FocusRequester() }
    val resultsEntry = remember { FocusRequester() }
    // The top match on screen: held until another has settled, so typing on does not flash between matches.
    var heldTop by remember { mutableStateOf<MusicTopResult?>(null) }
    // Down from the strip (or the line) with a top match swaps the strip for the action row in the same slot.
    var actionsOpen by remember { mutableStateOf(false) }
    var moveFocusToSlot by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val runner = remember(catalog) { HomeRunner(scope, catalog, onAlbumClick, onArtistClick) }
    val hold = rememberHoldToSelectState()
    val reducedMotion = rememberMusicReducedMotion()
    val context = LocalPlatformContext.current
    var backdrop by remember { mutableStateOf<String?>(null) }
    var palette by remember { mutableStateOf<ArtworkPalette?>(null) }

    fun setQuery(text: String) {
        tab.query = text
        if (text.isBlank()) lastPressed = null
    }

    // Completions after a short pause: they feed the strip, the greyed-out rest of the word and the chips.
    LaunchedEffect(query) {
        actionsOpen = false
        if (query.isBlank()) {
            suggestions = emptyList()
            settler.reset()
            settledTop = null
            heldTop = null
            return@LaunchedEffect
        }
        delay(SUGGEST_PAUSE_MS)
        suggestions = catalog.searchSuggestions(query)
    }

    // The search itself after the typing pause; YouTube Music and YouTube are asked together.
    LaunchedEffect(query, attempt) {
        val parsed = parseQuery(query, libraryNames)
        val text = parsed.searchText.trim()
        if (text.length < MIN_QUERY_LENGTH) {
            search = HomeSearchState.Idle
            return@LaunchedEffect
        }
        delay(SEARCH_PAUSE_MS)
        // Results stay up while the next ones load, so typing does not blank the panels on every keystroke.
        if (search !is HomeSearchState.Ready) search = HomeSearchState.Loading
        val (everything, youtube) = coroutineScope {
            val music = async { catalog.searchEverything(text) }
            val videos = async { catalog.searchYouTube(text) }
            music.await() to videos.await()
        }
        val next = if (everything.failed && youtube.isEmpty()) {
            HomeSearchState.Failed
        } else {
            HomeSearchState.Ready(buildHomeResults(parsed, everything, youtube))
        }
        search = next
        settledTop = settler.offer((next as? HomeSearchState.Ready)?.results?.topKey)
        val settledNow = settledTop
        if (settledNow == null) {
            heldTop = null
        } else if (next is HomeSearchState.Ready && next.results.topKey == settledNow) {
            heldTop = next.results.top
        }
        if (next is HomeSearchState.Ready && !next.results.isEmpty) MusicSearchHistory.record(text)
    }

    // Enter in the line (or a spoken search) sends the keyboard to the results once they are in.
    LaunchedEffect(search, tab.focusResultsWhenReady) {
        if (!tab.focusResultsWhenReady || search !is HomeSearchState.Ready) return@LaunchedEffect
        tab.focusResultsWhenReady = false
        runCatching { resultsEntry.requestFocus() }
    }

    // The app's search shortcut focuses the line (requests made before this screen existed are not replayed).
    var handledFocusRequests by remember { mutableIntStateOf(MusicTopBarSearch.focusRequests) }
    LaunchedEffect(MusicTopBarSearch.focusRequests) {
        if (MusicTopBarSearch.focusRequests == handledFocusRequests) return@LaunchedEffect
        handledFocusRequests = MusicTopBarSearch.focusRequests
        runCatching { lineFocus.requestFocus() }
    }

    VoiceSearchCommands(owner = VOICE_OWNER, text = query, onTextChange = ::setQuery)

    val candidates = remember(libraryNames, suggestions, history) {
        libraryNames.map { it to LetterSource.Library } +
            suggestions.map { it to LetterSource.Suggestion } +
            history.map { it to LetterSource.History }
    }
    val likely = remember(query, candidates) { if (query.isBlank()) emptyList() else nextLetterRanks(query, candidates) }
    val ghost = remember(query, libraryNames, suggestions) { ghostCompletion(query, libraryNames + suggestions) }
    val shownTop = heldTop?.takeIf { query.isNotBlank() }

    // The morph: once a match has settled, its picture fades in behind the screen and the accent takes its colours.
    // An artist match also has its YouTube Music page asked for now, so Play Essentials starts at once.
    val morphKey = shownTop?.let { listOfNotNull(it.albumBrowseId, it.videoId, it.artists.firstOrNull()?.browseId, it.title).first() }
    LaunchedEffect(morphKey) {
        val top = shownTop
        if (top == null) {
            backdrop = null
            palette = null
            actionsOpen = false
            return@LaunchedEffect
        }
        val artistId = top.artists.firstOrNull()?.browseId
        if (topMatchKind(top) == TopMatchKind.Artist && artistId != null) launch { catalog.artist(artistId) }
        val fanart = if (topMatchKind(top) == TopMatchKind.Artist) ArtistFanartRepository.forArtist(top.title).firstOrNull() else null
        val cover = top.thumbnailUrl?.let { enlargeYtThumbnail(it, size = 1200) ?: it }
        backdrop = fanart ?: cover
        val seedUrl = top.thumbnailUrl ?: fanart
        palette = seedUrl?.let { url ->
            withContext(Dispatchers.Default) { MusicPaletteResolver.sampleOne(context, url) }?.let { ArtworkPaletteMath.derive(it, forUrl = url) }
        }
    }
    val baseTokens = MaterialTheme.nuvio
    val darkSurface = baseTokens.colors.background.luminance() < 0.5f
    val wanted = palette?.takeIf { darkSurface && shownTop != null }
    val accent by animateColorAsState(
        wanted?.let { Color(it.accent) } ?: baseTokens.colors.accent,
        animationSpec = tween(if (reducedMotion) 0 else 600),
        label = "homeAccent",
    )
    val themed = remember(baseTokens, accent, wanted) {
        if (wanted == null && accent == baseTokens.colors.accent) baseTokens
        else baseTokens.withAccent(accent, wanted?.let { Color(it.onAccent) } ?: baseTokens.colors.onAccent)
    }

    // Focus follows the slot when it swaps (strip to action row and back), once the new one is on screen.
    LaunchedEffect(actionsOpen) {
        if (!moveFocusToSlot) return@LaunchedEffect
        withFrameNanos { }
        runCatching { tab.bodyFocus.requestFocus() }
        moveFocusToSlot = false
    }
    fun openActions(): Boolean {
        if (shownTop == null) return false
        moveFocusToSlot = true
        actionsOpen = true
        return true
    }

    CompositionLocalProvider(LocalNuvioThemeTokens provides themed) {
    Box(modifier.fillMaxSize().settingsSkyBackground(themed.colors.background, themed.colors.accent)) {
    HomeBackdrop(backdrop, reducedMotion)
    Column(
        Modifier
            .fillMaxSize()
            // Back (Escape, a pad's B) with something typed clears the line and goes back to the strip instead of leaving.
            // Both halves of the key are eaten, so the screen's own Back does not fire on the same press.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Escape || query.isEmpty()) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    setQuery("")
                    runCatching { tab.bodyFocus.requestFocus() }
                }
                true
            }
            .padding(top = topChromePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HomeViewIcons(
            selected = libraryView,
            onSelect = {
                libraryView = it
                setQuery("")
            },
        )
        Spacer(Modifier.height(12.dp))
        HomeGreeting(top = shownTop)
        Spacer(Modifier.height(16.dp))
        HomeSearchLine(
            query = query,
            ghost = ghost,
            onQueryChange = ::setQuery,
            focusRequester = lineFocus,
            onDown = { if (!openActions()) runCatching { tab.bodyFocus.requestFocus() } },
            onEnter = {
                tab.focusResultsWhenReady = true
                if (search is HomeSearchState.Ready) runCatching { resultsEntry.requestFocus() }
            },
        )
        Spacer(Modifier.height(14.dp))
        // One slot, one height: the letter strip, or the action row in its place.
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        val actionTop = shownTop
        if (actionsOpen && actionTop != null) {
            HomeActionRow(
                top = actionTop,
                primaryFocus = tab.bodyFocus,
                runner = runner,
                onUp = {
                    moveFocusToSlot = true
                    actionsOpen = false
                },
            )
        } else LetterStrip(
            arranged = remember(lastPressed, likely) { arrangeStrip(lastPressed, likely) },
            likely = remember(likely) { likely.take(STRIP_PULLED).toSet() },
            typing = query.isNotBlank(),
            micFocus = tab.bodyFocus,
            onLetter = { ch ->
                setQuery(query + ch.lowercaseChar())
                if (ch.uppercaseChar() in STRIP_LETTERS) lastPressed = ch.uppercaseChar()
            },
            onSpace = { if (query.isNotBlank() && !query.endsWith(" ")) setQuery("$query ") },
            onDelete = { setQuery(query.dropLast(1)) },
            onMic = { VoiceSearch.start(VOICE_OWNER) },
            onDown = ::openActions,
        )
        }
        Spacer(Modifier.height(12.dp))
        HomeChipRow(
            label = if (query.isBlank()) "RECENT" else "SUGGESTED",
            chips = (if (query.isBlank()) history else suggestions).take(CHIPS_SHOWN),
            onPick = {
                setQuery(it)
                tab.focusResultsWhenReady = true
            },
        )
        Spacer(Modifier.height(16.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (query.isBlank()) {
                if (libraryView == LibraryView.Playlists) {
                    com.nuvio.app.features.music.playlist.PlaylistsView(onOpenPlaylist = onPlaylistClick, modifier = Modifier.fillMaxSize())
                } else {
                    MusicLibraryShelf(
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        modifier = Modifier.fillMaxSize(),
                        onOpenLadder = onOpenLadder,
                        view = libraryView,
                    )
                }
            } else {
                HomeResultsBody(
                    search = search,
                    entry = resultsEntry,
                    onRetry = { attempt++ },
                    runner = runner,
                    hold = hold,
                )
            }
        }
    }
    }
    }
}

private const val VOICE_OWNER = "music-search"

// region header

/** The Library's views, as the old tab's icon row; choosing one clears the line and shows it below. */
@Composable
private fun HomeViewIcons(selected: LibraryView, onSelect: (LibraryView) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        HOME_VIEW_ICONS.forEach { (view, icon, label) ->
            BrowseFilterIconAction(icon, label, on = selected == view) { onSelect(view) }
        }
    }
}

private val HOME_VIEW_ICONS: List<Triple<LibraryView, ImageVector, String>> = listOf(
    Triple(LibraryView.Shelf, Icons.Rounded.ViewModule, "Shelf"),
    Triple(LibraryView.Constellation, Icons.Rounded.Hub, "Constellation"),
    Triple(LibraryView.Eras, Icons.Rounded.Timeline, "Eras"),
    Triple(LibraryView.Playlists, Icons.Rounded.QueueMusic, "Playlists"),
)

/**
 * The greeting slot: the weekday and part of the day over "What do you want to hear?", and once a top match has
 * settled, its kind over its name. A fixed height, so the line below never moves.
 */
@Composable
private fun HomeGreeting(top: MusicTopResult?) {
    val colors = MaterialTheme.nuvio.colors
    val label = remember(top) {
        top?.let { listOfNotNull("TOP MATCH", it.kind?.uppercase()).joinToString(" · ") }
            ?: localWeekdayAndHour().let { (day, hour) -> greetingLabel(day, hour) }
    }
    Column(Modifier.height(72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.accent, maxLines = 1)
        Text(
            top?.title ?: "What do you want to hear?",
            fontFamily = FontFamily.Serif,
            fontSize = 40.sp,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/**
 * The search line: what is typed, the rest of the best completion in grey after it (Right at the end takes it), and
 * the mic. Down goes to the letter strip, Enter to the results, Escape clears the line.
 */
@Composable
private fun HomeSearchLine(
    query: String,
    ghost: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onDown: () -> Unit,
    onEnter: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val colors = tokens.colors
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    // Text set from elsewhere (the strip, a chip, voice) puts the cursor at the end.
    LaunchedEffect(query) {
        if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
    }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    val textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.textPrimary)
    Row(
        Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(56.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 0.09f else 0.06f), shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) SolidColor(colors.borderFocus) else SolidColor(Color.White.copy(alpha = 0.14f)), shape)
            .padding(start = 22.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(24.dp))
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != query) onQueryChange(it.text)
            },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .trackTextInputFocus()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        // Both halves of Escape are eaten while there is text, so the screen's Back does not fire too.
                        return@onPreviewKeyEvent event.key == Key.Escape && query.isNotEmpty()
                    }
                    when (event.key) {
                        Key.DirectionRight -> if (ghost.isNotEmpty() && field.selection.end == field.text.length) {
                            onQueryChange(query + ghost)
                            true
                        } else false
                        Key.DirectionDown -> {
                            onDown()
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> if (query.isNotBlank()) {
                            onEnter()
                            true
                        } else false
                        Key.Escape -> if (query.isNotEmpty()) {
                            onQueryChange("")
                            true
                        } else false
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Artist, album, song, remix…", style = textStyle, color = colors.textMuted, maxLines = 1)
                    } else if (ghost.isNotEmpty()) {
                        // The typed part drawn invisibly, so the grey rest starts exactly where the text ends.
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color.Transparent)) { append(query) }
                                withStyle(SpanStyle(color = colors.textMuted)) { append(ghost) }
                            },
                            style = textStyle,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        Text("YouTube Music + YouTube", style = MaterialTheme.typography.labelMedium, color = colors.textMuted, maxLines = 1)
        val listening = VoiceSearch.owner.collectAsState().value == VOICE_OWNER
        HomeRoundButton(
            icon = Icons.Rounded.Mic,
            description = "Search by voice",
            active = listening,
            onClick = {
                runCatching { focusRequester.requestFocus() }
                VoiceSearch.start(VOICE_OWNER)
            },
        )
    }
}

@Composable
private fun HomeRoundButton(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        Modifier
            .size(42.dp)
            .musicKeyFocusRing(CircleShape)
            .clip(CircleShape)
            .background(if (active) colors.accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (active) colors.accent else colors.textPrimary, modifier = Modifier.size(22.dp))
    }
}

/**
 * The on-screen letters: space, A to Z with the mic between M and N, delete. While something is typed the likely next
 * letters are lit and the best three sit beside the last one pressed; the rest dim but still work. A keyboard used
 * while the strip has focus types into the line. The keys keep their identity as they move, so the one pressed keeps
 * the focus.
 */
@Composable
private fun LetterStrip(
    arranged: List<Char>,
    likely: Set<Char>,
    typing: Boolean,
    micFocus: FocusRequester,
    onLetter: (Char) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    onMic: () -> Unit,
    /** Down from the strip: true when it was taken (a top match opened the action row). */
    onDown: () -> Boolean,
) {
    val tokensList = remember(arranged) {
        arranged.take(arranged.size / 2).map { it.toString() } + MIC_TOKEN + arranged.drop(arranged.size / 2).map { it.toString() }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        // Keys shrink to fit a narrow window instead of running off it.
        val keyWidth = min(36.dp, (maxWidth - 140.dp - 4.dp * 29) / 27)
        Row(
            Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> onDown()
                    Key.Backspace -> if (typing) {
                        onDelete()
                        true
                    } else false
                    Key.Spacebar -> {
                        onSpace()
                        true
                    }
                    else -> {
                        val ch = event.utf16CodePoint.takeIf { it in 33..0xFFFF }?.toChar()
                        if (ch != null && ch.isLetterOrDigit()) {
                            onLetter(ch)
                            true
                        } else false
                    }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StripKey(width = 60.dp, lit = false, dim = false, onPress = onSpace) {
                Text("space", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.nuvio.colors.textSecondary)
            }
            tokensList.forEach { token ->
                key(token) {
                    if (token == MIC_TOKEN) {
                        StripKey(width = 48.dp, lit = true, dim = false, onPress = onMic, modifier = Modifier.focusRequester(micFocus), round = true) {
                            Icon(Icons.Rounded.Mic, contentDescription = "Search by voice", tint = MaterialTheme.nuvio.colors.textPrimary, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        val ch = token.single()
                        val lit = typing && ch in likely
                        StripKey(width = keyWidth, lit = lit, dim = typing && !lit, onPress = { onLetter(ch) }) {
                            Text(token, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            StripKey(width = 60.dp, lit = false, dim = false, onPress = onDelete) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Delete", tint = MaterialTheme.nuvio.colors.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StripKey(
    width: Dp,
    lit: Boolean,
    dim: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = if (round) RoundedCornerShape(21.dp) else RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = when {
        lit -> colors.accent.copy(alpha = 0.22f)
        hovered -> Color.White.copy(alpha = 0.10f)
        dim -> Color.Transparent
        else -> Color.White.copy(alpha = 0.04f)
    }
    val edge = when {
        lit -> colors.accent.copy(alpha = 0.55f)
        dim -> Color.White.copy(alpha = 0.05f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides if (dim) colors.textPrimary.copy(alpha = 0.3f) else colors.textPrimary,
    ) {
        Box(
            modifier
                .width(width)
                .height(42.dp)
                .musicKeyFocusRing(shape)
                .clip(shape)
                .background(fill, shape)
                .border(1.dp, edge, shape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onPress),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** Recent searches before anything is typed, YouTube Music's suggestions after: one pick runs it. Fixed height. */
@Composable
private fun HomeChipRow(label: String, chips: List<String>, onPick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Row(Modifier.height(36.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (chips.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, color = colors.textMuted)
        }
        chips.forEach { chip ->
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier
                    .height(34.dp)
                    .musicKeyFocusRing(shape)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.05f), shape)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                    .clickable { onPick(chip) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(chip, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// endregion

// region results

@Composable
private fun HomeResultsBody(
    search: HomeSearchState,
    entry: FocusRequester,
    onRetry: () -> Unit,
    runner: HomeRunner,
    hold: HoldToSelectState,
) {
    val results = (search as? HomeSearchState.Ready)?.results
    // The keyboard's way in: the first thing in the YouTube Music panel, else the top match, else the first video.
    val entryPanel = when {
        results == null -> -1
        results.rows.isNotEmpty() || results.albums.isNotEmpty() -> 1
        results.top != null || results.artists.isNotEmpty() -> 0
        else -> 2
    }
    val left: @Composable ColumnScope.() -> Unit = {
        LensPanelHeader(Icons.Rounded.Star, "TOP MATCH", null, MaterialTheme.nuvio.colors.accent)
        PanelBody(search, onRetry, results?.let { it.top == null && it.artists.isEmpty() } ?: true) {
            val r = results ?: return@PanelBody
            r.top?.let { top ->
                item(key = "top") {
                    TopMatchRow(top, if (entryPanel == 0) Modifier.focusRequester(entry) else Modifier, runner, hold)
                }
            }
            if (r.artists.isNotEmpty()) item(key = "artists-label") { SectionLabel("ARTISTS") }
            items(r.artists, key = { "artist:" + it.browseId }) { artist ->
                ArtistRow(artist, Modifier.let { m -> if (entryPanel == 0 && r.top == null && artist == r.artists.first()) m.focusRequester(entry) else m }, runner, hold)
            }
        }
    }
    val centre: @Composable ColumnScope.() -> Unit = {
        LensPanelHeader(Icons.Rounded.MusicNote, "YOUTUBE MUSIC · SONGS AND ALBUMS", null, YouTubeMusicTint)
        PanelBody(search, onRetry, results?.let { it.rows.isEmpty() && it.albums.isEmpty() } ?: true) {
            val r = results ?: return@PanelBody
            r.rows.forEachIndexed { index, row ->
                val song = row.song ?: return@forEachIndexed
                item(key = "song:" + song.videoId) {
                    SongRow(row, if (entryPanel == 1 && index == 0) Modifier.focusRequester(entry) else Modifier, runner, hold)
                }
            }
            if (r.albums.isNotEmpty()) item(key = "albums-label") { SectionLabel("ALBUMS") }
            r.albums.forEachIndexed { index, album ->
                item(key = "album:" + album.browseId) {
                    AlbumRow(album, if (entryPanel == 1 && r.rows.isEmpty() && index == 0) Modifier.focusRequester(entry) else Modifier, runner, hold)
                }
            }
        }
    }
    val right: @Composable ColumnScope.() -> Unit = {
        LensPanelHeader(Icons.Rounded.SmartDisplay, "YOUTUBE · VIDEOS", null, YouTubeTint)
        PanelBody(search, onRetry, results?.videos?.isEmpty() ?: true) {
            val r = results ?: return@PanelBody
            r.videos.forEachIndexed { index, video ->
                item(key = "video:" + video.videoId) {
                    VideoRow(video, if (entryPanel == 2 && index == 0) Modifier.focusRequester(entry) else Modifier, runner, hold)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, bottom = 16.dp)) {
        if (maxWidth >= THREE_PANELS_MIN_WIDTH) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LensPanel(Modifier.weight(1f), content = left)
                LensPanel(Modifier.weight(1.32f), content = centre)
                LensPanel(Modifier.weight(1f), content = right)
            }
        } else {
            // One column, the same order: each panel a window-high slice, the column scrolling.
            val sliceHeight = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LensPanel(Modifier.fillMaxWidth().height(sliceHeight), content = left)
                LensPanel(Modifier.fillMaxWidth().height(sliceHeight), content = centre)
                LensPanel(Modifier.fillMaxWidth().height(sliceHeight), content = right)
            }
        }
    }
}

/** A panel's list under its header, or what to say instead: loading, could not search, nothing found. */
@Composable
private fun ColumnScope.PanelBody(
    search: HomeSearchState,
    onRetry: () -> Unit,
    empty: Boolean,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Spacer(Modifier.height(14.dp))
    when {
        search is HomeSearchState.Loading || search is HomeSearchState.Idle -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp))
        }
        search is HomeSearchState.Failed -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't reach YouTube. Check your connection.", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.musicKeyFocusRing(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onRetry).padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Try again", style = MaterialTheme.typography.labelLarge, color = colors.textPrimary) }
        }
        empty -> Text("Nothing here for this search.", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, modifier = Modifier.padding(top = 16.dp))
        else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/** The top match, larger: an artist opens their page, an album its page, a song or video plays. Hold OK for its menu. */
@Composable
private fun TopMatchRow(top: MusicTopResult, modifier: Modifier, runner: HomeRunner, hold: HoldToSelectState) {
    val kind = topMatchKind(top)
    val isArtist = kind == TopMatchKind.Artist
    ResultRow(
        art = top.thumbnailUrl,
        circle = isArtist,
        artSize = 72.dp,
        title = top.title,
        subtitle = listOfNotNull(top.kind, top.artists.takeIf { !isArtist }?.joinToString(", ") { it.name }).joinToString(" · ").ifBlank { null },
        trailing = null,
        videoMark = false,
        modifier = modifier,
        menu = { runner.moreFor(top) },
        hold = hold,
        onClick = {
            when (kind) {
                TopMatchKind.Album -> runner.run(HomeAction.OpenAlbum, top)
                TopMatchKind.Track -> runner.run(HomeAction.PlayTrack, top)
                TopMatchKind.Artist -> runner.run(HomeAction.OpenArtist, top)
            }
        },
    )
}

@Composable
private fun ArtistRow(artist: MusicArtistSummary, modifier: Modifier, runner: HomeRunner, hold: HoldToSelectState) {
    ResultRow(
        art = artist.thumbnailUrl, circle = true, artSize = 48.dp, title = artist.name, subtitle = null, trailing = null, videoMark = false,
        modifier = modifier, menu = { runner.artistMenu(artist) }, hold = hold, onClick = { runner.openArtist(artist.browseId) },
    )
}

/** A YouTube Music song; plays on its own. The video mark says the same song is on YouTube as a video too. */
@Composable
private fun SongRow(row: MergedRow, modifier: Modifier, runner: HomeRunner, hold: HoldToSelectState) {
    val song = row.song ?: return
    ResultRow(
        art = song.coverUrl,
        circle = false,
        artSize = 48.dp,
        title = song.title,
        subtitle = listOfNotNull(
            "Song",
            song.artists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
            song.albumTitle,
        ).joinToString(" · "),
        trailing = youTubeLengthLabel(song.durationSeconds),
        videoMark = row.hasVideo,
        modifier = modifier,
        menu = { runner.songMenu(row) },
        hold = hold,
        onClick = { runner.track(song, PlayHow.Now) },
    )
}

@Composable
private fun AlbumRow(album: MusicAlbumSummary, modifier: Modifier, runner: HomeRunner, hold: HoldToSelectState) {
    ResultRow(
        art = album.thumbnailUrl,
        circle = false,
        artSize = 48.dp,
        title = album.title,
        subtitle = listOfNotNull(album.kind ?: "Album", album.artists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }, album.year).joinToString(" · "),
        trailing = null,
        videoMark = false,
        modifier = modifier,
        menu = { runner.albumMenu(album) },
        hold = hold,
        onClick = { runner.openAlbum(album.browseId) },
    )
}

@Composable
private fun VideoRow(video: MusicTrack, modifier: Modifier, runner: HomeRunner, hold: HoldToSelectState) {
    ResultRow(
        art = video.coverUrl,
        circle = false,
        artSize = 48.dp,
        wideArt = true,
        title = video.title,
        subtitle = video.artists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
        trailing = youTubeLengthLabel(video.durationSeconds),
        videoMark = false,
        modifier = modifier,
        menu = { runner.videoMenu(video) },
        hold = hold,
        onClick = { runner.track(video, PlayHow.Now) },
    )
}

@Composable
private fun ResultRow(
    art: String?,
    circle: Boolean,
    artSize: Dp,
    title: String,
    subtitle: String?,
    trailing: String?,
    videoMark: Boolean,
    modifier: Modifier,
    wideArt: Boolean = false,
    /** The row's More menu (right-click, the Menu key or a held OK); a tap of OK is [onClick]. */
    menu: (() -> List<PosterZoomOverlayAction>)? = null,
    hold: HoldToSelectState? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menuState = rememberMusicMenu()
    if (menu != null) MusicItemMenu(menuState, menu)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (menu != null && hold != null) Modifier.musicMenuTrigger(menuState, hold, onSelect = onClick) else Modifier)
            .musicKeyFocusRing(shape)
            .clip(shape)
            .background(if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val artShape = if (circle) CircleShape else RoundedCornerShape(8.dp)
        Box(
            Modifier
                .height(artSize)
                .then(if (wideArt) Modifier.aspectRatio(16f / 9f) else Modifier.width(artSize))
                .clip(artShape)
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                NuvioAsyncImage(
                    model = enlargeYtThumbnail(art, size = 240) ?: art,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (videoMark) {
            Icon(Icons.Rounded.SmartDisplay, contentDescription = "Also on YouTube as a video", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
        }
    }
}

// endregion
