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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalOpenMetaDetails
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioToastController
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
import nuvio.composeapp.generated.resources.settings_local_library_browse_open
import nuvio.composeapp.generated.resources.settings_local_library_browse_open_empty
import nuvio.composeapp.generated.resources.settings_local_library_browse_section
import nuvio.composeapp.generated.resources.settings_local_library_browse_unmatched
import nuvio.composeapp.generated.resources.settings_local_library_cancel
import nuvio.composeapp.generated.resources.settings_local_library_catalogs_empty
import nuvio.composeapp.generated.resources.settings_local_library_catalogs_title
import nuvio.composeapp.generated.resources.settings_local_library_clear_match
import nuvio.composeapp.generated.resources.settings_local_library_current_match
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
import nuvio.composeapp.generated.resources.settings_local_library_new_catalog_hint
import nuvio.composeapp.generated.resources.settings_local_library_no_results
import nuvio.composeapp.generated.resources.settings_local_library_not_matched
import nuvio.composeapp.generated.resources.settings_local_library_not_matched_detail
import nuvio.composeapp.generated.resources.settings_local_library_remove
import nuvio.composeapp.generated.resources.settings_local_library_rename
import nuvio.composeapp.generated.resources.settings_local_library_rescan_all
import nuvio.composeapp.generated.resources.settings_local_library_add_folder
import nuvio.composeapp.generated.resources.settings_local_library_folder_count
import nuvio.composeapp.generated.resources.settings_local_library_folder_count_one
import nuvio.composeapp.generated.resources.settings_local_library_category_empty
import nuvio.composeapp.generated.resources.settings_local_library_section_tv
import nuvio.composeapp.generated.resources.settings_local_library_reset_poster
import nuvio.composeapp.generated.resources.settings_local_library_save
import nuvio.composeapp.generated.resources.settings_local_library_search_hint
import nuvio.composeapp.generated.resources.settings_local_library_search_action
import nuvio.composeapp.generated.resources.settings_local_library_search_kitsu_hint
import nuvio.composeapp.generated.resources.settings_local_library_section_movies
import nuvio.composeapp.generated.resources.settings_local_library_section_anime_movies
import nuvio.composeapp.generated.resources.settings_local_library_section_anime_series
import nuvio.composeapp.generated.resources.settings_local_library_type_anime
import nuvio.composeapp.generated.resources.settings_local_library_type_movies
import nuvio.composeapp.generated.resources.settings_local_library_toggle_empty_catalogs
import nuvio.composeapp.generated.resources.settings_local_library_unsorted
import nuvio.composeapp.generated.resources.library_downloads_add_from_library
import nuvio.composeapp.generated.resources.local_library_fix_action
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import androidx.compose.ui.draw.alpha
import com.nuvio.app.core.ui.NuvioFieldIconButton
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff

// Preset catalog colours (packed ARGB). Kept small and distinct so the poster icon reads at a glance.
private val CATALOG_COLORS: List<Long> = listOf(
    0xFFEF5350, 0xFFAB47BC, 0xFF5C6BC0, 0xFF29B6F6,
    0xFF26A69A, 0xFF9CCC65, 0xFFFFCA28, 0xFFFF7043,
)

// Filter sentinels for the catalog tabs (distinct from any real catalog id).
internal const val FILTER_ALL = "*all*"
internal const val FILTER_UNSORTED = "*unsorted*"

internal class LocalLibraryTitlesState {
    var filter by mutableStateOf(FILTER_ALL)
    var query by mutableStateOf("")

    /** Whether the full-screen library browser is up. The settings page only links to it. */
    var browserOpen by mutableStateOf(false)

    /**
     * Open the browser scoped to [scope]. The search box is cleared on the way in: this state
     * outlives the browser (it is held per profile), and reopening onto a filter left over from
     * last time reads as an empty library.
     */
    fun openBrowser(scope: String) {
        filter = scope
        query = ""
        browserOpen = true
    }
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
    item { LocalLibraryBrowseSection(isTablet, state, titlesState) }
    item { LocalLibraryPlaybackSection(isTablet, state.playbackPreference) }
    item { LocalLibraryFoldersSection(isTablet) }
    item { LocalLibraryCatalogsSection(isTablet, titlesState) }
}

