package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.musicKeyFocusRing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import com.nuvio.app.features.music.library.MusicGlassRaised
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.runtime.collectAsState
import com.nuvio.app.features.music.library.MusicAccountShelves
import com.nuvio.app.features.music.library.MusicLikedSongs
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.library.musicGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.nuvio.app.features.music.identity.ArtworkPaletteMath
import com.nuvio.app.features.music.identity.MusicPaletteResolver
import com.nuvio.app.features.music.visualiser.TrackMapLive

/*
 * fork:music
 *
 * The minimised player: a small glass notch at the bottom of the window, so the rest of the app can
 * be used while the music plays. Hovering it (or giving it keyboard focus) opens it into a docked
 * panel with what is playing, the time, play / pause and skip, and the next four tracks. Clicking
 * the notch, or the panel's restore button, brings the full player back. Dragged into the top half
 * of the window it opens downwards instead, the controls staying where the notch was.
 */

/** How long the pointer may leave the panel before it folds back, so a brush past its edge does not shut it. */
private const val FOLD_DELAY_MS = 350L

private const val UP_NEXT_COUNT = 4

private const val DOCK_RESIZE_MS = 170
private const val DOCK_FADE_IN_MS = 120
private const val DOCK_FADE_IN_DELAY_MS = 50
private const val DOCK_FADE_OUT_MS = 70

/** Left / Right on the dock's seek bar move this far. */
private const val DOCK_NUDGE_MS = 5_000L

