@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.spydermahn.nuvioremote

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class Tab(val label: String) { Remote("Remote"), Watch("Watch"), Music("Music") }

/**
 * Once connected: the remote, and the PC's library to browse, under Nuvio's pill tab bar. Choosing a title or playing an
 * album goes back to the remote, since that is what drives the TV next (picking a source, skipping a track).
 */
@Composable
fun ConnectedScreen(state: UiState, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    var tab by rememberSaveable { mutableStateOf(Tab.Remote) }
    if (tab != Tab.Remote) BackHandler { tab = Tab.Remote }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Remote -> RemoteTab(state, viewModel)
                Tab.Watch -> VideoLibraryScreen(state, viewModel, onOpened = { tab = Tab.Remote })
                Tab.Music -> MusicLibraryScreen(state, viewModel, onPlayed = { tab = Tab.Remote })
            }
            state.notice?.let { notice ->
                Text(
                    notice,
                    color = g.text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .glass(RoundedCornerShape(16.dp), fill = Color(0xF01B1C22))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        PillTabs(
            items = Tab.entries,
            selected = tab,
            label = { it.label },
            onSelect = { tab = it },
            icon = {
                when (it) {
                    Tab.Remote -> Icons.Rounded.SettingsRemote
                    Tab.Watch -> Icons.Rounded.Movie
                    Tab.Music -> Icons.Rounded.LibraryMusic
                }
            },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )
    }
}

// ------------------------------------------------------------------------------------------------ The remote

private enum class Showing { Browsing, Film, Music }

/**
 * The remote changes with what the TV is doing. Browsing: the touchpad (or D-pad) and four keys. A film: what is playing,
 * its subtitle and audio as tiles, the transport, a smaller pad for the player's menus. Music: the cover and the
 * transport, with the pad one tap away.
 */
@Composable
private fun RemoteTab(state: UiState, viewModel: RemoteViewModel) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var padOverMusic by rememberSaveable { mutableStateOf(false) }
    val playing = state.nowPlaying
    val showing = when {
        playing == null -> Showing.Browsing
        playing.kind == "music" -> Showing.Music
        else -> Showing.Film
    }
    val search = {
        viewModel.press(RemoteKey.Search)
        typing = true
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        RemoteHeader(
            state = state,
            status = when (showing) {
                Showing.Browsing -> "Connected · browsing"
                Showing.Film -> "Playing a film"
                Showing.Music -> "Playing music"
            },
            onRetry = viewModel::reconnect,
            trailing = {
                if (showing == Showing.Music) {
                    GlassTag(
                        if (padOverMusic) "Now playing" else "Touchpad",
                        icon = if (padOverMusic) Icons.Rounded.MusicNote else Icons.Rounded.CropSquare,
                        onClick = { padOverMusic = !padOverMusic },
                    )
                }
            },
        )
        if (DemoTv.isDemo(state.current)) DemoLogStrip()
        if (typing) TypingPanel(viewModel, onClose = { typing = false })

        when {
            showing == Showing.Film && playing != null -> FilmBody(playing, state.cover, state.padMode, viewModel, onMore = { more = true })
            showing == Showing.Music && playing != null && !padOverMusic -> MusicBody(playing, state.cover, viewModel, onSearch = search, onMore = { more = true })
            else -> BrowseBody(state.padMode, viewModel, onSearch = search, onMore = { more = true })
        }
    }

    if (more) {
        MoreSheet(
            state = state,
            viewModel = viewModel,
            onType = {
                more = false
                typing = true
            },
            onDismiss = { more = false },
        )
    }
}

