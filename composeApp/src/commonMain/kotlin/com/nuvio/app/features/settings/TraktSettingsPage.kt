package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.nuvio.app.core.ui.NuvioDialogSurface
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktBrandAsset
import com.nuvio.app.features.trakt.TraktAuthUiState
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.MoreLikeThisSourcePreference
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.TraktSettingsUiState
import com.nuvio.app.features.trakt.WatchProgressSource
import com.nuvio.app.features.trakt.traktBrandPainter
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_playback_dialog_close
import nuvio.composeapp.generated.resources.settings_trakt_authentication
import nuvio.composeapp.generated.resources.settings_trakt_comments
import nuvio.composeapp.generated.resources.settings_trakt_comments_description
import nuvio.composeapp.generated.resources.settings_trakt_connect
import nuvio.composeapp.generated.resources.settings_trakt_connected_as
import nuvio.composeapp.generated.resources.settings_trakt_credentials_clear
import nuvio.composeapp.generated.resources.settings_trakt_credentials_cleared
import nuvio.composeapp.generated.resources.settings_trakt_credentials_save
import nuvio.composeapp.generated.resources.settings_trakt_credentials_saved
import nuvio.composeapp.generated.resources.settings_trakt_credentials_title
import nuvio.composeapp.generated.resources.settings_trakt_client_id
import nuvio.composeapp.generated.resources.settings_trakt_default_user
import nuvio.composeapp.generated.resources.settings_trakt_disconnect
import nuvio.composeapp.generated.resources.settings_trakt_failed_open_browser
import nuvio.composeapp.generated.resources.settings_trakt_features
import nuvio.composeapp.generated.resources.settings_trakt_intro_description
import nuvio.composeapp.generated.resources.settings_trakt_missing_credentials
import nuvio.composeapp.generated.resources.settings_trakt_open_login
import nuvio.composeapp.generated.resources.settings_trakt_save_actions_description
import nuvio.composeapp.generated.resources.settings_trakt_sign_in_description
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_dialog_subtitle
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_dialog_title
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_subtitle
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_title
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_tmdb
import nuvio.composeapp.generated.resources.trakt_more_like_this_source_trakt
import nuvio.composeapp.generated.resources.trakt_watch_progress_dialog_subtitle
import nuvio.composeapp.generated.resources.trakt_watch_progress_dialog_title
import nuvio.composeapp.generated.resources.trakt_watch_progress_nuvio_selected
import nuvio.composeapp.generated.resources.trakt_watch_progress_source_nuvio
import nuvio.composeapp.generated.resources.trakt_watch_progress_source_trakt
import nuvio.composeapp.generated.resources.trakt_watch_progress_subtitle
import nuvio.composeapp.generated.resources.trakt_watch_progress_title
import nuvio.composeapp.generated.resources.trakt_watch_progress_trakt_selected
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.accentBrush

