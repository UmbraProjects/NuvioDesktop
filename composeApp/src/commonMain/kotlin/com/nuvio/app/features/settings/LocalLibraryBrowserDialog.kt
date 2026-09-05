package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioDialogSurface
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.accentFill
import com.nuvio.app.core.ui.desktopHorizontalListNavigation
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.librarypvr.LibraryPvrRepository
import com.nuvio.app.features.locallibrary.LocalFolderType
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.locallibrary.LocalLibraryUiState
import com.nuvio.app.features.locallibrary.LocalMediaItem
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_add_toast_added
import nuvio.composeapp.generated.resources.settings_local_library_browse_close
import nuvio.composeapp.generated.resources.settings_local_library_browse_title
import nuvio.composeapp.generated.resources.settings_local_library_catalog_search_hint
import nuvio.composeapp.generated.resources.settings_local_library_filter_all
import nuvio.composeapp.generated.resources.settings_local_library_items_count
import nuvio.composeapp.generated.resources.settings_local_library_no_results
import nuvio.composeapp.generated.resources.settings_local_library_section_anime_movies
import nuvio.composeapp.generated.resources.settings_local_library_section_anime_series
import nuvio.composeapp.generated.resources.settings_local_library_section_movies
import nuvio.composeapp.generated.resources.settings_local_library_section_tv
import nuvio.composeapp.generated.resources.settings_local_library_unsorted
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The narrowest a poster may get before the grid drops a column. */
private val BrowserPosterMinWidth = 148.dp

/** One entry in the tab strip: a catalog, or one of the two sentinel scopes. */
private data class LocalLibraryTab(
    val id: String,
    val name: String,
    val color: Long?,
    val count: Int,
)

/**
 * The local library as a full-screen browser.
 *
 * The titles used to sit inline under the local-library settings, which pinned every row to the
 * settings column's width — four posters across on a 4K display. Here the grid owns the window, so
 * a row holds as many posters as the display can fit, and the catalog filter moves from the tiles
 * below into the tab strip along the top.
 */
