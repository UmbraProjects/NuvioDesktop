package com.nuvio.app.features.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import com.nuvio.app.features.music.library.LadderArtistTargets
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.WhereNextCache

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
) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(600))
    val album = state.album
    val upNext = remember(state.track?.videoId, state.shuffled, state.loadId) { player.upNext(3) }
    val total = album?.tracks?.size ?: 0
    Column(modifier.graphicsLayer { this.alpha = alpha }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (album != null && total > 1 && state.trackIndex >= 0) {
            Text("Track ${state.trackIndex + 1} of $total", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            Row(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                val done = ((state.trackIndex + 1).toFloat() / total).coerceIn(0.02f, 1f)
                Box(Modifier.weight(done).fillMaxHeight().background(Color.White.copy(alpha = 0.85f)))
                if (done < 1f) Box(Modifier.weight(1f - done).fillMaxHeight())
            }
        }
        if (upNext.isNotEmpty()) {
            Text("UP NEXT", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            upNext.forEach { next ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MusicCover(album?.thumbnailUrl, next.title, size = 40.dp)
                    Text(next.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (next.durationSeconds > 0) {
                        Text(formatMusicTime(next.durationSeconds * 1_000L), color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                    }
                }
            }
        }
        if (lyricsMissing && state.isPlaying && libraryArtist != null) MiniLadder(libraryArtist)
    }
}

@Composable
private fun MiniLadder(artist: LibraryArtist) {
    val outcome by produceState<ArtistLadderOutcome.Ready?>(WhereNextCache.get(artist.id, null), artist.id) {
        value = WhereNextCache.get(artist.id, null) ?: run {
            MusicLibraryRepository.ensureLoaded()
            (LadderArtistTargets.ladderOutFrom(artist.name, MusicLibraryRepository.state.value.artists) as? ArtistLadderOutcome.Ready)
                ?.also { WhereNextCache.put(artist.id, null, it) }
        }
    }
    val rungs = outcome?.ladder?.rungs.orEmpty().take(3)
    if (rungs.isEmpty()) return
    Text("WHERE NEXT FROM ${artist.name.uppercase()}?", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rungs.forEach { rung ->
            Text(
                rung.candidate.name,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
