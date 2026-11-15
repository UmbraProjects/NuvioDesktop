package com.nuvio.app.features.music.home

import com.nuvio.app.core.ui.KeyboardInputMode
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.nuvio.app.features.music.MusicSearchEverything
import com.nuvio.app.features.music.MusicTabState
import com.nuvio.app.features.music.MusicTopBarSearch
import com.nuvio.app.features.music.MusicTopResult
import com.nuvio.app.features.music.MusicTopSong
import com.nuvio.app.features.music.MusicPlaylistSummary
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.GenreArtist
import com.nuvio.app.features.music.library.GenreQuery
import com.nuvio.app.features.music.library.LadderArtwork
import com.nuvio.app.features.music.library.LadderRequest
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.MusicBrainzClient
import com.nuvio.app.features.music.library.MusicTagSource
import com.nuvio.app.features.music.library.TagKind
import com.nuvio.app.features.music.library.canonicalGenre
import com.nuvio.app.features.music.library.detectGenreQuery
import com.nuvio.app.features.music.library.libraryArtistsFor
import com.nuvio.app.features.music.library.looksLikeRealTag
import com.nuvio.app.features.music.library.pickGenreStation
import com.nuvio.app.features.music.library.rankTagArtists
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

/** What a row brings up behind the screen while the pointer or the remote is on it: its artist's fanart, else [fallback]. */
private data class RowPreview(val artist: String?, val fallback: String?)

/** Where a row reports that the pointer or the remote is on it (set by the screen, read by every result row). */
private val LocalRowPreview = staticCompositionLocalOf<(RowPreview) -> Unit> { {} }

/** How long the pointer or the remote rests on a row before its picture comes up, so moving down a list does not flash. */
private const val ROW_PREVIEW_PAUSE_MS = 300L

/** How one panel stands: waiting for its source's first answer, could not reach it, or showing what it has. */
private enum class PanelState { Loading, Failed, Ready }

