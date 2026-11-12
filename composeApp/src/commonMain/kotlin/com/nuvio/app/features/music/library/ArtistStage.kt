package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's artist page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import com.nuvio.app.features.music.nebula.LosslessPill
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.HoldToSelectState
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.rememberHoldToSelectState
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.secondaryClick
import com.nuvio.app.features.music.LENS_GAP
import com.nuvio.app.features.music.LENS_LIBRARY_TINT
import com.nuvio.app.features.music.LENS_OUTER
import com.nuvio.app.features.music.LensBody
import com.nuvio.app.features.music.LensButton
import com.nuvio.app.features.music.LensFact
import com.nuvio.app.features.music.LensFactGrid
import com.nuvio.app.features.music.LensPanel
import com.nuvio.app.features.music.LensPanelHeader
import com.nuvio.app.features.music.LensPanelTitle
import com.nuvio.app.features.music.LensRow
import com.nuvio.app.features.music.LensSectionLabel
import com.nuvio.app.features.music.LocalMusicBarInset
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicItemMenu
import com.nuvio.app.features.music.musicMenuTrigger
import com.nuvio.app.features.music.rememberMusicMenu
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicTopSong
import com.nuvio.app.features.music.booklet.SongStories
import com.nuvio.app.features.music.booklet.SongStory
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.formatMusicTime
import com.nuvio.app.features.music.identity.HeroSource
import com.nuvio.app.features.music.identity.identity
import com.nuvio.app.features.music.lensPanelWidth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * fork:artist-stage - the redesigned artist page (Music settings, "New artist page"), built like the new album page.
 *
 *  - The hero fills the first screen: the artist's photo, darkened, with the logo, the facts, the stats in words, the genres
 *    and a row of labelled buttons, Play Essentials first.
 *  - Moving down scrolls the hero away to three panels as tall as the window: about the artist (or the song the keyboard is
 *    on in Essentials), the lens tabs in the middle (Essentials, Discography), and the library side with Similar taste.
 *
 * Panel sizes depend on the window only, never on what they hold. All play / dialog / Add logic that the old page has stays
 * in MusicArtistScreen; this draws, reports taps, and plays the Essentials queue itself.
 */

/** The new artist page's lens tabs. */
internal enum class ArtistLens(val label: String) {
    Essentials("Essentials"),
    /** Albums, EPs, live records and compilations, with the kind pills. */
    Albums("Albums"),
    Singles("Singles"),
    /** fork:artist-story - a career timeline worked out from release dates. */
    Story("Story"),
}