/**
 * The way into the library itself. The titles used to be laid out inline below these settings,
 * which capped them at the settings column's width; they now live in a full-screen browser so a
 * row fits far more posters, and this section is the link to it.
 */
@Composable
private fun LocalLibraryBrowseSection(
    isTablet: Boolean,
    state: LocalLibraryUiState,
    titlesState: LocalLibraryTitlesState,
) {
    SettingsSection(
        title = stringResource(Res.string.settings_local_library_browse_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_local_library_browse_open),
                description = if (state.items.isEmpty()) {
                    stringResource(Res.string.settings_local_library_browse_open_empty)
                } else {
                    val titles = stringResource(Res.string.settings_local_library_items_count, state.items.size)
                    val unmatched = state.unmatchedCount
                    if (unmatched > 0) {
                        titles + " · " + stringResource(
                            Res.string.settings_local_library_browse_unmatched,
                            unmatched,
                        )
                    } else {
                        titles
                    }
                },
                enabled = state.items.isNotEmpty(),
                isTablet = isTablet,
                onClick = { titlesState.openBrowser(FILTER_ALL) },
            )
        }
    }
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
        actions = {
            // Refresh is the section-level action, on the far right of the heading like every
            // other page's heading action.
            val canRescan = !state.isScanning && state.folders.isNotEmpty()
            if (state.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                NuvioActionLabel(
                    text = stringResource(Res.string.settings_local_library_rescan_all),
                    onClick = if (canRescan) ({ LocalLibraryRepository.rescan() }) else null,
                    modifier = Modifier.alpha(if (canRescan) 1f else MaterialTheme.nuvio.opacity.medium),
                )
            }
        },
    ) {
        // One list, grouped by folder kind: a heading row per kind with its own "Add folder",
        // then that kind's directories beneath it. A kind with no directories keeps its heading
        // (with an empty note), so the four kinds always read as a stable set.
        SettingsGroup(isTablet = isTablet) {
            LOCAL_FOLDER_CATEGORIES.forEachIndexed { index, category ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                val folders = state.folders.filter { it.type == category.type && it.isAnime == category.isAnime }
                LocalFolderCategoryHeaderRow(
                    category = category,
                    folderCount = folders.size,
                    onAdd = { addFolder(category.type, category.isAnime) },
                )
                if (folders.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.settings_local_library_category_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.nuvio.colors.textMuted,
                        modifier = Modifier.padding(start = LocalFolderTreeInset + LocalFolderTreeTick + 10.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                    )
                } else {
                    LocalFolderTree {
                        folders.forEachIndexed { folderIndex, folder ->
                            LocalFolderRow(
                                folder = folder,
                                itemCount = state.items.count { it.folderId == folder.id },
                                last = folderIndex == folders.lastIndex,
                                onRemove = { LocalLibraryRepository.removeFolder(folder.id) },
                            )
                        }
                    }
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
    LocalFolderCategory(Res.string.settings_local_library_section_tv, LocalFolderType.SERIES, false),
    LocalFolderCategory(Res.string.settings_local_library_section_anime_movies, LocalFolderType.MOVIES, true),
    LocalFolderCategory(Res.string.settings_local_library_section_anime_series, LocalFolderType.SERIES, true),
)

/** Where the tree guide hangs, under the first letter of the category name. */
private val LocalFolderTreeInset = 22.dp

/** Length of the "|-" tick from the guide line to each folder's text. */
private val LocalFolderTreeTick = 14.dp

/** The heading row of one folder kind: name, how many directories, and its "Add folder". */
@Composable
private fun LocalFolderCategoryHeaderRow(
    category: LocalFolderCategory,
    folderCount: Int,
    onAdd: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(category.titleRes),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (folderCount == 1) {
                stringResource(Res.string.settings_local_library_folder_count_one)
            } else {
                stringResource(Res.string.settings_local_library_folder_count, folderCount)
            },
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        NuvioActionLabel(
            text = stringResource(Res.string.settings_local_library_add_folder),
            onClick = onAdd,
        )
    }
}

/**
 * The directories of one kind as a tree under its heading: a hairline guide runs down the left
 * and each row carries a short tick from it, the way the sidebar draws its section sub-list.
 * The guide stops at the last row's tick rather than running on to the divider.
 */
@Composable
private fun LocalFolderTree(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LocalFolderTreeInset, bottom = 6.dp),
        content = content,
    )
}

