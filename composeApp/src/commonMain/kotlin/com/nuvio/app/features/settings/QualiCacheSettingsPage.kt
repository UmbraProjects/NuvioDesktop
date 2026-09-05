package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.qualicache.QualiCacheSettings
import com.nuvio.app.features.qualicache.QualiCacheMinimumTrust
import com.nuvio.app.features.qualicache.QualiCacheSettingsRepository
import com.nuvio.app.features.qualicache.QualityBadgeCategory
import com.nuvio.app.features.qualicache.normalizeBaseUrl
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.settings_qualicache_access_key
import nuvio.composeapp.generated.resources.settings_qualicache_access_key_description
import nuvio.composeapp.generated.resources.settings_qualicache_badges
import nuvio.composeapp.generated.resources.settings_qualicache_category_audio
import nuvio.composeapp.generated.resources.settings_qualicache_category_audio_description
import nuvio.composeapp.generated.resources.settings_qualicache_category_dynamic_range
import nuvio.composeapp.generated.resources.settings_qualicache_category_dynamic_range_description
import nuvio.composeapp.generated.resources.settings_qualicache_category_resolution
import nuvio.composeapp.generated.resources.settings_qualicache_category_resolution_description
import nuvio.composeapp.generated.resources.settings_qualicache_missing_server
import nuvio.composeapp.generated.resources.settings_qualicache_minimum_trust
import nuvio.composeapp.generated.resources.settings_qualicache_minimum_trust_description
import nuvio.composeapp.generated.resources.settings_qualicache_trust_high
import nuvio.composeapp.generated.resources.settings_qualicache_trust_low
import nuvio.composeapp.generated.resources.settings_qualicache_trust_medium
import nuvio.composeapp.generated.resources.settings_qualicache_server
import nuvio.composeapp.generated.resources.settings_qualicache_server_address
import nuvio.composeapp.generated.resources.settings_qualicache_server_address_description
import nuvio.composeapp.generated.resources.settings_qualicache_server_url
import nuvio.composeapp.generated.resources.settings_qualicache_show_badges
import nuvio.composeapp.generated.resources.settings_qualicache_show_badges_description
import nuvio.composeapp.generated.resources.settings_qualicache_title
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.NuvioTextField
import androidx.compose.ui.text.input.KeyboardType

internal fun LazyListScope.qualiCacheSettingsContent(
    isTablet: Boolean,
    settings: QualiCacheSettings,
) {
    val displayControlsEnabled = settings.enabled && settings.hasBaseUrl

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_qualicache_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("qualicache-enable")),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_qualicache_show_badges),
                    description = stringResource(Res.string.settings_qualicache_show_badges_description),
                    checked = settings.enabled,
                    enabled = settings.hasBaseUrl,
                    isTablet = isTablet,
                    onCheckedChange = QualiCacheSettingsRepository::setEnabled,
                )
                if (!settings.hasBaseUrl) {
                    SettingsGroupDivider(isTablet = isTablet)
                    QualiCacheInfoRow(
                        isTablet = isTablet,
                        text = stringResource(Res.string.settings_qualicache_missing_server),
                    )
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_qualicache_server),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "qualicache-url",
                    "qualicache-access-key",
                    "qualicache-minimum-trust",
                ),
            ) {
                QualiCacheBaseUrlRow(
                    isTablet = isTablet,
                    value = settings.baseUrl,
                    onCommitted = QualiCacheSettingsRepository::setBaseUrl,
                )
                SettingsGroupDivider(isTablet = isTablet)
                QualiCacheAccessKeyRow(
                    isTablet = isTablet,
                    value = settings.accessKey,
                    onCommitted = QualiCacheSettingsRepository::setAccessKey,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = stringResource(Res.string.settings_qualicache_minimum_trust),
                    description = stringResource(Res.string.settings_qualicache_minimum_trust_description),
                    options = listOf(
                        SettingsChoiceOption(
                            QualiCacheMinimumTrust.HIGH,
                            stringResource(Res.string.settings_qualicache_trust_high),
                        ),
                        SettingsChoiceOption(
                            QualiCacheMinimumTrust.MEDIUM,
                            stringResource(Res.string.settings_qualicache_trust_medium),
                        ),
                        SettingsChoiceOption(
                            QualiCacheMinimumTrust.LOW,
                            stringResource(Res.string.settings_qualicache_trust_low),
                        ),
                    ),
                    selectedValue = settings.minimumTrust,
                    isTablet = isTablet,
                    onSelected = QualiCacheSettingsRepository::setMinimumTrust,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_qualicache_badges),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "qualicache-resolution",
                    "qualicache-dynamic-range",
                    "qualicache-audio",
                ),
            ) {
                CategoryRows(
                    isTablet = isTablet,
                    settings = settings,
                    controlsEnabled = displayControlsEnabled,
                )
            }
        }
    }
}

@Composable
private fun CategoryRows(
    isTablet: Boolean,
    settings: QualiCacheSettings,
    controlsEnabled: Boolean,
) {
    val categories = listOf(
        Triple(
            QualityBadgeCategory.Resolution,
            stringResource(Res.string.settings_qualicache_category_resolution),
            stringResource(Res.string.settings_qualicache_category_resolution_description),
        ),
        Triple(
            QualityBadgeCategory.DynamicRange,
            stringResource(Res.string.settings_qualicache_category_dynamic_range),
            stringResource(Res.string.settings_qualicache_category_dynamic_range_description),
        ),
        Triple(
            QualityBadgeCategory.Audio,
            stringResource(Res.string.settings_qualicache_category_audio),
            stringResource(Res.string.settings_qualicache_category_audio_description),
        ),
    )

    categories.forEachIndexed { index, (category, title, description) ->
        SettingsSwitchRow(
            title = title,
            description = description,
            checked = settings.isCategoryEnabled(category),
            enabled = controlsEnabled,
            isTablet = isTablet,
            onCheckedChange = { checked ->
                QualiCacheSettingsRepository.setCategoryEnabled(category, checked)
            },
        )
        if (index < categories.lastIndex) {
            SettingsGroupDivider(isTablet = isTablet)
        }
    }
}

@Composable
private fun QualiCacheBaseUrlRow(
    isTablet: Boolean,
    value: String,
    onCommitted: (String) -> Unit,
) {
    SettingsTextInputRow(
        title = stringResource(Res.string.settings_qualicache_server_address),
        description = stringResource(Res.string.settings_qualicache_server_address_description),
        value = value,
        placeholder = stringResource(Res.string.settings_qualicache_server_url),
        keyboardType = KeyboardType.Uri,
        normalize = ::normalizeBaseUrl,
        isTablet = isTablet,
        onSave = onCommitted,
    )
}

@Composable
private fun QualiCacheAccessKeyRow(
    isTablet: Boolean,
    value: String,
    onCommitted: (String) -> Unit,
) {
    SettingsTextInputRow(
        title = stringResource(Res.string.settings_qualicache_access_key),
        description = stringResource(Res.string.settings_qualicache_access_key_description),
        value = value,
        placeholder = stringResource(Res.string.settings_qualicache_access_key),
        isTablet = isTablet,
        secret = true,
        onSave = onCommitted,
    )
}

@Composable
private fun QualiCacheInfoRow(
    isTablet: Boolean,
    text: String,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 14.dp else 12.dp

    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