/** How long the keyboard rests on a song before its story is asked for (arrowing down the list asks nothing). */
private const val STAGE_STORY_REST_MS = 700L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ArtistStage(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    notice: String?,
    saved: Boolean,
    refreshing: String?,
    working: Boolean,
    filter: ReleaseFilter,
    playableOnly: Boolean,
    demosOpen: Boolean,
    canShuffle: Boolean,
    topLabel: String?,
    onBack: () -> Unit,
    /** Plays the newest playable release: what Play Essentials falls back to when YouTube Music lists no songs. */
    onPlayLatest: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTop: () -> Unit,
    onLinks: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpen: (LibraryRelease) -> Unit,
    onInfo: (LibraryRelease) -> Unit,
    onPlayableOnly: (Boolean) -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    onToggleDemos: () -> Unit,
    addonKeys: Set<String>,
    /** fork:music-menu - plays [LibraryRelease] (shuffled or not) the way the old page's Play does. */
    onPlayRelease: (LibraryRelease, Boolean) -> Unit,
    /** fork:music-menu - puts the release after what is queued. */
    onQueueRelease: (LibraryRelease) -> Unit,
    /** fork:music-menu - plays the release once the track playing ends. */
    onPlayNextRelease: (LibraryRelease) -> Unit,
    /** fork:music-menu - opens an album page by its id (a song's album). */
    onOpenAlbumId: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val catalog = remember { defaultMusicCatalog() }
    val hold = rememberHoldToSelectState()
    val scope = rememberCoroutineScope()

    // Essentials: YouTube Music's most played list for the artist, then the albums of its songs (length, cover).
    val topSongs by produceState<List<MusicTopSong>?>(null, artist.id) {
        value = try {
            catalog.artist(artist.id)?.topSongs.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emptyList()
        }
    }
    // fork:artist-fill - when YouTube Music lists fewer than ten songs, Deezer's most played ones it lacks fill the rest.
    val deezerTop by produceState<List<DeezerTopSong>>(emptyList(), artist.deezerId, topSongs) {
        val id = artist.deezerId
        val have = topSongs
        value = if (id != null && have != null && needsDeezerFill(have)) {
            ArtistTopSongs.ensure(id)
            ArtistTopSongs.state.value[id].orEmpty()
        } else {
            emptyList()
        }
    }
    val fills = remember(topSongs, deezerTop, artist.releases) {
        deezerFills(topSongs.orEmpty(), deezerTop, artist.releases)
    }
    var albums by remember(artist.id) { mutableStateOf<Map<String, MusicAlbum>>(emptyMap()) }
    LaunchedEffect(topSongs, fills) {
        val songs = topSongs ?: return@LaunchedEffect
        for (id in (essentialAlbumIds(songs) + fills.map { it.browseId }).distinct()) {
            if (id in albums) continue
            val album = try {
                catalog.album(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            }
            if (album != null) albums = albums + (id to album)
        }
    }
    val rows = remember(topSongs, fills, albums) {
        essentialRows(topSongs.orEmpty() + filledTopSongs(fills, albums), albums)
    }
    val mix = remember(rows, artist.id) { essentialsMix(artist.id, artist.name, artist.art.thumb, rows) }
    fun playEssentials(fromVideoId: String? = null) {
        val queue = mix
        if (queue == null) {
            onPlayLatest()
            return
        }
        val at = fromVideoId?.let { id -> queue.tracks.indexOfFirst { it.videoId == id } }?.takeIf { it >= 0 } ?: 0
        MusicPlayback.player.play(queue, trackIndex = at)
    }

    // fork:music-menu - what a song's and a release's More menus offer.
    fun oneSong(row: EssentialRow): MusicAlbum? {
        val queue = mix ?: return null
        val track = queue.tracks.firstOrNull { it.videoId == row.videoId } ?: return null
        return queue.copy(browseId = "${queue.browseId}:${row.videoId}", title = row.title, tracks = listOf(track.copy(trackNumber = 1)))
    }
    val songActions = SongMenuActions(
        playFrom = { playEssentials(it.videoId) },
        playOne = { row -> oneSong(row)?.let { MusicPlayback.player.play(it) } },
        queueOne = { row -> oneSong(row)?.let { MusicPlayback.player.enqueue(it) } },
        playNextOne = { row -> oneSong(row)?.let { MusicPlayback.player.playNext(it) } },
        openAlbum = onOpenAlbumId,
    )
    val releaseActions = ReleaseMenuActions(
        play = onPlayRelease,
        queue = onQueueRelease,
        playNext = onPlayNextRelease,
        open = onOpen,
        info = onInfo,
        playable = { it.isPlayable || it.key in addonKeys },
    )

    var lens by rememberSaveable(artist.id) { mutableStateOf(ArtistLens.Essentials) }
    // The song the keyboard is on in Essentials: the left panel is about it; null is about the artist.
    var focusRank by remember(artist.id) { mutableStateOf<Int?>(null) }
    val focusRow = focusRank?.let { r -> rows.firstOrNull { it.rank == r } }
    val singles = remember(artist.releases) { singleKeys(artist.releases) }

    BoxWithConstraints(modifier.background(colors.background)) {
        val viewport = maxHeight
        val side = lensPanelWidth(maxWidth)
        // Short of room the panels go, as on the album page: the left one first, then the right.
        val showRight = com.nuvio.app.features.music.lensShowsRight(maxWidth, side)
        val showLeft = com.nuvio.app.features.music.lensShowsLeft(maxWidth, side)
        val inset = LocalMusicBarInset.current
        val scroll = rememberScrollState()
        // The page is two screens: the hero, then the three panels. Whether the person was on the panels is kept with the page, so
        // coming back to it (Back on an album opened from here) lands on the whole panel screen again. Returning focus to the
        // album tile otherwise scrolls only far enough to show that tile, leaving the page stuck between the two screens.
        var onPanels by rememberSaveable(artist.id) { mutableStateOf(false) }
        // Read while composing, before the tracker below can see the page's first frame (scrolled to the top) and reset it.
        val returnToPanels = remember(artist.id) { onPanels }
        // While a return to the panels settles, focus landing on the hero for a moment must not take the page back up.
        var returnSettled by remember(artist.id) { mutableStateOf(!returnToPanels) }
        LaunchedEffect(scroll) {
            snapshotFlow { scroll.maxValue.takeIf { it > 0 }?.let { scroll.value >= it / 2 } }.collect { if (it != null) onPanels = it }
        }
        // Snapping: the page is two screens and is never left between them. One notch of the wheel goes the whole way (a
        // list inside a panel that can still scroll keeps the notch), and a move that stops part way (focus brought a
        // control into view) carries on to the screen it was heading for.
        var settledOnPanels by remember(artist.id) { mutableStateOf(returnToPanels) }
        var lastInnerUp by remember(artist.id) { mutableStateOf<kotlin.time.TimeMark?>(null) }
        fun goTo(panels: Boolean) {
            settledOnPanels = panels
            scope.launch { scroll.animateScrollTo(if (panels) scroll.maxValue else 0) }
        }
        LaunchedEffect(scroll) {
            snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
                if (moving) return@collect
                val max = scroll.maxValue
                val at = scroll.value
                when {
                    max <= 0 -> Unit
                    at <= 0 -> settledOnPanels = false
                    at >= max -> settledOnPanels = true
                    else -> goTo(!settledOnPanels)
                }
            }
        }
        LaunchedEffect(artist.id) {
            if (!returnToPanels) return@LaunchedEffect
            snapshotFlow { scroll.maxValue }.first { it > 0 }
            settledOnPanels = true
            scroll.scrollTo(scroll.maxValue)
            // Focus coming back to the tile runs after the first frames and may scroll again: settle on the panels once more.
            delay(RETURN_SETTLE_MS)
            settledOnPanels = true
            scroll.scrollTo(scroll.maxValue)
            returnSettled = true
        }
        // A new visit (another artist opened from Similar taste, the search, ...) starts on the hero. The page root's first-control
        // focus can land on a control in the panels before Play Essentials takes it, and scrolls part way down to show it; once
        // Play Essentials has focus the page goes back to the top.
        val onHeroFocused: () -> Unit = { if (!returnToPanels) scope.launch { settledOnPanels = false; scroll.scrollTo(0) } }
        Column(
            Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val dy = change.scrollDelta.y
                    val now = kotlin.time.TimeSource.Monotonic.markNow()
                    // A list in a panel scrolling up (Albums, Singles): remember when, so reaching its top does not carry
                    // straight on up to the hero.
                    if (change.isConsumed) {
                        if (dy < 0f) lastInnerUp = now
                        return@onPointerEvent
                    }
                    // Grace: an upward notch soon after the list was scrolling up is swallowed (and keeps the grace going), so
                    // only a fresh scroll up after a pause goes back to the hero.
                    val recent = lastInnerUp
                    if (dy < 0f && recent != null && recent.elapsedNow() < UP_GRACE) {
                        lastInnerUp = now
                        change.consume()
                        return@onPointerEvent
                    }
                    when {
                        dy > 0f && scroll.value < scroll.maxValue -> goTo(panels = true)
                        dy < 0f && scroll.value > 0 -> goTo(panels = false)
                        else -> return@onPointerEvent
                    }
                    change.consume()
                }
                // The wheel is the snapping above, not a free scroll; focus and the code still move the page.
                .verticalScroll(scroll, enabled = false),
        ) {
            StageHero(
                artist = artist,
                discography = discography,
                notice = notice,
                saved = saved,
                refreshing = refreshing,
                canShuffle = canShuffle,
                topLabel = topLabel,
                takeFocus = !returnToPanels,
                onHeroFocused = onHeroFocused,
                onPlayEssentials = { playEssentials() },
                onShuffle = onShuffle,
                onPlayTop = onPlayTop,
                onLinks = onLinks,
                onRefresh = onRefresh,
                onRemove = onRemove,
                // Focus going up into the hero takes the page back to it (and down into the panels, below, to them).
                modifier = Modifier.fillMaxWidth().height(viewport)
                    .onFocusChanged { if (it.hasFocus && settledOnPanels && returnSettled) goTo(panels = false) },
            )
            Row(
                Modifier.fillMaxWidth().height(viewport)
                    .onFocusChanged { if (it.hasFocus && !settledOnPanels) goTo(panels = true) }
                    // The top clears the back button, which stays in the corner on both screens.
                    .padding(start = LENS_OUTER, end = LENS_OUTER, top = BACK_CLEARANCE, bottom = inset + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(LENS_GAP),
            ) {
                val left = Modifier.width(side)
                if (!showLeft) Unit else if (focusRow != null) {
                    StageSongPanel(artist, focusRow, single = songKey(focusRow.title) in singles, onPlay = { playEssentials(focusRow.videoId) }, modifier = left)
                } else {
                    StageArtistPanel(artist, discography, canPlay = mix != null || discography.playableCount > 0, onPlay = { playEssentials() }, modifier = left)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    StageCentreHeader(artist, discography)
                    ArtistLensTabs(lens, onSelect = { lens = it; focusRank = null })
                    when (lens) {
                        ArtistLens.Essentials -> EssentialsLens(
                            rows = rows,
                            loading = topSongs == null,
                            top = remember(artist.topAlbumKeys, artist.releases) { artistTopReleases(artist) },
                            focusRank = focusRank,
                            onFocusSong = { focusRank = it },
                            onPlaySong = { playEssentials(it.videoId) },
                            songActions = songActions,
                            releaseActions = releaseActions,
                            hold = hold,
                            onOpen = onOpen,
                            onInfo = onInfo,
                            addonKeys = addonKeys,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        ArtistLens.Story -> StoryLens(
                            artist = artist,
                            releaseActions = releaseActions,
                            hold = hold,
                            addonKeys = addonKeys,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        ArtistLens.Albums, ArtistLens.Singles -> DiscographyLens(
                            singlesOnly = lens == ArtistLens.Singles,
                            artist = artist,
                            discography = discography,
                            working = working,
                            filter = filter,
                            playableOnly = playableOnly,
                            demosOpen = demosOpen,
                            onOpen = onOpen,
                            onInfo = onInfo,
                            onPlayableOnly = onPlayableOnly,
                            onFilter = onFilter,
                            onToggleDemos = onToggleDemos,
                            addonKeys = addonKeys,
                            releaseActions = releaseActions,
                            hold = hold,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
                if (showRight) StageLibraryPanel(
                    artist = artist,
                    discography = discography,
                    addonCount = addonKeys.size,
                    canShuffle = canShuffle,
                    onShuffle = onShuffle,
                    onOpenArtist = onOpenArtist,
                    openSimilar = { name, done ->
                        scope.launch {
                            val found = try {
                                rungArtist(catalog, name)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                null
                            }
                            done(found != null)
                            if (found != null) onOpenArtist(found.browseId)
                        }
                    },
                    modifier = Modifier.width(side),
                )
            }
        }
        // Always in the top left corner, over the hero and over the panels, so Back never needs a scroll to the top first.
        Box(Modifier.padding(start = 24.dp, top = 16.dp)) { NuvioBackButton(onClick = onBack) }
    }
}

/** How long after coming back to the page focus may still scroll it; the page settles on the panels again after that. */
private const val RETURN_SETTLE_MS = 250L

/** How long after a panel's list stopped scrolling up the wheel waits before an upward notch may go back to the hero. */
private val UP_GRACE = kotlin.time.Duration.parse("700ms")

/** The panel screen's top: under the back button (16 dp down, 40 dp tall) with a small gap. */
private val BACK_CLEARANCE = 64.dp

// region hero

/**
 * The first screen: the artist's picture filling it (a blurred cover when there is no photo), darkened evenly with a deeper
 * foot, and everything centred over it. Nothing in it depends on another part's size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageHero(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    notice: String?,
    saved: Boolean,
    refreshing: String?,
    canShuffle: Boolean,
    topLabel: String?,
    /** False when coming back to the panels: Play Essentials then leaves focus where it returns, so the page is not pulled up. */
    takeFocus: Boolean,
    /** Called once Play Essentials has taken focus on opening the page. */
    onHeroFocused: () -> Unit,
    onPlayEssentials: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTop: () -> Unit,
    onLinks: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val dark = colors.background
    val identity = remember(artist) { artist.identity() }
    Box(modifier.background(Brush.verticalGradient(0f to NuvioGlass.night(dark, colors.accent), 1f to dark))) {
        identity.hero?.let { hero ->
            val blurred = hero.source == HeroSource.BlurredCover
            NuvioAsyncImage(
                model = hero.url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(if (blurred) 0.55f else 1f).let { if (blurred) it.blur(40.dp) else it },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to dark.copy(alpha = 0.35f), 0.45f to dark.copy(alpha = 0.55f), 0.92f to dark, 1f to dark),
            ),
        )
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 1000.dp).padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val logo = artist.art.bestLogo
            if (logo != null) {
                NuvioAsyncImage(
                    model = logo,
                    contentDescription = artist.name,
                    modifier = Modifier.width(640.dp).height(170.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                )
            } else {
                Text(
                    artist.name,
                    style = MaterialTheme.typography.displayLarge,
                    fontFamily = FontFamily.Serif,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val facts = listOfNotNull(
                passportLine(artist).takeIf { it.isNotEmpty() },
                "${discography.totalCount} release${if (discography.totalCount == 1) "" else "s"}",
            ).joinToString("  ·  ")
            Text(facts, style = MaterialTheme.typography.titleMedium, color = colors.textSecondary, textAlign = TextAlign.Center)
            // The stats in words: "25" alone never said what it counted.
            val stats = listOfNotNull(
                artist.fans?.let { "${formatCount(it)} fans" },
                ratingText(artist.rating)?.let { "Rated $it" },
                "${discography.playableCount} playable here",
            ).joinToString("   ·   ")
            Text(stats, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, textAlign = TextAlign.Center)
            val (mood, genres) = passportChips(artist)
            val chips = (listOfNotNull(mood) + genres).take(6)
            if (chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { chip ->
                        Text(
                            chip.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } },
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary.copy(alpha = colors.textSecondary.alpha * 0.75f),
                            modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            val inLibrary = MusicLibraryRepository.state.collectAsState().value.contains(artist.id)
            val linkCount = groupLinks(artist.links).values.sumOf { it.size }
            val playFocus = remember(artist.id) { FocusRequester() }
            LaunchedEffect(artist.id) {
                if (!takeFocus) return@LaunchedEffect
                // Asked a few times: the page root's own first-control request lands first.
                repeat(10) {
                    delay(80)
                    if (runCatching { playFocus.requestFocus() }.getOrDefault(false)) {
                        onHeroFocused()
                        return@LaunchedEffect
                    }
                }
            }
            // The old artist page's round buttons, each named on hover (the labelled pills ran the width of the hero).
            val actions = listOfNotNull(
                HeroAction("Play Essentials", Icons.Rounded.PlayArrow, modifier = Modifier.focusRequester(playFocus), onClick = onPlayEssentials),
                HeroAction("Shuffle all", Icons.Rounded.Shuffle, onClick = { if (canShuffle) onShuffle() }),
                topLabel?.let { HeroAction("Play $it", Icons.Rounded.LocalFireDepartment, onClick = onPlayTop) },
                // fork:music-radio - the artist's radio, as YouTube Music makes it.
                if (artist.id.startsWith("UC")) HeroAction("Radio", Icons.Rounded.Radio, onClick = { com.nuvio.app.features.music.MusicRadioStarter.artist(artist.id, artist.name) }) else null,
                HeroAction(
                    if (inLibrary) "In your library (remove)" else "Add to library",
                    if (inLibrary) Icons.Rounded.Check else Icons.Rounded.Add,
                    active = inLibrary,
                    onClick = { if (inLibrary) onRemove() else MusicLibraryRepository.add(artist.id) },
                ),
                if (linkCount > 0) HeroAction("Links ($linkCount)", Icons.Rounded.Link, onClick = onLinks) else null,
                if (saved) HeroAction(refreshing ?: "Refresh", Icons.Rounded.Refresh, onClick = { if (refreshing == null) onRefresh() }) else null,
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                actions.forEach { action ->
                    com.nuvio.app.core.ui.NuvioPosterHoverTooltip(title = action.label) {
                        com.nuvio.app.features.details.components.DetailIconAction(
                            label = action.label,
                            icon = action.icon,
                            active = action.active,
                            size = 56.dp,
                            modifier = action.modifier,
                            onClick = action.onClick,
                        )
                    }
                }
            }
            (notice ?: refreshing)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = if (notice != null) colors.warning else colors.textMuted, textAlign = TextAlign.Center, maxLines = 2)
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = LocalMusicBarInset.current + 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
            Text("Down for more about ${artist.name}", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
        }
    }
}

/** One of the hero's round buttons; [active] lights it (the artist is in the library). */
private class HeroAction(
    val label: String,
    val icon: ImageVector,
    val active: Boolean = false,
    val modifier: Modifier = Modifier,
    val onClick: () -> Unit,
)

// endregion

// region side panels

/** Left, with no song in focus: who the artist is, four facts, and their story (the library's biography). */
@Composable
private fun StageArtistPanel(artist: LibraryArtist, discography: ArtistDiscography, canPlay: Boolean, onPlay: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val own = artist.biography?.trim().orEmpty()
    // No biography in the library: the opening of the artist's English Wikipedia page (the one Liner Notes reads), when it
    // is about a musician.
    val wiki by produceState<String?>(StageWikipedia.peek(artist.name), artist.name, own.isEmpty()) {
        if (own.isEmpty() && value == null) value = StageWikipedia.load(artist.name)
    }
    val bio = own.ifEmpty { wiki.orEmpty() }
    LensPanel(modifier) {
        StagePanelTitle(artist.name, passportLine(artist).ifEmpty { "Artist" })
        LensFactGrid(
            remember(artist, discography.totalCount, discography.playableCount) {
                artistStageFacts(artist, discography.totalCount, discography.playableCount).map { (label, value) -> LensFact(label, value) }
            },
            centred = true,
        )
        // The story only when there is one (no box: it reads like Similar taste opposite); the facts are their own section.
        val facts = remember(artist) { artistFactSheet(artist) }
        Column(Modifier.fillMaxWidth().weight(1f)) {
            if (bio.isNotEmpty()) {
                StageSectionHeading("THE STORY")
                Text(
                    bio,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                )
            }
            if (!facts.isEmpty) StageFacts(facts)
        }
        LensButton("Play Essentials", tint = colors.accent, enabled = canPlay, onClick = onPlay)
    }
}

/**
 * Left, with a song in focus in Essentials: the song, four facts, and its story (the same one the album page tells: its
 * Wikipedia article, written up by the AI when a key is set). The story is asked for once the keyboard rests on the song.
 */
@Composable
private fun StageSongPanel(artist: LibraryArtist, row: EssentialRow, single: Boolean, onPlay: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    // Settings > Music > Album notes and facts: off, no song story is looked up or shown.
    val notesOn = MusicLibraryRepository.state.collectAsState().value.settings.albumNotes
    var story by remember(artist.name, row.title, notesOn) { mutableStateOf<SongStory?>(if (notesOn) SongStories.peek(artist.name, row.title) else null) }
    var loading by remember(artist.name, row.title) { mutableStateOf(false) }
    LaunchedEffect(artist.name, row.title, notesOn) {
        if (!notesOn) return@LaunchedEffect
        val aiReady = com.nuvio.app.features.discover.DiscoverAiSettingsRepository.snapshot().isConfigured
        val kept = story
        if (kept != null && (kept.ai || !aiReady)) return@LaunchedEffect
        loading = kept == null
        delay(STAGE_STORY_REST_MS)
        runCatching { SongStories.load(artist.name, row.title, wantAi = true, backup = true) }.getOrNull()?.let { story = it }
        loading = false
    }
    val text = lensStoryBox(story)?.text
    LensPanel(modifier) {
        StagePanelTitle(row.title, row.albumTitle?.let { "On $it" } ?: "One of ${artist.name}'s most played")
        LensFactGrid(
            listOf(
                LensFact("Length", row.durationSeconds.takeIf { it > 0 }?.let { formatMusicTime(it * 1000L) } ?: "–"),
                LensFact("Most played", "#${row.rank}"),
                LensFact("Released as a single", if (single) "Yes" else "No"),
                LensFact("Album", row.albumTitle ?: "–"),
            ),
            centred = true,
        )
        // The story only while it is being looked for or once found; no box around it.
        Column(Modifier.fillMaxWidth().weight(1f)) {
            if (text != null || loading) {
                StageSectionHeading("THE STORY", if (text == null) "Looking…" else null)
                if (text != null) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    )
                }
            }
        }
        LensButton("Play the song", tint = colors.accent, enabled = true, onClick = onPlay)
    }
}

/** The artist's Wikipedia opening, asked once per name per session (a miss is kept too, so it is not asked again). */
private object StageWikipedia {
    private val kept = HashMap<String, String?>()

    fun peek(name: String): String? = kept[name.lowercase()]

    suspend fun load(name: String): String? {
        val key = name.lowercase()
        if (kept.containsKey(key)) return kept[key]
        val text = try {
            WikipediaClient.artistText(name)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return null // offline: asked again next time
        }?.takeIf(::looksLikeMusicianText)
        kept[key] = text
        return text
    }
}

/** A similar artist as the right panel lists it: a saved one opens at once, a name from Last.fm is looked up first. */
private class StageSimilar(val name: String, val savedId: String?)

/** Right: the library side. What of the discography plays here, then Similar taste as a list, then Shuffle all. */
@Composable
private fun StageLibraryPanel(
    artist: LibraryArtist,
    discography: ArtistDiscography,
    addonCount: Int,
    canShuffle: Boolean,
    onShuffle: () -> Unit,
    onOpenArtist: (String) -> Unit,
    openSimilar: (name: String, done: (Boolean) -> Unit) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val library = MusicLibraryRepository.state.collectAsState().value
    val inLibrary = library.contains(artist.id)
    val saved = remember(artist.id, library.artists.size) { similarInLibrary(artist, library.artists, limit = 4) }
    val names by produceState<List<SimilarArtist>?>(null, artist.name) {
        value = try {
            SimilarTaste.forArtist(artist.name).orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emptyList()
        }
    }
    val similar = remember(saved, names) {
        val savedNames = saved.map { it.name.lowercase() }.toSet()
        saved.map { StageSimilar(it.name, it.id) } +
            names.orEmpty().filter { it.name.lowercase() !in savedNames }.map { StageSimilar(it.name, null) }
    }.take(SIMILAR_PER_ROW * SIMILAR_ROWS)
    var note by remember(artist.id) { mutableStateOf<String?>(null) }
    LensPanel(modifier) {
        StagePanelTitle(
            if (inLibrary) "Saved in your library" else "Not in your library",
            "${discography.playableCount} of ${discography.totalCount} releases play here",
        )
        val counts = discography.filterCounts
        LensFactGrid(
            listOf(
                LensFact("Studio albums", "${remember(artist.releases) { studioAlbumCount(artist) }}"),
                LensFact("Singles & EPs", "${(counts[ReleaseFilter.Singles] ?: 0) + (counts[ReleaseFilter.EPs] ?: 0)}"),
                LensFact("Only on addon", if (addonCount == 0) "None" else "$addonCount"),
                LensFact("Demos", if (discography.demos.isEmpty()) "None" else "${discography.demos.size}"),
            ),
            centred = true,
        )
        StageSectionHeading("SIMILAR TASTE", note ?: if (names == null) "Looking…" else null)
        LensBody(LENS_LIBRARY_TINT, highlighted = false) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                if (similar.isEmpty() && names != null) {
                    Text("No similar artists found.", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                }
                // Portraits three to a row, each with the name under it; one saved in the library is ringed in the accent.
                similar.chunked(SIMILAR_PER_ROW).forEach { line ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        line.forEach { other ->
                            val savedArt = other.savedId?.let { id -> library.artists.firstOrNull { it.id == id }?.art?.thumb }
                            SimilarPortrait(
                                name = other.name,
                                photo = savedArt,
                                saved = other.savedId != null,
                                onClick = {
                                    val id = other.savedId
                                    if (id != null) onOpenArtist(id) else openSimilar(other.name) { found -> note = if (found) null else "${other.name} isn't on YouTube Music" }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(SIMILAR_PER_ROW - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        LensButton("Shuffle all", tint = LENS_LIBRARY_TINT, enabled = canShuffle, onClick = onShuffle)
    }
}

/**
 * The side panels' title and the line under it, centred, at the same place in both so the two read as a pair (the artist
 * or the song on the left, the library on the right).
 */
@Composable
private fun StagePanelTitle(title: String, subtitle: String) {
    val colors = MaterialTheme.nuvio.colors
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A side panel's section heading (The story, Similar taste): centred, one size, with an optional [note] under it. */
@Composable
private fun StageSectionHeading(label: String, note: String? = null) {
    val colors = MaterialTheme.nuvio.colors
    Column(Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.textSecondary)
        // Kept to one line's height whether or not there is a note, so the two panels' sections start level.
        Text(
            note.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The facts under the story, drawn like the rest of the panel rather than as a form: a short hairline, the years active
 * as one centred line, the span of releases under it, and the genres, style and mood as the hero's quiet outlined tags
 * (the mood in the accent).
 */
@Composable
private fun StageFacts(facts: ArtistFactSheet) {
    val colors = MaterialTheme.nuvio.colors
    Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.3f).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        facts.years?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, modifier = Modifier.padding(top = 22.dp))
        }
        facts.releases?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, modifier = Modifier.padding(top = 4.dp))
        }
        val tags = facts.tags.map { it.titleCasedTag() to false } + listOfNotNull(facts.mood?.let { it.titleCasedTag() to true })
        if (tags.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { (tag, mood) ->
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (mood) colors.accent else colors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier
                            .border(1.dp, if (mood) colors.accent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** "synth-pop" as "Synth-pop"; a word already in capitals ("EDM") stays as it is. */
private fun String.titleCasedTag(): String = split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

private const val SIMILAR_PER_ROW = 3
private const val SIMILAR_ROWS = 3

/**
 * One similar artist: a round portrait (the library's picture for a saved one, else TheAudioDB's, looked up when it
 * shows) and the name under it. A saved artist is ringed in the accent.
 */
@Composable
private fun SimilarPortrait(name: String, photo: String?, saved: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val looked by produceState(photo, name, photo) {
        if (value == null) value = runCatching { com.nuvio.app.features.music.home.MusicHeroInfo.artist(name)?.thumb }.getOrNull()
    }
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).musicKeyFocusRing(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(if (saved) 3.dp else 1.dp, if (saved) colors.accent else Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val url = looked
            if (url != null) {
                NuvioAsyncImage(model = url, contentDescription = name, modifier = Modifier.fillMaxSize().padding(if (saved) 3.dp else 1.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            } else {
                Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = colors.textMuted)
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
    }
}

// endregion

// region centre

/** The centre's header: the portrait at the left, the name centred, the top album's cover at the right, the same size. */
@Composable
private fun StageCentreHeader(artist: LibraryArtist, discography: ArtistDiscography) {
    val colors = MaterialTheme.nuvio.colors
    val firstTop = remember(artist.topAlbumKeys, artist.releases) { stageHeaderRelease(artist) }
    Row(Modifier.fillMaxWidth().height(104.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)) {
            artist.art.thumb?.let { NuvioAsyncImage(model = it, contentDescription = artist.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "ARTIST · ${discography.totalCount} RELEASES · ${discography.playableCount} PLAYABLE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                color = colors.accent,
                maxLines = 1,
            )
            Text(
                artist.name,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Serif,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f))) {
            // The top album's cover; without one, the artist's logo or a wide fanart picture, so the square is never empty.
            val logo = artist.art.bestLogo
            val fanart = artist.art.bestBackground
            when {
                firstTop != null -> ReleaseCover(firstTop, Modifier.fillMaxSize())
                logo != null -> NuvioAsyncImage(model = logo, contentDescription = artist.name, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                fanart != null -> NuvioAsyncImage(model = fanart, contentDescription = artist.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

/** The lens tabs: one glass pill, equal slots, the chosen one in the accent (the album page's tabs, for the artist lenses). */
@Composable
private fun ArtistLensTabs(selected: ArtistLens, onSelect: (ArtistLens) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val pill = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(pill).background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), pill).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArtistLens.entries.forEach { lens ->
            val on = lens == selected
            val tab = RoundedCornerShape(20.dp)
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(tab)
                    .background(if (on) colors.accent.copy(alpha = 0.20f) else Color.Transparent)
                    .musicKeyFocusRing(tab)
                    .semantics { role = Role.Tab; this.selected = on }
                    .clickable { onSelect(lens) },
                contentAlignment = Alignment.Center,
            ) {
                Text(lens.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) colors.accent else colors.textSecondary, maxLines = 1)
            }
        }
    }
}

/** Essentials: the artist's most played songs (a song plays the Essentials queue from there), then the top albums. */
@Composable
private fun EssentialsLens(
    rows: List<EssentialRow>,
    loading: Boolean,
    top: List<LibraryRelease>,
    focusRank: Int?,
    onFocusSong: (Int?) -> Unit,
    onPlaySong: (EssentialRow) -> Unit,
    songActions: SongMenuActions,
    releaseActions: ReleaseMenuActions,
    hold: HoldToSelectState,
    onOpen: (LibraryRelease) -> Unit,
    onInfo: (LibraryRelease) -> Unit,
    addonKeys: Set<String>,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#", style = MaterialTheme.typography.labelMedium, color = colors.textMuted, modifier = Modifier.width(36.dp))
            Text("TOP SONGS", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.textMuted, modifier = Modifier.weight(1f))
            Text("ALBUM", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.textMuted, modifier = Modifier.width(220.dp))
            Text("TIME", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
        }
        if (rows.isEmpty()) {
            Text(
                if (loading) "Looking up the most played songs…" else "YouTube Music lists no most played songs for this artist.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        rows.forEach { row ->
            val on = row.rank == focusRank
            val shape = RoundedCornerShape(10.dp)
            val menu = rememberMusicMenu()
            MusicItemMenu(menu) { songMenu(row, songActions) }
            val select = { onFocusSong(row.rank); onPlaySong(row) }
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(shape)
                    .background(if (on) colors.accent.copy(alpha = 0.10f) else Color.Transparent)
                    .border(1.dp, if (on) colors.accent.copy(alpha = 0.6f) else Color.Transparent, shape)
                    .onFocusChanged { if (it.isFocused) onFocusSong(row.rank) }
                    .musicMenuTrigger(menu, hold, onSelect = select)
                    .musicKeyFocusRing(shape)
                    .clickable(onClick = select)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${row.rank}", style = MaterialTheme.typography.bodyLarge, color = colors.textMuted, modifier = Modifier.width(36.dp))
                Text(row.title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(row.albumTitle.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
                Text(
                    row.durationSeconds.takeIf { it > 0 }?.let { formatMusicTime(it * 1000L) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
        if (top.isNotEmpty()) {
            Text(
                "TOP ALBUMS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 14.dp, top = 20.dp, bottom = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                top.take(5).forEach { release ->
                    StageAlbumTile(release, addon = release.key in addonKeys, onFocus = { onFocusSong(null) }, actions = releaseActions, hold = hold, modifier = Modifier.weight(1f))
                }
                // Fewer than five: the empty slots keep the tiles the same size.
                repeat(5 - top.take(5).size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A top album: its cover and title; right click (or the release dialog) shows its details. */
@Composable
private fun StageAlbumTile(release: LibraryRelease, addon: Boolean, onFocus: () -> Unit, actions: ReleaseMenuActions, hold: HoldToSelectState, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(12.dp)
    val menu = rememberMusicMenu()
    MusicItemMenu(menu) { releaseMenu(release, actions) }
    val open = { actions.open(release) }
    Column(
        modifier.clip(shape)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .musicMenuTrigger(menu, hold, onSelect = open)
            .musicKeyFocusRing(shape)
            .clickable(onClick = open)
            .alpha(if (release.isPlayable || addon) 1f else 0.5f)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ReleaseCover(release, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
            // fork:nebula - an overlay on the cover, so the tile does not change when the addon's answer arrives.
            if (addon) LosslessPill()
        }
        Text(release.title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            release.year ?: release.releaseDate?.take(4) ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * Story: the artist's career as a short timeline worked out from release dates (see ArtistStory.kt), each point linked to the
 * record from then. Every row is the same height and every point has its record, so nothing moves with the content.
 */
@Composable
private fun StoryLens(
    artist: LibraryArtist,
    releaseActions: ReleaseMenuActions,
    hold: HoldToSelectState,
    addonKeys: Set<String>,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val points = remember(artist.releases, artist.topAlbumKeys, artist.beginYear, artist.type, artist.ended, addonKeys) { artistStoryPoints(artist, addonKeys) }
    val summary = remember(artist.releases) { artistStorySummary(artist) }
    val ruler = remember(points) { artistStoryRuler(artist, points) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CAREER", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.textMuted, modifier = Modifier.weight(1f))
            Text(summary.orEmpty(), style = MaterialTheme.typography.labelMedium, color = colors.textMuted, maxLines = 1)
        }
        if (points.size < 2) {
            Text(
                "Not enough dated releases in the library yet to tell this artist's story.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        points.forEachIndexed { index, point ->
            StoryRow(
                point = point,
                first = index == 0,
                last = index == points.lastIndex,
                addon = point.release.key in addonKeys,
                actions = releaseActions,
                hold = hold,
            )
        }
        Text(
            "Worked out from the library's release dates.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** One point of the story: the year, a dot on the rail, what it is, and the record from then (a tap opens it). */
@Composable
private fun StoryRow(point: StoryPoint, first: Boolean, last: Boolean, addon: Boolean, actions: ReleaseMenuActions, hold: HoldToSelectState) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(12.dp)
    val release = point.release
    val menu = rememberMusicMenu()
    MusicItemMenu(menu) { releaseMenu(release, actions) }
    val open = { actions.open(release) }
    val rail = Color.White.copy(alpha = 0.16f)
    val dot = colors.accent
    Row(
        Modifier.fillMaxWidth().height(STORY_ROW_HEIGHT).clip(shape)
            .musicMenuTrigger(menu, hold, onSelect = open)
            .musicKeyFocusRing(shape)
            .clickable(onClick = open)
            .alpha(if (release.isPlayable || addon) 1f else 0.5f)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(point.year.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.accent, modifier = Modifier.width(60.dp))
        Box(
            Modifier.width(24.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2f
                val mid = size.height / 2f
                val stroke = 2.dp.toPx()
                if (!first) drawLine(rail, Offset(x, 0f), Offset(x, mid), stroke)
                if (!last) drawLine(rail, Offset(x, mid), Offset(x, size.height), stroke)
                drawCircle(dot, radius = 5.dp.toPx(), center = Offset(x, mid))
            },
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(point.kind.label, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp, color = colors.textMuted, maxLines = 1)
            Text(point.line, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.width(STORY_RECORD_WIDTH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(56.dp)) {
                ReleaseCover(release, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                // An overlay on the cover, so the row does not change when the addon's answer arrives.
                if (addon) LosslessPill()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(release.title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(release.kind.label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

/**
 * The story's years to scale (Nick, Nov 10: evenly spaced rows made 1993 to 1996 look as long as 1996 to 2017). A line from
 * the first point's year to the last, a tick for every studio album, a dot for each point, the decades marked under it and
 * the longest break between albums drawn dashed over a band with its length. Fixed height, so it never moves the rows.
 */
@Composable
private fun StoryRulerBar(ruler: StoryRuler) {
    val colors = MaterialTheme.nuvio.colors
    val measurer = rememberTextMeasurer()
    val small = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted)
    val rail = Color.White.copy(alpha = 0.22f)
    val tick = Color.White.copy(alpha = 0.55f)
    val accent = colors.accent
    val span = (ruler.to - ruler.from).toFloat()
    val gapLabel = if (ruler.gapFrom != null && ruler.gapTo != null) "${ruler.gapTo - ruler.gapFrom}-year break" else null
    Row(
        Modifier.fillMaxWidth().height(STORY_RULER_HEIGHT).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ruler.from.toString(), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.width(44.dp))
        androidx.compose.foundation.Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val inset = 8.dp.toPx()
            val axisY = size.height * 0.55f
            fun x(year: Int): Float = inset + (year - ruler.from) / span * (size.width - 2 * inset)
            val stroke = 2.dp.toPx()
            val gapStart = ruler.gapFrom?.let(::x)
            val gapEnd = ruler.gapTo?.let(::x)
            if (gapStart != null && gapEnd != null) {
                drawRect(accent.copy(alpha = 0.10f), Offset(gapStart, axisY - 7.dp.toPx()), androidx.compose.ui.geometry.Size(gapEnd - gapStart, 14.dp.toPx()))
                drawLine(rail, Offset(inset, axisY), Offset(gapStart, axisY), stroke)
                drawLine(
                    rail, Offset(gapStart, axisY), Offset(gapEnd, axisY), stroke,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
                drawLine(rail, Offset(gapEnd, axisY), Offset(size.width - inset, axisY), stroke)
                if (gapLabel != null) {
                    val laid = measurer.measure(gapLabel, small)
                    if (laid.size.width < gapEnd - gapStart - 8.dp.toPx()) {
                        drawText(laid, topLeft = Offset((gapStart + gapEnd - laid.size.width) / 2f, axisY - 9.dp.toPx() - laid.size.height))
                    }
                }
            } else {
                drawLine(rail, Offset(inset, axisY), Offset(size.width - inset, axisY), stroke)
            }
            val decadeEdge = 26.dp.toPx()
            var decade = (ruler.from / 10 + 1) * 10
            while (decade < ruler.to) {
                val dx = x(decade)
                if (dx > decadeEdge && dx < size.width - decadeEdge) {
                    val laid = measurer.measure(decade.toString(), small)
                    drawLine(rail, Offset(dx, axisY + 3.dp.toPx()), Offset(dx, axisY + 7.dp.toPx()), 1.dp.toPx())
                    drawText(laid, topLeft = Offset(dx - laid.size.width / 2f, axisY + 9.dp.toPx()))
                }
                decade += 10
            }
            ruler.albumYears.forEach { year ->
                val ax = x(year)
                drawLine(tick, Offset(ax, axisY - 6.dp.toPx()), Offset(ax, axisY + 6.dp.toPx()), 1.5.dp.toPx())
            }
            ruler.pointYears.forEach { year -> drawCircle(accent, radius = 5.dp.toPx(), center = Offset(x(year), axisY)) }
        }
        Text(
            ruler.to.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

private val STORY_RULER_HEIGHT = 56.dp

/** A story row's height and the width of its record slot: fixed, so a long title or a missing cover moves nothing. */
private val STORY_ROW_HEIGHT = 84.dp
private val STORY_RECORD_WIDTH = 250.dp

/** Discography: the old page's filter and release grid, inside the centre panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscographyLens(
    /** The Singles tab: singles only, with just the Playable / All switch. Otherwise the Albums tab: everything else. */
    singlesOnly: Boolean,
    artist: LibraryArtist,
    discography: ArtistDiscography,
    working: Boolean,
    filter: ReleaseFilter,
    playableOnly: Boolean,
    demosOpen: Boolean,
    onOpen: (LibraryRelease) -> Unit,
    onInfo: (LibraryRelease) -> Unit,
    onPlayableOnly: (Boolean) -> Unit,
    onFilter: (ReleaseFilter) -> Unit,
    onToggleDemos: () -> Unit,
    addonKeys: Set<String>,
    releaseActions: ReleaseMenuActions,
    hold: HoldToSelectState,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val timeline = remember(artist.releases) { buildEraTimeline(artist.releases) }
    val dated = remember(timeline) {
        timeline.eras.flatMap { it.groups }
            .sortedWith(compareByDescending<ReleaseGroup> { releasePosition(it.primary) ?: 0.0 }.thenBy { it.primary.title })
    }
    // Each tab keeps to its own kinds; a kind pill picked on the other tab (Singles) reads as All here.
    val sections = if (singlesOnly) SINGLES_SECTIONS else ALBUM_SECTIONS
    val kindsHere = sections.flatMap { it.second }.toSet()
    val shownFilter = if (singlesOnly || filter == ReleaseFilter.Singles) ReleaseFilter.All else filter
    val shown = if (shownFilter == ReleaseFilter.Demos) emptyList() else dated.filter { it.kind in kindsHere && it.passes(playableOnly, shownFilter, addonKeys) }
    val undated = if (shownFilter == ReleaseFilter.Demos) emptyList() else timeline.undated.filter { it.kind in kindsHere && it.passes(playableOnly, shownFilter, addonKeys) }
    val scroll = rememberScrollState()
    Column(modifier.verticalScroll(scroll).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FilterPanel(
            discography,
            shownFilter,
            playableOnly,
            onPlayableOnly,
            onFilter,
            addonInPlay = addonKeys.isNotEmpty(),
            bare = true,
            kinds = if (singlesOnly) emptySet() else ALBUM_TAB_FILTERS,
        )
        val all = shown + undated
        if (all.isEmpty() && shownFilter != ReleaseFilter.Demos) {
            Text(
                when {
                    working -> "Looking up releases…"
                    timeline.isEmpty -> "No releases found."
                    playableOnly -> "Nothing here is on YouTube Music. Pick All to see it anyway."
                    else -> "Nothing matches the filter."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
        if (singlesOnly) {
            SinglesBody(artist, all, addonKeys, releaseActions, hold, onOpen, scroll)
            Spacer(Modifier.height(16.dp))
            return@Column
        }
        sections.forEach { (title, kinds) ->
            val section = all.filter { it.kind in kinds }
            if (section.isEmpty()) return@forEach
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                Text("${section.size}", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, modifier = Modifier.padding(bottom = 2.dp))
            }
            // As many columns of about 150 dp as fit, each widened so a row runs edge to edge.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 14.dp
                val columns = ((maxWidth + gap) / (150.dp + gap)).toInt().coerceAtLeast(1)
                val card = (maxWidth - gap * (columns - 1)) / columns
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    section.forEach { group ->
                        val menu = rememberMusicMenu()
                        MusicItemMenu(menu) { releaseMenu(group.primary, releaseActions) }
                        ReleaseCard(
                            group,
                            onClick = { onOpen(group.primary) },
                            addon = group.hasLossless(addonKeys),
                            width = card,
                            extra = Modifier.musicMenuTrigger(menu, hold, onSelect = { onOpen(group.primary) }),
                        )
                    }
                }
            }
        }
        if (!singlesOnly && discography.demos.isNotEmpty()) {
            DemosArchive(discography.demos, open = demosOpen, onToggle = onToggleDemos, onOpen = onOpen, onInfo = onInfo)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The Singles tab's body, with its three options (kept in the music settings): Hide album songs (a single whose song is
 * also on one of the artist's albums or EPs is left out; the albums' track lists are read once, when it is turned on),
 * By year (a heading per year, newest first, and a strip of years that jumps to one) and List (a compact row per single
 * instead of the cover grid).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SinglesBody(
    artist: LibraryArtist,
    singles: List<ReleaseGroup>,
    addonKeys: Set<String>,
    releaseActions: ReleaseMenuActions,
    hold: HoldToSelectState,
    onOpen: (LibraryRelease) -> Unit,
    scroll: androidx.compose.foundation.ScrollState,
) {
    val colors = MaterialTheme.nuvio.colors
    val settings = MusicLibraryRepository.state.collectAsState().value.settings
    val scope = rememberCoroutineScope()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionPill("Hide album songs", selected = settings.singlesHideAlbumSongs) {
            MusicLibraryRepository.updateSettings(settings.copy(singlesHideAlbumSongs = !settings.singlesHideAlbumSongs))
        }
        ActionPill("By year", selected = settings.singlesByYear) {
            MusicLibraryRepository.updateSettings(settings.copy(singlesByYear = !settings.singlesByYear))
        }
        ActionPill("List", selected = settings.singlesAsList) {
            MusicLibraryRepository.updateSettings(settings.copy(singlesAsList = !settings.singlesAsList))
        }
    }
    // The songs on the artist's albums and EPs, read only while Hide album songs is on (the catalog keeps the albums).
    val albumSongs by produceState<Set<String>?>(null, artist.id, artist.releases.size, settings.singlesHideAlbumSongs) {
        value = if (settings.singlesHideAlbumSongs) albumSongKeys(artist.releases) else emptySet()
    }
    val hiding = settings.singlesHideAlbumSongs
    val kept = if (!hiding) singles else singles.filterNot { songKey(it.primary.title) in albumSongs.orEmpty() }
    val hidden = singles.size - kept.size
    if (hiding) {
        Text(
            when {
                albumSongs == null -> "Checking the albums…"
                hidden > 0 -> "$hidden single${if (hidden == 1) " is" else "s are"} also on an album"
                else -> "None of these is on an album"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val groups: List<Pair<String, List<ReleaseGroup>>> = if (settings.singlesByYear) {
        kept.groupBy { it.primary.yearLabel() }.toList()
            .sortedByDescending { (year, _) -> year.toIntOrNull() ?: Int.MIN_VALUE }
    } else {
        listOf("Singles" to kept)
    }
    // Where each year's heading sits in the column, so a year in the strip can scroll to it.
    val headingAt = remember { mutableMapOf<String, Int>() }
    if (settings.singlesByYear && groups.size > 1) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            groups.forEach { (year, _) ->
                ActionPill(year) { headingAt[year]?.let { y -> scope.launch { scroll.animateScrollTo(y) } } }
            }
        }
    }
    groups.forEach { (title, section) ->
        if (section.isEmpty()) return@forEach
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp).onGloballyPositioned { headingAt[title] = it.positionInParent().y.toInt() },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            Text("${section.size}", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (settings.singlesAsList) {
            Column(Modifier.fillMaxWidth().widthIn(max = 760.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                section.forEach { group ->
                    val menu = rememberMusicMenu()
                    MusicItemMenu(menu) { releaseMenu(group.primary, releaseActions) }
                    SingleListRow(
                        group,
                        addon = group.hasLossless(addonKeys),
                        onClick = { onOpen(group.primary) },
                        extra = Modifier.musicMenuTrigger(menu, hold, onSelect = { onOpen(group.primary) }),
                    )
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 14.dp
                val columns = ((maxWidth + gap) / (150.dp + gap)).toInt().coerceAtLeast(1)
                val card = (maxWidth - gap * (columns - 1)) / columns
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    section.forEach { group ->
                        val menu = rememberMusicMenu()
                        MusicItemMenu(menu) { releaseMenu(group.primary, releaseActions) }
                        ReleaseCard(
                            group,
                            onClick = { onOpen(group.primary) },
                            addon = group.hasLossless(addonKeys),
                            width = card,
                            extra = Modifier.musicMenuTrigger(menu, hold, onSelect = { onOpen(group.primary) }),
                        )
                    }
                }
            }
        }
    }
}

/** A single as one compact row: a small cover, its title, and its year. */
@Composable
private fun SingleListRow(group: ReleaseGroup, addon: Boolean, onClick: () -> Unit, extra: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val release = group.primary
    val shape = RoundedCornerShape(12.dp)
    Row(
        extra.fillMaxWidth().height(68.dp).clip(shape)
            .musicKeyFocusRing(shape)
            .clickable(onClick = onClick)
            .alpha(if (group.isPlayable || addon) 1f else 0.5f)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(52.dp)) {
            ReleaseCover(release, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
            if (addon) LosslessPill()
        }
        Text(
            release.title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(release.yearLabel(), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, maxLines = 1)
    }
}

/** The year a release came out, as a heading; "Undated" when none is known. */
private fun LibraryRelease.yearLabel(): String =
    (releaseDate ?: year)?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: "Undated"

/** The songs on [releases]' albums and EPs, as song keys, read through the catalog (which keeps each album it opens). */
private suspend fun albumSongKeys(releases: List<LibraryRelease>): Set<String> {
    val catalog = defaultMusicCatalog()
    val ids = releases.filter { it.kind == ReleaseKind.Album || it.kind == ReleaseKind.EP }.mapNotNull { it.ytBrowseId }.distinct()
    val gate = kotlinx.coroutines.sync.Semaphore(4)
    return kotlinx.coroutines.coroutineScope {
        ids.map { id ->
            async {
                gate.withPermit { runCatching { catalog.album(id) }.getOrNull()?.tracks?.map { songKey(it.title) }.orEmpty() }
            }
        }.awaitAll().flatten().filter { it.isNotEmpty() }.toSet()
    }
}

/** The Albums tab's sections (EPs with the albums), and the Singles tab's one. */
private val ALBUM_SECTIONS = listOf(
    "Albums" to setOf(ReleaseKind.Album),
    "EPs" to setOf(ReleaseKind.EP),
    "Live & Compilations" to setOf(ReleaseKind.Live, ReleaseKind.Compilation),
    "Other" to setOf(ReleaseKind.Other),
)
private val SINGLES_SECTIONS = listOf("Singles" to setOf(ReleaseKind.Single))

/** The kind pills on the Albums tab (Singles has a tab of its own). */
private val ALBUM_TAB_FILTERS = setOf(ReleaseFilter.Albums, ReleaseFilter.EPs, ReleaseFilter.Other, ReleaseFilter.Demos)

// endregion

// region more menu

/** fork:music-menu - what a song's More menu can do. */
internal class SongMenuActions(
    val playFrom: (EssentialRow) -> Unit,
    val playOne: (EssentialRow) -> Unit,
    val queueOne: (EssentialRow) -> Unit,
    val playNextOne: (EssentialRow) -> Unit,
    val openAlbum: (String) -> Unit,
)

/** fork:music-menu - what a release's More menu can do; [playable] says whether it plays here at all. */
internal class ReleaseMenuActions(
    val play: (LibraryRelease, Boolean) -> Unit,
    val queue: (LibraryRelease) -> Unit,
    val playNext: (LibraryRelease) -> Unit,
    val open: (LibraryRelease) -> Unit,
    val info: (LibraryRelease) -> Unit,
    val playable: (LibraryRelease) -> Boolean,
)

private fun songMenu(row: EssentialRow, actions: SongMenuActions): List<PosterZoomOverlayAction> = listOf(
    PosterZoomOverlayAction(icon = Icons.Rounded.PlayArrow, label = "Play from here", onSelected = { actions.playFrom(row) }),
    PosterZoomOverlayAction(icon = Icons.Rounded.MusicNote, label = "Play just this song", onSelected = { actions.playOne(row) }),
    PosterZoomOverlayAction(icon = Icons.Rounded.SkipNext, label = "Play next", onSelected = { actions.playNextOne(row) }),
    PosterZoomOverlayAction(icon = Icons.Rounded.PlaylistAdd, label = "Add to queue", onSelected = { actions.queueOne(row) }),
    // fork:music-radio - YouTube Music's "Start radio" from this song.
    PosterZoomOverlayAction(icon = Icons.Rounded.Radio, label = "Start radio", onSelected = { com.nuvio.app.features.music.MusicRadioStarter.song(row.videoId, row.title) }),
    PosterZoomOverlayAction(
        icon = Icons.Rounded.Album,
        label = row.albumTitle?.let { "Open $it" } ?: "Open the album",
        onSelected = { row.albumBrowseId?.let(actions.openAlbum) },
        enabled = row.albumBrowseId != null,
        group = 1,
    ),
)

private fun releaseMenu(release: LibraryRelease, actions: ReleaseMenuActions): List<PosterZoomOverlayAction> {
    val plays = actions.playable(release)
    return listOf(
        PosterZoomOverlayAction(icon = Icons.Rounded.PlayArrow, label = "Play", onSelected = { actions.play(release, false) }, enabled = plays),
        PosterZoomOverlayAction(icon = Icons.Rounded.Shuffle, label = "Shuffle", onSelected = { actions.play(release, true) }, enabled = plays),
        PosterZoomOverlayAction(icon = Icons.Rounded.SkipNext, label = "Play next", onSelected = { actions.playNext(release) }, enabled = plays),
        PosterZoomOverlayAction(icon = Icons.Rounded.PlaylistAdd, label = "Add to queue", onSelected = { actions.queue(release) }, enabled = plays),
        PosterZoomOverlayAction(icon = Icons.Rounded.Album, label = "Open", onSelected = { actions.open(release) }, group = 1),
        PosterZoomOverlayAction(icon = Icons.Rounded.Info, label = "Details and versions", onSelected = { actions.info(release) }, group = 1),
    )
}

// endregion
