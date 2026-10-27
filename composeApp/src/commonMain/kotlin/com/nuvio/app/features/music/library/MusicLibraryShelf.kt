package com.nuvio.app.features.music.library

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * left, then a rail of release covers. Only releases that can be played are shown; the artist's top
 * five come first as larger tiles (numbered when they are really ranked or starred), then everything
 * else oldest to newest. Hovering a cover slides its disc out and shows a star that pins the album to
 * the top five. A star by the artist's name floats it to the top of the shelf. Hovering a row tints
 * the glow with that artist's accent. Clicking a cover opens the album; the avatar, name or "All" open the room.
 * The header switches views: Shelf (this file), Constellation ([ConstellationView]) and Eras ([LibraryErasView]).
 */
@Composable
fun MusicLibraryShelf(
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    var showSources by remember { mutableStateOf(false) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var view by rememberSaveable { mutableStateOf(LibraryView.Shelf) } // fork:music - which of the Library's views is showing

    val focusAccent by animateColorAsState(
        state.artists.firstOrNull { it.id == focusedId }?.palette?.let { Color(it.accent) } ?: colors.accent,
        tween(400),
    )
    val yearAgo = remember(state.artists) {
        val (y, m, d) = civilDate(LibraryClock.nowEpochMs())
        pickYearAgo(state.artists, y, m, d)
    }
    val totalReleases = remember(state.artists) { state.artists.sumOf { countReleaseCards(it.releases) } }
    // Starred artists first; the sort is stable, so each group stays alphabetical.
    val ordered = remember(state.artists) { state.artists.sortedByDescending { it.favourite } }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(340.dp).background(
                Brush.radialGradient(
                    listOf(focusAccent.copy(alpha = 0.2f), Color.Transparent),
                    center = Offset(300f, 0f),
                    radius = 900f,
                ),
            ),
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    when (val n = state.artists.size) {
                        0 -> "No artists yet"
                        1 -> "1 artist"
                        else -> "$n artists"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                ActionPill("Shelf", selected = view == LibraryView.Shelf, onClick = { view = LibraryView.Shelf }) // fork:music
                ActionPill("Constellation", selected = view == LibraryView.Constellation, onClick = { view = LibraryView.Constellation }) // fork:music
                ActionPill("Eras", selected = view == LibraryView.Eras, onClick = { view = LibraryView.Eras }) // fork:music
                LibraryIconAction(Icons.Rounded.Refresh, "Refresh all artists", enabled = state.artists.isNotEmpty()) {
                    MusicLibraryRepository.refreshAll()
                }
                LibraryIconAction(Icons.Rounded.Settings, "Artwork sources") { showSources = true }
            }
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
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (view == LibraryView.Eras) { // fork:music
                LibraryErasView(
                    artists = state.artists,
                    pending = state.working.filterKeys { id -> state.artists.none { it.id == id } }.values.map { it.status },
                    reducedMotion = state.settings.reducedMotion,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    yearAgo = yearAgo?.let { pick -> { YearAgoStrip(pick, state.artists.size, totalReleases) } },
                )
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (yearAgo != null) {
                        item(key = "year-ago") { YearAgoStrip(yearAgo, state.artists.size, totalReleases) }
                    }
                    val pending = state.working.filterKeys { id -> state.artists.none { it.id == id } }
                    items(pending.entries.toList(), key = { "pending:${it.key}" }) { (_, progress) ->
                        Text("Adding… ${progress.status}", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    items(ordered, key = { it.id }) { artist ->
                        ShelfArtistRow(
                            artist = artist,
                            status = state.working[artist.id]?.status,
                            reducedMotion = state.settings.reducedMotion,
                            onFocus = { focusedId = artist.id },
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

    if (showSources) {
        ArtworkSourcesDialog(
            settings = state.settings,
            onSave = {
                MusicLibraryRepository.updateSettings(it)
                showSources = false
            },
            onDismiss = { showSources = false },
        )
    }
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
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).padding(12.dp),
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

// region shelf

private val SHELF_HEADER_WIDTH = 250.dp

@Composable
private fun ShelfArtistRow(
    artist: LibraryArtist,
    status: String?,
    reducedMotion: Boolean,
    onFocus: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenRelease: (LibraryRelease) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onFocus() }

    // Colours are derived in the background and cached; until then the app accent stands in.
    val identity = remember(artist) { artist.identity() }
    LaunchedEffect(identity.artistId, identity.needsPalette) { MusicPaletteResolver.ensure(context, identity) }
    val accent = artist.palette?.let { Color(it.accent) } ?: colors.accent
    val wash by animateColorAsState(if (hovered) accent.copy(alpha = 0.07f) else Color.Transparent, tween(200))

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

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(wash).hoverable(source).padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.width(SHELF_HEADER_WIDTH).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.clip(CircleShape).clickable(onClick = onOpenArtist)) {
                    Box(Modifier.border(3.dp, accent, CircleShape).padding(3.dp)) {
                        MusicCover(artist.art.thumb, artist.name, size = 58.dp, circle = true)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            artist.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenArtist),
                        )
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = if (artist.favourite) "Remove from favourites" else "Add to favourites",
                            tint = if (artist.favourite) GOLD else Color.White.copy(alpha = if (hovered) 0.4f else 0.18f),
                            modifier = Modifier.size(20.dp).clip(CircleShape).clickable {
                                MusicLibraryRepository.setFavourite(artist.id, !artist.favourite)
                            },
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
                    )
                }
            }
            if (row.top.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickPlayChip(Icons.Rounded.PlayArrow, row.topLabel, filled = true) { playTop(shuffled = false) }
                    QuickPlayChip(Icons.Rounded.Shuffle, null, filled = false) { playTop(shuffled = true) }
                }
            }
        }
        if (row.tiles.isEmpty()) {
            Text(
                if (status != null) "Reading releases…" else "Nothing here is on YouTube Music yet. Open the room to see every release.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.weight(1f).padding(top = 24.dp).clickable(onClick = onOpenArtist),
            )
        } else {
            LazyRow(
                Modifier.weight(1f),
                contentPadding = PaddingValues(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(row.top, key = { it.release.key }) { tile ->
                    CoverTile(
                        tile = tile,
                        reducedMotion = reducedMotion,
                        onClick = { onOpenRelease(tile.release) },
                        onTogglePin = { MusicLibraryRepository.toggleTopPin(artist.id, tile.release.key) },
                    )
                }
                if (row.top.isNotEmpty() && row.rest.isNotEmpty()) {
                    item(key = "divider") {
                        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(FEATURED_SIZE).background(Color.White.copy(alpha = 0.14f)))
                    }
                }
                items(row.rest, key = { it.release.key }) { tile ->
                    CoverTile(
                        tile = tile,
                        reducedMotion = reducedMotion,
                        onClick = { onOpenRelease(tile.release) },
                        onTogglePin = { MusicLibraryRepository.toggleTopPin(artist.id, tile.release.key) },
                    )
                }
                item(key = "all") {
                    Box(
                        Modifier.size(TILE_SIZE).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)).clickable(onClick = onOpenArtist),
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
            .background(if (filled) colors.accent else Color.White.copy(alpha = 0.1f))
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