@Composable
private fun RemoteHeader(state: UiState, status: String, onRetry: () -> Unit, trailing: @Composable () -> Unit) {
    val g = LocalGlass.current
    val (dot, label) = when (state.link) {
        Link.Connected -> g.success to status
        Link.Connecting -> g.warning to "Connecting…"
        Link.Offline -> g.danger to "Offline · tap to retry"
    }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .pointerInput(state.link) { detectTapGestures(onTap = { if (state.link == Link.Offline) onRetry() }) },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.size(8.dp))
                GlassLabel(label)
            }
            Text(
                (state.current?.name ?: "Nuvio").uppercase(),
                color = g.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.message?.let { Text(it, color = g.danger, fontSize = 13.sp) }
        }
        trailing()
    }
}

@Composable
private fun BrowseBody(padMode: PadMode, viewModel: RemoteViewModel, onSearch: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        NavPanel(padMode, viewModel, Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceAround) {
            GlassKey(Icons.AutoMirrored.Rounded.Undo, "Back") { viewModel.press(RemoteKey.Back) }
            GlassKey(Icons.Rounded.Home, "Home") { viewModel.press(RemoteKey.Home) }
            GlassKey(Icons.Rounded.Search, "Search", active = true, onPress = onSearch)
            GlassKey(Icons.Rounded.MoreHoriz, "More", onPress = onMore)
        }
    }
}

@Composable
private fun FilmBody(playing: NowPlaying, cover: Bitmap?, padMode: PadMode, viewModel: RemoteViewModel, onMore: () -> Unit) {
    // Scrolls rather than squeezing: with everything above it, a weighted pad was left with no room at all on a phone.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NowPlayingPanel(playing, cover, viewModel, Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Subtitles", playing.subtitleTrack ?: "Tap for the next", Modifier.weight(1f)) {
                viewModel.press(RemoteKey.Subtitles)
            }
            StatTile("Audio", playing.audioTrack ?: "Tap for the next", Modifier.weight(1f)) {
                viewModel.press(RemoteKey.Audio)
            }
        }
        Transport(playing, viewModel, Modifier.padding(top = 14.dp))
        NavPanel(padMode, viewModel, Modifier.height(FILM_PAD_HEIGHT).fillMaxWidth().padding(top = 12.dp), compact = true)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp, start = 24.dp, end = 24.dp), horizontalArrangement = Arrangement.SpaceAround) {
            GlassKey(Icons.AutoMirrored.Rounded.Undo, "Back", size = 56.dp) { viewModel.press(RemoteKey.Back) }
            GlassKey(Icons.AutoMirrored.Rounded.List, "Sources", size = 56.dp) { viewModel.press(RemoteKey.Sources) }
            GlassKey(Icons.Rounded.DarkMode, "Dark", size = 56.dp) { viewModel.setDark(true) }
            GlassKey(Icons.Rounded.MoreHoriz, "More", size = 56.dp, onPress = onMore)
        }
    }
}

@Composable
private fun MusicBody(playing: NowPlaying, cover: Bitmap?, viewModel: RemoteViewModel, onSearch: () -> Unit, onMore: () -> Unit) {
    val g = LocalGlass.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.padding(top = 12.dp).size(196.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2B2320)),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(cover.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = g.textMuted, modifier = Modifier.size(48.dp))
            }
        }
        GlassLabel("Now playing on the TV", Modifier.padding(top = 18.dp))
        DisplayTitle(playing.title, Modifier.padding(top = 8.dp).basicMarquee(initialDelayMillis = 1_500), maxLines = 1)
        playing.subtitle?.let { line ->
            // "Artist · Album": the artist in the accent, as on the TV's album page.
            val artist = line.substringBefore(" · ")
            val rest = line.substringAfter(" · ", "")
            Row(Modifier.padding(top = 6.dp).basicMarquee(initialDelayMillis = 1_500)) {
                Text(artist, color = g.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (rest.isNotEmpty()) Text(" · $rest", color = g.textSecondary, fontSize = 14.sp, maxLines = 1)
            }
        }
        Seek(playing, viewModel, Modifier.padding(top = 14.dp))
        Transport(playing, viewModel, Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp, start = 24.dp, end = 24.dp), horizontalArrangement = Arrangement.SpaceAround) {
            GlassKey(Icons.AutoMirrored.Rounded.Undo, "Back", size = 52.dp) { viewModel.press(RemoteKey.Back) }
            GlassKey(Icons.Rounded.Search, "Search", size = 52.dp, onPress = onSearch)
            GlassKey(Icons.Rounded.DarkMode, "Dark", size = 52.dp) { viewModel.setDark(true) }
            GlassKey(Icons.Rounded.MoreHoriz, "More", size = 52.dp, onPress = onMore)
        }
    }
}

