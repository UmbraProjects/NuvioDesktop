package com.nuvio.app.features.discover

import com.nuvio.app.core.ui.LocalScreenCovered
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.positionInWindow
import com.nuvio.app.core.ui.ContextMenuFill
import com.nuvio.app.core.ui.ContextMenuInvocation
import com.nuvio.app.core.ui.ContextMenuRowHeight
import com.nuvio.app.core.ui.CursorMenuPositionProvider
import com.nuvio.app.core.ui.NuvioPosterHoverTooltip
import com.nuvio.app.core.ui.secondaryClick
import kotlin.math.roundToInt

/*
 * The Nuvio Tracker site's filter bar, in Compose: a row of dropdown pills — each names its filter,
 * or what is picked in it, and turns accent-tinted while it narrows the list — opening a menu of
 * radio choices or checkboxes under it. Shared by Discover's row 1 and the Library.
 */

private val IconButtonSize = 34.dp
private val IconSize = 20.dp
private val MenuShape = RoundedCornerShape(10.dp)
private val OptionShape = RoundedCornerShape(7.dp)
private val MenuMinWidth = 220.dp
private val MenuMaxWidth = 560.dp
private val MenuMaxHeight = 460.dp
private val MenuWindowMargin = 8.dp

/** "Genres", "Genres: Drama, Crime" or "Genres: Drama +2" — what a filter's tooltip says. */
fun browseFilterSummary(label: String, picked: List<String>): String = when {
    picked.isEmpty() -> label
    picked.size <= 2 -> "$label: ${picked.joinToString(", ")}"
    else -> "$label: ${picked.first()} +${picked.size - 1}"
}

/**
 * The bar itself: one line of small icons that scrolls sideways — drag it, or rest the pointer near
 * an end, as a poster row does — rather than wrapping, so it never pushes the shelf down or runs
 * into whatever sits beside it.
 */
@Composable
fun BrowseFilterStrip(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .browseStripEdgeScroll(scroll)
            .browseStripDragScroll(scroll)
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** The row's own name at the head of the bar (the catalog, or the library row). */
@Composable
fun BrowseFilterTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(end = 10.dp).widthIn(max = 360.dp),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.nuvio.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun BrowseFilterSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .width(1.dp)
            .height(18.dp)
            .background(Color.White.copy(alpha = 0.18f)),
    )
}

/** Muted text at the end of the bar: a count, or a hint. */
@Composable
fun BrowseFilterCaption(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.nuvio.colors.textMuted,
        maxLines = 1,
    )
}

/** An icon that acts straight away ("Clear filters"), named by its tooltip. */
@Composable
fun BrowseFilterIconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    NuvioPosterHoverTooltip(title = label) {
        BrowseFilterIconButton(icon = icon, label = label, on = false, open = false, onPress = { onClick() })
    }
}

/**
 * A filter as a small white icon. Its tooltip names the filter and what is picked in it; it turns
 * accent-coloured, with a dot, while it narrows the list. A click (left or right) opens its menu at
 * the cursor, the way the right-click menu opens — one at a time, since the menu takes focus and a
 * press elsewhere closes it.
 */
@Composable
fun BrowseFilterMenu(
    icon: ImageVector,
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onOpenChange: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var openAt by remember { mutableStateOf<IntOffset?>(null) }
    val setOpenAt: (IntOffset?) -> Unit = { value ->
        val was = openAt != null
        openAt = value
        if (was != (value != null)) onOpenChange(value != null)
    }
    // TV Mode rebuilds a shelf's header on every row change: a menu torn down while open must
    // still report itself closed, or the page would stay frozen waiting for it.
    val latestOnOpenChange by rememberUpdatedState(onOpenChange)
    DisposableEffect(Unit) {
        onDispose { if (openAt != null) latestOnOpenChange(false) }
    }
    Box(modifier = modifier) {
        NuvioPosterHoverTooltip(title = if (openAt == null) label else "") {
            BrowseFilterIconButton(
                icon = icon,
                label = label,
                on = on,
                open = openAt != null,
                onPress = { cursor -> setOpenAt(if (openAt == null) cursor else null) },
            )
        }
        openAt?.takeIf { !LocalScreenCovered.current }?.let { cursor ->
            val density = LocalDensity.current
            val windowHeight = LocalWindowInfo.current.containerSize.height.toFloat()
            val marginPx = with(density) { MenuWindowMargin.toPx() }
            // The menu opens down from the cursor, or up from it when there's more room above.
            val room = maxOf(windowHeight - cursor.y, cursor.y.toFloat()) - marginPx * 2
            val maxHeight = with(density) { room.coerceAtLeast(160f).toDp() }.coerceAtMost(MenuMaxHeight)
            Popup(
                popupPositionProvider = remember(cursor) { CursorMenuPositionProvider(cursor) },
                onDismissRequest = { setOpenAt(null) },
                properties = PopupProperties(focusable = true),
            ) {
                BrowseFilterMenuSurface(maxHeight = maxHeight) {
                    content { setOpenAt(null) }
                }
            }
        }
    }
}