internal fun LazyListScope.traktSettingsContent(
    isTablet: Boolean,
    uiState: TraktAuthUiState,
    settingsUiState: TraktSettingsUiState,
    commentsEnabled: Boolean,
    onCommentsEnabledChange: (Boolean) -> Unit,
) {
    item {
        SettingsGroup(isTablet = isTablet) {
            TraktBrandIntro(isTablet = isTablet)
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_trakt_authentication),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("trakt-authentication")),
            ) {
                TraktConnectionCard(
                    isTablet = isTablet,
                    uiState = uiState,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TraktCredentialsCard(
                    isTablet = isTablet,
                    settingsUiState = settingsUiState,
                )
            }
        }
    }

    if (uiState.mode == TraktConnectionMode.CONNECTED) {
        item {
            SettingsSection(
                title = stringResource(Res.string.settings_trakt_features),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    TraktFeatureRows(
                        isTablet = isTablet,
                        settingsUiState = settingsUiState,
                        commentsEnabled = commentsEnabled,
                        onCommentsEnabledChange = onCommentsEnabledChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun TraktFeatureRows(
    isTablet: Boolean,
    settingsUiState: TraktSettingsUiState,
    commentsEnabled: Boolean,
    onCommentsEnabledChange: (Boolean) -> Unit,
) {
    var showMoreLikeThisSourceDialog by rememberSaveable { mutableStateOf(false) }

    val watchProgressValue = watchProgressSourceLabel(settingsUiState.watchProgressSource)
    val moreLikeThisSourceValue = moreLikeThisSourceLabel(settingsUiState.moreLikeThisSource)
    val traktProgressSelectedMessage = stringResource(Res.string.trakt_watch_progress_trakt_selected)
    val nuvioProgressSelectedMessage = stringResource(Res.string.trakt_watch_progress_nuvio_selected)

    SettingsSwitchRow(
        title = stringResource(Res.string.settings_trakt_comments),
        description = stringResource(Res.string.settings_trakt_comments_description),
        checked = commentsEnabled,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("trakt-comments")),
        onCheckedChange = onCommentsEnabledChange,
    )
    SettingsGroupDivider(isTablet = isTablet)
    TraktSettingsActionRow(
        title = stringResource(Res.string.trakt_more_like_this_source_title),
        description = stringResource(Res.string.trakt_more_like_this_source_subtitle),
        value = moreLikeThisSourceValue,
        isTablet = isTablet,
        modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("trakt-more-like-this-source")),
        onClick = { showMoreLikeThisSourceDialog = true },
    )
    if (showMoreLikeThisSourceDialog) {
        MoreLikeThisSourceDialog(
            selectedSource = settingsUiState.moreLikeThisSource,
            onSourceSelected = { source ->
                TraktSettingsRepository.setMoreLikeThisSource(source)
                showMoreLikeThisSourceDialog = false
            },
            onDismiss = { showMoreLikeThisSourceDialog = false },
        )
    }
}

@Composable
private fun TraktSettingsActionRow(
    title: String,
    description: String,
    value: String,
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    val horizontalPadding = if (isTablet) 20.dp else 16.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .widthIn(max = if (isTablet) 560.dp else Dp.Unspecified),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.accentBrush(),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun watchProgressSourceLabel(source: WatchProgressSource): String =
    when (source) {
        WatchProgressSource.TRAKT -> stringResource(Res.string.trakt_watch_progress_source_trakt)
        WatchProgressSource.NUVIO_SYNC -> stringResource(Res.string.trakt_watch_progress_source_nuvio)
    }

@Composable
private fun moreLikeThisSourceLabel(source: MoreLikeThisSourcePreference): String =
    when (source) {
        MoreLikeThisSourcePreference.TRAKT -> stringResource(Res.string.trakt_more_like_this_source_trakt)
        MoreLikeThisSourcePreference.TMDB -> stringResource(Res.string.trakt_more_like_this_source_tmdb)
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WatchProgressSourceDialog(
    selectedSource: WatchProgressSource,
    onSourceSelected: (WatchProgressSource) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.trakt_watch_progress_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.trakt_watch_progress_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(WatchProgressSource.TRAKT, WatchProgressSource.NUVIO_SYNC).forEach { source ->
                        TraktDialogOption(
                            label = watchProgressSourceLabel(source),
                            selected = source == selectedSource,
                            onClick = { onSourceSelected(source) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MoreLikeThisSourceDialog(
    selectedSource: MoreLikeThisSourcePreference,
    onSourceSelected: (MoreLikeThisSourcePreference) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.trakt_more_like_this_source_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.trakt_more_like_this_source_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(MoreLikeThisSourcePreference.TRAKT, MoreLikeThisSourcePreference.TMDB).forEach { source ->
                        TraktDialogOption(
                            label = moreLikeThisSourceLabel(source),
                            selected = source == selectedSource,
                            onClick = { onSourceSelected(source) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(Res.string.settings_playback_dialog_close),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TraktDialogOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TraktBrandIntro(
    isTablet: Boolean,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 18.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Image(
                painter = traktBrandPainter(TraktBrandAsset.Glyph),
                contentDescription = null,
                modifier = Modifier.size(if (isTablet) 84.dp else 72.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = stringResource(Res.string.settings_trakt_intro_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TraktCredentialsCard(
    isTablet: Boolean,
    settingsUiState: TraktSettingsUiState,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Literal string: newly added resource keys do not always resolve (see build notes).
        SettingsTextInputRow(
            title = stringResource(Res.string.settings_trakt_client_id),
            description = if (TraktSettingsRepository.hasBuiltInClientId()) {
                "Optional. Nuvio has its own Trakt app, so you can leave this empty. To use your own, " +
                    "create an app at app.trakt.tv/settings/apps and paste its Client ID here."
            } else {
                "Create an app at app.trakt.tv/settings/apps and paste its Client ID here."
            } + " Nuvio signs in with a device code, so no client secret is needed and the " +
                "app's redirect URI is never used.",
            value = settingsUiState.traktClientId,
            placeholder = stringResource(Res.string.settings_trakt_client_id),
            summarizeAsConfigured = true,
            isTablet = isTablet,
            onSave = { value ->
                TraktSettingsRepository.setClientId(value)
                TraktAuthRepository.onCredentialsChanged()
            },
        )
        if (settingsUiState.traktClientId.isNotBlank()) {
            Button(
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp),
                onClick = {
                    TraktSettingsRepository.clearCredentials()
                    TraktAuthRepository.onCredentialsChanged()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text(stringResource(Res.string.settings_trakt_credentials_clear))
            }
        }
    }
}

@Composable
private fun TraktConnectionCard(
    isTablet: Boolean,
    uiState: TraktAuthUiState,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 18.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (uiState.mode) {
            TraktConnectionMode.CONNECTED -> {
                Text(
                    text = stringResource(
                        Res.string.settings_trakt_connected_as,
                        uiState.username ?: stringResource(Res.string.settings_trakt_default_user),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                if (uiState.needsReconnect) {
                    Text(
                        text = "Trakt is no longer accepting this connection. Connections made before " +
                            "Trakt's October 2026 sign-in change, or removed from your Trakt Connected " +
                            "Apps, need one fresh sign-in. Your lists and history are kept.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.settings_trakt_save_actions_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (uiState.needsReconnect) {
                        Button(
                            onClick = { TraktAuthRepository.onConnectRequested() },
                            enabled = uiState.credentialsConfigured && !uiState.isLoading,
                        ) {
                            Text("Reconnect")
                        }
                    }
                    Button(
                        onClick = { TraktAuthRepository.onDisconnectRequested() },
                        enabled = !uiState.isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(stringResource(Res.string.settings_trakt_disconnect))
                    }
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            TraktConnectionMode.AWAITING_APPROVAL,
            TraktConnectionMode.DISCONNECTED -> {
                Text(
                    text = stringResource(Res.string.settings_trakt_sign_in_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { TraktAuthRepository.onConnectRequested() },
                    enabled = uiState.credentialsConfigured && !uiState.isLoading,
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(stringResource(Res.string.settings_trakt_connect))
                    }
                }
                if (!uiState.credentialsConfigured) {
                    Text(
                        text = stringResource(Res.string.settings_trakt_missing_credentials),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        uiState.statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        uiState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    val userCode = uiState.pendingUserCode
    if (userCode != null) {
        TraktDeviceCodeDialog(
            userCode = userCode,
            verificationUrl = uiState.pendingVerificationUrl ?: "https://auth.trakt.tv/activate",
            expiresAtMillis = uiState.pendingExpiresAtMillis,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TraktDeviceCodeDialog(
    userCode: String,
    verificationUrl: String,
    expiresAtMillis: Long?,
) {
    val uriHandler = LocalUriHandler.current
    val failedOpenBrowserMessage = stringResource(Res.string.settings_trakt_failed_open_browser)
    val openActivationPage = {
        runCatching { uriHandler.openUri(verificationUrl) }
            .onFailure { TraktAuthRepository.onAuthLaunchFailed(it.message ?: failedOpenBrowserMessage) }
        Unit
    }
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(userCode) { mutableStateOf(false) }
    val copyCode = {
        copied = runCatching { clipboardManager.setText(AnnotatedString(userCode)) }.isSuccess
    }
    // A desktop app copies the code and opens the activation page itself, so approving is a paste;
    // the code stays on screen for anyone approving from a phone instead.
    LaunchedEffect(userCode) {
        copyCode()
        openActivationPage()
    }
    var remainingSeconds by remember(expiresAtMillis) { mutableStateOf(secondsUntil(expiresAtMillis)) }
    LaunchedEffect(expiresAtMillis) {
        while (remainingSeconds > 0) {
            delay(1_000)
            remainingSeconds = secondsUntil(expiresAtMillis)
        }
    }

    BasicAlertDialog(onDismissRequest = { TraktAuthRepository.onCancelDeviceFlow() }) {
        NuvioDialogSurface {
            Column(
                modifier = Modifier.padding(24.dp).widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Literal strings: newly added resource keys do not always resolve (see build notes).
                Text(
                    text = "Connect Trakt",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Enter this code at ${verificationUrl.removePrefix("https://")} and approve Nuvio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = userCode,
                    style = MaterialTheme.typography.displaySmall.accentBrush(),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 4.sp,
                )
                val expiryText = expiresAtMillis?.let {
                    val minutes = remainingSeconds / 60
                    val seconds = (remainingSeconds % 60).toString().padStart(2, '0')
                    "Code expires in $minutes:$seconds"
                }
                val footer = listOfNotNull(
                    "Copied to clipboard".takeIf { copied },
                    expiryText,
                ).joinToString(" · ")
                if (footer.isNotEmpty()) {
                    Text(
                        text = footer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = openActivationPage) {
                        Text(stringResource(Res.string.settings_trakt_open_login))
                    }
                    Button(
                        onClick = copyCode,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text("Copy code")
                    }
                }
                CircularProgressIndicator()
                Button(
                    onClick = { TraktAuthRepository.onCancelDeviceFlow() },
                    colors = ButtonDefaults.outlinedButtonColors(),
                ) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        }
    }
}

private fun secondsUntil(epochMs: Long?): Long =
    epochMs?.let { ((it - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L) } ?: 0L
