package com.nuvio.app.features.music.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.defaultMusicCatalog
import kotlinx.coroutines.launch

/**
 * Library views, phase E1: the Eras view's ruler and header. One bar per era, its height the number of
 * releases and its colour the lead release's palette; the selected era is lit and named in the header.
 * Left/Up and Right/Down step through the eras, Escape goes back to the newest. Below the era header are its covers
 * by artist and Play era. Released / Added re-buckets the same library by release year or by when each artist joined.
 * This file only draws what [buildLibraryEras] produced.
 *
 * Hosted by [MusicLibraryShelf] behind one `// fork:music` switch. [yearAgo] is the shelf's "a year ago this
 * week" strip, passed in as a slot so it stays above the ruler without being copied.
 */
@Composable
internal fun LibraryErasView(
    artists: List<LibraryArtist>,
    pending: List<String>,
    reducedMotion: Boolean,
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    yearAgo: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    // Released places a release by its own year; Added by the year its artist joined the library.
    var axis by rememberSaveable { mutableStateOf(ErasAxis.Released) }
    // A release dated more than a year ahead (a 2045 "[unknown]" single) is undated, not a bar on the ruler.
    val latestYear = remember { (yearOfEpochMs(LibraryClock.nowEpochMs()) ?: MAX_RELEASE_YEAR) + 1 }
    val eras = remember(artists, axis) { buildLibraryEras(artists, axis, maxYear = latestYear) }
    // null means "not chosen": the newest era with something in it. Saved, so coming back lands where the person left.
    var chosen by rememberSaveable { mutableStateOf<Int?>(null) }
    val selected = chosen?.takeIf { it in eras.eras.indices } ?: eras.defaultEraIndex()
    val era = eras.eras.getOrNull(selected)
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    val groups = remember(era, newestFirst) { era?.let { groupEraByArtist(it, newestFirst) }.orEmpty() }
    val playReleases = remember(era) { era?.let { eraPlayReleases(it) }.orEmpty() }
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    // Per era: what Play era is doing, so a second click is not a second fetch and a failure is said once.
    var playing by remember(selected) { mutableStateOf(false) }
    var failed by remember(selected) { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    LazyColumn(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionUp, Key.DirectionRight, Key.DirectionDown -> {
                        if (eras.eras.isEmpty()) return@onPreviewKeyEvent false
                        val back = event.key == Key.DirectionLeft || event.key == Key.DirectionUp
                        chosen = stepEra(eras.eras, selected, if (back) -1 else 1).takeIf { it >= 0 }
                        true
                    }
                    Key.Escape -> {
                        val had = chosen != null
                        chosen = null
                        had
                    }
                    else -> false
                }
            },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (yearAgo != null) item(key = "year-ago") { yearAgo() }
        pending.forEachIndexed { i, status ->
            item(key = "pending:$i") {
                Text("Adding… $status", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        item(key = "axis") {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill("Released", selected = axis == ErasAxis.Released, onClick = { axis = ErasAxis.Released; chosen = null })
                ActionPill("Added", selected = axis == ErasAxis.Added, onClick = { axis = ErasAxis.Added; chosen = null })
            }
        }
        if (eras.eras.isEmpty()) {
            item(key = "no-ruler") {
                Text(
                    if (axis == ErasAxis.Added) {
                        "No artist has a recorded added date yet, so there is no timeline to draw."
                    } else {
                        "Nothing in your library has a release date yet, so there is no timeline to draw."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            item(key = "ruler") {
                Row(
                    Modifier.fillMaxWidth().height(RULER_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    eras.eras.forEach { e ->
                        EraBar(
                            era = e,
                            maxCount = eras.maxReleaseCount,
                            selected = e.index == selected,
                            reducedMotion = reducedMotion,
                            onClick = { chosen = e.index },
                            // An empty band is a gap in the library: kept, but drawn thin.
                            modifier = Modifier.weight(if (e.isEmpty) 0.35f else 1f),
                        )
                    }
                }
            }
            if (era != null) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                eraHeader(era),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                            era.lead?.let { lead ->
                                Text(
                                    "Led by ${lead.release.title} · ${lead.artist.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (!era.isEmpty) {
                            ActionPill(if (newestFirst) "Newest first" else "Oldest first", onClick = { newestFirst = !newestFirst })
                            Spacer(Modifier.width(10.dp))
                            NuvioPrimaryButton(
                                text = when {
                                    playReleases.isEmpty() -> "Nothing here can be played"
                                    failed -> "Couldn't open this era"
                                    playing -> "Opening…"
                                    else -> "Play era"
                                },
                                modifier = Modifier.width(if (playReleases.isEmpty() || failed) 220.dp else 130.dp),
                                enabled = playReleases.isNotEmpty() && !playing,
                                height = 40.dp,
                                onClick = {
                                    playing = true
                                    failed = false
                                    scope.launch {
                                        // The albums of every artist in the era, mixed: the shelf's Top 5 shuffle, across artists.
                                        failed = !playTopAlbums(catalog, playReleases, shuffled = true)
                                        playing = false
                                    }
                                },
                            )
                        }
                    }
                }
                if (!era.isEmpty) item(key = "summary") { EraSummary(era) }
                items(groups, key = { "era${era.index}:${it.artist.id}" }) { group ->
                    EraArtistRow(group, reducedMotion, onArtistClick, onAlbumClick)
                }
                eraNeighbour(eras, era)?.let { near ->
                    item(key = "also") {
                        Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Also from ${near.era.years}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textSecondary,
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(near.entries, key = { "also:${it.artist.id}:${it.release.key}" }) { entry ->
                                    CoverTile(
                                        tile = ShelfTile(entry.release, rank = null, featured = false, pinned = entry.release.key in entry.artist.pinnedTopKeys),
                                        reducedMotion = reducedMotion,
                                        dimmed = !entry.isPlayable,
                                        onClick = {
                                            val id = entry.release.ytBrowseId
                                            if (id != null) onAlbumClick(id) else onArtistClick(entry.artist.id)
                                        },
                                        onTogglePin = { MusicLibraryRepository.toggleTopPin(entry.artist.id, entry.release.key) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (eras.undated.isNotEmpty()) {
            item(key = "undated") {
                val n = eras.undated.size
                Text(
                    "$n undated release${if (n == 1) "" else "s"}" +
                        if (axis == ErasAxis.Added) " (their artists have no added date)" else " (no usable date, or dated more than a year ahead)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private val RULER_HEIGHT = 112.dp

/** The era as a banner: a mosaic of its covers, its top genres and the artists with the most releases in it. */
@Composable
private fun EraSummary(era: LibraryEra) {
    val colors = MaterialTheme.nuvio.colors
    val mosaic = (listOfNotNull(era.lead) + era.entries.asReversed()).distinctBy { it.artist.id }.take(4)
    val genres = eraTopGenres(era)
    val artists = eraTopArtists(era)
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            mosaic.forEach { ReleaseCover(it.release, Modifier.size(96.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (genres.isNotEmpty()) {
                Text(
                    genres.joinToString(" · ") { g -> g.replaceFirstChar { it.uppercase() } },
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (artists.isNotEmpty()) {
                Text(
                    "Most from " + artists.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One era on the ruler: its release count over a bar, the first year under it. */
@Composable
private fun EraBar(
    era: LibraryEra,
    maxCount: Int,
    selected: Boolean,
    reducedMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val tint = era.lead?.release?.palette?.let { Color(it.accent) } ?: colors.accent
    val glow by animateFloatAsState(if (selected) 1f else 0.45f, if (reducedMotion) snap() else tween(200), label = "eraBar")
    val fraction = if (maxCount == 0) 0f else era.releaseCount / maxCount.toFloat()
    val barHeight = if (era.isEmpty) 2.dp else (8f + 56f * fraction).dp
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
    ) {
        if (!era.isEmpty) {
            Text("${era.releaseCount}", fontSize = 10.sp, color = if (selected) colors.textPrimary else colors.textMuted, maxLines = 1)
        }
        Column(
            Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(4.dp)).background(tint.copy(alpha = glow)),
        ) {}
        Text(
            "${era.fromYear}",
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.textPrimary else colors.textMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** One artist's covers in the selected era: the name opens the room, a cover opens its album. A release that cannot be played here is dimmed and opens the room instead. */
@Composable
private fun EraArtistRow(
    group: EraArtistGroup,
    reducedMotion: Boolean,
    onArtistClick: (artistId: String) -> Unit,
    onAlbumClick: (browseId: String) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val artist = group.artist
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { onArtistClick(artist.id) }.padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MusicCover(artist.art.thumb, artist.name, size = 32.dp, circle = true)
            Text(
                artist.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(group.entries, key = { it.release.key }) { entry ->
                CoverTile(
                    tile = ShelfTile(entry.release, rank = null, featured = false, pinned = entry.release.key in artist.pinnedTopKeys),
                    reducedMotion = reducedMotion,
                    dimmed = !entry.isPlayable,
                    onClick = {
                        val id = entry.release.ytBrowseId
                        if (id != null) onAlbumClick(id) else onArtistClick(artist.id)
                    },
                    onTogglePin = { MusicLibraryRepository.toggleTopPin(artist.id, entry.release.key) },
                )
            }
        }
    }
}
