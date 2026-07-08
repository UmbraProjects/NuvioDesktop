package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.collection.CollectionImportDialog
import com.nuvio.app.features.collection.CollectionReorderableList
import com.nuvio.app.features.collection.CollectionRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_delete
import nuvio.composeapp.generated.resources.collections_copy_json
import nuvio.composeapp.generated.resources.collections_count_summary
import nuvio.composeapp.generated.resources.collections_delete_message
import nuvio.composeapp.generated.resources.collections_delete_title
import nuvio.composeapp.generated.resources.collections_empty_subtitle
import nuvio.composeapp.generated.resources.collections_empty_title
import nuvio.composeapp.generated.resources.collections_header
import nuvio.composeapp.generated.resources.collections_import
import nuvio.composeapp.generated.resources.collections_new
import nuvio.composeapp.generated.resources.collections_your_collections
import org.jetbrains.compose.resources.stringResource

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
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NuvioSurfaceCard {
                Text(
                    text = stringResource(
                        Res.string.collections_count_summary,
                        collections.size,
                        collections.sumOf { it.folders.size },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NuvioPrimaryButton(
                text = stringResource(Res.string.collections_new),
                onClick = { onNavigateToEditor(null) },
            )
            if (collections.isNotEmpty()) {
                NuvioSectionLabel(text = stringResource(Res.string.collections_your_collections))
                CollectionReorderableList(
                    collections = collections,
                    onEdit = { onNavigateToEditor(it) },
                    onDelete = { showDeleteConfirm = it },
                )
            } else {
                NuvioSurfaceCard {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        modifier = Modifier.padding(top = 12.dp),
                        text = stringResource(Res.string.collections_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        modifier = Modifier.padding(top = 8.dp),
                        text = stringResource(Res.string.collections_empty_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
