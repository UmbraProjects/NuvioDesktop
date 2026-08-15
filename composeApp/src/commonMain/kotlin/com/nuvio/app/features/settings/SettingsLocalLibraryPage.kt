package com.nuvio.app.features.settings

import com.nuvio.app.core.build.AppFeaturePolicy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalOpenMetaDetails
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.features.librarypvr.LibraryPvrRepository
import com.nuvio.app.features.librarypvr.LibraryPvrScheduler
import com.nuvio.app.features.librarypvr.MonitorMode
import com.nuvio.app.features.locallibrary.LocalAnimeFixDialog
import com.nuvio.app.features.locallibrary.LocalCatalog
import com.nuvio.app.features.locallibrary.LocalDirectoryPicker
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.locallibrary.LocalFolderType
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.locallibrary.LocalLibraryPlaybackPreference
import com.nuvio.app.features.locallibrary.LocalLibraryUiState
import com.nuvio.app.features.locallibrary.LocalMatchCandidate
import com.nuvio.app.features.locallibrary.LocalMatchProvider
import com.nuvio.app.features.locallibrary.LocalMatchState
import com.nuvio.app.features.locallibrary.LocalMatcher
import com.nuvio.app.features.locallibrary.LocalMediaItem
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_local_library_add_catalog
import nuvio.composeapp.generated.resources.settings_local_library_cancel
import nuvio.composeapp.generated.resources.settings_local_library_catalogs_empty
import nuvio.composeapp.generated.resources.settings_local_library_catalogs_title
import nuvio.composeapp.generated.resources.settings_local_library_catalog_search_hint
import nuvio.composeapp.generated.resources.settings_local_library_clear_match
import nuvio.composeapp.generated.resources.settings_local_library_current_match
import nuvio.composeapp.generated.resources.settings_local_library_filter_all
import nuvio.composeapp.generated.resources.settings_local_library_folders_empty
import nuvio.composeapp.generated.resources.settings_local_library_folders_title
import nuvio.composeapp.generated.resources.settings_local_library_playback_title
import nuvio.composeapp.generated.resources.settings_local_library_preferred_play_action
import nuvio.composeapp.generated.resources.settings_local_library_preferred_play_action_description
import nuvio.composeapp.generated.resources.settings_local_library_play_source_picker
import nuvio.composeapp.generated.resources.settings_local_library_play_local_file
import nuvio.composeapp.generated.resources.settings_local_library_intro
import nuvio.composeapp.generated.resources.settings_local_library_items_count
import nuvio.composeapp.generated.resources.settings_local_library_match_dialog_title
import nuvio.composeapp.generated.resources.settings_local_library_mode_advanced
import nuvio.composeapp.generated.resources.settings_local_library_mode_basic
import nuvio.composeapp.generated.resources.settings_local_library_mode_description
import nuvio.composeapp.generated.resources.settings_local_library_mode_title
import nuvio.composeapp.generated.resources.settings_local_library_new_catalog_hint
import nuvio.composeapp.generated.resources.settings_local_library_no_results
import nuvio.composeapp.generated.resources.settings_local_library_not_matched
import nuvio.composeapp.generated.resources.settings_local_library_not_matched_detail
import nuvio.composeapp.generated.resources.settings_local_library_remove
import nuvio.composeapp.generated.resources.settings_local_library_rename
import nuvio.composeapp.generated.resources.settings_local_library_rescan_all
import nuvio.composeapp.generated.resources.settings_local_library_add_folder_to
import nuvio.composeapp.generated.resources.settings_local_library_reset_poster
import nuvio.composeapp.generated.resources.settings_local_library_save
import nuvio.composeapp.generated.resources.settings_local_library_search_hint
import nuvio.composeapp.generated.resources.settings_local_library_search_action
import nuvio.composeapp.generated.resources.settings_local_library_search_kitsu_hint
import nuvio.composeapp.generated.resources.settings_local_library_section_movies
import nuvio.composeapp.generated.resources.settings_local_library_section_anime_movies
import nuvio.composeapp.generated.resources.settings_local_library_section_anime_series
import nuvio.composeapp.generated.resources.settings_local_library_section_tv
import nuvio.composeapp.generated.resources.settings_local_library_type_anime
import nuvio.composeapp.generated.resources.settings_local_library_type_movies
import nuvio.composeapp.generated.resources.settings_local_library_type_tv
import nuvio.composeapp.generated.resources.settings_local_library_toggle_empty_catalogs
import nuvio.composeapp.generated.resources.settings_local_library_unsorted
import nuvio.composeapp.generated.resources.library_add_toast_added
import nuvio.composeapp.generated.resources.library_downloads_add_from_library
import nuvio.composeapp.generated.resources.local_library_fix_action
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// Preset catalog colours (packed ARGB). Kept small and distinct so the poster icon reads at a glance.
private val CATALOG_COLORS: List<Long> = listOf(
    0xFFEF5350, 0xFFAB47BC, 0xFF5C6BC0, 0xFF29B6F6,
    0xFF26A69A, 0xFF9CCC65, 0xFFFFCA28, 0xFFFF7043,
)

