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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicMessage

/**
 * The Library's Shelf: the collection in three panels (fork:shelf, ShelfBrowser.kt). The Music tab's header holds the
 * Library's view switch (Shelf or Playlists) and its filter box feeds [filterQuery]. The row-per-artist shelf, its card row
 * (a year ago this week, starred, added recently, continue your ladder) and the Constellation and Eras views it hosted were
 * replaced on Nov 7, 2026.
 */
@Composable
internal fun MusicLibraryShelf(
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Kept for the hosts' calls; the Shelf no longer opens ladders. */
    @Suppress("UNUSED_PARAMETER") onOpenLadder: (LadderRequest) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") view: LibraryView = LibraryView.Shelf,
    filterQuery: String = "",
) {
    val colors = MaterialTheme.nuvio.colors
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            state.errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (state.artists.isEmpty() && state.working.isEmpty()) {
                MusicMessage("Open an artist from Search and choose Add to library. Every album, single and EP comes with it, with artwork and details.")
            } else {
                ShelfBrowser(
                    artists = state.artists,
                    pending = state.working.filterKeys { id -> state.artists.none { it.id == id } }.values.map { it.status },
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