@Composable
internal fun MusicDockNotch(
    state: MusicPlayerState,
    player: MusicPlayer,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set while a real video plays: the open dock offers to show it. */
    onExpandVideo: (() -> Unit)? = null,
    /** The notch sits in the top half: the panel grows down from it rather than up. */
    opensDown: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    // Focus anywhere in the dock (the notch itself or one of the open panel's buttons) keeps it open.
    var focusWithin by remember { mutableStateOf(false) }
    // Reaching the dock with the keyboard goes straight on to the open panel's play button, so the arrows and Enter
    // work on its controls; the dock itself, a box around them, has nothing to press.
    val panelEntry = remember { FocusRequester() }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        repeat(6) {
            delay(32)
            if (runCatching { panelEntry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // Opens at once on hover or focus; folds back a moment after both are gone.
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, focusWithin) {
        if (hovered || focusWithin) {
            open = true
        } else {
            delay(FOLD_DELAY_MS)
            open = false
        }
    }
    val shape = RoundedCornerShape(if (open) 22.dp else 20.dp) // the folded notch is 40dp tall: a full pill
    val colors = MaterialTheme.nuvio.colors
    Box(
        modifier
            .shadow(12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .background(NuvioGlass.night(colors.background, colors.accent))
            .musicGlass(shape, keyFocused = focused)
            .hoverable(source)
            .onFocusChanged { focusWithin = it.hasFocus }
            // The dock's one focus stop (the folded notch's own click is mouse-only): focusing it opens the panel, the
            // arrows go on into the panel's buttons, and Enter on the dock itself brings the full player back.
            .focusable(interactionSource = source)
            .onKeyEvent { event ->
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!focused || !enter) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) onRestore()
                true
            },
    ) {
        // A short eased resize rather than the default spring, which took ~0.4s to settle and read as lag; the
        // old content is gone before the box has moved much, and the new one fades in as it lands.
        AnimatedContent(
            targetState = open,
            transitionSpec = {
                fadeIn(tween(DOCK_FADE_IN_MS, delayMillis = DOCK_FADE_IN_DELAY_MS)) togetherWith
                    fadeOut(tween(DOCK_FADE_OUT_MS)) using
                    SizeTransform(clip = true) { _, _ -> tween(DOCK_RESIZE_MS, easing = FastOutSlowInEasing) }
            },
            // Pinned to the notch's edge, so the panel unfolds away from it.
            contentAlignment = if (opensDown) Alignment.TopCenter else Alignment.BottomCenter,
            label = "dockNotch",
        ) { expanded ->
            if (expanded) DockPanel(state, player, onRestore, onExpandVideo, opensDown, panelEntry) else Notch(state, onRestore)
        }
    }
}

/** Folded: a cover dot, the title and a small arrow, the whole pill one restore button. */
@Composable
private fun Notch(state: MusicPlayerState, onRestore: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        Modifier
            .height(40.dp)
            .widthIn(min = 180.dp, max = 320.dp)
            .focusProperties { canFocus = false }
            .clickable(onClick = onRestore)
            // The round cover sits inside the pill's curve with the same gap all round, clear of the edge.
            .padding(start = 7.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Cover(state.coverUrl, size = 26, round = true)
        Text(
            state.track?.title.orEmpty().ifBlank { "Now playing" },
            style = MaterialTheme.typography.labelLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Fills the pill, so the arrow always sits at its right end whatever the title's length.
            modifier = Modifier.weight(1f),
        )
        // A radio nearly out of songs asks to keep going: the question waits in the open panel, and this dot says so.
        val asking by RadioKeepGoing.offer.collectAsState()
        if (asking != null) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
        Icon(
            Icons.Rounded.KeyboardArrowUp,
            contentDescription = if (state.playWhenReady) "Show the player, playing" else "Show the player, paused",
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Open: what is coming up, then the track, the time and the controls. The controls sit where the notch was, so
 * the list is on top when the panel grows upwards and below them when it grows down from the top of the window.
 */
@Composable
private fun DockPanel(
    state: MusicPlayerState,
    player: MusicPlayer,
    onRestore: () -> Unit,
    onExpandVideo: (() -> Unit)?,
    opensDown: Boolean,
    entry: FocusRequester,
) {
    Column(Modifier.width(520.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DockKeepGoing()
        if (opensDown) {
            DockNowPlaying(state, player, onRestore, onExpandVideo, entry)
            DockDivider()
            DockUpNext(state, player)
        } else {
            DockUpNext(state, player)
            DockDivider()
            DockNowPlaying(state, player, onRestore, onExpandVideo, entry)
        }
    }
}

@Composable
private fun DockDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(NuvioGlass.EdgeColor))
}

/** What the open dock says the queue is: the radio, playlist or album it plays from. */
private fun upNextSource(state: MusicPlayerState): String? =
    playingFromLabel(state.album)?.removePrefix("From ") ?: state.albumTitle?.takeIf { it.isNotBlank() }

/**
 * The next few tracks under a header naming where they come from, with the queue's own buttons (shuffle, repeat, clear)
 * on its right. A row has the track's cover; a click plays it, and under the pointer the duration gives way to
 * "Play next" and "Remove".
 */
@Composable
private fun DockUpNext(state: MusicPlayerState, player: MusicPlayer) {
    val colors = MaterialTheme.nuvio.colors
    val upNext = remember(state.track, state.trackIndex, state.shuffled, state.hasNext) { player.upNext(UP_NEXT_COUNT) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            listOfNotNull("Up next", upNextSource(state)).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        DockButton(Icons.Rounded.Shuffle, if (state.shuffled) "Shuffle on" else "Shuffle off", active = state.shuffled, small = true) {
            player.setShuffled(!state.shuffled)
        }
        DockButton(
            if (state.repeat == MusicRepeat.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            when (state.repeat) {
                MusicRepeat.Off -> "Repeat off"
                MusicRepeat.All -> "Repeat the queue"
                MusicRepeat.One -> "Repeat this song"
            },
            active = state.repeat != MusicRepeat.Off,
            small = true,
        ) {
            player.setRepeat(
                when (state.repeat) {
                    MusicRepeat.Off -> MusicRepeat.All
                    MusicRepeat.All -> MusicRepeat.One
                    MusicRepeat.One -> MusicRepeat.Off
                },
            )
        }
        DockButton(Icons.Rounded.ClearAll, "Clear the queue", enabled = upNext.isNotEmpty(), small = true) { player.clearUpNext() }
    }
    if (upNext.isEmpty()) {
        Text(
            if (state.hasNext) "The next album starts after this one" else "Nothing after this track",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
    upNext.forEach { track ->
        // A click skips straight to the track.
        val rowSource = remember { MutableInteractionSource() }
        val rowHovered by rowSource.collectIsHoveredAsState()
        val rowShape = RoundedCornerShape(8.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(rowShape)
                .background(if (rowHovered) MusicGlassRaised else Color.Transparent)
                .hoverable(rowSource)
                .musicKeyFocusRing(rowShape)
                .clickable(interactionSource = rowSource, indication = null) { player.playUpNext(track) }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Cover(track.coverUrl ?: state.album?.thumbnailUrl, size = 30)
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (rowHovered) {
                RowAction("Play next") { player.playTrackNext(track) }
                RowAction("Remove") { player.removeUpNext(track) }
            } else {
                Text(
                    formatMusicTime(track.durationSeconds * 1000L),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A small rounded tag beside the title: the quality, or Radio. */
@Composable
private fun DockPill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** The cover's colour, as the Artwork palette's accent; null until it is read (or when the art is grey or missing). */
@Composable
private fun rememberCoverTint(url: String?): Color? {
    val context = coil3.compose.LocalPlatformContext.current
    var tint by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val seed = withContext(Dispatchers.Default) { MusicPaletteResolver.sampleOne(context, url) } ?: return@LaunchedEffect
        tint = Color(ArtworkPaletteMath.derive(seed).accent)
    }
    return tint
}

/** The radio's "keep going?" question, inside the open panel instead of a pop-up over the app. */
@Composable
private fun DockKeepGoing() {
    val colors = MaterialTheme.nuvio.colors
    val offer by RadioKeepGoing.offer.collectAsState()
    val o = offer ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Keep ${o.title} radio going?",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RowAction("Keep going") { RadioKeepGoing.yes() }
        RowAction("No") { RadioKeepGoing.no() }
    }
}

/** Likes the song on YouTube Music (and un-likes it); only there when the account is signed in. */
@Composable
private fun DockHeart(state: MusicPlayerState) {
    val videoId = state.track?.videoId ?: return
    val catalog = remember { defaultMusicCatalog() }
    val signedIn = com.nuvio.app.features.youtubemusic.YouTubeMusicAuth.isSignedIn
    LaunchedEffect(signedIn) { if (signedIn) MusicAccountShelves.refresh(catalog) }
    if (!signedIn) return
    val account by MusicAccountShelves.state.collectAsState()
    val overrides by MusicLikedSongs.overrides.collectAsState()
    val liked = MusicLikedSongs.isLiked(videoId, account.liked, overrides)
    DockButton(
        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        if (liked) "Unlike on YouTube Music" else "Like on YouTube Music",
        active = liked,
    ) { MusicLikedSongs.toggle(catalog, videoId, liked) }
}

/** A small text action on an Up next row, shown while the pointer is on it. */
@Composable
private fun RowAction(label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = colors.textPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MusicGlassRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The track, the time and the transport, with the seek bar under them. */
@Composable
private fun DockNowPlaying(
    state: MusicPlayerState,
    player: MusicPlayer,
    onRestore: () -> Unit,
    onExpandVideo: (() -> Unit)?,
    entry: FocusRequester,
) {
    val colors = MaterialTheme.nuvio.colors
    // The played part of the bar takes the cover's colour.
    val tint = rememberCoverTint(state.coverUrl)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Cover(state.coverUrl, size = 56)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    state.track?.title.orEmpty().ifBlank { "Now playing" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isRadioQueue(state.album?.browseId)) DockPill("Radio", colors.accent)
            }
            Text(
                subtitle(state, withQuality = false),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            DockButton(Icons.Rounded.SkipPrevious, "Previous") { player.previous() }
            DockButton(
                icon = if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                description = if (state.playWhenReady) "Pause" else "Play",
                filled = true,
                modifier = Modifier.focusRequester(entry),
            ) { player.togglePlayback() }
            DockButton(Icons.Rounded.SkipNext, "Next", enabled = state.hasNext) { player.next() }
            onExpandVideo?.let { DockButton(Icons.Rounded.OpenInFull, "Expand video", onClick = it) }
            DockHeart(state)
            state.track?.let { track ->
                DockButton(Icons.Rounded.PlaylistAdd, "Add to playlist") {
                    com.nuvio.app.features.music.playlist.PlaylistPicker.request(
                        listOf(with(com.nuvio.app.features.music.playlist.MusicPlaylistTracks) { track.forPlaylist(state.album) }),
                    )
                }
            }
            DockButton(Icons.Rounded.KeyboardArrowUp, "Show the player") { onRestore() }
        }
    }
    // One row under the header: the song's seek bar (a click, drag or Left/Right moves it) in two thirds, the volume
    // in the last, so it can be set without bringing the full player back and does not sit on a row of its own.
    // The elapsed time stands at its start and the time left at its end; hovering shows the time under the pointer.
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MusicSeekControl(state, player, nudgeMs = DOCK_NUDGE_MS, modifier = Modifier.weight(2f), tint = colors.textPrimary, showTimes = true, fill = tint)
        MusicVolumeControl(state, player, sliderWidth = null, modifier = Modifier.weight(1f), tint = colors.textPrimary)
    }
}

@Composable
private fun Cover(url: String?, size: Int, round: Boolean = false) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        Modifier.size(size.dp).clip(if (round) CircleShape else RoundedCornerShape((size / 5).dp)).background(colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            NuvioAsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.MusicNote, null, tint = colors.textMuted, modifier = Modifier.size((size / 2).dp))
        }
    }
}

@Composable
private fun DockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    filled: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** A toggle that is on (shuffle, repeat): drawn in the accent. */
    active: Boolean = false,
    /** The queue header's buttons. */
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Box(
        modifier
            .size(if (small) 30.dp else 36.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(colors.accentFill) else Modifier)
            .musicKeyFocusRing(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                filled -> colors.onAccent
                active && enabled -> colors.accent
                enabled -> colors.textPrimary
                else -> colors.textDisabled
            },
            modifier = Modifier.size(if (small) 18.dp else 22.dp),
        )
    }
}
