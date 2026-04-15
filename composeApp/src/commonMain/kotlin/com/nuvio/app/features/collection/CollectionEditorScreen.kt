package com.nuvio.app.features.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateDpAsState
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvioPlatformExtraBottomPadding
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.features.home.PosterShape
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CollectionEditorScreen(
    collectionId: String?,
    onBack: () -> Unit,
) {
    val state by CollectionEditorRepository.uiState.collectAsState()
    val bottomInset = nuvioPlatformExtraBottomPadding

    LaunchedEffect(collectionId) {
        CollectionEditorRepository.initialize(collectionId)
    }

    val editingFolder = state.editingFolder
    if (state.showFolderEditor && editingFolder != null) {
        val genrePickerIndex = state.genrePickerSourceIndex
        val genrePickerSource = genrePickerIndex?.let { editingFolder.catalogSources.getOrNull(it) }
        val genrePickerCatalog = genrePickerSource?.let { source ->
            state.availableCatalogs.find {
                it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId
            }
        }

        FolderEditorPage(
            state = state,
            onBack = { CollectionEditorRepository.cancelFolderEdit() },
        )

        if (state.showCatalogPicker) {
            CatalogPickerSheet(
                availableCatalogs = state.availableCatalogs,
                selectedSources = editingFolder.catalogSources,
                onToggle = { CollectionEditorRepository.toggleCatalogSource(it) },
                onDismiss = { CollectionEditorRepository.hideCatalogPicker() },
            )
        }

        if (
            genrePickerIndex != null &&
            genrePickerSource != null &&
            genrePickerCatalog != null &&
            genrePickerCatalog.genreOptions.isNotEmpty()
        ) {
            GenrePickerSheet(
                title = genrePickerCatalog.catalogName,
                selectedGenre = genrePickerSource.genre,
                genreOptions = genrePickerCatalog.genreOptions,
                allowAll = !genrePickerCatalog.genreRequired,
                onSelect = {
                    CollectionEditorRepository.updateCatalogSourceGenre(genrePickerIndex, it)
                    CollectionEditorRepository.hideGenrePicker()
                },
                onDismiss = { CollectionEditorRepository.hideGenrePicker() },
            )
        }
        return
    }

    if (state.showCatalogPicker) {
        CatalogPickerSheet(
            availableCatalogs = state.availableCatalogs,
            selectedSources = state.editingFolder?.catalogSources.orEmpty(),
            onToggle = { CollectionEditorRepository.toggleCatalogSource(it) },
            onDismiss = { CollectionEditorRepository.hideCatalogPicker() },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NuvioScreen(
            modifier = Modifier.fillMaxSize(),
        ) {
            stickyHeader {
                NuvioScreenHeader(
                    title = if (state.isNew) "New Collection" else "Edit Collection",
                    onBack = onBack,
                )
            }

            item {
                NuvioInputField(
                    value = state.title,
                    onValueChange = { CollectionEditorRepository.setTitle(it) },
                    placeholder = "Collection Title",
                )
            }

            item {
                NuvioInputField(
                    value = state.backdropImageUrl,
                    onValueChange = { CollectionEditorRepository.setBackdropImageUrl(it) },
                    placeholder = "Backdrop Image URL (optional)",
                )
            }

            item {
                NuvioSurfaceCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { CollectionEditorRepository.setPinToTop(!state.pinToTop) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Pin to Top",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Display this collection above regular catalog rows.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.pinToTop,
                            onCheckedChange = { CollectionEditorRepository.setPinToTop(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                        )
                    }
                }
            }



            // View Mode
        item {
                NuvioSurfaceCard {
                    Text(
                        text = "View Mode",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FolderViewMode.entries
                            .filter { it != FolderViewMode.FOLLOW_LAYOUT }
                            .forEach { mode ->
                            FilterChip(
                                selected = state.viewMode == mode,
                                onClick = { CollectionEditorRepository.setViewMode(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            FolderViewMode.TABBED_GRID -> "Tabbed Grid"
                                            FolderViewMode.ROWS -> "Rows"
                                            FolderViewMode.FOLLOW_LAYOUT -> "Rows"
                                        }
                                    )
                                },
                                leadingIcon = if (state.viewMode == mode) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }

            // Show All Tab
        item {
                NuvioSurfaceCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { CollectionEditorRepository.setShowAllTab(!state.showAllTab) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Show \"All\" Tab",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Combine all folder catalogs into a single tab.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.showAllTab,
                            onCheckedChange = { CollectionEditorRepository.setShowAllTab(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                        )
                    }
                }
            }

            // Folders Section Header
        item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioSectionLabel(text = "FOLDERS")
                    TextButton(onClick = { CollectionEditorRepository.addFolder() }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Folder")
                    }
                }
            }

            // Folder Items
        if (state.folders.isNotEmpty()) {
            item {
                FolderReorderableList(
                    folders = state.folders,
                    onEdit = { CollectionEditorRepository.editFolder(it) },
                    onDelete = { CollectionEditorRepository.removeFolder(it) },
                )
            }
        }

        if (state.folders.isEmpty()) {
            item {
                NuvioSurfaceCard(
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        text = "No folders yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Add one to get started.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

            item {
                Spacer(modifier = Modifier.height(96.dp + bottomInset))
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = bottomInset),
            ) {
                NuvioPrimaryButton(
                    text = if (state.isNew) "Create Collection" else "Save Changes",
                    enabled = state.title.isNotBlank(),
                    onClick = {
                        if (CollectionEditorRepository.save()) {
                            onBack()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FolderReorderableList(
    folders: List<CollectionFolder>,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
    ) { from, to ->
        CollectionEditorRepository.moveFolderByIndex(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp),
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(folders, key = { _, folder -> folder.id }) { _, folder ->
            ReorderableItem(reorderableLazyListState, key = folder.id) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.extraLarge,
                    shadowElevation = elevation,
                ) {
                    FolderListItem(
                        folder = folder,
                        onEdit = { onEdit(folder.id) },
                        onDelete = { onDelete(folder.id) },
                        dragHandleScope = this@ReorderableItem,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderListItem(
    folder: CollectionFolder,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragHandleScope: ReorderableCollectionItemScope,
) {
    val hapticFeedback = LocalHapticFeedback.current

    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Folder cover preview
            if (folder.coverEmoji != null) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = folder.coverEmoji, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${folder.catalogSources.size} source${if (folder.catalogSources.size != 1) "s" else ""} · ${folder.posterShape.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                modifier = with(dragHandleScope) {
                    Modifier.draggableHandle(
                        onDragStarted = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                    ).size(36.dp)
                },
                onClick = {},
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Reorder",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Edit",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderEditorPage(
    state: CollectionEditorUiState,
    onBack: () -> Unit,
) {
    val folder = state.editingFolder ?: return
    val bottomInset = nuvioPlatformExtraBottomPadding

    PlatformBackHandler(enabled = true) {
        onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NuvioScreen(modifier = Modifier.fillMaxSize()) {
            stickyHeader {
                NuvioScreenHeader(
                    title = if (state.folders.any { it.id == folder.id }) "Edit Folder" else "New Folder",
                    onBack = onBack,
                )
            }

            item {
                NuvioSurfaceCard {
                    Text(
                        text = "Set the folder identity, presentation, and catalog sources with the same structure as the main collections editor.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                FolderEditorSection(title = "BASICS") {
                    NuvioSurfaceCard {
                        NuvioInputField(
                            value = folder.title,
                            onValueChange = { CollectionEditorRepository.updateFolderTitle(it) },
                            placeholder = "Folder Title",
                        )
                    }
                }
            }

            item {
                FolderEditorSection(title = "APPEARANCE") {
                    NuvioSurfaceCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Cover",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilterChip(
                                        selected = folder.coverEmoji == null && folder.coverImageUrl == null,
                                        onClick = { CollectionEditorRepository.clearFolderCover() },
                                        label = { Text("None") },
                                    )
                                    FilterChip(
                                        selected = folder.coverEmoji != null,
                                        onClick = {
                                            if (folder.coverEmoji == null) {
                                                CollectionEditorRepository.updateFolderCoverEmoji("📁")
                                            }
                                        },
                                        label = { Text("Emoji") },
                                    )
                                    FilterChip(
                                        selected = folder.coverImageUrl != null,
                                        onClick = {
                                            if (folder.coverImageUrl == null) {
                                                CollectionEditorRepository.updateFolderCoverImage("")
                                            }
                                        },
                                        label = { Text("Image") },
                                    )
                                }
                            }

                            if (folder.coverEmoji != null) {
                                NuvioInputField(
                                    value = folder.coverEmoji,
                                    onValueChange = { CollectionEditorRepository.updateFolderCoverEmoji(it) },
                                    placeholder = "Emoji",
                                    modifier = Modifier.width(100.dp),
                                )
                            }

                            if (folder.coverImageUrl != null) {
                                NuvioInputField(
                                    value = folder.coverImageUrl,
                                    onValueChange = { CollectionEditorRepository.updateFolderCoverImage(it) },
                                    placeholder = "Image URL",
                                )
                            }

                            NuvioInputField(
                                value = folder.focusGifUrl.orEmpty(),
                                onValueChange = { CollectionEditorRepository.updateFolderFocusGifUrl(it) },
                                placeholder = "Always-play GIF URL (optional)",
                            )
                        }
                    }

                    NuvioSurfaceCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Tile Shape",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    PosterShape.entries.forEach { shape ->
                                        FilterChip(
                                            selected = folder.posterShape == shape,
                                            onClick = { CollectionEditorRepository.updateFolderTileShape(shape) },
                                            label = { Text(shape.name) },
                                            leadingIcon = if (folder.posterShape == shape) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            } else null,
                                        )
                                    }
                                }
                            }

                            FolderEditorToggleRow(
                                title = "Show GIF When Configured",
                                subtitle = "Play the configured GIF instead of the static cover when available.",
                                checked = folder.focusGifEnabled,
                                onCheckedChange = { CollectionEditorRepository.updateFolderFocusGifEnabled(it) },
                            )

                            FolderEditorToggleRow(
                                title = "Hide Title",
                                subtitle = "Only show the artwork or emoji for this folder tile.",
                                checked = folder.hideTitle,
                                onCheckedChange = { CollectionEditorRepository.updateFolderHideTitle(it) },
                            )
                        }
                    }
                }
            }

            item {
                FolderEditorSection(
                    title = "CATALOG SOURCES",
                    actions = {
                        TextButton(onClick = { CollectionEditorRepository.showCatalogPicker() }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add")
                        }
                    },
                ) {
                    if (folder.catalogSources.isEmpty()) {
                        NuvioSurfaceCard {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "No catalog sources yet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Add catalogs from your installed addons to define what this folder shows.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            folder.catalogSources.forEachIndexed { index, source ->
                                FolderCatalogSourceCard(
                                    source = source,
                                    matchingCatalog = state.availableCatalogs.find {
                                        it.addonId == source.addonId && it.type == source.type && it.catalogId == source.catalogId
                                    },
                                    onRemove = { CollectionEditorRepository.removeCatalogSource(index) },
                                    onOpenGenrePicker = { CollectionEditorRepository.showGenrePicker(index) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(96.dp + bottomInset))
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = bottomInset),
            ) {
                NuvioPrimaryButton(
                    text = "Save Folder",
                    enabled = folder.title.isNotBlank(),
                    onClick = { CollectionEditorRepository.saveFolderEdit() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogPickerSheet(
    availableCatalogs: List<AvailableCatalog>,
    selectedSources: List<CollectionCatalogSource>,
    onToggle: (AvailableCatalog) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Select Catalog Sources",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Done")
                    }
                }
                Text(
                    text = "Choose the addon catalogs this folder should aggregate.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            val grouped = availableCatalogs.groupBy { it.addonName }
            grouped.forEach { (addonName, catalogs) ->
                item {
                    NuvioSectionLabel(
                        text = addonName.uppercase(),
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                catalogs.forEach { catalog ->
                    val isSelected = selectedSources.any {
                        it.addonId == catalog.addonId && it.type == catalog.type && it.catalogId == catalog.catalogId
                    }
                    item(key = "${catalog.addonId}:${catalog.type}:${catalog.catalogId}") {
                        val bgColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        }
                        val borderColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(bgColor)
                                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                .clickable { onToggle(catalog) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = catalog.catalogName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = catalog.type.replaceFirstChar {
                                        if (it.isLowerCase()) it.titlecase() else it.toString()
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenrePickerSheet(
    title: String,
    selectedGenre: String?,
    genreOptions: List<String>,
    allowAll: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Genre Filter",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (allowAll) {
                item {
                    GenrePickerOptionRow(
                        title = "All genres",
                        selected = selectedGenre == null,
                        onClick = { onSelect(null) },
                    )
                }
            }

            itemsIndexed(genreOptions) { _, genre ->
                GenrePickerOptionRow(
                    title = genre,
                    selected = selectedGenre == genre,
                    onClick = { onSelect(genre) },
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FolderEditorSection(
    title: String,
    actions: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioSectionLabel(text = title)
            actions?.invoke()
        }
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderEditorToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderCatalogSourceCard(
    source: CollectionCatalogSource,
    matchingCatalog: AvailableCatalog?,
    onRemove: () -> Unit,
    onOpenGenrePicker: () -> Unit,
) {
    val typeLabel = source.type.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase() else it.toString()
    }
    val metaLine = buildString {
        append(typeLabel)
        append(" · ${source.catalogId}")
    }
    val genreOptions = matchingCatalog?.genreOptions.orEmpty()
    val selectedGenreLabel = source.genre ?: if (matchingCatalog?.genreRequired == true) "Select genre" else "All genres"

    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = matchingCatalog?.catalogName ?: source.catalogId,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = matchingCatalog?.addonName ?: source.addonId,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Remove",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (genreOptions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenGenrePicker),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Genre Filter",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = selectedGenreLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenGenrePicker) {
                        Text("Choose")
                    }
                }
            }
        }
    }
}

@Composable
private fun GenrePickerOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
