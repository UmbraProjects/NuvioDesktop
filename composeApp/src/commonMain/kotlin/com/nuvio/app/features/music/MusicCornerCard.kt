package com.nuvio.app.features.music

import com.nuvio.app.core.ui.KeyboardInputMode
import androidx.compose.foundation.layout.offset
import com.nuvio.app.core.ui.ditheredBackground
import com.nuvio.app.core.ui.NuvioGlass
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio

/** The corner card: a square cover, the words, the live lyric line and the transport, in one tall card. */
internal val MusicCornerCardHeight: Dp = 240.dp
private val CornerCardMaxWidth = 680.dp
private val CornerCoverSize = MusicCornerCardHeight - 32.dp

/**
 * The player as a card pinned bottom-left, the alternative to [MusicMiniPlayerBar] (see [MusicBarStyle]). It is built
 * like the cards above it: a big cover, the title and artist at TV sizes, the line being sung right now in the
 * artist's accent, and previous, a play button wearing a ring that fills as the track plays, next, and the same
 * "more" panel as the bar. It floats over the page and reserves no room, so it covers whatever is under it; the host
 * only shows it when no real video, lyric view or ladder needs the bar instead.
 */
@Composable
internal fun MusicCornerCard(
    state: MusicPlayerState,
    visible: Boolean,
    player: MusicPlayer,
    modifier: Modifier = Modifier,
    onOpenNowPlaying: () -> Unit = {},
    onCollapse: (() -> Unit)? = null,
    initiallyShown: Boolean = false,
    /** The window's height, so a dragged card is kept inside it. */
    areaHeight: Dp = Dp.Unspecified,
) {
    val colors = MaterialTheme.nuvio.colors
    val reduced = rememberMusicReducedMotion()
    val shown = remember { MutableTransitionState(initiallyShown) }
    shown.targetState = visible
    val onScreen = shown.currentState || shown.targetState
    val enter = if (reduced) EnterTransition.None else slideInVertically(tween(320)) { it } + fadeIn(tween(240))
    val exit = if (reduced) ExitTransition.None else slideOutVertically(tween(260)) { it } + fadeOut(tween(200))
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(if (onScreen) MusicCornerCardHeight + MusicBarBottomMargin else 1.dp),
    ) {
        var moreOpen by remember { mutableStateOf(false) }
        LaunchedEffect(visible) { if (!visible) moreOpen = false }
        if (!onScreen) return@BoxWithConstraints

        val side = musicBarSideMargin(maxWidth)
        val cardWidth = minOf(CornerCardMaxWidth, maxWidth - side * 2)
        AnimatedVisibility(
            visibleState = shown,
            modifier = Modifier.align(Alignment.BottomStart),
            enter = enter,
            exit = exit,
        ) {
            val shape = RoundedCornerShape(32.dp)
            val ground = lerp(colors.surfaceCard, colors.accent, 0.16f)
            val cover = state.coverUrl
            val fade = if (reduced) snap<Float>() else tween(400)
            val cardSource = remember { MutableInteractionSource() }
            val cardFocused by cardSource.collectIsFocusedAsState()
            // fork:music - wherever the person dragged it, as wide as they made it.
            val placement by MusicPlayback.cardPlacement.collectAsStateWithLifecycle()
            val areaWidth = maxWidth
            val frame = musicCornerFrame(areaWidth, areaHeight, MusicCornerCardHeight, cardWidth, placement)
            Box(
                Modifier
                    .padding(bottom = MusicBarBottomMargin)
                    .offset(x = frame.left, y = frame.dy)
                    .width(frame.width)
                    .height(MusicCornerCardHeight)
                    .musicPlayerDrag(MusicPlayback.cardPlacement) {
                        clampCornerPlacement(areaWidth, areaHeight, MusicCornerCardHeight, cardWidth, it)
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .shadow(
                            elevation = 16.dp,
                            shape = shape,
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.55f),
                            spotColor = Color.Black.copy(alpha = 0.55f),
                        )
                        .clip(shape)
                        // The app glass over its night base (it floats over the page, so the base is solid).
                        .background(NuvioGlass.night(colors.background, colors.accent))
                        .ditheredBackground(NuvioGlass.Fill, shape)
                        .border(
                            if (cardFocused && KeyboardInputMode.active) BorderStroke(3.dp, colors.focusRing) else BorderStroke(1.dp, NuvioGlass.EdgeColor),
                            shape,
                        )
                        // With the card itself focused, Enter opens the full Now Playing view; its buttons keep their own keys.
                        .focusable(interactionSource = cardSource)
                        .onKeyEvent { event ->
                            val enterKey = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (cardFocused && enterKey && event.type == KeyEventType.KeyDown) {
                                onOpenNowPlaying()
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(CornerCoverSize).clip(RoundedCornerShape(22.dp)).clickable(onClick = onOpenNowPlaying),
                        ) {
                            Crossfade(targetState = cover, animationSpec = fade, modifier = Modifier.fillMaxSize()) { url ->
                                if (url != null) {
                                    NuvioAsyncImage(
                                        model = url,
                                        contentDescription = state.albumTitle,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize().background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.MusicNote, null, tint = colors.textMuted, modifier = Modifier.size(56.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(22.dp))
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            val notice = state.notice
                            val line = BarLine(state.track?.title.orEmpty(), notice ?: subtitle(state), notice != null)
                            AnimatedContent(
                                targetState = line,
                                modifier = Modifier.clickable(onClick = onOpenNowPlaying),
                                transitionSpec = {
                                    if (reduced) EnterTransition.None togetherWith ExitTransition.None
                                    else fadeIn(tween(300, delayMillis = 100)) togetherWith fadeOut(tween(200))
                                },
                                label = "cornerLine",
                            ) { text ->
                                Column {
                                    Text(
                                        text = text.title,
                                        color = colors.textPrimary,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = text.subtitle,
                                        color = if (text.notice) colors.warning else colors.textSecondary,
                                        fontSize = 20.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            // A fixed slot so the controls do not move when a track has no timed lyrics.
                            Box(Modifier.fillMaxWidth().height(56.dp)) {
                                val live = rememberLiveLyricLine(state)
                                AnimatedContent(
                                    targetState = live,
                                    transitionSpec = {
                                        if (reduced) EnterTransition.None togetherWith ExitTransition.None
                                        else fadeIn(tween(260)) togetherWith fadeOut(tween(160))
                                    },
                                    label = "liveLyric",
                                ) { text ->
                                    if (text != null) {
                                        Text(
                                            text = text,
                                            color = colors.accent,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) {
                                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = colors.textPrimary, modifier = Modifier.size(34.dp))
                                }
                                PlayRing(state, player)
                                IconButton(onClick = { player.next() }, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                                    Icon(
                                        Icons.Rounded.SkipNext,
                                        "Next",
                                        tint = if (state.hasNext) colors.textPrimary else colors.textDisabled,
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                MoreButton(open = moreOpen, onToggle = { moreOpen = !moreOpen }) {
                                    MorePanel(
                                        state = state,
                                        player = player,
                                        videoView = MusicVideoView.Bar,
                                        onCollapse = onCollapse,
                                        onOpenNowPlaying = onOpenNowPlaying,
                                        onSetVideoView = {},
                                        onDismiss = { moreOpen = false },
                                    )
                                }
                            }
                        }
                    }
                }
                MusicResizeGrip(
                    placement = MusicPlayback.cardPlacement,
                    currentWidth = frame.width,
                    minWidth = MusicCornerMinWidth,
                    maxWidth = minOf(MusicCornerMaxWidth, areaWidth),
                    centred = false,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
                MusicResizeGrip(
                    placement = MusicPlayback.cardPlacement,
                    currentWidth = frame.width,
                    minWidth = MusicCornerMinWidth,
                    maxWidth = minOf(MusicCornerMaxWidth, areaWidth),
                    centred = false,
                    modifier = Modifier.align(Alignment.CenterStart),
                    edge = MusicGripEdge.Left,
                )
            }
        }
    }
}

/** The play/pause button inside a thin ring that fills with the track's progress. */
@Composable
private fun PlayRing(state: MusicPlayerState, player: MusicPlayer) {
    val colors = MaterialTheme.nuvio.colors
    val fraction = if (state.durationMs > 0L) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.size(76.dp).clip(CircleShape).clickable { player.togglePlayback() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 4.dp.toPx()
            val topLeft = Offset(width / 2, width / 2)
            val arc = Size(size.width - width, size.height - width)
            drawArc(Color.White.copy(alpha = 0.20f), 0f, 360f, false, topLeft, arc, style = Stroke(width))
            drawArc(colors.accent, -90f, 360f * fraction, false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
        }
        Box(Modifier.size(60.dp).clip(CircleShape).background(colors.accentFill), contentAlignment = Alignment.Center) {
            if (state.isLoading && state.playWhenReady) {
                CircularProgressIndicator(Modifier.size(28.dp), color = colors.onAccent, strokeWidth = 3.dp)
            } else {
                Icon(
                    imageVector = if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.playWhenReady) "Pause" else "Play",
                    tint = colors.onAccent,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
    }
}

/**
 * The line being sung at the playing position, or null when the track has no timed lyrics, none were found, or the
 * song is between lines. Uses the offset [MusicLyricSync] already worked out for this stream (the lyric view
 * is what asks it to analyse; the card only reads the answer), so it follows the same timing as that view.
 */
@Composable
private fun rememberLiveLyricLine(state: MusicPlayerState): String? {
    val track = state.track
    val album = state.album
    val lyrics by produceState<Lyrics?>(null, track?.videoId) {
        value = null
        value = track?.let { LyricsRepository.forTrack(it, album) }
    }
    // The version of the lyrics that fits this stream, as the lyric view picks it, so the offset below is for them.
    val streamLyrics = rememberStreamLyrics(
        track, album, lyrics as? Lyrics.Synced, state.loadId, state.durationMs, state.durationMs > 0L && !state.isLoading,
    )
    val syncStates by MusicLyricSync.states.collectAsStateWithLifecycle()
    val key = track?.let { lyricSyncKey(it, realVideo = false) }
    // Before the lyric view has looked at this stream, what the person saved for it still applies.
    val offset = key?.let { syncStates[it]?.offsetMs() ?: MusicLyricSync.savedOffsetOf(it) } ?: 0L
    val lines = remember(streamLyrics.lyrics, offset) { streamLyrics.lyrics?.lines?.shiftedBy(offset) }
    if (lines == null) return null
    val index = activeLyricIndex(lines, state.positionMs)
    return lines.getOrNull(index)?.text?.trim()?.takeIf { isSungLyric(it) }
}
