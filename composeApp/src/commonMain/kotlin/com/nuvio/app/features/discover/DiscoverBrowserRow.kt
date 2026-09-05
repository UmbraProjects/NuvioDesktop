package com.nuvio.app.features.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.accentFill
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.search.DiscoverCatalogOption
import com.nuvio.app.features.search.DiscoverUiState
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.search.discoverCatalogDisplayLabels
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.discover_all_filters
import org.jetbrains.compose.resources.stringResource

/**
 * The two header segments. The second is called Filter, not Genre: the addon protocol calls this
 * extra "genre", but its values are routinely years, decades, or providers, so "genre" is
 * misleading in the UI. Wire-level naming (SearchRepository.selectDiscoverGenre) keeps the
 * protocol term.
 */
enum class DiscoverPickerSegment {
    Catalog,
    Filter,
}

/**
 * One selectable entry in the picker panel. Catalog rows carry the disambiguated display label
 * computed over the *full* catalog list, so mixed-type lists read unambiguously.
 */
data class DiscoverPickerEntry(
    val key: String,
    val label: String,
    val selected: Boolean,
)

/**
 * Catalog labels for the picker, computed over every available catalog rather than the
 * type-filtered subset. There is no separate type selector — the catalog's own name carries its
 * type ("Simkl Trending Movies - Films"), and [discoverCatalogDisplayLabels] appends a type
 * qualifier only where two catalogs would otherwise read identically.
 */
fun DiscoverUiState.catalogPickerEntries(): List<DiscoverPickerEntry> {
    val catalogs = availableCatalogs
    val labels = discoverCatalogDisplayLabels(catalogs)
    return catalogs.mapIndexed { index, catalog ->
        DiscoverPickerEntry(
            key = catalog.key,
            label = labels.getOrElse(index) { catalog.catalogName },
            selected = catalog.key == selectedCatalogKey,
        )
    }
}

/**
 * Filter values for the selected catalog. "All" leads unless the catalog requires a value, in
 * which case there is no unfiltered option to offer.
 */
@Composable
fun DiscoverUiState.filterPickerEntries(): List<DiscoverPickerEntry> {
    val catalog = selectedCatalog ?: return emptyList()
    val allLabel = stringResource(Res.string.discover_all_filters)
    return buildList {
        if (!catalog.genreRequired) {
            add(
                DiscoverPickerEntry(
                    key = "",
                    label = allLabel,
                    selected = selectedGenre == null,
                ),
            )
        }
        catalog.genreOptions.forEach { genre ->
            add(DiscoverPickerEntry(key = genre, label = genre, selected = genre == selectedGenre))
        }
    }
}

/**
 * The row-1 header: the catalog name, then — once a catalog is chosen — the filter, both clickable
 * and both opening their list into the row body. Rendered through `NuvioShelfSection`'s
 * `titleContent` slot so it keeps the shelf's header line, accent bar, and view-all pill.
 */
@Composable
fun DiscoverRowHeader(
    catalogLabel: String,
    filterLabel: String?,
    activeSegment: DiscoverPickerSegment?,
    onSegmentClick: (DiscoverPickerSegment) -> Unit,
    onSegmentPositioned: (DiscoverPickerSegment, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiscoverHeaderSegment(
            label = catalogLabel,
            active = activeSegment == DiscoverPickerSegment.Catalog,
            onClick = { onSegmentClick(DiscoverPickerSegment.Catalog) },
            onPositioned = { x -> onSegmentPositioned(DiscoverPickerSegment.Catalog, x) },
        )
        // The filter segment only exists once a catalog is selected and that catalog exposes
        // filter values.
        if (filterLabel != null) {
            Text(
                text = "·",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
            DiscoverHeaderSegment(
                label = filterLabel,
                active = activeSegment == DiscoverPickerSegment.Filter,
                onClick = { onSegmentClick(DiscoverPickerSegment.Filter) },
                onPositioned = { x -> onSegmentPositioned(DiscoverPickerSegment.Filter, x) },
            )
        }
    }
}

@Composable
private fun DiscoverHeaderSegment(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onPositioned: (Float) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            // Reported in the header Row's own coordinates so the panel can hang off this segment
            // rather than off the row's left edge.
            .onGloballyPositioned { onPositioned(it.positionInParent().x) }
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = if (active) tokens.colors.accent else tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // Rotating a caret glyph would need a layout pass per frame for no real gain; swapping
            // the glyph reads the same and costs nothing.
            text = if (active) "▴" else "▾",
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) tokens.colors.accent else tokens.colors.textMuted,
        )
    }
}

/**
 * The picker panel that takes over the row body while a segment is open. Sized and placed relative
 * to the row it belongs to — never to the window — because the row's position on screen moves with
 * TV mode, adaptive hero, and hero height.
 */
