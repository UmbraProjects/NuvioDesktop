package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.search.CONFIDENT_SEARCH_THRESHOLDS
import com.nuvio.app.features.search.ConfidentSearchSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_confident_search
import nuvio.composeapp.generated.resources.settings_confident_search_description
import nuvio.composeapp.generated.resources.settings_confident_search_threshold
import nuvio.composeapp.generated.resources.settings_confident_search_threshold_description
import nuvio.composeapp.generated.resources.settings_homescreen_section_search
import org.jetbrains.compose.resources.stringResource

/** Homescreen page's Search section: confidence-based search on/off and its threshold. */
@Composable
internal fun ConfidentSearchSettingsSection(isTablet: Boolean) {
    // The store is profile-scoped, so read it fresh whenever the page opens.
    LaunchedEffect(Unit) { ConfidentSearchSettingsRepository.snapshot() }
    val settings by ConfidentSearchSettingsRepository.uiState.collectAsStateWithLifecycle()
    SettingsSection(
        title = stringResource(Res.string.settings_homescreen_section_search),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_confident_search),
                description = stringResource(Res.string.settings_confident_search_description),
                checked = settings.enabled,
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("search-confident")),
                onCheckedChange = ConfidentSearchSettingsRepository::setEnabled,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsChoiceRow(
                title = stringResource(Res.string.settings_confident_search_threshold),
                description = stringResource(Res.string.settings_confident_search_threshold_description),
                options = CONFIDENT_SEARCH_THRESHOLDS.map { SettingsChoiceOption(it, "$it%") },
                selectedValue = CONFIDENT_SEARCH_THRESHOLDS.minBy { kotlin.math.abs(it - settings.threshold) },
                enabled = settings.enabled,
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("search-confident-threshold")),
                onSelected = ConfidentSearchSettingsRepository::setThreshold,
            )
        }
    }
}