/** What is playing, for a film: poster, the serif title, the line under it and the seek bar. */
@Composable
private fun NowPlayingPanel(playing: NowPlaying, cover: Bitmap?, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A3540)),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(cover.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.Movie, contentDescription = null, tint = g.textMuted)
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassLabel("Now playing", Modifier.weight(1f), icon = Icons.Rounded.AutoAwesome)
                    if (!playing.hasTimeline) GlassTag("Live")
                }
                DisplayTitle(playing.title, size = 22, maxLines = 2)
                playing.subtitle?.let {
                    Text(it, color = g.textSecondary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.basicMarquee(initialDelayMillis = 1_500))
                }
            }
        }
        Seek(playing, viewModel)
    }
}

/** The seek bar and its times; it moves on by itself between the PC's updates and seeks the PC when let go. */
@Composable
private fun Seek(playing: NowPlaying, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    if (!playing.hasTimeline) return
    val g = LocalGlass.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playing) {
        now = SystemClock.elapsedRealtime()
        while (playing.isPlaying) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    var dragging by remember(playing.title) { mutableStateOf<Long?>(null) }
    val shown = (dragging ?: playing.positionAt(now)).coerceIn(0L, playing.durationMs)
    Column(modifier.fillMaxWidth()) {
        SeekBar(
            fraction = shown.toFloat() / playing.durationMs.toFloat(),
            onScrub = { fraction -> dragging = (fraction * playing.durationMs).toLong() },
            onScrubEnd = {
                dragging?.let(viewModel::seek)
                dragging = null
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shown), color = g.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("−" + formatTime(playing.durationMs - shown), color = g.textSecondary, fontSize = 12.sp)
        }
    }
}

