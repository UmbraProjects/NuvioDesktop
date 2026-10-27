package com.nuvio.app.features.music.library

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.defaultMusicCatalog
import kotlinx.coroutines.launch

/**
 * HTPC phase 3: "Where next from X?" on the artist page, under the release eras. Builds by itself when the page opens
 * (the artist's own other genres decide the direction; [ladderOutFrom] does the work) and is cached per artist and
 * direction for the session, so coming back to the page is instant.
 *
 *  - Five rung cards (empty shapes while loading, so the layout does not jump).
 *  - Direction chips, from the ladder's own destination and its alternatives; focusing or clicking one rebuilds the strip.
 *  - A "Similar in your library" row from the constellation links.
 *  - Enter or a click on a rung opens an audition panel under the strip: its hook starts playing, with Save and Close.
 */

/** Session cache of out-from ladders, by artist and direction. Only successful builds are kept, so a failure can be retried. */
internal object WhereNextCache {
    private val ladders = HashMap<String, ArtistLadderOutcome.Ready>()
    private fun key(artistId: String, destination: String?) = "$artistId|${destination?.let(::genreKey).orEmpty()}"
    fun get(artistId: String, destination: String?): ArtistLadderOutcome.Ready? = ladders[key(artistId, destination)]
    fun put(artistId: String, destination: String?, outcome: ArtistLadderOutcome.Ready) {
        ladders[key(artistId, destination)] = outcome
    }
}

/** The saved artists most like [artist] by the constellation's own tag similarity, best first. */
internal fun similarInLibrary(artist: LibraryArtist, all: List<LibraryArtist>, limit: Int = 8): List<LibraryArtist> {
    val byId = all.associateBy { it.id }
    return buildConstellationGraph(all).neighbours(artist.id).mapNotNull { byId[it.first] }.take(limit)
}

private const val PLACEHOLDER_CARDS = LADDER_RUNGS

@Composable
internal fun WhereNextStrip(artist: LibraryArtist, onOpenArtist: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.nuvio.colors
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultMusicCatalog() }
    var expanded by rememberSaveable(artist.id) { mutableStateOf(true) }
    var destination by rememberSaveable(artist.id) { mutableStateOf<String?>(null) }
    var attempt by remember(artist.id) { mutableIntStateOf(0) }
    var selectedId by remember(artist.id, destination) { mutableStateOf<String?>(null) }
    var note by remember(artist.id) { mutableStateOf<String?>(null) }

    val outcome by produceState<ArtistLadderOutcome?>(WhereNextCache.get(artist.id, destination), artist.id, destination, attempt) {
        val cached = WhereNextCache.get(artist.id, destination)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = null
        MusicLibraryRepository.ensureLoaded()
        val result = LadderArtistTargets.ladderOutFrom(artist.name, MusicLibraryRepository.state.value.artists, destination)
        if (result is ArtistLadderOutcome.Ready) WhereNextCache.put(artist.id, destination, result)
        value = result
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
        Text(
            text = (if (expanded) "▾ " else "▸ ") + "Where next from ${artist.name}?",
            fontSize = 28.sp,
            color = colors.textPrimary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 6.dp),
        )
        if (!expanded) return@Column

        if (chips.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(chips, key = { it }) { chip ->
                    val current = ready?.destination?.let { genreKey(it) == genreKey(chip) } == true
                    FocusPill(chip, selected = current, onActivate = { destination = chip })
                }
            }
        }

        when {
            outcome is ArtistLadderOutcome.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if ((outcome as ArtistLadderOutcome.Failed).reason == ArtistLadderFailure.NoOutward) "Nothing to lead out into yet" else "Couldn't build this right now",
                    fontSize = 24.sp,
                    color = colors.textMuted,
                )
                FocusPill("Try again", selected = false, onActivate = { attempt++ })
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceCard).padding(20.dp),
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(library, key = { it.id }) { other ->
                    FocusPill(other.name, selected = false, onActivate = { onOpenArtist(other.id) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A pill the d-pad can reach: focus ring and a small grow, Enter or click to activate. */
@Composable
private fun FocusPill(text: String, selected: Boolean, onActivate: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f)
    val shape = RoundedCornerShape(24.dp)
    Text(
        text = text,
        fontSize = 24.sp,
        color = if (selected) colors.onAccent else colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (selected) colors.accent else colors.surfaceElevated)
            .then(if (focused) Modifier.border(3.dp, colors.accent, shape) else Modifier)
            .focusable(interactionSource = source)
            .clickable(interactionSource = source, indication = null, onClick = onActivate)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

private val CARD_WIDTH = 190.dp

@Composable
private fun WhereNextCard(rung: LadderRung, chosen: Boolean, onActivate: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val picture by produceState<String?>(null, rung.candidate.name) { value = LadderArtwork.picture(rung.candidate.name) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (chosen || focused) colors.surfaceElevated else colors.surfaceCard)
            .then(if (focused || chosen) Modifier.border(3.dp, colors.accent, shape) else Modifier)
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
    Box(Modifier.width(CARD_WIDTH).height(CARD_WIDTH + 100.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceCard))
}
