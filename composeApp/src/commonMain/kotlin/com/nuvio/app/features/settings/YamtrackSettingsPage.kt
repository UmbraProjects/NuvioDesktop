package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.yamtrack.YamtrackConnectionState
import com.nuvio.app.features.yamtrack.YamtrackSettings
import com.nuvio.app.features.yamtrack.YamtrackSettingsRepository
import com.nuvio.app.features.yamtrack.YamtrackTrackingAuthProvider
import com.nuvio.app.features.yamtrack.isInsecureRemoteBaseUrl
import com.nuvio.app.features.yamtrack.normalizeBaseUrl
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.compose_settings_page_yamtrack
import nuvio.composeapp.generated.resources.settings_yamtrack_add_credentials_first
import nuvio.composeapp.generated.resources.settings_yamtrack_enable
import nuvio.composeapp.generated.resources.settings_yamtrack_enable_description
import nuvio.composeapp.generated.resources.settings_yamtrack_insecure_warning
import nuvio.composeapp.generated.resources.settings_yamtrack_section_connection
import nuvio.composeapp.generated.resources.settings_yamtrack_state_connected
import nuvio.composeapp.generated.resources.settings_yamtrack_state_connected_version
import nuvio.composeapp.generated.resources.settings_yamtrack_state_testing
import nuvio.composeapp.generated.resources.settings_yamtrack_state_unauthorized
import nuvio.composeapp.generated.resources.settings_yamtrack_state_unknown
import nuvio.composeapp.generated.resources.settings_yamtrack_state_unreachable
import nuvio.composeapp.generated.resources.settings_yamtrack_state_unreachable_detail
import nuvio.composeapp.generated.resources.settings_yamtrack_test_connection
import nuvio.composeapp.generated.resources.settings_yamtrack_token_description
import nuvio.composeapp.generated.resources.settings_yamtrack_token_label
import nuvio.composeapp.generated.resources.settings_yamtrack_token_title
import nuvio.composeapp.generated.resources.settings_yamtrack_url_description
import nuvio.composeapp.generated.resources.settings_yamtrack_url_label
import nuvio.composeapp.generated.resources.settings_yamtrack_url_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.yamtrackSettingsContent(
    isTablet: Boolean,
    settings: YamtrackSettings,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.compose_settings_page_yamtrack),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("yamtrack-enable")),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_yamtrack_enable),
                    description = stringResource(Res.string.settings_yamtrack_enable_description),
                    checked = settings.enabled,
                    enabled = settings.hasCredentials,
                    isTablet = isTablet,
                    onCheckedChange = YamtrackSettingsRepository::setEnabled,
                )
                if (!settings.hasCredentials) {
                    SettingsGroupDivider(isTablet = isTablet)
                    YamtrackInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_yamtrack_add_credentials_first),
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_yamtrack_section_connection),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("yamtrack-connection")),
            ) {
                YamtrackConnectionRows(isTablet = isTablet, settings = settings)
            }
        }
    }
}

@Composable
private fun YamtrackConnectionRows(
    isTablet: Boolean,
    settings: YamtrackSettings,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    val scope = rememberCoroutineScope()

    var urlDraft by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var tokenDraft by rememberSaveable(settings.apiToken) { mutableStateOf(settings.apiToken) }
    val normalizedUrlDraft = normalizeBaseUrl(urlDraft)
    val normalizedTokenDraft = tokenDraft.trim()
    val hasPendingEdits =
        normalizedUrlDraft != settings.baseUrl || normalizedTokenDraft != settings.apiToken

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsFieldLabel(
            title = stringResource(Res.string.settings_yamtrack_url_title),
            description = stringResource(Res.string.settings_yamtrack_url_description),
        )
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            singleLine = true,
            label = { Text(stringResource(Res.string.settings_yamtrack_url_label)) },
            // Without this a URL containing "h" would fire the Home shortcut mid-typing, the same
            // reason SettingsSecretTextField applies it internally.
            modifier = Modifier.fillMaxWidth().trackSettingsTextFocus(),
        )

        SettingsFieldLabel(
            title = stringResource(Res.string.settings_yamtrack_token_title),
            description = stringResource(Res.string.settings_yamtrack_token_description),
        )
        SettingsSecretTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            label = stringResource(Res.string.settings_yamtrack_token_label),
            modifier = Modifier.fillMaxWidth(),
        )

        if (isInsecureRemoteBaseUrl(settings.baseUrl)) {
            Text(
                text = stringResource(Res.string.settings_yamtrack_insecure_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            text = settings.connectionState.describe(),
            style = MaterialTheme.typography.bodySmall,
            // The theme's primary is the brand red, which reads as a failure on a success message,
            // so only genuine failures are coloured.
            color = when (settings.connectionState) {
                is YamtrackConnectionState.Unauthorized,
                is YamtrackConnectionState.Unreachable,
                -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    urlDraft = normalizedUrlDraft
                    tokenDraft = normalizedTokenDraft
                    YamtrackSettingsRepository.setBaseUrl(normalizedUrlDraft)
                    YamtrackSettingsRepository.setApiToken(normalizedTokenDraft)
                },
                enabled = hasPendingEdits,
            ) { Text(stringResource(Res.string.action_save)) }

            Button(
                onClick = {
                    scope.launch {
                        YamtrackTrackingAuthProvider.testConnection(
                            baseUrl = settings.baseUrl,
                            apiToken = settings.apiToken,
                        )
                    }
                },
                // Testing the saved credentials, so unsaved edits would report a stale result.
                enabled = settings.hasCredentials &&
                    !hasPendingEdits &&
                    settings.connectionState !is YamtrackConnectionState.Testing,
            ) { Text(stringResource(Res.string.settings_yamtrack_test_connection)) }
        }
    }
}

@Composable
private fun SettingsFieldLabel(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun YamtrackConnectionState.describe(): String = when (this) {
    is YamtrackConnectionState.Unknown -> stringResource(Res.string.settings_yamtrack_state_unknown)
    is YamtrackConnectionState.Testing -> stringResource(Res.string.settings_yamtrack_state_testing)
    is YamtrackConnectionState.Connected -> version
        ?.let { stringResource(Res.string.settings_yamtrack_state_connected_version, it) }
        ?: stringResource(Res.string.settings_yamtrack_state_connected)
    is YamtrackConnectionState.Unauthorized ->
        stringResource(Res.string.settings_yamtrack_state_unauthorized)
    is YamtrackConnectionState.Unreachable -> detail
        ?.let { stringResource(Res.string.settings_yamtrack_state_unreachable_detail, it) }
        ?: stringResource(Res.string.settings_yamtrack_state_unreachable)
}

@Composable
private fun YamtrackInfoRow(isTablet: Boolean, text: String) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    )
}
