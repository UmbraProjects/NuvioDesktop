package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.ExtraLargePosterCardWidthDp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.settings_poster_card_radius
import nuvio.composeapp.generated.resources.settings_poster_card_style
import nuvio.composeapp.generated.resources.settings_poster_card_width
import nuvio.composeapp.generated.resources.settings_poster_description
import nuvio.composeapp.generated.resources.settings_poster_hide_labels
import nuvio.composeapp.generated.resources.settings_poster_landscape_mode
import nuvio.composeapp.generated.resources.settings_poster_radius_classic
import nuvio.composeapp.generated.resources.settings_poster_radius_pill
import nuvio.composeapp.generated.resources.settings_poster_radius_rounded
import nuvio.composeapp.generated.resources.settings_poster_radius_sharp
import nuvio.composeapp.generated.resources.settings_poster_radius_subtle
import nuvio.composeapp.generated.resources.settings_poster_width_balanced
import nuvio.composeapp.generated.resources.settings_poster_width_comfort
import nuvio.composeapp.generated.resources.settings_poster_width_compact
import nuvio.composeapp.generated.resources.settings_poster_width_dense
import nuvio.composeapp.generated.resources.settings_poster_width_extra_large
import nuvio.composeapp.generated.resources.settings_poster_width_large
import nuvio.composeapp.generated.resources.settings_poster_width_standard
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.posterCustomizationSettingsContent(
    isTablet: Boolean,
    uiState: PosterCardStyleUiState,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_poster_card_style),
            isTablet = isTablet,
            actions = {
                NuvioActionLabel(
                    text = stringResource(Res.string.action_reset),
                    onClick = PosterCardStyleRepository::resetToDefaults,
                )
            },
        ) {
            SettingsGroup(isTablet = isTablet) {
                PosterCardStyleControls(
                    isTablet = isTablet,
                    widthDp = uiState.widthDp,
                    cornerRadiusDp = uiState.cornerRadiusDp,
                    catalogLandscapeModeEnabled = uiState.catalogLandscapeModeEnabled,
                    hideLabelsEnabled = uiState.hideLabelsEnabled,
                    onWidthSelected = PosterCardStyleRepository::setWidthDp,
                    onCornerRadiusSelected = PosterCardStyleRepository::setCornerRadiusDp,
                    onCatalogLandscapeModeChange = PosterCardStyleRepository::setCatalogLandscapeModeEnabled,
                    onHideLabelsChange = PosterCardStyleRepository::setHideLabelsEnabled,
                )
            }
        }
    }
}

@Composable
internal fun PosterCardStyleControls(
    isTablet: Boolean,
    widthDp: Int,
    cornerRadiusDp: Int,
    catalogLandscapeModeEnabled: Boolean,
    hideLabelsEnabled: Boolean,
    onWidthSelected: (Int) -> Unit,
    onCornerRadiusSelected: (Int) -> Unit,
    onCatalogLandscapeModeChange: (Boolean) -> Unit,
    onHideLabelsChange: (Boolean) -> Unit,
) {
    val widthOptions = listOf(
        PresetOption(stringResource(Res.string.settings_poster_width_compact), 104),
        PresetOption(stringResource(Res.string.settings_poster_width_dense), 112),
        PresetOption(stringResource(Res.string.settings_poster_width_standard), 120),
        PresetOption(stringResource(Res.string.settings_poster_width_balanced), 126),
        PresetOption(stringResource(Res.string.settings_poster_width_comfort), 134),
        PresetOption(stringResource(Res.string.settings_poster_width_large), 140),
        PresetOption(stringResource(Res.string.settings_poster_width_extra_large), ExtraLargePosterCardWidthDp),
    )
    val radiusOptions = listOf(
        PresetOption(stringResource(Res.string.settings_poster_radius_sharp), 0),
        PresetOption(stringResource(Res.string.settings_poster_radius_subtle), 4),
        PresetOption(stringResource(Res.string.settings_poster_radius_classic), 8),
        PresetOption(stringResource(Res.string.settings_poster_radius_rounded), 12),
        PresetOption(stringResource(Res.string.settings_poster_radius_pill), 16),
    )
    val widthChoiceOptions = widthOptions
        .takeIf { options -> options.any { it.value == widthDp } }
        ?: (widthOptions + PresetOption("${widthDp}dp", widthDp))
    val radiusChoiceOptions = radiusOptions
        .takeIf { options -> options.any { it.value == cornerRadiusDp } }
        ?: (radiusOptions + PresetOption("${cornerRadiusDp}dp", cornerRadiusDp))

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_poster_card_width),
            description = widthChoiceOptions.first { it.value == widthDp }.label,
            options = widthChoiceOptions.map { SettingsChoiceOption(it.value, it.label) },
            selectedValue = widthDp,
            isTablet = isTablet,
            modifier = Modifier
                .settingsScrollAnchor(SettingsScrollAnchor.ExtraLargePosters)
                .settingsScrollAnchor(SettingsScrollAnchor.searchKey("poster-poster-width")),
            onSelected = onWidthSelected,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_poster_card_radius),
            description = radiusChoiceOptions.first { it.value == cornerRadiusDp }.label,
            options = radiusChoiceOptions.map { SettingsChoiceOption(it.value, it.label) },
            selectedValue = cornerRadiusDp,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("poster-poster-radius")),
            onSelected = onCornerRadiusSelected,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_poster_landscape_mode),
            description = null,
            checked = catalogLandscapeModeEnabled,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("poster-poster-landscape")),
            onCheckedChange = onCatalogLandscapeModeChange,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_poster_hide_labels),
            description = null,
            checked = hideLabelsEnabled,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("poster-poster-hide-labels")),
            onCheckedChange = onHideLabelsChange,
        )
    }
}

private data class PresetOption(
    val label: String,
    val value: Int,
)
