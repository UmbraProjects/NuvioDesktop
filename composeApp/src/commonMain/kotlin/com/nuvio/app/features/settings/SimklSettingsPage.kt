package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDialogSurface
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklAuthUiState
import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.simkl.SimklSettingsUiState
import com.nuvio.app.features.simkl.canUseRewatches
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.*
import nuvio.composeapp.generated.resources.settings_simkl_connect
import nuvio.composeapp.generated.resources.settings_simkl_connected_as
import nuvio.composeapp.generated.resources.settings_simkl_credentials_description
import nuvio.composeapp.generated.resources.settings_simkl_client_id
import nuvio.composeapp.generated.resources.settings_simkl_description
import nuvio.composeapp.generated.resources.settings_simkl_disconnect
import nuvio.composeapp.generated.resources.settings_simkl_pin_cancel
import nuvio.composeapp.generated.resources.settings_simkl_pin_instruction
import nuvio.composeapp.generated.resources.settings_simkl_pin_title
import nuvio.composeapp.generated.resources.settings_simkl_section_auth
import nuvio.composeapp.generated.resources.settings_simkl_section_credentials
import nuvio.composeapp.generated.resources.settings_simkl_daily_visit
import nuvio.composeapp.generated.resources.settings_simkl_daily_visit_desc
import nuvio.composeapp.generated.resources.settings_simkl_section_daily_visit
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.accentBrush

internal fun LazyListScope.simklSettingsContent(
    isTablet: Boolean,
    uiState: SimklAuthUiState,
    settingsUiState: SimklSettingsUiState,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_simkl_section_credentials),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SimklCredentialsCard(
                    isTablet = isTablet,
                    settingsUiState = settingsUiState,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("simkl-client-id")),
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_simkl_section_auth),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("simkl-connect")),
            ) {
                SimklConnectionCard(isTablet = isTablet, uiState = uiState)
            }
        }
    }

    if (uiState.mode == SimklConnectionMode.CONNECTED) {
        item {
            SettingsSection(
                title = stringResource(Res.string.settings_simkl_section_rewatches),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_simkl_track_rewatches),
                        description = if (uiState.canUseRewatches) {
                            stringResource(Res.string.settings_simkl_track_rewatches_desc)
                        } else {
                            stringResource(Res.string.settings_simkl_track_rewatches_requires_pro)
                        },
                        checked = settingsUiState.simklTrackRewatches && uiState.canUseRewatches,
                        enabled = uiState.canUseRewatches,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("simkl-rewatches")),
                        onCheckedChange = SimklSettingsRepository::setTrackRewatches,
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(Res.string.settings_simkl_section_daily_visit),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_simkl_daily_visit),
                        description = stringResource(Res.string.settings_simkl_daily_visit_desc),
                        checked = settingsUiState.simklOpenDailyOnStartup,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("simkl-daily-visit")),
                        onCheckedChange = SimklSettingsRepository::setOpenDailyOnStartup,
                    )
                }
            }
        }
    }

}

@Composable
private fun SimklCredentialsCard(
    isTablet: Boolean,
    settingsUiState: SimklSettingsUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        SettingsTextInputRow(
            title = stringResource(Res.string.settings_simkl_client_id),
            description = stringResource(Res.string.settings_simkl_credentials_description),
            value = settingsUiState.simklClientId,
            placeholder = stringResource(Res.string.settings_simkl_client_id),
            summarizeAsConfigured = true,
            isTablet = isTablet,
            onSave = { clientId -> SimklSettingsRepository.setClientId(clientId) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimklConnectionCard(
    isTablet: Boolean,
    uiState: SimklAuthUiState,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 18.dp else 16.dp
    var showPinDialog by rememberSaveable { mutableStateOf(false) }

    if (uiState.mode == SimklConnectionMode.AWAITING_PIN && uiState.pendingPin != null) {
        showPinDialog = true
    }
    if (uiState.mode != SimklConnectionMode.AWAITING_PIN) {
        showPinDialog = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_simkl_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (uiState.mode) {
            SimklConnectionMode.CONNECTED -> {
                uiState.username?.let { name ->
                    Text(
                        text = stringResource(Res.string.settings_simkl_connected_as_format, name),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).accentBrush(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = SimklAuthRepository::onDisconnectRequested,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(Res.string.settings_simkl_disconnect))
                }
            }
            SimklConnectionMode.AWAITING_PIN, SimklConnectionMode.DISCONNECTED -> {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    uiState.errorMessage?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = SimklAuthRepository::onConnectRequested) {
                        Text(stringResource(Res.string.settings_simkl_connect))
                    }
                }
            }
        }
    }

    if (showPinDialog && uiState.pendingPin != null) {
        BasicAlertDialog(onDismissRequest = {
            showPinDialog = false
            SimklAuthRepository.onDisconnectRequested()
        }) {
            NuvioDialogSurface {
                Column(
                    modifier = Modifier.padding(24.dp).widthIn(max = 360.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.settings_simkl_pin_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(Res.string.settings_simkl_pin_instruction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = uiState.pendingPin,
                        style = MaterialTheme.typography.displaySmall.accentBrush(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(
                            8f,
                            androidx.compose.ui.unit.TextUnitType.Sp,
                        ),
                    )
                    val uriHandler = LocalUriHandler.current
                    Button(onClick = { uriHandler.openUri("https://simkl.com/pin") }) {
                        Text("Open simkl.com/pin")
                    }
                    Spacer(Modifier.height(4.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            showPinDialog = false
                            SimklAuthRepository.onDisconnectRequested()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Text(stringResource(Res.string.settings_simkl_pin_cancel))
                    }
                }
            }
        }
    }
}
