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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.vector.ImageVector
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
import android.text.format.DateFormat
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import java.util.Date

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
                        // At the top, clear of the keys along the bottom.
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp)
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
    var showSubtitles by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    // Film and music: the touchpad (or D-pad) in place of what is playing, from the tag in the header.
    var padOverPlaying by rememberSaveable { mutableStateOf(false) }
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
    // The next film or album opens on what is playing, not on a pad left up from the last one.
    LaunchedEffect(showing) { if (showing == Showing.Browsing) padOverPlaying = false }
    // Each new source list opens as the list, even if the pad was chosen over the last one.
    var padOverSources by rememberSaveable { mutableStateOf(false) }
    val sourcesOpen = state.sources != null
    LaunchedEffect(sourcesOpen) { if (!sourcesOpen) padOverSources = false }
    // The TV's Tonight picker opens as its mirror here; the tag swaps to the pad (for the modes the phone leaves to the TV).
    var padOverTonight by rememberSaveable { mutableStateOf(false) }
    val tonightOpen = state.tonight != null
    LaunchedEffect(tonightOpen) { if (!tonightOpen) padOverTonight = false }
    // Live TV's stages (Nov 14) open as their mirror the same way.
    var padOverLiveTv by rememberSaveable { mutableStateOf(false) }
    val liveTvOpen = state.liveTv != null
    LaunchedEffect(liveTvOpen) { if (!liveTvOpen) padOverLiveTv = false }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        RemoteHeader(
            state = state,
            status = when {
                state.sources != null -> "Choosing a source"
                state.tonight != null -> "Choosing tonight"
                state.liveTv != null && showing == Showing.Browsing -> "Live TV"
                showing == Showing.Browsing -> "Connected · browsing"
                showing == Showing.Film -> "Playing a film"
                else -> "Playing music"
            },
            onRetry = viewModel::reconnect,
            trailing = {
                if (state.sources != null) {
                    GlassTag(
                        if (padOverSources) "Sources" else if (state.padMode == PadMode.Touchpad) "Touchpad" else "D-pad",
                        icon = if (padOverSources) Icons.AutoMirrored.Rounded.List else Icons.Rounded.CropSquare,
                        onClick = { padOverSources = !padOverSources },
                    )
                } else if (state.tonight != null && showing == Showing.Browsing) {
                    GlassTag(
                        if (padOverTonight) "Tonight" else if (state.padMode == PadMode.Touchpad) "Touchpad" else "D-pad",
                        icon = if (padOverTonight) Icons.Rounded.Movie else Icons.Rounded.CropSquare,
                        onClick = { padOverTonight = !padOverTonight },
                    )
                } else if (state.liveTv != null && showing == Showing.Browsing) {
                    GlassTag(
                        if (padOverLiveTv) "Live TV" else if (state.padMode == PadMode.Touchpad) "Touchpad" else "D-pad",
                        icon = if (padOverLiveTv) Icons.Rounded.Movie else Icons.Rounded.CropSquare,
                        onClick = { padOverLiveTv = !padOverLiveTv },
                    )
                } else if (showing != Showing.Browsing) {
                    GlassTag(
                        when {
                            padOverPlaying -> "Now playing"
                            state.padMode == PadMode.Touchpad -> "Touchpad"
                            else -> "D-pad"
                        },
                        icon = when {
                            !padOverPlaying -> Icons.Rounded.CropSquare
                            showing == Showing.Music -> Icons.Rounded.MusicNote
                            else -> Icons.Rounded.Movie
                        },
                        onClick = { padOverPlaying = !padOverPlaying },
                    )
                }
            },
        )
        if (state.canWake && (state.link == Link.Offline || state.waking)) WakeCard(state, viewModel)
        if (DemoTv.isDemo(state.current)) DemoLogStrip()
        if (typing) TypingPanel(viewModel, onClose = { typing = false })

        val sources = state.sources
        val tonight = state.tonight
        when {
            // The TV is showing its source list: pick one here (the pad, from the header tag, still drives the TV).
            sources != null && !padOverSources -> SourcesBody(sources, viewModel, onPad = { padOverSources = true })
            // Only while nothing plays: once a card starts a film, the film screen takes over.
            tonight != null && showing == Showing.Browsing && !padOverTonight -> TonightBody(tonight, viewModel)
            state.liveTv != null && showing == Showing.Browsing && !padOverLiveTv -> LiveTvBody(state.liveTv, viewModel)
            showing == Showing.Film && playing != null && !padOverPlaying -> FilmBody(
                playing,
                state.cover,
                viewModel,
                // The sources panel is a menu on the TV: the pad comes up to move around it.
                onSources = { padOverPlaying = true },
                onMore = { more = true },
                onSubtitles = {
                    showSubtitles = true
                    viewModel.loadSubtitles()
                },
                onAudio = {
                    showAudio = true
                    viewModel.loadAudio()
                },
            )
            showing == Showing.Music && playing != null && !padOverPlaying -> MusicBody(state, playing, state.cover, viewModel, onMore = { more = true })
            else -> BrowseBody(state.padMode, viewModel, onSearch = search, onMore = { more = true })
        }
    }

    if (showSubtitles) {
        TrackSheet(
            choices = state.subtitles?.choices,
            loading = state.subtitles?.loading == true,
            heading = { n -> if (n <= 1) "Subtitles" else "Subtitles · ${n - 1} found" },
            loadingText = "Still asking the add-ons…",
            onPick = viewModel::pickSubtitle,
            onDismiss = { showSubtitles = false },
        )
    }
    if (showAudio) {
        TrackSheet(
            choices = state.audio?.choices,
            loading = false,
            heading = { n -> if (n == 1) "Audio · 1 track" else "Audio · $n tracks" },
            loadingText = "",
            onPick = viewModel::pickAudio,
            onDismiss = { showAudio = false },
        )
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
        // The hot button for the TV's Tonight picker (Nov 10): one tap opens it, the mirror takes over from there.
        WhatToWatchButton(viewModel, Modifier.padding(top = 8.dp))
        NavPanel(padMode, viewModel, Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceAround) {
            GlassKey(Icons.AutoMirrored.Rounded.Undo, "Back") { viewModel.press(RemoteKey.Back) }
            GlassKey(Icons.Rounded.Home, "Home") { viewModel.press(RemoteKey.Home) }
            GlassKey(Icons.Rounded.Search, "Search", active = true, onPress = onSearch)
            GlassKey(Icons.Rounded.MoreHoriz, "More", onPress = onMore)
        }
    }
}

