package com.nuvio.app.features.games

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSection
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

// The local library's catalog palette, so a row dot and a catalog dot read the same way.
private val ROW_COLORS: List<Long> = listOf(
    0xFFEF5350, 0xFFAB47BC, 0xFF5C6BC0, 0xFF29B6F6,
    0xFF26A69A, 0xFF9CCC65, 0xFFFFCA28, 0xFFFF7043,
)

@Composable
internal actual fun GameRowsSettingsSection(isTablet: Boolean, title: String) {
    val controller = remember { GameLibraryController.shared }
    val rows by controller.rows.collectAsState()
    val games by controller.games.collectAsState()
    var showAddBox by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val tokens = MaterialTheme.nuvio
    // Counted the way the shelf files them, so a game pinned to a since-deleted row is counted
    // under its bucket, not lost.
    val counts = remember(games, rows) {
        games.groupingBy { it.effectiveRowId(rows) }.eachCount()
    }

    SettingsSection(
        title = title,
        isTablet = isTablet,
        actions = {
            NuvioActionLabel(
                text = stringResource(Res.string.games_rows_add),
                onClick = { showAddBox = !showAddBox },
            )
        },
    ) {
        SettingsGroup(isTablet = isTablet) {
            Text(
                text = stringResource(Res.string.games_rows_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            SettingsGroupDivider(isTablet = isTablet)
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
                        placeholder = stringResource(Res.string.games_rows_new_row_name),
                    )
                    NuvioActionLabel(
                        text = stringResource(Res.string.games_rows_add),
                        onClick = if (newName.isBlank()) {
                            null
                        } else {
                            {
                                controller.addRow(newName)
                                newName = ""
                                showAddBox = false
                                // Release focus so the input tracker drops the shortcut lock.
                                focusManager.clearFocus()
                            }
                        },
                        modifier = Modifier.alpha(if (newName.isBlank()) tokens.opacity.medium else 1f),
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                if (editingId == row.id) {
                    GameRowEditRow(
                        row = row,
                        editName = editName,
                        onEditNameChange = { editName = it },
                        onSave = {
                            controller.renameRow(row.id, editName)
                            editingId = null
                            focusManager.clearFocus()
                        },
                        onCancel = {
                            editingId = null
                            focusManager.clearFocus()
                        },
                        onPickColor = { controller.setRowColor(row.id, it) },
                    )
                } else {
                    GameRowRow(
                        row = row,
                        gameCount = counts[row.id] ?: 0,
                        onMoveUp = if (index > 0) ({ controller.moveRow(row.id, -1) }) else null,
                        onMoveDown = if (index < rows.lastIndex) ({ controller.moveRow(row.id, 1) }) else null,
                        onStartEdit = {
                            editingId = row.id
                            editName = row.name
                        },
                        onRemove = if (row.defaultBucket == null) ({ controller.removeRow(row.id) }) else null,
                    )
                }
            }
        }
    }
}

/** A colour dot, the row name, its game count, reorder arrows, rename and (user rows) remove. */
@Composable
private fun GameRowRow(
    row: GameRow,
    gameCount: Int,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onStartEdit: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onStartEdit)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(row.color?.let { Color(it) } ?: tokens.colors.textMuted),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(if (gameCount == 1) "1 game" else "$gameCount games")
                    when (row.defaultBucket) {
                        GameRowBucket.Installed -> append("  •  default for games with an executable")
                        GameRowBucket.Uninstalled -> append("  •  default for games without one")
                        null -> Unit
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Both arrows always take their slot, disabled at either end, so the pens stay flush.
        IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
            Icon(
                Icons.Rounded.KeyboardArrowUp,
                contentDescription = stringResource(Res.string.games_rows_move_up),
                tint = if (onMoveUp != null) tokens.colors.textMuted else tokens.colors.textDisabled,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.games_rows_move_down),
                tint = if (onMoveDown != null) tokens.colors.textMuted else tokens.colors.textDisabled,
                modifier = Modifier.size(20.dp),
            )
        }
        // Remove sits inside the pen's slot and only user-made rows offer it, so the column of
        // pens stays flush at the edge whether or not a row can be deleted.
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_remove),
                    tint = tokens.colors.danger,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onStartEdit) {
            Icon(
                Icons.Rounded.Edit,
                contentDescription = stringResource(Res.string.games_rows_rename),
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The same row while renaming: the field replaces the name and the colour swatches sit under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameRowEditRow(
    row: GameRow,
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
                    .background(row.color?.let { Color(it) } ?: tokens.colors.textMuted),
            )
            Spacer(Modifier.width(12.dp))
            NuvioTextField(
                value = editName,
                onValueChange = onEditNameChange,
                modifier = Modifier.weight(1f),
                onImeAction = { if (editName.isNotBlank()) onSave() },
            )
            IconButton(onClick = onSave, enabled = editName.isNotBlank()) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = stringResource(Res.string.action_save),
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.action_cancel),
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
            // A neutral dot first, so a colour can be taken back off a row.
            RowColorSwatch(color = null, selected = row.color == null, onClick = { onPickColor(null) })
            ROW_COLORS.forEach { color ->
                RowColorSwatch(color = color, selected = row.color == color, onClick = { onPickColor(color) })
            }
        }
    }
}

@Composable
private fun RowColorSwatch(color: Long?, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(color?.let { Color(it) } ?: tokens.colors.textMuted.copy(alpha = 0.35f))
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
