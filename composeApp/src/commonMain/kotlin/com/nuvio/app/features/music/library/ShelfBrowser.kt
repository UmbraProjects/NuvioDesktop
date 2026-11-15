package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's Shelf redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.ui.focus.focusProperties
import com.nuvio.app.features.music.MusicTabState
import com.nuvio.app.core.ui.requestFocusLogged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.composed
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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
import com.nuvio.app.features.music.playlist.MusicPlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * fork:shelf - the Shelf: your collection in three panels (the model is ShelfBrowserModel.kt, the data ShelfSources.kt).
 *
 *  - Left: the shelves. What only your library knows comes first: New from your artists, Recently played, Most played,
 *    Gathering dust (from the play log, MusicPlayLog.kt), Lossless (what the music addon is known to have), Starred, Your
 *    playlists (made here, and your YouTube Music ones) and Mixed for you (YouTube Music's mixes for your account). Then
 *    Artists A to Z (the artist list, its letter rail and typed letters) and All albums (sort and narrow a wall).
 *  - Middle: the shelf's page of covers, or (Artists A to Z) a scrolling grid of artists that opens the artist page. OK on an album opens its page; on a playlist or mix it plays.
 *  - Right: what focus rests on, with Play, Shuffle or Add to queue, and Open, Go to artist or Save to your playlists.
 *
 * Everything fits one screen: pages turn instead of scrolling (Down past the last row, Up past the first), and nothing moves
 * or resizes as focus moves or answers arrive. Only the artist grid and the genre list scroll, inside their panels.
 */

private val PANEL_GAP = 24.dp
private val SIDE_MIN = 320.dp
private val SIDE_MAX = 460.dp

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
    var section by rememberSaveable { mutableStateOf<ShelfSection?>(null) }
    var artistFilter by rememberSaveable { mutableStateOf(ShelfArtistFilter.All) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(ShelfAlbumSort.Year) }
    var show by remember { mutableStateOf<ShelfAlbumShow>(ShelfAlbumShow.All) }
    var focusedKey by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    val actions = remember(scope) { ShelfActions(catalog, scope) }
    val now = remember { LibraryClock.nowEpochMs() }
    val daySeed = remember(now) { (now / (24L * 60 * 60 * 1000)).toInt() }

    // What the smart shelves read: the play log, the account's YouTube Music shelves, playlists made here, and what the music
    // addon is known to have (its kept answers only; nothing is asked from here).
    val plays by MusicPlayLog.entries.collectAsState()
    val account by MusicAccountShelves.state.collectAsState()
    val localPlaylists by MusicPlaylistRepository.playlists.collectAsState()
    LaunchedEffect(Unit) { MusicAccountShelves.refresh(catalog) }
    var losslessKeys by remember { mutableStateOf(emptySet<String>()) }
    val everyAlbum = remember(artists, filterQuery) { shelfAllAlbums(artists, filterQuery) }
    LaunchedEffect(everyAlbum) {
        losslessKeys = everyAlbum.mapNotNull { a ->
            val key = com.nuvio.app.features.music.nebula.addonCacheKey(a.artist.id, a.release.key)
            key.takeIf { runCatching { com.nuvio.app.features.music.nebula.NebulaMusicSource.cachedAvailability(it)?.has }.getOrNull() == true }
        }.toSet()
    }

    val lastPlayed = remember(plays) { lastPlayedByAlbum(plays) }
    val playCounts = remember(plays) { playsByAlbum(plays) }
    val libraryById = remember(everyAlbum) { everyAlbum.filter { it.release.ytBrowseId != null }.associateBy { it.release.ytBrowseId!! } }
    val shelves: Map<ShelfSection, List<ShelfItem>> = remember(everyAlbum, plays, account, localPlaylists, losslessKeys, daySeed) {
        fun albums(list: List<ShelfAlbum>) = list.map { ShelfItem.Album(it) }
        mapOf(
            ShelfSection.New to albums(shelfNew(everyAlbum, now)),
            ShelfSection.Recent to recentPlays(plays).map { p ->
                when (p) {
                    is RecentPlay.Album -> libraryById[p.browseId]?.let { ShelfItem.Album(it) } ?: ShelfItem.PlayedAlbum(p)
                    is RecentPlay.Queue -> ShelfItem.Played(p)
                }
            },
            ShelfSection.MostPlayed to albums(shelfMostPlayed(everyAlbum, playCounts)),
            ShelfSection.Dust to albums(shelfDust(everyAlbum, lastPlayed, now, daySeed)),
            ShelfSection.Unplayed to albums(shelfUnplayed(everyAlbum, playCounts, daySeed)),
            ShelfSection.Lossless to albums(shelfLossless(everyAlbum, losslessKeys)),
            ShelfSection.Starred to albums(shelfStarred(everyAlbum)),
            ShelfSection.Liked to shelfLiked(account.playlists).map { ShelfItem.Playlist(it) } + account.liked.map { ShelfItem.Song(it) },
            ShelfSection.Playlists to localPlaylists.map { ShelfItem.Local(it) } + account.playlists.map { ShelfItem.Playlist(it) },
            ShelfSection.Mixes to shelfMixes(account.home).map { ShelfItem.Playlist(it) },
            ShelfSection.ListenAgain to shelfListenAgain(account.home).map { ShelfItem.Playlist(it) },
        )
    }
    // The shelf that opens first: what is new, else what was played, else the artists.
    val current = section ?: when {
        shelves[ShelfSection.New].orEmpty().isNotEmpty() -> ShelfSection.New
        shelves[ShelfSection.Recent].orEmpty().isNotEmpty() -> ShelfSection.Recent
        else -> ShelfSection.Artists
    }

    val filtered = remember(artists, artistFilter, filterQuery) { shelfArtists(artists, artistFilter, filterQuery, now) }
    val genreChoices = remember(artists) { shelfGenres(artists) }
    // A genre the artists no longer have (one removed) falls back to every genre.
    val activeGenre = genre?.takeIf { g -> genreChoices.any { it.key == g } }
    val list = remember(filtered, activeGenre) { shelfByGenre(filtered, activeGenre) }
    val selected = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    val showChoices = remember(everyAlbum) { shelfShowChoices(everyAlbum) }
    // A decade the collection no longer has (an artist removed) falls back to everything.
    val shown = if (show in showChoices) show else ShelfAlbumShow.All
    val wall = remember(everyAlbum, sort, shown, current) { if (current == ShelfSection.AllAlbums) shelfWall(everyAlbum, sort, shown) else emptyList() }

    // What the right panel shows: the item focus (or the pointer) last rested on, while it is still in view.
    val inView: List<ShelfItem> = when (current) {
        ShelfSection.Artists -> emptyList()
        ShelfSection.AllAlbums -> wall.map { ShelfItem.Album(it) }
        else -> shelves[current].orEmpty()
    }
    val focusedItem = inView.firstOrNull { it.key == focusedKey } ?: inView.firstOrNull()

    // The artist rows' focus, so Left from the covers lands on the artist that is open (not the nearest row).
    val artistFocus = remember { HashMap<String, FocusRequester>() }
    fun artistRequester(id: String) = artistFocus.getOrPut(id) { FocusRequester() }
    fun choose(next: ShelfSection) {
        if (next != current) { section = next; focusedKey = null }
    }

    BoxWithConstraints(modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 24.dp)) {
        val side = (maxWidth * 0.24f).coerceIn(SIDE_MIN, SIDE_MAX)
        val showRight = maxWidth >= 1100.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
            ShelfPanel(Modifier.width(side)) {
                when (current) {
                    ShelfSection.Artists -> {
                        BackToShelves(entry = MusicTabState.cratesEntry) { section = ShelfSection.New.takeIf { shelves[it].orEmpty().isNotEmpty() } ?: ShelfSection.Recent }
                        Spacer(Modifier.height(14.dp))
                        ArtistFilters(
                            pending = pending,
                            filter = artistFilter,
                            onFilter = { artistFilter = it },
                            genres = genreChoices,
                            genre = activeGenre,
                            totalArtists = filtered.size,
                            onGenre = { genre = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ShelfSection.AllAlbums -> {
                        BackToShelves(entry = MusicTabState.cratesEntry) { section = ShelfSection.New.takeIf { shelves[it].orEmpty().isNotEmpty() } ?: ShelfSection.Recent }
                        Spacer(Modifier.height(14.dp))
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
                    else -> ShelfList(
                        entry = MusicTabState.cratesEntry,
                        current = current,
                        counts = shelves.mapValues { it.value.size },
                        signedIn = account.signedIn,
                        onChoose = ::choose,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (current) {
                    ShelfSection.Artists -> ArtistGridPanel(
                        title = genreChoices.firstOrNull { it.key == activeGenre }?.label ?: "Artists A to Z",
                        list = list,
                        filter = artistFilter,
                        focusedId = selected?.id,
                        requester = ::artistRequester,
                        onFocusArtist = { id -> if (id != selectedId) selectedId = id },
                        onOpenArtist = onArtistClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ShelfSection.AllAlbums -> WallPanel(
                        title = shelfWallTitle(shown),
                        albums = wall,
                        focusedKey = (focusedItem as? ShelfItem.Album)?.album?.key,
                        onFocusAlbum = { focusedKey = ShelfItem.Album(it).key },
                        onAlbumClick = onAlbumClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> ItemsPanel(
                        section = current,
                        items = shelves[current].orEmpty(),
                        empty = emptyShelfText(current, account),
                        focusedKey = focusedItem?.key,
                        onFocusItem = { focusedKey = it.key },
                        onOpen = { item ->
                            when (item) {
                                is ShelfItem.Album -> item.album.release.ytBrowseId?.let(onAlbumClick)
                                is ShelfItem.PlayedAlbum -> onAlbumClick(item.play.browseId)
                                else -> actions.play(item)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (showRight && current == ShelfSection.Artists) {
                ArtistSummaryPanel(selected, onArtistClick, Modifier.width(side))
            } else if (showRight) {
                when (val item = focusedItem) {
                    is ShelfItem.Album -> AlbumPanel(
                        album = item.album,
                        byArtist = current == ShelfSection.Artists,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        modifier = Modifier.width(side),
                    )
                    null -> AlbumPanel(album = null, byArtist = true, onAlbumClick = onAlbumClick, onArtistClick = onArtistClick, modifier = Modifier.width(side))
                    else -> ItemPanel(item, actions, onAlbumClick, onArtistClick, Modifier.width(side))
                }
            }
        }
    }
}

/** What an empty shelf says. */
private fun emptyShelfText(section: ShelfSection, account: MusicAccountShelves.State): String = when (section) {
    ShelfSection.New -> "Nothing new from your artists in the last six months."
    ShelfSection.Recent -> "What you play shows here."
    ShelfSection.MostPlayed -> "The albums you play most show here once you have played them a few times."
    ShelfSection.Dust -> "Nothing gathering dust yet."
    ShelfSection.Unplayed -> "Every album in your library has been played here."
    ShelfSection.Lossless -> "No albums known on your music addon yet. Open an artist to have their albums checked."
    ShelfSection.Starred -> "Star an album or an artist to see them here."
    ShelfSection.Liked -> when {
        !account.signedIn -> "Sign in under Settings > YouTube account to play your Liked Music."
        account.loading -> "Asking YouTube Music…"
        else -> "YouTube Music did not list Liked Music among your playlists."
    }
    ShelfSection.ListenAgain -> when {
        !account.signedIn -> "Sign in under Settings > YouTube account to see what YouTube Music suggests you listen to again."
        account.loading -> "Asking YouTube Music…"
        else -> "YouTube Music has no Listen again playlists for you right now."
    }
    ShelfSection.Playlists -> if (account.signedIn) "No playlists yet." else "No playlists yet. Sign in under Settings > YouTube account to see your YouTube Music playlists too."
    ShelfSection.Mixes -> when {
        !account.signedIn -> "Sign in under Settings > YouTube account to see the mixes YouTube Music makes for you."
        account.loading -> "Asking YouTube Music…"
        account.failed -> "YouTube Music didn't answer. Try again later."
        else -> "YouTube Music has no mixes for you yet."
    }
    ShelfSection.Artists, ShelfSection.AllAlbums -> ""
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

/** Left panel of Artists A to Z: the All / Starred / Added recently chips, then the genres to narrow the grid by. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistFilters(
    pending: List<String>,
    filter: ShelfArtistFilter,
    onFilter: (ShelfArtistFilter) -> Unit,
    genres: List<ShelfGenre>,
    genre: String?,
    totalArtists: Int,
    onGenre: (String?) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShelfArtistFilter.entries.forEach { f -> ShelfChip(f.label, f == filter) { onFilter(f) } }
        }
        Spacer(Modifier.height(16.dp))
        pending.take(2).forEach { status ->
            Text("Adding… $status", fontSize = 16.sp, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
        }
        SectionLabel("GENRE")
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "all") { GenreRow("All genres", totalArtists, genre == null) { onGenre(null) } }
            items(genres, key = { it.key }) { g -> GenreRow(g.label, g.count, g.key == genre) { onGenre(g.key) } }
        }
    }
}

/** One genre: its name and how many artists have it; chosen it edges in the accent and narrows the grid. */
@Composable
private fun GenreRow(label: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsKeyboardFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape)
            .background(if (on) Color.White.copy(alpha = 0.12f) else if (hovered) MusicGlassRaised else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) colors.borderFocus else Color.Transparent, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) colors.accent else Color.Transparent))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 19.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count.toString(), fontSize = 16.sp, color = colors.textMuted, maxLines = 1)
    }
}

/**
 * The middle panel of Artists A to Z: every artist as a photo card in a grid that scrolls (the one thing on the Shelf that
 * does), a letter rail beside it, and typing a letter jumps. Resting on a card shows the artist on the right; OK or a click
 * opens their artist page, the one with the big fanart.
 */
@Composable
private fun ArtistGridPanel(
    title: String,
    list: List<LibraryArtist>,
    filter: ShelfArtistFilter,
    focusedId: String?,
    requester: (String) -> FocusRequester,
    onFocusArtist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val letters = remember(list, filter) { if (filter == ShelfArtistFilter.AddedRecently) emptyMap() else shelfLetterIndex(list) }
    // A new list (another genre or filter) starts at the top.
    LaunchedEffect(title, filter, list.size) { gridState.scrollToItem(0) }
    fun jump(letter: Char) {
        val index = shelfJumpTo(letters, letter) ?: return
        val artist = list[index]
        onFocusArtist(artist.id)
        scope.launch {
            gridState.scrollToItem(index)
            withFrameNanos { }
            runCatching { requester(artist.id).requestFocus() }
        }
    }
    LensPanel(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 40.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${list.size} artist${if (list.size == 1) "" else "s"}", fontSize = 19.sp, color = colors.textSecondary)
        }
        Spacer(Modifier.height(20.dp))
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (filter == ShelfArtistFilter.Starred) "Star an artist on their page to see them here." else "No artists match.", fontSize = 19.sp, color = colors.textMuted)
            }
            return@LensPanel
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(176.dp),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxHeight().onKeyEvent { event ->
                    // A typed letter jumps to the first artist filed under it.
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val letter = LETTER_KEYS[event.key] ?: return@onKeyEvent false
                    if (letters.isEmpty()) return@onKeyEvent false
                    jump(letter)
                    true
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { artist ->
                    ArtistCard(artist, artist.id == focusedId, requester(artist.id), onFocus = { onFocusArtist(artist.id) }, onOpen = { onOpenArtist(artist.id) })
                }
            }
            if (letters.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Column(Modifier.width(30.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                    val current = list.firstOrNull { it.id == focusedId }?.shelfLetter()
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

/** One artist in the grid: round photo, name and releases. Every card is the same size; a star marks a starred artist. */
@Composable
private fun ArtistCard(artist: LibraryArtist, selected: Boolean, requester: FocusRequester, onFocus: () -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val keyFocused by source.collectIsKeyboardFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onFocus() }
    val shape = RoundedCornerShape(18.dp)
    val count = remember(artist.releases) { countReleaseCards(artist.releases) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(204.dp)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clip(shape)
            .background(if (selected || keyFocused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .border(if (keyFocused) 3.dp else 0.dp, if (keyFocused) colors.borderFocus else Color.Transparent, shape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            MusicCover(artist.art.thumb, artist.name, size = 124.dp, circle = true)
            if (artist.favourite) {
                Icon(Icons.Rounded.Star, contentDescription = "Starred", tint = GOLD, modifier = Modifier.align(Alignment.TopEnd).size(24.dp))
            }
        }
        Text(artist.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text("$count release${if (count == 1) "" else "s"}", fontSize = 14.sp, color = colors.textMuted, maxLines = 1)
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

/** The right panel of Artists A to Z: the artist in focus, with the way into their page and a quick play. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistSummaryPanel(artist: LibraryArtist?, onArtistClick: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    var busy by remember(artist?.id) { mutableStateOf(false) }
    fun launchPlay(shuffled: Boolean) {
        val a = artist ?: return
        if (busy) return
        busy = true
        com.nuvio.app.core.ui.NuvioToastController.show(if (shuffled) "Mixing ${a.name}…" else "Opening ${a.name}…")
        scope.launch {
            val albums = shelfAlbumsOf(a).map { it.release }
            val ok = try {
                if (shuffled) playArtistMix(catalog, a, albums) else playTopAlbums(catalog, albums.take(12), shuffled = false)
            } catch (e: CancellationException) { throw e } catch (_: Throwable) { false }
            busy = false
            if (!ok) com.nuvio.app.core.ui.NuvioToastController.show("Couldn't open ${a.name}")
        }
    }
    LensPanel(modifier) {
        SectionLabel("ARTIST")
        Spacer(Modifier.height(14.dp))
        if (artist == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Rest on an artist to see them here.", fontSize = 18.sp, color = colors.textMuted)
            }
            return@LensPanel
        }
        val row = remember(artist) { buildShelfRow(artist) }
        val genres = remember(artist) { artist.genres.mapNotNull { canonicalGenre(it) }.distinct().take(4).map { ShelfGenre(it, 0).label } }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val size = minOf(maxWidth, maxHeight * 0.55f)
            Column {
                MusicCover(artist.art.thumb, artist.name, size = size)
                Text(artist.name, fontSize = 32.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                Text(shelfArtistLine(row.totalReleases, row.playableReleases, 0), fontSize = 18.sp, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                if (genres.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        genres.forEach { g ->
                            Text(g, fontSize = 15.sp, color = colors.textSecondary, modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
                        }
                    }
                }
            }
        }
        ShelfButton("Open artist page", Icons.Rounded.Person, primary = true, modifier = Modifier.fillMaxWidth()) { onArtistClick(artist.id) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShelfButton("Play", Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f)) { launchPlay(false) }
            ShelfButton("Shuffle", Icons.Rounded.Shuffle, modifier = Modifier.weight(1f)) { launchPlay(true) }
        }
    }
}

/** The middle panel in All albums: the wall's title, its count and a page of fifteen covers. */
@Composable
private fun WallPanel(title: String, albums: List<ShelfAlbum>, focusedKey: String?, onFocusAlbum: (ShelfAlbum) -> Unit, onAlbumClick: (String) -> Unit, modifier: Modifier) {
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
                onOpen = onAlbumClick,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/**
 * A fixed grid of [perPage] covers, [columns] across, turned a page at a time: Down past the last row and Up past the first
 * turn the page (Up on the first page goes on to the buttons above). Left from the first column calls [onLeftEdge] when given.
 * OK or a click opens the album's page ([onOpen]). The grid keeps its size whatever is on the page.
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
    onOpen: (browseId: String) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val rows = perPage / columns
    val pages = shelfPageCount(albums.size, perPage)
    var turnedTo by remember(resetKey, albums.size) { mutableIntStateOf(0) }
    val page = turnedTo.coerceAtMost(pages - 1)
    val slots = remember(resetKey, perPage) { List(perPage) { FocusRequester() } }
    var pendingSlot by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(page, pendingSlot) {
        val slot = pendingSlot ?: return@LaunchedEffect
        withFrameNanos { }
        slots[slot].requestFocusLogged()
        pendingSlot = null
    }
    val onPage = albums.shelfPage(page, perPage)

    fun turn(to: Int, slot: Int) {
        val count = albums.shelfPage(to, perPage).size
        turnedTo = to
        pendingSlot = slot.coerceAtMost(count - 1).coerceAtLeast(0)
    }


    Column(modifier.pageTurning(page, pages) { turnedTo = it }) {
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
                                    onOpen = { album.release.ytBrowseId?.let(onOpen) },
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
            PageFooter(page, pages) { turnedTo = it }
        }
    }
}

/**
 * Turning pages without the remote: the mouse wheel (one page per notch, not per tick of a trackpad), Page Up and Page Down.
 * Arrow keys on the covers turn them as before.
 */
private fun Modifier.pageTurning(page: Int, pages: Int, turnTo: (Int) -> Unit): Modifier = composed {
    var lastWheel by remember { mutableStateOf(0L) }
    this
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            val now = LibraryClock.nowEpochMs()
            if (dy != 0f && now - lastWheel > 250) {
                val to = if (dy > 0) page + 1 else page - 1
                if (to in 0 until pages) { lastWheel = now; turnTo(to) }
            }
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val to = when (event.key) {
                Key.PageDown -> page + 1
                Key.PageUp -> page - 1
                else -> return@onKeyEvent false
            }
            if (to in 0 until pages) { turnTo(to); true } else false
        }
}

/**
 * Out of the keyboard's way: the page dots and arrows are for the mouse. The arrow keys turn the pages already (moving
 * past a page's last cover), and as focus stops they showed no ring, so a press seemed to do nothing (2026-11-13).
 */
private fun Modifier.mouseOnly(): Modifier = focusProperties { canFocus = false }

/** Under a grid: a page dot for each page (up to twelve), arrows and "Page 2 of 27", all clickable. Keeps its height whatever the page count. */
@Composable
private fun PageFooter(page: Int, pages: Int, turnTo: (Int) -> Unit) {
    if (pages <= 1) return
    val colors = MaterialTheme.nuvio.colors
    @Composable
    fun Arrow(label: String, to: Int) {
        val on = to in 0 until pages
        Text(
            label,
            fontSize = 20.sp,
            color = if (on) colors.textPrimary else colors.textMuted.copy(alpha = 0.4f),
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).mouseOnly().clickable(enabled = on) { turnTo(to) }.padding(horizontal = 10.dp),
        )
    }
    Arrow("‹", page - 1)
    // Twelve dots around the page in view, so a long wall still shows where it is.
    val first = (page - 5).coerceAtLeast(0).coerceAtMost((pages - 12).coerceAtLeast(0))
    for (p in first until minOf(pages, first + 12)) {
        Box(
            Modifier.mouseOnly().clickable { turnTo(p) }.padding(horizontal = 4.dp, vertical = 7.dp).height(8.dp).width(if (p == page) 28.dp else 8.dp).clip(CircleShape)
                .background(if (p == page) colors.accent else Color.White.copy(alpha = 0.3f)),
        )
    }
    Arrow("›", page + 1)
    Text("Page ${page + 1} of $pages", fontSize = 15.sp, color = colors.textMuted, modifier = Modifier.padding(start = 4.dp))
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
    onOpen: () -> Unit,
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
                    .clickable(interactionSource = source, indication = null, onClick = onOpen),
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

// region smart shelves (fork:shelf)

/** The row that leads back from Artists A to Z or All albums to the list of shelves. */
@Composable
private fun BackToShelves(entry: FocusRequester? = null, onBack: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape)
            .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
            .musicKeyFocusRing(shape).clickable(onClick = onBack).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("‹", fontSize = 26.sp, color = colors.accent)
        Text("All shelves", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
    }
}

/**
 * The shelves, one row each with how many things it holds; resting on one (focus or pointer) opens it in the middle, as the
 * artist list does. Artists A to Z and All albums sit at the bottom, under a line.
 */
@Composable
private fun ShelfList(
    /** On the open shelf's row: where Down from the Music tab's view pills lands. */
    entry: FocusRequester,
    current: ShelfSection,
    counts: Map<ShelfSection, Int>,
    signedIn: Boolean,
    onChoose: (ShelfSection) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("SHELVES")
        Spacer(Modifier.height(8.dp))
        ShelfSection.entries.forEach { s ->
            if (s == ShelfSection.Artists) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                Spacer(Modifier.height(8.dp))
            }
            val on = s == current
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsKeyboardFocusedAsState()
            val hovered by source.collectIsHoveredAsState()
            val shape = RoundedCornerShape(14.dp)
            val count = counts[s]
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .then(if (on) Modifier.focusRequester(entry) else Modifier)
                    .onFocusChanged { if (it.hasFocus && s != ShelfSection.Artists && s != ShelfSection.AllAlbums) onChoose(s) }
                    .clip(shape)
                    .background(if (on) Color.White.copy(alpha = 0.12f) else if (hovered) MusicGlassRaised else Color.Transparent)
                    .border(if (focused) 2.dp else 0.dp, if (focused) colors.borderFocus else Color.Transparent, shape)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null) { onChoose(s) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) colors.accent else Color.Transparent))
                Spacer(Modifier.width(10.dp))
                Text(s.label, fontSize = 19.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val trailing = when {
                    (s == ShelfSection.Mixes || s == ShelfSection.Liked || s == ShelfSection.ListenAgain) && !signedIn -> "Sign in"
                    count != null && count > 0 -> count.toString()
                    s == ShelfSection.Artists || s == ShelfSection.AllAlbums -> "›"
                    else -> null
                }
                trailing?.let { Text(it, fontSize = 16.sp, color = colors.textMuted, maxLines = 1) }
            }
        }
    }
}

/** The middle panel for a smart shelf: its name and line, and a page of fifteen covers. */
@Composable
private fun ItemsPanel(
    section: ShelfSection,
    items: List<ShelfItem>,
    empty: String,
    focusedKey: String?,
    onFocusItem: (ShelfItem) -> Unit,
    onOpen: (ShelfItem) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    LensPanel(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(section.label, fontSize = 40.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(section.blurb, fontSize = 17.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            if (items.isNotEmpty()) Text("${items.size}", fontSize = 19.sp, color = colors.textSecondary)
        }
        Spacer(Modifier.height(20.dp))
        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(empty, fontSize = 19.sp, color = colors.textMuted)
            }
        } else {
            ItemPages(items, focusedKey, section, onFocusItem, onOpen, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** A fixed grid of fifteen shelf items, turned a page at a time as the album grids are. OK or a click opens (an album) or plays (a list). */
@Composable
private fun ItemPages(
    items: List<ShelfItem>,
    focusedKey: String?,
    resetKey: Any,
    onFocusItem: (ShelfItem) -> Unit,
    onOpen: (ShelfItem) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val columns = SHELF_WALL_COLUMNS
    val perPage = SHELF_WALL_PAGE
    val rows = perPage / columns
    val pages = shelfPageCount(items.size, perPage)
    var turnedTo by remember(resetKey, items.size) { mutableIntStateOf(0) }
    val page = turnedTo.coerceAtMost(pages - 1)
    val slots = remember(resetKey) { List(perPage) { FocusRequester() } }
    var pendingSlot by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(page, pendingSlot) {
        val slot = pendingSlot ?: return@LaunchedEffect
        withFrameNanos { }
        slots[slot].requestFocusLogged()
        pendingSlot = null
    }
    val onPage = items.shelfPage(page, perPage)
    fun turn(to: Int, slot: Int) {
        val count = items.shelfPage(to, perPage).size
        turnedTo = to
        pendingSlot = slot.coerceAtMost(count - 1).coerceAtLeast(0)
    }
    Column(modifier.pageTurning(page, pages) { turnedTo = it }) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (r in 0 until rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    for (c in 0 until columns) {
                        val slot = r * columns + c
                        val item = onPage.getOrNull(slot)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (item != null) {
                                ItemCell(
                                    item = item,
                                    requester = slots[slot],
                                    onFocus = { onFocusItem(item) },
                                    onOpen = { onOpen(item) },
                                    onKey = { key ->
                                        when {
                                            key == Key.DirectionDown && (r == rows - 1 || slot + columns >= onPage.size) && page < pages - 1 -> { turn(page + 1, c); true }
                                            key == Key.DirectionDown && slot + columns >= onPage.size -> true
                                            key == Key.DirectionUp && r == 0 && page > 0 -> { turn(page - 1, (rows - 1) * columns + c); true }
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
            PageFooter(page, pages) { turnedTo = it }
        }
    }
}

/** A shelf item's cover, title and line, for the grid. */
private fun itemLines(item: ShelfItem): Triple<String, String, String?> = when (item) {
    is ShelfItem.Song -> Triple(item.track.title, item.track.artists.joinToString(", ") { it.name }, item.track.coverUrl)
    is ShelfItem.Album -> Triple(item.album.release.title, listOfNotNull(item.album.artist.name, item.album.release.year ?: item.album.release.releaseDate?.take(4)).joinToString(" · "), null)
    is ShelfItem.PlayedAlbum -> Triple(item.play.title, item.play.artist.orEmpty(), item.play.coverUrl)
    is ShelfItem.Playlist -> Triple(item.list.title, item.list.subtitle ?: if (item.list.radio) "Mix" else "Playlist", item.list.thumbnailUrl)
    is ShelfItem.Played -> Triple(item.play.title, item.play.kind ?: "Played", item.play.coverUrl)
    is ShelfItem.Local -> Triple(item.playlist.name, "${item.playlist.tracks.size} songs · made here", item.playlist.covers.firstOrNull())
}

@Composable
private fun ItemCell(item: ShelfItem, requester: FocusRequester, onFocus: () -> Unit, onOpen: () -> Unit, onKey: (Key) -> Boolean) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val keyFocused by source.collectIsKeyboardFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onFocus() }
    val (title, line, cover) = itemLines(item)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverSize: Dp = minOf(maxWidth, maxHeight - 46.dp).coerceAtLeast(40.dp)
        Column(Modifier.width(coverSize)) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .size(coverSize)
                    .focusRequester(requester)
                    .onFocusChanged { if (it.isFocused) onFocus() }
                    .onKeyEvent { event -> event.type == KeyEventType.KeyDown && onKey(event.navigationKey()) }
                    .border(if (keyFocused) 3.dp else 0.dp, if (keyFocused) colors.borderFocus else Color.Transparent, shape)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null, onClick = onOpen),
            ) {
                if (item is ShelfItem.Album) {
                    ReleaseCover(item.album.release, Modifier.fillMaxSize().padding(if (keyFocused) 3.dp else 0.dp))
                } else {
                    MusicCover(cover, title, size = coverSize, modifier = Modifier.padding(if (keyFocused) 3.dp else 0.dp))
                }
                val badge = when (item) {
                    is ShelfItem.Playlist -> if (item.list.radio) "Mix" else null
                    is ShelfItem.Played -> if (item.play.playlistId == null || com.nuvio.app.features.music.isRadioPlaylistId(item.play.playlistId!!)) "Radio" else null
                    else -> null
                }
                badge?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Text(line, fontSize = 14.sp, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The right panel for a playlist, mix, played radio or album from the play log. */
@Composable
private fun ItemPanel(item: ShelfItem, actions: ShelfActions, onAlbumClick: (String) -> Unit, onArtistClick: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val (title, line, cover) = itemLines(item)
    LensPanel(modifier) {
        SectionLabel(
            when (item) {
                is ShelfItem.PlayedAlbum -> "ALBUM"
                is ShelfItem.Local -> "YOUR PLAYLIST"
                is ShelfItem.Playlist -> if (item.list.radio) "MIX" else "PLAYLIST"
                is ShelfItem.Played -> "PLAYED LATELY"
                is ShelfItem.Album -> "ALBUM"
                is ShelfItem.Song -> "LIKED SONG"
            },
        )
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val size = minOf(maxWidth, maxHeight * 0.62f)
            Column {
                MusicCover(cover, title, size = size)
                Text(title, fontSize = 32.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                Text(line, fontSize = 18.sp, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        ShelfButton(if (item is ShelfItem.PlayedAlbum) "Play album" else "Play", Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.fillMaxWidth()) { actions.play(item) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (item is ShelfItem.PlayedAlbum) {
                ShelfButton("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic, modifier = Modifier.weight(1f)) { actions.queue(item) }
                ShelfButton("Open album", Icons.AutoMirrored.Rounded.OpenInNew, modifier = Modifier.weight(1f)) { onAlbumClick(item.play.browseId) }
            } else {
                ShelfButton("Shuffle", Icons.Rounded.Shuffle, modifier = Modifier.weight(1f)) { actions.play(item, shuffled = true) }
                ShelfButton("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic, modifier = Modifier.weight(1f)) { actions.queue(item) }
            }
        }
        val secondary: Pair<String, () -> Unit>? = when (item) {
            is ShelfItem.Playlist -> "Save to your playlists" to { actions.save(item) }
            is ShelfItem.PlayedAlbum -> item.play.artistId?.let { id -> "Go to ${item.play.artist ?: "the artist"}" to { onArtistClick(id) } }
            is ShelfItem.Song -> item.track.artists.firstOrNull()?.takeIf { it.browseId != null }?.let { a -> "Go to ${a.name}" to { onArtistClick(a.browseId!!) } }
            else -> null
        }
        secondary?.let { (label, act) ->
            Spacer(Modifier.height(12.dp))
            ShelfButton(label, Icons.Rounded.LibraryMusic, modifier = Modifier.fillMaxWidth(), onClick = act)
        }
    }
}

// endregion

// endregion
