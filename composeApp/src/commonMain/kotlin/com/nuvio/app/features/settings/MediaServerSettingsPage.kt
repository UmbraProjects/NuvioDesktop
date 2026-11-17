package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.servers.ServerCapability
import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerFailure
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.message
import com.nuvio.app.features.servers.serverFailure
import com.nuvio.app.features.servers.supports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_show_in_local_library_description
import nuvio.composeapp.generated.resources.servers_show_in_local_library
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.servers_catalog_metadata
import nuvio.composeapp.generated.resources.servers_catalog_metadata_description
import nuvio.composeapp.generated.resources.servers_enabled
import nuvio.composeapp.generated.resources.servers_enabled_description
import nuvio.composeapp.generated.resources.servers_import_watch_state
import nuvio.composeapp.generated.resources.servers_import_watch_state_description
import nuvio.composeapp.generated.resources.servers_libraries
import nuvio.composeapp.generated.resources.servers_libraries_description
import nuvio.composeapp.generated.resources.servers_no_libraries
import nuvio.composeapp.generated.resources.servers_refresh_libraries
import nuvio.composeapp.generated.resources.servers_remove
import nuvio.composeapp.generated.resources.servers_remove_confirm_message
import nuvio.composeapp.generated.resources.servers_remove_confirm_title
import nuvio.composeapp.generated.resources.servers_sign_in_again
import nuvio.composeapp.generated.resources.servers_signed_in_as
import nuvio.composeapp.generated.resources.servers_status_auth
import nuvio.composeapp.generated.resources.servers_status_disabled
import nuvio.composeapp.generated.resources.servers_status_unreachable
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

internal object MediaServerSelection {
    var connectionId by mutableStateOf<String?>(null)
}

internal fun LazyListScope.mediaServerSettingsContent(
    isTablet: Boolean,
    onBack: () -> Unit,
) {
    item {
        val uiState by remember {
            ServerRepository.ensureLoaded()
            ServerRepository.uiState
        }.collectAsStateWithLifecycle()
        val connection = uiState.connections.firstOrNull { it.id == MediaServerSelection.connectionId }
        if (connection == null) {
            LaunchedEffect(Unit) { onBack() }
        } else {
            MediaServerSettingsBody(
                connection = connection,
                failure = uiState.failures[connection.id],
                isTablet = isTablet,
            )
        }
    }
}

