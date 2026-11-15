package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's album page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.EssentialSong
import com.nuvio.app.features.music.library.LibraryRelease
import com.nuvio.app.features.music.library.musicKeyFocusRing
import com.nuvio.app.features.music.library.songKey

// fork:essentials - the album page's right-hand column: the artist's best, in place of "More from" (which was only the
// latest releases). Same width as the object column on the left and padded as its mirror, so the record between them is
// centred on the window.

/** An album in the Best albums row: [rank] from 1, [here] when it is the record the page is showing. */
internal data class EssentialAlbum(val release: LibraryRelease, val rank: Int, val here: Boolean)

@Composable
private fun EssentialsLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.nuvio.colors.textMuted,
    )
}

@Composable
internal fun EssentialAlbumTile(tile: EssentialAlbum, size: Dp, onAlbumClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val release = tile.release
    val shape = RoundedCornerShape(10.dp)
    val target = release.ytBrowseId
    Column(
        Modifier.width(size).clip(shape).musicKeyFocusRing(shape)
            .clickable(enabled = !tile.here && target != null) { target?.let(onAlbumClick) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            MusicCover(
                release.art.cover,
                release.title,
                size = size,
                modifier = if (tile.here) Modifier.border(2.dp, colors.accent, RoundedCornerShape(6.dp)) else Modifier,
            )
            Text(
                tile.rank.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Text(release.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(release.year.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
    }
}

/**
 * fork:lenses - the Best of tab's middle: the artist's best songs, ranked by listeners, then their best albums as covers,
 * in the album page's own quiet rows (no cards). A song on this record plays here; another opens its album.
 */
@Composable
internal fun BestOfCentre(
    artist: String,
    songs: List<EssentialSong>,
    hereKeys: Set<String>,
    albums: List<EssentialAlbum>,
    onSongClick: (EssentialSong) -> Unit,
    onAlbumClick: (String) -> Unit,
    maxWidth: Dp,
    bottom: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    androidx.compose.foundation.lazy.LazyColumn(
        modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (songs.isEmpty() && albums.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing is ranked for $artist yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 40.dp),
                )
            }
        }
        if (songs.isNotEmpty()) {
            item(key = "songs-label") {
                Box(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp)) { EssentialsLabel("BEST SONGS") }
            }
            // Three to a row, in rank order across then down; a short last row keeps its cells the same width.
            val rows = songs.withIndex().chunked(HITS_GRID_COLUMNS)
            items(rows.size, key = { "songs-$it" }) { r ->
                Row(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows[r].forEach { (i, song) ->
                        BestSongCell(i + 1, song, here = songKey(song.title) in hereKeys, onClick = { onSongClick(song) }, modifier = Modifier.weight(1f))
                    }
                    repeat(HITS_GRID_COLUMNS - rows[r].size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "albums") {
                BoxWithConstraints(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 8.dp).padding(top = 28.dp)) {
                    // Five covers to the row, never bigger than 160 dp: a function of the column's width only.
                    val tile = ((this.maxWidth - 14.dp * 4) / 5).coerceAtMost(160.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        EssentialsLabel("BEST ALBUMS")
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            albums.forEach { EssentialAlbumTile(it, tile, onAlbumClick) }
                        }
                    }
                }
            }
        }
    }
}

private const val HITS_GRID_COLUMNS = 3

/** One best song in the Hits grid: rank, cover, title and album; the title in the accent when the song is on this album. */
@Composable
private fun BestSongCell(rank: Int, song: EssentialSong, here: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(10.dp)
    val actionable = here || song.release?.ytBrowseId != null
    Row(
        modifier.height(68.dp).clip(shape).musicKeyFocusRing(shape)
            .clickable(enabled = actionable, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(rank.toString(), Modifier.width(18.dp), style = MaterialTheme.typography.bodyMedium, color = colors.textMuted, textAlign = TextAlign.Center)
        MusicCover(song.release?.art?.cover, description = null, size = 52.dp)
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, color = if (here) colors.accent else colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = if (here) "On this album" else song.albumTitle
            if (line.isNotBlank()) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = if (here) colors.accent.copy(alpha = 0.8f) else colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
