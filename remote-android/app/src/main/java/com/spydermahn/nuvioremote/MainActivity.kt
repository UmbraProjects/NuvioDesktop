package com.spydermahn.nuvioremote

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap

class MainActivity : ComponentActivity() {
    private val viewModel: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = RemoteColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = RemoteColors.background) {
                    RemoteApp(viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    /** The phone's volume rocker drives the player's volume while the remote is showing. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val onRemote = viewModel.state.value.screen == Screen.Remote
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VolumeUp
            KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VolumeDown
            else -> null
        }
        if (onRemote && key != null) {
            if (event.action == KeyEvent.ACTION_DOWN) viewModel.press(key)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

internal val Accent = Color(0xFF8AB4FF)
internal val Panel = Color(0xFF1B1D26)
internal val PanelRaised = Color(0xFF262935)
internal val Muted = Color(0xFF9AA0B4)

internal val RemoteColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B1730),
    background = Color(0xFF0E0F14),
    surface = Color(0xFF0E0F14),
    surfaceVariant = Panel,
    onSurface = Color(0xFFE8EAF2),
    onSurfaceVariant = Muted,
)

@Composable
private fun RemoteApp(viewModel: RemoteViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val screen = state.screen) {
            Screen.Pick -> PickScreen(state, viewModel)
            is Screen.Pair -> PairScreen(screen, state, viewModel)
            Screen.Remote -> ConnectedScreen(state, viewModel)
        }
    }
}

// ---------------------------------------------------------------- Picking a PC

@Composable
private fun PickScreen(state: UiState, viewModel: RemoteViewModel) {
    var address by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("Nuvio Remote", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "On the PC, turn on Settings › Keyboard shortcuts › Phone remote. Your phone needs to be on the same Wi-Fi.",
                color = Muted,
                fontSize = 14.sp,
            )
            state.message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Color(0xFFFFB4A9), fontSize = 14.sp)
            }
        }

        if (state.saved.isNotEmpty()) {
            item { SectionLabel("Paired") }
            items(state.saved, key = { "saved-" + it.address }) { pc ->
                PcRow(
                    title = pc.name,
                    subtitle = pc.address,
                    enabled = !state.busy,
                    onClick = { viewModel.connectTo(pc) },
                    trailing = {
                        IconButton(onClick = { viewModel.forget(pc) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Forget ${pc.name}", tint = Muted)
                        }
                    },
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("On this network", modifier = Modifier.weight(1f))
                if (state.scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = viewModel::scan) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Search again", tint = Accent)
                    }
                }
            }
        }
        if (state.found.isEmpty() && !state.scanning) {
            item {
                Text(
                    "No PCs found. Check Phone remote is on in Nuvio, or add the PC by its address below (it's shown on the same settings page).",
                    color = Muted,
                    fontSize = 14.sp,
                )
            }
        }
        items(state.found, key = { "found-${it.host}:${it.port}" }) { pc ->
            PcRow(
                title = pc.name,
                subtitle = "${pc.host}:${pc.port}",
                enabled = !state.busy,
                onClick = { viewModel.connectTo(pc) },
            )
        }

        item {
            SectionLabel("Add by address")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.connectToAddress(address) }),
                )
                Spacer(Modifier.size(10.dp))
                Button(onClick = { viewModel.connectToAddress(address) }, enabled = !state.busy && address.isNotBlank()) {
                    Text("Connect")
                }
            }
            if (state.busy) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Connecting…", color = Muted)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 18.dp, bottom = 4.dp),
        color = Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun PcRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Tv, contentDescription = null, tint = Accent)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        trailing()
    }
}

// ---------------------------------------------------------------- Pairing

@Composable
private fun PairScreen(screen: Screen.Pair, state: UiState, viewModel: RemoteViewModel) {
    var pin by rememberSaveable(screen) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(screen) { focus.requestFocus() }
    BackHandler { viewModel.cancelPairing() }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Tv, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Pair with ${screen.name}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter the six-digit code shown in Nuvio under Settings › Keyboard shortcuts › Phone remote.",
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(6) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = 8.sp),
            modifier = Modifier.widthIn(max = 260.dp).focusRequester(focus),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (pin.length == 6) viewModel.pair(pin) }),
        )
        state.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Color(0xFFFFB4A9), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = viewModel::cancelPairing) { Text("Cancel") }
            Spacer(Modifier.size(12.dp))
            Button(onClick = { viewModel.pair(pin) }, enabled = pin.length == 6 && !state.busy) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Pair")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Connected: the remote and the library

private enum class Tab(val label: String) { Remote("Remote"), Video("Movies & TV"), Music("Music") }

/**
 * Once connected: the remote, and the PC's library to browse. Choosing a title or playing an album goes back to the
 * remote, since that is what drives the TV next (picking a source, skipping a track).
 */
@Composable
private fun ConnectedScreen(state: UiState, viewModel: RemoteViewModel) {
    var tabName by rememberSaveable { mutableStateOf(Tab.Remote.name) }
    val tab = Tab.valueOf(tabName)
    if (tab != Tab.Remote) BackHandler { tabName = Tab.Remote.name }
    val toRemote = { tabName = Tab.Remote.name }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Remote -> RemoteScreen(state, viewModel)
                Tab.Video -> VideoLibraryScreen(state, viewModel, onOpened = toRemote)
                Tab.Music -> MusicLibraryScreen(state, viewModel, onPlayed = toRemote)
            }
            state.notice?.let { notice ->
                Text(
                    notice,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PanelRaised)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    fontSize = 14.sp,
                )
            }
        }
        // The screen already sits inside the safe-drawing padding; the bar must not add the system bars again.
        NavigationBar(containerColor = Panel, windowInsets = WindowInsets(0, 0, 0, 0)) {
            Tab.entries.forEach { item ->
                NavigationBarItem(
                    selected = item == tab,
                    onClick = { tabName = item.name },
                    icon = {
                        Icon(
                            when (item) {
                                Tab.Remote -> Icons.Rounded.Gamepad
                                Tab.Video -> Icons.Rounded.Movie
                                Tab.Music -> Icons.Rounded.LibraryMusic
                            },
                            contentDescription = null,
                        )
                    },
                    label = { Text(item.label, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Accent.copy(alpha = 0.22f)),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- The remote

@Composable
private fun RemoteScreen(state: UiState, viewModel: RemoteViewModel) {
    var typing by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        StatusBar(state, onSwitch = viewModel::showPicker, onRetry = viewModel::reconnect)

        if (typing) {
            TypingPanel(viewModel, onClose = { typing = false })
        }

        val playing = state.nowPlaying
        if (playing != null && !typing) {
            NowPlayingCard(playing, state.cover, viewModel)
        }

        // The D-pad takes whatever height the three rows of keys leave, so the bottom row and its labels always show
        // (the now-playing card or the keyboard can take a lot of the screen). Never larger than 280 dp or the width.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // coerceAtMost then coerceAtLeast, never coerceIn: on a very narrow screen the cap can fall below the floor.
            val padSize = (maxHeight - KEY_ROWS_HEIGHT).coerceAtMost(minOf(MAX_PAD, maxWidth * 0.85f)).coerceAtLeast(MIN_PAD)
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundKey(Icons.AutoMirrored.Rounded.Undo, "Back") { viewModel.press(RemoteKey.Back) }
                    RoundKey(Icons.Rounded.Home, "Home") { viewModel.press(RemoteKey.Home) }
                    RoundKey(Icons.Rounded.Search, "Search") { viewModel.press(RemoteKey.Search) }
                    RoundKey(Icons.Rounded.Keyboard, "Type", highlighted = typing) { typing = !typing }
                }

                DPad(viewModel, size = padSize)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundKey(Icons.Rounded.FastRewind, "Back 10s", repeat = true) { viewModel.press(RemoteKey.SeekBack) }
                    // The one play/pause on the screen, so it shows what pressing it will do.
                    val isPlaying = playing?.isPlaying == true
                    RoundKey(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        large = true,
                    ) { viewModel.press(RemoteKey.PlayPause) }
                    RoundKey(Icons.Rounded.FastForward, "Forward 10s", repeat = true) { viewModel.press(RemoteKey.SeekForward) }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundKey(Icons.Rounded.Subtitles, "Subtitles") { viewModel.press(RemoteKey.Subtitles) }
                    RoundKey(Icons.Rounded.Audiotrack, "Audio") { viewModel.press(RemoteKey.Audio) }
                    RoundKey(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { viewModel.press(RemoteKey.Mute) }
                    RoundKey(Icons.AutoMirrored.Rounded.VolumeDown, "Vol −", repeat = true) { viewModel.press(RemoteKey.VolumeDown) }
                    RoundKey(Icons.AutoMirrored.Rounded.VolumeUp, "Vol +", repeat = true) { viewModel.press(RemoteKey.VolumeUp) }
                }
            }
        }
    }
}

