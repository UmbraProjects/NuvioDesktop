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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
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
            onDisc = { discChoice = !discOut },
        )
    }
    val facts: @Composable () -> Unit = {
        AlbumFacts(album = album, info = info, onArtistClick = onArtistClick)
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
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioPrimaryButton(
                    text = when {
                        startsFresh -> "Play"
                        state.playWhenReady -> "Pause"
                        else -> "Resume"
                    },
                    modifier = Modifier.width(140.dp),
                    height = 44.dp,
                    enabled = album.tracks.any { it.isAvailable },
                    onClick = {
                        if (!startsFresh) player.togglePlayback()
                        else startAlbum(0, mode, shuffled = false)
                    },
                )
                ShuffleButton(
                    on = !startsFresh && state.shuffled,
                    enabled = album.tracks.any { it.isAvailable },
                    onClick = {
                        if (!startsFresh) player.setShuffled(!state.shuffled)
                        else startAlbum(randomPlayableIndex(album), mode, shuffled = true)
                    },
                )
                MusicSegmented(
                    options = listOf(MusicPlayMode.Audio to "Audio", MusicPlayMode.Video to "Video"),
                    selected = mode,
                    onSelect = { picked ->
                        chosenMode = picked
                        if (loaded) player.switchMode(picked)
                    },
                )
            }
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
                    if (loaded && startsFresh) {
                        // fork:nebula - the album is playing from the other source: start it over in the chosen one.
                        startAlbum(index, chosenMode, shuffled = false)
                    } else when (val tap = musicTrackTap(state, album, index, chosenMode)) {
                        is MusicTrackTap.JumpInQueue -> player.jumpTo(tap.trackIndex)
                        is MusicTrackTap.StartAlbum -> startAlbum(tap.trackIndex, tap.mode, shuffled = false)
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
                    Modifier.width(520.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(start = 32.dp, end = 12.dp, top = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { objectPanel(340.dp) }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 12.dp, end = 32.dp, top = 16.dp, bottom = 40.dp),
                ) {
                    item(key = "facts") {
                        Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { facts() }
                    }
                    pane()
                }
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
                    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { facts() }
                }
                pane()
            }
        }
    }
}

/** The object itself and its two toggles, with a line saying where the art comes from. */
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
    onDisc: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The case turns to its back cover and the disc slides out of it; both snap under reduced motion.
        AlbumObject(
            album = album,
            release = info?.release,
            flipped = flipped,
            discOut = discOut,
            discSpinning = discSpinning,
            reducedMotion = reducedMotion,
            size = caseSize,
            peek = 0.3f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AlbumObjectButton(if (flipped) "Show front" else "Flip to back", onFlip, selected = flipped)
            AlbumObjectButton(if (discOut) "Slide disc in" else "Slide disc out", onDisc, selected = discOut)
        }
        Text(
            "Cover, back and disc come from TheAudioDB and Fanart.tv. Missing art falls back to a drawn back and disc.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
