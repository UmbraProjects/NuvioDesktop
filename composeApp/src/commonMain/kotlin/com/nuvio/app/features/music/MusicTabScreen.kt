package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.LadderAlbumRef // fork:music
import com.nuvio.app.features.music.library.LadderOfferRow // fork:music
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
import com.nuvio.app.features.music.library.recentChips // fork:music
import com.nuvio.app.features.music.library.startIsEmpty // fork:music
import com.nuvio.app.features.music.library.startArt // fork:music
import com.nuvio.app.features.music.library.MusicSearchHistory
import androidx.compose.runtime.collectAsState // fork:music
import kotlinx.coroutines.delay

private enum class MusicSearchKind { Albums, Artists }

private enum class MusicSection { Search, Library }

private sealed interface MusicSearchResults {
    data object Idle : MusicSearchResults
    data object Loading : MusicSearchResults
    data object Empty : MusicSearchResults
    data class Albums(val items: List<MusicAlbumSummary>) : MusicSearchResults
    data class Artists(val items: List<MusicArtistSummary>) : MusicSearchResults
}

private const val MIN_QUERY_LENGTH = 2
private const val TYPING_PAUSE_MS = 350L

/**
 * The Music tab: one search box, with Albums and Artists as the two kinds of result. Searching
 * runs a moment after the last keystroke. Tapping a result opens its screen through [onAlbumClick]
 * or [onArtistClick]; this screen starts no playback itself. A library artist opens through the
 * same [onArtistClick] as a search result: both doors lead to one artist page.
 *
 * [topChromePadding] keeps the title clear of the floating top bar, like the other tabs.
 */
@Composable
fun MusicTabScreen(
    onAlbumClick: (browseId: String) -> Unit,
    onArtistClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    topChromePadding: Dp = 0.dp,
    catalog: MusicCatalog = remember { defaultMusicCatalog() },
) {
    val colors = MaterialTheme.nuvio.colors
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(MusicSearchKind.Albums) }
    // Opens on the shelf when the person has saved artists, and on Search when there is nothing to show
    // yet. Loading is synchronous and cheap, so the first frame is already the right one. A choice made
    // here is remembered, so coming back to the tab lands where the person left it.
    var section by rememberSaveable {
        MusicLibraryRepository.ensureLoaded()
        mutableStateOf(
            if (MusicLibraryRepository.state.value.artists.isEmpty()) MusicSection.Search else MusicSection.Library,
        )
    }
    var results by remember { mutableStateOf<MusicSearchResults>(MusicSearchResults.Idle) }
    var ladder by remember { mutableStateOf<LadderRequest?>(null) } // fork:music - the open ladder, if any

    // Restarts on every keystroke or kind change, so the delay is the typing pause.
    LaunchedEffect(query, kind) {
        val q = query.trim()
        if (q.length < MIN_QUERY_LENGTH) {
            results = MusicSearchResults.Idle
            return@LaunchedEffect
        }
        results = MusicSearchResults.Loading
        delay(TYPING_PAUSE_MS)
        results = when (kind) {
            MusicSearchKind.Albums -> catalog.searchAlbums(q).let {
                if (it.isEmpty()) MusicSearchResults.Empty else MusicSearchResults.Albums(it)
            }
            MusicSearchKind.Artists -> catalog.searchArtists(q).let {
                if (it.isEmpty()) MusicSearchResults.Empty else MusicSearchResults.Artists(it)
            }
        }
        if (results !is MusicSearchResults.Empty) MusicSearchHistory.record(q) // feeds Recent searches
    }

    // fork:music - a ladder replaces the tab body while it is open
    val openLadder = ladder
    if (openLadder != null) {
        // The ladder wants the room under its cards, so the player bar shrinks to a pill while it is open.
        DisposableEffect(Unit) {
            MusicPlayback.slimBar.value = true
            onDispose { MusicPlayback.slimBar.value = false }
        }
        MusicLadderScreen(openLadder, onBack = { ladder = null }, onRequest = { ladder = it }, modifier = modifier, topChromePadding = topChromePadding)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = topChromePadding),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "Music",
                style = MaterialTheme.typography.displayLarge,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            MusicSegmented(
                options = listOf(MusicSection.Search to "Search", MusicSection.Library to "Library"),
                selected = section,
                onSelect = { section = it },
            )
            if (section == MusicSection.Search) {
                Spacer(Modifier.height(12.dp))
                NuvioTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Search albums and artists",
                    leadingContent = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textMuted)
                    },
                )
                Spacer(Modifier.height(12.dp))
                MusicSegmented(
                    options = listOf(MusicSearchKind.Albums to "Albums", MusicSearchKind.Artists to "Artists"),
                    selected = kind,
                    onSelect = { kind = it },
                )
            }
        }

        if (section == MusicSection.Library) {
            MusicLibraryShelf(
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onOpenLadder = { ladder = it }, // fork:music
            )
            return@Column
        }

        // fork:music - before anything is typed, the start screen fills the blank space (it scrolls itself)
        if (results is MusicSearchResults.Idle) {
            val libraryArtists = MusicLibraryRepository.state.collectAsState().value.artists
            val recent = recentChips(MusicSearchHistory.recent.collectAsState().value)
            val starters = remember(libraryArtists) { ladderStarters(libraryArtists) }
            val tiles = remember(libraryArtists) { genreTiles(libraryArtists) }
            val continues = remember(libraryArtists) { continueCards(LadderProgressStore.resumable()) }
            val startArtwork = remember(libraryArtists) { startArt(libraryArtists) }
            if (!startIsEmpty(recent, starters, continues, tiles)) {
                SearchStartView(
                    recent = recent, starters = starters, continues = continues, tiles = tiles,
                    onRecent = { query = it }, onLadder = { ladder = it }, onGenre = { query = it },
                    modifier = Modifier.weight(1f), art = startArtwork,
                )
                return@Column
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp),
        ) {
            when (val shown = results) {
                MusicSearchResults.Idle -> item {
                    MusicMessage("Search for an album or an artist.")
                }
                MusicSearchResults.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }
                MusicSearchResults.Empty -> item {
                    MusicMessage("Nothing found. Check the spelling, or YouTube Music may be unreachable.")
                }
                is MusicSearchResults.Albums -> {
                    item { // fork:music - the first record's ladder offers
                        val first = shown.items.firstOrNull()
                        val ref = first?.let { f -> f.artists.firstOrNull()?.name?.let { LadderAlbumRef(f.title, it, f.year) } }
                        LadderOfferRow(ladderOffers(query, null, ref), onPick = { ladder = it })
                    }
                    items(shown.items) { album ->
                        MusicAlbumRow(album, showArtist = true, onClick = { onAlbumClick(album.browseId) })
                    }
                }
                is MusicSearchResults.Artists -> {
                    item { LadderOfferRow(ladderOffers(query, shown.items.firstOrNull()?.name), onPick = { ladder = it }) } // fork:music
                    items(shown.items) { artist ->
                        MusicArtistRow(artist, onClick = { onArtistClick(artist.browseId) })
                    }
                }
            }
        }
    }
}