/** The three rows of round keys with their labels (75 + 95 + 75 dp) plus room for the gaps between rows. */
private val KEY_ROWS_HEIGHT = 285.dp
private val MIN_PAD = 150.dp
private val MAX_PAD = 280.dp

/**
 * What the PC is playing: cover, the full title and artist (long ones scroll), previous and next under them for music,
 * and a seek bar that moves on by itself between the PC's updates and seeks the PC when let go. Play/pause is the big
 * key below, so it is not repeated here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NowPlayingCard(playing: NowPlaying, cover: Bitmap?, viewModel: RemoteViewModel) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playing) {
        now = SystemClock.elapsedRealtime()
        while (playing.isPlaying) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    var dragging by remember(playing.title) { mutableStateOf<Long?>(null) }
    val position = playing.positionAt(now)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(PanelRaised),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(cover.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(if (playing.kind == "music") Icons.Rounded.MusicNote else Icons.Rounded.Movie, contentDescription = null, tint = Muted)
                }
            }
            Spacer(Modifier.size(12.dp))
            // The whole width beside the cover goes to the text; a title too long for it scrolls slowly instead of
            // being cut to "If I Die 2N…".
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playing.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(initialDelayMillis = 1_500),
                )
                playing.subtitle?.let {
                    Text(it, color = Muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.basicMarquee(initialDelayMillis = 1_500))
                }
            }
        }
        if (playing.hasTimeline) {
            val shown = (dragging ?: position).coerceIn(0L, playing.durationMs)
            Spacer(Modifier.height(6.dp))
            SeekBar(
                fraction = shown.toFloat() / playing.durationMs.toFloat(),
                onScrub = { fraction -> dragging = (fraction * playing.durationMs).toLong() },
                onScrubEnd = {
                    dragging?.let(viewModel::seek)
                    dragging = null
                },
            )
        }
        // Times at the ends, and previous / next between them when the queue has them (music).
        if (playing.hasTimeline || playing.canSkip) Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (playing.hasTimeline) formatTime((dragging ?: position).coerceIn(0L, playing.durationMs)) else "",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (playing.canSkip) {
                IconButton(onClick = { viewModel.press(RemoteKey.Previous) }) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.size(16.dp))
                IconButton(onClick = { viewModel.press(RemoteKey.Next) }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(28.dp))
                }
            }
            Text(
                if (playing.hasTimeline) formatTime(playing.durationMs) else "",
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A thin seek bar with a small round marker. Drawn here rather than with Material's Slider, whose current look (a
 * tall bar for a thumb, a gap either side of it and a dot at the end) did not suit the card. Tap or drag anywhere on
 * it; [onScrub] follows the finger and [onScrubEnd] runs on release, which is when the PC is told to seek.
 */
