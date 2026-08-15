package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.ExtraLargePosterCardWidthDp
import com.nuvio.app.core.ui.BalancedPosterCardWidthDp
import com.nuvio.app.core.ui.ComfortPosterCardWidthDp
import com.nuvio.app.core.ui.CompactPosterCardWidthDp
import com.nuvio.app.core.ui.DensePosterCardWidthDp
import com.nuvio.app.core.ui.LargePosterCardWidthDp
import com.nuvio.app.core.ui.StandardPosterCardWidthDp
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.core.ui.PosterRatingBadgeScale
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_reset
import nuvio.composeapp.generated.resources.settings_poster_card_radius
import nuvio.composeapp.generated.resources.settings_poster_card_style
import nuvio.composeapp.generated.resources.settings_poster_card_width
import nuvio.composeapp.generated.resources.settings_poster_description
import nuvio.composeapp.generated.resources.settings_poster_hide_labels
import nuvio.composeapp.generated.resources.settings_poster_collections_portrait
import nuvio.composeapp.generated.resources.settings_poster_collections_portrait_description
import nuvio.composeapp.generated.resources.settings_poster_landscape_mode
import nuvio.composeapp.generated.resources.settings_poster_landscape_rating_badge
import nuvio.composeapp.generated.resources.settings_poster_landscape_rating_badge_description
import nuvio.composeapp.generated.resources.settings_poster_landscape_rating_badge_off
import nuvio.composeapp.generated.resources.settings_poster_landscape_rating_badge_out_of_hundred
import nuvio.composeapp.generated.resources.settings_poster_landscape_rating_badge_out_of_ten
import nuvio.composeapp.generated.resources.settings_poster_landscape_text_titles
import nuvio.composeapp.generated.resources.settings_poster_landscape_text_titles_description
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
import nuvio.composeapp.generated.resources.settings_poster_action_preview
import nuvio.composeapp.generated.resources.settings_poster_action_preview_description
import nuvio.composeapp.generated.resources.settings_poster_card_depth
import nuvio.composeapp.generated.resources.settings_poster_card_depth_apply_to
import nuvio.composeapp.generated.resources.settings_poster_card_depth_cast
import nuvio.composeapp.generated.resources.settings_poster_card_depth_continue_watching
import nuvio.composeapp.generated.resources.settings_poster_card_depth_description
import nuvio.composeapp.generated.resources.settings_poster_card_depth_edge
import nuvio.composeapp.generated.resources.settings_poster_card_depth_edge_coverage
import nuvio.composeapp.generated.resources.settings_poster_card_depth_episodes
import nuvio.composeapp.generated.resources.settings_poster_card_depth_posters
import nuvio.composeapp.generated.resources.settings_poster_card_depth_sheen
import nuvio.composeapp.generated.resources.settings_poster_card_depth_trailers
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
                    collectionsPortraitPostersEnabled = uiState.collectionsPortraitPostersEnabled,
                    landscapeTextTitlesEnabled = uiState.landscapeTextTitlesEnabled,
                    landscapeRatingBadgeScale = uiState.landscapeRatingBadgeScale,
                    hideLabelsEnabled = uiState.hideLabelsEnabled,
                    zoomActionPreviewEnabled = uiState.zoomActionPreviewEnabled,
                    onWidthSelected = PosterCardStyleRepository::setWidthDp,
                    onCornerRadiusSelected = PosterCardStyleRepository::setCornerRadiusDp,
                    onCatalogLandscapeModeChange = PosterCardStyleRepository::setCatalogLandscapeModeEnabled,
                    onCollectionsPortraitPostersChange = PosterCardStyleRepository::setCollectionsPortraitPostersEnabled,
                    onLandscapeTextTitlesChange = PosterCardStyleRepository::setLandscapeTextTitlesEnabled,
                    onLandscapeRatingBadgeScaleChange = PosterCardStyleRepository::setLandscapeRatingBadgeScale,
                    onHideLabelsChange = PosterCardStyleRepository::setHideLabelsEnabled,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_poster_card_depth),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                CardDepthControls(
                    isTablet = isTablet,
                    uiState = uiState,
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
    collectionsPortraitPostersEnabled: Boolean,
    landscapeTextTitlesEnabled: Boolean,
    landscapeRatingBadgeScale: PosterRatingBadgeScale,
    hideLabelsEnabled: Boolean,
    zoomActionPreviewEnabled: Boolean,
    onWidthSelected: (Int) -> Unit,
    onCornerRadiusSelected: (Int) -> Unit,
    onCatalogLandscapeModeChange: (Boolean) -> Unit,
    onCollectionsPortraitPostersChange: (Boolean) -> Unit,
    onLandscapeTextTitlesChange: (Boolean) -> Unit,
    onLandscapeRatingBadgeScaleChange: (PosterRatingBadgeScale) -> Unit,
    onHideLabelsChange: (Boolean) -> Unit,
) {
    val widthOptions = listOf(
        PresetOption(stringResource(Res.string.settings_poster_width_compact), CompactPosterCardWidthDp),
        PresetOption(stringResource(Res.string.settings_poster_width_dense), DensePosterCardWidthDp),
        PresetOption(stringResource(Res.string.settings_poster_width_standard), StandardPosterCardWidthDp),
        PresetOption(stringResource(Res.string.settings_poster_width_balanced), BalancedPosterCardWidthDp),
        PresetOption(stringResource(Res.string.settings_poster_width_comfort), ComfortPosterCardWidthDp),
        PresetOption(stringResource(Res.string.settings_poster_width_large), LargePosterCardWidthDp),
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
            title = stringResource(Res.string.settings_poster_collections_portrait),
            description = stringResource(Res.string.settings_poster_collections_portrait_description),
            checked = collectionsPortraitPostersEnabled,
            enabled = catalogLandscapeModeEnabled,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(
                SettingsScrollAnchor.searchKey("poster-collections-portrait"),
            ),
            onCheckedChange = onCollectionsPortraitPostersChange,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_poster_landscape_text_titles),
            description = stringResource(Res.string.settings_poster_landscape_text_titles_description),
            checked = landscapeTextTitlesEnabled,
            enabled = catalogLandscapeModeEnabled,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(
                SettingsScrollAnchor.searchKey("poster-landscape-text-titles"),
            ),
            onCheckedChange = onLandscapeTextTitlesChange,
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsChoiceRow(
            title = stringResource(Res.string.settings_poster_landscape_rating_badge),
            description = stringResource(Res.string.settings_poster_landscape_rating_badge_description),
            options = ratingBadgeScaleOptions(),
            selectedValue = landscapeRatingBadgeScale,
            enabled = catalogLandscapeModeEnabled,
            isTablet = isTablet,
            modifier = Modifier.settingsScrollAnchor(
                SettingsScrollAnchor.searchKey("poster-landscape-rating-badge"),
            ),
            onSelected = onLandscapeRatingBadgeScaleChange,
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
        SettingsGroupDivider(isTablet = isTablet)
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_poster_action_preview),
            description = stringResource(Res.string.settings_poster_action_preview_description),
            checked = zoomActionPreviewEnabled,
            isTablet = isTablet,
            onCheckedChange = PosterCardStyleRepository::setZoomActionPreviewEnabled,
        )
    }
}

