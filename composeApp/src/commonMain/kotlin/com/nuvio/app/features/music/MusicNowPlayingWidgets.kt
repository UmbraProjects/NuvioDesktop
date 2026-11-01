package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.musicKeyFocusRing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import com.nuvio.app.core.ui.nuvio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.nuvio.app.features.music.library.rungArtist
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.music.library.ArtistLadderOutcome
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.WhereNextLadders
import com.nuvio.app.features.music.library.rememberAutoWhereNext

/**
 * HTPC phase 7: widgets for the empty lower-left of the Stage view, under the title. All of them sit off the lyric
 * column and fade out ([visible] false) a few seconds after the last input, so the Stage is clean at a distance.
 *
 *  - Progress by release: where this track sits in the album.
 *  - Up next: the next three tracks that can be played, with the album cover.
 *  - Where next: three artists out from the one playing, only while a track plays with no lyrics on screen.
 */
@Composable
internal fun NowPlayingWidgets(
    state: MusicPlayerState,
    player: MusicPlayer,
    libraryArtist: LibraryArtist?,
    lyricsMissing: Boolean,
    visible: Boolean,
    modifier: Modifier = Modifier,
    onOpenArtist: (String) -> Unit = {},
) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(600))
    val album = state.album
    val (queue, playingAt) = remember(state.track?.videoId, state.shuffled, state.loadId) { player.queueView() }
    val total = album?.tracks?.size ?: 0
    Column(modifier.graphicsLayer { this.alpha = alpha }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (queue.size > 1) {
            Text("QUEUE", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            // The whole queue, scrolling in whatever height the column has left, kept on the song playing: earlier songs
            // above it (dimmed), the rest below. A click plays that song; a later album's songs are reached by Next.
            val listState = rememberLazyListState()
            LaunchedEffect(playingAt) { if (playingAt > 0) listState.animateScrollToItem((playingAt - 1).coerceAtLeast(0)) }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = UP_NEXT_MIN_HEIGHT),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(queue, key = { i, e -> "$i:${e.track.videoId}" }) { i, entry ->
                    val next = entry.track
                    val playing = i == playingAt
                    val source = remember { MutableInteractionSource() }
                    val hovered by source.collectIsHoveredAsState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { this.alpha = if (i < playingAt) 0.5f else 1f }
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    playing -> MaterialTheme.nuvio.colors.accent.copy(alpha = 0.22f)
                                    hovered && entry.inAlbum -> Color.White.copy(alpha = 0.16f)
                                    else -> Color.White.copy(alpha = 0.08f)
                                },
                            )
                            .hoverable(source)
                            .then(
                                if (entry.inAlbum && !playing) Modifier.musicKeyFocusRing().clickable(interactionSource = source, indication = null) { player.jumpTo(entry.albumIndex) }
                                else Modifier,
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MusicCover(next.coverUrl ?: album?.thumbnailUrl, next.title, size = 40.dp)
                        Text(next.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (next.durationSeconds > 0) {
                            Text(formatMusicTime(next.durationSeconds * 1_000L), color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        if (lyricsMissing && state.isPlaying && libraryArtist != null) MiniLadder(libraryArtist, onOpenArtist)
    }
}

@Composable
private fun MiniLadder(artist: LibraryArtist, onOpenArtist: (String) -> Unit) {
    val auto = rememberAutoWhereNext()
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    // What the last click is doing ("Opening…") or why it went nowhere; clears on the next click.
    var note by remember(artist.id) { mutableStateOf<String?>(null) }
    // A ladder kept in memory or on disk shows at once. Otherwise it is built behind other MusicBrainz calls, and only
    // when "Build Where next automatically" is on, so the Stage view never spends calls the person turned off.
    val outcome by produceState<ArtistLadderOutcome.Ready?>(WhereNextLadders.peek(artist.id, null), artist.id, auto) {
        value = WhereNextLadders.cached(artist.id, null)
            ?: if (auto) WhereNextLadders.build(artist, null, background = true) as? ArtistLadderOutcome.Ready else null
    }
    val rungs = outcome?.ladder?.rungs.orEmpty().take(3)
    if (rungs.isEmpty()) return
    Text("WHERE NEXT FROM ${artist.name.uppercase()}?", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rungs.forEach { rung ->
            val shape = RoundedCornerShape(16.dp)
            Text(
                rung.candidate.name,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(shape)
                    .musicKeyFocusRing(shape)
                    // A click opens that artist's page: the rung is only a name, so the artist is found on YouTube Music first.
                    .clickable {
                        scope.launch {
                            note = "Opening ${rung.candidate.name}…"
                            val found = rungArtist(catalog, rung.candidate.name)
                            if (found == null) {
                                note = "${rung.candidate.name} isn't on YouTube Music"
                            } else {
                                note = null
                                MusicPlayback.nowPlayingOpen.value = false
                                onOpenArtist(found.browseId)
                            }
                        }
                    }
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
    note?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** One white for every control and one softer shade for the texts either side (where in the album, the time). */
private val CONTROLS_SIDE_TEXT = Color.White.copy(alpha = 0.7f)

/** The least height the queue's scrolling box keeps (about two rows). */
private val UP_NEXT_MIN_HEIGHT = 112.dp

/**
 * The lyric view's own player, which stays on screen (the bar, dock and card step aside while the view is open). One
 * compact row: where the song is in the album or playlist on the left, previous / play / next and Add to playlist in
 * the middle, the time on the right; the seek bar under it, full width. The middle is placed in a Box on its own, so
 * it never moves as the texts either side change width.
 */
@Composable
internal fun NowPlayingControls(state: MusicPlayerState, player: MusicPlayer, modifier: Modifier = Modifier) {
    val total = state.album?.tracks?.size ?: 0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            if (total > 1 && state.trackIndex >= 0) {
                Text(
                    "Track ${state.trackIndex + 1} of $total",
                    color = CONTROLS_SIDE_TEXT,
                    fontSize = 15.sp,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                ControlIcon(Icons.Rounded.SkipPrevious, "Previous") { player.previous() }
                ControlIcon(
                    if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (state.playWhenReady) "Pause" else "Play",
                    large = true,
                ) { player.togglePlayback() }
                ControlIcon(Icons.Rounded.SkipNext, "Next", enabled = state.hasNext) { player.next() }
                state.track?.let { track ->
                    ControlIcon(Icons.Rounded.PlaylistAdd, "Add to playlist") {
                        com.nuvio.app.features.music.playlist.PlaylistPicker.request(
                            listOf(with(com.nuvio.app.features.music.playlist.MusicPlaylistTracks) { track.forPlaylist(state.album) }),
                        )
                    }
                }
            }
            Text(
                "${formatMusicTime(state.positionMs)}/${formatMusicTime(state.durationMs)}",
                color = CONTROLS_SIDE_TEXT,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        SeekBar(
            fraction = if (state.durationMs > 0L) state.positionMs.toFloat() / state.durationMs else 0f,
            onSeek = { player.seekTo((it * state.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.durationMs > 0L,
            onNudge = { d -> player.seekTo((state.positionMs + d * 5_000L).coerceIn(0L, state.durationMs)) },
            glass = true,
        )
    }
}

@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    /** Play / pause: a bigger plain white glyph, no circle behind it. */
    large: Boolean = false,
    onClick: () -> Unit,
) {
    com.nuvio.app.core.ui.NuvioPosterHoverTooltip(title = label) {
        Box(Modifier.size(if (large) 44.dp else 38.dp).clip(CircleShape).musicKeyFocusRing(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(if (large) 30.dp else 22.dp),
            )
        }
    }
}