@Composable
private fun SeekBar(fraction: Float, onScrub: (Float) -> Unit, onScrubEnd: () -> Unit) {
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    val markerRadius = 7.dp
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                val inset = markerRadius.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                detectTapGestures(onPress = { offset ->
                    scrub(at(offset.x))
                    // A press that turns into a drag is cancelled here; the drag's own end does the seek then.
                    if (tryAwaitRelease()) end()
                })
            }
            .pointerInput(Unit) {
                val inset = markerRadius.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { offset -> scrub(at(offset.x)) },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { change, _ ->
                    change.consume()
                    scrub(at(change.position.x))
                }
            },
    ) {
        val inset = markerRadius.toPx()
        val y = size.height / 2
        val x = inset + (size.width - 2 * inset) * fraction.coerceIn(0f, 1f)
        val stroke = 4.dp.toPx()
        drawLine(PanelRaised, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Accent, Offset(inset, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(Accent, radius = inset, center = Offset(x, y))
    }
}

/** 3:07 or 1:02:09. */
private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}

@Composable
private fun StatusBar(state: UiState, onSwitch: () -> Unit, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (dot, label) = when (state.link) {
            Link.Connected -> Color(0xFF6FD08C) to "Connected"
            Link.Connecting -> Color(0xFFF2C14E) to "Connecting…"
            Link.Offline -> Color(0xFFFF7A6B) to "Offline · tap to retry"
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = state.link == Link.Offline, onClick = onRetry)
                .padding(vertical = 6.dp),
        ) {
            Text(state.current?.name ?: "Nuvio", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.size(6.dp))
                Text(label, color = Muted, fontSize = 13.sp)
            }
            state.message?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 13.sp) }
        }
        IconButton(onClick = onSwitch) {
            Icon(Icons.Rounded.Devices, contentDescription = "Choose another PC", tint = Muted)
        }
    }
}

