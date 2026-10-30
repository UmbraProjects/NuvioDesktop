package com.nuvio.app.features.music.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.PlatformBackHandler
import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.defaultMusicCatalog
import kotlinx.coroutines.launch
import com.nuvio.app.features.music.MusicMessage
import kotlin.math.roundToInt

/**
 * Ladder phase 5: the five-rung screen. Opened from the Music tab's search card (one `// fork:music` hook in
 * MusicTabScreen); it replaces the tab body while open and [onBack] closes it. Rung 1 is nearest the listener's taste
 * (or the artist, out from), the last rung is the core of the sound. Each rung shows a picture, the artist, a
 * one-sentence reason and how typical of the genre they are. Playing and saving a rung is phase 6.
 *
 * Phase 10: the same screen shows a ladder from a record ([LadderRequest.Album]); the chips lead out into another genre.
 *
 * Phase 9, audition: Left and Right move over the rung cards and are silent; the room (palette, glow) follows the cursor
 * ([LadderRoom]). Enter, A, the Preview button or a click on the card plays the rung from its hook ([auditionRung]); pressing the rung that is
 * playing stops it, and pressing another rung moves on to that one. Nothing ever plays by itself. "Play album" is the
 * phase 6 play from the first track.
 *
 * HTPC layout: five large cards in a strip, a path rail, and a detail panel with a closeness bar and the familiarity dial.
 * Up turns the dial further out ("too close"), Down brings it closer ("too far"), L loves, S skips (a pad's A is Enter, so it
 * plays; the Love and Skip buttons on screen take a click); the same
 * four [RungFeedback] values go through [applyFeedback], so the model is unchanged. Text is sized for a TV (28sp body).
 *
 * [onRequest] swaps the request in place (out-from mode's other destination genres).
 */
