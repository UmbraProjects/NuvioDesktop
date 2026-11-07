package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's Shelf redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.core.ui.navigationKey
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.LensPanel
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.identity.ReleasePaletteResolver
import com.nuvio.app.features.music.nebula.rememberAddonReleases
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * fork:shelf - the Shelf, redesigned as a collection browser in three panels (the model is ShelfBrowserModel.kt):
 *
 *  - By artist: the artists A to Z on the left (All, Starred, Added recently; a letter rail, and a typed letter, jump), the
 *    artist in focus's albums in the middle as one page of eight covers (top five numbered, Lossless marked when the music
 *    addon has them), and the album in focus on the right with Play, Add to queue and Open.
 *  - All albums: the left panel sorts (album, artist, year, added) and narrows (starred, a decade) the wall, and the middle
 *    is a page of fifteen covers.
 *
 * Everything fits one screen: pages turn instead of scrolling (Down past the last row, Up past the first), and nothing moves
 * or resizes as focus moves or answers arrive. OK on a cover plays the album; Left from the first column goes back to the
 * artist that is open. Only the artist list scrolls, inside its panel.
 */

private val PANEL_GAP = 24.dp
private val SIDE_MIN = 320.dp
private val SIDE_MAX = 460.dp
private val ROW_HEIGHT = 68.dp

/** Keys A to Z, for typing a letter to jump the artist list. */
private val LETTER_KEYS: Map<Key, Char> = mapOf(
    Key.A to 'A', Key.B to 'B', Key.C to 'C', Key.D to 'D', Key.E to 'E', Key.F to 'F', Key.G to 'G', Key.H to 'H',
    Key.I to 'I', Key.J to 'J', Key.K to 'K', Key.L to 'L', Key.M to 'M', Key.N to 'N', Key.O to 'O', Key.P to 'P',
    Key.Q to 'Q', Key.R to 'R', Key.S to 'S', Key.T to 'T', Key.U to 'U', Key.V to 'V', Key.W to 'W', Key.X to 'X',
    Key.Y to 'Y', Key.Z to 'Z',
)

private val RAIL_LETTERS: List<Char> = ('A'..'Z').toList() + '#'