/** How long an emptied line keeps the results up before the screen glides back to the centred search. */
private const val COLLAPSE_AFTER_MS = 5_000L

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
    // The tab in the icon row: null is Search, otherwise one of the Library's views.
    var libraryTab by rememberSaveable { mutableStateOf<LibraryView?>(null) }
    var fetchedSuggestions by remember { mutableStateOf(emptyList<String>()) }
    // Answers for an earlier, shorter line stay hidden until the pause passes: only ones that still begin with the line count.
    val suggestions = remember(fetchedSuggestions, query) {
        val line = foldName(query.trim())
        fetchedSuggestions.filter { foldName(it).startsWith(line) }
    }
    // Each source's latest answer, kept separately so each panel fills as soon as its own source answers. They stay up
    // while the next search runs and are cleared when the screen glides back to the centred search.
    var musicAnswer by remember { mutableStateOf<MusicSearchEverything?>(null) }
    var youtubeAnswer by remember { mutableStateOf<List<MusicTrack>?>(null) }
    var answeredQuery by remember { mutableStateOf<ParsedQuery?>(null) }
    // True from the first letter until the line has been empty for COLLAPSE_AFTER_MS: the header sits at the top and
    // the panels are up. False is the centred search.
    var expanded by remember { mutableStateOf(tab.query.isNotBlank()) }
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
    // A recent search is only kept once it led somewhere: something played or opened from it.
    runner.onCommit = {
        recentSearchLabel(tab.query, heldTop?.title).takeIf { it.length >= MIN_QUERY_LENGTH }?.let { MusicSearchHistory.record(it) }
    }
    val hold = rememberHoldToSelectState()
    val reducedMotion = rememberMusicReducedMotion()
    val context = LocalPlatformContext.current
    var backdrop by remember { mutableStateOf<String?>(null) }
    // The row the pointer or the remote is on, and the picture it brought up (shown over the top match's).
    var rowPreview by remember { mutableStateOf<RowPreview?>(null) }
    var rowBackdrop by remember { mutableStateOf<String?>(null) }
    var palette by remember { mutableStateOf<ArtworkPalette?>(null) }

    fun setQuery(text: String) {
        tab.query = text
        if (text.isBlank()) lastPressed = null
    }

    // Completions after a short pause: they feed the strip, the greyed-out rest of the word and the chips.
    LaunchedEffect(query) {
        actionsOpen = false
        rowPreview = null
        if (query.isBlank()) {
            fetchedSuggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SUGGEST_PAUSE_MS)
        fetchedSuggestions = catalog.searchSuggestions(query)
    }

    // The first letter lifts the header; an emptied line lets it glide back after COLLAPSE_AFTER_MS.
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            expanded = true
        } else if (expanded) {
            delay(COLLAPSE_AFTER_MS)
            expanded = false
        }
    }
    // Back at the centred search: the last answers and the match are forgotten.
    LaunchedEffect(expanded) {
        if (expanded) return@LaunchedEffect
        musicAnswer = null
        youtubeAnswer = null
        answeredQuery = null
        settler.reset()
        settledTop = null
        heldTop = null
    }

    // The search itself after the typing pause. YouTube Music and YouTube are asked together and each fills its own
    // panels the moment it answers.
    LaunchedEffect(query, attempt) {
        val parsedNow = parseQuery(query, libraryNames)
        val text = parsedNow.searchText.trim()
        if (text.length < MIN_QUERY_LENGTH) return@LaunchedEffect
        delay(SEARCH_PAUSE_MS)
        coroutineScope {
            launch {
                val music = catalog.searchEverything(text)
                answeredQuery = parsedNow
                musicAnswer = music
                if (!music.failed) {
                    settledTop = settler.offer(music.top?.let(::topKeyOf))
                    val settledNow = settledTop
                    if (settledNow == null) {
                        heldTop = null
                    } else if (music.top?.let(::topKeyOf) == settledNow) {
                        heldTop = music.top
                    }
                }
            }
            launch {
                val videos = catalog.searchYouTube(text)
                answeredQuery = parsedNow
                youtubeAnswer = videos
            }
        }
    }
    val answered = remember(answeredQuery, musicAnswer, youtubeAnswer) {
        answeredQuery?.let { buildHomeResults(it, musicAnswer ?: MusicSearchEverything.EMPTY, youtubeAnswer.orEmpty()) }
    }
    // fork:music-genre - a line that is a genre, mood, decade or activity gets a genre card in Top Match (below an artist
    // match). The lists answer at once; a tag they do not know is checked against the offline MusicBrainz copy.
    var offlineTag by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        offlineTag = null
        val line = query.trim()
        if (line.length < MIN_QUERY_LENGTH || line.split(' ').size > 3 || detectGenreQuery(line) != null) return@LaunchedEffect
        if (!MusicBrainzClient.hasOfflineCopy) return@LaunchedEffect
        val tag = canonicalGenre(line) ?: return@LaunchedEffect
        delay(SEARCH_PAUSE_MS)
        val found = MusicBrainzClient.searchByTagOffline(tag) ?: return@LaunchedEffect
        if (looksLikeRealTag(tag, found)) offlineTag = tag
    }
    val genre = remember(query, offlineTag) { detectGenreQuery(query) { it == offlineTag } }
    var genreArtists by remember { mutableStateOf(emptyList<GenreArtist>()) }
    LaunchedEffect(genre?.tag) {
        genreArtists = emptyList()
        val tag = genre?.tag?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        delay(SEARCH_PAUSE_MS)
        val owned = libraryNames.mapTo(HashSet()) { it.lowercase().trim() }
        genreArtists = MusicTagSource.searchByTag(tag)?.let { rankTagArtists(tag, it, owned) }.orEmpty()
    }
    // fork:music-home - YouTube Music's newer search rows carry a play or view count where the length was, so those rows
    // read "–:––". Their lengths are looked up once the results have sat still a moment (a few at a time, cached, so
    // typing does not fire a burst) and filled in as each one comes back. Kept for the session: a length never changes.
    val lengths = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(answered) {
        val wanted = answered?.missingLengthIds()?.filter { it !in lengths } ?: return@LaunchedEffect
        if (wanted.isEmpty()) return@LaunchedEffect
        delay(LENGTH_LOOKUP_PAUSE_MS)
        val gate = Semaphore(LENGTH_LOOKUPS_AT_ONCE)
        coroutineScope {
            wanted.forEach { id ->
                launch { gate.withPermit { catalog.lengthOf(id)?.let { lengths[id] = it } } }
            }
        }
    }
    val results = answered?.withLengths(lengths)
    val musicState = when {
        musicAnswer == null -> PanelState.Loading
        musicAnswer?.failed == true -> PanelState.Failed
        else -> PanelState.Ready
    }
    val youtubeState = if (youtubeAnswer == null) PanelState.Loading else PanelState.Ready

    // Enter in the line (or a spoken search) sends the keyboard to the results once they are in.
    LaunchedEffect(answered, tab.focusResultsWhenReady) {
        if (!tab.focusResultsWhenReady || results == null) return@LaunchedEffect
        tab.focusResultsWhenReady = false
        withFrameNanos { }
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
    // The match stays (picture and colours included) through the pause after the line is emptied.
    val shownTop = heldTop?.takeIf { expanded }
    // The top match's artist's most played songs: they fill the Top Match panel under the artists.
    val topArtistId = shownTop?.artists?.firstOrNull()?.browseId
    val matchSongs by produceState<List<MusicTopSong>>(emptyList(), topArtistId) {
        value = emptyList()
        value = topArtistId?.let { runCatching { catalog.artist(it)?.topSongs.orEmpty() }.getOrNull() }.orEmpty()
    }
    val greetingTop = shownTop?.takeIf { query.isNotBlank() }
    // The line read as chips: a settled artist match counts as a known name even when it is not in the library.
    val chipNames = remember(libraryNames, shownTop) {
        libraryNames + listOfNotNull(shownTop?.takeIf { topMatchKind(it) == TopMatchKind.Artist }?.title)
    }
    val parsed = remember(query, chipNames) { parseQuery(query, chipNames) }

    // A row the pointer or the remote rests on: its artist's fanart (else its own picture) fades in behind the screen.
    // The colours stay with the top match. Gone when the line changes or the screen glides back.
    LaunchedEffect(rowPreview, expanded) {
        val wanted = rowPreview?.takeIf { expanded }
        if (wanted == null) {
            rowBackdrop = null
            return@LaunchedEffect
        }
        delay(ROW_PREVIEW_PAUSE_MS)
        val fanart = wanted.artist?.takeIf { it.isNotBlank() }?.let { ArtistFanartRepository.forArtist(it).firstOrNull() }
        rowBackdrop = fanart ?: wanted.fallback?.let { enlargeYtThumbnail(it, size = 1200) ?: it }
    }

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
        // The match's own picture first; a picture with no usable colour (a black-and-white photo) lets the fanart and
        // the cover try, so the accent still follows the match.
        palette = null
        for (url in listOfNotNull(top.thumbnailUrl, fanart, cover).distinct()) {
            val sampled = withContext(Dispatchers.Default) { MusicPaletteResolver.sampleOne(context, url) } ?: continue
            palette = ArtworkPaletteMath.derive(sampled, forUrl = url)
            break
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
    HomeBackdrop(rowBackdrop ?: backdrop, reducedMotion)
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
        HomeTabs(
            libraryTab = libraryTab,
            onSelect = {
                libraryTab = it
                if (it != null) setQuery("")
            },
        )
        val selectedLibrary = libraryTab
        if (selectedLibrary != null) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (selectedLibrary == LibraryView.Playlists) {
                    com.nuvio.app.features.music.playlist.PlaylistsView(onOpenPlaylist = onPlaylistClick, modifier = Modifier.fillMaxSize().focusRequester(tab.bodyFocus))
                } else {
                    MusicLibraryShelf(
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        modifier = Modifier.fillMaxSize().focusRequester(tab.bodyFocus),
                        onOpenLadder = onOpenLadder,
                        view = selectedLibrary,
                    )
                }
            }
        } else {
            // The search: centred on the screen until the first letter, then gliding up to the top while the panels
            // rise into the room below it; back to the centre COLLAPSE_AFTER_MS after the line is emptied.
            val density = LocalDensity.current
            var headerHeight by remember { mutableStateOf(0.dp) }
            val motion = if (reducedMotion) 0 else 550
            val headerBias by animateFloatAsState(
                if (expanded) -1f else 0f,
                animationSpec = tween(motion, easing = FastOutSlowInEasing),
                label = "homeHeaderBias",
            )
            val panelsShown by animateFloatAsState(
                if (expanded) 1f else 0f,
                animationSpec = tween(if (reducedMotion) 0 else 450, delayMillis = if (expanded && !reducedMotion) 150 else 0),
                label = "homePanels",
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (panelsShown > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(top = headerHeight + 28.dp)
                            .graphicsLayer {
                                alpha = panelsShown
                                translationY = (1f - panelsShown) * 48.dp.toPx()
                            },
                    ) {
                        CompositionLocalProvider(LocalRowPreview provides { rowPreview = it }) {
                        HomeResultsBody(
                            results = results,
                            musicState = musicState,
                            youtubeState = youtubeState,
                            entry = resultsEntry,
                            onRetry = { attempt++ },
                            runner = runner,
                            hold = hold,
                            matchSongs = matchSongs,
                            genre = genre,
                            genreArtists = genreArtists,
                            libraryArtists = libraryState.artists,
                            onSearch = ::setQuery,
                        )
                        }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(BiasAlignment(0f, headerBias))
                        .padding(top = 12.dp)
                        .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HomeGreeting(top = greetingTop, kind = parsed.kind)
                    Spacer(Modifier.height(16.dp))
                    HomeSearchLine(
                        query = query,
                        ghost = ghost,
                        parsed = parsed,
                        onQueryChange = ::setQuery,
                        focusRequester = lineFocus,
                        onDown = { if (!openActions()) runCatching { tab.bodyFocus.requestFocus() } },
                        onEnter = {
                            tab.focusResultsWhenReady = true
                            if (results != null) runCatching { resultsEntry.requestFocus() }
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                    // One slot, one height: the letter strip, or the action row in its place.
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        val actionTop = shownTop
                        if (actionsOpen && actionTop != null) {
                            HomeActionRow(
                                top = actionTop,
                                kind = parsed.kind,
                                tracks = results?.centreTracks.orEmpty(),
                                primaryFocus = tab.bodyFocus,
                                runner = runner,
                                onUp = {
                                    moveFocusToSlot = true
                                    actionsOpen = false
                                },
                            )
                        } else {
                            LetterStrip(
                                // The likely next letters move beside the key only while the remote or the arrow keys drive the
                                // strip (one press instead of a walk along A to Z). With the mouse the strip stays A to Z, so
                                // nothing moves under the pointer; the likely letters are still lit either way.
                                arranged = remember(lastPressed, likely, KeyboardInputMode.active) {
                                    if (KeyboardInputMode.active) arrangeStrip(lastPressed, likely) else STRIP_LETTERS
                                },
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
                    }
                    Spacer(Modifier.height(12.dp))
                    // Recent searches, then suggestions while typing, then (once a match has settled) the Narrow row.
                    val chipItems = when {
                        query.isBlank() -> history.take(CHIPS_SHOWN).map { HomeChip(it) { setQuery(it); tab.focusResultsWhenReady = true } }
                        shownTop != null -> narrowChips(parsed, ::setQuery)
                        else -> suggestions.take(CHIPS_SHOWN).map { HomeChip(it) { setQuery(it); tab.focusResultsWhenReady = true } }
                    }
                    HomeChipRow(
                        label = when {
                            query.isBlank() -> "RECENT"
                            shownTop != null -> "NARROW"
                            else -> "SUGGESTED"
                        },
                        chips = chipItems,
                    )
                }
            }
        }
    }
    }
    }
}