@Composable
internal fun MusicLadderScreen(
    request: LadderRequest,
    onBack: () -> Unit,
    onRequest: (LadderRequest) -> Unit,
    modifier: Modifier = Modifier,
    topChromePadding: Dp = 0.dp,
) {
    var attempt by remember { mutableIntStateOf(0) }
    // Phase 7: the marks and the dial for this ladder, read from disk when the ladder opens and written on every change.
    var progress by remember(request) { mutableStateOf(LadderProgressStore.get(request)) }
    val state by produceState<LadderState>(LadderState.Loading, request, attempt, progress) {
        // A ladder being re-ranked keeps showing the old one rather than flashing a spinner.
        if (value !is LadderState.Ready) value = LadderState.Loading
        MusicLibraryRepository.ensureLoaded()
        val started = LibraryClock.nowEpochMs()
        value = LadderArtistTargets.load(request, MusicLibraryRepository.state.value.artists, LadderProgressStore.forBuilding(progress))
        // How long the build took, and from which source, so a slow ladder can be traced to its lookups.
        Logger.withTag("LadderDebug").i { "Built in ${LibraryClock.nowEpochMs() - started} ms (tags: ${MusicTagSource.describe()})" }
    }
    fun mark(rung: LadderRung, feedback: RungFeedback) {
        progress = applyFeedback(progress, rung, feedback).also { LadderProgressStore.put(request, it) }
    }
    PlatformBackHandler(enabled = true, onBack = onBack)

    // Phase 6: what happened to each rung the person played or saved, by candidate id.
    val catalog = remember { defaultMusicCatalog() }
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun note(id: String, text: String) { notes = notes + (id to text) }

    // Phase 9: the rung under the cursor (by id, so a re-rank cannot move the cursor onto another artist), the audition
    // being opened, and the rung whose hook is playing. Arrowing never starts one: only a press does. A new AuditionTarget
    // cancels one still opening, so pressing quickly on two rungs plays the second.
    val shown = state
    // Item 5: F3 shows how each rung was picked; the same text goes to the log every time a ladder is built.
    var debug by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        (shown as? LadderState.Ready)?.let { Logger.withTag("LadderDebug").i { ladderDebugLines(it.view.ladder, it.view.title).joinToString("\n") } }
    }
    val rungs = (shown as? LadderState.Ready)?.view?.ladder?.rungs.orEmpty()
    var focusedId by remember(request) { mutableStateOf<String?>(null) }
    var auditionTarget by remember(request) { mutableStateOf<AuditionTarget?>(null) }
    val focusedIndex = rungs.indexOfFirst { it.candidate.id == focusedId }
    val focusedRung = rungs.getOrNull(focusedIndex)
    // Silent: the cursor moves and the room follows, nothing plays.
    fun focusRung(rung: LadderRung) { focusedId = rung.candidate.id }
    // The rung whose hook the last press opened. It still counts as playing only while the player has a track.
    var playingId by remember(request) { mutableStateOf<String?>(null) }
    // A button press (Enter, A, click): plays the rung's hook, or stops it when that rung is the one playing.
    fun pressRung(rung: LadderRung) {
        focusRung(rung)
        val stillPlaying = if (MusicPlayback.player.state.value.isActive) playingId else null
        when (auditionPress(stillPlaying, rung.candidate.id)) {
            AuditionPress.Stop -> {
                MusicPlayback.player.stop()
                playingId = null
                auditionTarget = null
                note(rung.candidate.id, "Stopped")
            }
            AuditionPress.Play -> {
                playingId = rung.candidate.id
                auditionTarget = AuditionTarget(rung.candidate.id, rung.candidate.name)
            }
        }
    }
    LaunchedEffect(auditionTarget) {
        val target = auditionTarget ?: return@LaunchedEffect
        note(target.id, "Opening…")
        val result = auditionRung(catalog, target.name, MusicLibraryRepository.state.value.artists)
        if (result == RungAudition.NotPlayable && playingId == target.id) playingId = null
        note(target.id, auditionNote(result))
    }
    val context = LocalPlatformContext.current
    // The room is the focused rung's, even before its song opens. While a new rung's colours are being found the old
    // ones stay, so the room fades from one artist to the next instead of flashing to the default.
    val room by produceState<AuditionRoom?>(null, focusedRung?.candidate?.id) {
        value = focusedRung?.let { roomFor(context, it, MusicLibraryRepository.state.value.artists) }
    }

    val listState = rememberLazyListState()
    val view = (shown as? LadderState.Ready)?.view
    // Phase 2 (HTPC): the cursor starts on the first rung, so Left and Right move from somewhere real. Nothing plays until a key is pressed.
    LaunchedEffect(rungs.firstOrNull()?.candidate?.id) {
        if (focusedId == null) focusedId = rungs.firstOrNull()?.candidate?.id
    }
    val activeRung = focusedRung ?: rungs.firstOrNull()
    LaunchedEffect(focusedId) {
        if (focusedIndex >= 0) listState.ensureVisible(focusedId, focusedIndex)
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun playAlbum(rung: LadderRung) {
        val id = rung.candidate.id
        note(id, "Opening…")
        scope.launch {
            val result = playRung(catalog, rung.candidate.name, MusicLibraryRepository.state.value.artists)
            note(
                id,
                when (result) {
                    is RungPlay.Playing -> "Playing ${result.albumTitle}"
                    RungPlay.NotPlayable -> "No release to play here"
                },
            )
        }
    }
    fun save(rung: LadderRung, via: String) {
        val id = rung.candidate.id
        note(id, "Saving…")
        scope.launch {
            val saved = saveRung(catalog, rung.candidate.name, foundViaLabel(via))
            note(id, if (saved != null) "Saved to your Library" else "Not found on YouTube Music")
        }
    }

    LadderRoom(
        room = room,
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.F3) {
                    debug = !debug
                    return@onPreviewKeyEvent true
                }
                if (rungs.isEmpty()) return@onPreviewKeyEvent false
                val current = activeRung
                when (event.key) {
                    // The rungs are a horizontal strip now: Left and Right browse them.
                    Key.DirectionRight, Key.DirectionLeft -> {
                        val next = moveFocus(focusedIndex, if (event.key == Key.DirectionRight) 1 else -1, rungs.size)
                        rungs.getOrNull(next)?.let { focusRung(it) }
                        true
                    }
                    // The closeness dial: Up leads further out, Down comes back toward the listener's taste.
                    Key.DirectionUp -> current?.let { mark(it, RungFeedback.TooClose) }.let { current != null }
                    Key.DirectionDown -> current?.let { mark(it, RungFeedback.TooFar) }.let { current != null }
                    // Enter, a pad's A (it injects Enter) and a click play the hook; a second press stops it.
                    Key.Enter, Key.NumPadEnter, Key.ButtonA -> {
                        current?.let { pressRung(it) }
                        current != null
                    }
                    // L and S on a keyboard; X and Y on a pad, which has no letter keys.
                    Key.L, Key.ButtonX -> current?.let { mark(it, RungFeedback.Love) }.let { current != null }
                    Key.S, Key.ButtonY -> current?.let { mark(it, RungFeedback.Skip) }.let { current != null }
                    else -> false
                }
            },
    ) {
        val colors = MaterialTheme.nuvio.colors
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topChromePadding)
                .padding(horizontal = SAFE_MARGIN),
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "Back",
                    style = ladderText(LadderType.Body),
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = view?.title ?: requestTitle(request),
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.textPrimary,
                )
                view?.subtitle?.let {
                    Text(text = it, style = ladderText(LadderType.Body), color = colors.textSecondary)
                }
                if (!progress.isFresh) {
                    Text(
                        text = "Start over",
                        style = ladderText(LadderType.Body),
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { progress = LadderProgress().also { LadderProgressStore.put(request, it) } }
                            .padding(vertical = 6.dp),
                    )
                }
            }

            when (shown) {
                LadderState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
                is LadderState.Failed -> MusicMessage(
                    text = shown.message,
                    actionLabel = if (shown.retry) "Try again" else null,
                    onAction = if (shown.retry) ({ attempt++ }) else null,
                )
                is LadderState.Ready -> Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    shown.view.note?.let { note ->
                        Text(text = note, style = ladderText(LadderType.Body), color = colors.textMuted)
                    }
                    if (shown.view.alternatives.isNotEmpty() && request.hasDestinations) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Lead out into", style = ladderText(LadderType.Body), color = colors.textMuted)
                            shown.view.alternatives.forEach { genre ->
                                BigPill(text = genre, onClick = { onRequest(request.withDestination(genre)) })
                            }
                        }
                    }

                    PathRail(
                        start = pathStart(request),
                        target = shown.view.ladder.target,
                        rungs = rungs,
                        focusedId = activeRung?.candidate?.id,
                    )

                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cardWidth = maxWidth * CARD_WIDTH_FRACTION
                        LazyRow(
                            state = listState,
                            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                            contentPadding = PaddingValues(vertical = 20.dp, horizontal = 12.dp),
                        ) {
                            items(rungs, key = { it.candidate.id }) { rung ->
                                LadderRungCard(
                                    rung = rung,
                                    width = cardWidth,
                                    focused = rung.candidate.id == activeRung?.candidate?.id,
                                    playing = rung.candidate.id == playingId,
                                    onClick = { pressRung(rung) },
                                )
                            }
                        }
                    }

                    activeRung?.let { rung ->
                        LadderDetailPanel(
                            rung = rung,
                            target = shown.view.ladder.target,
                            dial = progress.dial,
                            note = notes[rung.candidate.id],
                            previewing = playingId == rung.candidate.id && MusicPlayback.player.state.value.isActive,
                            onPreview = { pressRung(rung) },
                            onFeedback = { mark(rung, it) },
                            onPlayAlbum = { playAlbum(rung) },
                            onSave = { save(rung, shown.view.title) },
                        )
                        if (rung.step == rungs.size) {
                            LadderSummary(
                                rungs = rungs,
                                onSaveAll = { rungs.forEach { save(it, shown.view.title) } },
                                onPlayPlaylist = {
                                    val last = rungs.last().candidate.id
                                    note(last, "Opening playlist…")
                                    scope.launch {
                                        val queued = playRungsAsPlaylist(catalog, rungs.map { it.candidate.name }, MusicLibraryRepository.state.value.artists)
                                        note(last, if (queued > 0) "Playing $queued album${if (queued == 1) "" else "s"}, one per rung" else "Nothing to play here")
                                    }
                                },
                                onClimbHigher = { onRequest(LadderRequest.Artist(rung.candidate.name, LadderMode.OutFrom)) },
                            )
                        }
                    }
                    if (debug) LadderDebugPanel(ladderDebugLines(shown.view.ladder, shown.view.title))
                    LadderLegend()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** HTPC type scale at 1080p: body never below 28sp, row titles 36sp (see the "Using the Blank Space" doc). */
