package com.nuvio.app.features.settings

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
        description = summary,
        enabled = enabled,
        isTablet = isTablet,
        modifier = modifier,
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
            )
        }
    }
}
