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
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
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

    // One settings row: the account state as title and description, the action in the control
    // column where every other row keeps its switch or dropdown.
    val showsConnection = uiState.mode == SimklConnectionMode.CONNECTED || uiState.isReconnecting
    // Literal strings: newly added resource keys do not always resolve (see build notes).
    val reconnectReason = when {
        !showsConnection -> null
        uiState.needsReconnect ->
            "SIMKL is no longer accepting this connection. It may have been removed from your " +
                "SIMKL Connected Apps, or gone unused for six months. Your history and lists are " +
                "kept; reconnect to resume syncing."
        uiState.isLegacyConnection ->
            "Connected with SIMKL's older sign-in method, which SIMKL is retiring around April 2027. " +
                "Paste the Client ID of a new SIMKL app above, then reconnect once. Sign-in opens " +
                "simkl.com in your browser, so Nuvio never sees your password, and your watch " +
                "history stays where it is. Everything keeps working until you do."
        else -> null
    }
    val description = listOfNotNull(
        reconnectReason ?: stringResource(Res.string.settings_simkl_description),
        uiState.errorMessage,
    ).joinToString(" ")
    SettingsActionRow(
        title = if (showsConnection) {
            uiState.username?.let { stringResource(Res.string.settings_simkl_connected_as_format, it) } ?: "Connected"
        } else {
            "Not connected"
        },
        description = description,
        descriptionColor = if (uiState.needsReconnect || uiState.errorMessage != null) {
            MaterialTheme.colorScheme.error
        } else {
            null
        },
        isTablet = isTablet,
    ) {
        if (showsConnection) {
            if (reconnectReason != null) {
                SettingsGlassButton(
                    text = "Reconnect",
                    onClick = SimklAuthRepository::onConnectRequested,
                    accent = true,
                    loading = uiState.isLoading,
                )
            }
            SettingsGlassButton(
                text = stringResource(Res.string.settings_simkl_disconnect),
                onClick = SimklAuthRepository::onDisconnectRequested,
            )
        } else {
            SettingsGlassButton(
                text = stringResource(Res.string.settings_simkl_connect),
                onClick = SimklAuthRepository::onConnectRequested,
                accent = true,
                loading = uiState.isLoading,
            )
        }
    }

    if (showPinDialog && uiState.pendingPin != null) {
        val uriHandler = LocalUriHandler.current
        val verificationUrl = uiState.pendingVerificationUrl ?: "https://simkl.com/pin"
        // A desktop app opens the pre-filled approval page itself; the code stays on screen for
        // anyone approving from a phone instead.
        LaunchedEffect(uiState.pendingPin) {
            runCatching { uriHandler.openUri(verificationUrl) }
        }
        var remainingSeconds by remember(uiState.pendingExpiresAtMs) {
            mutableStateOf(secondsUntil(uiState.pendingExpiresAtMs))
        }
        LaunchedEffect(uiState.pendingExpiresAtMs) {
            while (remainingSeconds > 0) {
                delay(1_000)
                remainingSeconds = secondsUntil(uiState.pendingExpiresAtMs)
            }
        }
        BasicAlertDialog(onDismissRequest = {
            showPinDialog = false
            SimklAuthRepository.onConnectCancelled()
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
                    // Shown exactly as issued, hyphen included.
                    Text(
                        text = uiState.pendingPin,
                        style = MaterialTheme.typography.displaySmall.accentBrush(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(
                            4f,
                            androidx.compose.ui.unit.TextUnitType.Sp,
                        ),
                    )
                    if (uiState.pendingExpiresAtMs != null) {
                        val minutes = remainingSeconds / 60
                        val seconds = (remainingSeconds % 60).toString().padStart(2, '0')
                        Text(
                            text = "Code expires in $minutes:$seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { uriHandler.openUri(verificationUrl) }) {
                        Text("Open simkl.com")
                    }
                    Spacer(Modifier.height(4.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            showPinDialog = false
                            SimklAuthRepository.onConnectCancelled()
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

private fun secondsUntil(epochMs: Long?): Long =
    epochMs?.let { ((it - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L) } ?: 0L
