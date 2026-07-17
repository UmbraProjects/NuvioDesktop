package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.ReauthenticationTrigger
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.updater.AppUpdaterPlatform
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.compose_auth_sign_in
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile_description
import nuvio.composeapp.generated.resources.settings_advanced_section_startup
import nuvio.composeapp.generated.resources.settings_account_email
import nuvio.composeapp.generated.resources.settings_account_not_signed_in
import nuvio.composeapp.generated.resources.settings_account_sign_out
import nuvio.composeapp.generated.resources.settings_account_sign_out_confirm_message
import nuvio.composeapp.generated.resources.settings_account_sign_out_confirm_title
import nuvio.composeapp.generated.resources.settings_account_status
import nuvio.composeapp.generated.resources.settings_account_status_anonymous
import nuvio.composeapp.generated.resources.settings_account_status_signed_in
import nuvio.composeapp.generated.resources.settings_updates_auto_install
import nuvio.composeapp.generated.resources.settings_updates_auto_install_description
import nuvio.composeapp.generated.resources.settings_updates_section
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.accountSettingsContent(
    isTablet: Boolean,
    rememberLastProfileEnabled: Boolean,
) {
    item {
        AccountSettingsBody(
            isTablet = isTablet,
            rememberLastProfileEnabled = rememberLastProfileEnabled,
        )
    }
}

@Composable
private fun AccountSettingsBody(
    isTablet: Boolean,
    rememberLastProfileEnabled: Boolean,
) {
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSignOutConfirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NuvioSurfaceCard(
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("account-status")),
        ) {
            Text(
                text = stringResource(Res.string.compose_settings_page_account),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(14.dp))

            when (val state = authState) {
                is AuthState.Authenticated -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(Res.string.settings_account_status),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (state.isAnonymous) {
                                stringResource(Res.string.settings_account_status_anonymous)
                            } else {
                                stringResource(Res.string.settings_account_status_signed_in)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (!state.isAnonymous && state.email != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(Res.string.settings_account_email),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = state.email,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        text = stringResource(Res.string.settings_account_not_signed_in),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (authState is AuthState.Authenticated) {
            NuvioPrimaryButton(
                text = stringResource(Res.string.settings_account_sign_out),
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("account-sign-out")),
                onClick = { showSignOutConfirm = true },
            )
        } else {
            NuvioPrimaryButton(
                text = stringResource(Res.string.compose_auth_sign_in),
                onClick = { ReauthenticationTrigger.trigger() },
            )
        }

        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_startup),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_advanced_remember_last_profile),
                    description = stringResource(Res.string.settings_advanced_remember_last_profile_description),
                    checked = rememberLastProfileEnabled,
                    isTablet = isTablet,
                    modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("remember-last-profile")),
                    onCheckedChange = ProfileRepository::setRememberLastProfileEnabled,
                )
            }
        }

        if (AppUpdaterPlatform.isSupported) {
            SettingsSection(
                title = stringResource(Res.string.settings_updates_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    var autoInstall by rememberSaveable {
                        mutableStateOf(AppUpdaterPlatform.isInPlaceUpdateEnabled())
                    }
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_updates_auto_install),
                        description = stringResource(Res.string.settings_updates_auto_install_description),
                        checked = autoInstall,
                        isTablet = isTablet,
                        modifier = Modifier.settingsScrollAnchor(
                            SettingsScrollAnchor.searchKey("auto-install-updates"),
                        ),
                        onCheckedChange = { value ->
                            autoInstall = value
                            AppUpdaterPlatform.setInPlaceUpdateEnabled(value)
                        },
                    )
                }
            }
        }
    }

    NuvioStatusModal(
        title = stringResource(Res.string.settings_account_sign_out_confirm_title),
        message = stringResource(Res.string.settings_account_sign_out_confirm_message),
        isVisible = showSignOutConfirm,
        confirmText = stringResource(Res.string.settings_account_sign_out),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            showSignOutConfirm = false
            scope.launch { AuthRepository.signOut() }
        },
        onDismiss = { showSignOutConfirm = false },
    )
}