@Composable
internal fun LocalLibraryBrowserDialog(
    state: LocalLibraryUiState,
    titlesState: LocalLibraryTitlesState,
    onDismiss: () -> Unit,
) {
    val pvr by LibraryPvrRepository.uiState.collectAsState()
    LaunchedEffect(Unit) { LibraryPvrRepository.ensureLoaded() }
    val monitoredContentIds = remember(pvr.monitoredItems) {
        pvr.monitoredItems.mapTo(mutableSetOf()) { it.contentId }
    }
    val addedText = stringResource(Res.string.library_add_toast_added)

    val allLabel = stringResource(Res.string.settings_local_library_filter_all)
    val unsortedLabel = stringResource(Res.string.settings_local_library_unsorted)
    val tabs = remember(state.items, state.catalogs, allLabel, unsortedLabel) {
        buildList {
            add(LocalLibraryTab(FILTER_ALL, allLabel, null, state.items.size))
            val unsorted = state.items.count { it.catalogId == null }
            if (unsorted > 0) add(LocalLibraryTab(FILTER_UNSORTED, unsortedLabel, null, unsorted))
            state.sortedCatalogs.forEach { catalog ->
                val count = state.items.count { it.catalogId == catalog.id }
                // Empty catalogs are management, not browsing: a tab that can only ever show
                // "nothing here" is noise in a strip you scroll sideways.
                if (count > 0) add(LocalLibraryTab(catalog.id, catalog.name, catalog.color, count))
            }
        }
    }

    // A catalog can be emptied (or deleted) while the browser is open — fall back to All rather
    // than leaving the strip with nothing highlighted.
    LaunchedEffect(tabs) {
        if (tabs.none { it.id == titlesState.filter }) titlesState.filter = FILTER_ALL
    }

    val query = titlesState.query.trim()
    val visible = remember(state.items, titlesState.filter, query) {
        val scoped = when (titlesState.filter) {
            FILTER_ALL -> state.items
            FILTER_UNSORTED -> state.items.filter { it.catalogId == null }
            else -> state.items.filter { it.catalogId == titlesState.filter }
        }
        if (query.isBlank()) {
            scoped
        } else {
            scoped.filter { item ->
                item.title.contains(query, ignoreCase = true) ||
                    item.displayYear?.contains(query, ignoreCase = true) == true
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Full-screen is the whole point — the platform default would clamp this back to a column.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NuvioDialogSurface(
            // A hair of inset rather than edge-to-edge, so the panel still reads as a modal sitting
            // over settings. It costs no column at any width the app runs at.
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                LocalLibraryBrowserHeader(total = state.items.size, onDismiss = onDismiss)
                // Search shares the tab row rather than sitting above it: both are ways of
                // narrowing the same grid, and stacking them cost a band of header height that
                // the grid wanted for another row of posters.
                LocalLibraryFilterBar(
                    tabs = tabs,
                    selectedId = titlesState.filter,
                    onSelect = { titlesState.filter = it },
                    query = titlesState.query,
                    onQueryChange = { titlesState.query = it },
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.07f)),
                )
                LocalLibraryBrowserGrid(
                    items = visible,
                    state = state,
                    monitoredContentIds = monitoredContentIds,
                    addedText = addedText,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LocalLibraryBrowserHeader(total: Int, onDismiss: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(Res.string.settings_local_library_browse_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.settings_local_library_items_count, total),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.settings_local_library_browse_close),
                tint = colors.textPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The tab strip and the search field on one line — the two ways of narrowing the grid. */
@Composable
private fun LocalLibraryFilterBar(
    tabs: List<LocalLibraryTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The strip takes what is left after the search field, so a long list of catalogs scrolls
        // rather than pushing search off the row.
        LocalLibraryTabStrip(
            tabs = tabs,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
        NuvioTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.width(300.dp),
            placeholder = stringResource(Res.string.settings_local_library_catalog_search_hint),
            leadingContent = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
    }
}

/**
 * The catalog tabs. Each carries the accent — filled for the selected one, a faint accent wash for
 * the rest — with the catalog's own colour as a dot on the left, so the strip stays readable as a
 * set while still telling the catalogs apart.
 */
@Composable
private fun LocalLibraryTabStrip(
    tabs: List<LocalLibraryTab>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        modifier = modifier
            // The strip has no vertical scroller of its own, so a plain wheel over it scrolls
            // sideways; drag and arrow-key navigation come with it.
            .desktopHorizontalListNavigation(listState, treatPlainScrollAsHorizontal = true),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tabs, key = { it.id }) { tab ->
            val selected = tab.id == selectedId
            val shape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (selected) colors.accentFill(0.22f) else colors.accentFill(0.07f),
                        shape,
                    )
                    .border(
                        width = 1.dp,
                        brush = if (selected) colors.accentFill(0.75f) else SolidColor(Color.Transparent),
                        shape = shape,
                    )
                    .clickable { onSelect(tab.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        // Sentinel scopes (All, Unsorted) have no catalog colour of their own, so
                        // they take the accent — the dot never goes missing and leaves the label
                        // sitting at a different offset from its neighbours.
                        .background(tab.color?.let { Color(it) } ?: colors.accent),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = tab.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.accent else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = tab.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) colors.accent.copy(alpha = 0.7f) else colors.textMuted.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LocalLibraryBrowserGrid(
    items: List<LocalMediaItem>,
    state: LocalLibraryUiState,
    monitoredContentIds: Set<String>,
    addedText: String,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    Box(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(BrowserPosterMinWidth),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(Res.string.settings_local_library_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            BROWSER_SECTIONS.forEach { section ->
                val sectionItems = items
                    .filter { it.type == section.type && it.isAnime == section.isAnime }
                    .sortedBy { it.title.lowercase() }
                if (sectionItems.isEmpty()) return@forEach
                item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(section.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.nuvio.colors.textPrimary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(sectionItems, key = { "${section.key}-${it.key}" }) { item ->
                    LocalPosterCard(
                        item = item,
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                        onResetPoster = { LocalLibraryRepository.resetPoster(item) },
                        onAssign = { catalogId ->
                            LocalLibraryRepository.assignToCatalog(item.key, catalogId)
                        },
                        isMonitored = item.contentId in monitoredContentIds,
                        onMonitor = { monitorLocalItem(item, addedText) },
                    )
                }
            }
        }
        NuvioDesktopVerticalScrollbar(gridState, Modifier.align(Alignment.CenterEnd))
    }
}

private data class BrowserSection(
    val key: String,
    val titleRes: StringResource,
    val type: LocalFolderType,
    val isAnime: Boolean,
)

private val BROWSER_SECTIONS = listOf(
    BrowserSection("movies", Res.string.settings_local_library_section_movies, LocalFolderType.MOVIES, false),
    BrowserSection("tv", Res.string.settings_local_library_section_tv, LocalFolderType.SERIES, false),
    BrowserSection("anime-movies", Res.string.settings_local_library_section_anime_movies, LocalFolderType.MOVIES, true),
    BrowserSection("anime-series", Res.string.settings_local_library_section_anime_series, LocalFolderType.SERIES, true),
)
