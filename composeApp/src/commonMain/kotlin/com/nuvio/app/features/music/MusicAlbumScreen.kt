package com.nuvio.app.features.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.LibraryAlbumInfo
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.rememberLibraryAlbumInfo

/**
 * An album: cover, a Play/Shuffle pair, the Audio/Video choice, and the track list.
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
    val load = rememberMusicLoad(browseId, attempt) { catalog.album(browseId) }

    Box(modifier.fillMaxSize().background(colors.background)) {
    // The cover, blurred, as the page's atmosphere (it does not move, so reduced motion needs nothing).
    if (load is MusicLoad.Loaded) AlbumAmbientBackdrop(load.value.thumbnailUrl)
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
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
    val albumArtistId = album.artists.firstOrNull()?.browseId
    LaunchedEffect(albumArtistId) {
        albumArtistId?.let { MusicLibraryRepository.openPreview(it) }
    }
    // What the music library knows about this album: set when it is a release of a saved or previewed artist.
    val info = rememberLibraryAlbumInfo(album.browseId)

    val loaded = state.holds(album)
    // While this album plays, the player's mode is the truth: a failed switch to video leaves it on audio.
    val mode = if (loaded) state.mode else chosenMode

    val playing = loaded && state.isPlaying
    val discOut = discChoice ?: playing
    val rail: @Composable () -> Unit = {
        AlbumHeader(
            album = album,
            info = info,
            flipped = flipped,
            discOut = discOut,
            discSpinning = discOut && playing,
            reducedMotion = reducedMotion,
            onFlip = { flipped = !flipped },
            onDisc = { discChoice = !discOut },
            onArtistClick = onArtistClick,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NuvioPrimaryButton(
                    text = when {
                        !loaded -> "Play"
                        state.playWhenReady -> "Pause"
                        else -> "Resume"
                    },
                    modifier = Modifier.width(140.dp),
                    height = 44.dp,
                    enabled = album.tracks.any { it.isAvailable },
                    onClick = {
                        if (loaded) player.togglePlayback()
                        else player.play(album, trackIndex = 0, mode = mode, shuffled = false)
                    },
                )
                ShuffleButton(
                    on = loaded && state.shuffled,
                    enabled = album.tracks.any { it.isAvailable },
                    onClick = {
                        if (loaded) player.setShuffled(!state.shuffled)
                        else player.play(album, trackIndex = randomPlayableIndex(album), mode = mode, shuffled = true)
                    },
                )
            }
            MusicSegmented(
                options = listOf(MusicPlayMode.Audio to "Audio", MusicPlayMode.Video to "Video"),
                selected = mode,
                onSelect = { picked ->
                    chosenMode = picked
                    if (loaded) player.switchMode(picked)
                },
            )
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
                )
            }
        }
        info?.release?.let { release ->
            // Liner notes: label and date, genre chips with the mood in the accent, the description.
            AlbumLinerNotes(
                notes = buildLinerNotes(release, album.year),
                descriptionOpen = descriptionOpen,
                onToggleDescription = { descriptionOpen = !descriptionOpen },
            )
            // Where the library and YouTube Music disagree is worth a line: it explains a "missing" song.
            val listed = release.trackCount
            if (listed != null && listed != album.tracks.size) {
                Text(
                    "Your library lists $listed tracks for this release; YouTube Music has ${album.tracks.size}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
        val unavailable = album.tracks.count { !it.isAvailable }
        if (unavailable > 0) {
            Text(
                "$unavailable track${if (unavailable == 1) "" else "s"} unavailable here.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }

    val pane: LazyListScope.() -> Unit = {
        itemsIndexed(album.tracks) { index, track ->
            TrackRow(
                album = album,
                track = track,
                current = loaded && state.trackIndex == index,
                onClick = {
                    when (val tap = musicTrackTap(state, album, index, chosenMode)) {
                        is MusicTrackTap.JumpInQueue -> player.jumpTo(tap.trackIndex)
                        is MusicTrackTap.StartAlbum ->
                            player.play(album, trackIndex = tap.trackIndex, mode = tap.mode, shuffled = false)
                    }
                },
            )
        }
        val more = info?.more.orEmpty()
        if (more.isNotEmpty()) {
            item(key = "more") {
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "More from ${info?.artist?.name ?: "this artist"}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(more, key = { it.key }) { release ->
                            Column(
                                Modifier.width(124.dp).clip(RoundedCornerShape(10.dp)).clickable { release.ytBrowseId?.let(onAlbumClick) },
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                MusicCover(release.art.cover, release.title, size = 124.dp)
                                Text(release.title, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(release.year, release.kind.label.takeIf { it != "Album" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { rail() }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 12.dp, end = 24.dp, top = 12.dp, bottom = 40.dp),
                ) { pane() }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            ) {
                item(key = "rail") {
                    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { rail() }
                }
                pane()
            }
        }
    }
}

@Composable
private fun AlbumHeader(
    album: MusicAlbum,
    info: LibraryAlbumInfo?,
    flipped: Boolean,
    discOut: Boolean,
    discSpinning: Boolean,
    reducedMotion: Boolean,
    onFlip: () -> Unit,
    onDisc: () -> Unit,
    onArtistClick: (String) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val artistId = album.artists.firstOrNull()?.browseId
    val minutes = album.tracks.sumOf { it.durationSeconds } / 60
    val meta = listOfNotNull(
        album.kind,
        album.year,
        "${album.tracks.size} track${if (album.tracks.size == 1) "" else "s"}",
        "$minutes min".takeIf { minutes > 0 },
    ).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The case turns to its back cover and the disc slides out of it; both snap under reduced motion.
        AlbumObject(
            album = album,
            release = info?.release,
            flipped = flipped,
            discOut = discOut,
            discSpinning = discSpinning,
            reducedMotion = reducedMotion,
            size = 260.dp,
            peek = 0.3f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AlbumObjectButton(if (flipped) "Flip to front" else "Flip to back", onFlip)
            AlbumObjectButton(if (discOut) "Slide disc in" else "Slide disc out", onDisc)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (album.artists.isNotEmpty()) {
                Text(
                    text = album.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (artistId != null) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (artistId != null) {
                        Modifier.clip(RoundedCornerShape(4.dp)).clickable { onArtistClick(artistId) }
                    } else {
                        Modifier
                    },
                )
            }
            Text(text = meta, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

@Composable
private fun ShuffleButton(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(shape)
            .border(1.dp, if (on) colors.accent else colors.borderDefault, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (!enabled) colors.textDisabled else if (on) colors.accent else colors.textPrimary
        Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text("Shuffle", style = MaterialTheme.typography.titleMedium, color = tint)
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
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (current) colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = track.isAvailable, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                Icon(Icons.Rounded.VolumeUp, contentDescription = "Now playing", tint = colors.accent, modifier = Modifier.size(18.dp))
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
                    text = "feat. $credit",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (track.isAvailable) colors.accent else colors.textDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).clip(chip)
                        .background((if (track.isAvailable) colors.accent else colors.textDisabled).copy(alpha = 0.12f))
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