/** One directory: `path · N titles` on a single line behind its tree tick, and Remove. */
@Composable
private fun LocalFolderRow(folder: LocalFolder, itemCount: Int, last: Boolean, onRemove: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val guideColor = tokens.colors.borderDefault
    val countText = stringResource(Res.string.settings_local_library_items_count, itemCount)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // The vertical guide runs the full row height on every row but the last, where it
                // stops at the tick so the tree closes with an "L" instead of a dangling line.
                val stroke = tokens.borders.hairline.toPx()
                val x = stroke / 2f
                val tickY = size.height / 2f
                drawLine(guideColor, Offset(x, 0f), Offset(x, if (last) tickY else size.height), stroke)
                drawLine(guideColor, Offset(x, tickY), Offset(LocalFolderTreeTick.toPx(), tickY), stroke)
            }
            .padding(start = LocalFolderTreeTick + 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                // Full path, so the drive is always visible — two drives can hold folders with
                // identical names and the heading already carries the type.
                append(folder.path)
                withStyle(SpanStyle(color = tokens.colors.textMuted)) {
                    append("  \u00B7  ")
                    append(countText)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.settings_local_library_remove),
                tint = tokens.colors.danger,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LocalLibraryCatalogsSection(isTablet: Boolean, titlesState: LocalLibraryTitlesState) {
    val state by LocalLibraryRepository.uiState.collectAsState()
    var showAddBox by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val tokens = MaterialTheme.nuvio

    // Clicking a catalog opens the library browser already scoped to it — the row is the shortcut
    // to that catalog's shelf, and the browser's own tabs take over from there.
    fun openBrowser(id: String) = titlesState.openBrowser(id)

    SettingsSection(
        title = stringResource(Res.string.settings_local_library_catalogs_title),
        isTablet = isTablet,
        actions = {
            // Empty catalogs (incl. the anime defaults) are shown by default so users know they
            // exist; the eye hides them for anyone who doesn't want them cluttering the list.
            IconButton(
                onClick = { LocalLibraryRepository.setHideEmptyCatalogs(!state.hideEmptyCatalogs) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (state.hideEmptyCatalogs) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(Res.string.settings_local_library_toggle_empty_catalogs),
                    modifier = Modifier.size(18.dp),
                    tint = tokens.colors.textMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            NuvioActionLabel(
                text = stringResource(Res.string.settings_local_library_add_catalog),
                onClick = { showAddBox = !showAddBox },
            )
        },
    ) {
        // A null entry is the Unsorted pseudo-catalog (items with no catalog), which leads the
        // list and follows the same hide-when-empty rule as the real ones.
        val counts = state.sortedCatalogs.associate { it.id to state.itemsInCatalog(it.id).size }
        val unsortedCount = state.itemsInCatalog(null).size
        val entries: List<LocalCatalog?> = buildList {
            if (unsortedCount > 0 || !state.hideEmptyCatalogs) add(null)
            state.sortedCatalogs.forEach { catalog ->
                if (counts[catalog.id] != 0 || !state.hideEmptyCatalogs) add(catalog)
            }
        }
        SettingsGroup(isTablet = isTablet) {
            if (showAddBox) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NuvioTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(Res.string.settings_local_library_new_catalog_hint),
                    )
                    NuvioActionLabel(
                        text = stringResource(Res.string.settings_local_library_add_catalog),
                        onClick = if (newName.isBlank()) {
                            null
                        } else {
                            {
                                LocalLibraryRepository.addCatalog(newName)
                                newName = ""
                                showAddBox = false
                                // Release focus so the input tracker drops the shortcut lock.
                                focusManager.clearFocus()
                            }
                        },
                        modifier = Modifier.alpha(if (newName.isBlank()) tokens.opacity.medium else 1f),
                    )
                }
                if (entries.isNotEmpty()) SettingsGroupDivider(isTablet = isTablet)
            }
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_local_library_catalogs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            entries.forEachIndexed { index, catalog ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                if (catalog == null) {
                    LocalCatalogRow(
                        name = stringResource(Res.string.settings_local_library_unsorted),
                        color = null,
                        itemCount = unsortedCount,
                        onClick = { openBrowser(FILTER_UNSORTED) },
                    )
                } else if (editingId == catalog.id) {
                    LocalCatalogEditRow(
                        catalog = catalog,
                        editName = editName,
                        onEditNameChange = { editName = it },
                        onSave = {
                            LocalLibraryRepository.renameCatalog(catalog.id, editName)
                            editingId = null
                            focusManager.clearFocus()
                        },
                        onCancel = {
                            editingId = null
                            focusManager.clearFocus()
                        },
                        onPickColor = { LocalLibraryRepository.setCatalogColor(catalog.id, it) },
                    )
                } else {
                    LocalCatalogRow(
                        name = catalog.name,
                        color = catalog.color,
                        itemCount = counts[catalog.id] ?: 0,
                        onClick = { openBrowser(catalog.id) },
                        onStartEdit = { editingId = catalog.id; editName = catalog.name },
                        onRemove = if (catalog.defaultBucket == null) {
                            { LocalLibraryRepository.removeCatalog(catalog.id) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** A colour dot, the catalog name, its title count, and (for a real catalog) rename / remove. */
@Composable
private fun LocalCatalogRow(
    name: String,
    color: Long?,
    itemCount: Int,
    onClick: () -> Unit,
    onStartEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color?.let { Color(it) } ?: tokens.colors.textMuted),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.settings_local_library_items_count, itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
        }
        // The pen takes the outer slot because every real catalog has one; Remove, which only
        // the user-made catalogs offer, sits inside it so the column of pens stays flush at the
        // edge whether or not a row can be deleted. Unsorted has neither and keeps the pen's slot.
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.settings_local_library_remove),
                    tint = tokens.colors.danger,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onStartEdit != null) {
            IconButton(onClick = onStartEdit) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = stringResource(Res.string.settings_local_library_rename),
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

/** The same row while renaming: the field replaces the name and the colour swatches sit under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalCatalogEditRow(
    catalog: LocalCatalog,
    editName: String,
    onEditNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onPickColor: (Long?) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(catalog.color?.let { Color(it) } ?: tokens.colors.textMuted),
            )
            Spacer(Modifier.width(12.dp))
            NuvioTextField(
                value = editName,
                onValueChange = onEditNameChange,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSave, enabled = editName.isNotBlank()) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = stringResource(Res.string.settings_local_library_save),
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.settings_local_library_cancel),
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CATALOG_COLORS.forEach { color ->
                ColorSwatch(color = color, selected = catalog.color == color, onClick = { onPickColor(color) })
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

/**
 * Queue a local title for the auto-downloader. Lives outside the card so the settings tiles and
 * the library browser both add a title the same way.
 */
internal fun monitorLocalItem(item: LocalMediaItem, addedText: String) {
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
}

@Composable
internal fun LocalPosterCard(
    item: LocalMediaItem,
    state: LocalLibraryUiState,
    modifier: Modifier = Modifier,
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

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
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
                NuvioTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = searchHint,
                    onImeAction = { runSearch() },
                    trailingContent = {
                        NuvioFieldIconButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = searchHint,
                            onClick = { runSearch() },
                        )
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
