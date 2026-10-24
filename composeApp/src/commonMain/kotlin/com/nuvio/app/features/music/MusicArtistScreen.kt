package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.nuvio

/** An artist: their picture and name, and the albums YouTube Music lists for them. A tap opens the album. */
@Composable
fun MusicArtistScreen(
    browseId: String,
    onBack: () -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    catalog: MusicCatalog = remember { defaultMusicCatalog() },
) {
    val colors = MaterialTheme.nuvio.colors
    var attempt by remember { mutableStateOf(0) }
    val load = rememberMusicLoad(browseId, attempt) { catalog.artist(browseId) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 12.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp)) { NuvioBackButton(onClick = onBack) }
        when (load) {
            MusicLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            MusicLoad.Failed -> MusicMessage(
                text = "Couldn't load this artist.",
                actionLabel = "Try again",
                onAction = { attempt++ },
            )
            is MusicLoad.Loaded -> ArtistContent(load.value, onAlbumClick)
        }
    }
}

@Composable
private fun ArtistContent(artist: MusicArtist, onAlbumClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MusicCover(artist.thumbnailUrl, artist.name, size = 112.dp, circle = true)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            Text(
                text = if (artist.albums.isEmpty()) "No albums listed" else "Albums",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        items(artist.albums) { album ->
            // The artist is already the page, so the row leaves the name out.
            MusicAlbumRow(album, showArtist = false, onClick = { onAlbumClick(album.browseId) })
        }
    }
}