private const val VOICE_OWNER = "music-search"

// region header

/** The icon row: Search first, then the Library's views (Shelf, Playlists). Null is Search. */
@Composable
private fun HomeTabs(libraryTab: LibraryView?, onSelect: (LibraryView?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        BrowseFilterIconAction(Icons.Rounded.Search, "Search", on = libraryTab == null) { onSelect(null) }
        HOME_VIEW_ICONS.forEach { (view, icon, label) ->
            BrowseFilterIconAction(icon, label, on = libraryTab == view) { onSelect(view) }
        }
    }
}

private val HOME_VIEW_ICONS: List<Triple<LibraryView, ImageVector, String>> = listOf(
    Triple(LibraryView.Crates, Icons.Rounded.ViewModule, "Library shelf"),
    Triple(LibraryView.Playlists, Icons.Rounded.QueueMusic, "Playlists"),
)

/**
 * The greeting slot: the weekday and part of the day over "What do you want to hear?", and once a top match has
 * settled, its kind over its name. A fixed height, so the line below never moves.
 */
@Composable
private fun HomeGreeting(top: MusicTopResult?, kind: QueryKind?) {
    val colors = MaterialTheme.nuvio.colors
    val label = remember(top, kind) {
        top?.let { listOfNotNull("TOP MATCH", it.kind?.uppercase(), kind?.let { k -> "SHOWING ${k.plural.uppercase()}" }).joinToString(" · ") }
            ?: localWeekdayAndHour().let { (day, hour) -> greetingLabel(day, hour) }
    }
    Column(Modifier.height(72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.accent, maxLines = 1)
        Text(
            if (top == null) {
                buildAnnotatedString { append("What do you want to hear?") }
            } else {
                buildAnnotatedString {
                    append(top.title)
                    // The kind asked for, fainter after the name: "Yelawolf remixes".
                    if (kind != null) withStyle(SpanStyle(color = colors.textPrimary.copy(alpha = 0.45f))) { append(" " + kind.plural) }
                }
            },
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
    parsed: ParsedQuery,
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
                // Away from the line, what was typed reads as chips (artist, kind, year); in it, as plain text to edit.
                val asChips = !focused && query.isNotBlank() && parsed.chips.isNotEmpty()
                Box(contentAlignment = Alignment.CenterStart) {
                    if (asChips) {
                        LineChips(parsed)
                    } else if (query.isEmpty()) {
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
                    Box(if (asChips) Modifier.alpha(0f) else Modifier) { inner() }
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
            Modifier
                // Glass behind the keys, so the letters read over a bright fanart.
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .onPreviewKeyEvent { event ->
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

/** One chip in the row under the strip. [selected] draws it lit (the Narrow row's chosen kind). */
private class HomeChip(val label: String, val selected: Boolean = false, val onClick: () -> Unit)

/**
 * The Narrow row for [parsed]: Everything, then each kind as "+ remix" (adds it) or, when chosen, "remix ×" (removes
 * it), then the year as "2012 ×" when there is one. Each rewrites the line, which searches again.
 */
private fun narrowChips(parsed: ParsedQuery, setQuery: (String) -> Unit): List<HomeChip> = buildList {
    add(HomeChip("Everything", selected = parsed.kind == null) { setQuery(withKind(parsed, null)) })
    QueryKind.entries.forEach { kind ->
        if (parsed.kind == kind) {
            add(HomeChip("${kind.label} ×", selected = true) { setQuery(withKind(parsed, null)) })
        } else {
            add(HomeChip("+ ${kind.label}") { setQuery(withKind(parsed, kind)) })
        }
    }
    parsed.year?.let { year -> add(HomeChip("$year ×", selected = true) { setQuery(withKind(parsed, parsed.kind, keepYear = false)) }) }
}

/** Recent searches, suggestions or the Narrow row: one label and the chips. Fixed height, so nothing below moves. */
@Composable
private fun HomeChipRow(label: String, chips: List<HomeChip>) {
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
                    .background(if (chip.selected) colors.accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f), shape)
                    .border(1.dp, if (chip.selected) colors.accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.14f), shape)
                    .clickable(onClick = chip.onClick)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    chip.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chip.selected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What was typed, drawn as chips in the line: the artist (when the whole name is one the library or the settled match
 * knows), else the words as typed, then the kind and the year.
 */
@Composable
private fun LineChips(parsed: ParsedQuery) {
    val colors = MaterialTheme.nuvio.colors
    val wholeArtist = parsed.artist?.takeIf { foldName(it) == foldName(parsed.terms) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (wholeArtist != null) {
            LineChip("ARTIST", wholeArtist, colors.accent)
        } else if (parsed.terms.isNotBlank()) {
            Text(parsed.terms, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, maxLines = 1)
        }
        parsed.kind?.let { LineChip("KIND", it.label, LineKindTint) }
        parsed.year?.let { LineChip("YEAR", it.toString(), colors.textSecondary) }
    }
}

private val LineKindTint = Color(0xFFC9A6EE)

@Composable
private fun LineChip(kicker: String, value: String, tint: Color) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.height(36.dp).clip(shape).background(tint.copy(alpha = 0.16f), shape).border(1.dp, tint.copy(alpha = 0.5f), shape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(kicker, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = tint, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.nuvio.colors.textPrimary, maxLines = 1)
    }
}

// endregion

// region results

@Composable
private fun HomeResultsBody(
    results: HomeResults?,
    musicState: PanelState,
    youtubeState: PanelState,
    entry: FocusRequester,
    onRetry: () -> Unit,
    runner: HomeRunner,
    hold: HoldToSelectState,
    matchSongs: List<MusicTopSong> = emptyList(),
    genre: GenreQuery? = null,
    genreArtists: List<GenreArtist> = emptyList(),
    libraryArtists: List<LibraryArtist> = emptyList(),
    onSearch: (String) -> Unit = {},
) {
    // The keyboard's way in: the first thing in the YouTube Music panel, else the top match, else the first video.
    val entryPanel = when {
        results == null -> -1
        results.rows.isNotEmpty() || results.albums.isNotEmpty() -> 1
        results.top != null || results.artists.isNotEmpty() || genre != null -> 0
        else -> 2
    }
    val left: @Composable ColumnScope.() -> Unit = {
        LensPanelHeader(Icons.Rounded.Star, "TOP MATCH", null, MaterialTheme.nuvio.colors.accent)
        PanelBody(musicState, onRetry, results?.let { it.top == null && it.artists.isEmpty() && genre == null } ?: true) {
            val r = results ?: return@PanelBody
            // The genre card leads unless an artist is the top match: the artist wins, the card sits second.
            val artistWins = r.top?.let { topMatchKind(it) == TopMatchKind.Artist } == true
            if (genre != null && !artistWins) {
                genreCard(genre, r, genreArtists, libraryArtists, runner, hold, onSearch, entryFirst = entryPanel == 0 && r.top == null, entry = entry)
            }
            r.top?.let { top ->
                item(key = "top") {
                    TopMatchRow(top, if (entryPanel == 0 && !(genre != null && !artistWins)) Modifier.focusRequester(entry) else Modifier, runner, hold)
                }
            }
            if (genre != null && artistWins) {
                genreCard(genre, r, genreArtists, libraryArtists, runner, hold, onSearch, entryFirst = false, entry = entry)
            }
            if (r.artists.isNotEmpty()) item(key = "artists-label") { SectionLabel("ARTISTS") }
            items(r.artists, key = { "artist:" + it.browseId }) { artist ->
                ArtistRow(artist, Modifier.let { m -> if (entryPanel == 0 && r.top == null && artist == r.artists.first()) m.focusRequester(entry) else m }, runner, hold)
            }
            if (matchSongs.isNotEmpty()) {
                item(key = "match-songs-label") { SectionLabel("TOP SONGS") }
                items(matchSongs.take(MATCH_SONGS_SHOWN), key = { "match-song:" + it.videoId }) { song ->
                    val track = MusicTrack(
                        videoId = song.videoId, title = song.title, artists = r.top?.artists.orEmpty(), durationSeconds = 0,
                        trackNumber = 0, isAvailable = true, albumBrowseId = song.albumBrowseId,
                    )
                    ResultRow(
                        art = null, circle = false, artSize = 40.dp, title = song.title, subtitle = r.top?.artists?.firstOrNull()?.name,
                        trailing = null, videoMark = false, modifier = Modifier, hold = hold,
                        menu = { runner.videoMenu(track) },
                        onClick = { runner.track(track, PlayHow.Now) },
                    )
                }
            }
        }
    }
    val centre: @Composable ColumnScope.() -> Unit = {
        LensPanelHeader(Icons.Rounded.MusicNote, results?.centreLabel ?: "YOUTUBE MUSIC · SONGS AND ALBUMS", null, YouTubeMusicTint)
        PanelBody(if (results?.rows?.isNotEmpty() == true) PanelState.Ready else musicState, onRetry, results?.let { it.rows.isEmpty() && it.albums.isEmpty() && it.playlists.isEmpty() } ?: true) {
            val r = results ?: return@PanelBody
            r.rows.forEachIndexed { index, row ->
                val track = row.playable ?: return@forEachIndexed
                item(key = "row:" + track.videoId) {
                    SongRow(row, if (entryPanel == 1 && index == 0) Modifier.focusRequester(entry) else Modifier, runner, hold, badge = row.source.label.takeIf { r.split })
                }
            }
            if (r.albums.isNotEmpty()) item(key = "albums-label") { SectionLabel("ALBUMS") }
            r.albums.forEachIndexed { index, album ->
                item(key = "album:" + album.browseId) {
                    AlbumRow(album, if (entryPanel == 1 && r.rows.isEmpty() && index == 0) Modifier.focusRequester(entry) else Modifier, runner, hold)
                }
            }
            // fork:music-radio - community playlists, YouTube Music's own and stations: OK plays, hold OK for the menu.
            if (r.playlists.isNotEmpty()) item(key = "playlists-label") { SectionLabel("PLAYLISTS AND RADIO") }
            r.playlists.forEach { list ->
                item(key = "list:" + list.playlistId) { PlaylistRow(list, runner, hold) }
            }
        }
    }
    val right: @Composable ColumnScope.() -> Unit = {
        LensPanelHeader(Icons.Rounded.SmartDisplay, results?.rightLabel ?: "YOUTUBE · VIDEOS", null, YouTubeTint)
        PanelBody(youtubeState, onRetry, results?.videos?.isEmpty() ?: true) {
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
    state: PanelState,
    onRetry: () -> Unit,
    empty: Boolean,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Spacer(Modifier.height(14.dp))
    when {
        state == PanelState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp))
        }
        state == PanelState.Failed -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't reach YouTube Music. Check your connection.", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
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
        preview = RowPreview(if (isArtist) top.title else top.artists.firstOrNull()?.name, top.thumbnailUrl),
        onClick = {
            when (kind) {
                TopMatchKind.Album -> runner.run(HomeAction.OpenAlbum, top)
                TopMatchKind.Track -> runner.run(HomeAction.PlayTrack, top)
                TopMatchKind.Artist -> runner.run(HomeAction.OpenArtist, top)
                TopMatchKind.Playlist -> runner.run(HomeAction.PlayList, top)
            }
        },
    )
}

@Composable
private fun ArtistRow(artist: MusicArtistSummary, modifier: Modifier, runner: HomeRunner, hold: HoldToSelectState) {
    ResultRow(
        art = artist.thumbnailUrl, circle = true, artSize = 48.dp, title = artist.name, subtitle = null, trailing = null, videoMark = false,
        modifier = modifier, menu = { runner.artistMenu(artist) }, hold = hold, onClick = { runner.openArtist(artist.browseId) },
        preview = RowPreview(artist.name, artist.thumbnailUrl),
    )
}

/**
 * fork:music-genre - the genre card and what hangs off it, as list items: the card (OK plays the genre's radio), then
 * the library artists in it, then the top tag-ranked artists (OK searches that artist). Hold OK on the card for the same.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.genreCard(
    genre: GenreQuery,
    results: HomeResults,
    genreArtists: List<GenreArtist>,
    libraryArtists: List<LibraryArtist>,
    runner: HomeRunner,
    hold: HoldToSelectState,
    onSearch: (String) -> Unit,
    entryFirst: Boolean,
    entry: FocusRequester,
) {
    val station = pickGenreStation(results.playlists, genre)
    val owned = libraryArtistsFor(genre.tag, libraryArtists)
    item(key = "genre-card") {
        ResultRow(
            art = station?.thumbnailUrl,
            circle = false,
            artSize = if (entryFirst || genre.kind != TagKind.Genre) 72.dp else 56.dp,
            title = genre.label,
            subtitle = listOfNotNull(genre.kind.noun, if (station != null) "Play radio" else "Play from your library").joinToString(" · "),
            trailing = null,
            videoMark = false,
            modifier = if (entryFirst) Modifier.focusRequester(entry) else Modifier,
            hold = hold,
            preview = RowPreview(genre.label, station?.thumbnailUrl),
            onClick = {
                when {
                    station != null -> runner.playlist(station.playlistId, station.title, PlayHow.Now, station.thumbnailUrl)
                    owned.isNotEmpty() -> owned.first().let { runner.artistRadio(it.id, it.name) }
                    genreArtists.isNotEmpty() -> onSearch(genreArtists.first().name)
                }
            },
        )
    }
    if (owned.isNotEmpty()) {
        item(key = "genre-owned-label") { SectionLabel("IN YOUR LIBRARY") }
        items(owned.take(GENRE_ARTISTS_SHOWN), key = { "genre-owned:" + it.id }) { a ->
            ResultRow(
                art = a.art.thumb, circle = true, artSize = 44.dp, title = a.name, subtitle = null, trailing = null, videoMark = false,
                modifier = Modifier, hold = hold, onClick = { runner.openArtist(a.id) },
            )
        }
    }
    val others = genreArtists.filter { !it.owned }
    if (others.isNotEmpty()) {
        item(key = "genre-top-label") { SectionLabel("TOP ARTISTS · ${genre.label.uppercase()}") }
        items(others.take(GENRE_ARTISTS_SHOWN), key = { "genre-top:" + it.name }) { a ->
            // fork:music-home - a tag-ranked artist is a name only, so its photo is Deezer's (exact name, kept for the
            // session) as on the ladder; the note placeholder shows until it arrives or when Deezer has none.
            val picture by produceState<String?>(null, a.name) { value = runCatching { LadderArtwork.picture(a.name) }.getOrNull() }
            ResultRow(
                art = picture, circle = true, artSize = 44.dp, title = a.name, subtitle = null, trailing = null, videoMark = false,
                modifier = Modifier, hold = hold, onClick = { onSearch(a.name) },
            )
        }
    }
}

private const val GENRE_ARTISTS_SHOWN = 8

private const val MATCH_SONGS_SHOWN = 6

/** How long the results sit still before missing lengths are looked up, and how many lookups run at once. */
private const val LENGTH_LOOKUP_PAUSE_MS = 400L
private const val LENGTH_LOOKUPS_AT_ONCE = 3

private const val NO_LENGTH_LABEL = "–:––"

/** A YouTube Music song; plays on its own. The video mark says the same song is on YouTube as a video too. */
@Composable
private fun SongRow(row: MergedRow, modifier: Modifier, runner: HomeRunner, hold: HoldToSelectState, badge: String? = null) {
    val track = row.playable ?: return
    val isSong = row.song != null
    ResultRow(
        art = track.coverUrl,
        circle = false,
        artSize = 48.dp,
        wideArt = !isSong,
        title = track.title,
        subtitle = listOfNotNull(
            if (isSong) "Song" else "Video",
            track.artists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
            track.albumTitle,
        ).joinToString(" · "),
        trailing = youTubeLengthLabel(track.durationSeconds) ?: NO_LENGTH_LABEL,
        videoMark = isSong && row.hasVideo && badge == null,
        reserveVideoSlot = isSong && badge == null,
        badge = badge,
        modifier = modifier,
        menu = { if (isSong) runner.songMenu(row) else runner.videoMenu(track) },
        hold = hold,
        preview = RowPreview(track.artists.firstOrNull()?.name, track.coverUrl),
        onClick = { runner.track(track, PlayHow.Now) },
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
        preview = RowPreview(album.artists.firstOrNull()?.name, album.thumbnailUrl),
        onClick = { runner.openAlbum(album.browseId) },
    )
}

/** fork:music-radio - a playlist or station: its cover, name and line ("Playlist • YouTube Music • 47 songs"). */
@Composable
private fun PlaylistRow(list: MusicPlaylistSummary, runner: HomeRunner, hold: HoldToSelectState) {
    ResultRow(
        art = list.thumbnailUrl,
        circle = false,
        artSize = 48.dp,
        title = list.title,
        subtitle = list.subtitle,
        trailing = if (list.radio) "Radio" else null,
        videoMark = false,
        modifier = Modifier,
        menu = { runner.playlistMenu(list) },
        hold = hold,
        preview = RowPreview(null, list.thumbnailUrl),
        onClick = { runner.playlist(list.playlistId, list.title, PlayHow.Now, list.thumbnailUrl) },
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
        trailing = youTubeLengthLabel(video.durationSeconds) ?: NO_LENGTH_LABEL,
        videoMark = false,
        modifier = modifier,
        menu = { runner.videoMenu(video) },
        hold = hold,
        preview = RowPreview(video.artists.firstOrNull()?.name, video.coverUrl),
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
    /** Song rows keep the video mark's slot even when empty, so the length never shifts between rows. */
    reserveVideoSlot: Boolean = false,
    /** A small source pill before the length (BOTH, YT MUSIC, YOUTUBE) on split panels. */
    badge: String? = null,
    /** The row's More menu (right-click, the Menu key or a held OK); a tap of OK is [onClick]. */
    menu: (() -> List<PosterZoomOverlayAction>)? = null,
    hold: HoldToSelectState? = null,
    /** What the row brings up behind the screen while the pointer or the remote is on it. */
    preview: RowPreview? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val report = LocalRowPreview.current
    LaunchedEffect(hovered) { if (hovered && preview != null) report(preview) }
    val menuState = rememberMusicMenu()
    if (menu != null) MusicItemMenu(menuState, menu)
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused && preview != null) report(preview) }
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
        } else if (reserveVideoSlot) {
            Spacer(Modifier.size(18.dp))
        }
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
        }
    }
}

// endregion