@Composable
private fun MediaServerSettingsBody(
    connection: ServerConnection,
    failure: ServerFailure?,
    isTablet: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var refreshing by remember(connection.id) { mutableStateOf(false) }
    var signIn by remember { mutableStateOf<ServerSignInRequest?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val rowPadding = if (isTablet) 20.dp else 16.dp
    val provider = ServerRepository.provider(connection)

    // Laid out like the other settings pages: every group sits in a titled section, so the card
    // layout draws its glass rows and gaps (a loose group fell back to dark divider lines).
    val localLibraryState by remember {
        com.nuvio.app.features.locallibrary.LocalLibraryRepository.ensureLoaded()
        com.nuvio.app.features.locallibrary.LocalLibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap)) {
        SettingsSection(
            title = connection.name,
            isTablet = isTablet,
            subtitle = listOf(connection.identity(), connection.address).joinToString(" · "),
        ) {
            connection.status(failure)?.let { (text, color) ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = rowPadding, vertical = NuvioTokens.Space.s8),
                )
            }
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.servers_enabled),
                    description = stringResource(Res.string.servers_enabled_description),
                    checked = connection.enabled,
                    isTablet = isTablet,
                    onCheckedChange = { ServerRepository.setEnabled(connection.id, it) },
                )
                if (provider?.supports(ServerCapability.EXTERNAL_ID_LOOKUP) == true) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.servers_catalog_metadata),
                        description = stringResource(Res.string.servers_catalog_metadata_description, provider.displayName),
                        checked = connection.useCatalogMetadata,
                        enabled = connection.enabled,
                        isTablet = isTablet,
                        onCheckedChange = { ServerRepository.setCatalogMetadata(connection.id, it) },
                    )
                }
                if (provider?.supports(ServerCapability.USER_STATE_READ) == true) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = stringResource(Res.string.servers_import_watch_state),
                        description = stringResource(Res.string.servers_import_watch_state_description, provider.displayName),
                        checked = connection.importWatchState,
                        enabled = connection.enabled,
                        isTablet = isTablet,
                        onCheckedChange = { ServerRepository.setImportWatchState(connection.id, it) },
                    )
                }
                // Fork: the server titles in the Local Library catalogs (LocalServerLinks).
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.servers_show_in_local_library),
                    description = stringResource(Res.string.servers_show_in_local_library_description),
                    checked = connection.id in localLibraryState.linkedServers,
                    enabled = connection.enabled,
                    isTablet = isTablet,
                    onCheckedChange = {
                        com.nuvio.app.features.locallibrary.LocalLibraryRepository.setServerLinked(connection.id, it)
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.servers_sign_in_again),
                    description = "",
                    isTablet = isTablet,
                    onClick = {
                        signIn = provider?.let { ServerSignInRequest(it, address = connection.address, username = connection.userName) }
                    },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.servers_remove),
                    description = "",
                    isTablet = isTablet,
                    onClick = { confirmRemove = true },
                )
            }
        }

        SettingsSection(
            title = stringResource(Res.string.servers_libraries),
            isTablet = isTablet,
            subtitle = stringResource(
                if (connection.libraries.isEmpty()) Res.string.servers_no_libraries else Res.string.servers_libraries_description,
            ),
        ) {
            SettingsGroup(isTablet = isTablet) {
                connection.libraries.forEachIndexed { index, library ->
                    if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = library.name,
                        checked = library.selected,
                        enabled = connection.enabled,
                        isTablet = isTablet,
                        onCheckedChange = { ServerRepository.setLibrarySelected(connection.id, library.id, it) },
                    )
                }
                if (connection.libraries.isNotEmpty()) SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.servers_refresh_libraries),
                    description = "",
                    enabled = connection.enabled && !refreshing,
                    isTablet = isTablet,
                    trailingContent = if (refreshing) {
                        { NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.sm)) }
                    } else {
                        null
                    },
                    onClick = {
                        refreshing = true
                        scope.launch {
                            runCatching { ServerRepository.refreshLibraries(connection.id) }
                                .onFailure { if (it is CancellationException) throw it }
                                .onFailure { NuvioToastController.show(getString(it.serverFailure().message())) }
                            refreshing = false
                        }
                    },
                )
            }
        }
    }

    NuvioStatusModal(
        title = stringResource(Res.string.servers_remove_confirm_title),
        message = stringResource(Res.string.servers_remove_confirm_message, connection.name),
        isVisible = confirmRemove,
        confirmText = stringResource(Res.string.servers_remove),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            confirmRemove = false
            ServerRepository.remove(connection.id)
        },
        onDismiss = { confirmRemove = false },
    )

    signIn?.let { request ->
        ServerSignInSheet(
            request = request,
            onConnected = { connected ->
                signIn = null
                MediaServerSelection.connectionId = connected.id
            },
            onDismiss = { signIn = null },
        )
    }
}

@Composable
internal fun ServerConnection.identity(): String = stringResource(
    Res.string.servers_signed_in_as,
    ServerRepository.provider(this)?.displayName ?: providerId,
    userName,
)

@Composable
internal fun ServerConnection.status(failure: ServerFailure?): Pair<String, Color>? {
    val colors = MaterialTheme.nuvio.colors
    return when {
        !enabled -> stringResource(Res.string.servers_status_disabled) to colors.textMuted
        failure == ServerFailure.AUTH_REQUIRED -> stringResource(Res.string.servers_status_auth) to colors.danger
        failure == ServerFailure.UNREACHABLE -> stringResource(Res.string.servers_status_unreachable) to colors.warning
        else -> null
    }
}
