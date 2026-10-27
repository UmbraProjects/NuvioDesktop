package com.nuvio.app.features.music

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.nuvio

private val CoverSize = MusicBarCardHeight - 24.dp
private val VideoWidth = BarThumbnailWidth

/** Windows narrower than this drop the elapsed and remaining time labels. */
private val CompactBelow = 900.dp

/** How far one d-pad press on the progress bar jumps, in milliseconds. */
private const val NUDGE_MS = 5_000L

/** The side margin of the floating bar: 5% of the width, the same as the Music screens' rows. */
internal fun musicBarSideMargin(width: Dp): Dp = width * 0.05f

/**
 * The bar along the bottom of the app, drawn as a floating card: a large cover, the title and artist, a thick
 * progress bar with the elapsed and remaining time, transport and the audio/video switch. The card is tinted by the
 * playing artist's accent and takes a blurred wash of the cover. In video mode the picture is drawn over the card's
 * left end by [MusicPlayerHost], which owns the native surface; the card only leaves that room empty. The bar
 * reserves [MusicMiniPlayerHeight] (the card plus its bottom margin); when it is hidden it takes no room.
 */
@Composable
fun MusicMiniPlayerBar(
    state: MusicPlayerState,
    visible: Boolean,
    player: MusicPlayer,
    modifier: Modifier = Modifier,
    videoView: MusicVideoView = MusicVideoView.Bar,
    onSetVideoView: (MusicVideoView) -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
    onCollapse: (() -> Unit)? = null,
    slim: Boolean = false,
    initiallyShown: Boolean = false,
) {
    val colors = MaterialTheme.nuvio.colors
    // The picture only sits in the bar when there is a real video; a lyric video is audio.
    val video = state.mode == MusicPlayMode.Video && !state.lyricVideo
    val reduced = rememberMusicReducedMotion()
    val showCard = visible && !slim
    val showPill = visible && slim
    // The card and the pill each slide in from the bottom edge and back out. [initiallyShown] lets a bar that is
    // already on screen when it is composed (the one that plays out after the player stops) start in place.
    val cardState = remember { MutableTransitionState(initiallyShown && !slim) }
    cardState.targetState = showCard
    val pillState = remember { MutableTransitionState(initiallyShown && slim) }
    pillState.targetState = showPill
    val onScreen = cardState.currentState || cardState.targetState || pillState.currentState || pillState.targetState
    val enter = if (reduced) EnterTransition.None else slideInVertically(tween(320)) { it } + fadeIn(tween(240))
    val exit = if (reduced) ExitTransition.None else slideOutVertically(tween(260)) { it } + fadeOut(tween(200))
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(if (onScreen) MusicMiniPlayerHeight else 1.dp),
    ) {
        var moreOpen by remember { mutableStateOf(false) }
        LaunchedEffect(showCard) { if (!showCard) moreOpen = false }
        if (!onScreen) return@BoxWithConstraints

        val compact = maxWidth < CompactBelow
        val side = musicBarSideMargin(maxWidth)
        AnimatedVisibility(
            visibleState = pillState,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = enter,
            exit = exit,
        ) {
            SlimPill(state, onOpenNowPlaying, player, Modifier.padding(end = side, bottom = MusicBarBottomMargin))
        }
        AnimatedVisibility(
            visibleState = cardState,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter = enter,
            exit = exit,
        ) {
            val shape = RoundedCornerShape(28.dp)
            val ground = lerp(colors.surfaceCard, colors.accent, 0.16f)
            val cover = state.album?.thumbnailUrl
            val fade = if (reduced) snap<Float>() else tween(400)
            val cardSource = remember { MutableInteractionSource() }
            val cardFocused by cardSource.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .padding(start = side, end = side, bottom = MusicBarBottomMargin)
                    .fillMaxWidth()
                    .height(MusicBarCardHeight)
                    .shadow(
                        elevation = 18.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = colors.accent.copy(alpha = 0.45f),
                        spotColor = colors.accent,
                    )
                    .clip(shape)
                    .background(ground)
                    .border(
                        if (cardFocused) BorderStroke(3.dp, colors.focusRing) else BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        shape,
                    )
                    // The card itself can take focus; with it focused, Enter opens the full Now Playing view.
                    // (Its buttons and bars keep their own keys: this only fires when the card is the focus.)
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
                // A wash of the cover, blurred and enlarged so its edges stay out of the card. Fades when the track changes.
                Crossfade(targetState = cover, animationSpec = fade, modifier = Modifier.fillMaxSize()) { url ->
                    if (url != null) {
                        NuvioAsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(48.dp)
                                .graphicsLayer { scaleX = 1.5f; scaleY = 1.5f; alpha = 0.55f },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(listOf(ground.copy(alpha = 0.90f), ground.copy(alpha = 0.62f))),
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left end: the picture in video mode (drawn by the surface above), else the cover.
                    // In a real video's thumbnail the host's native surface covers this box, so it is not a button then.
                    Box(
                        Modifier.width(if (video && videoView == MusicVideoView.Bar) VideoWidth else if (video) 12.dp else CoverSize)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !video, onClick = onOpenNowPlaying),
                    ) {
                        if (!video) {
                            Crossfade(targetState = cover, animationSpec = fade, modifier = Modifier.fillMaxSize()) { url ->
                                if (url != null) {
                                    NuvioAsyncImage(
                                        model = url,
                                        contentDescription = state.album?.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize().background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.MusicNote, null, tint = colors.textMuted, modifier = Modifier.size(36.dp))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        val notice = state.notice
                        val line = BarLine(state.track?.title.orEmpty(), notice ?: subtitle(state), notice != null)
                        AnimatedContent(
                            targetState = line,
                            modifier = Modifier.clickable(onClick = onOpenNowPlaying),
                            transitionSpec = {
                                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                                else fadeIn(tween(300, delayMillis = 100)) togetherWith fadeOut(tween(200))
                            },
                            label = "barLine",
                        ) { shown ->
                            Column {
                                Text(
                                    text = shown.title,
                                    color = colors.textPrimary,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = shown.subtitle,
                                    color = if (shown.notice) colors.warning else colors.textSecondary,
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!compact) {
                                Text(formatMusicTime(state.positionMs), color = colors.textSecondary, fontSize = 16.sp, maxLines = 1)
                            }
                            SeekBar(
                                fraction = if (state.durationMs > 0L) state.positionMs.toFloat() / state.durationMs else 0f,
                                onSeek = { fraction -> player.seekTo((fraction * state.durationMs).toLong()) },
                                modifier = Modifier.weight(1f).padding(horizontal = if (compact) 0.dp else 14.dp),
                                enabled = state.durationMs > 0L,
                                onNudge = { direction ->
                                    player.seekTo((state.positionMs + direction * NUDGE_MS).coerceIn(0L, state.durationMs))
                                },
                            )
                            if (!compact) {
                                val left = (state.durationMs - state.positionMs).coerceAtLeast(0L)
                                Text("-" + formatMusicTime(left), color = colors.textSecondary, fontSize = 16.sp, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Transport(state, player)
                    Spacer(Modifier.width(4.dp))
                    MoreButton(
                        open = moreOpen,
                        onToggle = { moreOpen = !moreOpen },
                    ) {
                        MorePanel(
                            state = state,
                            player = player,
                            videoView = videoView,
                            // A real video's picture lives in the bar, so the bar cannot be tucked away then.
                            onCollapse = if (!video) onCollapse else null,
                            onOpenNowPlaying = onOpenNowPlaying,
                            onSetVideoView = onSetVideoView,
                            onDismiss = { moreOpen = false },
                        )
                    }
                }
            }
        }
    }
}

/** What the bar's two text lines say; a change in any of it fades to the new lines. */
internal data class BarLine(val title: String, val subtitle: String, val notice: Boolean)

/**
 * The bar when a screen needs the room (a ladder is open): a slim pill at the bottom right with a small cover, the
 * title and artist, and a play/pause button wearing a ring that fills as the track plays. Click or Enter opens the
 * full Now Playing view; the play button keeps its own click.
 */
@Composable
private fun SlimPill(state: MusicPlayerState, onOpenNowPlaying: () -> Unit, player: MusicPlayer, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    val cover = state.album?.thumbnailUrl
    val fraction = if (state.durationMs > 0L) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    Row(
        modifier = modifier
            .height(MusicBarSlimHeight - MusicBarBottomMargin)
            .widthIn(max = 480.dp)
            .shadow(12.dp, shape, clip = false, ambientColor = colors.accent.copy(alpha = 0.40f), spotColor = colors.accent)
            .clip(shape)
            .background(lerp(colors.surfaceCard, colors.accent, 0.16f))
            .border(
                if (focused) BorderStroke(3.dp, colors.focusRing) else BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                shape,
            )
            .clickable(interactionSource = source, indication = null, onClick = onOpenNowPlaying)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
            if (cover != null) {
                NuvioAsyncImage(model = cover, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = colors.textMuted)
            }
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
            Text(
                text = state.track?.title.orEmpty().ifBlank { "Now playing" },
                color = colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.notice ?: subtitle(state),
                color = if (state.notice != null) colors.warning else colors.textSecondary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.size(52.dp).clip(CircleShape).clickable { player.togglePlayback() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val width = 3.dp.toPx()
                val topLeft = Offset(width / 2, width / 2)
                val arc = Size(size.width - width, size.height - width)
                drawArc(Color.White.copy(alpha = 0.20f), 0f, 360f, false, topLeft, arc, style = Stroke(width))
                drawArc(colors.accent, -90f, 360f * fraction, false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accentFill), contentAlignment = Alignment.Center) {
                if (state.isLoading && state.playWhenReady) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = colors.onAccent, strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playWhenReady) "Pause" else "Play",
                        tint = colors.onAccent,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

internal fun subtitle(state: MusicPlayerState): String {
    val artist = (state.track?.artists?.takeIf { it.isNotEmpty() } ?: state.album?.artists.orEmpty())
        .joinToString(", ") { it.name }
    return listOf(artist, state.album?.title.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
}

/** The three controls that stay on the bar: previous, play or pause, next. */
@Composable
private fun Transport(state: MusicPlayerState, player: MusicPlayer) {
    val colors = MaterialTheme.nuvio.colors
    IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) {
        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = colors.textPrimary, modifier = Modifier.size(34.dp))
    }
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(colors.accentFill)
            .clickable { player.togglePlayback() },
        contentAlignment = Alignment.Center,
    ) {
        if (state.isLoading && state.playWhenReady) {
            CircularProgressIndicator(Modifier.size(30.dp), color = colors.onAccent, strokeWidth = 3.dp)
        } else {
            Icon(
                imageVector = if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.playWhenReady) "Pause" else "Play",
                tint = colors.onAccent,
                modifier = Modifier.size(42.dp),
            )
        }
    }
    IconButton(onClick = { player.next() }, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
        Icon(
            Icons.Rounded.SkipNext,
            contentDescription = "Next",
            tint = if (state.hasNext) colors.textPrimary else colors.textDisabled,
            modifier = Modifier.size(34.dp),
        )
    }
}

/**
 * The "more" button at the bar's right end. It owns the popup so the panel opens just above it, right-aligned with it,
 * and (when the panel is open) shows in the accent colour.
 */
@Composable
internal fun MoreButton(open: Boolean, onToggle: () -> Unit, panel: @Composable () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (focused || open) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                .then(if (focused) Modifier.border(BorderStroke(2.dp, colors.accent), CircleShape) else Modifier)
                .clickable(interactionSource = source, indication = null, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MoreHoriz,
                contentDescription = "More controls",
                tint = if (open) colors.accent else colors.textSecondary,
                modifier = Modifier.size(32.dp),
            )
        }
        if (open) panel()
    }
}

/** Puts the panel above its anchor with the right edges lined up, kept inside the window. */
private class AbovePositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * Everything that left the bar: shuffle, lyrics, the audio/video switch (and, for a real video, its size), volume,
 * hiding the bar and closing the player. Up and Down move between rows; Escape or a click outside closes it.
 * The first row takes focus when the panel opens, so a d-pad can use it straight away.
 */
@Composable
internal fun MorePanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    videoView: MusicVideoView,
    onCollapse: (() -> Unit)?,
    onOpenNowPlaying: () -> Unit,
    onSetVideoView: (MusicVideoView) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val video = state.mode == MusicPlayMode.Video
    val gap = with(LocalDensity.current) { 12.dp.roundToPx() }
    val first = remember { FocusRequester() }
    Popup(
        popupPositionProvider = remember(gap) { AbovePositionProvider(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // The popup has its own composition, so the focus request has to run in here, once the first row exists.
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        val shape = RoundedCornerShape(24.dp)
        Column(
            modifier = Modifier
                .width(380.dp)
                .shadow(16.dp, shape, clip = false, ambientColor = colors.accent.copy(alpha = 0.35f), spotColor = colors.accent)
                .clip(shape)
                .background(lerp(colors.surfaceCard, colors.accent, 0.10f))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PanelRow(
                icon = Icons.Rounded.Shuffle,
                label = "Shuffle",
                value = if (state.shuffled) "On" else "Off",
                active = state.shuffled,
                modifier = Modifier.focusRequester(first),
                onClick = { player.setShuffled(!state.shuffled) },
            )
            PanelRow(Icons.Rounded.Lyrics, "Lyrics") { onDismiss(); onOpenNowPlaying() }
            PanelRow(
                icon = if (video) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                label = if (video) "Switch to audio" else "Switch to video",
            ) {
                onDismiss()
                player.switchMode(if (video) MusicPlayMode.Audio else MusicPlayMode.Video)
            }
            if (video && !state.lyricVideo) {
                val (label, target) = when (videoView) {
                    MusicVideoView.Bar -> "Expand video" to MusicVideoView.Expanded
                    MusicVideoView.Expanded -> "Fullscreen video" to MusicVideoView.Fullscreen
                    MusicVideoView.Fullscreen -> "Exit fullscreen" to MusicVideoView.Expanded
                }
                PanelRow(Icons.Rounded.OpenInFull, label) { onDismiss(); onSetVideoView(target) }
            }
            val style = rememberMusicBarStyle()
            PanelRow(
                icon = Icons.Rounded.Dashboard,
                label = "Player style",
                value = if (style == MusicBarStyle.Corner) "Corner card" else "Bottom bar",
            ) {
                onDismiss()
                setMusicBarStyle(if (style == MusicBarStyle.Corner) MusicBarStyle.Bar else MusicBarStyle.Corner)
            }
            VolumeRow(state.volume, onChange = { player.setVolume(it.coerceIn(0, 100)) })
            if (onCollapse != null) {
                PanelRow(Icons.Rounded.KeyboardArrowDown, "Hide player bar") { onDismiss(); onCollapse() }
            }
            PanelRow(Icons.Rounded.Close, "Close player") { onDismiss(); player.stop() }
        }
    }
}

/** One row of the panel: an icon, a label and an optional value. The focused row is lit and ringed. */
@Composable
private fun PanelRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (focused) Modifier.border(BorderStroke(2.dp, colors.accent), shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) colors.accent else colors.textSecondary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = colors.textPrimary, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, color = if (active) colors.accent else colors.textSecondary, fontSize = 18.sp, maxLines = 1)
        }
    }
}

/** Volume as a panel row. Left and Right step by 5 once the slider has focus. */
@Composable
private fun VolumeRow(volume: Int, onChange: (Int) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.VolumeUp, null, tint = colors.textSecondary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        SeekBar(
            fraction = volume / 100f,
            onSeek = { onChange((it * 100f).toInt()) },
            modifier = Modifier.weight(1f),
            onNudge = { direction -> onChange(volume + direction * 5) },
        )
        Spacer(Modifier.width(12.dp))
        Text("$volume", color = colors.textSecondary, fontSize = 18.sp, maxLines = 1, modifier = Modifier.width(36.dp))
    }
}

