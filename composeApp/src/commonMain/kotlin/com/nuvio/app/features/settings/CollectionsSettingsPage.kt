package com.nuvio.app.features.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionImportDialog
import com.nuvio.app.features.collection.CollectionRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_delete
import nuvio.composeapp.generated.resources.action_edit
import nuvio.composeapp.generated.resources.collections_copy_json
import nuvio.composeapp.generated.resources.collections_count_summary
import nuvio.composeapp.generated.resources.collections_delete_message
import nuvio.composeapp.generated.resources.collections_delete_title
import nuvio.composeapp.generated.resources.collections_empty_subtitle
import nuvio.composeapp.generated.resources.collections_empty_title
import nuvio.composeapp.generated.resources.collections_folder_count
import nuvio.composeapp.generated.resources.collections_header
import nuvio.composeapp.generated.resources.collections_import
import nuvio.composeapp.generated.resources.collections_new
import nuvio.composeapp.generated.resources.collections_pinned
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableColumn

internal fun LazyListScope.collectionsSettingsContent(
    isTablet: Boolean,
    onNavigateToEditor: (String?) -> Unit,
) {
    item {
        CollectionsSettingsSection(isTablet = isTablet, onNavigateToEditor = onNavigateToEditor)
    }
}

@Composable
private fun CollectionsSettingsSection(
    isTablet: Boolean,
    onNavigateToEditor: (String?) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val collections by CollectionRepository.collections.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    SettingsSection(
        title = stringResource(Res.string.collections_header),
        isTablet = isTablet,
        subtitle = stringResource(
            Res.string.collections_count_summary,
            collections.size,
            collections.sumOf { it.folders.size },
        ),
        actions = {
            IconButton(onClick = {
                clipboardManager.setText(AnnotatedString(CollectionRepository.exportToJson()))
            }) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(Res.string.collections_copy_json),
                    tint = tokens.colors.textMuted,
                )
            }
            IconButton(onClick = { showImportDialog = true }) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = stringResource(Res.string.collections_import),
                    tint = tokens.colors.textMuted,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            NuvioActionLabel(
                text = stringResource(Res.string.collections_new),
                onClick = { onNavigateToEditor(null) },
            )
        },
    ) {
        // Laid out like the Homescreen catalog section: the summary as the heading's subtitle,
        // then one divider-separated row per collection, dragged by the row itself.
        if (collections.isNotEmpty()) {
            CollectionSettingsList(
                collections = collections,
                isTablet = isTablet,
                onEdit = { onNavigateToEditor(it) },
                onDelete = { showDeleteConfirm = it },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = tokens.colors.textMuted.copy(alpha = tokens.opacity.medium),
                )
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = stringResource(Res.string.collections_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringResource(Res.string.collections_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }

    if (showImportDialog) {
        CollectionImportDialog(
            importText = importText,
            importError = importError,
            onTextChange = {
                importText = it
                importError = null
            },
            onConfirm = {
                val result = CollectionRepository.validateJson(importText)
                if (result.valid) {
                    CollectionRepository.importFromJson(importText)
                    showImportDialog = false
                    importText = ""
                    importError = null
                } else {
                    importError = result.error
                }
            },
            onDismiss = {
                showImportDialog = false
                importText = ""
                importError = null
            },
        )
    }

    val deleteId = showDeleteConfirm
    val deleteCollection = deleteId?.let { id -> collections.find { it.id == id } }
    NuvioStatusModal(
        title = stringResource(Res.string.collections_delete_title),
        message = stringResource(Res.string.collections_delete_message, deleteCollection?.title.orEmpty()),
        isVisible = deleteId != null,
        confirmText = stringResource(Res.string.action_delete),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            if (deleteId != null) {
                CollectionRepository.removeCollection(deleteId)
            }
            showDeleteConfirm = null
        },
        onDismiss = { showDeleteConfirm = null },
    )
}

@Composable
private fun CollectionSettingsList(
    collections: List<Collection>,
    isTablet: Boolean,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    // This list lives inside the settings page's own LazyColumn. A nested reorderable LazyColumn
    // creates a second edge auto-scroller; dragging the first row enters both top zones and sends
    // the page racing upward. A regular reorderable Column delegates all scrolling to the page.
    ReorderableColumn(
        list = collections,
        onSettle = { fromIndex, toIndex ->
            CollectionRepository.moveByIndex(fromIndex, toIndex)
        },
        onMove = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { index, collection, isDragging ->
        key(collection.id) {
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
                        if (index > 0) {
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        CollectionSettingsRow(
                            collection = collection,
                            isTablet = isTablet,
                            onEdit = { onEdit(collection.id) },
                            onDelete = { onDelete(collection.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Mirrors [HomescreenCatalogRow]: title over a muted summary, controls on the right. */
@Composable
private fun CollectionSettingsRow(
    collection: Collection,
    isTablet: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val summary = buildString {
        append(stringResource(Res.string.collections_folder_count, collection.folders.size))
        if (collection.pinToTop) {
            append(" \u2022 ")
            append(stringResource(Res.string.collections_pinned))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 14.dp else 12.dp,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = if (isTablet) SettingsRowTextGap else 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = collection.title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(Res.string.action_edit),
                    tint = tokens.colors.textMuted,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(Res.string.action_delete),
                    tint = tokens.colors.danger,
                )
            }
        }
    }
}
