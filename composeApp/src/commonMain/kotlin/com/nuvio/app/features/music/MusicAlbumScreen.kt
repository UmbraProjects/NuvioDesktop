package com.nuvio.app.features.music

import com.nuvio.app.features.music.nebula.NebulaMusicSource
import com.nuvio.app.features.music.nebula.NebulaSourceRow
import com.nuvio.app.features.music.nebula.rememberNebulaAlbumController
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import com.nuvio.app.features.music.library.LibraryRelease
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.features.details.components.DetailIconAction
import com.nuvio.app.features.music.nebula.MusicSource
import com.nuvio.app.features.music.nebula.NebulaOffer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.LibraryAlbumInfo
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.rememberLibraryAlbumInfo

/**
 * An album: cover, a Play/Shuffle pair, the Audio/Video choice, the track list, and more from the artist.
 *
 * This is where playback starts. Play and Shuffle hand the album to [player]; a tap on a track
 * jumps inside the queue when this album is already loaded, and starts the album at that track
 * when it is not (see [musicTrackTap]). Flipping Audio/Video while this album plays switches the
 * same song at the same position; otherwise it only sets the mode the next Play starts in.
 */
@Composable
fun MusicAlbumScreen(
    browseId: String,
    onBack: () -> Unit,
    onArtistClick: (browseId: String) -> Unit,
    modifier: Modifier = Modifier,
    onAlbumClick: (browseId: String) -> Unit = {},
    catalog: MusicCatalog = remember { defaultMusicCatalog() },
    player: MusicPlayer = MusicPlayback.player,
) {
    val colors = MaterialTheme.nuvio.colors
    var attempt by remember { mutableStateOf(0) }
    val load = rememberMusicLoad(browseId, attempt) { catalog.album(browseId)?.let { libraryEdition(catalog, it) } }

    Box(modifier.fillMaxSize().background(colors.background)) {
    // The cover, blurred, as the page's atmosphere (it does not move, so reduced motion needs nothing).
    if (load is MusicLoad.Loaded) AlbumAmbientBackdrop(load.value.thumbnailUrl)
    // The backdrop above runs to the window's bottom edge; only the content makes room for the player bar.
    Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = LocalMusicBarInset.current)) {
        Box(Modifier.padding(horizontal = 16.dp)) { NuvioBackButton(onClick = onBack) }
        when (load) {
            MusicLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            MusicLoad.Failed -> MusicMessage(
                text = "Couldn't load this album.",
                actionLabel = "Try again",
                onAction = { attempt++ },
            )
            is MusicLoad.Loaded -> AlbumContent(load.value, onArtistClick, onAlbumClick, player)
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumContent(
    album: MusicAlbum,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    player: MusicPlayer,
) {
    val colors = MaterialTheme.nuvio.colors
    val state by player.state.collectAsStateWithLifecycle()
    var chosenMode by rememberSaveable(album.browseId) { mutableStateOf(player.state.value.mode) }
    var descriptionOpen by rememberSaveable(album.browseId) { mutableStateOf(false) }
    var flipped by rememberSaveable(album.browseId) { mutableStateOf(false) }
    // null follows playback (the disc comes out while the album plays); a tap on the button takes over.
    var discChoice by rememberSaveable(album.browseId) { mutableStateOf<Boolean?>(null) }
    val reducedMotion = rememberMusicReducedMotion()
    // Looks the album's artist up in the background (not saved) so an album opened from Search gets
    // the same label, mood, CD art and palette as one from the library. A saved artist is left alone.
    val albumArtistId = albumArtistToPreview(album)
    LaunchedEffect(albumArtistId) {
        albumArtistId?.let { MusicLibraryRepository.openPreview(it) }
    }
    // What the music library knows about this album: set when it is a release of a saved or previewed artist.
    val info = rememberLibraryAlbumInfo(album.browseId)

    val loaded = state.holds(album)
    // fork:nebula - null unless a music addon link is set; then the album can be played from the addon.
    var nebulaRetry by remember(album.browseId) { mutableStateOf(0) }
    val nebula = rememberNebulaAlbumController(album, player, nebulaRetry)
    val playingNebula = state.track?.let(NebulaMusicSource::owns) == true
    val wantNebula = nebula?.active == true
    // The source the person picked is not the one this album is playing from: Play starts it over in the chosen one.
    val startsFresh = !loaded || playingNebula != wantNebula
    // Which tracks can play depends on the source: a track YouTube Music greys out may exist on the addon, and the reverse.
    val view = if (wantNebula) nebula?.preparedAlbum ?: album else album
    fun startAlbum(trackIndex: Int, mode: MusicPlayMode, shuffled: Boolean) {
        if (wantNebula) nebula?.play(trackIndex, mode, shuffled)
        else player.play(album, trackIndex = trackIndex, mode = mode, shuffled = shuffled)
    }
    // While this album plays, the player's mode is the truth: a failed switch to video leaves it on audio.
    val mode = if (loaded) state.mode else chosenMode

    val playing = loaded && state.isPlaying
    val discOut = discChoice ?: playing
    // Board 3: the object (case, disc and its two toggles) on the left, everything about the record on the right.
    val objectPanel: @Composable (Dp) -> Unit = { caseSize ->
        AlbumObjectPanel(
            album = album,
            info = info,
            flipped = flipped,
            discOut = discOut,
            discSpinning = discOut && playing,
            reducedMotion = reducedMotion,
            caseSize = caseSize,
            onFlip = { flipped = !flipped },
            onDisc = { out -> discChoice = out },
        )
    }
    val facts: @Composable () -> Unit = {
        AlbumFacts(album = album, info = info, onArtistClick = onArtistClick)
        info?.release?.let { release ->
            // Liner notes: genre chips with the mood in the accent, and the description.
            AlbumLinerNotes(
                notes = buildLinerNotes(release, album.year),
                descriptionOpen = descriptionOpen,
                onToggleDescription = { descriptionOpen = !descriptionOpen },
            )
        }
        val canPlay = view.tracks.any { it.isAvailable }
        val addonReady = nebula?.offer is NebulaOffer.Available
        // The artist page's round buttons, each named by its tooltip: Play / Pause, Shuffle, Audio, Video and, when the
        // addon has the album, the lossless addon copy as a toggle.
        val actions = listOfNotNull(
            AlbumAction(
                label = when {
                    startsFresh -> "Play"
                    state.playWhenReady -> "Pause"
                    else -> "Resume"
                },
                icon = if (!startsFresh && state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                active = !startsFresh && state.playWhenReady,
                onClick = {
                    if (!canPlay) Unit
                    else if (!startsFresh) player.togglePlayback()
                    else startAlbum(0, mode, shuffled = false)
                },
            ),
            AlbumAction(
                label = if (!startsFresh && state.shuffled) "Shuffle on" else "Shuffle",
                icon = Icons.Rounded.Shuffle,
                active = !startsFresh && state.shuffled,
                onClick = {
                    if (!canPlay) Unit
                    else if (!startsFresh) player.setShuffled(!state.shuffled)
                    else startAlbum(randomPlayableIndex(view), mode, shuffled = true)
                },
            ),
            AlbumAction("Audio", Icons.Rounded.Headphones, active = mode == MusicPlayMode.Audio, onClick = {
                chosenMode = MusicPlayMode.Audio
                if (loaded) player.switchMode(MusicPlayMode.Audio)
            }),
            AlbumAction("Video", Icons.Rounded.Movie, active = mode == MusicPlayMode.Video, onClick = {
                chosenMode = MusicPlayMode.Video
                if (loaded) player.switchMode(MusicPlayMode.Video)
            }),
            nebula?.takeIf { addonReady }?.let { addon ->
                val onAddon = addon.chosen == MusicSource.Addon
                AlbumAction(
                    label = if (onAddon) "Playing your addon's lossless copy (switch to YouTube Music)" else "Play your addon's lossless copy",
                    icon = MusicIcons.Lossless,
                    active = onAddon,
                    onClick = { addon.choose(if (onAddon) MusicSource.YouTube else MusicSource.Addon) },
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            actions.forEach { action ->
                NuvioPosterHoverTooltip(title = action.label) {
                    DetailIconAction(label = action.label, icon = action.icon, active = action.active, size = 56.dp, onClick = action.onClick)
                }
            }
        }
        // The lines under the buttons say only what needs saying, centred like everything above them.
        nebula?.let { NebulaSourceRow(it, onRetry = { nebulaRetry++ }) } // fork:nebula
        val note = when {
            loaded && state.notice != null -> state.notice
            mode == MusicPlayMode.Video -> "Tracks without a music video are skipped."
            else -> null
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (loaded && state.notice != null) colors.warning else colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        val unavailable = view.tracks.count { !it.isAvailable }
        if (unavailable > 0) {
            Text(
                "$unavailable track${if (unavailable == 1) "" else "s"} unavailable${if (wantNebula) " on your addon" else " on YouTube Music"}.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        // Where the library and YouTube Music disagree is worth a line: it explains a "missing" song.
        val listed = info?.release?.trackCount
        if (listed != null && listed != album.tracks.size) {
            Text(
                "Your library lists $listed tracks for this release; YouTube Music has ${album.tracks.size}.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }

    val pane: LazyListScope.() -> Unit = {
        itemsIndexed(view.tracks) { index, track ->
            TrackRow(
                album = album,
                track = track,
                current = loaded && state.trackIndex == index,
                onClick = {
                    if (loaded && startsFresh) {
                        // fork:nebula - the album is playing from the other source: start it over in the chosen one.
                        startAlbum(index, chosenMode, shuffled = false)
                    } else when (val tap = musicTrackTap(state, view, index, chosenMode)) {
                        is MusicTrackTap.JumpInQueue -> player.jumpTo(tap.trackIndex)
                        is MusicTrackTap.StartAlbum -> startAlbum(tap.trackIndex, tap.mode, shuffled = false)
                    }
                },
            )
        }
    }
    val more = info?.more.orEmpty()
    val moreTitle = "More from ${info?.artist?.name ?: "this artist"}"

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wide enough for three columns: the object, the record and its songs, then More from down the right in two
        // columns, always in the same place however long the track list is. Narrower, More from goes under the songs.
        val moreAside = more.isNotEmpty() && maxWidth >= 1200.dp
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(OBJECT_COLUMN_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = 32.dp, end = 12.dp, top = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { objectPanel(340.dp) }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    // With More from beside it, the end padding makes up the difference between the two side columns, so
                    // the record is centred on the window (level with the docked player) rather than between them.
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = if (moreAside) (OBJECT_COLUMN_WIDTH + 12.dp - MORE_COLUMN_WIDTH).coerceAtLeast(24.dp) else 32.dp,
                        top = 16.dp,
                        bottom = 40.dp,
                    ),
                    // The record sits centred between the object on the left and More from on the right.
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item(key = "facts") {
                        Column(
                            Modifier.widthIn(max = 720.dp).padding(bottom = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) { facts() }
                    }
                    pane()
                    if (!moreAside && more.isNotEmpty()) item(key = "more") { MoreFromRow(moreTitle, more, onAlbumClick) }
                }
                if (moreAside) MoreFromColumn(moreTitle, more, onAlbumClick)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            ) {
                item(key = "object") {
                    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { objectPanel(240.dp) }
                }
                item(key = "facts") {
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) { facts() }
                }
                pane()
                if (more.isNotEmpty()) item(key = "more") { MoreFromRow(moreTitle, more, onAlbumClick) }
            }
        }
    }
}

/** Narrow enough that a song's length reads with its title, as on a record sleeve. */
private val TRACK_ROW_MAX_WIDTH = 480.dp

/** One round button in the album's action row. */
private class AlbumAction(val label: String, val icon: ImageVector, val active: Boolean, val onClick: () -> Unit)
private val MORE_TILE = 150.dp
private val MORE_GAP = 16.dp
private val MORE_COLUMN_WIDTH = MORE_TILE * 2 + MORE_GAP + 56.dp
private val OBJECT_COLUMN_WIDTH = 520.dp

/** More from the artist down the right of a wide window: two columns of covers, scrolling on their own. */
@Composable
private fun MoreFromColumn(title: String, more: List<LibraryRelease>, onAlbumClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.width(MORE_COLUMN_WIDTH).fillMaxHeight(),
        contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 24.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(MORE_GAP),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        }
        gridItems(more, key = { it.key }) { release -> MoreFromTile(release, MORE_TILE, onAlbumClick) }
    }
}

/** More from the artist under the songs, on a narrower window: one sideways row. */
@Composable
private fun MoreFromRow(title: String, more: List<LibraryRelease>, onAlbumClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(more, key = { it.key }) { release -> MoreFromTile(release, 124.dp, onAlbumClick) }
        }
    }
}

@Composable
private fun MoreFromTile(release: LibraryRelease, size: Dp, onAlbumClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        Modifier.width(size).clip(RoundedCornerShape(10.dp)).clickable { release.ytBrowseId?.let(onAlbumClick) },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MusicCover(release.art.cover, release.title, size = size)
        Text(release.title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(release.year, release.kind.label.takeIf { it != "Album" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** The object itself (click to flip, drag to slide the disc), with a hint and a line saying where the art comes from. */
@Composable
private fun AlbumObjectPanel(
    album: MusicAlbum,
    info: LibraryAlbumInfo?,
    flipped: Boolean,
    discOut: Boolean,
    discSpinning: Boolean,
    reducedMotion: Boolean,
    caseSize: Dp,
    onFlip: () -> Unit,
    onDisc: (out: Boolean) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The case turns to its back cover (a click) and the disc slides out of it (a sideways drag); both snap
        // under reduced motion.
        AlbumObject(
            album = album,
            release = info?.release,
            flipped = flipped,
            discOut = discOut,
            discSpinning = discSpinning,
            reducedMotion = reducedMotion,
            size = caseSize,
            peek = 0.3f,
            onFlip = onFlip,
            onDisc = onDisc,
        )
        Text(
            "Click the cover to turn it over, drag sideways to slide the disc out or in. " +
                "Cover, back and disc come from TheAudioDB and Fanart.tv. Missing art falls back to a drawn back and disc.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            // As wide as the case, not the disc beside it, so the note stays clear of the record column.
            modifier = Modifier.width(caseSize),
        )
    }
}

/** Kicker, serif title, artist line (photo, name, label, date) and the length line, as on board 3. */
@Composable
private fun AlbumFacts(album: MusicAlbum, info: LibraryAlbumInfo?, onArtistClick: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val artistId = album.artists.firstOrNull()?.browseId
    val minutes = album.tracks.sumOf { it.durationSeconds } / 60
    val kicker = listOfNotNull(
        album.kind,
        album.year,
        "${album.tracks.size} track${if (album.tracks.size == 1) "" else "s"}",
        "$minutes min".takeIf { minutes > 0 },
    ).joinToString(" · ").uppercase()
    val notes = buildLinerNotes(info?.release, album.year)
    val credit = listOfNotNull(notes.label, notes.date, "Explicit".takeIf { notes.explicit }).joinToString(" · ")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = kicker,
            style = MaterialTheme.typography.labelLarge,
            color = colors.accent,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
        )
        Text(
            text = album.title,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Serif,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (album.artists.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = if (artistId != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable { onArtistClick(artistId) } else Modifier,
            ) {
                info?.artist?.art?.thumb?.let { MusicCover(it, description = null, size = 30.dp, circle = true) }
                Text(
                    text = album.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (artistId != null) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (credit.isNotEmpty()) {
                    Text("· $credit", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    album: MusicAlbum,
    track: MusicTrack,
    current: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val albumArtists = album.artists.map { it.name }
    val trackArtists = track.artists.map { it.name }
    // Only when the song has someone the album does not (a feature), not on every row.
    val credit = trackArtists.takeIf { it.isNotEmpty() && it != albumArtists }?.joinToString(", ")
    Row(
        modifier = Modifier
            .widthIn(max = TRACK_ROW_MAX_WIDTH) // the length stays near the title however wide the column gets
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (current) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = track.isAvailable, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                MusicEqualiser(colors.accent, modifier = Modifier.semantics { contentDescription = "Now playing" })
            } else {
                Text(
                    text = track.trackNumber.takeIf { it > 0 }?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (track.isAvailable) colors.textMuted else colors.textDisabled,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !track.isAvailable -> colors.textDisabled
                    current -> colors.accent
                    else -> colors.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (credit != null) {
                // A credit chip for the featured artist(s), as on board 3.
                val chip = RoundedCornerShape(8.dp)
                Text(
                    text = credit,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (track.isAvailable) colors.textSecondary else colors.textDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).clip(chip)
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (track.durationSeconds > 0) formatMusicTime(track.durationSeconds * 1000L) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (track.isAvailable) colors.textMuted else colors.textDisabled,
        )
    }
}

/**
 * The channel to look up in the background when [album] is opened, or null to skip it. Compilations
 * credited to "Various Artists" (or to nobody linkable) would pull in a broad channel that is not the
 * record's artist, so they get no look-up; every other album looks up its first artist, as before.
 */
internal fun albumArtistToPreview(album: MusicAlbum): String? {
    val first = album.artists.firstOrNull() ?: return null
    val name = first.name.trim().lowercase()
    if (name == "various artists" || name == "various" || name == "va") return null
    return first.browseId
}
