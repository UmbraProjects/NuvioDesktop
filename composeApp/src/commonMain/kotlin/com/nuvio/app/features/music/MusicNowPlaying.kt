package com.nuvio.app.features.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import kotlinx.coroutines.delay

private const val FANART_HOLD_MS = 14_000L
private const val FANART_PUSH_MS = 16_000

private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data class Ready(val lyrics: Lyrics) : LyricsUi
    data object Missing : LyricsUi
}

/**
 * The full-window "lyric video": artist fanart drifting behind the words, the line being sung lit
 * and centred, a tap on a line seeks to it. Sits over the app but under the mini-player, which
 * keeps its controls. Opened by [MusicPlayback.nowPlayingOpen].
 */
@Composable
fun MusicNowPlayingHost(
    hidden: Boolean,
    modifier: Modifier = Modifier,
    player: MusicPlayer = MusicPlayback.player,
) {
    val state by player.state.collectAsStateWithLifecycle()
    val open by MusicPlayback.nowPlayingOpen.collectAsStateWithLifecycle()
    // The view goes with the player: a stopped player has nothing to show next time it starts.
    LaunchedEffect(state.isActive) {
        if (!state.isActive) MusicPlayback.nowPlayingOpen.value = false
    }
    AnimatedVisibility(
        visible = open && state.isActive && !hidden,
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        MusicNowPlayingScreen(
            state = state,
            onSeek = player::seekTo,
            onClose = { MusicPlayback.nowPlayingOpen.value = false },
            modifier = Modifier.fillMaxSize().padding(bottom = MusicMiniPlayerHeight),
        )
    }
}

@Composable
private fun MusicNowPlayingScreen(
    state: MusicPlayerState,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.track
    val album = state.album
    val artist = (track?.artists?.takeIf { it.isNotEmpty() } ?: album?.artists.orEmpty())
        .joinToString(", ") { it.name }
    val primaryArtist = (track?.artists?.firstOrNull() ?: album?.artists?.firstOrNull())?.name.orEmpty()

    val lyrics by produceState<LyricsUi>(LyricsUi.Loading, track?.videoId) {
        value = LyricsUi.Loading
        val found = track?.let { LyricsRepository.forTrack(it, album) }
        value = if (found != null) LyricsUi.Ready(found) else LyricsUi.Missing
    }
    val fanart by produceState(emptyList<String>(), primaryArtist) {
        value = ArtistFanartRepository.forArtist(primaryArtist)
    }

    val effects by MusicPlayback.lyricEffects.collectAsStateWithLifecycle()
    val accent = remember(track?.title) { moodColor(track?.title.orEmpty() + primaryArtist) }
    var hype by remember { mutableStateOf(false) }

    Box(modifier.background(Color.Black)) {
        FanartBackdrop(images = fanart, coverUrl = album?.thumbnailUrl)
        if (effects) LyricAurora(accent = accent, hype = hype)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 860.dp
            if (wide) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(300.dp)) {
                        Cover(album?.thumbnailUrl, Modifier.size(300.dp))
                        Spacer(Modifier.height(20.dp))
                        Text(
                            track?.title.orEmpty(),
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(artist, color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    LyricsPane(lyrics, state.positionMs, state.isPlaying, effects, accent, { hype = it }, onSeek, Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Cover(album?.thumbnailUrl, Modifier.size(64.dp))
                        Column {
                            Text(track?.title.orEmpty(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(artist, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    LyricsPane(lyrics, state.positionMs, state.isPlaying, effects, accent, { hype = it }, onSeek, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        MusicTextChip(
            text = if (effects) "Effects: on" else "Effects: calm",
            onClick = { MusicPlayback.lyricEffects.value = !effects },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Close lyrics", tint = Color.White)
        }
    }
}

@Composable
private fun Cover(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f))) {
        if (url != null) {
            NuvioAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/**
 * Fanart that crossfades every few seconds while each picture pushes in slowly. With no fanart the
 * album cover stands in, blurred, so the view is never a flat black.
 */
@Composable
private fun FanartBackdrop(images: List<String>, coverUrl: String?) {
    val usingCover = images.isEmpty()
    val pool = if (usingCover) listOfNotNull(coverUrl) else images
    var index by remember(pool) { mutableIntStateOf(0) }
    LaunchedEffect(pool) {
        while (pool.size > 1) {
            delay(FANART_HOLD_MS)
            index = (index + 1) % pool.size
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (pool.isNotEmpty()) {
            Crossfade(targetState = index.coerceIn(0, pool.size - 1), animationSpec = tween(1800), label = "fanart") { i ->
                key(pool[i]) {
                    val push = remember { Animatable(1f) }
                    LaunchedEffect(Unit) { push.animateTo(1.1f, tween(FANART_PUSH_MS, easing = LinearEasing)) }
                    NuvioAsyncImage(
                        model = pool[i],
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (usingCover) Modifier.blur(56.dp) else Modifier)
                            .graphicsLayer {
                                scaleX = push.value
                                scaleY = push.value
                            },
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        // Dark enough that white words read on any picture, a little heavier towards the bottom.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.82f))),
            ),
        )
    }
}

@Composable
private fun LyricsPane(
    ui: LyricsUi,
    positionMs: Long,
    playing: Boolean,
    effects: Boolean,
    accent: Color,
    onHype: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (ui) {
            LyricsUi.Loading -> Unit
            LyricsUi.Missing -> Message("No lyrics found for this track")
            is LyricsUi.Ready -> when (val lyrics = ui.lyrics) {
                Lyrics.Instrumental -> Message("Instrumental")
                is Lyrics.Plain -> PlainLyrics(lyrics.lines)
                is Lyrics.Synced -> SyncedLyricsFx(lyrics.lines, positionMs, playing, effects, accent, onHype, onSeek)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 22.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun PlainLyrics(lines: List<String>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 48.dp)) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = line,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 22.sp,
                lineHeight = 30.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}
