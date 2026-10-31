package com.nuvio.app.features.music.library

import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.core.ui.smoothBorder
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.ui.text.style.TextAlign
import com.nuvio.app.core.ui.NuvioGlass
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import com.nuvio.app.core.ui.navigationKey
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.isDesktop
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.MusicMessage
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.identity.MusicPaletteResolver
import com.nuvio.app.features.music.identity.ReleasePaletteResolver
import com.nuvio.app.features.music.identity.identity
import kotlinx.coroutines.launch

/**
 * Phase 4: the Library home as a shelf. One row per artist: the artist and its quick-play chips on the
 * left (a fixed column, set off from the covers by a divider), then a rail of release covers. Only releases
 * that can be played are shown; the artist's top five come first (numbered when they are really ranked or
 * starred), then a thin line and everything else oldest to newest, all at one size. Hovering a cover slides its disc out and shows a star that pins the album to
 * the top five. A star by the artist's name floats it to the top of the shelf. Clicking a cover opens
 * the album; the avatar, name or "All" open the room.
 * Above the rows sit up to four cards (a year ago this week, a starred artist, added recently, continue your ladder).
 * [view] picks Shelf (this file), Constellation ([ConstellationView]) or Eras ([LibraryErasView]); the Music tab's
 * header holds the switch, and on Constellation its filter box feeds [filterQuery]. The page sits on the tab's
 * sky and the app accent: the artists' own colours are kept for their own pages.
 */