// Filter sentinels for the Advanced catalog filter (distinct from any real catalog id).
private const val FILTER_ALL = "*all*"
private const val FILTER_UNSORTED = "*unsorted*"

internal class LocalLibraryTitlesState {
    var filter by mutableStateOf(FILTER_ALL)
    var query by mutableStateOf("")
}

/** Keeps list controls alive while details temporarily replaces the Settings destination. */
internal object LocalLibraryTitlesSessionStore {
    private val statesByProfile = mutableMapOf<Int, LocalLibraryTitlesState>()

    fun stateForProfile(profileId: Int): LocalLibraryTitlesState =
        statesByProfile.getOrPut(profileId) { LocalLibraryTitlesState() }

    fun clear() {
        statesByProfile.clear()
    }
}

@Composable
internal fun rememberLocalLibraryTitlesState(): LocalLibraryTitlesState {
    val profileState by ProfileRepository.state.collectAsState()
    val profileId = profileState.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
    return remember(profileId) { LocalLibraryTitlesSessionStore.stateForProfile(profileId) }
}

internal fun LazyListScope.localLibraryContent(
    isTablet: Boolean,
    state: LocalLibraryUiState,
    titlesState: LocalLibraryTitlesState,
) {
    item {
        Text(
            text = stringResource(Res.string.settings_local_library_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    item { LocalLibraryPlaybackSection(isTablet, state.playbackPreference) }
    item { LocalLibraryFoldersSection(isTablet) }
    item { LocalLibraryCatalogsSection(isTablet, titlesState) }
    localLibraryTitlesContent(
        isTablet = isTablet,
        state = state,
        titlesState = titlesState,
    )
}

@Composable
private fun LocalLibraryPlaybackSection(
    isTablet: Boolean,
    preference: LocalLibraryPlaybackPreference,
) {
    SettingsSection(
        title = stringResource(Res.string.settings_local_library_playback_title),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsDropdownChoiceRow(
                title = stringResource(Res.string.settings_local_library_preferred_play_action),
                description = stringResource(
                    Res.string.settings_local_library_preferred_play_action_description,
                ),
                options = listOf(
                    SettingsChoiceOption(
                        LocalLibraryPlaybackPreference.SOURCE_PICKER,
                        stringResource(Res.string.settings_local_library_play_source_picker),
                    ),
                    SettingsChoiceOption(
                        LocalLibraryPlaybackPreference.LOCAL_LIBRARY,
                        stringResource(Res.string.settings_local_library_play_local_file),
                    ),
                ),
                selectedValue = preference,
                isTablet = isTablet,
                onSelected = LocalLibraryRepository::setPlaybackPreference,
            )
            // Governs how matched local anime is addressed. Shares its value with the copy beside
            // the Continue Watching source — one setting, reachable from either surface it affects.
            AnimeIdPreferenceRow(isTablet = isTablet)
        }
    }
}

@Composable
private fun LocalLibraryFoldersSection(isTablet: Boolean) {
    val state by LocalLibraryRepository.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { LocalLibraryRepository.ensureLoaded() }

    fun addFolder(type: LocalFolderType, isAnime: Boolean) {
        scope.launch {
            LocalDirectoryPicker.pick()?.let { path -> LocalLibraryRepository.addFolder(path, type, isAnime) }
        }
    }

    SettingsSection(
        title = stringResource(Res.string.settings_local_library_folders_title),
        isTablet = isTablet,
        titleTrailing = {
            // Refresh sits right next to the heading (a little padding, not pushed to the far right).
            val canRescan = !state.isScanning && state.folders.isNotEmpty()
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { LocalLibraryRepository.rescan() },
                enabled = canRescan,
                modifier = Modifier.size(32.dp),
            ) {
                if (state.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(Res.string.settings_local_library_rescan_all),
                        modifier = Modifier.size(18.dp),
                        tint = if (canRescan) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) {
        // One tile per folder kind, each with its own "+", and its directories stacked inside it.
        // A kind with no directories keeps its tile (showing an empty note), so the four kinds
        // always read as a stable set rather than appearing and vanishing.
        if (isTablet) {
            // Four across. IntrinsicSize.Min + fillMaxHeight keeps every tile as tall as the
            // fullest one, so the row reads as a single band instead of a ragged edge.
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LOCAL_FOLDER_CATEGORIES.forEach { category ->
                    LocalFolderCategoryTile(
                        category = category,
                        state = state,
                        onAdd = { addFolder(category.type, category.isAnime) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        } else {
            // Compact windows can't hold four columns; the same tiles simply stack.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LOCAL_FOLDER_CATEGORIES.forEach { category ->
                    LocalFolderCategoryTile(
                        category = category,
                        state = state,
                        onAdd = { addFolder(category.type, category.isAnime) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private data class LocalFolderCategory(
    val titleRes: StringResource,
    val type: LocalFolderType,
    val isAnime: Boolean,
)

private val LOCAL_FOLDER_CATEGORIES = listOf(
    LocalFolderCategory(Res.string.settings_local_library_section_movies, LocalFolderType.MOVIES, false),
    LocalFolderCategory(Res.string.settings_local_library_type_tv, LocalFolderType.SERIES, false),
    LocalFolderCategory(Res.string.settings_local_library_section_anime_movies, LocalFolderType.MOVIES, true),
    LocalFolderCategory(Res.string.settings_local_library_section_anime_series, LocalFolderType.SERIES, true),
)

/** One folder kind as a self-contained tile: heading, its own "+", and its directories. */
@Composable
private fun LocalFolderCategoryTile(
    category: LocalFolderCategory,
    state: LocalLibraryUiState,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(category.titleRes)
    val folders = state.folders.filter { it.type == category.type && it.isAnime == category.isAnime }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (category.type == LocalFolderType.SERIES) Icons.Rounded.Tv else Icons.Rounded.Movie,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(Res.string.settings_local_library_add_folder_to, label),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (folders.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_local_library_folders_empty),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp, top = 6.dp),
            )
        } else {
            folders.forEach { folder ->
                LocalFolderTileRow(
                    folder = folder,
                    itemCount = state.items.count { it.folderId == folder.id },
                    onRemove = { LocalLibraryRepository.removeFolder(folder.id) },
                )
            }
        }
    }
}

/** One directory inside its category tile. */
@Composable
private fun LocalFolderTileRow(folder: LocalFolder, itemCount: Int, onRemove: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 6.dp, top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            // Full path, so the drive is always visible — two drives can hold folders with
            // identical names and the tile heading already carries the type.
            text = folder.path,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.settings_local_library_items_count, itemCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            // A bare label rather than a TextButton: the button's own min-width and padding don't
            // fit a quarter-width tile.
            Text(
                text = stringResource(Res.string.settings_local_library_remove),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalLibraryCatalogsSection(isTablet: Boolean, titlesState: LocalLibraryTitlesState) {
    val state by LocalLibraryRepository.uiState.collectAsState()
    var showAddBox by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // Clicking a catalog scopes the titles below to it; clicking the selected one again clears back
    // to showing everything. This replaces the old row of filter chips under the search field.
    fun toggleFilter(id: String) {
        titlesState.filter = if (titlesState.filter == id) FILTER_ALL else id
    }

    SettingsSection(
        title = stringResource(Res.string.settings_local_library_catalogs_title),
        isTablet = isTablet,
        titleTrailing = {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { showAddBox = !showAddBox }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(Res.string.settings_local_library_add_catalog),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            // Empty catalogs (incl. the anime defaults) are shown by default so users know they exist;
            // the eye hides them for anyone who doesn't want them cluttering the list.
            IconButton(
                onClick = { LocalLibraryRepository.setHideEmptyCatalogs(!state.hideEmptyCatalogs) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (state.hideEmptyCatalogs) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(Res.string.settings_local_library_toggle_empty_catalogs),
                    modifier = Modifier.size(18.dp),
                    tint = if (state.hideEmptyCatalogs) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) {
        if (showAddBox) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(Res.string.settings_local_library_new_catalog_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).trackTextInputFocus(),
                )
                OutlinedButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        LocalLibraryRepository.addCatalog(newName)
                        newName = ""
                        showAddBox = false
                        // Release focus so the input tracker drops the shortcut lock.
                        focusManager.clearFocus()
                    },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.settings_local_library_add_catalog))
                }
            }
        }

        // Same tile shape as the folders above, but the catalog count varies, so the tiles flow four
        // to a row and wrap. A null entry is the Unsorted pseudo-catalog (items with no catalog),
        // which leads the grid and follows the same hide-when-empty rule as the real ones.
        val counts = state.sortedCatalogs.associate { it.id to state.itemsInCatalog(it.id).size }
        val unsortedCount = state.itemsInCatalog(null).size
        val entries: List<LocalCatalog?> = buildList {
            if (unsortedCount > 0 || !state.hideEmptyCatalogs) add(null)
            state.sortedCatalogs.forEach { catalog ->
                if (counts[catalog.id] != 0 || !state.hideEmptyCatalogs) add(catalog)
            }
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_local_library_catalogs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val columns = if (isTablet) 4 else 2
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                entries.chunked(columns).forEach { rowEntries ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowEntries.forEach { catalog ->
                            val tileModifier = Modifier.weight(1f).fillMaxHeight()
                            if (catalog == null) {
                                CatalogFilterTile(
                                    name = stringResource(Res.string.settings_local_library_unsorted),
                                    color = null,
                                    itemCount = unsortedCount,
                                    selected = titlesState.filter == FILTER_UNSORTED,
                                    onClick = { toggleFilter(FILTER_UNSORTED) },
                                    modifier = tileModifier,
                                )
                            } else {
                                LocalCatalogTile(
                                    catalog = catalog,
                                    itemCount = counts[catalog.id] ?: 0,
                                    selected = titlesState.filter == catalog.id,
                                    deletable = catalog.defaultBucket == null,
                                    onClick = { toggleFilter(catalog.id) },
                                    isEditing = editingId == catalog.id,
                                    editName = editName,
                                    onEditNameChange = { editName = it },
                                    onStartEdit = { editingId = catalog.id; editName = catalog.name },
                                    onSaveEdit = {
                                        LocalLibraryRepository.renameCatalog(catalog.id, editName)
                                        editingId = null
                                    },
                                    onPickColor = { LocalLibraryRepository.setCatalogColor(catalog.id, it) },
                                    onRemove = {
                                        if (editingId == catalog.id) editingId = null
                                        LocalLibraryRepository.removeCatalog(catalog.id)
                                    },
                                    modifier = tileModifier,
                                )
                            }
                        }
                        // Pad a short final row so its tiles keep a full row's width instead of
                        // stretching to fill the gap.
                        repeat(columns - rowEntries.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * Shared shell for a catalog tile. Selection is carried by the fill and border rather than a
 * checkmark, since the tile's whole job is to scope the titles grid below.
 */
@Composable
private fun CatalogTileFrame(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = shape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        content = content,
    )
}

/** The name/colour line every catalog tile opens with. */
@Composable
private fun CatalogTileTitle(name: String, color: Long?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A lightweight clickable filter tile (used for the Unsorted entry) with a selection highlight. */
@Composable
private fun CatalogFilterTile(
    name: String,
    color: Long?,
    itemCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CatalogTileFrame(selected = selected, onClick = onClick, modifier = modifier) {
        CatalogTileTitle(name = name, color = color, modifier = Modifier.fillMaxWidth().padding(end = 6.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.settings_local_library_items_count, itemCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalCatalogTile(
    catalog: LocalCatalog,
    itemCount: Int,
    selected: Boolean,
    deletable: Boolean,
    onClick: () -> Unit,
    isEditing: Boolean,
    editName: String,
    onEditNameChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onPickColor: (Long?) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CatalogTileFrame(
        selected = selected,
        // Only the non-editing tile toggles the filter; while editing, taps belong to the field.
        onClick = if (isEditing) null else onClick,
        modifier = modifier,
    ) {
        if (isEditing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = onEditNameChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f).trackTextInputFocus(),
                )
                IconButton(
                    onClick = onSaveEdit,
                    enabled = editName.isNotBlank(),
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(Res.string.settings_local_library_save),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(end = 6.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CATALOG_COLORS.forEach { color ->
                    ColorSwatch(color = color, selected = catalog.color == color, onClick = { onPickColor(color) })
                }
            }
        } else {
            CatalogTileTitle(
                name = catalog.name,
                color = catalog.color,
                modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Count and the tile's own actions share the bottom line, so the tile stays two lines tall
        // whether or not a catalog can be renamed or removed.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.settings_local_library_items_count, itemCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (!isEditing) {
                IconButton(onClick = onStartEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = stringResource(Res.string.settings_local_library_rename),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (deletable) {
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.settings_local_library_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

private fun LazyListScope.localLibraryTitlesContent(
    isTablet: Boolean,
    state: LocalLibraryUiState,
    titlesState: LocalLibraryTitlesState,
) {
    if (state.items.isEmpty()) return

    val cardWidth = if (isTablet) 150.dp else 120.dp
    // Keep each poster row as its own outer LazyColumn item. The old FlowRow put the complete
    // library in one item, forcing every card and image to compose at the catalog/title boundary.
    val columns = if (isTablet) 4 else 2

    // The catalog list above is the filter now: FILTER_ALL shows everything, FILTER_UNSORTED the
    // unfiled items, otherwise scope to the selected catalog id.
    val catalogVisible = when (titlesState.filter) {
        FILTER_ALL -> state.items
        FILTER_UNSORTED -> state.items.filter { it.catalogId == null }
        else -> state.items.filter { it.catalogId == titlesState.filter }
    }
    val query = titlesState.query.trim()
    val visible = if (query.isBlank()) {
        catalogVisible
    } else {
        catalogVisible.filter { item ->
            item.title.contains(query, ignoreCase = true) ||
                item.displayYear?.toString()?.contains(query, ignoreCase = true) == true
        }
    }

    item(key = "local-library-search") {
        OutlinedTextField(
            value = titlesState.query,
            onValueChange = { titlesState.query = it },
            placeholder = { Text(stringResource(Res.string.settings_local_library_catalog_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .trackTextInputFocus(),
        )
    }

    if (visible.isEmpty() && query.isNotBlank()) {
        item(key = "local-library-search-empty") {
            Text(
                text = stringResource(Res.string.settings_local_library_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        return
    }

    localTitlesCategory(
        key = "movies",
        titleRes = Res.string.settings_local_library_section_movies,
        items = visible.filter { it.type == LocalFolderType.MOVIES && !it.isAnime }.sortedBy { it.title.lowercase() },
        state = state,
        isTablet = isTablet,
        cardWidth = cardWidth,
        columns = columns,
    )
    localTitlesCategory(
        key = "tv",
        titleRes = Res.string.settings_local_library_section_tv,
        items = visible.filter { it.type == LocalFolderType.SERIES && !it.isAnime }.sortedBy { it.title.lowercase() },
        state = state,
        isTablet = isTablet,
        cardWidth = cardWidth,
        columns = columns,
    )
    localTitlesCategory(
        key = "anime-movies",
        titleRes = Res.string.settings_local_library_section_anime_movies,
        items = visible.filter { it.type == LocalFolderType.MOVIES && it.isAnime }.sortedBy { it.title.lowercase() },
        state = state,
        isTablet = isTablet,
        cardWidth = cardWidth,
        columns = columns,
    )
    localTitlesCategory(
        key = "anime-series",
        titleRes = Res.string.settings_local_library_section_anime_series,
        items = visible.filter { it.type == LocalFolderType.SERIES && it.isAnime }.sortedBy { it.title.lowercase() },
        state = state,
        isTablet = isTablet,
        cardWidth = cardWidth,
        columns = columns,
    )
}

private fun LazyListScope.localTitlesCategory(
    key: String,
    titleRes: org.jetbrains.compose.resources.StringResource,
    items: List<LocalMediaItem>,
    state: LocalLibraryUiState,
    isTablet: Boolean,
    cardWidth: Dp,
    columns: Int,
) {
    if (items.isEmpty()) return
    items.chunked(columns).forEachIndexed { rowIndex, rowItems ->
        item(key = "local-library-$key-${rowItems.joinToString("-") { it.key }}") {
            val cards: @Composable () -> Unit = {
                LocalPosterRow(
                    items = rowItems,
                    state = state,
                    cardWidth = cardWidth,
                    topPadding = if (rowIndex == 0) 8.dp else 0.dp,
                )
            }
            if (rowIndex == 0) {
                SettingsSection(title = stringResource(titleRes), isTablet = isTablet) {
                    cards()
                }
            } else {
                cards()
            }
        }
    }
}

@Composable
private fun LocalPosterRow(
    items: List<LocalMediaItem>,
    state: LocalLibraryUiState,
    cardWidth: Dp,
    topPadding: Dp,
) {
    val pvr by LibraryPvrRepository.uiState.collectAsState()
    LaunchedEffect(Unit) { LibraryPvrRepository.ensureLoaded() }
    val monitoredContentIds = remember(pvr.monitoredItems) {
        pvr.monitoredItems.mapTo(mutableSetOf()) { it.contentId }
    }
    val addedText = stringResource(Res.string.library_add_toast_added)

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = topPadding, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
    ) {
        items.forEach { item ->
            LocalPosterCard(
                item = item,
                state = state,
                width = cardWidth,
                onResetPoster = { LocalLibraryRepository.resetPoster(item) },
                onAssign = { catalogId -> LocalLibraryRepository.assignToCatalog(item.key, catalogId) },
                isMonitored = item.contentId in monitoredContentIds,
                onMonitor = {
                    LibraryPvrRepository.newMonitoredItem(
                        contentId = item.contentId,
                        contentType = item.contentType,
                        title = item.title,
                        targetFolderId = item.folderId,
                        mode = if (item.type == LocalFolderType.SERIES) {
                            MonitorMode.SELECTED_PLUS_FUTURE
                        } else {
                            MonitorMode.MOVIE_WHEN_AVAILABLE
                        },
                        tmdbId = item.tmdbId,
                        imdbId = item.imdbId,
                        kitsuId = item.kitsuId,
                        malId = item.malId,
                        isAnime = item.isAnime,
                        year = item.year,
                        poster = item.poster,
                        background = item.background,
                    )
                    NuvioToastController.show(addedText)
                    LibraryPvrScheduler.checkNow()
                },
            )
        }
    }
}

@Composable
private fun LocalPosterCard(
    item: LocalMediaItem,
    state: LocalLibraryUiState,
    width: Dp,
    onResetPoster: () -> Unit,
    onAssign: (String?) -> Unit,
    isMonitored: Boolean,
    onMonitor: () -> Unit,
) {
    var menuOpen by remember(item.key) { mutableStateOf(false) }
    var fixOpen by remember(item.key) { mutableStateOf(false) }
    var numberingOpen by remember(item.key) { mutableStateOf(false) }
    val folder = remember(item.folderId, state.folders) {
        state.folders.firstOrNull { it.id == item.folderId }
    }
    val catalogColor = item.catalogId
        ?.let { id -> state.catalogs.firstOrNull { it.id == id }?.color }
        ?.let { Color(it) }
    val openDetails = LocalOpenMetaDetails.current
    // An unmatched item has no real content id — only the synthetic "local:" key — so there is no
    // details page to open and the poster stays inert rather than navigating somewhere broken.
    val onPosterClick = openDetails
        ?.takeIf { item.isMatched }
        ?.let { open -> { open(item.contentType, item.contentId) } }

    Column(modifier = Modifier.width(width)) {
        Box(
            modifier = Modifier
                .width(width)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (onPosterClick != null) {
                        Modifier.clickable(onClick = onPosterClick)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (item.poster != null) {
                NuvioAsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (item.type == LocalFolderType.SERIES) Icons.Rounded.Tv else Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PosterIconButton(
                    Icons.Rounded.Edit,
                    stringResource(Res.string.settings_local_library_match_dialog_title, item.title),
                    onClick = { fixOpen = true },
                )
                PosterIconButton(Icons.Rounded.Refresh, stringResource(Res.string.settings_local_library_reset_poster), onClick = onResetPoster)
                run {
                    Box {
                        PosterIconButton(
                            icon = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.settings_local_library_catalogs_title),
                            tint = catalogColor ?: Color.White,
                            onClick = { menuOpen = true },
                        )
                        CatalogAssignMenu(
                            expanded = menuOpen,
                            catalogs = state.sortedCatalogs,
                            selectedCatalogId = item.catalogId,
                            onDismiss = { menuOpen = false },
                            onSelect = { catalogId ->
                                onAssign(catalogId)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            // Only anime series carry Kitsu ids, and only they are numbered entry-relative — a
            // live-action show's SxxExx coordinates are already the ones its meta uses, so there
            // is nothing to realign and the action would be meaningless.
            val fixFolder = folder.takeIf {
                item.type == LocalFolderType.SERIES && item.isAnime && item.kitsuId != null
            }
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (fixFolder != null) {
                    PosterIconButton(
                        icon = Icons.Rounded.FormatListNumbered,
                        contentDescription = stringResource(Res.string.local_library_fix_action),
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { numberingOpen = true },
                    )
                }
                if (
                    AppFeaturePolicy.downloadsEnabled &&
                    item.isMatched &&
                    !isMonitored
                ) {
                    PosterIconButton(
                        icon = Icons.Rounded.VideoLibrary,
                        contentDescription = stringResource(Res.string.library_downloads_add_from_library),
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = onMonitor,
                    )
                }
            }
        }

        Spacer(Modifier.size(6.dp))
        Text(
            text = item.displayYear?.let { "${item.title} ($it)" } ?: item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!item.isMatched) {
            Text(
                text = stringResource(Res.string.settings_local_library_not_matched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (fixOpen) {
        LocalMatchDialog(item = item, onDismiss = { fixOpen = false })
    }
    if (numberingOpen && folder != null) {
        LocalAnimeFixDialog(item = item, onDismiss = { numberingOpen = false })
    }
}

@Composable
private fun CatalogAssignMenu(
    expanded: Boolean,
    catalogs: List<LocalCatalog>,
    selectedCatalogId: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.settings_local_library_unsorted)) },
            leadingIcon = { if (selectedCatalogId == null) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onSelect(null) },
        )
        catalogs.forEach { catalog ->
            DropdownMenuItem(
                text = { Text(catalog.name) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(catalog.color?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                },
                trailingIcon = { if (selectedCatalogId == catalog.id) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { onSelect(catalog.id) },
            )
        }
    }
}

@Composable
private fun PosterIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun LocalMatchDialog(item: LocalMediaItem, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(item.displayYear?.let { "${item.title} $it" } ?: item.title) }
    var results by remember { mutableStateOf<List<LocalMatchCandidate>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var provider by remember(item.key) {
        mutableStateOf(
            when {
                item.kitsuId != null || item.malId != null -> LocalMatchProvider.KITSU
                item.imdbId != null || item.tmdbId != null -> LocalMatchProvider.TMDB
                item.isAnime -> LocalMatchProvider.KITSU
                else -> LocalMatchProvider.TMDB
            },
        )
    }
    val providerOptions = remember {
        listOf(
            SettingsChoiceOption(LocalMatchProvider.KITSU, "Kitsu"),
            SettingsChoiceOption(LocalMatchProvider.TMDB, "TMDB"),
        )
    }
    val searchHint = stringResource(
        if (provider == LocalMatchProvider.KITSU) Res.string.settings_local_library_search_kitsu_hint
        else Res.string.settings_local_library_search_hint,
    )

    fun runSearch() {
        val submitted = query.trim()
        if (submitted.isBlank()) return
        val selectedProvider = provider
        scope.launch {
            isSearching = true
            hasSearched = true
            val resolved = runCatching {
                LocalMatcher.applySearchInput(item, submitted, selectedProvider)
            }.getOrNull()
            if (resolved != null) {
                LocalLibraryRepository.applyManualMatch(item, resolved)
                isSearching = false
                onDismiss()
                return@launch
            }
            results = LocalMatcher.search(submitted, item.type, selectedProvider)
            isSearching = false
        }
    }

    LaunchedEffect(item.key) { runSearch() }

    NuvioAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_local_library_match_dialog_title, item.title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (item.isMatched) {
                            stringResource(Res.string.settings_local_library_current_match, item.contentId)
                        } else {
                            stringResource(Res.string.settings_local_library_not_matched_detail)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isMatched) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    SettingsSegmentedControl(
                        options = providerOptions,
                        selectedValue = provider,
                        enabled = !isSearching,
                        isTablet = true,
                        modifier = Modifier.width(176.dp),
                        onSelected = { selected ->
                            provider = selected
                            results = emptyList()
                            hasSearched = false
                        },
                    )
                }
                // Which file/folder is being matched — the title alone is ambiguous when the same
                // show sits in more than one library folder.
                item.sourceLocation?.let { location ->
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(searchHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
                    trailingIcon = {
                        IconButton(onClick = { runSearch() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = searchHint)
                        }
                    },
                )
                Spacer(Modifier.size(8.dp))
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 240.dp)) {
                    when {
                        isSearching -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        hasSearched && results.isEmpty() -> Text(
                            text = stringResource(Res.string.settings_local_library_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            results.forEach { candidate ->
                                LocalMatchCandidateRow(candidate) {
                                    scope.launch {
                                        val resolved = when {
                                            candidate.kitsuId != null ->
                                                LocalMatcher.applyKitsuId(item, candidate.kitsuId, candidate.poster, LocalMatchState.MANUAL)
                                            candidate.tmdbId != null ->
                                                LocalMatcher.applyTmdbId(item, candidate.tmdbId, LocalMatchState.MANUAL)
                                                    .copy(poster = candidate.poster ?: item.poster)
                                            else -> item
                                        }
                                        LocalLibraryRepository.applyManualMatch(item, resolved)
                                        onDismiss()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = query.isNotBlank() && !isSearching,
                onClick = { runSearch() },
            ) { Text(stringResource(Res.string.settings_local_library_search_action)) }
        },
        dismissButton = {
            Row {
                if (item.isMatched) {
                    TextButton(onClick = {
                        LocalLibraryRepository.clearMatch(item)
                        onDismiss()
                    }) { Text(stringResource(Res.string.settings_local_library_clear_match)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.settings_local_library_cancel)) }
            }
        },
    )
}

@Composable
private fun LocalMatchCandidateRow(candidate: LocalMatchCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(width = 34.dp, height = 50.dp).clip(RoundedCornerShape(4.dp))) {
            if (candidate.poster != null) {
                NuvioAsyncImage(
                    model = candidate.poster,
                    contentDescription = candidate.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.year?.let { "${candidate.title} ($it)" } ?: candidate.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            candidate.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
