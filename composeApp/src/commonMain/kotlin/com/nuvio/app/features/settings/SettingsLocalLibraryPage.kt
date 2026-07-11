package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
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
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.features.locallibrary.LocalCatalog
import com.nuvio.app.features.locallibrary.LocalDirectoryPicker
import com.nuvio.app.features.locallibrary.LocalFolder
import com.nuvio.app.features.locallibrary.LocalFolderType
import com.nuvio.app.features.locallibrary.LocalLibraryMode
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.locallibrary.LocalLibraryUiState
import com.nuvio.app.features.locallibrary.LocalMatchCandidate
import com.nuvio.app.features.locallibrary.LocalMatchState
import com.nuvio.app.features.locallibrary.LocalMatcher
import com.nuvio.app.features.locallibrary.LocalMediaItem
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_local_library_add_catalog
import nuvio.composeapp.generated.resources.settings_local_library_add_movie_folder
import nuvio.composeapp.generated.resources.settings_local_library_add_tv_folder
import nuvio.composeapp.generated.resources.settings_local_library_apply
import nuvio.composeapp.generated.resources.settings_local_library_cancel
import nuvio.composeapp.generated.resources.settings_local_library_catalogs_empty
import nuvio.composeapp.generated.resources.settings_local_library_catalogs_title
import nuvio.composeapp.generated.resources.settings_local_library_clear_match
import nuvio.composeapp.generated.resources.settings_local_library_current_match
import nuvio.composeapp.generated.resources.settings_local_library_empty
import nuvio.composeapp.generated.resources.settings_local_library_filter_all
import nuvio.composeapp.generated.resources.settings_local_library_folders_title
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
import nuvio.composeapp.generated.resources.settings_local_library_paste_id_hint
import nuvio.composeapp.generated.resources.settings_local_library_remove
import nuvio.composeapp.generated.resources.settings_local_library_rename
import nuvio.composeapp.generated.resources.settings_local_library_rescan
import nuvio.composeapp.generated.resources.settings_local_library_reset_poster
import nuvio.composeapp.generated.resources.settings_local_library_save
import nuvio.composeapp.generated.resources.settings_local_library_search_hint
import nuvio.composeapp.generated.resources.settings_local_library_section_movies
import nuvio.composeapp.generated.resources.settings_local_library_section_tv
import nuvio.composeapp.generated.resources.settings_local_library_type_movies
import nuvio.composeapp.generated.resources.settings_local_library_type_tv
import nuvio.composeapp.generated.resources.settings_local_library_unsorted
import org.jetbrains.compose.resources.stringResource

// Preset catalog colours (packed ARGB). Kept small and distinct so the poster icon reads at a glance.
private val CATALOG_COLORS: List<Long> = listOf(
    0xFFEF5350, 0xFFAB47BC, 0xFF5C6BC0, 0xFF29B6F6,
    0xFF26A69A, 0xFF9CCC65, 0xFFFFCA28, 0xFFFF7043,
)

// Filter sentinels for the Advanced catalog filter (distinct from any real catalog id).
private const val FILTER_ALL = "*all*"
private const val FILTER_UNSORTED = "*unsorted*"

