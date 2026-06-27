package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import com.nuvio.app.features.simkl.SIMKL_CW_DAYS_CAP_ALL
import com.nuvio.app.features.simkl.SimklAuthRepository
import com.nuvio.app.features.simkl.SimklAuthUiState
import com.nuvio.app.features.simkl.SimklConnectionMode
import com.nuvio.app.features.simkl.SimklSettingsRepository
import com.nuvio.app.features.simkl.SimklSettingsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_simkl_connect
import nuvio.composeapp.generated.resources.settings_simkl_connected_as
import nuvio.composeapp.generated.resources.settings_simkl_credentials_clear
import nuvio.composeapp.generated.resources.settings_simkl_credentials_description
import nuvio.composeapp.generated.resources.settings_simkl_credentials_save
import nuvio.composeapp.generated.resources.settings_simkl_credentials_title
import nuvio.composeapp.generated.resources.settings_simkl_client_id
import nuvio.composeapp.generated.resources.settings_simkl_description
import nuvio.composeapp.generated.resources.settings_simkl_disconnect
import nuvio.composeapp.generated.resources.settings_simkl_pin_cancel
import nuvio.composeapp.generated.resources.settings_simkl_pin_instruction
import nuvio.composeapp.generated.resources.settings_simkl_pin_title
import nuvio.composeapp.generated.resources.settings_simkl_section_auth
import nuvio.composeapp.generated.resources.settings_simkl_section_credentials
import nuvio.composeapp.generated.resources.settings_simkl_section_sources
import nuvio.composeapp.generated.resources.settings_simkl_library_source
import nuvio.composeapp.generated.resources.settings_simkl_library_source_desc
import nuvio.composeapp.generated.resources.settings_simkl_cw_source
import nuvio.composeapp.generated.resources.settings_simkl_cw_source_desc
import nuvio.composeapp.generated.resources.settings_simkl_cw_window
import nuvio.composeapp.generated.resources.settings_simkl_cw_window_all
import nuvio.composeapp.generated.resources.settings_simkl_cw_window_days
import nuvio.composeapp.generated.resources.settings_simkl_cw_window_subtitle
import nuvio.composeapp.generated.resources.settings_simkl_calendar_source
import nuvio.composeapp.generated.resources.settings_simkl_calendar_source_desc
import org.jetbrains.compose.resources.stringResource

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
                title = stringResource(Res.string.settings_simkl_section_sources),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_simkl_library_source),
                        description = stringResource(Res.string.settings_simkl_library_source_desc),
                        checked = settingsUiState.simklAsLibrarySource,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("simkl-library-source")),
                        onCheckedChange = SimklSettingsRepository::setAsLibrarySource,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_simkl_cw_source),
                        description = stringResource(Res.string.settings_simkl_cw_source_desc),
                        checked = settingsUiState.simklAsCwSource,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("simkl-continue-watching")),
                        onCheckedChange = SimklSettingsRepository::setAsCwSource,
                    )
                    if (settingsUiState.simklAsCwSource) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SimklCwWindowRow(
                            isTablet = isTablet,
                            daysCap = settingsUiState.simklContinueWatchingDaysCap,
                            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("simkl-continue-watching-window")),
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_simkl_calendar_source),
                        description = stringResource(Res.string.settings_simkl_calendar_source_desc),
                        checked = settingsUiState.simklAsCalendarSource,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("simkl-calendar")),
                        onCheckedChange = SimklSettingsRepository::setAsCalendarSource,
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
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 18.dp else 16.dp
    var clientId by rememberSaveable { mutableStateOf(settingsUiState.simklClientId) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(settingsUiState.simklClientId) {
        clientId = settingsUiState.simklClientId
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_simkl_credentials_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(Res.string.settings_simkl_credentials_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(Res.string.settings_simkl_client_id)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    SimklSettingsRepository.setClientId(clientId)
                    statusMessage = "Saved."
                },
            ) {
                Text(stringResource(Res.string.settings_simkl_credentials_save))
            }
            Button(
                onClick = {
                    clientId = ""
                    SimklSettingsRepository.clearClientId()
                    SimklAuthRepository.onDisconnectRequested()
                    statusMessage = "Cleared."
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(stringResource(Res.string.settings_simkl_credentials_clear))
            }
        }
        statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                        text = "${stringResource(Res.string.settings_simkl_connected_as)} $name",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
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
            Surface(shape = MaterialTheme.shapes.large) {
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
                        style = MaterialTheme.typography.displaySmall,
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

private val SIMKL_CW_DAY_OPTIONS = listOf(14, 30, 60, 90, 180, 365, SIMKL_CW_DAYS_CAP_ALL)

@Composable
private fun simklCwWindowLabel(days: Int): String =
    if (days == SIMKL_CW_DAYS_CAP_ALL) stringResource(Res.string.settings_simkl_cw_window_all)
    else stringResource(Res.string.settings_simkl_cw_window_days, days)

@Composable
private fun SimklCwWindowRow(
    isTablet: Boolean,
    daysCap: Int,
    modifier: Modifier = Modifier,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val label = simklCwWindowLabel(daysCap)
    SettingsNavigationRow(
        title = stringResource(Res.string.settings_simkl_cw_window),
        description = "${stringResource(Res.string.settings_simkl_cw_window_subtitle)} $label",
        isTablet = isTablet,
        modifier = modifier,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        SimklCwWindowDialog(
            selectedDaysCap = daysCap,
            onDaysCapSelected = {
                SimklSettingsRepository.setSimklContinueWatchingDaysCap(it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimklCwWindowDialog(
    selectedDaysCap: Int,
    onDaysCapSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_simkl_cw_window),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.settings_simkl_cw_window_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SIMKL_CW_DAY_OPTIONS.forEach { days ->
                        val selected = days == selectedDaysCap
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDaysCapSelected(days) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selected,
                                onClick = { onDaysCapSelected(days) },
                            )
                            Text(
                                text = simklCwWindowLabel(days),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
