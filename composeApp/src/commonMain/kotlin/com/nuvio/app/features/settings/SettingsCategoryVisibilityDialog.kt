package com.nuvio.app.features.settings

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.trackTextInputFocus
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.settings_desktop_categories
import nuvio.composeapp.generated.resources.settings_desktop_categories_dialog_subtitle
import nuvio.composeapp.generated.resources.settings_desktop_categories_rename_hint
import nuvio.composeapp.generated.resources.settings_desktop_categories_show_all
import nuvio.composeapp.generated.resources.settings_desktop_categories_show_configure_icon
import nuvio.composeapp.generated.resources.settings_desktop_categories_show_configure_icon_description
import org.jetbrains.compose.resources.stringResource

// Matches the Discover custom-row editor, the other settings modal built out of full settings
// rows: narrower than this and the 120.dp SettingsRowTextGap squeezes a label like
// "Licenses & attributions" into three lines.
private val CategoryVisibilityDialogWidth = 640.dp
private val CategoryVisibilityDialogMaxContentHeight = 560.dp

/**
 * Lets the user turn individual settings categories off so the desktop sidebar only lists the
 * features this install actually uses, and rename the ones it keeps.
 *
 * [items] is the sidebar's own item list — every category, in its current order, including the
 * hidden ones — so a row that is switched off still has a place to be switched back on. Toggling
 * and renaming write straight through to their repositories; there is no draft state and no
 * confirm step, matching how the rest of settings behaves.
 */
@Composable
internal fun SettingsCategoryVisibilityDialog(
    items: List<DesktopSettingsSidebarItem>,
    onDismiss: () -> Unit,
) {
    val hiddenPages by SettingsHiddenCategoriesRepository.hiddenPages.collectAsStateWithLifecycle()
    val configureIconVisible by SettingsHiddenCategoriesRepository.configureIconVisible
        .collectAsStateWithLifecycle()
    // One row at a time: an editor left open in a row that has scrolled away is an edit the user
    // has forgotten they started.
    var editingPage by remember { mutableStateOf<SettingsPage?>(null) }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_desktop_categories),
        subtitle = stringResource(Res.string.settings_desktop_categories_dialog_subtitle),
        maxWidth = CategoryVisibilityDialogWidth,
        modifier = Modifier.width(CategoryVisibilityDialogWidth),
        actions = {
            TextButton(
                onClick = { SettingsHiddenCategoriesRepository.showAll() },
                enabled = hiddenPages.isNotEmpty(),
            ) { Text(stringResource(Res.string.settings_desktop_categories_show_all)) }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_done)) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The full category list is taller than a short window, so the list scrolls inside
                // the panel rather than pushing the action strip off the bottom of the screen.
                .heightIn(max = CategoryVisibilityDialogMaxContentHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup(isTablet = true) {
                items.forEachIndexed { index, item ->
                    if (index > 0) SettingsGroupDivider(isTablet = true)
                    val editing = editingPage == item.page
                    SettingsSwitchRow(
                        title = item.label,
                        description = if (editing) {
                            stringResource(Res.string.settings_desktop_categories_rename_hint)
                        } else {
                            null
                        },
                        checked = item.page.name !in hiddenPages,
                        icon = item.icon,
                        isTablet = true,
                        titleContent = {
                            EditableCategoryName(
                                label = item.label,
                                editing = editing,
                                onStartEdit = { editingPage = item.page },
                                onCancel = { editingPage = null },
                                onCommit = { name ->
                                    editingPage = null
                                    SettingsCategoryNamesRepository.setName(
                                        page = item.page,
                                        name = name,
                                        defaultLabel = item.defaultLabel,
                                    )
                                },
                            )
                        },
                        onCheckedChange = { visible ->
                            SettingsHiddenCategoriesRepository.setHidden(item.page, !visible)
                        },
                    )
                }
            }
            // Below the list, in its own group: this is about the panel's chrome, not about which
            // categories are listed, and putting it first would push the actual subject of the
            // dialog down the page. Reachable only from in here, which is the point — you can
            // only turn the mark off once you have found what it points at.
            Spacer(modifier = Modifier.height(16.dp))
            SettingsGroup(isTablet = true) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_desktop_categories_show_configure_icon),
                    description = stringResource(
                        Res.string.settings_desktop_categories_show_configure_icon_description,
                    ),
                    checked = configureIconVisible,
                    isTablet = true,
                    onCheckedChange = SettingsHiddenCategoriesRepository::setConfigureIconVisible,
                )
            }
        }
    }
}

/**
 * The category name, editable in place on a double-click.
 *
 * Deliberately not a text field in a box: out of edit mode this is the row's own label, and in it
 * the only thing that changes is a caret, an underline and a selection. Enter and focus loss save,
 * Escape reverts, and an emptied name falls back to the shipped label.
 */
@Composable
private fun EditableCategoryName(
    label: String,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onCancel: () -> Unit,
    onCommit: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = tokens.colors.textPrimary,
        fontWeight = FontWeight.Medium,
    )

    if (!editing) {
        Text(
            text = label,
            modifier = Modifier.pointerInput(label) {
                detectTapGestures(onDoubleTap = { onStartEdit() })
            },
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val focusRequester = remember { FocusRequester() }
    var draft by remember {
        mutableStateOf(TextFieldValue(label, selection = TextRange(0, label.length)))
    }
    // Escape and Enter both take the field out of the composition, which then reports lost focus.
    // Without this latch that second signal would save the draft Escape had just thrown away.
    var settled by remember { mutableStateOf(false) }
    var everFocused by remember { mutableStateOf(false) }

    fun settle(save: Boolean) {
        if (settled) return
        settled = true
        if (save) onCommit(draft.text) else onCancel()
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    BasicTextField(
        value = draft,
        onValueChange = { value -> draft = value.copy(text = value.text.take(SettingsCategoryNameMaxLength)) },
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = tokens.colors.accent.copy(alpha = 0.55f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(bottom = 3.dp)
            .focusRequester(focusRequester)
            // Global single-key shortcuts (G for game mode, S for settings) would otherwise fire
            // while a name is being typed.
            .trackTextInputFocus()
            .onFocusChanged { state ->
                if (state.isFocused) {
                    everFocused = true
                } else if (everFocused) {
                    settle(save = true)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        settle(save = true)
                        true
                    }
                    Key.Escape -> {
                        settle(save = false)
                        true
                    }
                    else -> false
                }
            },
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(tokens.colors.accent),
    )
}
