package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioTextField

/**
 * A secret [NuvioTextField], kept as its own name because "this holds an API key" is worth
 * saying at the call site. The old outlined-field styling now lives in [NuvioTextField]; the
 * `label` that used to float through the border became the placeholder, since every one of these
 * sits under a row title that already names the field.
 */
@Composable
internal fun SettingsSecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true,
    supportingText: String? = null,
    onDone: (() -> Unit)? = null,
) {
    NuvioTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = label,
        enabled = enabled,
        isError = isError,
        secret = true,
        supportingText = supportingText,
        onImeAction = onDone,
    )
}
