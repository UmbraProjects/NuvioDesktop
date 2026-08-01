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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.qualicache.QualiCacheSettings
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
import nuvio.composeapp.generated.resources.settings_qualicache_server
import nuvio.composeapp.generated.resources.settings_qualicache_server_address
import nuvio.composeapp.generated.resources.settings_qualicache_server_address_description
import nuvio.composeapp.generated.resources.settings_qualicache_server_url
import nuvio.composeapp.generated.resources.settings_qualicache_show_badges
import nuvio.composeapp.generated.resources.settings_qualicache_show_badges_description
import nuvio.composeapp.generated.resources.settings_qualicache_title
import org.jetbrains.compose.resources.stringResource

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
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    // Compare against the same normalisation the repository will apply, so the Save button does not
    // stay enabled after committing a value that only differed by a trailing slash or missing scheme.
    val normalizedDraft = normalizeBaseUrl(draft)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_qualicache_server_address),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.settings_qualicache_server_address_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            // Same reason as SettingsSecretTextField: without this a URL containing "h" would
            // trigger the Home hotkey mid-typing.
            modifier = Modifier
                .fillMaxWidth()
                .trackSettingsTextFocus(),
            singleLine = true,
            label = { Text(stringResource(Res.string.settings_qualicache_server_url)) },
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    draft = normalizedDraft
                    onCommitted(normalizedDraft)
                },
                enabled = normalizedDraft != value,
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

@Composable
private fun QualiCacheAccessKeyRow(
    isTablet: Boolean,
    value: String,
    onCommitted: (String) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val normalizedDraft = draft.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_qualicache_access_key),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.settings_qualicache_access_key_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSecretTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(Res.string.settings_qualicache_access_key),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    draft = normalizedDraft
                    onCommitted(normalizedDraft)
                },
                enabled = normalizedDraft != value,
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
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
