package com.nuvio.app.features.music.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicMessage

/**
 * The Library's artist views ([LibraryView] other than Playlists): the classic Shelf ([ClassicLibraryShelf]), Crates
 * ([ShelfBrowser]), Constellation ([ConstellationView]) and Eras ([LibraryErasView]). The Music tab's header holds the
 * view switch and its filter box feeds [filterQuery]: Shelf, Crates and Eras narrow to it, the Constellation dims what
 * does not match instead.
 */
@Composable
internal fun MusicLibraryShelf(
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** The Shelf's "Continue your ladder" card and the Constellation's ladder buttons open a ladder in the host. */
    onOpenLadder: (LadderRequest) -> Unit = {},
    view: LibraryView = LibraryView.Shelf,
    filterQuery: String = "",
) {
    if (view == LibraryView.Shelf) {
        ClassicLibraryShelf(onArtistClick, onAlbumClick, modifier, onOpenLadder, filterQuery)
        return
    }
    val colors = MaterialTheme.nuvio.colors
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val pending = state.working.filterKeys { id -> state.artists.none { it.id == id } }.values.map { it.status }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            state.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (state.artists.isEmpty() && state.working.isEmpty()) {
                MusicMessage("Open an artist from Search and choose Add to library. Every album, single and EP comes with it, with artwork and details.")
            } else when (view) {
                LibraryView.Constellation -> ConstellationView(
                    artists = state.artists,
                    pending = pending,
                    onArtistClick = onArtistClick,
                    query = filterQuery,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onOpenLadder = onOpenLadder,
                )
                LibraryView.Eras -> {
                    val shown = remember(state.artists, filterQuery) { state.artists.filter { it.matchesLibraryFilter(filterQuery) } }
                    val yearAgo = remember(state.artists) {
                        val (y, m, d) = civilDate(LibraryClock.nowEpochMs())
                        pickYearAgo(state.artists, y, m, d)
                    }
                    val totalReleases = remember(state.artists) { state.artists.sumOf { countReleaseCards(it.releases) } }
                    LibraryErasView(
                        artists = shown,
                        pending = pending,
                        reducedMotion = state.settings.reducedMotion,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        yearAgo = yearAgo?.let { pick -> { YearAgoStrip(pick, state.artists.size, totalReleases) } },
                    )
                }
                else -> ShelfBrowser(
                    artists = state.artists,
                    pending = pending,
                    filterQuery = filterQuery,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
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
