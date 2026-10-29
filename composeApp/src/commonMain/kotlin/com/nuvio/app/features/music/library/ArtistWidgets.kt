package com.nuvio.app.features.music.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio

/**
 * HTPC phase 8: the artist page's widgets, sized for a TV.
 *
 *  - [ArtistStatTiles]: fans, releases, playable and rating as large tiles (left rail, replacing the small stat row).
 *  - [ArtistWidgets]: the artist's top albums (the five ranked or starred ones) as large cards that open the release,
 *    a short bio that opens out on focus or click, and the "found through" line. Placed above the "Where next?" strip.
 */

/** The releases the page calls the artist's top ones, in rank order: [LibraryArtist.topAlbumKeys] that still exist, at most [limit]. */
internal fun artistTopReleases(artist: LibraryArtist, limit: Int = 5): List<LibraryRelease> =
    artist.topAlbumKeys.mapNotNull { key -> artist.releases.firstOrNull { it.key == key } }.distinctBy { it.key }.take(limit)

/** "8.3 / 10" style rating text, or null with no rating. */
internal fun ratingText(rating: Double?): String? =
    rating?.takeIf { it > 0.0 }?.let { "${(kotlin.math.round(it * 10) / 10.0)}" }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ArtistStatTiles(artist: LibraryArtist, totalCount: Int, playableCount: Int, modifier: Modifier = Modifier) {
    val tiles = buildList {
        artist.fans?.let { add(formatCount(it) to "fans") }
        add("$totalCount" to "releases")
        add("$playableCount" to "playable")
        ratingText(artist.rating)?.let { add("★ $it" to "rating") }
    }
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val colors = MaterialTheme.nuvio.colors
        tiles.forEach { (value, label) ->
            Column(
                Modifier.width(96.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceCard).padding(vertical = 12.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(value, fontSize = 30.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, fontSize = 20.sp, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun ArtistWidgets(
    artist: LibraryArtist,
    onOpen: (LibraryRelease) -> Unit,
    modifier: Modifier = Modifier,
    /** False where the page shows the biography itself (the artist room's lane). */
    showBio: Boolean = true,
) {
    val colors = MaterialTheme.nuvio.colors
    val top = remember(artist.topAlbumKeys, artist.releases) { artistTopReleases(artist) }
    val bio = if (showBio) artist.biography?.trim().orEmpty() else ""
    var bioOpen by rememberSaveable(artist.id) { mutableStateOf(false) }
    if (top.isEmpty() && bio.isEmpty() && artist.foundVia == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (top.isNotEmpty()) {
            Text("Top albums", fontSize = 28.sp, color = colors.textPrimary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 6.dp)) {
                items(top, key = { it.key }) { release -> TopAlbumCard(release, onOpen = { onOpen(release) }) }
            }
        }
        if (bio.isNotEmpty()) {
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surfaceCard)
                    .then(if (focused) Modifier.border(3.dp, colors.accent, shape) else Modifier)
                    .focusable(interactionSource = source)
                    .clickable(interactionSource = source, indication = null) { bioOpen = !bioOpen }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    bio,
                    fontSize = 24.sp,
                    lineHeight = 32.sp,
                    color = colors.textSecondary,
                    maxLines = if (bioOpen || focused) 14 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                artist.biographySource?.let { Text("From $it", fontSize = 20.sp, color = colors.textMuted) }
            }
        }
        artist.foundVia?.let { Text("Found through $it", fontSize = 22.sp, color = colors.textMuted) }
    }
}

@Composable
private fun TopAlbumCard(release: LibraryRelease, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f)
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(170.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) colors.surfaceElevated else colors.surfaceCard)
            .then(if (focused) Modifier.border(3.dp, colors.accent, shape) else Modifier)
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ReleaseCover(release, Modifier.size(150.dp))
        Text(release.title, fontSize = 22.sp, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        release.year?.let { Text(it, fontSize = 20.sp, color = colors.textSecondary) }
    }
}
