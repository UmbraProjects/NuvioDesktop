@file:OptIn(ExperimentalMaterial3Api::class)

package com.spydermahn.nuvioremote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer

/** Lower-case, accents and "the " gone, so "Beyoncé" finds "beyonce" and "the beatles" sorts under B. */
private fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

private fun matches(query: String, vararg fields: String?): Boolean {
    val words = searchKey(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val haystack = fields.filterNotNull().joinToString(" ") { searchKey(it) }
    return words.all { it in haystack }
}

// ---------------------------------------------------------------- Shared pieces

@Composable
private fun LibraryHeader(
    title: String,
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = Muted) }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Muted) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = Muted) }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        )
    }
}

@Composable
private fun Chips(labels: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels, key = { it }) { label ->
            FilterChip(
                selected = label == selected,
                onClick = { onSelect(label) },
                label = { Text(label, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent.copy(alpha = 0.22f),
                    selectedLabelColor = RemoteColors.onSurface,
                ),
            )
        }
    }
}

/** Nothing to show: still loading, the PC could not answer, or the library is simply empty. */
@Composable
private fun EmptyLibrary(loading: Boolean, problem: String?, empty: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Asking the PC for its library…", color = Muted)
            }
            problem != null -> {
                Text(problem, color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            else -> Text(empty, color = Muted, textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------- Movies & TV

private const val ALL = "All"
private const val UNWATCHED = "Unwatched"

/**
 * The PC's Library (saved titles, its Trakt or Simkl lists, local folders), as posters. Choosing one opens its details
 * on the TV, where the remote picks a source.
 */
@Composable
fun VideoLibraryScreen(state: UiState, viewModel: RemoteViewModel, onOpened: () -> Unit) {
    LaunchedEffect(state.link) { if (state.link == Link.Connected) viewModel.loadVideo() }
    val library = state.video
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf(ALL) }

    val sections = remember(library.titles) { library.titles.map { it.section }.distinct() }
    val chips = remember(sections, library.titles) {
        buildList {
            add(ALL)
            addAll(sections)
            if (library.titles.any { it.watched }) add(UNWATCHED)
        }
    }
    // A section that went away (the library changed) shows everything rather than nothing.
    val activeSection = if (section in chips) section else ALL
    val shown = remember(library.titles, query, activeSection) {
        library.titles.filter { title ->
            when (activeSection) {
                ALL -> true
                UNWATCHED -> !title.watched
                else -> title.section == section
            } && matches(query, title.name, title.year)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LibraryHeader("Movies & TV", query, { query = it }, "Search your library", library.loading) { viewModel.loadVideo(force = true) }
        if (chips.size > 2) Chips(chips, activeSection) { section = it }
        if (shown.isEmpty()) {
            EmptyLibrary(
                loading = library.loading && library.titles.isEmpty(),
                problem = library.problem,
                empty = if (library.titles.isEmpty()) "Nothing in the library on the PC yet. Save films and shows there and they show up here."
                else "Nothing matches.",
                onRetry = { viewModel.loadVideo(force = true) },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(countLabel(shown.size, "title"), color = Muted, fontSize = 13.sp)
                }
                items(shown, key = { it.key }) { title ->
                    PosterCell(title) {
                        viewModel.openTitle(title)
                        onOpened()
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCell(title: LibraryTitle, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box {
            RemoteImage(
                url = title.poster,
                fallback = title.posterFallback,
                placeholder = title.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
            )
            if (title.watched) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "Watched",
                    tint = Color(0xFF6FD08C),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(Color(0xCC0E0F14)),
                )
            }
        }
        Text(title.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        title.year?.let { Text(it, fontSize = 12.sp, color = Muted, maxLines = 1) }
    }
}

private fun countLabel(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

// ---------------------------------------------------------------- Music

private const val ALBUMS = "Albums"
private const val ARTISTS = "Artists"
private const val STARRED = "Starred"

/**
 * The PC's music library: every album of every artist in it, or the artists A to Z. An album plays, shuffles or joins
 * the queue on the TV; an artist plays as a mix of their albums or as YouTube Music's radio.
 */
@Composable
fun MusicLibraryScreen(state: UiState, viewModel: RemoteViewModel, onPlayed: () -> Unit) {
    LaunchedEffect(state.link) { if (state.link == Link.Connected) viewModel.loadMusic() }
    val library = state.music
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(ALBUMS) }
    var kind by rememberSaveable { mutableStateOf(ALL) }
    var openArtistId by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<LibraryAlbum?>(null) }

    val artistsById = remember(library.artists) { library.artists.associateBy { it.id } }
    val openArtist = openArtistId?.let(artistsById::get)

    if (openArtist != null) {
        BackHandler { openArtistId = null }
        ArtistPage(
            artist = openArtist,
            albums = library.albums.filter { it.artistId == openArtist.id },
            onBack = { openArtistId = null },
            onAlbum = { picked = it },
            onAction = { action ->
                viewModel.artist(openArtist, action)
                onPlayed()
            },
        )
    } else {
        Column(Modifier.fillMaxSize()) {
            LibraryHeader("Music", query, { query = it }, "Search artists and albums", library.loading) { viewModel.loadMusic(force = true) }
            Chips(listOf(ALBUMS, ARTISTS), view) { view = it }
            when {
                library.albums.isEmpty() && library.artists.isEmpty() -> EmptyLibrary(
                    loading = library.loading,
                    problem = library.problem,
                    empty = "No artists in the music library on the PC yet. Add some from the Music tab there.",
                    onRetry = { viewModel.loadMusic(force = true) },
                )
                view == ARTISTS -> ArtistList(
                    artists = library.artists.filter { matches(query, it.name) },
                    albumCounts = remember(library.albums) { library.albums.groupingBy { it.artistId }.eachCount() },
                    onOpen = { openArtistId = it.id },
                )
                else -> {
                    val kinds = remember(library.albums) {
                        buildList {
                            add(ALL)
                            addAll(library.albums.map { it.kind }.distinct().sortedBy(::kindOrder))
                            if (library.albums.any { it.starred }) add(STARRED)
                        }
                    }
                    val activeKind = if (kind in kinds) kind else ALL
                    val shown = library.albums.filter { album ->
                        when (activeKind) {
                            ALL -> true
                            STARRED -> album.starred
                            else -> album.kind == kind
                        } && matches(query, album.title, artistsById[album.artistId]?.name, album.year)
                    }
                    if (kinds.size > 2) Chips(kinds, activeKind) { kind = it }
                    AlbumGrid(shown, artistsById, header = countLabel(shown.size, "album")) { picked = it }
                }
            }
        }
    }

    picked?.let { album ->
        AlbumSheet(
            album = album,
            artist = artistsById[album.artistId],
            onDismiss = { picked = null },
            onAction = { action ->
                viewModel.album(album, action)
                picked = null
                if (action != AlbumAction.Queue) onPlayed()
            },
            onArtist = { artist ->
                picked = null
                openArtistId = artist.id
            },
        )
    }
}

private fun kindOrder(kind: String): Int = when (kind) {
    "Album" -> 0
    "EP" -> 1
    "Single" -> 2
    "Live" -> 3
    "Compilation" -> 4
    else -> 5
}

@Composable
private fun AlbumGrid(
    albums: List<LibraryAlbum>,
    artistsById: Map<String, LibraryArtistInfo>,
    header: String?,
    showArtist: Boolean = true,
    top: (@Composable () -> Unit)? = null,
    onClick: (LibraryAlbum) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (top != null) item(span = { GridItemSpan(maxLineSpan) }) { top() }
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { Text(header, color = Muted, fontSize = 13.sp) }
        items(albums, key = { it.artistId + "|" + it.browseId }) { album ->
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick(album) }) {
                Box {
                    RemoteImage(
                        url = album.cover,
                        fallback = null,
                        placeholder = album.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
                    )
                    if (album.starred) {
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = "Starred",
                            tint = Color(0xFFF2C14E),
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                        )
                    }
                }
                Text(album.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                val line = listOfNotNull(
                    if (showArtist) artistsById[album.artistId]?.name else null,
                    album.year,
                    album.kind.takeIf { it != "Album" },
                ).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ArtistList(artists: List<LibraryArtistInfo>, albumCounts: Map<String, Int>, onOpen: (LibraryArtistInfo) -> Unit) {
    if (artists.isEmpty()) {
        EmptyLibrary(loading = false, problem = null, empty = "No artists match.", onRetry = {})
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        items(artists, key = { it.id }) { artist ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(artist) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteImage(
                    url = artist.photo,
                    fallback = null,
                    placeholder = artist.name.take(1),
                    modifier = Modifier.size(52.dp).clip(CircleShape),
                )
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(countLabel(albumCounts[artist.id] ?: 0, "release"), fontSize = 13.sp, color = Muted)
                }
                if (artist.starred) Icon(Icons.Rounded.Star, contentDescription = "Starred", tint = Color(0xFFF2C14E), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ArtistPage(
    artist: LibraryArtistInfo,
    albums: List<LibraryAlbum>,
    onBack: () -> Unit,
    onAlbum: (LibraryAlbum) -> Unit,
    onAction: (ArtistAction) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            Text(artist.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AlbumGrid(
            albums = albums.sortedWith(compareBy<LibraryAlbum> { kindOrder(it.kind) }.thenByDescending { it.year ?: "" }),
            artistsById = emptyMap(),
            header = countLabel(albums.size, "release"),
            showArtist = false,
            top = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(
                        url = artist.photo,
                        fallback = null,
                        placeholder = artist.name.take(1),
                        modifier = Modifier.size(88.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.size(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Shuffle all", Icons.Rounded.Shuffle, primary = true) { onAction(ArtistAction.Mix) }
                        ActionButton("Radio", Icons.Rounded.Radio) { onAction(ArtistAction.Radio) }
                    }
                }
            },
            onClick = onAlbum,
        )
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (primary) {
        Button(onClick = onClick, modifier = modifier) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, colors = ButtonDefaults.outlinedButtonColors(contentColor = RemoteColors.onSurface)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(label)
        }
    }
}

@Composable
private fun AlbumSheet(
    album: LibraryAlbum,
    artist: LibraryArtistInfo?,
    onDismiss: () -> Unit,
    onAction: (AlbumAction) -> Unit,
    onArtist: (LibraryArtistInfo) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PanelRaised,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = album.cover,
                    fallback = null,
                    placeholder = album.title,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    artist?.let { Text(it.name, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(listOfNotNull(album.kind, album.year).joinToString(" · "), color = Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            ActionButton("Play on the TV", Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.fillMaxWidth()) { onAction(AlbumAction.Play) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Shuffle", Icons.Rounded.Shuffle, modifier = Modifier.weight(1f)) { onAction(AlbumAction.Shuffle) }
                ActionButton("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic, modifier = Modifier.weight(1f)) { onAction(AlbumAction.Queue) }
            }
            if (artist != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onArtist(artist) }) { Text("More by ${artist.name}") }
            }
        }
    }
}
