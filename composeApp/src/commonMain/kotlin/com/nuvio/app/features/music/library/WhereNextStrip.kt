package com.nuvio.app.features.music.library

import com.nuvio.app.core.ui.NuvioGlass
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.defaultMusicCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * HTPC phase 3: "Where next from X?" on the artist page, under the release eras. It builds by itself, but lazily (the
 * artist's own other genres decide the direction; [ladderOutFrom] does the work): about [WHERE_NEXT_START_DELAY_MS]
 * after the page opens, or as soon as focus reaches the strip's heading, and only if the person is still on the page.
 * The build queues behind other MusicBrainz calls ([BackgroundWork]). A built ladder is kept per artist and direction in
 * memory and on disk for a week ([WhereNextLadders]), so coming back to the page is instant and costs no call. Refresh
 * rebuilds it; a setting (Music settings) turns automatic building off, and then the strip waits for "Show where next".
 *
 *  - Five rung cards (empty shapes while loading, so the layout does not jump).
 *  - Direction chips, from the ladder's own destination and its alternatives; focusing or clicking one rebuilds the strip.
 *  - A "Similar in your library" row from the constellation links.
 *  - Enter or a click on a rung opens an audition panel under the strip: its hook starts playing, with Save and Close.
 */

/** Session cache of out-from ladders, by artist and direction. Only successful builds are kept, so a failure can be retried. */
internal object WhereNextCache {
    private val ladders = HashMap<String, ArtistLadderOutcome.Ready>()
    internal fun key(artistId: String, destination: String?) = "$artistId|${destination?.let(::genreKey).orEmpty()}"
    fun get(artistId: String, destination: String?): ArtistLadderOutcome.Ready? = ladders[key(artistId, destination)]
    fun put(artistId: String, destination: String?, outcome: ArtistLadderOutcome.Ready) {
        ladders[key(artistId, destination)] = outcome
    }
    fun remove(artistId: String, destination: String?) {
        ladders.remove(key(artistId, destination))
    }
}

/** The saved artists most like [artist] by the constellation's own tag similarity, best first. */
internal fun similarInLibrary(artist: LibraryArtist, all: List<LibraryArtist>, limit: Int = 8): List<LibraryArtist> {
    val byId = all.associateBy { it.id }
    return buildConstellationGraph(all).neighbours(artist.id).mapNotNull { byId[it.first] }.take(limit)
}

private const val PLACEHOLDER_CARDS = LADDER_RUNGS

/** How long the artist page stays open before its ladder is built, when focus has not reached the strip sooner. */
internal const val WHERE_NEXT_START_DELAY_MS = 1_500L

/** Whether "Where next?" ladders build without being asked (the Music settings switch; on by default). */
@Composable
internal fun rememberAutoWhereNext(): Boolean {
    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    return state.settings.autoWhereNext
}

@Composable
internal fun WhereNextStrip(artist: LibraryArtist, onOpenArtist: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    val auto = rememberAutoWhereNext()
    var expanded by rememberSaveable(artist.id) { mutableStateOf(true) }
    var destination by rememberSaveable(artist.id) { mutableStateOf<String?>(null) }
    var attempt by remember(artist.id) { mutableIntStateOf(0) }
    var selectedId by remember(artist.id, destination) { mutableStateOf<String?>(null) }
    var note by remember(artist.id) { mutableStateOf<String?>(null) }

    // Lazy build: nothing is asked of MusicBrainz until the page has been open a moment or focus reaches this strip
    // ([wanted]). Leaving the page cancels the wait and any build under way. A ladder kept in memory or on disk shows at
    // once, wanted or not, because it costs nothing.
    var wanted by remember(artist.id) { mutableStateOf(false) }
    // The person did something that asks for a ladder (a direction, Refresh, Try again, Show where next): that build
    // does not queue behind other MusicBrainz calls.
    var asked by remember(artist.id) { mutableStateOf(false) }
    // Whether memory and disk have been looked at, so the "not built" prompt does not flash before a kept ladder shows.
    var looked by remember(artist.id, destination) { mutableStateOf(WhereNextLadders.peek(artist.id, destination) != null) }
    LaunchedEffect(artist.id, auto) {
        if (auto) {
            delay(WHERE_NEXT_START_DELAY_MS)
            wanted = true
        }
    }

    val outcome by produceState<ArtistLadderOutcome?>(
        WhereNextLadders.peek(artist.id, destination), artist.id, destination, attempt, wanted, asked,
    ) {
        val cached = WhereNextLadders.cached(artist.id, destination)
        looked = true
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = null
        if (!wanted) return@produceState
        value = WhereNextLadders.build(artist, destination, background = !asked)
    }
    val ready = outcome as? ArtistLadderOutcome.Ready

    // The chips keep their order once known, so choosing one does not reshuffle the row.
    var chips by remember(artist.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(ready) {
        if (chips.isEmpty() && ready != null) chips = (listOfNotNull(ready.destination) + ready.alternatives).distinct()
    }

    val rungs = ready?.ladder?.rungs.orEmpty()
    val selected = rungs.firstOrNull { it.candidate.id == selectedId }
    LaunchedEffect(selectedId) {
        val rung = rungs.firstOrNull { it.candidate.id == selectedId } ?: return@LaunchedEffect
        note = "Opening…"
        note = auditionNote(auditionRung(catalog, rung.candidate.name, MusicLibraryRepository.state.value.artists))
    }

    val library = remember(artist.id, MusicLibraryRepository.state.value.artists.size) {
        similarInLibrary(artist, MusicLibraryRepository.state.value.artists)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = (if (expanded) "▾ " else "▸ ") + "Where next from ${artist.name}?",
                fontSize = 28.sp,
                color = colors.textPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    // Focus arriving at the heading means the person is heading for the strip: start building.
                    .onFocusChanged { if (it.isFocused && auto) wanted = true }
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp),
            )
            if (expanded && ready != null) {
                FocusPill("Refresh", selected = false, onActivate = {
                    scope.launch {
                        WhereNextLadders.forget(artist.id, destination)
                        asked = true
                        wanted = true
                        attempt++
                    }
                })
            }
        }
        if (!expanded) return@Column

        if (chips.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(chips, key = { it }) { chip ->
                    val current = ready?.destination?.let { genreKey(it) == genreKey(chip) } == true
                    FocusPill(chip, selected = current, onActivate = { asked = true; wanted = true; destination = chip })
                }
            }
        }

        if (!auto && !wanted && looked && outcome == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Not built, to save network calls", fontSize = 24.sp, color = colors.textMuted)
                FocusPill("Show where next", selected = false, onActivate = { asked = true; wanted = true })
            }
        }

        when {
            outcome is ArtistLadderOutcome.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if ((outcome as ArtistLadderOutcome.Failed).reason == ArtistLadderFailure.NoOutward) "Nothing to lead out into yet" else "Couldn't build this right now",
                    fontSize = 24.sp,
                    color = colors.textMuted,
                )
                FocusPill("Try again", selected = false, onActivate = { asked = true; wanted = true; attempt++ })
            }
            else -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp),
            ) {
                if (rungs.isEmpty()) {
                    items(PLACEHOLDER_CARDS) { PlaceholderCard() }
                } else {
                    items(rungs, key = { it.candidate.id }) { rung ->
                        WhereNextCard(rung, chosen = rung.candidate.id == selectedId) {
                            selectedId = if (selectedId == rung.candidate.id) null else rung.candidate.id
                        }
                    }
                }
            }
        }

        selected?.let { rung ->
            Column(
                Modifier.fillMaxWidth().musicGlass(RoundedCornerShape(16.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(rung.candidate.name, fontSize = 32.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rung.why, fontSize = 24.sp, color = colors.textSecondary)
                note?.let { Text(it, fontSize = 22.sp, color = colors.textMuted) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusPill("Hear it again", selected = false, onActivate = {
                        scope.launch {
                            note = "Opening…"
                            note = auditionNote(auditionRung(catalog, rung.candidate.name, MusicLibraryRepository.state.value.artists))
                        }
                    })
                    FocusPill("Save", selected = false, onActivate = {
                        scope.launch {
                            note = "Saving…"
                            val saved = saveRung(catalog, rung.candidate.name, "Where next from ${artist.name}")
                            note = if (saved != null) "Saved to your Library" else "Not found on YouTube Music"
                        }
                    })
                    FocusPill("Close", selected = false, onActivate = { selectedId = null })
                }
            }
        }

        if (library.isNotEmpty()) {
            Text("Similar in your library", fontSize = 24.sp, color = colors.textSecondary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(library, key = { it.id }) { other -> SimilarPortrait(other, onOpen = { onOpenArtist(other.id) }) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** An artist from the Library as a round portrait with the name under it; a ring on hover or focus. */
@Composable
private fun SimilarPortrait(artist: LibraryArtist, onOpen: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier
            .width(104.dp)
            .hoverable(source)
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.border(2.dp, if (hovered || focused) colors.accent else NuvioGlass.EdgeColor, CircleShape).padding(3.dp)) {
            MusicCover(artist.art.thumb, artist.name, size = 84.dp, circle = true)
        }
        Text(
            artist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** A pill the d-pad can reach: the glass, a ring on focus, Enter or click to activate. */
@Composable
private fun FocusPill(text: String, selected: Boolean, onActivate: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(24.dp)
    Text(
        text = text,
        fontSize = 22.sp,
        color = if (selected) colors.onAccent else colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .musicGlass(shape, raised = hovered, selected = selected, keyFocused = focused)
            .hoverable(source)
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onActivate)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

private val CARD_WIDTH = 190.dp

@Composable
private fun WhereNextCard(rung: LadderRung, chosen: Boolean, onActivate: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val picture by produceState<String?>(null, rung.candidate.name) { value = LadderArtwork.picture(rung.candidate.name) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .musicGlass(shape, raised = hovered || chosen, keyFocused = focused || chosen)
            .hoverable(source)
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onActivate)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MusicCover(picture, rung.candidate.name, size = CARD_WIDTH - 20.dp)
        Text("${rung.step}  ${rung.candidate.name}", fontSize = 24.sp, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(rung.why, fontSize = 20.sp, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** An empty card shape while the strip builds, the same size as a real one so nothing jumps. */
@Composable
private fun PlaceholderCard() {
    val colors = MaterialTheme.nuvio.colors
    Box(Modifier.width(CARD_WIDTH).height(CARD_WIDTH + 100.dp).musicGlass(RoundedCornerShape(14.dp)))
}