/**
 * Typing goes to the PC as you type: added letters as text, deleted ones as backspaces, so the
 * PC's search box follows the phone's exactly. Return sends Enter.
 */
@Composable
private fun TypingPanel(viewModel: RemoteViewModel, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
            modifier = Modifier.weight(1f).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.press(RemoteKey.Enter) }),
        )
        IconButton(onClick = { viewModel.press(RemoteKey.Enter) }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, contentDescription = "Enter", tint = Accent)
        }
        IconButton(onClick = {
            text = ""
            onClose()
        }) {
            Icon(Icons.Rounded.Close, contentDescription = "Close keyboard", tint = Muted)
        }
    }
}

@Composable
private fun DPad(viewModel: RemoteViewModel, size: Dp) {
    val compact = size < 240.dp
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(Panel),
        contentAlignment = Alignment.Center,
    ) {
        val arrow = size * 0.3f
        PressArea(Modifier.align(Alignment.TopCenter).size(arrow), repeat = true, onPress = { viewModel.press(RemoteKey.Up) }) {
            Icon(Icons.Rounded.ExpandLess, contentDescription = "Up", modifier = Modifier.size(40.dp))
        }
        PressArea(Modifier.align(Alignment.BottomCenter).size(arrow), repeat = true, onPress = { viewModel.press(RemoteKey.Down) }) {
            Icon(Icons.Rounded.ExpandMore, contentDescription = "Down", modifier = Modifier.size(40.dp))
        }
        PressArea(Modifier.align(Alignment.CenterStart).size(arrow), repeat = true, onPress = { viewModel.press(RemoteKey.Left) }) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Left", modifier = Modifier.size(40.dp))
        }
        PressArea(Modifier.align(Alignment.CenterEnd).size(arrow), repeat = true, onPress = { viewModel.press(RemoteKey.Right) }) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Right", modifier = Modifier.size(40.dp))
        }
        PressArea(
            Modifier.fillMaxSize(0.38f).aspectRatio(1f).clip(CircleShape).background(PanelRaised),
            onPress = { viewModel.press(RemoteKey.Select) },
        ) {
            Text("OK", fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RoundKey(
    icon: ImageVector,
    label: String,
    large: Boolean = false,
    highlighted: Boolean = false,
    repeat: Boolean = false,
    onPress: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PressArea(
            Modifier
                .size(if (large) 72.dp else 56.dp)
                .clip(CircleShape)
                .background(if (highlighted || large) Accent.copy(alpha = if (large) 1f else 0.25f) else Panel),
            repeat = repeat,
            onPress = onPress,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (large) RemoteColors.onPrimary else RemoteColors.onSurface,
                modifier = Modifier.size(if (large) 36.dp else 26.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

/**
 * Fires on touch-down (not release, so the remote feels immediate) with a light tick, and with
 * [repeat] keeps firing while held, the way a TV remote's arrows do.
 */
@Composable
private fun PressArea(
    modifier: Modifier,
    repeat: Boolean = false,
    onPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier.pointerInput(repeat) {
            detectTapGestures(onPress = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPress()
                var repeater: Job? = null
                if (repeat) {
                    repeater = scope.launch {
                        delay(400)
                        while (true) {
                            onPress()
                            delay(110)
                        }
                    }
                }
                tryAwaitRelease()
                repeater?.cancel()
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