/** A small text button for the expanded video's header and the Now Playing screen. */
@Composable
internal fun MusicTextChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = MaterialTheme.nuvio.colors.textSecondary,
        fontSize = 16.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * A thick, rounded bar you can click, drag or step with the d-pad. [fraction] and what [onSeek] receives are both
 * 0..1; [onNudge] gets -1 or +1 for Left and Right. Focused or dragged, the track grows and a handle appears.
 */
@Composable
private fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onNudge: ((Int) -> Unit)? = null,
) {
    val colors = MaterialTheme.nuvio.colors
    // While dragging, follow the finger rather than the (older) playback position.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val active = focused || dragging != null
    val trackHeight by animateDpAsState(if (active) 10.dp else 6.dp)
    val track = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .height(28.dp)
            .focusable(enabled = enabled, interactionSource = source)
            .onKeyEvent { event ->
                if (onNudge == null || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onNudge(-1); true }
                    Key.DirectionRight -> { onNudge(1); true }
                    else -> false
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var last = 0f
                detectDragGestures(
                    onDragStart = { offset: Offset ->
                        last = (offset.x / size.width).coerceIn(0f, 1f)
                        dragging = last
                    },
                    onDrag = { change, _ ->
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragging = last
                    },
                    onDragEnd = {
                        onSeek(last)
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(trackHeight).clip(track).background(Color.White.copy(alpha = 0.20f)))
        Box(Modifier.fillMaxWidth(shown).height(trackHeight).clip(track).background(colors.accent))
        if (active && enabled) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(shown.coerceIn(0.001f, 0.999f)))
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.weight((1f - shown).coerceIn(0.001f, 0.999f)))
            }
        }
    }
}