@Composable
private fun FilmBody(
    playing: NowPlaying,
    cover: Bitmap?,
    viewModel: RemoteViewModel,
    onSources: () -> Unit,
    onMore: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
) {
    // What is playing and its controls, with the keys pinned at the bottom. The pad is a tap away (the tag in the
    // header): on a phone there was no room left for a usable one under all of this. The space left between the
    // volume and the keys is one fixed slot: the "Ends at" clock, with Skip and Next episode in a row of their own
    // inside it, so nothing moves when they come and go.
    Column(Modifier.fillMaxSize()) {
        FilmTop(playing, cover, viewModel, onSubtitles, onAudio)
        // Never scrolls: the card takes exactly what is left and its clock shrinks to fit (a show's top panel is a
        // line taller than a film's).
        FilmClockSlot(playing, viewModel, Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp, bottom = 10.dp))
        BottomKeys {
            GlassKey(Icons.AutoMirrored.Rounded.Undo, "Back", size = 56.dp) { viewModel.press(RemoteKey.Back) }
            GlassKey(Icons.AutoMirrored.Rounded.List, "Sources", size = 56.dp) {
                viewModel.press(RemoteKey.Sources)
                onSources()
            }
            GlassKey(Icons.Rounded.DarkMode, "Dark", size = 56.dp) { viewModel.setDark(true) }
            GlassKey(Icons.Rounded.MoreHoriz, "More", size = 56.dp, onPress = onMore)
        }
    }
}

/** The film screen above the clock slot: now playing, the track tiles, the transport and the volume. */
@Composable
private fun FilmTop(
    playing: NowPlaying,
    cover: Bitmap?,
    viewModel: RemoteViewModel,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        NowPlayingPanel(playing, cover, viewModel, Modifier.padding(top = 4.dp), compact = true)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Tap: the next one. Hold: the list of every subtitle the file and the add-ons have.
            StatTile("Subtitles", playing.subtitleTrack ?: "Tap for next · hold for list", Modifier.weight(1f), onLongClick = onSubtitles) {
                viewModel.press(RemoteKey.Subtitles)
            }
            StatTile("Audio", playing.audioTrack ?: "Tap for next · hold for list", Modifier.weight(1f), onLongClick = onAudio) {
                viewModel.press(RemoteKey.Audio)
            }
        }
        Transport(playing, viewModel, Modifier.padding(top = 18.dp))
        FilmVolume(playing, viewModel, Modifier.padding(top = 12.dp))
    }
}

