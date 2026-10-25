package com.nuvio.app.features.music.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.NuvioAsyncImage
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
 * Phase 4: the Library home as a shelf. One shelf per artist; each release is a spine whose width
 * follows its track count. Spines use TheAudioDB spine art when there is some, else a generated spine
 * (cover thumb, vertical title, the cover's colour). Hovering a shelf tints the glow and the ledge with
 * that artist's accent. Clicking a spine opens the album; the avatar, name or "+N more" open the room.
 * Only the Shelf view exists; Constellation and Eras are shown as coming soon.
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

    val focusAccent by animateColorAsState(
        state.artists.firstOrNull { it.id == focusedId }?.palette?.let { Color(it.accent) } ?: colors.accent,
        tween(400),
    )
    val yearAgo = remember(state.artists) {
        val (y, m, d) = civilDate(LibraryClock.nowEpochMs())
        pickYearAgo(state.artists, y, m, d)
    }
    val totalReleases = remember(state.artists) { state.artists.sumOf { countReleaseCards(it.releases) } }

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
                // Constellation and Eras are not built yet; shown dimmed so the switch matches the design.
                ActionPill("Shelf", selected = true, onClick = {})
                ActionPill("Constellation", enabled = false, onClick = {})
                ActionPill("Eras", enabled = false, onClick = {})
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
                    items(state.artists, key = { it.id }) { artist ->
                        ShelfArtistRow(
                            artist = artist,
                            status = state.working[artist.id]?.status,
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
private val SPINE_HEIGHT = 126.dp

@Composable
private fun ShelfArtistRow(
    artist: LibraryArtist,
    status: String?,
    onFocus: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenRelease: (LibraryRelease) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val context = LocalPlatformContext.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onFocus() }

    // Colours are derived in the background and cached; until then the app accent stands in.
    val identity = remember(artist) { artist.identity() }
    LaunchedEffect(identity.artistId, identity.needsPalette) { MusicPaletteResolver.ensure(context, identity) }
    val accent = artist.palette?.let { Color(it.accent) } ?: colors.accent
    val ledge by animateColorAsState(if (hovered) accent else Color.White.copy(alpha = 0.12f), tween(200))

    Row(
        Modifier.fillMaxWidth().height(154.dp).hoverable(source),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(
            Modifier.width(SHELF_HEADER_WIDTH).padding(bottom = 26.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenArtist),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.border(3.dp, accent, CircleShape).padding(3.dp)) {
                MusicCover(artist.art.thumb, artist.name, size = 58.dp, circle = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(artist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val count = remember(artist.releases) { countReleaseCards(artist.releases) }
                Text(
                    status ?: "$count release${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status != null) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).height(150.dp)) {
            val available = (maxWidth.value.toInt() - 24).coerceAtLeast(0)
            val row = remember(artist.releases, artist.palette, available) { buildShelfRow(artist, available) }
            // Only the covers on this shelf get a palette (for the spine colour).
            val keys = remember(row) { row.spines.map { it.release.key }.toSet() }
            val missing = row.spines.count { it.release.needsPalette }
            LaunchedEffect(artist.id, missing) { ReleasePaletteResolver.ensure(context, artist, keys = keys, limit = 40) }

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(SPINE_GAP_DP.dp),
                ) {
                    row.spines.forEach { spine -> SpineView(spine, onClick = { onOpenRelease(spine.release) }) }
                    if (row.hidden > 0) {
                        Text(
                            "+${row.hidden} more",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(start = 8.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable(onClick = onOpenArtist)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(5.dp).background(ledge))
            }
        }
    }
}

/** Rotates a one-line text to read bottom to top and swaps its measured width and height. */
private fun Modifier.vertical(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight.coerceAtLeast(0)))
    layout(placeable.height, placeable.width) {
        placeable.place(
            x = -(placeable.width / 2 - placeable.height / 2),
            y = -(placeable.height / 2 - placeable.width / 2),
        )
    }
}

@Composable
private fun SpineView(spine: ShelfSpine, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lift by animateDpAsState(if (hovered) (-16).dp else 0.dp, tween(150))
    // TheAudioDB spine art when it loads; otherwise (or on a load error) the generated spine.
    var useArt by remember(spine.release.key, spine.spineArtUrl) { mutableStateOf(spine.spineArtUrl != null) }
    Box(
        Modifier
            .offset { IntOffset(0, lift.roundToPx()) }
            .width(spine.widthDp.dp)
            .height(SPINE_HEIGHT)
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .hoverable(source)
            .clickable(onClick = onClick),
    ) {
        if (useArt) {
            NuvioAsyncImage(
                model = spine.spineArtUrl,
                contentDescription = spine.release.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { useArt = false },
            )
        } else {
            Column(
                Modifier.fillMaxSize().background(Color(spine.bg)).padding(start = 3.dp, end = 3.dp, top = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size((spine.widthDp - 6).dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.14f))) {
                    spine.thumbUrl?.let {
                        NuvioAsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        spine.release.title,
                        color = Color(spine.fg),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.SansSerif,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.vertical().rotate(-90f),
                    )
                }
            }
        }
    }
}

// endregion
