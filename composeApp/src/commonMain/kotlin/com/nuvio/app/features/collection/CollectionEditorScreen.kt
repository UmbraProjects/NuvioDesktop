package com.nuvio.app.features.collection

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.accentFill
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.settings.LocalSettingsSectionCards
import com.nuvio.app.features.settings.SettingsChoiceOption
import com.nuvio.app.features.settings.SettingsChoiceRow
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSectionNote
import com.nuvio.app.features.settings.SettingsSwitchRow
import com.nuvio.app.features.settings.SettingsTextRow
import com.nuvio.app.features.trakt.TraktPublicListSearchResult
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableColumn

/**
 * Collection editor in the desktop settings language: one card per section with the heading as
 * a band across the top, rows flush beneath, and the page's primary action on the far right of
 * the screen header instead of a floating bar. The folder editor and the three source pickers
 * replace the page in turn, exactly as before — only the presentation changed.
 */
@Composable
fun CollectionEditorScreen(
    collectionId: String?,
    onBack: () -> Unit,
) {
    val state by CollectionEditorRepository.uiState.collectAsState()

    LaunchedEffect(collectionId) {
        CollectionEditorRepository.initialize(collectionId)
    }

    // The pickers sit above the folder editor, which sits above the collection page.
    if (state.showCatalogPicker) {
        CatalogPickerScreen(
            availableCatalogs = state.availableCatalogs,
            selectedSources = state.editingFolder?.resolvedCatalogSources.orEmpty(),
            onToggle = { CollectionEditorRepository.toggleCatalogSource(it) },
            onBack = { CollectionEditorRepository.hideCatalogPicker() },
        )
        return
    }
    if (state.showTmdbSourcePicker) {
        TmdbSourcePickerScreen(
            state = state,
            onBack = { CollectionEditorRepository.hideTmdbSourcePicker() },
        )
        return
    }
    if (state.showTraktSourcePicker) {
        TraktSourcePickerScreen(
            state = state,
            onBack = { CollectionEditorRepository.hideTraktSourcePicker() },
        )
        return
    }
    if (state.showFolderEditor && state.editingFolder != null) {
        FolderEditorPage(
            state = state,
            onBack = { CollectionEditorRepository.cancelFolderEdit() },
        )
        return
    }

    EditorScaffold(
        title = if (state.isNew) {
            stringResource(Res.string.collections_new)
        } else {
            stringResource(Res.string.collections_editor_edit_collection)
        },
        onBack = onBack,
        actions = {
            EditorPrimaryAction(
                text = if (state.isNew) {
                    stringResource(Res.string.collections_editor_create_collection)
                } else {
                    stringResource(Res.string.collections_editor_save_changes)
                },
                enabled = state.title.isNotBlank(),
                onClick = {
                    if (CollectionEditorRepository.save()) {
                        onBack()
                    }
                },
            )
        },
    ) {
        item {
            SettingsSection(
                title = stringResource(Res.string.collections_editor_section_collection),
                isTablet = true,
            ) {
                SettingsGroup(isTablet = true) {
                    SettingsTextRow(
                        title = stringResource(Res.string.collections_editor_name),
                        description = stringResource(Res.string.collections_editor_name_desc),
                        value = state.title,
                        placeholder = stringResource(Res.string.collections_editor_placeholder_name),
                        isTablet = true,
                        onValueChange = { CollectionEditorRepository.setTitle(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsTextRow(
                        title = stringResource(Res.string.collections_editor_backdrop),
                        description = stringResource(Res.string.collections_editor_backdrop_desc),
                        value = state.backdropImageUrl,
                        placeholder = stringResource(Res.string.collections_editor_placeholder_backdrop),
                        isTablet = true,
                        onValueChange = { CollectionEditorRepository.setBackdropImageUrl(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.collections_editor_pin_above),
                        description = stringResource(Res.string.collections_editor_pin_above_desc),
                        checked = state.pinToTop,
                        isTablet = true,
                        onCheckedChange = { CollectionEditorRepository.setPinToTop(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.collections_editor_view_mode),
                        description = stringResource(Res.string.collections_editor_view_mode_desc),
                        options = listOf(
                            SettingsChoiceOption(
                                FolderViewMode.TABBED_GRID,
                                stringResource(Res.string.collections_editor_view_mode_tabs),
                            ),
                            SettingsChoiceOption(
                                FolderViewMode.ROWS,
                                stringResource(Res.string.collections_editor_view_mode_rows),
                            ),
                        ),
                        // FOLLOW_LAYOUT is a legacy value that renders as rows; it is never offered.
                        selectedValue = if (state.viewMode == FolderViewMode.TABBED_GRID) {
                            FolderViewMode.TABBED_GRID
                        } else {
                            FolderViewMode.ROWS
                        },
                        isTablet = true,
                        onSelected = { CollectionEditorRepository.setViewMode(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.collections_editor_show_all_tab),
                        description = stringResource(Res.string.collections_editor_show_all_tab_desc),
                        checked = state.showAllTab,
                        isTablet = true,
                        onCheckedChange = { CollectionEditorRepository.setShowAllTab(it) },
                    )
                }
            }
        }

        item {
            val newFolderTitle = stringResource(Res.string.collections_editor_new_folder)
            SettingsSection(
                title = stringResource(Res.string.collections_editor_folders),
                isTablet = true,
                actions = {
                    NuvioActionLabel(
                        text = stringResource(Res.string.collections_editor_add_folder),
                        onClick = { CollectionEditorRepository.addFolder(newFolderTitle) },
                    )
                },
            ) {
                if (state.folders.isEmpty()) {
                    EditorEmptyState(
                        title = stringResource(Res.string.collections_editor_folder_empty_title),
                        subtitle = stringResource(Res.string.collections_editor_folder_empty_subtitle),
                    )
                } else {
                    FolderReorderableList(
                        folders = state.folders,
                        onEdit = { CollectionEditorRepository.editFolder(it) },
                        onDelete = { CollectionEditorRepository.removeFolder(it) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Scaffold and shared pieces
// ---------------------------------------------------------------------------------------------

/** Same column width as the desktop settings page, so the editor reads as one of its pages. */
private val EditorColumnMaxWidth = 930.dp
private val EditorPrimaryActionWidth = 200.dp

@Composable
private fun EditorScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val tokens = MaterialTheme.nuvio
        // Centred: whatever is left over past the settings column width becomes the side gutter.
        val sidePadding = ((maxWidth - EditorColumnMaxWidth) / 2)
            .coerceAtLeast(tokens.spacing.screenHorizontal)
        CompositionLocalProvider(LocalSettingsSectionCards provides true) {
            NuvioScreen(
                modifier = Modifier.fillMaxSize(),
                horizontalPadding = sidePadding,
            ) {
                stickyHeader {
                    NuvioScreenHeader(
                        title = title,
                        onBack = onBack,
                        actions = actions,
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun EditorPrimaryAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NuvioPrimaryButton(
        text = text,
        modifier = Modifier.width(EditorPrimaryActionWidth),
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * A pick-me row: title over an optional subtitle, a check on the right when [selected]. Used by
 * every list the editor offers choices from (catalogs, search results, presets).
 */
@Composable
private fun EditorOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(Res.string.cd_selected),
                tint = tokens.colors.accent,
                modifier = Modifier.size(tokens.icons.sm),
            )
        }
    }
}

/** A row that names something already added, with its controls on the right. */
@Composable
private fun EditorEntryRow(
    title: String,
    subtitle: String?,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!meta.isNullOrBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = trailing,
        )
    }
}

@Composable
private fun EditorEmptyState(title: String, subtitle: String) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
}

@Composable
private fun EditorErrorNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.nuvio.colors.danger,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** Small preset values under a filter field; tapping one fills the field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorQuickChips(
    label: String,
    chips: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { (chipLabel, value) ->
                Text(
                    text = chipLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textPrimary,
                    modifier = Modifier
                        .clip(tokens.shapes.compactCard)
                        .background(tokens.colors.surfaceCard)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** A filter text field with its helper as the description and optional quick-fill chips. */
@Composable
private fun EditorFilterRow(
    label: String,
    helper: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    quickChipsLabel: String? = null,
    quickChips: List<Pair<String, String>> = emptyList(),
) {
    SettingsTextRow(
        title = label,
        description = helper.takeIf { it.isNotBlank() },
        value = value,
        placeholder = placeholder,
        isTablet = true,
        onValueChange = onValueChange,
    )
    if (quickChipsLabel != null && quickChips.isNotEmpty()) {
        EditorQuickChips(label = quickChipsLabel, chips = quickChips, onSelect = onValueChange)
    }
}

@Composable
private fun RemoveIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(Res.string.action_remove),
            tint = MaterialTheme.nuvio.colors.danger,
        )
    }
}

@Composable
private fun EditIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = stringResource(Res.string.action_edit),
            tint = MaterialTheme.nuvio.colors.textMuted,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Folder list on the collection page
// ---------------------------------------------------------------------------------------------

@Composable
private fun FolderReorderableList(
    folders: List<CollectionFolder>,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    // A plain reorderable Column inside the page's LazyColumn, as on the Collections settings
    // page: a nested reorderable LazyColumn would add a second edge auto-scroller.
    ReorderableColumn(
        list = folders,
        onSettle = { fromIndex, toIndex ->
            CollectionEditorRepository.moveFolderByIndex(fromIndex, toIndex)
        },
        onMove = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { index, folder, isDragging ->
        key(folder.id) {
            ReorderableItem {
                val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
                Surface(
                    modifier = Modifier.draggableHandle(
                        onDragStarted = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragStopped = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                    ),
                    color = Color.Transparent,
                    shadowElevation = elevation,
                ) {
                    Column {
                        if (index > 0) SettingsGroupDivider(isTablet = true)
                        FolderListRow(
                            folder = folder,
                            onEdit = { onEdit(folder.id) },
                            onDelete = { onDelete(folder.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderListRow(
    folder: CollectionFolder,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    EditorEntryRow(
        title = folder.title,
        subtitle = stringResource(
            Res.string.collections_editor_source_count,
            folder.resolvedSources.size,
            posterShapeLabel(folder.posterShape),
        ),
        leading = folder.coverEmoji?.let { emoji ->
            {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(tokens.colors.accentFill(tokens.opacity.pressed), tokens.shapes.compactCard),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        onClick = onEdit,
    ) {
        EditIconButton(onClick = onEdit)
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.action_delete),
                tint = tokens.colors.danger,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Folder editor
// ---------------------------------------------------------------------------------------------

private enum class FolderCoverKind { None, Emoji, Image }

@Composable
private fun FolderEditorPage(
    state: CollectionEditorUiState,
    onBack: () -> Unit,
) {
    val folder = state.editingFolder ?: return
    val isExisting = state.folders.any { it.id == folder.id }

    PlatformBackHandler(enabled = true) {
        onBack()
    }

    EditorScaffold(
        title = if (isExisting) {
            stringResource(Res.string.collections_editor_edit_folder)
        } else {
            stringResource(Res.string.collections_editor_new_folder)
        },
        onBack = onBack,
        actions = {
            EditorPrimaryAction(
                text = stringResource(Res.string.collections_editor_save),
                enabled = folder.title.isNotBlank(),
                onClick = { CollectionEditorRepository.saveFolderEdit() },
            )
        },
    ) {
        item {
            SettingsSection(
                title = stringResource(Res.string.collections_editor_section_basics),
                isTablet = true,
            ) {
                SettingsGroup(isTablet = true) {
                    SettingsTextRow(
                        title = stringResource(Res.string.collections_editor_name),
                        description = stringResource(Res.string.collections_editor_folder_name_desc),
                        value = folder.title,
                        placeholder = stringResource(Res.string.collections_editor_placeholder_folder),
                        isTablet = true,
                        onValueChange = { CollectionEditorRepository.updateFolderTitle(it) },
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(Res.string.collections_editor_section_appearance),
                isTablet = true,
            ) {
                SettingsGroup(isTablet = true) {
                    val coverKind = when {
                        folder.coverEmoji != null -> FolderCoverKind.Emoji
                        folder.coverImageUrl != null -> FolderCoverKind.Image
                        else -> FolderCoverKind.None
                    }
                    SettingsChoiceRow(
                        title = stringResource(Res.string.collections_editor_cover),
                        description = stringResource(Res.string.collections_editor_cover_desc),
                        options = listOf(
                            SettingsChoiceOption(FolderCoverKind.None, stringResource(Res.string.collections_editor_cover_none)),
                            SettingsChoiceOption(FolderCoverKind.Emoji, stringResource(Res.string.collections_editor_cover_emoji)),
                            SettingsChoiceOption(FolderCoverKind.Image, stringResource(Res.string.collections_editor_cover_image_url)),
                        ),
                        selectedValue = coverKind,
                        isTablet = true,
                        onSelected = { kind ->
                            when (kind) {
                                FolderCoverKind.None -> CollectionEditorRepository.clearFolderCover()
                                FolderCoverKind.Emoji -> if (folder.coverEmoji == null) {
                                    CollectionEditorRepository.updateFolderCoverEmoji("📁")
                                }
                                FolderCoverKind.Image -> if (folder.coverImageUrl == null) {
                                    CollectionEditorRepository.updateFolderCoverImage("")
                                }
                            }
                        },
                    )
                    if (folder.coverEmoji != null) {
                        SettingsGroupDivider(isTablet = true)
                        SettingsTextRow(
                            title = stringResource(Res.string.collections_editor_cover_emoji),
                            description = stringResource(Res.string.collections_editor_cover_emoji_desc),
                            value = folder.coverEmoji,
                            placeholder = "📁",
                            isTablet = true,
                            onValueChange = { CollectionEditorRepository.updateFolderCoverEmoji(it) },
                        )
                    }
                    if (folder.coverImageUrl != null) {
                        SettingsGroupDivider(isTablet = true)
                        SettingsTextRow(
                            title = stringResource(Res.string.collections_editor_cover_image_url),
                            description = stringResource(Res.string.collections_editor_cover_image_desc),
                            value = folder.coverImageUrl,
                            placeholder = "https://",
                            isTablet = true,
                            onValueChange = { CollectionEditorRepository.updateFolderCoverImage(it) },
                        )
                    }
                    SettingsGroupDivider(isTablet = true)
                    SettingsTextRow(
                        title = stringResource(Res.string.collections_editor_focus_gif),
                        description = stringResource(Res.string.collections_editor_focus_gif_desc),
                        value = folder.focusGifUrl.orEmpty(),
                        placeholder = stringResource(Res.string.collections_editor_placeholder_gif),
                        isTablet = true,
                        onValueChange = { CollectionEditorRepository.updateFolderFocusGifUrl(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.collections_editor_show_gif_when_configured),
                        description = stringResource(Res.string.collections_editor_show_gif_when_configured_desc),
                        checked = folder.mobileFocusGifEnabled,
                        isTablet = true,
                        onCheckedChange = { CollectionEditorRepository.updateFolderMobileFocusGifEnabled(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.collections_editor_tile_shape),
                        description = stringResource(Res.string.collections_editor_tile_shape_desc),
                        options = PosterShape.entries.map { shape ->
                            SettingsChoiceOption(shape, posterShapeLabel(shape))
                        },
                        selectedValue = folder.posterShape,
                        isTablet = true,
                        onSelected = { CollectionEditorRepository.updateFolderTileShape(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.collections_editor_hide_title),
                        description = stringResource(Res.string.collections_editor_hide_title_desc),
                        checked = folder.hideTitle,
                        isTablet = true,
                        onCheckedChange = { CollectionEditorRepository.updateFolderHideTitle(it) },
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(Res.string.collections_editor_section_catalog_sources),
                isTablet = true,
                actions = {
                    NuvioActionLabel(
                        text = stringResource(Res.string.source_tmdb),
                        onClick = { CollectionEditorRepository.showTmdbSourcePicker() },
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    NuvioActionLabel(
                        text = stringResource(Res.string.collections_editor_add_trakt_source),
                        onClick = { CollectionEditorRepository.showTraktSourcePicker() },
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    NuvioActionLabel(
                        text = stringResource(Res.string.collections_editor_add_catalog),
                        onClick = { CollectionEditorRepository.showCatalogPicker() },
                    )
                },
            ) {
                val sources = folder.resolvedSources
                if (sources.isEmpty()) {
                    EditorEmptyState(
                        title = stringResource(Res.string.collections_editor_catalog_sources_empty_title),
                        subtitle = stringResource(Res.string.collections_editor_catalog_sources_empty_subtitle),
                    )
                } else {
                    SettingsGroup(isTablet = true) {
                        sources.forEachIndexed { index, source ->
                            if (index > 0) SettingsGroupDivider(isTablet = true)
                            val addonSource = source.addonCatalogSource()
                            when {
                                source.isTmdb -> EditorEntryRow(
                                    title = source.title?.takeIf { it.isNotBlank() }
                                        ?: stringResource(Res.string.source_tmdb),
                                    subtitle = stringResource(Res.string.source_tmdb),
                                    meta = tmdbSourceSubtitle(source),
                                ) {
                                    RemoveIconButton(onClick = { CollectionEditorRepository.removeCatalogSource(index) })
                                }
                                source.isTrakt -> EditorEntryRow(
                                    title = source.title?.takeIf { it.isNotBlank() }
                                        ?: stringResource(Res.string.source_trakt),
                                    subtitle = stringResource(Res.string.source_trakt),
                                    meta = traktSourceSubtitle(source),
                                    onClick = { CollectionEditorRepository.editTraktSource(index) },
                                ) {
                                    EditIconButton(onClick = { CollectionEditorRepository.editTraktSource(index) })
                                    RemoveIconButton(onClick = { CollectionEditorRepository.removeCatalogSource(index) })
                                }
                                addonSource != null -> FolderCatalogSourceRows(
                                    index = index,
                                    source = addonSource,
                                    matchingCatalog = state.availableCatalogs.findAvailableCatalog(addonSource),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * An addon catalog source and, when the catalog has genres, its genre filter as the row directly
 * beneath it. A dropdown replaces the old bottom-sheet picker: the choice is made in place.
 */
@Composable
private fun FolderCatalogSourceRows(
    index: Int,
    source: CollectionCatalogSource,
    matchingCatalog: AvailableCatalog?,
) {
    val typeLabel = source.type.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase() else it.toString()
    }
    EditorEntryRow(
        title = matchingCatalog?.catalogName ?: source.catalogId,
        subtitle = matchingCatalog?.addonName ?: source.addonId,
        meta = "$typeLabel · ${source.catalogId}",
    ) {
        RemoveIconButton(onClick = { CollectionEditorRepository.removeCatalogSource(index) })
    }
    val genreOptions = matchingCatalog?.genreOptions.orEmpty()
    if (genreOptions.isNotEmpty()) {
        val allowAll = matchingCatalog?.genreRequired != true
        val noGenreLabel = if (allowAll) {
            stringResource(Res.string.collections_editor_all_genres)
        } else {
            stringResource(Res.string.collections_editor_select_genre)
        }
        SettingsChoiceRow(
            title = stringResource(Res.string.collections_editor_genre_filter),
            description = matchingCatalog?.catalogName,
            options = listOf(SettingsChoiceOption<String?>(null, noGenreLabel)) +
                genreOptions.map { SettingsChoiceOption<String?>(it, it) },
            selectedValue = source.genre,
            isTablet = true,
            onSelected = { CollectionEditorRepository.updateCatalogSourceGenre(index, it) },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Addon catalog picker
// ---------------------------------------------------------------------------------------------

@Composable
private fun CatalogPickerScreen(
    availableCatalogs: List<AvailableCatalog>,
    selectedSources: List<CollectionCatalogSource>,
    onToggle: (AvailableCatalog) -> Unit,
    onBack: () -> Unit,
) {
    PlatformBackHandler(enabled = true) {
        onBack()
    }

    fun isSelected(catalog: AvailableCatalog): Boolean = selectedSources.any {
        it.addonId == catalog.addonId && it.type == catalog.type && it.catalogId == catalog.catalogId
    }

    EditorScaffold(
        title = stringResource(Res.string.collections_editor_select_catalogs),
        onBack = onBack,
        actions = {
            EditorPrimaryAction(
                text = stringResource(Res.string.collections_editor_done),
                enabled = true,
                onClick = onBack,
            )
        },
    ) {
        item {
            SettingsSectionNote(
                text = stringResource(Res.string.collections_editor_select_catalogs_description) +
                    " " + stringResource(Res.string.collections_editor_selected_count, selectedSources.size),
                isTablet = true,
            )
        }

        val grouped = availableCatalogs.groupBy { it.addonName }
        grouped.forEach { (addonName, catalogs) ->
            item {
                val selectedCount = catalogs.count { isSelected(it) }
                SettingsSection(
                    title = addonName,
                    isTablet = true,
                    actions = {
                        Text(
                            text = if (selectedCount > 0) {
                                stringResource(Res.string.collections_editor_catalog_selected_count, selectedCount)
                            } else {
                                stringResource(Res.string.collections_editor_catalog_count, catalogs.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.nuvio.colors.textMuted,
                        )
                    },
                ) {
                    SettingsGroup(isTablet = true) {
                        catalogs.forEachIndexed { index, catalog ->
                            if (index > 0) SettingsGroupDivider(isTablet = true)
                            EditorOptionRow(
                                title = catalog.catalogName,
                                subtitle = catalog.type.replaceFirstChar {
                                    if (it.isLowerCase()) it.titlecase() else it.toString()
                                },
                                selected = isSelected(catalog),
                                onClick = { onToggle(catalog) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// TMDB source picker
// ---------------------------------------------------------------------------------------------

@Composable
private fun TmdbSourcePickerScreen(
    state: CollectionEditorUiState,
    onBack: () -> Unit,
) {
    val mode = state.tmdbBuilderMode
    val sourceType = when (mode) {
        TmdbBuilderMode.PRESETS -> TmdbCollectionSourceType.DISCOVER
        TmdbBuilderMode.LIST -> TmdbCollectionSourceType.LIST
        TmdbBuilderMode.COLLECTION -> TmdbCollectionSourceType.COLLECTION
        TmdbBuilderMode.PRODUCTION -> TmdbCollectionSourceType.COMPANY
        TmdbBuilderMode.NETWORK -> TmdbCollectionSourceType.NETWORK
        TmdbBuilderMode.PERSON -> TmdbCollectionSourceType.PERSON
        TmdbBuilderMode.DIRECTOR -> TmdbCollectionSourceType.DIRECTOR
        TmdbBuilderMode.DISCOVER -> TmdbCollectionSourceType.DISCOVER
    }
    val requiresId = sourceType != TmdbCollectionSourceType.DISCOVER
    val canSearch = sourceType == TmdbCollectionSourceType.COMPANY ||
        sourceType == TmdbCollectionSourceType.COLLECTION
    val showMediaControls = mode == TmdbBuilderMode.PRODUCTION ||
        mode == TmdbBuilderMode.PERSON ||
        mode == TmdbBuilderMode.DIRECTOR ||
        mode == TmdbBuilderMode.DISCOVER
    val showSortControls = mode == TmdbBuilderMode.PRODUCTION ||
        mode == TmdbBuilderMode.NETWORK ||
        mode == TmdbBuilderMode.PERSON ||
        mode == TmdbBuilderMode.DIRECTOR ||
        mode == TmdbBuilderMode.DISCOVER
    val showFilterControls = mode == TmdbBuilderMode.DISCOVER
    val isPresets = mode == TmdbBuilderMode.PRESETS

    PlatformBackHandler(enabled = true) {
        onBack()
    }

    EditorScaffold(
        title = stringResource(Res.string.collections_editor_tmdb_sources),
        onBack = onBack,
        actions = {
            if (!isPresets) {
                EditorPrimaryAction(
                    text = stringResource(Res.string.collections_editor_add_source),
                    enabled = !requiresId || state.tmdbInput.isNotBlank(),
                    onClick = { CollectionEditorRepository.addTmdbSourceFromInput() },
                )
            }
        },
    ) {
        item {
            SettingsSection(
                title = stringResource(Res.string.collections_editor_tmdb_source_type),
                isTablet = true,
            ) {
                SettingsGroup(isTablet = true) {
                    SettingsChoiceRow(
                        title = stringResource(Res.string.collections_editor_tmdb_source_type),
                        description = tmdbModeHelpText(mode),
                        options = TmdbBuilderMode.entries.map { SettingsChoiceOption(it, tmdbBuilderModeLabel(it)) },
                        selectedValue = mode,
                        isTablet = true,
                        onSelected = { CollectionEditorRepository.setTmdbBuilderMode(it) },
                    )
                }
            }
        }

        if (!isPresets) {
            item {
                SettingsSection(
                    title = stringResource(Res.string.collections_editor_section_details),
                    isTablet = true,
                    actions = {
                        if (canSearch) {
                            NuvioActionLabel(
                                text = stringResource(Res.string.collections_editor_tmdb_search),
                                onClick = {
                                    if (sourceType == TmdbCollectionSourceType.COMPANY) {
                                        CollectionEditorRepository.searchTmdbCompanies()
                                    } else {
                                        CollectionEditorRepository.searchTmdbCollections()
                                    }
                                },
                            )
                        }
                    },
                ) {
                    SettingsGroup(isTablet = true) {
                        if (requiresId) {
                            SettingsTextRow(
                                title = tmdbInputLabel(mode),
                                description = tmdbInputHelper(mode).takeIf { it.isNotBlank() },
                                value = state.tmdbInput,
                                placeholder = tmdbInputPlaceholder(mode),
                                isTablet = true,
                                onValueChange = { CollectionEditorRepository.setTmdbInput(it) },
                            )
                            SettingsGroupDivider(isTablet = true)
                        }
                        SettingsTextRow(
                            title = stringResource(Res.string.collections_editor_tmdb_display_title),
                            description = stringResource(Res.string.collections_editor_tmdb_title_helper),
                            value = state.tmdbTitleInput,
                            placeholder = tmdbTitlePlaceholder(mode),
                            isTablet = true,
                            onValueChange = { CollectionEditorRepository.setTmdbTitleInput(it) },
                        )
                        if (showMediaControls) {
                            SettingsGroupDivider(isTablet = true)
                            TmdbMediaTypeRow(
                                mediaType = state.tmdbMediaType,
                                both = state.tmdbMediaBoth,
                                onSelect = { selection ->
                                    when (selection) {
                                        MediaSelection.MOVIES -> {
                                            CollectionEditorRepository.setTmdbMediaBoth(false)
                                            CollectionEditorRepository.setTmdbMediaType(TmdbCollectionMediaType.MOVIE)
                                        }
                                        MediaSelection.SERIES -> {
                                            CollectionEditorRepository.setTmdbMediaBoth(false)
                                            CollectionEditorRepository.setTmdbMediaType(TmdbCollectionMediaType.TV)
                                        }
                                        MediaSelection.BOTH -> CollectionEditorRepository.setTmdbMediaBoth(true)
                                    }
                                },
                            )
                        }
                        if (showSortControls) {
                            SettingsGroupDivider(isTablet = true)
                            val sorts = listOf(
                                TmdbCollectionSort.POPULAR_DESC,
                                TmdbCollectionSort.VOTE_AVERAGE_DESC,
                                TmdbCollectionSort.VOTE_COUNT_DESC,
                                if (state.tmdbMediaType == TmdbCollectionMediaType.TV && !state.tmdbMediaBoth) {
                                    TmdbCollectionSort.FIRST_AIR_DATE_DESC
                                } else {
                                    TmdbCollectionSort.RELEASE_DATE_DESC
                                },
                            )
                            SettingsChoiceRow(
                                title = stringResource(Res.string.collections_editor_tmdb_sort),
                                description = null,
                                options = sorts.map { SettingsChoiceOption(it.value, tmdbSortLabel(it)) },
                                selectedValue = state.tmdbSortBy,
                                isTablet = true,
                                onSelected = { CollectionEditorRepository.setTmdbSortBy(it) },
                            )
                        }
                        if (state.tmdbSearchError != null) {
                            SettingsGroupDivider(isTablet = true)
                            EditorErrorNote(state.tmdbSearchError)
                        }
                    }
                }
            }
        }

        if (mode == TmdbBuilderMode.PRODUCTION && state.tmdbCompanyResults.isNotEmpty()) {
            item {
                val movieSuffix = stringResource(Res.string.collections_editor_tmdb_movies)
                val seriesSuffix = stringResource(Res.string.collections_editor_tmdb_series)
                SettingsSection(
                    title = stringResource(Res.string.collections_editor_tmdb_search_results),
                    isTablet = true,
                ) {
                    SettingsGroup(isTablet = true) {
                        state.tmdbCompanyResults.forEachIndexed { index, result ->
                            if (index > 0) SettingsGroupDivider(isTablet = true)
                            val title = result.name
                                ?: stringResource(Res.string.collections_editor_tmdb_company_fallback, result.id)
                            EditorOptionRow(
                                title = title,
                                subtitle = listOfNotNull(
                                    stringResource(Res.string.collections_editor_tmdb_subtitle_production),
                                    result.originCountry,
                                ).joinToString(" • "),
                                onClick = {
                                    val sources = tmdbSelectedMediaTypes(state).map { mediaType ->
                                        CollectionSource(
                                            provider = "tmdb",
                                            tmdbSourceType = TmdbCollectionSourceType.COMPANY.name,
                                            title = tmdbTitleForMedia(title, mediaType, state.tmdbMediaBoth, movieSuffix, seriesSuffix),
                                            tmdbId = result.id,
                                            mediaType = mediaType.name,
                                            sortBy = state.tmdbSortBy,
                                            filters = state.tmdbFilters,
                                        )
                                    }
                                    CollectionEditorRepository.addTmdbSourcesFromPicker(sources)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (mode == TmdbBuilderMode.COLLECTION && state.tmdbCollectionResults.isNotEmpty()) {
            item {
                SettingsSection(
                    title = stringResource(Res.string.collections_editor_tmdb_search_results),
                    isTablet = true,
                ) {
                    SettingsGroup(isTablet = true) {
                        state.tmdbCollectionResults.forEachIndexed { index, result ->
                            if (index > 0) SettingsGroupDivider(isTablet = true)
                            val title = result.name
                                ?: stringResource(Res.string.collections_editor_tmdb_collection_fallback, result.id)
                            EditorOptionRow(
                                title = title,
                                subtitle = stringResource(Res.string.collections_editor_tmdb_collection),
                                onClick = {
                                    CollectionEditorRepository.addTmdbSource(
                                        CollectionSource(
                                            provider = "tmdb",
                                            tmdbSourceType = TmdbCollectionSourceType.COLLECTION.name,
                                            title = title,
                                            tmdbId = result.id,
                                            mediaType = TmdbCollectionMediaType.MOVIE.name,
                                            sortBy = state.tmdbSortBy,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showFilterControls) {
            item { TmdbFiltersSection(state = state) }
        }

        if (isPresets) {
            item {
                SettingsSection(
                    title = stringResource(Res.string.collections_editor_tmdb_presets),
                    isTablet = true,
                    subtitle = stringResource(Res.string.collections_editor_tmdb_help_presets),
                ) {
                    SettingsGroup(isTablet = true) {
                        TmdbCollectionSourceResolver.presets().forEachIndexed { index, preset ->
                            if (index > 0) SettingsGroupDivider(isTablet = true)
                            EditorOptionRow(
                                title = preset.label,
                                subtitle = tmdbSourceSubtitle(preset.source),
                                onClick = { CollectionEditorRepository.addTmdbPreset(preset.source) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class MediaSelection { MOVIES, SERIES, BOTH }

@Composable
private fun TmdbMediaTypeRow(
    mediaType: TmdbCollectionMediaType,
    both: Boolean,
    onSelect: (MediaSelection) -> Unit,
) {
    SettingsChoiceRow(
        title = stringResource(Res.string.collections_editor_tmdb_type),
        description = null,
        options = listOf(
            SettingsChoiceOption(MediaSelection.MOVIES, stringResource(Res.string.collections_editor_tmdb_movies)),
            SettingsChoiceOption(MediaSelection.SERIES, stringResource(Res.string.collections_editor_tmdb_series)),
            SettingsChoiceOption(MediaSelection.BOTH, stringResource(Res.string.collections_editor_tmdb_both)),
        ),
        selectedValue = when {
            both -> MediaSelection.BOTH
            mediaType == TmdbCollectionMediaType.TV -> MediaSelection.SERIES
            else -> MediaSelection.MOVIES
        },
        isTablet = true,
        onSelected = onSelect,
    )
}

@Composable
private fun TmdbFiltersSection(state: CollectionEditorUiState) {
    val filters = state.tmdbFilters
    fun update(transform: (TmdbCollectionFilters) -> TmdbCollectionFilters) =
        CollectionEditorRepository.updateTmdbFilters(transform)

    SettingsSection(
        title = stringResource(Res.string.collections_editor_tmdb_filters),
        isTablet = true,
        subtitle = stringResource(Res.string.collections_editor_tmdb_filters_helper),
    ) {
        SettingsGroup(isTablet = true) {
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_genres),
                helper = stringResource(Res.string.collections_editor_tmdb_genres_helper),
                value = filters.withGenres.orEmpty(),
                placeholder = if (state.tmdbMediaType == TmdbCollectionMediaType.MOVIE) {
                    stringResource(Res.string.collections_editor_tmdb_genres_movie_placeholder)
                } else {
                    stringResource(Res.string.collections_editor_tmdb_genres_series_placeholder)
                },
                onValueChange = { value -> update { it.copy(withGenres = value.ifBlank { null }) } },
                quickChipsLabel = stringResource(Res.string.collections_editor_tmdb_quick_genres),
                quickChips = tmdbGenreQuickChips(state.tmdbMediaType),
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_date_from),
                helper = stringResource(Res.string.collections_editor_tmdb_date_helper),
                value = filters.releaseDateGte.orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_date_from_placeholder),
                onValueChange = { value -> update { it.copy(releaseDateGte = value.ifBlank { null }) } },
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_date_to),
                helper = stringResource(Res.string.collections_editor_tmdb_date_helper),
                value = filters.releaseDateLte.orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_date_to_placeholder),
                onValueChange = { value -> update { it.copy(releaseDateLte = value.ifBlank { null }) } },
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_rating_min),
                helper = stringResource(Res.string.collections_editor_tmdb_rating_helper),
                value = filters.voteAverageGte?.toString().orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_rating_min_placeholder),
                onValueChange = { value -> update { it.copy(voteAverageGte = value.toDoubleOrNull()) } },
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_rating_max),
                helper = stringResource(Res.string.collections_editor_tmdb_rating_helper),
                value = filters.voteAverageLte?.toString().orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_rating_max_placeholder),
                onValueChange = { value -> update { it.copy(voteAverageLte = value.toDoubleOrNull()) } },
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_votes_min),
                helper = stringResource(Res.string.collections_editor_tmdb_votes_helper),
                value = filters.voteCountGte?.toString().orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_votes_min_placeholder),
                onValueChange = { value -> update { it.copy(voteCountGte = value.toIntOrNull()) } },
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_language),
                helper = stringResource(Res.string.collections_editor_tmdb_language_helper),
                value = filters.withOriginalLanguage.orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_language_placeholder),
                onValueChange = { value -> update { it.copy(withOriginalLanguage = value.ifBlank { null }) } },
                quickChipsLabel = stringResource(Res.string.collections_editor_tmdb_quick_languages),
                quickChips = listOf(
                    stringResource(Res.string.collections_editor_tmdb_language_english) to "en",
                    stringResource(Res.string.collections_editor_tmdb_language_korean) to "ko",
                    stringResource(Res.string.collections_editor_tmdb_language_japanese) to "ja",
                    stringResource(Res.string.collections_editor_tmdb_language_hindi) to "hi",
                    stringResource(Res.string.collections_editor_tmdb_language_spanish) to "es",
                ),
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_country),
                helper = stringResource(Res.string.collections_editor_tmdb_country_helper),
                value = filters.withOriginCountry.orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_country_placeholder),
                onValueChange = { value -> update { it.copy(withOriginCountry = value.ifBlank { null }) } },
                quickChipsLabel = stringResource(Res.string.collections_editor_tmdb_quick_countries),
                quickChips = listOf(
                    stringResource(Res.string.collections_editor_tmdb_country_us) to "US",
                    stringResource(Res.string.collections_editor_tmdb_country_korea) to "KR",
                    stringResource(Res.string.collections_editor_tmdb_country_japan) to "JP",
                    stringResource(Res.string.collections_editor_tmdb_country_india) to "IN",
                    stringResource(Res.string.collections_editor_tmdb_country_uk) to "GB",
                ),
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_keywords),
                helper = stringResource(Res.string.collections_editor_tmdb_keywords_helper),
                value = filters.withKeywords.orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_keywords_placeholder),
                onValueChange = { value -> update { it.copy(withKeywords = value.ifBlank { null }) } },
                quickChipsLabel = stringResource(Res.string.collections_editor_tmdb_quick_keywords),
                quickChips = listOf(
                    stringResource(Res.string.collections_editor_tmdb_keyword_superhero) to "9715",
                    stringResource(Res.string.collections_editor_tmdb_keyword_based_on_novel) to "818",
                    stringResource(Res.string.collections_editor_tmdb_keyword_time_travel) to "4379",
                    stringResource(Res.string.collections_editor_tmdb_keyword_space) to "9882",
                ),
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_companies),
                helper = stringResource(Res.string.collections_editor_tmdb_companies_helper),
                value = filters.withCompanies.orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_companies_placeholder),
                onValueChange = { value -> update { it.copy(withCompanies = value.ifBlank { null }) } },
                quickChipsLabel = stringResource(Res.string.collections_editor_tmdb_quick_studios),
                quickChips = listOf(
                    stringResource(Res.string.collections_editor_tmdb_studio_marvel) to "420",
                    stringResource(Res.string.collections_editor_tmdb_studio_disney) to "2",
                    stringResource(Res.string.collections_editor_tmdb_studio_pixar) to "3",
                    stringResource(Res.string.collections_editor_tmdb_studio_lucasfilm) to "1",
                    stringResource(Res.string.collections_editor_tmdb_studio_warner) to "174",
                ),
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_networks),
                helper = stringResource(Res.string.collections_editor_tmdb_networks_helper),
                value = filters.withNetworks.orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_networks_placeholder),
                onValueChange = { value -> update { it.copy(withNetworks = value.ifBlank { null }) } },
                quickChipsLabel = stringResource(Res.string.collections_editor_tmdb_quick_networks),
                quickChips = listOf(
                    stringResource(Res.string.collections_editor_tmdb_network_netflix) to "213",
                    stringResource(Res.string.collections_editor_tmdb_network_hbo) to "49",
                    stringResource(Res.string.collections_editor_tmdb_network_disney_plus) to "2739",
                    stringResource(Res.string.collections_editor_tmdb_network_prime_video) to "1024",
                    stringResource(Res.string.collections_editor_tmdb_network_hulu) to "453",
                ),
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_year),
                helper = stringResource(Res.string.collections_editor_tmdb_year_helper),
                value = filters.year?.toString().orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_year_placeholder),
                onValueChange = { value -> update { it.copy(year = value.toIntOrNull()) } },
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_watch_providers),
                helper = stringResource(Res.string.collections_editor_tmdb_watch_providers_helper),
                value = filters.withWatchProviders.orEmpty(),
                placeholder = stringResource(Res.string.collections_editor_tmdb_watch_providers_placeholder),
                onValueChange = { value -> update { it.copy(withWatchProviders = value.ifBlank { null }) } },
                quickChipsLabel = stringResource(Res.string.collections_editor_tmdb_quick_watch_providers),
                quickChips = listOf(
                    stringResource(Res.string.collections_editor_tmdb_watch_provider_netflix) to "8",
                    stringResource(Res.string.collections_editor_tmdb_watch_provider_prime) to "119",
                    stringResource(Res.string.collections_editor_tmdb_watch_provider_disney) to "337",
                    stringResource(Res.string.collections_editor_tmdb_watch_provider_apple) to "350",
                    stringResource(Res.string.collections_editor_tmdb_watch_provider_hulu) to "15",
                ),
            )
            SettingsGroupDivider(isTablet = true)
            EditorFilterRow(
                label = stringResource(Res.string.collections_editor_tmdb_watch_region),
                helper = stringResource(Res.string.collections_editor_tmdb_watch_region_helper),
                value = filters.watchRegion.orEmpty(),
                placeholder = "US",
                onValueChange = { value -> update { it.copy(watchRegion = value.ifBlank { null }) } },
                quickChipsLabel = stringResource(Res.string.collections_editor_tmdb_quick_watch_regions),
                quickChips = listOf(
                    stringResource(Res.string.collections_editor_tmdb_country_us) to "US",
                    stringResource(Res.string.collections_editor_tmdb_country_uk) to "GB",
                    stringResource(Res.string.collections_editor_tmdb_country_ca) to "CA",
                    stringResource(Res.string.collections_editor_tmdb_country_au) to "AU",
                    stringResource(Res.string.collections_editor_tmdb_country_de) to "DE",
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Trakt source picker
// ---------------------------------------------------------------------------------------------

@Composable
private fun TraktSourcePickerScreen(
    state: CollectionEditorUiState,
    onBack: () -> Unit,
) {
    val isEditing = state.editingTraktSourceIndex != null

    PlatformBackHandler(enabled = true) {
        onBack()
    }

    EditorScaffold(
        title = if (isEditing) {
            stringResource(Res.string.collections_editor_edit_trakt_source)
        } else {
            stringResource(Res.string.collections_editor_trakt_sources)
        },
        onBack = onBack,
        actions = {
            EditorPrimaryAction(
                text = if (isEditing) {
                    stringResource(Res.string.collections_editor_save)
                } else {
                    stringResource(Res.string.collections_editor_add_source)
                },
                enabled = state.traktInput.isNotBlank(),
                onClick = { CollectionEditorRepository.addTraktSourceFromInput() },
            )
        },
    ) {
        item {
            SettingsSection(
                title = stringResource(Res.string.collections_editor_section_details),
                isTablet = true,
                actions = {
                    NuvioActionLabel(
                        text = stringResource(Res.string.collections_editor_tmdb_search),
                        onClick = { CollectionEditorRepository.searchTraktLists() },
                    )
                },
            ) {
                SettingsGroup(isTablet = true) {
                    SettingsTextRow(
                        title = stringResource(Res.string.collections_editor_trakt_list),
                        description = stringResource(Res.string.collections_editor_trakt_input_helper),
                        value = state.traktInput,
                        placeholder = stringResource(Res.string.collections_editor_trakt_input_placeholder),
                        isTablet = true,
                        onValueChange = { CollectionEditorRepository.setTraktInput(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsTextRow(
                        title = stringResource(Res.string.collections_editor_tmdb_display_title),
                        description = stringResource(Res.string.collections_editor_tmdb_title_helper),
                        value = state.traktTitleInput,
                        placeholder = stringResource(Res.string.collections_editor_trakt_title_placeholder),
                        isTablet = true,
                        onValueChange = { CollectionEditorRepository.setTraktTitleInput(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    TmdbMediaTypeRow(
                        mediaType = state.traktMediaType,
                        both = state.traktMediaBoth,
                        onSelect = { selection ->
                            when (selection) {
                                MediaSelection.MOVIES -> {
                                    CollectionEditorRepository.setTraktMediaBoth(false)
                                    CollectionEditorRepository.setTraktMediaType(TmdbCollectionMediaType.MOVIE)
                                }
                                MediaSelection.SERIES -> {
                                    CollectionEditorRepository.setTraktMediaBoth(false)
                                    CollectionEditorRepository.setTraktMediaType(TmdbCollectionMediaType.TV)
                                }
                                MediaSelection.BOTH -> CollectionEditorRepository.setTraktMediaBoth(true)
                            }
                        },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.collections_editor_tmdb_sort),
                        description = null,
                        options = traktSortOptions().map { (value, label) -> SettingsChoiceOption(value, label) },
                        selectedValue = TraktListSort.normalize(state.traktSortBy),
                        isTablet = true,
                        onSelected = { CollectionEditorRepository.setTraktSortBy(it) },
                    )
                    SettingsGroupDivider(isTablet = true)
                    SettingsChoiceRow(
                        title = stringResource(Res.string.collections_editor_trakt_direction),
                        description = null,
                        options = listOf(
                            SettingsChoiceOption(TraktSortHow.ASC.value, stringResource(Res.string.collections_editor_trakt_ascending)),
                            SettingsChoiceOption(TraktSortHow.DESC.value, stringResource(Res.string.collections_editor_trakt_descending)),
                        ),
                        selectedValue = TraktSortHow.normalize(state.traktSortHow),
                        isTablet = true,
                        onSelected = { CollectionEditorRepository.setTraktSortHow(it) },
                    )
                    if (state.traktSearchError != null) {
                        SettingsGroupDivider(isTablet = true)
                        EditorErrorNote(state.traktSearchError)
                    }
                }
            }
        }

        traktResultSection(
            title = Res.string.collections_editor_trakt_search_results,
            results = state.traktSearchResults,
        )
        traktResultSection(
            title = Res.string.collections_editor_trakt_trending,
            results = state.traktTrendingResults,
        )
        traktResultSection(
            title = Res.string.collections_editor_trakt_popular,
            results = state.traktPopularResults,
        )
    }
}

private fun LazyListScope.traktResultSection(
    title: org.jetbrains.compose.resources.StringResource,
    results: List<TraktPublicListSearchResult>,
) {
    if (results.isEmpty()) return
    item {
        SettingsSection(title = stringResource(title), isTablet = true) {
            SettingsGroup(isTablet = true) {
                results.forEachIndexed { index, result ->
                    if (index > 0) SettingsGroupDivider(isTablet = true)
                    EditorOptionRow(
                        title = result.title,
                        subtitle = result.subtitle,
                        onClick = { CollectionEditorRepository.addTraktSourceFromResult(result) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------------------------

@Composable
private fun tmdbGenreQuickChips(mediaType: TmdbCollectionMediaType): List<Pair<String, String>> =
    when (mediaType) {
        TmdbCollectionMediaType.MOVIE -> listOf(
            stringResource(Res.string.collections_editor_tmdb_genre_action) to "28",
            stringResource(Res.string.collections_editor_tmdb_genre_adventure) to "12",
            stringResource(Res.string.collections_editor_tmdb_genre_animation) to "16",
            stringResource(Res.string.collections_editor_tmdb_genre_comedy) to "35",
            stringResource(Res.string.collections_editor_tmdb_genre_horror) to "27",
            stringResource(Res.string.collections_editor_tmdb_genre_scifi) to "878",
        )
        TmdbCollectionMediaType.TV -> listOf(
            stringResource(Res.string.collections_editor_tmdb_genre_drama) to "18",
            stringResource(Res.string.collections_editor_tmdb_genre_comedy) to "35",
            stringResource(Res.string.collections_editor_tmdb_genre_animation) to "16",
            stringResource(Res.string.collections_editor_tmdb_genre_crime) to "80",
            stringResource(Res.string.collections_editor_tmdb_genre_scifi) to "10765",
            stringResource(Res.string.collections_editor_tmdb_genre_reality) to "10764",
        )
    }

private fun tmdbSelectedMediaTypes(state: CollectionEditorUiState): List<TmdbCollectionMediaType> =
    if (state.tmdbMediaBoth) {
        listOf(TmdbCollectionMediaType.MOVIE, TmdbCollectionMediaType.TV)
    } else {
        listOf(state.tmdbMediaType)
    }

private fun tmdbTitleForMedia(
    title: String,
    mediaType: TmdbCollectionMediaType,
    addSuffix: Boolean,
    movieSuffix: String,
    seriesSuffix: String,
): String {
    if (!addSuffix) return title
    val suffix = when (mediaType) {
        TmdbCollectionMediaType.MOVIE -> movieSuffix
        TmdbCollectionMediaType.TV -> seriesSuffix
    }
    return "$title $suffix"
}

@Composable
private fun tmdbBuilderModeLabel(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.PRESETS -> stringResource(Res.string.collections_editor_tmdb_presets)
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_public_list_mode)
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_production_mode)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_network_mode)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_collection_mode)
        TmdbBuilderMode.PERSON -> stringResource(Res.string.collections_editor_tmdb_person_mode)
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_director_mode)
        TmdbBuilderMode.DISCOVER -> stringResource(Res.string.collections_editor_tmdb_custom_mode)
    }

@Composable
private fun tmdbModeHelpText(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.PRESETS -> stringResource(Res.string.collections_editor_tmdb_help_presets)
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_help_list)
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_help_production)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_help_network)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_help_collection)
        TmdbBuilderMode.PERSON -> stringResource(Res.string.collections_editor_tmdb_help_person)
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_help_director)
        TmdbBuilderMode.DISCOVER -> stringResource(Res.string.collections_editor_tmdb_help_discover)
    }

@Composable
private fun tmdbInputLabel(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_public_list)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_network_id)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_collection_id)
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_company_search)
        TmdbBuilderMode.PERSON,
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_person_id)
        else -> stringResource(Res.string.collections_editor_tmdb_id_or_url)
    }

@Composable
private fun tmdbInputPlaceholder(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_list_placeholder)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_network_placeholder)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_collection_placeholder)
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_company_placeholder)
        TmdbBuilderMode.PERSON,
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_person_placeholder)
        else -> stringResource(Res.string.collections_editor_tmdb_id_or_url)
    }

@Composable
private fun tmdbInputHelper(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.PRODUCTION -> stringResource(Res.string.collections_editor_tmdb_search_helper)
        TmdbBuilderMode.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_collection_helper)
        TmdbBuilderMode.NETWORK -> stringResource(Res.string.collections_editor_tmdb_network_helper)
        TmdbBuilderMode.LIST -> stringResource(Res.string.collections_editor_tmdb_list_helper)
        TmdbBuilderMode.PERSON,
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_person_helper)
        else -> ""
    }

@Composable
private fun tmdbTitlePlaceholder(mode: TmdbBuilderMode): String =
    when (mode) {
        TmdbBuilderMode.DISCOVER -> stringResource(Res.string.collections_editor_tmdb_discover_title_placeholder)
        TmdbBuilderMode.PERSON -> stringResource(Res.string.collections_editor_tmdb_person_title_placeholder)
        TmdbBuilderMode.DIRECTOR -> stringResource(Res.string.collections_editor_tmdb_director_title_placeholder)
        else -> stringResource(Res.string.collections_editor_tmdb_title_placeholder)
    }

@Composable
private fun tmdbSortLabel(sort: TmdbCollectionSort): String =
    when (sort) {
        TmdbCollectionSort.ORIGINAL -> stringResource(Res.string.collections_editor_tmdb_sort_original)
        TmdbCollectionSort.POPULAR_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_popular)
        TmdbCollectionSort.VOTE_AVERAGE_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_top_rated)
        TmdbCollectionSort.VOTE_COUNT_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_vote_count)
        TmdbCollectionSort.RELEASE_DATE_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_recent)
        TmdbCollectionSort.FIRST_AIR_DATE_DESC -> stringResource(Res.string.collections_editor_tmdb_sort_recent)
    }

@Composable
private fun traktSortOptions(): List<Pair<String, String>> =
    listOf(
        TraktListSort.RANK.value to stringResource(Res.string.collections_editor_trakt_sort_list_order),
        TraktListSort.ADDED.value to stringResource(Res.string.collections_editor_trakt_sort_recently_added),
        TraktListSort.TITLE.value to stringResource(Res.string.collections_editor_trakt_sort_title),
        TraktListSort.RELEASED.value to stringResource(Res.string.collections_editor_trakt_sort_released),
        TraktListSort.RUNTIME.value to stringResource(Res.string.collections_editor_trakt_sort_runtime),
        TraktListSort.POPULARITY.value to stringResource(Res.string.collections_editor_trakt_sort_popular),
        TraktListSort.PERCENTAGE.value to stringResource(Res.string.collections_editor_trakt_sort_percentage),
        TraktListSort.VOTES.value to stringResource(Res.string.collections_editor_trakt_sort_votes),
    )

@Composable
private fun traktSortLabel(value: String?): String =
    when (TraktListSort.normalize(value)) {
        TraktListSort.ADDED.value -> stringResource(Res.string.collections_editor_trakt_sort_recently_added)
        TraktListSort.TITLE.value -> stringResource(Res.string.collections_editor_trakt_sort_title)
        TraktListSort.RELEASED.value -> stringResource(Res.string.collections_editor_trakt_sort_released)
        TraktListSort.RUNTIME.value -> stringResource(Res.string.collections_editor_trakt_sort_runtime)
        TraktListSort.POPULARITY.value -> stringResource(Res.string.collections_editor_trakt_sort_popular)
        TraktListSort.PERCENTAGE.value -> stringResource(Res.string.collections_editor_trakt_sort_percentage)
        TraktListSort.VOTES.value -> stringResource(Res.string.collections_editor_trakt_sort_votes)
        else -> stringResource(Res.string.collections_editor_trakt_sort_list_order)
    }

@Composable
private fun traktDirectionLabel(value: String?): String =
    when (TraktSortHow.normalize(value)) {
        TraktSortHow.DESC.value -> stringResource(Res.string.collections_editor_trakt_descending)
        else -> stringResource(Res.string.collections_editor_trakt_ascending)
    }

@Composable
private fun traktSourceSubtitle(source: CollectionSource): String {
    val media = when (TmdbCollectionMediaType.fromString(source.mediaType)) {
        TmdbCollectionMediaType.MOVIE -> stringResource(Res.string.collections_editor_tmdb_movies)
        TmdbCollectionMediaType.TV -> stringResource(Res.string.collections_editor_tmdb_series)
    }
    return listOf(
        media,
        traktSortLabel(source.sortBy),
        traktDirectionLabel(source.sortHow),
        stringResource(Res.string.collections_editor_trakt_list_id_format, source.traktListId ?: ""),
    ).joinToString(" • ")
}

@Composable
private fun tmdbSourceSubtitle(source: CollectionSource): String {
    val media = when (TmdbCollectionMediaType.fromString(source.mediaType)) {
        TmdbCollectionMediaType.MOVIE -> stringResource(Res.string.collections_editor_tmdb_movies)
        TmdbCollectionMediaType.TV -> stringResource(Res.string.collections_editor_tmdb_series)
    }
    val sort = source.sortBy?.let { value ->
        TmdbCollectionSort.entries.firstOrNull { it.value == value }?.let { sort ->
            tmdbSortLabel(sort)
        }
    } ?: stringResource(Res.string.collections_editor_tmdb_sort_popular)
    val sourceType = runCatching {
        TmdbCollectionSourceType.valueOf(source.tmdbSourceType.orEmpty())
    }.getOrDefault(TmdbCollectionSourceType.DISCOVER)
    return when (sourceType) {
        TmdbCollectionSourceType.LIST -> stringResource(Res.string.collections_editor_tmdb_subtitle_list)
        TmdbCollectionSourceType.COLLECTION -> stringResource(Res.string.collections_editor_tmdb_subtitle_movie_collection)
        TmdbCollectionSourceType.COMPANY -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_production),
            media,
            sort,
        ).joinToString(" • ")
        TmdbCollectionSourceType.NETWORK -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_network),
            stringResource(Res.string.collections_editor_tmdb_series),
            sort,
        ).joinToString(" • ")
        TmdbCollectionSourceType.PERSON -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_person),
            media,
            sort,
        ).joinToString(" • ")
        TmdbCollectionSourceType.DIRECTOR -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_director),
            media,
            sort,
        ).joinToString(" • ")
        TmdbCollectionSourceType.DISCOVER -> listOf(
            stringResource(Res.string.collections_editor_tmdb_subtitle_discover),
            media,
            sort,
        ).joinToString(" • ")
    }
}

@Composable
private fun posterShapeLabel(shape: PosterShape): String =
    when (shape) {
        PosterShape.Poster -> stringResource(Res.string.collections_editor_shape_poster)
        PosterShape.Square -> stringResource(Res.string.collections_editor_shape_square)
        PosterShape.Landscape -> stringResource(Res.string.collections_editor_shape_wide)
    }
