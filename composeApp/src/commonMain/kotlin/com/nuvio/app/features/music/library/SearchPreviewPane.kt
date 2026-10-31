package com.nuvio.app.features.music.library

import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.core.ui.smoothBorder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtist
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.MusicPlayback
import kotlinx.coroutines.delay

/**
 * HTPC search split view (drawing part; the choices are in SearchPreviewModel.kt). Results sit in a left column and
 * this pane, on the right, follows the focused or hovered row:
 *
 *  - **Artist:** picture, name, their top releases (open the album), Add to library, Ladder from here, Open artist page.
 *  - **Album:** cover, title line, track list with lengths, Play, Climb into the sound of it, Open album.
 *
 * Nothing is fetched until focus has rested on a row for [PREVIEW_SETTLE_MS]; answers are kept in a small memo, so going
 * back to a row is instant. A failed fetch shows the basics already known from the result row and still offers Open.
 */
internal sealed interface SearchPreviewTarget {
    val id: String

    data class Artist(val summary: MusicArtistSummary) : SearchPreviewTarget {
        override val id: String get() = summary.browseId
    }

    data class Album(val summary: MusicAlbumSummary) : SearchPreviewTarget {
        override val id: String get() = summary.browseId
    }
}

private sealed interface Fetch<out T> {
    data object Loading : Fetch<Nothing>
    data object Failed : Fetch<Nothing>
    data class Ready<T>(val value: T) : Fetch<T>
}

private val artistMemo = PreviewMemo<String, MusicArtist>()
private val albumMemo = PreviewMemo<String, MusicAlbum>()

/** Wraps a result row: hovering or focusing it makes it the previewed one, and the previewed row is tinted. */
@Composable
internal fun PreviewRowFrame(selected: Boolean, onSelect: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onSelect() }
    Box(
        Modifier
            .hoverable(source)
            .clip(RoundedCornerShape(8.dp))
            .then(if (selected) Modifier.background(colors.accent.copy(alpha = 0.14f)) else Modifier),
    ) {
        content(Modifier.onFocusChanged { if (it.hasFocus) onSelect() })
    }
}

@Composable
internal fun SearchPreviewPane(
    target: SearchPreviewTarget,
    catalog: MusicCatalog,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onLadder: (LadderRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (target) {
            is SearchPreviewTarget.Artist -> ArtistPreview(target.summary, catalog, onOpenArtist, onOpenAlbum, onLadder)
            is SearchPreviewTarget.Album -> AlbumPreview(target.summary, catalog, onOpenAlbum, onLadder)
        }
    }
}