/** The icon button: [onPress] gets the cursor's window position, to open a menu there. */
@Composable
private fun BrowseFilterIconButton(
    icon: ImageVector,
    label: String,
    on: Boolean,
    open: Boolean,
    onPress: (IntOffset) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    var lastPress by remember { mutableStateOf(Offset.Zero) }
    val cursor = { IntOffset((windowOrigin.x + lastPress.x).roundToInt(), (windowOrigin.y + lastPress.y).roundToInt()) }
    Box(
        modifier = Modifier
            .size(IconButtonSize)
            .onGloballyPositioned { windowOrigin = it.positionInWindow() }
            .pointerInput(Unit) {
                // Where the press landed, read before the click fires so the menu opens under it.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            event.changes.firstOrNull()?.let { lastPress = it.position }
                        }
                    }
                }
            }
            .clip(CircleShape)
            .background(
                when {
                    open -> Color.White.copy(alpha = 0.16f)
                    hovered -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label) { onPress(cursor()) }
            .secondaryClick { onPress(ContextMenuInvocation.consume() ?: cursor()) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(IconSize),
            tint = if (on) tokens.colors.accent else Color.White.copy(alpha = if (hovered || open) 1f else 0.82f),
        )
        if (on) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 5.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.accent),
            )
        }
    }
}

/** The right-click menu's panel, holding a filter's choices. */
@Composable
private fun BrowseFilterMenuSurface(maxHeight: Dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.widthIn(min = MenuMinWidth, max = MenuMaxWidth),
        color = ContextMenuFill,
        contentColor = Color.White,
        shape = MenuShape,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            content()
        }
    }
}

// ---- Menu content ----

/** A small upper-case heading inside a menu ("Rating", "Votes"). */
@Composable
fun BrowseMenuNote(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.nuvio.colors.textMuted,
    )
}