@Composable
private fun ratingBadgeScaleOptions(): List<SettingsChoiceOption<PosterRatingBadgeScale>> = listOf(
    SettingsChoiceOption(
        PosterRatingBadgeScale.Off,
        stringResource(Res.string.settings_poster_landscape_rating_badge_off),
    ),
    SettingsChoiceOption(
        PosterRatingBadgeScale.OutOfTen,
        stringResource(Res.string.settings_poster_landscape_rating_badge_out_of_ten),
    ),
    SettingsChoiceOption(
        PosterRatingBadgeScale.OutOfHundred,
        stringResource(Res.string.settings_poster_landscape_rating_badge_out_of_hundred),
    ),
)

@Composable
internal fun CardDepthControls(
    isTablet: Boolean,
    uiState: PosterCardStyleUiState,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSwitchRow(
            title = stringResource(Res.string.settings_poster_card_depth),
            description = stringResource(Res.string.settings_poster_card_depth_description),
            checked = uiState.depthEnabled,
            isTablet = isTablet,
            onCheckedChange = PosterCardStyleRepository::setDepthEnabled,
        )
        if (uiState.depthEnabled) {
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSliderRow(
                title = stringResource(Res.string.settings_poster_card_depth_edge),
                value = uiState.depthEdgeStrength,
                valueText = "${uiState.depthEdgeStrength}%",
                valueTextForValue = { "$it%" },
                valueRange = 0..100,
                step = 1,
                isTablet = isTablet,
                onValueChange = PosterCardStyleRepository::setDepthEdgeStrength,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSliderRow(
                title = stringResource(Res.string.settings_poster_card_depth_sheen),
                value = uiState.depthSheenStrength,
                valueText = "${uiState.depthSheenStrength}%",
                valueTextForValue = { "$it%" },
                valueRange = 0..100,
                step = 1,
                isTablet = isTablet,
                onValueChange = PosterCardStyleRepository::setDepthSheenStrength,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSliderRow(
                title = stringResource(Res.string.settings_poster_card_depth_edge_coverage),
                value = uiState.depthEdgeCoverage,
                valueText = "${uiState.depthEdgeCoverage}%",
                valueTextForValue = { "$it%" },
                valueRange = 0..100,
                step = 1,
                isTablet = isTablet,
                onValueChange = PosterCardStyleRepository::setDepthEdgeCoverage,
            )
            listOf(
                NuvioCardDepthSurface.Posters to (stringResource(Res.string.settings_poster_card_depth_posters) to uiState.depthPosters),
                NuvioCardDepthSurface.ContinueWatching to (stringResource(Res.string.settings_poster_card_depth_continue_watching) to uiState.depthContinueWatching),
                NuvioCardDepthSurface.Episodes to (stringResource(Res.string.settings_poster_card_depth_episodes) to uiState.depthEpisodes),
                NuvioCardDepthSurface.Cast to (stringResource(Res.string.settings_poster_card_depth_cast) to uiState.depthCast),
                NuvioCardDepthSurface.Trailers to (stringResource(Res.string.settings_poster_card_depth_trailers) to uiState.depthTrailers),
            ).forEach { (surface, option) ->
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_poster_card_depth_apply_to, option.first.lowercase()),
                    description = null,
                    checked = option.second,
                    isTablet = isTablet,
                    onCheckedChange = { PosterCardStyleRepository.setDepthSurfaceEnabled(surface, it) },
                )
            }
        }
    }
}

private data class PresetOption(
    val label: String,
    val value: Int,
)