private object LadderType {
    val Body = 28.sp
    val Row = 36.sp
    val Legend = 24.sp
}

private val SAFE_MARGIN = 48.dp
private val CARD_GAP = 16.dp
private const val CARD_WIDTH_FRACTION = 0.18f
private const val FOCUS_SCALE = 1.08f

@Composable
private fun ladderText(size: TextUnit): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontSize = size, lineHeight = size * 1.25f)

/** Where the climb starts: the artist or record it leads out from, otherwise the listener's own Library. */
private fun pathStart(request: LadderRequest): String = when (request) {
    is LadderRequest.Genre -> "Your Library"
    is LadderRequest.Artist -> if (request.mode == LadderMode.OutFrom) request.name else "Your Library"
    is LadderRequest.Album -> if (request.mode == LadderMode.OutFrom) request.title else "Your Library"
}

/** The path rail: start on the left, target on the right, one dot per rung (filled once marked, ringed when current). */
@Composable
private fun PathRail(start: String, target: String, rungs: List<LadderRung>, focusedId: String?) {
    val colors = MaterialTheme.nuvio.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(start, style = ladderText(LadderType.Body), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
        Spacer(Modifier.width(16.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            rungs.forEach { rung ->
                Box(Modifier.weight(1f).height(4.dp).background(colors.surfaceElevated))
                val current = rung.candidate.id == focusedId
                Box(
                    modifier = Modifier
                        .size(if (current) 28.dp else 20.dp)
                        .clip(CircleShape)
                        .background(if (rung.mark != null) colors.accent else colors.surfaceElevated)
                        .then(if (current) Modifier.border(3.dp, colors.accent, CircleShape) else Modifier),
                )
            }
            Box(Modifier.weight(1f).height(4.dp).background(colors.surfaceElevated))
        }
        Spacer(Modifier.width(16.dp))
        Text(target, style = ladderText(LadderType.Body), color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
    }
}

/** One rung as a large card: the picture fills it, the focused card grows and the others dim. */
@Composable
private fun LadderRungCard(rung: LadderRung, width: Dp, focused: Boolean, playing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val picture by produceState<String?>(null, rung.candidate.name) { value = LadderArtwork.picture(rung.candidate.name) }
    val scale by animateFloatAsState(if (focused) FOCUS_SCALE else 1f)
    val alpha by animateFloatAsState(if (focused) 1f else 0.55f)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .width(width)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .clip(shape)
            .background(if (focused) colors.surfaceElevated else colors.surfaceCard)
            .then(if (focused) Modifier.border(3.dp, colors.accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MusicCover(picture, rung.candidate.name, size = width - 24.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .then(if (playing) Modifier.border(4.dp, colors.textPrimary, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                // The number becomes a play mark while this rung's hook is on, so the card says what a second press does.
                Text(if (playing) "■" else "${rung.step}", style = ladderText(LadderType.Body), color = colors.onAccent)
            }
            rung.mark?.let { Text(feedbackLabel(it), style = ladderText(LadderType.Legend), color = colors.textSecondary) }
        }
        Text(
            text = rung.candidate.name,
            style = ladderText(LadderType.Row),
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Detail for the rung under the cursor: the reason, how near it is, the dial, and the actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LadderDetailPanel(
    rung: LadderRung,
    target: String,
    dial: Double,
    note: String?,
    previewing: Boolean,
    onPreview: () -> Unit,
    onFeedback: (RungFeedback) -> Unit,
    onPlayAlbum: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceCard)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(rung.candidate.name, style = ladderText(LadderType.Row), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(rung.why, style = ladderText(LadderType.Body), color = colors.textSecondary)
        if (!rung.candidate.playable) {
            Text("May not be playable here", style = ladderText(LadderType.Legend), color = colors.textMuted)
        }

        // Closeness, as one two-ended bar: how much of this artist leans to the Library and how much to the target genre.
        val library = rung.proximity.toFloat().coerceAtLeast(0f)
        val genre = rung.purity.toFloat().coerceAtLeast(0f)
        val libraryShare = if (library + genre <= 0f) 0.5f else library / (library + genre)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(16.dp).clip(CircleShape).background(colors.surfaceElevated)) {
                Box(Modifier.weight(libraryShare.coerceIn(0.03f, 0.97f)).fillMaxHeight().background(colors.textSecondary))
                Box(Modifier.weight((1f - libraryShare).coerceIn(0.03f, 0.97f)).fillMaxHeight().background(colors.accent))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rung.anchorName?.let { "Near your $it" } ?: "Near your Library", style = ladderText(LadderType.Legend), color = colors.textSecondary)
                Text("Into $target", style = ladderText(LadderType.Legend), color = colors.accent)
            }
        }

        // The familiarity dial the whole ladder is built at.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.surfaceElevated))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(dial.toFloat().coerceIn(0.001f, 0.999f)))
                    Box(Modifier.size(24.dp).clip(CircleShape).background(colors.accent))
                    Spacer(Modifier.weight((1f - dial.toFloat()).coerceIn(0.001f, 0.999f)))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Closer to my taste", style = ladderText(LadderType.Legend), color = colors.textSecondary)
                Text("Further out", style = ladderText(LadderType.Legend), color = colors.textSecondary)
            }
        }

        // A flow, not a row: with Preview there are seven pills, too many for one line at TV text size.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // The same action as Enter or A on the card, made visible: a taste of this artist before committing.
            BigPill(if (previewing) "Stop preview" else "Preview", onPreview, selected = previewing)
            BigPill("Closer", { onFeedback(RungFeedback.TooFar) }, selected = rung.mark == RungFeedback.TooFar)
            BigPill("Further out", { onFeedback(RungFeedback.TooClose) }, selected = rung.mark == RungFeedback.TooClose)
            BigPill("Skip", { onFeedback(RungFeedback.Skip) })
            BigPill("Love", { onFeedback(RungFeedback.Love) }, selected = rung.mark == RungFeedback.Love)
            BigPill("Save", onSave)
            BigPill("Play album", onPlayAlbum)
        }
        if (note != null) Text(note, style = ladderText(LadderType.Body), color = colors.textSecondary)
    }
}