internal fun LazyListScope.localLibraryContent(isTablet: Boolean) {
    item {
        Text(
            text = stringResource(Res.string.settings_local_library_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    item { LocalLibraryModeSection(isTablet) }
    item { LocalLibraryFoldersSection(isTablet) }
    item { LocalLibraryCatalogsSection(isTablet) }
    item { LocalLibraryTitlesSection(isTablet) }
}

@Composable
private fun LocalLibraryModeSection(isTablet: Boolean) {
    val state by LocalLibraryRepository.uiState.collectAsState()
    SettingsGroup(isTablet = isTablet) {
        SettingsSegmentedChoiceRow(
            title = stringResource(Res.string.settings_local_library_mode_title),
            description = stringResource(Res.string.settings_local_library_mode_description),
            options = listOf(
                SettingsChoiceOption(LocalLibraryMode.BASIC, stringResource(Res.string.settings_local_library_mode_basic)),
                SettingsChoiceOption(LocalLibraryMode.ADVANCED, stringResource(Res.string.settings_local_library_mode_advanced)),
            ),
            selectedValue = state.mode,
            isTablet = isTablet,
            onSelected = { LocalLibraryRepository.setMode(it) },
        )
    }
}

@Composable
private fun LocalLibraryFoldersSection(isTablet: Boolean) {
    val state by LocalLibraryRepository.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { LocalLibraryRepository.ensureLoaded() }

    fun addFolder(type: LocalFolderType) {
        scope.launch {
            LocalDirectoryPicker.pick()?.let { path -> LocalLibraryRepository.addFolder(path, type) }
        }
    }

    SettingsSection(
        title = stringResource(Res.string.settings_local_library_folders_title),
        isTablet = isTablet,
        actions = {
            if (state.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        },
    ) {
        SettingsGroup(isTablet = isTablet) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = { addFolder(LocalFolderType.MOVIES) }) {
                    Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.settings_local_library_add_movie_folder))
                }
                OutlinedButton(onClick = { addFolder(LocalFolderType.SERIES) }) {
                    Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.settings_local_library_add_tv_folder))
                }
            }

            if (state.folders.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_local_library_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                state.folders.forEach { folder ->
                    LocalFolderRow(
                        folder = folder,
                        itemCount = state.items.count { it.folderId == folder.id },
                        onRemove = { LocalLibraryRepository.removeFolder(folder.id) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { LocalLibraryRepository.rescan() }, enabled = !state.isScanning) {
                        Text(stringResource(Res.string.settings_local_library_rescan))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalFolderRow(folder: LocalFolder, itemCount: Int, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (folder.type == LocalFolderType.SERIES) Icons.Rounded.Tv else Icons.Rounded.Movie,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folder.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(
                if (folder.type == LocalFolderType.SERIES) Res.string.settings_local_library_type_tv
                else Res.string.settings_local_library_type_movies,
            ) + " · " + stringResource(Res.string.settings_local_library_items_count, itemCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRemove) {
            Text(stringResource(Res.string.settings_local_library_remove))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalLibraryCatalogsSection(isTablet: Boolean) {
    val state by LocalLibraryRepository.uiState.collectAsState()
    if (state.mode != LocalLibraryMode.ADVANCED) return
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    SettingsSection(title = stringResource(Res.string.settings_local_library_catalogs_title), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(Res.string.settings_local_library_new_catalog_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).trackSettingsTextFocus(),
                )
                OutlinedButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        LocalLibraryRepository.addCatalog(newName)
                        newName = ""
                        // Release focus so the input tracker drops the shortcut lock (the button
                        // disables itself here, which otherwise leaves focus on the field).
                        focusManager.clearFocus()
                    },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.settings_local_library_add_catalog))
                }
            }
            if (state.catalogs.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_local_library_catalogs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                state.sortedCatalogs.forEach { catalog ->
                    LocalCatalogRow(
                        catalog = catalog,
                        itemCount = state.itemsInCatalog(catalog.id).size,
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
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalCatalogRow(
    catalog: LocalCatalog,
    itemCount: Int,
    isEditing: Boolean,
    editName: String,
    onEditNameChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onPickColor: (Long?) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(catalog.color?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            if (isEditing) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = onEditNameChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f).trackSettingsTextFocus(),
                )
                IconButton(onClick = onSaveEdit, enabled = editName.isNotBlank()) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(Res.string.settings_local_library_save),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Text(
                    text = catalog.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.settings_local_library_items_count, itemCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onStartEdit) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = stringResource(Res.string.settings_local_library_rename),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.settings_local_library_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (isEditing) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 26.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CATALOG_COLORS.forEach { color ->
                    ColorSwatch(color = color, selected = catalog.color == color, onClick = { onPickColor(color) })
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalLibraryTitlesSection(isTablet: Boolean) {
    val state by LocalLibraryRepository.uiState.collectAsState()
    if (state.items.isEmpty()) return

    var fixItem by remember { mutableStateOf<LocalMediaItem?>(null) }
    var filter by remember { mutableStateOf(FILTER_ALL) }
    val advanced = state.mode == LocalLibraryMode.ADVANCED
    val cardWidth = if (isTablet) 150.dp else 120.dp

    val visible = when {
        !advanced || filter == FILTER_ALL -> state.items
        filter == FILTER_UNSORTED -> state.items.filter { it.catalogId == null }
        else -> state.items.filter { it.catalogId == filter }
    }

    Column {
        if (advanced && state.catalogs.isNotEmpty()) {
            CatalogFilterRow(state = state, selected = filter, onSelect = { filter = it })
        }
        LocalTitlesGrid(
            titleRes = Res.string.settings_local_library_section_movies,
            items = visible.filter { it.type == LocalFolderType.MOVIES }.sortedBy { it.title.lowercase() },
            state = state,
            isTablet = isTablet,
            cardWidth = cardWidth,
            onFix = { fixItem = it },
        )
        LocalTitlesGrid(
            titleRes = Res.string.settings_local_library_section_tv,
            items = visible.filter { it.type == LocalFolderType.SERIES }.sortedBy { it.title.lowercase() },
            state = state,
            isTablet = isTablet,
            cardWidth = cardWidth,
            onFix = { fixItem = it },
        )
    }

    fixItem?.let { item ->
        LocalMatchDialog(item = item, onDismiss = { fixItem = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogFilterRow(state: LocalLibraryUiState, selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == FILTER_ALL,
            onClick = { onSelect(FILTER_ALL) },
            label = { Text(stringResource(Res.string.settings_local_library_filter_all)) },
        )
        FilterChip(
            selected = selected == FILTER_UNSORTED,
            onClick = { onSelect(FILTER_UNSORTED) },
            label = { Text(stringResource(Res.string.settings_local_library_unsorted)) },
        )
        state.sortedCatalogs.forEach { catalog ->
            FilterChip(
                selected = selected == catalog.id,
                onClick = { onSelect(catalog.id) },
                label = { Text(catalog.name) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(catalog.color?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalTitlesGrid(
    titleRes: org.jetbrains.compose.resources.StringResource,
    items: List<LocalMediaItem>,
    state: LocalLibraryUiState,
    isTablet: Boolean,
    cardWidth: Dp,
    onFix: (LocalMediaItem) -> Unit,
) {
    if (items.isEmpty()) return
    SettingsSection(title = stringResource(titleRes), isTablet = isTablet) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items.forEach { item ->
                LocalPosterCard(
                    item = item,
                    state = state,
                    width = cardWidth,
                    onFix = { onFix(item) },
                    onResetPoster = { LocalLibraryRepository.resetPoster(item) },
                    onAssign = { catalogId -> LocalLibraryRepository.assignToCatalog(item.key, catalogId) },
                )
            }
        }
    }
}

@Composable
private fun LocalPosterCard(
    item: LocalMediaItem,
    state: LocalLibraryUiState,
    width: Dp,
    onFix: () -> Unit,
    onResetPoster: () -> Unit,
    onAssign: (String?) -> Unit,
) {
    var menuOpen by remember(item.key) { mutableStateOf(false) }
    val catalogColor = item.catalogId
        ?.let { id -> state.catalogs.firstOrNull { it.id == id }?.color }
        ?.let { Color(it) }

    Column(modifier = Modifier.width(width)) {
        Box(
            modifier = Modifier
                .width(width)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
                PosterIconButton(Icons.Rounded.Edit, stringResource(Res.string.settings_local_library_match_dialog_title, item.title), onClick = onFix)
                PosterIconButton(Icons.Rounded.Refresh, stringResource(Res.string.settings_local_library_reset_poster), onClick = onResetPoster)
                if (state.mode == LocalLibraryMode.ADVANCED) {
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
    var pastedId by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LocalMatchCandidate>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    fun runSearch() {
        scope.launch {
            isSearching = true
            hasSearched = true
            results = LocalMatcher.search(query, item.type)
            isSearching = false
        }
    }

    LaunchedEffect(item.key) { runSearch() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_local_library_match_dialog_title, item.title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                Text(
                    text = if (item.isMatched) {
                        stringResource(Res.string.settings_local_library_current_match, item.contentId)
                    } else {
                        stringResource(Res.string.settings_local_library_not_matched)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isMatched) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(Res.string.settings_local_library_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().trackSettingsTextFocus(),
                    trailingIcon = {
                        IconButton(onClick = { runSearch() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(Res.string.settings_local_library_search_hint))
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
                                        val resolved = LocalMatcher.applyTmdbId(item, candidate.tmdbId, LocalMatchState.MANUAL)
                                            .copy(poster = candidate.poster ?: item.poster)
                                        LocalLibraryRepository.applyManualMatch(item, resolved)
                                        onDismiss()
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = pastedId,
                    onValueChange = { pastedId = it },
                    label = { Text(stringResource(Res.string.settings_local_library_paste_id_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().trackSettingsTextFocus(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pastedId.isNotBlank(),
                onClick = {
                    scope.launch {
                        val resolved = LocalMatcher.applyPastedId(item, pastedId)
                        if (resolved != null) LocalLibraryRepository.applyManualMatch(item, resolved)
                        onDismiss()
                    }
                },
            ) { Text(stringResource(Res.string.settings_local_library_apply)) }
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