@Composable
private fun ArtistPreview(
    summary: MusicArtistSummary,
    catalog: MusicCatalog,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onLadder: (LadderRequest) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val fetch by produceState<Fetch<MusicArtist>>(Fetch.Loading, summary.browseId) {
        value = Fetch.Loading
        artistMemo[summary.browseId]?.let { value = Fetch.Ready(it); return@produceState }
        delay(PREVIEW_SETTLE_MS)
        val artist = runCatching { catalog.artist(summary.browseId) }.getOrNull()
        value = if (artist != null) Fetch.Ready(artist).also { artistMemo[summary.browseId] = artist } else Fetch.Failed
    }
    val artist = (fetch as? Fetch.Ready)?.value
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        MusicCover(artist?.thumbnailUrl ?: summary.thumbnailUrl, summary.name, size = 160.dp, circle = true)
        Text(summary.name, fontSize = 40.sp, lineHeight = 46.sp, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    MusicAddToLibraryButton(summary.browseId)
    NuvioPrimaryButton(text = "Ladder from here", modifier = Modifier.fillMaxWidth(), height = 52.dp) {
        onLadder(LadderRequest.Artist(summary.name, LadderMode.OutFrom))
    }
    NuvioPrimaryButton(text = "Open artist page", modifier = Modifier.fillMaxWidth(), height = 52.dp) { onOpenArtist(summary.browseId) }
    when (fetch) {
        Fetch.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
        Fetch.Failed -> Text("Couldn't load their releases.", fontSize = 24.sp, color = colors.textMuted)
        is Fetch.Ready -> {
            val releases = previewReleases(artist?.albums.orEmpty())
            if (releases.isEmpty()) {
                Text("No releases listed.", fontSize = 24.sp, color = colors.textMuted)
            } else {
                Text("Top releases", fontSize = 28.sp, color = colors.textPrimary)
                releases.forEach { release ->
                    PreviewLine(
                        title = release.title,
                        detail = listOfNotNull(release.kind, release.year).joinToString(" · "),
                        art = release.thumbnailUrl,
                        onClick = { onOpenAlbum(release.browseId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumPreview(
    summary: MusicAlbumSummary,
    catalog: MusicCatalog,
    onOpenAlbum: (String) -> Unit,
    onLadder: (LadderRequest) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val fetch by produceState<Fetch<MusicAlbum>>(Fetch.Loading, summary.browseId) {
        value = Fetch.Loading
        albumMemo[summary.browseId]?.let { value = Fetch.Ready(it); return@produceState }
        delay(PREVIEW_SETTLE_MS)
        val album = runCatching { catalog.album(summary.browseId) }.getOrNull()
        value = if (album != null) Fetch.Ready(album).also { albumMemo[summary.browseId] = album } else Fetch.Failed
    }
    val album = (fetch as? Fetch.Ready)?.value
    val artistName = summary.artists.joinToString(", ") { it.name }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        MusicCover(album?.thumbnailUrl ?: summary.thumbnailUrl, summary.title, size = 200.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(summary.title, fontSize = 40.sp, lineHeight = 46.sp, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val line = listOf(artistName.takeIf { it.isNotBlank() }, summary.kind, summary.year).filterNotNull().joinToString(" · ")
            if (line.isNotBlank()) Text(line, fontSize = 24.sp, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (album != null) Text(trackSummary(album.tracks), fontSize = 22.sp, color = colors.textMuted)
        }
    }
    val firstPlayable = album?.tracks?.indexOfFirst { it.isAvailable } ?: -1
    if (album != null && firstPlayable >= 0) {
        NuvioPrimaryButton(text = "Play", modifier = Modifier.fillMaxWidth(), height = 52.dp) {
            MusicPlayback.player.play(album, trackIndex = firstPlayable)
        }
    }
    if (artistName.isNotBlank()) {
        NuvioPrimaryButton(text = "Climb into the sound of ${summary.title}", modifier = Modifier.fillMaxWidth(), height = 52.dp) {
            onLadder(LadderRequest.Album(summary.title, summary.artists.first().name, summary.year, LadderMode.Into))
        }
    }
    NuvioPrimaryButton(text = "Open album", modifier = Modifier.fillMaxWidth(), height = 52.dp) { onOpenAlbum(summary.browseId) }
    when (fetch) {
        Fetch.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
        Fetch.Failed -> Text("Couldn't load the track list.", fontSize = 24.sp, color = colors.textMuted)
        is Fetch.Ready -> {
            val tracks = album?.tracks.orEmpty()
            tracks.take(PREVIEW_TRACK_LIMIT).forEach { track ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${track.trackNumber}", fontSize = 22.sp, color = colors.textMuted, modifier = Modifier.width(48.dp))
                    Text(
                        track.title,
                        fontSize = 24.sp,
                        color = if (track.isAvailable) colors.textPrimary else colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(durationText(track.durationSeconds), fontSize = 22.sp, color = colors.textSecondary)
                }
            }
            if (tracks.size > PREVIEW_TRACK_LIMIT) {
                Text("and ${tracks.size - PREVIEW_TRACK_LIMIT} more", fontSize = 22.sp, color = colors.textMuted)
            }
        }
    }
}

/** Most tracks the album preview lists; the album page has the rest. */
private const val PREVIEW_TRACK_LIMIT = 12

@Composable
private fun PreviewLine(title: String, detail: String, art: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsKeyboardFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) colors.surfaceElevated else colors.surfaceCard)
            .then(if (focused) Modifier.smoothBorder(3.dp, colors.accent, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MusicCover(art, title, size = 72.dp)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 26.sp, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, fontSize = 22.sp, color = colors.textSecondary)
        }
    }
}