/** Radio choices: (value, label) pairs, one of them [value]. */
@Composable
fun BrowseMenuChoices(
    options: List<Pair<String, String>>,
    value: String,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        options.forEach { (v, label) ->
            BrowseMenuOption(selected = v == value, onClick = { onPick(v) }, radio = true) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A checkbox row. */
@Composable
fun BrowseMenuCheck(
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    BrowseMenuOption(selected = on, onClick = onClick, radio = false, modifier = modifier, content = content)
}

/** Checkboxes in two columns, for the long lists (genres, networks). */
@Composable
fun <T> BrowseMenuCheckColumns(
    items: List<T>,
    isOn: (T) -> Boolean,
    onToggle: (T) -> Unit,
    label: (T) -> String,
) {
    Column(modifier = Modifier.width(MenuColumnsWidth)) {
        items.chunked(2).forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth()) {
                pair.forEach { item ->
                    BrowseMenuCheck(on = isOn(item), onClick = { onToggle(item) }, modifier = Modifier.weight(1f)) {
                        Text(label(item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

private val MenuColumnsWidth = 400.dp

@Composable
private fun BrowseMenuOption(
    selected: Boolean,
    onClick: () -> Unit,
    radio: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ContextMenuRowHeight)
            .clip(OptionShape)
            .background(if (hovered) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (radio) RadioMark(selected) else CheckMark(selected)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (selected) Color.White else Color.White.copy(alpha = 0.86f),
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium,
        ) {
            content()
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(16.dp)
            .border(
                width = if (selected) 5.dp else 2.dp,
                color = if (selected) tokens.colors.accent else Color.White.copy(alpha = 0.35f),
                shape = CircleShape,
            ),
    )
}

@Composable
private fun CheckMark(selected: Boolean) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(shape)
            .background(if (selected) tokens.colors.accent else Color.Transparent)
            .border(2.dp, if (selected) tokens.colors.accent else Color.White.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = tokens.colors.onAccent,
            )
        }
    }
}

/** A pill-shaped quick pick inside a menu (the decades). */
@Composable
fun BrowseMenuChip(text: String, on: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) tokens.colors.accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (on) tokens.colors.accent.copy(alpha = 0.6f) else tokens.colors.borderStrong, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textPrimary,
    )
}

/** The decades offered under the year fields: label, from, to. */
val BrowseDecades: List<Triple<String, Int?, Int?>> = listOf(
    Triple("2020s", 2020, null), Triple("2010s", 2010, 2019), Triple("2000s", 2000, 2009),
    Triple("1990s", 1990, 1999), Triple("1980s", 1980, 1989), Triple("Older", null, 1979),
)

/** From / To year fields and the decade chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseYearsEditor(
    yearFrom: Int?,
    yearTo: Int?,
    onChange: (from: Int?, to: Int?) -> Unit,
) {
    // Typed text is held locally so a half-typed "19" doesn't refetch on every keystroke; a value
    // is applied once it is a plausible four-digit year (or cleared).
    var fromText by remember(yearFrom) { mutableStateOf(yearFrom?.toString().orEmpty()) }
    var toText by remember(yearTo) { mutableStateOf(yearTo?.toString().orEmpty()) }
    fun parsed(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1900..2100 }
    Column(modifier = Modifier.width(300.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf("From" to true, "To" to false).forEach { (label, isFrom) ->
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textMuted)
                    NuvioTextField(
                        value = if (isFrom) fromText else toText,
                        onValueChange = { raw ->
                            val text = raw.filter(Char::isDigit).take(4)
                            if (isFrom) fromText = text else toText = text
                            if (text.isEmpty() || parsed(text) != null) {
                                if (isFrom) onChange(parsed(text), yearTo) else onChange(yearFrom, parsed(text))
                            }
                        },
                        placeholder = "Any",
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
        }
        FlowRow(
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // "Any year" clears typed years too, and a picked decade's chip toggles it back off —
            // otherwise the only way out of a year filter was deleting the digits by hand.
            BrowseMenuChip(text = "Any year", on = yearFrom == null && yearTo == null) { onChange(null, null) }
            BrowseDecades.forEach { (label, from, to) ->
                val on = yearFrom == from && yearTo == to
                BrowseMenuChip(text = label, on = on) { if (on) onChange(null, null) else onChange(from, to) }
            }
        }
    }
}

/** A studio by name: TMDB's company search, ranked by catalogue size (the tracker's lookup). */
@Composable
fun BrowseStudioPicker(onPick: (TmdbBrowseStudio) -> Unit) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TmdbBrowseStudio>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            hits = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        hits = runCatching { TmdbBrowse.searchStudios(query) }.getOrDefault(emptyList())
    }
    Column(modifier = Modifier.width(300.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NuvioTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Find a studio…",
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        hits.forEach { studio ->
            BrowseMenuCheck(on = false, onClick = {
                onPick(studio)
                query = ""
                hits = emptyList()
            }) {
                Text(studio.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                studio.country?.let {
                    Text(" · $it", maxLines = 1, color = MaterialTheme.nuvio.colors.textMuted)
                }
            }
        }
    }
}

// ---- Sideways scrolling, as the poster rows do it ----

private fun Modifier.browseStripDragScroll(state: ScrollState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
        var totalDx = 0f
        var totalDy = 0f
        var dragging = false
        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.position - change.previousPosition
            totalDx += delta.x
            totalDy += delta.y
            if (!dragging) {
                val horizontal = abs(totalDx) > viewConfiguration.touchSlop && abs(totalDx) > abs(totalDy)
                val vertical = abs(totalDy) > viewConfiguration.touchSlop && abs(totalDy) > abs(totalDx)
                when {
                    vertical -> break
                    horizontal -> dragging = true
                    else -> continue
                }
            }
            state.dispatchRawDelta(-delta.x)
            // Consumed once it is a drag, so letting go over a pill doesn't also open it.
            change.consume()
        }
    }
}

private fun Modifier.browseStripEdgeScroll(state: ScrollState): Modifier = pointerInput(state) {
    val pointerScope = this
    val positions = Channel<Float?>(Channel.CONFLATED)
    coroutineScope {
        launch {
            var job: Job? = null
            var direction = 0
            for (x in positions) {
                val width = pointerScope.size.width.toFloat()
                val zone = (width * 0.08f).coerceAtMost(72f * pointerScope.density)
                val next = when {
                    x == null || state.maxValue == 0 -> 0
                    x < zone -> -1
                    x > width - zone -> 1
                    else -> 0
                }
                if (next != direction) {
                    direction = next
                    job?.cancel()
                    job = if (next != 0) launch {
                        while (true) {
                            state.dispatchRawDelta(next * 6f)
                            delay(16)
                        }
                    } else null
                }
            }
        }
        pointerScope.awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Exit -> positions.trySend(null)
                    PointerEventType.Move, PointerEventType.Enter -> positions.trySend(event.changes.firstOrNull()?.position?.x)
                    else -> Unit
                }
            }
        }
    }
}