/** Back 10s · the one play/pause, tinted and showing what a press does · forward 10s; previous and next for music. */
@Composable
private fun Transport(playing: NowPlaying, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (playing.canSkip) {
            GlassKey(Icons.Rounded.SkipPrevious, null, size = 60.dp) { viewModel.press(RemoteKey.Previous) }
        } else {
            GlassKey(Icons.Rounded.Replay10, null, size = 60.dp, repeat = true) { viewModel.press(RemoteKey.SeekBack) }
        }
        Spacer(Modifier.size(28.dp))
        GlassKey(
            if (playing.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            null,
            size = 88.dp,
            active = true,
        ) { viewModel.press(RemoteKey.PlayPause) }
        Spacer(Modifier.size(28.dp))
        if (playing.canSkip) {
            GlassKey(Icons.Rounded.SkipNext, null, size = 60.dp) { viewModel.press(RemoteKey.Next) }
        } else {
            GlassKey(Icons.Rounded.Forward10, null, size = 60.dp, repeat = true) { viewModel.press(RemoteKey.SeekForward) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Touchpad and D-pad

/** The navigation panel: the touchpad or the D-pad, with a tag in its corner to switch, remembered for next time. */
@Composable
private fun NavPanel(padMode: PadMode, viewModel: RemoteViewModel, modifier: Modifier, compact: Boolean = false) {
    Column(modifier.glass(RoundedCornerShape(if (compact) 22.dp else 26.dp)).padding(if (compact) 12.dp else 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassLabel(
                if (padMode == PadMode.Touchpad) "Touchpad" else "D-pad",
                Modifier.weight(1f),
                icon = if (padMode == PadMode.Touchpad) Icons.Rounded.OpenWith else Icons.Rounded.RadioButtonChecked,
            )
            GlassTag(
                if (padMode == PadMode.Touchpad) "D-pad" else "Touchpad",
                onClick = { viewModel.setPadMode(if (padMode == PadMode.Touchpad) PadMode.DPad else PadMode.Touchpad) },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (padMode == PadMode.Touchpad) {
                Touchpad(onKey = viewModel::press, compact = compact, modifier = Modifier.fillMaxSize())
            } else {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DPad(viewModel, size = minOf(maxWidth, maxHeight, 300.dp))
                }
            }
        }
    }
}

/**
 * Swipe to move, tap for OK. Every [STEP] of travel along the main direction sends one arrow, with a tick under the
 * thumb, so a long swipe walks along a row and a short flick moves one tile.
 */
@Composable
private fun Touchpad(onKey: (RemoteKey) -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val haptics = LocalHapticFeedback.current
    val send by rememberUpdatedState(onKey)
    val stepPx = with(LocalDensity.current) { STEP.toPx() }
    Box(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    send(RemoteKey.Select)
                })
            }
            .pointerInput(Unit) {
                var x = 0f
                var y = 0f
                detectDragGestures(onDragStart = {
                    x = 0f
                    y = 0f
                }) { change, drag ->
                    change.consume()
                    x += drag.x
                    y += drag.y
                    while (abs(x) >= stepPx || abs(y) >= stepPx) {
                        val key = if (abs(x) >= abs(y)) {
                            val k = if (x > 0) RemoteKey.Right else RemoteKey.Left
                            x -= if (x > 0) stepPx else -stepPx
                            y = 0f // a sideways swipe never drifts into an up or down
                            k
                        } else {
                            val k = if (y > 0) RemoteKey.Down else RemoteKey.Up
                            y -= if (y > 0) stepPx else -stepPx
                            x = 0f
                            k
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        send(key)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compact) Icon(Icons.Rounded.OpenWith, contentDescription = null, tint = Color.White.copy(alpha = 0.28f), modifier = Modifier.size(48.dp))
            Text("Swipe to move", color = Color(0xFFC9CDD2), fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold)
            Text(if (compact) "For the player's menus · tap for OK" else "Tap for OK", color = g.textMuted, fontSize = 12.sp)
        }
    }
}

private val STEP = 44.dp

/** The film screen's pad: room for the D-pad or a usable touchpad, whatever is above it. */
private val FILM_PAD_HEIGHT = 250.dp

@Composable
private fun DPad(viewModel: RemoteViewModel, size: androidx.compose.ui.unit.Dp) {
    val g = LocalGlass.current
    Box(
        Modifier.size(size).glass(CircleShape, fill = Color.White.copy(alpha = 0.04f), edge = Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        val arrow = size * 0.3f
        Box(Modifier.align(Alignment.TopCenter).size(arrow).pressable(repeat = true) { viewModel.press(RemoteKey.Up) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ExpandLess, contentDescription = "Up", tint = g.text, modifier = Modifier.size(36.dp))
        }
        Box(Modifier.align(Alignment.BottomCenter).size(arrow).pressable(repeat = true) { viewModel.press(RemoteKey.Down) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ExpandMore, contentDescription = "Down", tint = g.text, modifier = Modifier.size(36.dp))
        }
        Box(Modifier.align(Alignment.CenterStart).size(arrow).pressable(repeat = true) { viewModel.press(RemoteKey.Left) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Left", tint = g.text, modifier = Modifier.size(36.dp))
        }
        Box(Modifier.align(Alignment.CenterEnd).size(arrow).pressable(repeat = true) { viewModel.press(RemoteKey.Right) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Right", tint = g.text, modifier = Modifier.size(36.dp))
        }
        Box(
            Modifier
                .size(size * 0.38f)
                .glass(CircleShape, fill = g.tint, edge = g.tintEdge)
                .pressable { viewModel.press(RemoteKey.Select) },
            contentAlignment = Alignment.Center,
        ) {
            Text("OK", color = g.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ------------------------------------------------------------------------------------------------ More and typing

/** Everything that is not on the screen: volume and mute, typing, the pad switch, another PC. */
@Composable
private fun MoreSheet(state: UiState, viewModel: RemoteViewModel, onType: () -> Unit, onDismiss: () -> Unit) {
    val g = LocalGlass.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF14151A),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            GlassLabel("More")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GlassKey(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { viewModel.press(RemoteKey.Mute) }
                GlassKey(Icons.AutoMirrored.Rounded.VolumeDown, "Vol −", repeat = true) { viewModel.press(RemoteKey.VolumeDown) }
                GlassKey(Icons.AutoMirrored.Rounded.VolumeUp, "Vol +", repeat = true) { viewModel.press(RemoteKey.VolumeUp) }
                GlassKey(Icons.Rounded.Keyboard, "Type", onPress = onType)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GlassKey(
                    if (state.padMode == PadMode.Touchpad) Icons.Rounded.RadioButtonChecked else Icons.Rounded.OpenWith,
                    if (state.padMode == PadMode.Touchpad) "D-pad" else "Touchpad",
                ) {
                    viewModel.setPadMode(if (state.padMode == PadMode.Touchpad) PadMode.DPad else PadMode.Touchpad)
                    onDismiss()
                }
                GlassKey(Icons.Rounded.Devices, "Other PC") {
                    onDismiss()
                    viewModel.showPicker()
                }
                GlassKey(Icons.Rounded.DarkMode, "Dark") {
                    onDismiss()
                    viewModel.setDark(true)
                }
                Spacer(Modifier.size(60.dp))
            }
            if (DemoTv.isDemo(state.current)) {
                GlassLabel("Demo TV")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassKey(Icons.Rounded.Movie, "Film") { DemoTv.playFilm(); onDismiss() }
                    GlassKey(Icons.Rounded.MusicNote, "Album") { DemoTv.playMusic(); onDismiss() }
                    GlassKey(Icons.Rounded.Stop, "Stop") { DemoTv.stop(); onDismiss() }
                    GlassKey(Icons.Rounded.Palette, "Theme") { DemoTv.nextTheme() }
                }
            }
            Text("The phone's volume buttons turn the TV up and down too.", color = g.textMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Typing goes to the PC as you type: added letters as text, deleted ones as backspaces, so the PC's search box follows
 * the phone's exactly. Return sends Enter.
 */
@Composable
private fun TypingPanel(viewModel: RemoteViewModel, onClose: () -> Unit) {
    val g = LocalGlass.current
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { next ->
                val common = text.commonPrefixWith(next).length
                val removed = text.length - common
                if (removed > 0) viewModel.backspace(removed)
                if (next.length > common) viewModel.type(next.substring(common))
                text = next
            },
            placeholder = { Text("Type into Nuvio") },
            singleLine = true,
            shape = RoundedCornerShape(23.dp),
            modifier = Modifier.weight(1f).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.press(RemoteKey.Enter) }),
        )
        IconButton(onClick = { viewModel.press(RemoteKey.Enter) }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, contentDescription = "Enter", tint = g.accent)
        }
        IconButton(onClick = {
            text = ""
            onClose()
        }) {
            Icon(Icons.Rounded.Close, contentDescription = "Close keyboard", tint = g.textMuted)
        }
    }
}

/** On the demo TV: the last few things it was sent, so a press can be checked without a TV to look at. */
@Composable
private fun DemoLogStrip() {
    val g = LocalGlass.current
    val log by DemoTv.log.collectAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .glass(RoundedCornerShape(14.dp), fill = g.tint, edge = g.tintEdge)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        GlassLabel("Demo TV received")
        Text(
            log.takeLast(3).reversed().joinToString("  ·  ").ifEmpty { "Nothing yet" },
            color = g.text,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).basicMarquee(initialDelayMillis = 800),
        )
    }
}
