package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's album page redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
internal fun EssentialsColumn(
    artist: String,
    songs: List<EssentialSong>,
    hereKeys: Set<String>,
    albums: List<EssentialAlbum>,
    startHere: String?,
    onSongClick: (EssentialSong) -> Unit,
    onAlbumClick: (String) -> Unit,
    /** The size of an album tile: four of them and their gaps fill the column's content (108 dp in the widest column). */
    tile: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 32.dp, top = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Essentials", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Text("$artist, best first. Ranked by listeners.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        if (songs.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EssentialsLabel("BEST SONGS")
                songs.forEachIndexed { index, song ->
                    EssentialSongRow(index + 1, song, here = songKey(song.title) in hereKeys, onClick = { onSongClick(song) })
                }
            }
        }
        if (albums.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EssentialsLabel("BEST ALBUMS")
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    albums.forEach { EssentialAlbumTile(it, tile, onAlbumClick) }
                }
            }
        }
        if (startHere != null) {
            AlbumGlassCard {
                BriefHeader("START HERE", ai = true)
                Text(startHere, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                Text("Written by AI, may contain mistakes", style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
    }
}

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
private fun EssentialSongRow(rank: Int, song: EssentialSong, here: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(12.dp)
    // A song opens the album it is on; one on this very record starts playing here. Nothing to open: not a button.
    val actionable = here || song.release?.ytBrowseId != null
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(shape)
            .background(Color.White.copy(alpha = if (here) 0.08f else 0.03f))
            .musicKeyFocusRing(shape)
            .clickable(enabled = actionable, onClick = onClick)
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            rank.toString(),
            modifier = Modifier.width(22.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textMuted,
        )
        MusicCover(song.release?.art?.cover, description = null, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (song.albumTitle.isNotBlank()) {
                Text(song.albumTitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (here) {
            Text(
                "ON THIS ALBUM",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = colors.accent,
                maxLines = 1,
                modifier = Modifier.border(1.dp, colors.accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun EssentialAlbumTile(tile: EssentialAlbum, size: Dp, onAlbumClick: (String) -> Unit) {
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