@Composable
fun BoxScope.DiscoverPickerPanel(
    entries: List<DiscoverPickerEntry>,
    maxHeight: Dp,
    maxWidth: Dp,
    horizontalPadding: Dp,
    anchorX: Dp,
    onEntryClick: (DiscoverPickerEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val listState = rememberLazyListState()
    val selectedIndex = entries.indexOfFirst { it.selected }
    // Open on the current selection rather than at the top: with a few hundred catalogs installed,
    // the one in use is otherwise nowhere near the visible window.
    LaunchedEffect(entries) {
        if (selectedIndex > 0) listState.scrollToItem(selectedIndex)
    }
    // Width follows the longest label. LazyColumn cannot be measured intrinsically (it is a
    // SubcomposeLayout), so the text is measured directly instead — otherwise the panel is a fixed
    // fraction of the row and mostly empty for short catalog names. Measured once per list, and the
    // selected row's SemiBold weight is covered by DiscoverPickerWidthSlack.
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val panelWidth = remember(entries, labelStyle, density, maxWidth) {
        val widestPx = entries.maxOfOrNull { entry ->
            textMeasurer.measure(entry.label, labelStyle, maxLines = 1).size.width
        } ?: 0
        val contentWidth = with(density) { widestPx.toDp() }
        (contentWidth + DiscoverPickerRowPadding * 2 + DiscoverPickerWidthSlack)
            .coerceIn(DiscoverPickerMinWidth, maxWidth.coerceAtLeast(DiscoverPickerMinWidth))
    }
    // Left-align the panel with the segment that opened it, but never let it run off the right edge.
    val offsetX = anchorX.coerceIn(0.dp, (maxWidth - panelWidth).coerceAtLeast(0.dp))
    Surface(
        modifier = modifier
            .align(Alignment.TopStart)
            .padding(start = horizontalPadding + offsetX, end = horizontalPadding)
            .width(panelWidth),
        color = tokens.colors.surfaceCard,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        shadowElevation = tokens.elevation.overlay,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = maxHeight).padding(vertical = 6.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                Text(
                    text = entry.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEntryClick(entry) }
                        .background(
                            if (entry.selected) {
                                tokens.colors.accentFill(0.16f)
                            } else {
                                androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent)
                            },
                        )
                        .padding(horizontal = DiscoverPickerRowPadding, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (entry.selected) tokens.colors.accent else tokens.colors.textPrimary,
                    fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Stand-in for the poster strip when the selected catalog returns nothing (or is still loading).
 * Occupies real height so the row cannot collapse to a bare header — the header is the only way
 * back to a different catalog, so it must always be visible and clickable.
 */
@Composable
fun BoxScope.DiscoverRowBodyMessage(
    text: String,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 28.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.nuvio.colors.textMuted,
    )
}

/**
 * Everything that can occupy the Discover row's body instead of posters: the open picker, or the
 * empty/loading message. Both the plain list and the TV-mode immersive shelf render the row through
 * `HomeCatalogRowSection`, so both call this — keeping one behaviour rather than two that drift.
 */
@Composable
fun BoxScope.DiscoverRowBody(
    state: DiscoverUiState,
    itemsEmpty: Boolean,
    segment: DiscoverPickerSegment?,
    pickerMaxHeight: Dp,
    pickerMaxWidth: Dp,
    horizontalPadding: Dp,
    anchorX: Dp,
    onSegmentChange: (DiscoverPickerSegment?) -> Unit,
    emptyText: String,
    loadingText: String,
) {
    if (segment == null) {
        if (itemsEmpty) {
            DiscoverRowBodyMessage(
                text = if (state.isLoading) loadingText else emptyText,
                horizontalPadding = horizontalPadding,
            )
        }
        return
    }
    val entries = when (segment) {
        DiscoverPickerSegment.Catalog -> state.catalogPickerEntries()
        DiscoverPickerSegment.Filter -> state.filterPickerEntries()
    }
    DiscoverPickerPanel(
        entries = entries,
        maxHeight = pickerMaxHeight,
        maxWidth = pickerMaxWidth,
        horizontalPadding = horizontalPadding,
        anchorX = anchorX,
        onEntryClick = { entry ->
            when (segment) {
                DiscoverPickerSegment.Catalog -> {
                    SearchRepository.selectDiscoverCatalog(entry.key)
                    // Advance to the filter step rather than closing: picking a catalog is rarely
                    // the whole intent.
                    onSegmentChange(DiscoverPickerSegment.Filter)
                }
                DiscoverPickerSegment.Filter -> {
                    SearchRepository.selectDiscoverGenre(entry.key.ifBlank { null })
                    onSegmentChange(null)
                }
            }
        },
    )
}

/** Alpha the posters fade to while a picker owns the row body. Not 0 — the row stays legible as context. */
const val DiscoverPostersDimmedAlpha = 0.12f

/** Horizontal inset on a picker row; counted twice when sizing the panel to its widest label. */
private val DiscoverPickerRowPadding = 14.dp

/**
 * Breathing room past the measured text: covers the selected row's SemiBold weight (measured at
 * Normal) and keeps labels off the panel edge.
 */
private val DiscoverPickerWidthSlack = 24.dp

/** Floor so a list of very short values ("War", "2026") still reads as a panel rather than a chip. */
private val DiscoverPickerMinWidth = 180.dp

/** Fade duration for the poster dim / picker cross-fade. */
private const val DiscoverPickerFadeMs = 180

@Composable
fun rememberDiscoverPostersAlpha(pickerOpen: Boolean): Float {
    val alpha by animateFloatAsState(
        targetValue = if (pickerOpen) DiscoverPostersDimmedAlpha else 1f,
        animationSpec = tween(durationMillis = DiscoverPickerFadeMs),
    )
    return alpha
}

/** Header label for the currently selected catalog, or a prompt when nothing is selected yet. */
fun DiscoverCatalogOption?.headerLabel(fallback: String): String = this?.catalogName ?: fallback