@Composable
internal fun MusicLibraryShelf(
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** HTPC phase 4: the "Continue your ladder" card reopens a ladder; the host swaps its body for the ladder screen. */
    onOpenLadder: (LadderRequest) -> Unit = {},
    view: LibraryView = LibraryView.Shelf,
    filterQuery: String = "",
) {
    val colors = MaterialTheme.nuvio.colors
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()

    val yearAgo = remember(state.artists) {
        val (y, m, d) = civilDate(LibraryClock.nowEpochMs())
        pickYearAgo(state.artists, y, m, d)
    }
    // HTPC phase 4: the card row above the shelf (a year ago, starred, added recently, continue your ladder).
    val cards = remember(state.artists, yearAgo) {
        val (y, m, d) = civilDate(LibraryClock.nowEpochMs())
        shelfCards(state.artists, yearAgo, LadderProgressStore.resumable(), daySeed = y * 372 + m * 31 + d)
    }
    val totalReleases = remember(state.artists) { state.artists.sumOf { countReleaseCards(it.releases) } }
    // The header's filter narrows the Shelf and Eras here; the Constellation dims what doesn't match instead.
    val shown = remember(state.artists, filterQuery) { state.artists.filter { it.matchesLibraryFilter(filterQuery) } }
    // Starred artists first; the sort is stable, so each group stays alphabetical.
    val ordered = remember(shown) { shown.sortedByDescending { it.favourite } }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            state.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (state.artists.isEmpty() && state.working.isEmpty()) {
                MusicMessage("Open an artist from Search and choose Add to library. Every album, single and EP comes with it, with artwork and details.")
            } else if (view == LibraryView.Constellation) { // fork:music
                ConstellationView(
                    artists = state.artists,
                    pending = state.working.filterKeys { id -> state.artists.none { it.id == id } }.values.map { it.status },
                    onArtistClick = onArtistClick,
                    query = filterQuery,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onOpenLadder = onOpenLadder, // HTPC phase 5
                )
            } else if (view == LibraryView.Eras) { // fork:music
                LibraryErasView(
                    artists = shown,
                    pending = state.working.filterKeys { id -> state.artists.none { it.id == id } }.values.map { it.status },
                    reducedMotion = state.settings.reducedMotion,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    yearAgo = yearAgo?.let { pick -> { YearAgoStrip(pick, state.artists.size, totalReleases) } },
                )
            } else {
                val shelfListState = rememberLazyListState()
                val shelfScope = rememberCoroutineScope()
                val coverNavigator = remember(shelfListState) { ShelfCoverNavigator(shelfListState, shelfScope) }
                val pendingCount = state.working.keys.count { id -> state.artists.none { it.id == id } }
                // Where the first artist's row sits in the list: under the cards, the count and any artist still being added.
                coverNavigator.firstArtistItem = (if (cards.isNotEmpty()) 1 else 0) + 1 + pendingCount
                coverNavigator.artistCount = ordered.size
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = shelfListState,
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 24.dp), // 5% safe area for the TV
                ) {
                    // No gaps between the items under the cards: each draws its own stretch of the column divider.
                    if (cards.isNotEmpty()) {
                        item(key = "shelf-cards") {
                            ShelfCardRow(
                                cards = cards,
                                libraryPictures = remember(state.artists) { state.artists.associate { it.name.lowercase() to it.art.thumb } },
                                onOpenArtist = onArtistClick,
                                onOpenLadder = onOpenLadder,
                            )
                        }
                    }
                    item(key = "shelf-count") {
                        ShelfColumnLayout(Modifier.padding(top = 4.dp, bottom = 12.dp), left = {
                            Text("${state.artists.size} artists · $totalReleases releases", fontSize = 22.sp, color = colors.textMuted, maxLines = 1)
                        }) {}
                    }
                    val pending = state.working.filterKeys { id -> state.artists.none { it.id == id } }
                    items(pending.entries.toList(), key = { "pending:${it.key}" }) { (_, progress) ->
                        ShelfColumnLayout(Modifier.padding(vertical = 12.dp), left = {}) {
                            Text("Adding… ${progress.status}", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                        }
                    }
                    itemsIndexed(ordered, key = { _, artist -> artist.id }) { index, artist ->
                        ShelfArtistRow(
                            artist = artist,
                            index = index,
                            navigator = coverNavigator,
                            status = state.working[artist.id]?.status,
                            reducedMotion = state.settings.reducedMotion,
                            onOpenArtist = { onArtistClick(artist.id) },
                            onOpenRelease = { release ->
                                val id = release.ytBrowseId
                                if (id != null) onAlbumClick(id) else onArtistClick(artist.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** True when [query] is blank or appears in the artist's name, genres or tags (ignoring case). */
internal fun LibraryArtist.matchesLibraryFilter(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return name.contains(q, ignoreCase = true) || (genres + tags).any { it.contains(q, ignoreCase = true) }
}

// region year ago

/** "A year ago this week": last year's release from the same date, with a Play button. */
@Composable
private fun YearAgoStrip(pick: YearAgoPick, artistCount: Int, releaseCount: Int) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    val release = pick.release
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).musicGlass(RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReleaseCover(release, Modifier.size(60.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("A YEAR AGO THIS WEEK", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.accent)
            Text(
                listOfNotNull(release.title, pick.artist.name, release.kind.label.lowercase(), release.releaseDate?.let(::formatReleaseDay)).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("$artistCount artists · $releaseCount releases", style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
        val browseId = release.ytBrowseId
        if (browseId != null) {
            NuvioPrimaryButton(
                text = "Play it",
                modifier = Modifier.width(120.dp),
                height = 40.dp,
                onClick = {
                    scope.launch {
                        val album = runCatching { catalog.album(browseId) }.getOrNull() ?: return@launch
                        MusicPlayback.player.play(album, trackIndex = 0, shuffled = false)
                    }
                },
            )
        } else {
            Text("Not on YouTube Music", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
        }
    }
}

// endregion

// region shelf card row (HTPC phase 4)

/**
 * Three or four large cards above the shelf. Each can be reached with the d-pad (focus ring and a small grow). Enter or a
 * click plays the card's release when it can be played, otherwise opens the artist; the ladder card reopens the ladder.
 */
@Composable
private fun ShelfCardRow(
    cards: List<ShelfCard>,
    /** Saved artists' pictures by lower-cased name, for the ladder card. */
    libraryPictures: Map<String, String?>,
    onOpenArtist: (String) -> Unit,
    onOpenLadder: (LadderRequest) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    // Air between the cards and the count under them, so the line reads as the shelf's heading, not a card caption.
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        // One height for every card: each fills the row, which is as tall as the tallest.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            cards.forEach { card ->
                val source = remember { MutableInteractionSource() }
                val focused by source.collectIsKeyboardFocusedAsState()
                val hovered by source.collectIsHoveredAsState()
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .musicGlass(shape, raised = hovered || focused, keyFocused = focused)
                        .hoverable(source)
                        .clickable(interactionSource = source, indication = null) {
                            val ladder = card.ladder
                            val browseId = card.release?.ytBrowseId
                            val artistId = card.artist?.id
                            when {
                                ladder != null -> onOpenLadder(ladder)
                                browseId != null && card.kind != ShelfCardKind.Added -> scope.launch {
                                    val album = runCatching { catalog.album(browseId) }.getOrNull() ?: return@launch
                                    MusicPlayback.player.play(album, trackIndex = 0, shuffled = false)
                                }
                                artistId != null -> onOpenArtist(artistId)
                            }
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // A card with no release (the ladder) shows its artist's picture at the cover's size, so its text lines up
                    // with the others: the Library's own picture when the artist is saved, else one looked up by name.
                    val release = card.release
                    val pictureOf = card.pictureOf
                    val picture by produceState(libraryPictures[pictureOf?.lowercase()], pictureOf) {
                        if (value == null && pictureOf != null) value = LadderArtwork.picture(pictureOf)
                    }
                    if (release != null) {
                        ReleaseCover(release, Modifier.size(96.dp))
                    } else if (picture != null) {
                        MusicCover(picture, pictureOf, size = 96.dp)
                    } else {
                        Box(Modifier.size(96.dp).musicGlass(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Explore, contentDescription = null, tint = colors.accent, modifier = Modifier.size(40.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(card.label.uppercase(), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(card.title, fontSize = 28.sp, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        card.detail?.let { Text(it, fontSize = 22.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

// endregion

// region shelf

// The artist's column: one fixed width, so every artist (and the count over them) shares a centre line,
// with a divider between it and the covers that runs unbroken down the shelf.
private val SHELF_COLUMN_WIDTH = 260.dp
private val SHELF_COLUMN_GAP = 28.dp
private val SHELF_DIVIDER = Color.White.copy(alpha = 0.10f)

/**
 * One stretch of the shelf: [left] centred in the artist column, the divider, then [content]. The divider is
 * drawn behind rather than laid out, because the covers' LazyRow can't be measured for an intrinsic height.
 */
@Composable
private fun ShelfColumnLayout(
    modifier: Modifier = Modifier,
    left: @Composable () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = (SHELF_COLUMN_WIDTH + SHELF_COLUMN_GAP / 2).toPx()
                drawLine(SHELF_DIVIDER, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SHELF_COLUMN_GAP),
    ) {
        Box(Modifier.width(SHELF_COLUMN_WIDTH), contentAlignment = Alignment.Center) { left() }
        content()
    }
}

@Composable
private fun ShelfArtistRow(
    artist: LibraryArtist,
    index: Int,
    navigator: ShelfCoverNavigator,
    status: String?,
    reducedMotion: Boolean,
    onOpenArtist: () -> Unit,
    onOpenRelease: (LibraryRelease) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val wash by animateColorAsState(if (hovered) MusicGlassRaised else Color.Transparent, tween(200))

    // Artists added before Top 5 existed get their ranking filled in once, quietly.
    LaunchedEffect(artist.id, artist.topAlbumKeys.isEmpty(), status == null) {
        if (status == null) MusicLibraryRepository.ensureTopAlbums(artist.id)
    }

    val row = remember(artist) { buildShelfRow(artist) }
    // Only the covers on this shelf get a palette (for the disc label and a missing cover).
    val keys = remember(row) { row.tiles.map { it.release.key }.toSet() }
    val missing = row.tiles.count { it.release.needsPalette }
    LaunchedEffect(artist.id, missing) { ReleasePaletteResolver.ensure(context, artist, keys = keys, limit = 40) }

    fun playTop(shuffled: Boolean) {
        scope.launch { playTopAlbums(catalog, row.top.map { it.release }, shuffled) }
    }

    // The covers by column for the keyboard: the top albums, the rest (the divider between them is no column), then
    // "All". Up and Down from a cover land on the same column of the row above or below (see ShelfCoverNavigator).
    val columnCount = if (row.tiles.isEmpty()) 1 else row.top.size + row.rest.size + 1
    val columnRequesters = remember(columnCount) { List(columnCount) { FocusRequester() } }
    var focusedColumn by remember { mutableIntStateOf(-1) }
    val rowListState = rememberLazyListState()
    val dividerShown = row.top.isNotEmpty() && row.rest.isNotEmpty()
    fun lazyIndexOf(column: Int) = if (column < row.top.size || !dividerShown) column else column + 1
    DisposableEffect(index, navigator, columnRequesters) {
        val focusColumn: (Int) -> Unit = { wanted ->
            val column = wanted.coerceIn(0, columnCount - 1)
            scope.launch {
                val target = lazyIndexOf(column)
                if (row.tiles.isNotEmpty() && rowListState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                    rowListState.scrollToItem(target)
                    withFrameNanos { }
                }
                runCatching { columnRequesters[column].requestFocus() }
            }
        }
        navigator.rows[index] = focusColumn
        onDispose { if (navigator.rows[index] === focusColumn) navigator.rows.remove(index) }
    }
    val coverKeys = Modifier
        .onFocusChanged { if (!it.hasFocus) focusedColumn = -1 }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || focusedColumn < 0) return@onKeyEvent false
            when (event.navigationKey()) {
                Key.DirectionDown -> navigator.move(index, 1, focusedColumn)
                Key.DirectionUp -> navigator.move(index, -1, focusedColumn)
                else -> false
            }
        }
    fun Modifier.coverColumn(column: Int) = this
        .focusRequester(columnRequesters[column])
        .onFocusChanged { if (it.hasFocus) focusedColumn = column }

    // The whole row comes into view when anything in it takes focus: a cover alone would leave the artist's buttons,
    // which reach further down, cut off at the bottom of the window.
    val rowInView = remember { BringIntoViewRequester() }
    ShelfColumnLayout(
        Modifier
            .bringIntoViewRequester(rowInView)
            .onFocusChanged { if (it.hasFocus) scope.launch { rowInView.bringIntoView() } }
            .hoverable(source)
            .padding(vertical = 18.dp),
        left = {
            // The artist on top, their name and count under it, then three round buttons: their hits (the top five),
            // a shuffle of them, and the favourite star, which floats the artist to the top of the shelf.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The picture and the name are one control: both open the room, so the keyboard stops on them once.
                val artistSource = remember { MutableInteractionSource() }
                val artistFocused by artistSource.collectIsKeyboardFocusedAsState()
                Column(
                    Modifier.clickable(interactionSource = artistSource, indication = null, onClick = onOpenArtist),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val ring = when {
                        artistFocused -> colors.borderFocus
                        hovered -> colors.accent
                        else -> NuvioGlass.EdgeColor
                    }
                    Box(Modifier.smoothBorder(if (artistFocused) 3.dp else 2.dp, ring, CircleShape).padding(3.dp)) {
                        MusicCover(artist.art.thumb, artist.name, size = 64.dp, circle = true)
                    }
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val subtitle = when {
                    status != null -> status
                    row.playableReleases == row.totalReleases -> "${row.totalReleases} release${if (row.totalReleases == 1) "" else "s"}"
                    else -> "${row.playableReleases} playable of ${row.totalReleases}"
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status != null) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (row.top.isNotEmpty()) {
                        ShelfRoundButton(Icons.Rounded.LocalFireDepartment, row.topLabel) { playTop(shuffled = false) }
                        ShelfRoundButton(Icons.Rounded.Shuffle, "Shuffle their hits") { playTop(shuffled = true) }
                    }
                    ShelfRoundButton(
                        icon = Icons.Rounded.Star,
                        description = if (artist.favourite) "Remove from favourites" else "Add to favourites",
                        tint = if (artist.favourite) GOLD else null,
                    ) { MusicLibraryRepository.setFavourite(artist.id, !artist.favourite) }
                }
            }
        },
    ) {
        if (row.tiles.isEmpty()) {
            Text(
                if (status != null) "Reading releases…" else "Nothing here is on YouTube Music yet. Open the room to see every release.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.weight(1f).then(coverKeys).coverColumn(0).clickable(onClick = onOpenArtist),
            )
        } else {
            // Every cover is one size: the top albums lead, then a thin line, then every other release.
            LazyRow(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(wash).then(coverKeys),
                state = rowListState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(row.top, key = { _, tile -> tile.release.key }) { column, tile ->
                    Box(Modifier.coverColumn(column)) {
                        CoverTile(
                            tile = tile,
                            reducedMotion = reducedMotion,
                            onClick = { onOpenRelease(tile.release) },
                            onTogglePin = { MusicLibraryRepository.toggleTopPin(artist.id, tile.release.key) },
                        )
                    }
                }
                if (row.top.isNotEmpty() && row.rest.isNotEmpty()) {
                    item(key = "divider") {
                        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(TILE_SIZE).background(Color.White.copy(alpha = 0.14f)))
                    }
                }
                itemsIndexed(row.rest, key = { _, tile -> tile.release.key }) { restIndex, tile ->
                    Box(Modifier.coverColumn(row.top.size + restIndex)) {
                        CoverTile(
                            tile = tile,
                            reducedMotion = reducedMotion,
                            onClick = { onOpenRelease(tile.release) },
                            onTogglePin = { MusicLibraryRepository.toggleTopPin(artist.id, tile.release.key) },
                        )
                    }
                }
                item(key = "all") {
                    Box(
                        Modifier.coverColumn(columnCount - 1).size(TILE_SIZE).clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .musicKeyFocusRing(RoundedCornerShape(8.dp)).clickable(onClick = onOpenArtist),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "All ${row.totalReleases}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Up and Down between the shelf's cover rows, keeping the column, as a TV grid does. Compose's own search would pick
 * whatever is nearest below (the artist's buttons, beside the covers) rather than the cover underneath. A row out of
 * view is scrolled to first, so it is composed and can take focus.
 */
private class ShelfCoverNavigator(
    private val listState: LazyListState,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /** Each composed artist row (by its place on the shelf) focuses one of its columns. */
    val rows = mutableMapOf<Int, (Int) -> Unit>()
    var firstArtistItem = 0
    var artistCount = 0

    /**
     * Moves from row [from] by [delta] to [column]. Up from the first row carries on as usual (to the cards and the view
     * icons above); Down from the last goes nowhere, rather than sideways onto the artist's buttons.
     */
    fun move(from: Int, delta: Int, column: Int): Boolean {
        val target = from + delta
        if (target < 0) return false
        if (target >= artistCount) return true
        scope.launch {
            // A row not on screen (it may be composed ahead, but cannot take focus off screen) is brought on by about
            // one row's height, not a jump that puts it at the top; once focused it scrolls itself fully into view.
            val info = listState.layoutInfo
            val itemIndex = firstArtistItem + target
            if (info.visibleItemsInfo.none { it.index == itemIndex }) {
                val step = info.visibleItemsInfo.firstOrNull { it.index == firstArtistItem + from }?.size
                    ?: (info.viewportEndOffset - info.viewportStartOffset) / 2
                listState.scrollBy(delta * step.toFloat())
                withFrameNanos { }
            }
            rows[target]?.invoke(column)
        }
        return true
    }

}

/** A round glass button under an artist's name. [tint] overrides the icon colour (the gold of a favourite). */
@Composable
private fun ShelfRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsKeyboardFocusedAsState()
    Box(
        Modifier
            .size(40.dp)
            .musicGlass(CircleShape, raised = hovered, keyFocused = focused)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint ?: colors.textPrimary, modifier = Modifier.size(20.dp))
    }
}

/** A pill on the artist's header: with [label] the filled play chip, without one a round icon button. */
@Composable
private fun QuickPlayChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, filled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(20.dp)
    val tint = if (filled) colors.onAccent else colors.textPrimary
    Row(
        Modifier
            .height(36.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(colors.accent) else Modifier.musicGlass(shape))
            .clickable(onClick = onClick)
            .padding(horizontal = if (label != null) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = label ?: "Shuffle the top albums", tint = tint, modifier = Modifier.size(20.dp))
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

// endregion
