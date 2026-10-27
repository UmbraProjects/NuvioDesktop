package com.nuvio.app.features.music.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.defaultMusicCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nuvio.app.features.music.MusicMessage
import kotlin.math.roundToInt

/**
 * Ladder phase 5: the five-rung screen. Opened from the Music tab's search card (one `// fork:music` hook in
 * MusicTabScreen); it replaces the tab body while open and [onBack] closes it. Rung 1 is nearest the listener's taste
 * (or the artist, out from), the last rung is the core of the sound. Each rung shows a picture, the artist, a
 * one-sentence reason and how typical of the genre they are. Playing and saving a rung is phase 6.
 *
 * Phase 9, audition: Up and Down move over the rungs; after a short pause ([AUDITION_SETTLE_MS]) the rung under the cursor
 * plays from its hook ([auditionRung]), and the room takes on their palette and motion ([LadderRoom]). Enter, or a click
 * on the row, auditions at once. "Play album" is the phase 6 play from the first track.
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
        value = LadderArtistTargets.load(request, MusicLibraryRepository.state.value.artists, progress)
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

    // Phase 9: the rung under the cursor (by id, so a re-rank cannot move the cursor onto another artist), and the audition
    // that is waiting to start or running. A new AuditionTarget cancels the one before it, so a held arrow key plays only
    // the rung it comes to rest on.
    val shown = state
    val rungs = (shown as? LadderState.Ready)?.view?.ladder?.rungs.orEmpty()
    var focusedId by remember(request) { mutableStateOf<String?>(null) }
    var auditionTarget by remember(request) { mutableStateOf<AuditionTarget?>(null) }
    val focusedIndex = rungs.indexOfFirst { it.candidate.id == focusedId }
    val focusedRung = rungs.getOrNull(focusedIndex)
    fun focusRung(rung: LadderRung, immediate: Boolean) {
        focusedId = rung.candidate.id
        auditionTarget = AuditionTarget(rung.candidate.id, rung.candidate.name, immediate)
    }
    LaunchedEffect(auditionTarget) {
        val target = auditionTarget ?: return@LaunchedEffect
        if (!target.immediate) delay(AUDITION_SETTLE_MS)
        note(target.id, "Opening…")
        val result = auditionRung(catalog, target.name, MusicLibraryRepository.state.value.artists)
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
    val headerItems = (if (view?.note != null) 1 else 0) +
        (if (view != null && view.alternatives.isNotEmpty() && request is LadderRequest.Artist) 1 else 0)
    LaunchedEffect(focusedId) {
        if (focusedIndex >= 0) listState.ensureVisible(focusedId, headerItems + focusedIndex)
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    LadderRoom(
        room = room,
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || rungs.isEmpty()) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown, Key.DirectionUp -> {
                        val next = moveFocus(focusedIndex, if (event.key == Key.DirectionDown) 1 else -1, rungs.size)
                        rungs.getOrNull(next)?.let { focusRung(it, immediate = false) }
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        focusedRung?.let { focusRung(it, immediate = true) }
                        focusedRung != null
                    }
                    else -> false
                }
            },
    ) {
        val colors = MaterialTheme.nuvio.colors
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topChromePadding),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "Back",
                    style = MaterialTheme.typography.bodyMedium,
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
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                if (!progress.isFresh) {
                    Text(
                        text = "Start over",
                        style = MaterialTheme.typography.bodyMedium,
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
                is LadderState.Ready -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shown.view.note?.let { note ->
                        item {
                            Text(text = note, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                        }
                    }
                    if (shown.view.alternatives.isNotEmpty() && request is LadderRequest.Artist) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Lead out into", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                                shown.view.alternatives.forEach { genre ->
                                    Text(
                                        text = genre,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textSecondary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(colors.surfaceCard)
                                            .clickable { onRequest(request.copy(destination = genre)) }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                    items(shown.view.ladder.rungs, key = { it.candidate.id }) { rung ->
                        val id = rung.candidate.id
                        LadderRungRow(
                            rung = rung,
                            focused = id == focusedId,
                            note = notes[id],
                            onFeedback = { mark(rung, it) },
                            onAudition = { focusRung(rung, immediate = true) },
                            onPlayAlbum = {
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
                            },
                            onSave = {
                                note(id, "Saving…")
                                scope.launch {
                                    val saved = saveRung(catalog, rung.candidate.name, foundViaLabel(shown.view.title))
                                    note(id, if (saved != null) "Saved to your Library" else "Not found on YouTube Music")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One audition: which rung and whether to start at once (a click or Enter) or after the settle pause (an arrow key). A class, not a data class, so asking again for the same rung starts it again. */
private class AuditionTarget(val id: String, val name: String, val immediate: Boolean)

/** Scrolls so the rung at [index] is fully on screen, only when it is not already. */
private suspend fun LazyListState.ensureVisible(key: Any?, index: Int) {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    val inView = info != null && info.offset >= layoutInfo.viewportStartOffset &&
        info.offset + info.size <= layoutInfo.viewportEndOffset
    if (!inView) animateScrollToItem(index)
}

private fun requestTitle(request: LadderRequest): String = when (request) {
    is LadderRequest.Genre -> "Into ${request.genre}"
    is LadderRequest.Artist -> if (request.mode == LadderMode.Into) "Into ${request.name}" else "Out from ${request.name}"
}

@Composable
private fun LadderRungRow(
    rung: LadderRung,
    focused: Boolean,
    note: String?,
    onFeedback: (RungFeedback) -> Unit,
    onAudition: () -> Unit,
    onPlayAlbum: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val picture by produceState<String?>(null, rung.candidate.name) { value = LadderArtwork.picture(rung.candidate.name) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) colors.surfaceElevated else colors.surfaceCard)
            .then(if (focused) Modifier.border(2.dp, colors.accent, shape) else Modifier)
            .clickable(onClick = onAudition)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("${rung.step}", style = MaterialTheme.typography.bodyMedium, color = colors.onAccent)
        }
        Spacer(Modifier.width(12.dp))
        MusicCover(picture, rung.candidate.name, size = 64.dp, circle = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = rung.candidate.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = rung.why, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            val facts = buildList {
                add("${(rung.purity * 100).roundToInt()}% of its tags")
                if (!rung.candidate.playable) add("may not be playable here")
            }
            Text(text = facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Save",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSave).padding(vertical = 4.dp, horizontal = 4.dp),
                )
                Text(
                    text = "Play album",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPlayAlbum).padding(vertical = 4.dp, horizontal = 4.dp),
                )
                if (note != null) {
                    Text(text = note, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (feedback in RungFeedback.entries) {
                    val chosen = rung.mark == feedback
                    Text(
                        text = feedbackLabel(feedback),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chosen) colors.onAccent else colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (chosen) colors.accent else colors.surfaceElevated)
                            .clickable { onFeedback(feedback) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
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
    RungFeedback.TooFar -> "Too far"
    RungFeedback.TooClose -> "Too close"
    RungFeedback.Skip -> "Skip"
    RungFeedback.Love -> "Love"
}