@Composable
internal fun ShelfBrowser(
    artists: List<LibraryArtist>,
    /** Artists still being added: their status lines, shown above the list. */
    pending: List<String>,
    filterQuery: String,
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(ShelfMode.ByArtist) }
    var artistFilter by rememberSaveable { mutableStateOf(ShelfArtistFilter.All) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(ShelfAlbumSort.Year) }
    var show by remember { mutableStateOf<ShelfAlbumShow>(ShelfAlbumShow.All) }
    var focusedKey by remember { mutableStateOf<String?>(null) }

    val now = remember { LibraryClock.nowEpochMs() }
    val list = remember(artists, artistFilter, filterQuery) { shelfArtists(artists, artistFilter, filterQuery, now) }
    val selected = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    val allAlbums = remember(artists, filterQuery, mode) { if (mode == ShelfMode.AllAlbums) shelfAllAlbums(artists, filterQuery) else emptyList() }
    val showChoices = remember(allAlbums) { shelfShowChoices(allAlbums) }
    // A decade the collection no longer has (an artist removed) falls back to everything.
    val shown = if (show in showChoices) show else ShelfAlbumShow.All
    val wall = remember(allAlbums, sort, shown) { shelfWall(allAlbums, sort, shown) }

    // The album the right panel shows: the one focus (or the pointer) last rested on, while it is still in view.
    val artistAlbums = remember(selected) { selected?.let(::shelfAlbumsOf).orEmpty() }
    val inView = if (mode == ShelfMode.ByArtist) artistAlbums else wall
    val focusedAlbum = inView.firstOrNull { it.key == focusedKey } ?: inView.firstOrNull()

    // The artist rows' focus, so Left from the covers lands on the artist that is open (not the nearest row).
    val artistFocus = remember { HashMap<String, FocusRequester>() }
    fun artistRequester(id: String) = artistFocus.getOrPut(id) { FocusRequester() }

    BoxWithConstraints(modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 24.dp)) {
        val side = (maxWidth * 0.24f).coerceIn(SIDE_MIN, SIDE_MAX)
        val showRight = maxWidth >= 1100.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
            ShelfPanel(Modifier.width(side)) {
                ModeSwitch(mode) { mode = it; focusedKey = null }
                Spacer(Modifier.height(18.dp))
                if (mode == ShelfMode.ByArtist) {
                    ArtistList(
                        list = list,
                        pending = pending,
                        filter = artistFilter,
                        onFilter = { artistFilter = it },
                        selectedId = selected?.id,
                        requester = ::artistRequester,
                        onSelect = { id -> if (id != selectedId) { selectedId = id; focusedKey = null } },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    WallOptions(
                        sort = sort,
                        onSort = { sort = it },
                        show = shown,
                        choices = showChoices,
                        onShow = { show = it },
                        count = wall.size,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (mode == ShelfMode.ByArtist) {
                    if (selected == null) {
                        EmptyPanel("No artists match.", Modifier.fillMaxSize())
                    } else {
                        ArtistAlbumsPanel(
                            artist = selected,
                            albums = artistAlbums,
                            focusedKey = focusedAlbum?.key,
                            onFocusAlbum = { focusedKey = it.key },
                            onBackToArtist = { runCatching { artistRequester(selected.id).requestFocus() } },
                            onArtistClick = onArtistClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    WallPanel(
                        title = shelfWallTitle(shown),
                        albums = wall,
                        focusedKey = focusedAlbum?.key,
                        onFocusAlbum = { focusedKey = it.key },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (showRight) {
                AlbumPanel(
                    album = focusedAlbum,
                    byArtist = mode == ShelfMode.ByArtist,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    modifier = Modifier.width(side),
                )
            }
        }
    }
}

// region panels

@Composable
private fun ShelfPanel(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    LensPanel(modifier, content)
}

@Composable
private fun EmptyPanel(text: String, modifier: Modifier) {
    LensPanel(modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 20.sp, color = MaterialTheme.nuvio.colors.textMuted)
        }
    }
}

/** By artist / All albums, as one two-part switch. */
@Composable
private fun ModeSwitch(mode: ShelfMode, onMode: (ShelfMode) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(shape).background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SwitchHalf("By artist", mode == ShelfMode.ByArtist, Modifier.weight(1f)) { onMode(ShelfMode.ByArtist) }
        SwitchHalf("All albums", mode == ShelfMode.AllAlbums, Modifier.weight(1f)) { onMode(ShelfMode.AllAlbums) }
    }
}

@Composable
private fun SwitchHalf(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.fillMaxHeight().clip(shape).background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .musicKeyFocusRing(shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 19.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) colors.textPrimary else colors.textSecondary, maxLines = 1)
    }
}

/** A choice chip: edged in the accent when chosen. */
@Composable
private fun ShelfChip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.height(44.dp).clip(shape).border(1.dp, if (on) colors.accent else Color.White.copy(alpha = 0.18f), shape)
            .musicKeyFocusRing(shape).clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = if (on) colors.textPrimary else colors.textSecondary, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistList(
    list: List<LibraryArtist>,
    pending: List<String>,
    filter: ShelfArtistFilter,
    onFilter: (ShelfArtistFilter) -> Unit,
    selectedId: String?,
    requester: (String) -> FocusRequester,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val letters = remember(list, filter) { if (filter == ShelfArtistFilter.AddedRecently) emptyMap() else shelfLetterIndex(list) }
    fun jump(letter: Char) {
        val index = shelfJumpTo(letters, letter) ?: return
        val artist = list[index]
        onSelect(artist.id)
        scope.launch {
            listState.scrollToItem(index)
            withFrameNanos { }
            runCatching { requester(artist.id).requestFocus() }
        }
    }
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShelfArtistFilter.entries.forEach { f -> ShelfChip(f.label, f == filter) { onFilter(f) } }
        }
        Spacer(Modifier.height(16.dp))
        pending.take(2).forEach { status ->
            Text("Adding… $status", fontSize = 16.sp, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight().onKeyEvent { event ->
                    // A typed letter jumps to the first artist filed under it.
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val letter = LETTER_KEYS[event.key] ?: return@onKeyEvent false
                    if (letters.isEmpty()) return@onKeyEvent false
                    jump(letter)
                    true
                },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (list.isEmpty()) {
                    item(key = "none") {
                        Text(
                            if (filter == ShelfArtistFilter.Starred) "Star an artist on their page to see them here." else "No artists match.",
                            fontSize = 17.sp,
                            color = colors.textMuted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(list, key = { it.id }) { artist ->
                    ArtistRow(artist, artist.id == selectedId, requester(artist.id)) { onSelect(artist.id) }
                }
            }
            if (letters.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Column(Modifier.width(30.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                    val current = list.firstOrNull { it.id == selectedId }?.shelfLetter()
                    RAIL_LETTERS.forEach { letter ->
                        val has = letter in letters
                        val on = letter == current
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(if (on) colors.accent else Color.Transparent)
                                .musicKeyFocusRing(CircleShape).clickable(enabled = has) { jump(letter) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                letter.toString(),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    on -> colors.onAccent
                                    has -> colors.textSecondary
                                    else -> colors.textMuted.copy(alpha = 0.35f)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One artist: picture, name and how many releases; resting on it (focus or pointer) opens its albums in the middle. */
@Composable
private fun ArtistRow(artist: LibraryArtist, selected: Boolean, requester: FocusRequester, onSelect: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsKeyboardFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(16.dp)
    val count = remember(artist.releases) { countReleaseCards(artist.releases) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .focusRequester(requester)
            .onFocusChanged { if (it.hasFocus) onSelect() }
            .clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.12f) else if (hovered) MusicGlassRaised else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) colors.borderFocus else Color.Transparent, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onSelect)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MusicCover(artist.art.thumb, artist.name, size = 48.dp, circle = true)
        Column(Modifier.weight(1f)) {
            Text(artist.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count release${if (count == 1) "" else "s"}", fontSize = 15.sp, color = colors.textMuted, maxLines = 1)
        }
        if (artist.favourite) Icon(Icons.Rounded.Star, contentDescription = "Starred", tint = GOLD, modifier = Modifier.size(22.dp))
    }
}

/** The album wall's sort and Show choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WallOptions(
    sort: ShelfAlbumSort,
    onSort: (ShelfAlbumSort) -> Unit,
    show: ShelfAlbumShow,
    choices: List<ShelfAlbumShow>,
    onShow: (ShelfAlbumShow) -> Unit,
    count: Int,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(modifier) {
        SectionLabel("SORT")
        Spacer(Modifier.height(10.dp))
        ShelfAlbumSort.entries.forEach { s ->
            val on = s == sort
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(shape)
                    .background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                    .border(if (on) 2.dp else 0.dp, if (on) colors.accent else Color.Transparent, shape)
                    .musicKeyFocusRing(shape).clickable { onSort(s) }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.label, fontSize = 19.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (s.hint.isNotEmpty()) Text(s.hint, fontSize = 15.sp, color = colors.textMuted)
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(18.dp))
        SectionLabel("SHOW")
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            choices.forEach { c -> ShelfChip(c.label, c == show) { onShow(c) } }
        }
        Spacer(Modifier.weight(1f))
        Text("Showing $count album${if (count == 1) "" else "s"}, ${sort.label.lowercase()}${if (sort.hint.isNotEmpty()) ", ${sort.hint}" else ""}.", fontSize = 16.sp, color = colors.textMuted)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, color = MaterialTheme.nuvio.colors.accent)
}

/** The middle panel in By artist: the artist, three buttons, and a page of eight of their albums. */
@Composable
private fun ArtistAlbumsPanel(
    artist: LibraryArtist,
    albums: List<ShelfAlbum>,
    focusedKey: String?,
    onFocusAlbum: (ShelfAlbum) -> Unit,
    onBackToArtist: () -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    val row = remember(artist) { buildShelfRow(artist) }
    val addon = rememberAddonReleases(artist)
    val lossless = albums.count { it.release.key in addon.keys }
    // Artists added before Top 5 existed get their ranking filled in once, quietly; the covers on the page get a palette.
    LaunchedEffect(artist.id) { MusicLibraryRepository.ensureTopAlbums(artist.id) }
    val keys = remember(albums) { albums.map { it.release.key }.toSet() }
    LaunchedEffect(artist.id, keys) { ReleasePaletteResolver.ensure(context, artist, keys = keys, limit = 40) }

    var notice by remember(artist.id) { mutableStateOf<String?>(null) }
    var busy by remember(artist.id) { mutableStateOf(false) }
    fun launchPlay(opening: String, failed: String, play: suspend () -> Boolean) {
        if (busy) return
        busy = true
        notice = opening
        scope.launch {
            val ok = try { play() } catch (e: CancellationException) { throw e } catch (_: Throwable) { false }
            busy = false
            notice = if (ok) null else failed
            if (!ok) { delay(4_000); if (notice == failed) notice = null }
        }
    }

    LensPanel(modifier) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabel("ARTIST")
                Text(artist.name, fontSize = 44.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(
                    notice ?: shelfArtistLine(row.totalReleases, row.playableReleases, lossless),
                    fontSize = 18.sp,
                    color = if (notice != null) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (albums.isNotEmpty()) {
                ShelfButton("Play all", Icons.Rounded.PlayArrow, primary = true) {
                    launchPlay("Opening their albums…", "Couldn't open their albums.") { playTopAlbums(catalog, albums.take(12).map { it.release }, shuffled = false) }
                }
                ShelfButton("Shuffle", Icons.Rounded.Shuffle) {
                    launchPlay("Mixing their tracks…", "Couldn't open their tracks.") { playArtistMix(catalog, artist, albums.map { it.release }) }
                }
            }
            ShelfButton("Artist page", Icons.Rounded.Person) { onArtistClick(artist.id) }
        }
        Spacer(Modifier.height(24.dp))
        if (albums.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Nothing by ${artist.name} plays here yet. Their page has every release.", fontSize = 19.sp, color = colors.textMuted)
            }
        } else {
            CoverPages(
                albums = albums,
                columns = SHELF_ARTIST_COLUMNS,
                perPage = SHELF_ARTIST_PAGE,
                resetKey = artist.id,
                focusedKey = focusedKey,
                showArtist = false,
                lossless = addon.keys,
                onFocusAlbum = onFocusAlbum,
                onLeftEdge = onBackToArtist,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/** The middle panel in All albums: the wall's title, its count and a page of fifteen covers. */
@Composable
private fun WallPanel(title: String, albums: List<ShelfAlbum>, focusedKey: String?, onFocusAlbum: (ShelfAlbum) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    LensPanel(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 40.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${albums.size} album${if (albums.size == 1) "" else "s"}", fontSize = 19.sp, color = colors.textSecondary)
        }
        Spacer(Modifier.height(20.dp))
        if (albums.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No albums here.", fontSize = 19.sp, color = colors.textMuted)
            }
        } else {
            CoverPages(
                albums = albums,
                columns = SHELF_WALL_COLUMNS,
                perPage = SHELF_WALL_PAGE,
                resetKey = title,
                focusedKey = focusedKey,
                showArtist = true,
                lossless = emptySet(),
                onFocusAlbum = onFocusAlbum,
                onLeftEdge = null,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/**
 * A fixed grid of [perPage] covers, [columns] across, turned a page at a time: Down past the last row and Up past the first
 * turn the page (Up on the first page goes on to the buttons above). Left from the first column calls [onLeftEdge] when given.
 * OK or a click plays the album. The grid keeps its size whatever is on the page.
 */
@Composable
private fun CoverPages(
    albums: List<ShelfAlbum>,
    columns: Int,
    perPage: Int,
    resetKey: Any,
    focusedKey: String?,
    showArtist: Boolean,
    lossless: Set<String>,
    onFocusAlbum: (ShelfAlbum) -> Unit,
    onLeftEdge: (() -> Unit)?,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    val rows = perPage / columns
    val pages = shelfPageCount(albums.size, perPage)
    var turnedTo by remember(resetKey, albums.size) { mutableIntStateOf(0) }
    val page = turnedTo.coerceAtMost(pages - 1)
    val slots = remember(resetKey, perPage) { List(perPage) { FocusRequester() } }
    var pendingSlot by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(page, pendingSlot) {
        val slot = pendingSlot ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { slots[slot].requestFocus() }
        pendingSlot = null
    }
    val onPage = albums.shelfPage(page, perPage)

    fun turn(to: Int, slot: Int) {
        val count = albums.shelfPage(to, perPage).size
        turnedTo = to
        pendingSlot = slot.coerceAtMost(count - 1).coerceAtLeast(0)
    }

    fun play(album: ShelfAlbum) {
        val id = album.release.ytBrowseId ?: return
        scope.launch {
            val opened = runCatching { catalog.album(id) }.getOrNull() ?: return@launch
            MusicPlayback.player.play(opened, trackIndex = 0, shuffled = false)
        }
    }

    Column(modifier) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (r in 0 until rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    for (c in 0 until columns) {
                        val slot = r * columns + c
                        val album = onPage.getOrNull(slot)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (album != null) {
                                CoverCell(
                                    album = album,
                                    focused = album.key == focusedKey,
                                    showArtist = showArtist,
                                    lossless = album.release.key in lossless,
                                    requester = slots[slot],
                                    onFocus = { onFocusAlbum(album) },
                                    onPlay = { play(album) },
                                    onKey = { key ->
                                        when {
                                            key == Key.DirectionDown && (r == rows - 1 || slot + columns >= onPage.size) && page < pages - 1 -> { turn(page + 1, c); true }
                                            key == Key.DirectionDown && slot + columns >= onPage.size -> true
                                            key == Key.DirectionUp && r == 0 && page > 0 -> { turn(page - 1, (rows - 1) * columns + c); true }
                                            key == Key.DirectionLeft && c == 0 && onLeftEdge != null -> { onLeftEdge(); true }
                                            key == Key.DirectionRight && slot == onPage.size - 1 && c < columns - 1 -> true
                                            else -> false
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (pages > 1) {
                for (p in 0 until minOf(pages, 12)) {
                    Box(Modifier.padding(horizontal = 4.dp).height(8.dp).width(if (p == page) 28.dp else 8.dp).clip(CircleShape).background(if (p == page) colors.accent else Color.White.copy(alpha = 0.3f)))
                }
                Text("Page ${page + 1} of $pages", fontSize = 15.sp, color = colors.textMuted, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

/** One cover in the grid, square in whatever room the cell has, with its title and year (and artist on the wall) under it. */
@Composable
private fun CoverCell(
    album: ShelfAlbum,
    focused: Boolean,
    showArtist: Boolean,
    lossless: Boolean,
    requester: FocusRequester,
    onFocus: () -> Unit,
    onPlay: () -> Unit,
    onKey: (Key) -> Boolean,
) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val keyFocused by source.collectIsKeyboardFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    // The pointer resting on a cover shows it on the right, as focus does.
    LaunchedEffect(hovered) { if (hovered) onFocus() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val textRoom = if (showArtist) 46.dp else 48.dp
        val coverSize: Dp = minOf(maxWidth, maxHeight - textRoom).coerceAtLeast(40.dp)
        Column(Modifier.width(coverSize)) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .size(coverSize)
                    .focusRequester(requester)
                    .onFocusChanged { if (it.isFocused) onFocus() }
                    .onKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event.navigationKey()) }
                    .border(if (keyFocused || (focused && hovered)) 3.dp else 0.dp, if (keyFocused) colors.borderFocus else colors.accent, shape)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null, onClick = onPlay),
            ) {
                ReleaseCover(album.release, Modifier.fillMaxSize().padding(if (keyFocused) 3.dp else 0.dp))
                if (album.rank != null) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(8.dp).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${album.rank}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
                // An overlay, so the cover keeps its size when the addon's answer arrives.
                if (lossless) com.nuvio.app.features.music.nebula.LosslessPill(Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Text(album.release.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            val sub = listOfNotNull(album.artist.name.takeIf { showArtist }, album.release.year ?: album.release.releaseDate?.take(4)).joinToString(" · ")
            Text(sub, fontSize = 14.sp, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The right panel: the album in focus, big, with what is known about it and Play, Add to queue and Open. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumPanel(
    album: ShelfAlbum?,
    byArtist: Boolean,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    var notice by remember(album?.key) { mutableStateOf<String?>(null) }
    LensPanel(modifier) {
        SectionLabel("ALBUM")
        Spacer(Modifier.height(14.dp))
        if (album == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Album, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(64.dp))
            }
            return@LensPanel
        }
        val release = album.release
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val cover = minOf(maxWidth, maxHeight * 0.62f)
            Column {
                ReleaseCover(release, Modifier.size(cover))
                Text(release.title, fontSize = 32.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                Text(
                    shelfAlbumLine(release, artistName = album.artist.name.takeIf { !byArtist }),
                    fontSize = 18.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    album.rank?.let { InfoPill("Top 5 · #$it") }
                    if (album.pinned) InfoPill("Starred")
                    release.label?.takeIf { it.isNotBlank() }?.let { InfoPill(it) }
                }
            }
        }
        notice?.let { Text(it, fontSize = 16.sp, color = colors.accent, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp)) }
        val id = release.ytBrowseId
        ShelfButton("Play album", Icons.Rounded.PlayArrow, primary = true, enabled = id != null, modifier = Modifier.fillMaxWidth()) {
            if (id == null) return@ShelfButton
            notice = "Opening…"
            scope.launch {
                val opened = runCatching { catalog.album(id) }.getOrNull()
                if (opened == null) { notice = "Couldn't open this album."; return@launch }
                MusicPlayback.player.play(opened, trackIndex = 0, shuffled = false)
                notice = null
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShelfButton("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic, enabled = id != null, modifier = Modifier.weight(1f)) {
                if (id == null) return@ShelfButton
                scope.launch {
                    val opened = runCatching { catalog.album(id) }.getOrNull()
                    notice = when {
                        opened == null -> "Couldn't open this album."
                        MusicPlayback.player.enqueue(opened) -> "Added to the queue."
                        else -> null
                    }
                }
            }
            if (byArtist) {
                ShelfButton("Open album", Icons.AutoMirrored.Rounded.OpenInNew, enabled = id != null, modifier = Modifier.weight(1f)) { id?.let(onAlbumClick) }
            } else {
                ShelfButton("Go to artist", Icons.Rounded.LibraryMusic, modifier = Modifier.weight(1f)) { onArtistClick(album.artist.id) }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Text(
        text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.nuvio.colors.textPrimary,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** A labelled pill button, as on the new artist page: [primary] is filled in the accent. */
@Composable
private fun ShelfButton(
    label: String,
    icon: ImageVector,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(28.dp)
    val fill = if (primary) colors.accent else Color.Black.copy(alpha = 0.35f)
    val edge = if (primary) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f)
    val tint = if (!enabled) colors.textDisabled else if (primary) colors.onAccent else colors.textPrimary
    Row(
        modifier.height(54.dp).clip(shape).background(fill).border(1.dp, edge, shape)
            .musicKeyFocusRing(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 18.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium, color = tint, maxLines = 1)
    }
}

// endregion
