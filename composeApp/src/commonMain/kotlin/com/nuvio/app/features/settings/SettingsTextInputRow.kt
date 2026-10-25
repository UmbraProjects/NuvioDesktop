package com.nuvio.app.features.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioGlass
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.ditheredBackground
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.settings_suggested_value_copied
import nuvio.composeapp.generated.resources.settings_suggested_value_copy
import nuvio.composeapp.generated.resources.settings_suggested_value_use
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioTextField
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_value_configured
import nuvio.composeapp.generated.resources.settings_value_not_set
import org.jetbrains.compose.resources.stringResource

/**
 * A scalar text setting that keeps its editor out of the page until it is needed.
 *
 * Settings pages stay compact and scannable, while the popup gives long values enough room and
 * mirrors the AnimeSkip Client ID interaction.
 */
@Composable
internal fun SettingsTextInputRow(
    title: String,
    description: String,
    value: String,
    placeholder: String,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secret: Boolean = false,
    summarizeAsConfigured: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
    normalize: (String) -> String = String::trim,
    /**
     * Content shown at the trailing edge of the editor's text field, given the current draft and a
     * setter for it — e.g. a colour swatch that fills the field from a picker.
     */
    trailingContent: (@Composable RowScope.(draft: String, onDraftChange: (String) -> Unit) -> Unit)? = null,
    /** Replaces the row's "Configured" / value summary, e.g. to explain why a key was rejected. */
    summaryOverride: String? = null,
    /** In the row's control column; see [SettingsNavigationRow]. */
    rowTrailingContent: (@Composable () -> Unit)? = null,
    /**
     * A known value to offer in the editor (a public server's address or key), shown with Copy
     * and Use buttons. Text in a dialog's description can't be selected, so a value named only
     * there could not be copied at all.
     */
    suggestedValue: SettingsSuggestedValue? = null,
    onSave: (String) -> Unit,
) {
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    val summary = when {
        value.isBlank() -> stringResource(Res.string.settings_value_not_set)
        secret || summarizeAsConfigured -> stringResource(Res.string.settings_value_configured)
        else -> value
    }

    SettingsNavigationRow(
        title = title,
        description = summaryOverride ?: summary,
        enabled = enabled,
        isTablet = isTablet,
        modifier = modifier,
        trailingContent = rowTrailingContent,
        onClick = { dialogVisible = true },
    )

    if (dialogVisible) {
        var draft by rememberSaveable(value) { mutableStateOf(value) }
        val focusRequester = remember { FocusRequester() }
        val saveAndDismiss = {
            onSave(normalize(draft))
            dialogVisible = false
        }
        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
        }
        NuvioModalDialog(
            onDismissRequest = { dialogVisible = false },
            title = title,
            subtitle = description,
            actions = {
                TextButton(onClick = { dialogVisible = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
                TextButton(onClick = saveAndDismiss) {
                    Text(stringResource(Res.string.action_save))
                }
            },
        ) {
            if (suggestedValue != null) {
                SettingsSuggestedValueRow(suggestedValue, onUse = { draft = suggestedValue.value })
            }
            NuvioTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = placeholder,
                secret = secret,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                keyboardType = keyboardType,
                onImeAction = saveAndDismiss.takeIf { singleLine },
                focusRequester = focusRequester,
                trailingContent = trailingContent?.let { content ->
                    { content(draft) { draft = it } }
                },
            )
        }
    }
}

/** See [SettingsTextInputRow]'s suggestedValue. */
internal data class SettingsSuggestedValue(val label: String, val value: String)

@Composable
private fun SettingsSuggestedValueRow(suggestion: SettingsSuggestedValue, onUse: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val clipboard = LocalClipboardManager.current
    val copiedMessage = stringResource(Res.string.settings_suggested_value_copied)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .ditheredBackground(NuvioGlass.Fill, NuvioGlass.Shape)
            .border(1.dp, NuvioGlass.Edge, NuvioGlass.Shape)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = suggestion.label,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            Text(
                text = suggestion.value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(suggestion.value))
            NuvioToastController.show(copiedMessage)
        }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.settings_suggested_value_copy))
        }
        TextButton(onClick = onUse) {
            Text(stringResource(Res.string.settings_suggested_value_use))
        }
    }
}