/** After the last rung: the whole climb at a glance, with the two ways on. */
@Composable
private fun LadderSummary(rungs: List<LadderRung>, onSaveAll: () -> Unit, onPlayPlaylist: () -> Unit, onClimbHigher: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceCard).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("The top of the ladder", style = ladderText(LadderType.Row), color = colors.textPrimary)
        Text(
            rungs.joinToString("  ›  ") { it.candidate.name },
            style = ladderText(LadderType.Body),
            color = colors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigPill("Save all", onSaveAll)
            BigPill("Play as a playlist", onPlayPlaylist)
            BigPill("Climb higher", onClimbHigher)
        }
    }
}

/** The controls, shown once at the bottom instead of on every row. Keyboard and pad keys side by side. */
@Composable
private fun LadderLegend() {
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "◀ ▶ browse     ▲ further out     ▼ closer to my taste",
            style = ladderText(LadderType.Legend),
            color = colors.textMuted,
        )
        Text(
            text = "Enter / A preview, again to stop     L / X love     S / Y skip",
            style = ladderText(LadderType.Legend),
            color = colors.textMuted,
        )
    }
}

@Composable
private fun BigPill(text: String, onClick: () -> Unit, selected: Boolean = false) {
    val colors = MaterialTheme.nuvio.colors
    Text(
        text = text,
        style = ladderText(LadderType.Body),
        color = if (selected) colors.onAccent else colors.textPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) colors.accent else colors.surfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/** One audition being opened: which rung. A class, not a data class, so asking again for the same rung starts it again. */
private class AuditionTarget(val id: String, val name: String)

/** Scrolls so the rung at [index] is fully on screen, only when it is not already. */
private suspend fun LazyListState.ensureVisible(key: Any?, index: Int) {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    val inView = info != null && info.offset >= layoutInfo.viewportStartOffset &&
        info.offset + info.size <= layoutInfo.viewportEndOffset
    if (!inView) animateScrollToItem(index)
}

/** The search card: one pill per offer ("Ladder into Shoegaze", "Climb into / out from X"), above the first result. */
@Composable
internal fun LadderOfferRow(offers: List<LadderOffer>, onPick: (LadderRequest) -> Unit, modifier: Modifier = Modifier) {
    if (offers.isEmpty()) return
    val colors = MaterialTheme.nuvio.colors
    Row(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        offers.forEach { offer ->
            Text(
                text = offer.label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.accent)
                    .clickable { onPick(offer.request) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private fun feedbackLabel(feedback: RungFeedback): String = when (feedback) {
    // The words of the buttons and the legend (Closer / Further out), so a mark reads as what was asked for.
    RungFeedback.TooFar -> "Closer next"
    RungFeedback.TooClose -> "Further out next"
    RungFeedback.Skip -> "Skip"
    RungFeedback.Love -> "Love"
}

/** Item 5: the build details of the ladder as small monospaced text, for tuning. Hidden until F3. */
@Composable
private fun LadderDebugPanel(lines: List<String>) {
    val colors = MaterialTheme.nuvio.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceCard).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Debug (F3 hides)", style = ladderText(LadderType.Legend), color = colors.textMuted)
        lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = colors.textSecondary) }
    }
}