/**
 * The film screen's fixed slot: "Ends at 21:44" in big type (the end moves with the clock while paused, and follows
 * the speed), and under it one row of fixed height that holds Skip intro / recap / credits and Next episode when
 * the PC says they apply, otherwise a quiet line (paused, the sleep timer, the speed, the running time). The card
 * is whatever height is left on the screen: tall enough, it stacks the clock over the row; on a show (a line taller
 * top panel) it lays the clock on the left and the buttons on the right, so the time never shrinks to a sliver.
 */
@Composable
private fun FilmClockSlot(playing: NowPlaying, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val context = LocalContext.current
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        // The minute only changes once a minute, but a seek or a speed change should show at once: tick each second
        // while playing, every 15 s while paused (the end slides on with the clock).
        while (true) {
            tick = SystemClock.elapsedRealtime()
            delay(if (playing.isPlaying) 1_000L else 15_000L)
        }
    }
    val endsAt = remember(playing, tick) {
        filmEndsAt(playing.positionAt(SystemClock.elapsedRealtime()), playing.durationMs, playing.speedPercent, System.currentTimeMillis())
    }
    val timeText = endsAt?.let { DateFormat.getTimeFormat(context).format(Date(it)) } ?: "–:––"
    val quiet = buildList {
        if (!playing.isPlaying) add("Paused")
        if (playing.sleepMinutes > 0) add("Sleep in ${playing.sleepMinutes} min")
        playing.speedPercent?.takeIf { it != 100 }?.let { add(speedLabel(it)) }
        if (playing.hasTimeline) add("Runs ${runtimeWords(playing.durationMs)}")
    }.joinToString(" · ")
    val label = if (playing.isPlaying) "Ends at" else "Ends at · paused"
    val labelColor = if (playing.isPlaying) null else g.textMuted
    val hasActions = playing.skipLabel != null || playing.canNextEpisode
    BoxWithConstraints(modifier.glass(RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
        val room = maxHeight
        if (room >= 132.dp) {
            // Tall (most films): label, the big time sized to the room, then a fixed 52 dp row for the buttons or
            // the quiet line.
            val timeHeight = minOf(60.dp, room - 52.dp - 20.dp - 4.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) { GlassLabel(label, color = labelColor) }
                ClockTime(timeText, timeHeight, Modifier.height(timeHeight))
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                    if (hasActions) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilmActions(playing, viewModel, Modifier.weight(1f), height = 52.dp)
                        }
                    } else {
                        Text(quiet, color = g.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            // Short (a show's taller top panel): the clock on the left, a fixed column on the right holding Skip and
            // Next episode stacked (or the quiet line), so the time keeps its size and place.
            val timeHeight = minOf(52.dp, room - 20.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) { GlassLabel(label, color = labelColor) }
                    ClockTime(timeText, timeHeight, Modifier.fillMaxWidth().height(timeHeight).padding(end = 12.dp), alignment = Alignment.CenterStart)
                }
                Column(
                    Modifier.width(156.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (hasActions) {
                        val pill = if (playing.skipLabel != null && playing.canNextEpisode) minOf(44.dp, (room - 6.dp) / 2) else minOf(52.dp, room)
                        FilmActions(playing, viewModel, Modifier.fillMaxWidth(), height = pill)
                    } else {
                        Text(quiet, color = g.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

/**
 * The big serif "Ends at" time, as tall as [height] allows and never wider than its space: it is measured first and
 * shrunk to fit the width, so "00:16" is never cut to "00:1" when the card lays out sideways.
 */
@Composable
private fun ClockTime(text: String, height: Dp, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val base = with(density) { (height / 1.12f).toSp() }
        val style = TextStyle(fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = base, lineHeight = base * 1.1f)
        val natural = remember(text, base) { measurer.measure(text, style, softWrap = false, maxLines = 1).size.width }
        val room = constraints.maxWidth
        val fitted = if (natural > room && room > 0) base * (room.toFloat() / natural * 0.97f) else base
        Text(
            text,
            style = style.copy(fontSize = fitted, lineHeight = fitted * 1.1f),
            color = LocalGlass.current.text,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Skip intro / recap / credits and Next episode, each only when the PC says it applies. */
@Composable
private fun FilmActions(playing: NowPlaying, viewModel: RemoteViewModel, each: Modifier, height: Dp) {
    playing.skipLabel?.let { label ->
        ActionPill(label, Icons.Rounded.SkipNext, accent = true, modifier = each, height = height) { viewModel.control("SKIP_INTRO") }
    }
    if (playing.canNextEpisode) {
        ActionPill("Next episode", Icons.Rounded.SkipNext, accent = playing.skipLabel == null, modifier = each, height = height) {
            viewModel.control("NEXT_EPISODE")
        }
    }
}

/**
 * The TV's source list, on the phone: every source in the TV's order, with its addon, size and whether it plays at once
 * (cached on the debrid service). Tapping one plays it on the TV; greyed rows are the ones the TV greys out.
 */
@Composable
private fun SourcesBody(list: SourceList, viewModel: RemoteViewModel, onPad: () -> Unit) {
    val g = LocalGlass.current
    // One filter at a time: "instant", "4k", "hdr" or "dv"; null shows everything.
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val facts = remember(list.sources) { list.sources.associate { it.number to SourceBadges.read(it.name, it.detail) } }
    val chips = buildList {
        if (list.sources.any { it.instant }) add("instant" to "Instant")
        if (list.sources.any { SourceBadges.matches(facts.getValue(it.number), SourceBadges.FOUR_K) }) add(SourceBadges.FOUR_K to "4K")
        if (list.sources.any { SourceBadges.matches(facts.getValue(it.number), SourceBadges.HDR) }) add(SourceBadges.HDR to "HDR")
        if (list.sources.any { SourceBadges.matches(facts.getValue(it.number), SourceBadges.DOLBY_VISION) }) add(SourceBadges.DOLBY_VISION to "Dolby Vision")
    }
    // A filter whose sources are gone (the list refreshed) falls back to showing everything.
    val active = filter?.takeIf { key -> chips.any { it.first == key } }
    val shown = when (active) {
        null -> list.sources
        "instant" -> list.sources.filter { it.instant }
        else -> list.sources.filter { SourceBadges.matches(facts.getValue(it.number), active) }
    }
    var expanded by rememberSaveable { mutableStateOf(setOf<Int>()) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            GlassLabel(if (list.loading) "Finding sources… ${list.sources.size} so far" else "${list.sources.size} sources")
            DisplayTitle(list.title, Modifier.padding(top = 4.dp), size = 20, maxLines = 1)
        }
        if (chips.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassTag("All", accent = active == null, onClick = { filter = null })
                chips.forEach { (key, label) ->
                    GlassTag(label, accent = active == key, onClick = { filter = if (active == key) null else key })
                }
            }
        }
        if (list.loading) {
            androidx.compose.material3.LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 10.dp).height(2.dp),
                color = g.accent,
                trackColor = Color.White.copy(alpha = 0.08f),
            )
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown.size, key = { shown[it].number }) { index ->
                val source = shown[index]
                val read = facts.getValue(source.number)
                val release = SourceBadges.releaseName(source.detail)
                val open = source.number in expanded
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(16.dp))
                        .pointerInput(source.number, source.playable, open) {
                            // Tap plays; hold shows or hides the whole release name.
                            detectTapGestures(
                                onTap = { if (source.playable) viewModel.pickSource(source) },
                                onLongPress = { expanded = if (open) expanded - source.number else expanded + source.number },
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            source.addon.takeIf { it.isNotBlank() } ?: "Source ${source.number}",
                            color = g.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (source.instant) GlassTag("Instant", accent = true)
                    }
                    if (read.all.isEmpty()) {
                        // Nothing readable in the name: show it as the TV does.
                        Text(
                            source.name.replace('\n', ' '),
                            color = if (source.playable) g.text else g.textMuted,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            read.resolution?.let { GlassTag(it.label, accent = source.playable) }
                            read.hdr?.let { GlassTag(it.label) }
                            read.codec?.let { GlassTag(it.label) }
                            read.extras.forEach { GlassTag(it.label) }
                            source.sizeLabel?.let { GlassTag(it) }
                        }
                    }
                    if (read.all.isEmpty()) {
                        source.sizeLabel?.let { Text(it, color = g.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                    if (release != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                release,
                                color = g.textSecondary,
                                fontSize = 12.sp,
                                maxLines = if (open) 4 else 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = g.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
        BottomKeys {
            GlassKey(Icons.AutoMirrored.Rounded.Undo, "Back", size = 52.dp) { viewModel.press(RemoteKey.Back) }
            GlassKey(Icons.Rounded.CropSquare, "Pad", size = 52.dp, onPress = onPad)
        }
    }
}

/** The row of keys at the foot of a body, pinned there whatever is above it. */
@Composable
private fun BottomKeys(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp, start = 24.dp, end = 24.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        content = content,
    )
}

@Composable
private fun MusicBody(state: UiState, playing: NowPlaying, cover: Bitmap?, viewModel: RemoteViewModel, onMore: () -> Unit) {
    val g = LocalGlass.current
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    // A new song's lyrics are asked for as it starts, while the lyric view is open.
    LaunchedEffect(showLyrics, playing.title) { if (showLyrics) viewModel.loadLyrics(playing.title) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The cover (or the lyrics in its place) takes whatever height is left, so the transport and the keys always fit.
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            if (showLyrics) {
                LyricsView(state.lyrics?.takeIf { it.title == null || it.title == playing.title }, playing, Modifier.fillMaxSize())
            } else {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .heightIn(max = 260.dp)
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2B2320))
                        .pressable { showLyrics = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (cover != null) {
                        Image(cover.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = g.textMuted, modifier = Modifier.size(48.dp))
                    }
                }
            }
        }
        GlassLabel("Now playing on the TV", Modifier.padding(top = 16.dp))
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
        Seek(playing, viewModel, Modifier.padding(top = 12.dp))
        Transport(playing, viewModel, Modifier.padding(top = 4.dp))
        MusicExtras(playing, viewModel, Modifier.padding(top = 10.dp))
        BottomKeys {
            GlassKey(Icons.AutoMirrored.Rounded.Undo, "Back", size = 50.dp) { viewModel.press(RemoteKey.Back) }
            GlassKey(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", size = 50.dp) {
                viewModel.loadQueue()
                showQueue = true
            }
            GlassKey(Icons.Rounded.FormatQuote, "Lyrics", size = 50.dp, active = showLyrics) { showLyrics = !showLyrics }
            GlassKey(Icons.Rounded.DarkMode, "Dark", size = 50.dp) { viewModel.setDark(true) }
            GlassKey(Icons.Rounded.MoreHoriz, "More", size = 50.dp, onPress = onMore)
        }
    }
    if (showQueue) QueueSheet(state.queue, viewModel, onDismiss = { showQueue = false })
}

/**
 * The playing song's lyrics, as the TV shows them: timed ones follow the song (the line being sung is bright and kept in
 * the middle), untimed ones simply scroll. Tapping the cover opens them; the Lyrics key switches back to the cover.
 */
@Composable
private fun LyricsView(lyrics: ServerLine.LyricsText?, playing: NowPlaying, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    if (lyrics == null || lyrics.lines.isEmpty()) {
        Box(modifier.glass(RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Text(
                if (lyrics == null) "Finding the lyrics…" else "No lyrics for this song.",
                color = g.textSecondary,
                fontSize = 14.sp,
            )
        }
        return
    }
    // Timed lyrics show in the stage's Loud style (the default) or as the scrolling list; the tag in the corner switches.
    var loud by rememberSaveable { mutableStateOf(true) }
    if (lyrics.synced && loud) {
        Box(modifier.glass(RoundedCornerShape(20.dp), fill = Color.Black.copy(alpha = 0.35f))) {
            LoudLyricsView(lyrics.lines, playing, Modifier.fillMaxSize())
            GlassTag("List", modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), onClick = { loud = false })
        }
        return
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playing) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val position = playing.positionAt(now)
    val current = if (lyrics.synced) lyrics.lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= position } else -1
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    Box(modifier) {
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().glass(RoundedCornerShape(20.dp)).padding(horizontal = 18.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp),
    ) {
        items(lyrics.lines.size) { index ->
            val line = lyrics.lines[index]
            val lit = index == current
            Text(
                line.text.ifBlank { "♪" },
                color = when {
                    !lyrics.synced -> g.text
                    lit -> g.text
                    index < current -> g.textMuted
                    else -> g.textSecondary
                },
                fontSize = if (lit) 20.sp else 17.sp,
                fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
    if (lyrics.synced) GlassTag("Loud", modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), onClick = { loud = true })
    }
}

/**
 * The film player's subtitles or audio tracks (hold the Subtitles or Audio tile): subtitles are Off, the file's own, then
 * each add-on's; audio is the file's tracks. The one in use is marked. Tap one and the TV switches to it, as picking it
 * in its own panel does. [choices] is null until the TV has answered.
 */
@Composable
private fun TrackSheet(
    choices: List<SubtitleChoice>?,
    loading: Boolean,
    heading: (Int) -> String,
    loadingText: String,
    onPick: (SubtitleChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val g = LocalGlass.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF14151A),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            GlassLabel(heading(choices?.size ?: 0))
            if (choices == null) {
                Text("Asking the TV…", color = g.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
                return@Column
            }
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                var lastGroup: String? = null
                choices.forEach { choice ->
                    // A small heading where the source changes: the file's own, then each add-on.
                    if (choice.group.isNotBlank() && choice.group != "Off" && choice.group != lastGroup) {
                        val heading = choice.group
                        item(key = "group:$heading") {
                            Text(heading.uppercase(), color = g.textSecondary, fontSize = 11.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        }
                    }
                    lastGroup = choice.group
                    item(key = "sub:${choice.number}") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(14.dp), fill = if (choice.selected) g.tint else Color.White.copy(alpha = 0.045f), edge = if (choice.selected) g.tintEdge else Color.White.copy(alpha = 0.09f))
                                // A tap on release, not on touch-down: a finger that starts a scroll must not pick one.
                                .pointerInput(choice) {
                                    detectTapGestures(onTap = {
                                        if (!choice.selected) onPick(choice)
                                        onDismiss()
                                    })
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(choice.label, color = g.text, fontSize = 15.sp, fontWeight = if (choice.selected) FontWeight.Bold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!choice.detail.isNullOrBlank()) Text(choice.detail, color = g.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (choice.selected) Text("On", color = g.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (loading) item(key = "loading") {
                    Text(loadingText, color = g.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}

/** The TV's music queue: tap a song to play it; Next and Remove on the ones still to come. */
@Composable
private fun QueueSheet(queue: ServerLine.Queue?, viewModel: RemoteViewModel, onDismiss: () -> Unit) {
    val g = LocalGlass.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF14151A),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            GlassLabel(if (queue == null) "Up next" else "Up next · ${(queue.tracks.size - queue.playing - 1).coerceAtLeast(0)} to come")
            if (queue == null) {
                Text("Asking the TV…", color = g.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
                return@Column
            }
            if (queue.tracks.isEmpty()) {
                Text("Nothing is queued.", color = g.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
                return@Column
            }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = (queue.playing - 1).coerceAtLeast(0),
            )
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(top = 10.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(queue.tracks.size, key = { queue.tracks[it].videoId + "#" + it }) { index ->
                    val track = queue.tracks[index]
                    val isPlaying = index == queue.playing
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(14.dp), fill = if (isPlaying) g.tint else Color.White.copy(alpha = 0.045f), edge = if (isPlaying) g.tintEdge else Color.White.copy(alpha = 0.09f))
                            .pressable(onPress = { if (!isPlaying) viewModel.queueAction("play", track) })
                            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RemoteImage(track.cover, null, track.title, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                color = if (index < queue.playing) g.textMuted else g.text,
                                fontSize = 15.sp,
                                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOf(track.artist, formatTime(track.seconds * 1000L)).filter { it.isNotBlank() }.joinToString(" · "),
                                color = if (isPlaying) g.accent else g.textSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (track.upcoming) {
                            IconButton(onClick = { viewModel.queueAction("next", track) }) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = "Play next", tint = g.textSecondary)
                            }
                            IconButton(onClick = { viewModel.queueAction("remove", track) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = g.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What is playing, for a film: poster, the serif title, the line under it and the seek bar. */
@Composable
private fun NowPlayingPanel(playing: NowPlaying, cover: Bitmap?, viewModel: RemoteViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val g = LocalGlass.current
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(if (compact) 40.dp else 52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A3540)),
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
                DisplayTitle(playing.title, size = if (compact) 19 else 22, maxLines = if (compact) 1 else 2)
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

/** Under the film's transport: the TV's volume as a slim slider, when the PC says it has one. */
@Composable
private fun FilmVolume(playing: NowPlaying, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    Column(modifier.fillMaxWidth()) {
        playing.volume?.let { level ->
            val max = playing.volumeMax.coerceAtLeast(1)
            var dragging by remember { mutableStateOf<Int?>(null) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "TV volume", tint = g.textSecondary, modifier = Modifier.size(20.dp))
                SeekBar(
                    fraction = (dragging ?: level).toFloat() / max,
                    onScrub = { fraction -> dragging = (fraction * max).toInt().coerceIn(0, max) },
                    onScrubEnd = {
                        dragging?.let(viewModel::setVolume)
                        dragging = null
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text("${dragging ?: level}", color = g.textSecondary, fontSize = 13.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
            }
        }
    }
}

/** "1x", "1.25x", "0.75x". */
private fun speedLabel(percent: Int): String = when {
    percent % 100 == 0 -> "${percent / 100}x"
    percent % 10 == 0 -> "%.1fx".format(percent / 100.0)
    else -> "%.2fx".format(percent / 100.0)
}

/** A label, then chips that scroll sideways when they do not fit. */
@Composable
private fun ChipRow(label: String, chips: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val g = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = g.textMuted, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = chips)
    }
}

/** The film's speed: 0.75x to 2x, the one in use lit. */
@Composable
private fun SpeedChips(speed: Int, viewModel: RemoteViewModel) {
    ChipRow("Speed") {
        listOf(75, 100, 125, 150, 200).forEach { percent ->
            GlassTag(speedLabel(percent), accent = speed == percent, onClick = {
                viewModel.control("SPEED $percent") { it.copy(speedPercent = percent) }
            })
        }
    }
}

/** The PC's sleep timer: pauses what is playing after 15, 30 or 60 minutes; shows the minutes left while it runs. */
@Composable
private fun SleepChips(playing: NowPlaying, viewModel: RemoteViewModel) {
    ChipRow("Sleep") {
        GlassTag("Off", accent = playing.sleepMinutes == 0, onClick = { viewModel.control("SLEEP 0") { it.copy(sleepMinutes = 0) } })
        listOf(15, 30, 60).forEach { minutes ->
            GlassTag("$minutes min", onClick = { viewModel.control("SLEEP $minutes") { it.copy(sleepMinutes = minutes) } })
        }
        if (playing.sleepMinutes > 0) GlassTag("${playing.sleepMinutes} min left", accent = true)
    }
}

/** A wide button for one action ("Skip intro", "Next episode"). */
@Composable
private fun ActionPill(text: String, icon: ImageVector, accent: Boolean, modifier: Modifier = Modifier, height: Dp = 52.dp, onClick: () -> Unit) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .height(height)
            .then(if (accent) Modifier.glass(shape, fill = g.tint, edge = g.tintEdge) else Modifier.glass(shape))
            .pressable(onPress = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (accent) g.accent else g.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, color = if (accent) g.accent else g.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Under the music transport: shuffle, repeat, like (when signed in to YouTube Music on the PC) and a radio from this song. */
@Composable
private fun MusicExtras(playing: NowPlaying, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        playing.shuffle?.let { on ->
            GlassKey(Icons.Rounded.Shuffle, null, size = 48.dp, active = on) {
                viewModel.control("SHUFFLE ${if (on) 0 else 1}") { it.copy(shuffle = !on) }
            }
        }
        playing.repeat?.let { mode ->
            GlassKey(if (mode == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null, size = 48.dp, active = mode != 0) {
                val next = (mode + 1) % 3
                viewModel.control("REPEAT $next") { it.copy(repeat = next) }
            }
        }
        playing.liked?.let { liked ->
            GlassKey(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, size = 48.dp, active = liked) {
                viewModel.control("LIKE") { it.copy(liked = !liked) }
            }
        }
        GlassKey(Icons.Rounded.Radio, null, size = 48.dp) { viewModel.control("RADIO") }
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
internal fun Touchpad(
    onKey: (RemoteKey) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
    step: androidx.compose.ui.unit.Dp = STEP,
    title: String = "Swipe to move",
    hint: String? = null,
) {
    val g = LocalGlass.current
    val haptics = LocalHapticFeedback.current
    val send by rememberUpdatedState(onKey)
    val stepPx = with(LocalDensity.current) { step.toPx() }
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
            Text(title, color = Color(0xFFC9CDD2), fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.SemiBold)
            Text(hint ?: if (compact) "For the player's menus · tap for OK" else "Tap for OK", color = g.textMuted, fontSize = 12.sp)
        }
    }
}

private val STEP = 44.dp


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

/** Everything that is not on the screen, in three groups: Playback (volume, sleep timer), Display (pad, dark mode), Device (typing, another PC). */
@Composable
private fun MoreSheet(state: UiState, viewModel: RemoteViewModel, onType: () -> Unit, onDismiss: () -> Unit) {
    val g = LocalGlass.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF14151A),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            GlassLabel("Playback")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GlassKey(Icons.AutoMirrored.Rounded.VolumeOff, "Mute") { viewModel.press(RemoteKey.Mute) }
                GlassKey(Icons.AutoMirrored.Rounded.VolumeDown, "Vol −", repeat = true) { viewModel.press(RemoteKey.VolumeDown) }
                GlassKey(Icons.AutoMirrored.Rounded.VolumeUp, "Vol +", repeat = true) { viewModel.press(RemoteKey.VolumeUp) }
            }
            state.nowPlaying?.let { playing ->
                playing.speedPercent?.let { speed -> SpeedChips(speed, viewModel) }
                SleepChips(playing, viewModel)
            }

            GlassLabel("Display")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GlassKey(
                    if (state.padMode == PadMode.Touchpad) Icons.Rounded.RadioButtonChecked else Icons.Rounded.OpenWith,
                    if (state.padMode == PadMode.Touchpad) "D-pad" else "Touchpad",
                ) {
                    viewModel.setPadMode(if (state.padMode == PadMode.Touchpad) PadMode.DPad else PadMode.Touchpad)
                    onDismiss()
                }
                GlassKey(Icons.Rounded.DarkMode, "Dark") {
                    onDismiss()
                    viewModel.setDark(true)
                }
                Spacer(Modifier.size(60.dp))
            }

            LockScreenSection()

            GlassLabel("Device")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GlassKey(Icons.Rounded.Keyboard, "Type", onPress = onType)
                GlassKey(Icons.Rounded.Devices, "Other PC") {
                    onDismiss()
                    viewModel.showPicker()
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    GlassKey(Icons.Rounded.PowerSettingsNew, "Sleep") { DemoTv.sleep(); onDismiss() }
                    Spacer(Modifier.size(60.dp))
                    Spacer(Modifier.size(60.dp))
                    Spacer(Modifier.size(60.dp))
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
        // Voice: what is said is typed into the TV's search box, as if typed here.
        val voice = rememberVoiceInput("Say what to search for", onText = { said ->
            val next = if (text.isEmpty()) said else "$text $said"
            viewModel.type(next.substring(text.length))
            text = next
        })
        IconButton(onClick = voice) {
            Icon(Icons.Rounded.Mic, contentDescription = "Search by voice", tint = g.accent)
        }
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

/** While the PC is out of reach: wake it from sleep (Wake-on-LAN), then the app keeps trying to connect. */
@Composable
private fun WakeCard(state: UiState, viewModel: RemoteViewModel) {
    val g = LocalGlass.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .glass(RoundedCornerShape(16.dp), fill = g.tint, edge = g.tintEdge)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GlassLabel(if (state.waking) "Waking it up" else "Asleep or off?")
            Text(
                if (state.waking) "Sent the wake-up. Connecting as soon as ${state.current?.name ?: "the PC"} answers…"
                else "Wake ${state.current?.name ?: "the PC"} from here.",
                color = g.text,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (state.waking) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = g.accent, strokeWidth = 2.dp)
        } else {
            GlassTag("Wake", icon = Icons.Rounded.PowerSettingsNew, accent = true, onClick = viewModel::wakePc)
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
