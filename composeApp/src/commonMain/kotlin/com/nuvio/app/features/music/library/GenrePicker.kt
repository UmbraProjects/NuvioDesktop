package com.nuvio.app.features.music.library

import com.nuvio.app.core.ui.collectIsKeyboardFocusedAsState
import com.nuvio.app.core.ui.smoothBorder
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.core.ui.NuvioGlass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import com.nuvio.app.core.ui.NuvioAsyncImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicCover
import com.nuvio.app.features.music.MusicMessage
import com.nuvio.app.features.music.identity.MusicPaletteResolver
import com.nuvio.app.features.music.identity.identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The genre picker for one saved artist: what is known about its genres, a one-tap suggestion ([suggestGenres], never
 * applied on its own), the genres already in the library, a box to type one, and a retry that is withheld while a previous
 * empty look-up is being waited out. The chips toggle, up to three; Done writes the chosen ones as the artist's own
 * ([LibraryArtist.manualGenres]), in the order they were picked (the first leads); taking every chip off and pressing Done
 * clears the person's genres. The artist room opens it ([onOpen] null there, as the room is already open). It was the
 * Constellation's picker; the Constellation is retired.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenrePickerPanel(
    artist: LibraryArtist,
    library: List<LibraryArtist>,
    working: Boolean,
    onApply: (List<String>) -> Unit,
    onLookAgain: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    val colors = MaterialTheme.nuvio.colors
    val now = LibraryClock.nowEpochMs()
    val suggestions = remember(artist, library) { suggestGenres(artist, library) }
    val choices = remember(library) { genreChoices(library) }
    val wait = artist.retryTagsInMs(now)
    var typed by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    var chosen by remember(artist.id) { mutableStateOf(artist.manualGenres) }
    fun isChosen(genre: String) = chosen.any { sameGenre(it, genre) }
    val toggle: (String) -> Unit = { genre -> chosen = toggledManualGenre(chosen, genre) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(360.dp)
            .background(NuvioGlass.night(colors.background, colors.accent), shape)
            .musicGlass(shape)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(artist.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                working -> "Looking it up…"
                artist.hasLookedUpGenres() && artist.genres.isNotEmpty() ->
                    "Found online: ${artist.genres.take(3).joinToString(", ") { displayGenre(it) }}. The ones you pick come first."
                artist.hasLookedUpGenres() -> "Genre data was found online. The ones you pick come first."
                artist.tagMisses > 0 && wait > 0L ->
                    "No genre data found online. The next look-up is in ${retryWaitText(wait)}; until then pick one yourself."
                artist.tagMisses > 0 -> "No genre data found online last time. You can look again, or pick one yourself."
                else -> "No genre data yet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        // What will be saved, in pick order; a tap takes one off. Typed genres show here too, as no chip below may name them.
        Text(
            if (chosen.isEmpty()) "Pick up to three" else if (manualGenresFull(chosen)) "Chosen (three, the most)" else "Chosen",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted,
        )
        if (chosen.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chosen.forEach { genre -> GenreChip(displayGenre(genre), selected = true) { toggle(genre) } }
            }
        }
        if (suggestions.isNotEmpty()) {
            Text("Suggested", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            suggestions.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GenreChip(displayGenre(s.genre), selected = isChosen(s.genre)) { toggle(s.genre) }
                    Text(s.reason, style = MaterialTheme.typography.bodySmall, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (choices.isNotEmpty()) {
            Text("In your library", style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { genre ->
                    GenreChip(displayGenre(genre), selected = isChosen(genre)) { toggle(genre) }
                }
            }
        }
        val apply: () -> Unit = {
            val next = withTypedGenres(chosen, typed)
            if (parseGenreInput(typed).isEmpty()) {
                rejected = true
            } else {
                chosen = next
                typed = ""
            }
        }
        NuvioTextField(
            value = typed,
            onValueChange = {
                typed = it
                rejected = false
            },
            placeholder = "Or type some, like uk garage, grime",
            isError = rejected,
            supportingText = if (rejected) "That is not a genre the library can use" else null,
            onImeAction = apply,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (typed.isNotBlank()) GenreChip("Add", selected = false) { apply() }
            // Saves the chosen genres (and any typed but not yet added) and places the artist; nothing is written until then.
            val toSave = withTypedGenres(chosen, typed)
            // Empty only clears what the person had chosen before (an unplaced artist has nothing to clear).
            if (toSave != artist.manualGenres) GenreChip("Done", selected = true) { onApply(toSave) }
            if (!working && wait == 0L) GenreChip("Look again", selected = false, onClick = onLookAgain)
            if (onOpen != null) GenreChip("Open artist room", selected = false, onClick = onOpen)
        }
    }
}

/** A chip the d-pad can reach: 22sp text, a focus ring, Enter or a click to use it. */
@Composable
private fun GenreChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsKeyboardFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(20.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) colors.onAccent else colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .musicGlass(shape, raised = hovered, selected = selected, keyFocused = focused)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
